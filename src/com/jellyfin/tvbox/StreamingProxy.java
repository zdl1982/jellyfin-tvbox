package com.jellyfin.tvbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * Local HTTP proxy that lets Android 4's MediaPlayer play HTTPS video.
 *
 * Android 4.x MediaPlayer uses its own native HTTP stack (libstagefright)
 * which does NOT honour the Java-side TLS 1.2 settings we set in TlsHelper.
 * So playing an HTTPS stream URL directly fails with SSL handshake errors.
 *
 * This proxy runs a plain-HTTP server on 127.0.0.1, forwards each video
 * request to the real Jellyfin HTTPS stream URL using HttpURLConnection
 * (which *does* use our TLS 1.2 factory), and pipes the bytes back.
 * MediaPlayer talks plain HTTP to localhost, which always works.
 *
 * Uses a multi-threaded producer-consumer model with a BlockingQueue to
 * decouple upstream reads from downstream writes. This prevents the player
 * from stalling when the upstream (Jellyfin transcode) is temporarily slow.
 */
public class StreamingProxy {

    private static final int PORT = 18080;
    private static final int CHUNK_SIZE = 65536;       // 64 KB per chunk
    private static final int QUEUE_CAPACITY = 64;       // 64 chunks = 4 MB max buffer
    private static final byte[] END_SENTINEL = new byte[0]; // signals end of stream

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private AtomicBoolean running = new AtomicBoolean(false);
    private JellyfinClient jfClient;

    public StreamingProxy(JellyfinClient client) {
        this.jfClient = client;
    }

    /** Start the proxy. Returns true if started. */
    public boolean start() {
        if (running.get()) return true;
        try {
            serverSocket = new ServerSocket(PORT, 10, java.net.InetAddress.getByName("127.0.0.1"));
            running.set(true);
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() { acceptLoop(); }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
    }

    /** Build the local URL that MediaPlayer should play. */
    public String getLocalUrl(String itemId) {
        return "http://127.0.0.1:" + PORT + "/stream/" + itemId;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(120000);
                handleClient(client);
            } catch (Exception e) {
                // socket closed
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Read request line
            String requestLine = readLine(in);
            if (requestLine == null) { client.close(); return; }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            // Read headers
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(),
                                line.substring(idx + 1).trim());
                }
            }

            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendSimple(out, "405 Method Not Allowed", "text/plain", null);
                client.close();
                return;
            }

            // Parse item id from path: /stream/<itemId>[?q=low]
            String itemId = null;
            boolean lowQuality = false;
            if (path != null && path.startsWith("/stream/")) {
                itemId = path.substring("/stream/".length());
                int q = itemId.indexOf('?');
                if (q >= 0) {
                    String query = itemId.substring(q + 1);
                    itemId = itemId.substring(0, q);
                    lowQuality = query.contains("q=low");
                }
            }
            if (itemId == null || itemId.isEmpty()) {
                sendSimple(out, "400 Bad Request", "text/plain", "missing item id");
                client.close();
                return;
            }

            // Build upstream URL: transcoded flavor if low quality requested
            String upstream = lowQuality
                ? jfClient.getTranscodedStreamUrl(itemId)
                : jfClient.getStreamUrl(itemId);

            // Open upstream connection (uses TlsHelper TLS 1.2)
            HttpURLConnection conn = (HttpURLConnection) new URL(upstream).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("X-Emby-Token", jfClient.getToken());
            conn.setRequestProperty("User-Agent", "Jellyfin-TVBox/1.0");

            // Forward Range header for seeking
            String range = headers.get("range");
            if (range != null) {
                conn.setRequestProperty("Range", range);
            }

            int code = conn.getResponseCode();
            if (code == 200 || code == 206 || code == 202) {
                final InputStream upstreamIn = conn.getInputStream();
                final boolean headOnly = "HEAD".equalsIgnoreCase(method);

                // Send response headers
                StringBuilder resp = new StringBuilder();
                resp.append("HTTP/1.1 ").append(code).append(" ").append(statusText(code)).append("\r\n");
                resp.append("Content-Type: ").append(conn.getContentType()).append("\r\n");
                resp.append("Accept-Ranges: bytes\r\n");
                String cl = conn.getHeaderField("Content-Length");
                if (cl != null) resp.append("Content-Length: ").append(cl).append("\r\n");
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null) resp.append("Content-Range: ").append(cr).append("\r\n");
                resp.append("Connection: close\r\n");
                resp.append("X-Proxy: JellyfinTV-MultiThread\r\n");
                resp.append("\r\n");
                out.write(resp.toString().getBytes("ISO-8859-1"));
                out.flush();

                if (headOnly) {
                    upstreamIn.close();
                    conn.disconnect();
                    client.close();
                    return;
                }

                // --- Multi-threaded streaming ---
                // Producer thread: reads from upstream Jellyfin, puts chunks into queue
                // Main thread: takes chunks from queue, writes to client socket
                // This decouples upstream latency from playback — even if ffmpeg
                // is slow to produce the next frame, the player can consume from
                // the buffered queue.
                final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(QUEUE_CAPACITY);
                final AtomicBoolean producerDone = new AtomicBoolean(false);
                final AtomicBoolean producerError = new AtomicBoolean(false);

                Thread producer = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[CHUNK_SIZE];
                        try {
                            while (true) {
                                int n = upstreamIn.read(buf);
                                if (n < 0) break; // EOF
                                byte[] chunk = new byte[n];
                                System.arraycopy(buf, 0, chunk, 0, n);
                                queue.put(chunk); // blocks if queue is full
                            }
                        } catch (Exception e) {
                            producerError.set(true);
                        } finally {
                            producerDone.set(true);
                            // Wake up consumer if it's waiting on an empty queue
                            try { queue.put(END_SENTINEL); } catch (Exception ignored) {}
                            try { upstreamIn.close(); } catch (Exception ignored) {}
                        }
                    }
                });
                producer.setDaemon(true);
                producer.start();

                // Consumer: take chunks from queue and write to socket
                try {
                    while (true) {
                        byte[] chunk = queue.take(); // blocks until data available
                        if (chunk.length == 0) break; // END_SENTINEL
                        out.write(chunk, 0, chunk.length);
                        out.flush();
                    }
                } catch (Exception e) {
                    // Client disconnected or error — stop consuming
                }

                // Ensure producer finishes
                try { producer.join(5000); } catch (Exception ignored) {}
            } else {
                sendSimple(out, String.valueOf(code), "text/plain", "upstream error");
            }

            conn.disconnect();
            client.close();
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 202: return "Accepted";
            case 404: return "Not Found";
            default: return "Error";
        }
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (sb.length() == 0) return null;
        return sb.toString();
    }

    private void sendSimple(OutputStream out, String status, String type, String body) throws IOException {
        String b = body == null ? "" : body;
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(b.getBytes("UTF-8").length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (body != null) out.write(body.getBytes("UTF-8"));
        out.flush();
    }
}