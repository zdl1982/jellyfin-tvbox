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
import java.util.concurrent.TimeUnit;
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
 * Routes:
 *   /stream/<itemId>              — original quality direct stream
 *   /stream/<itemId>?q=low|medium — transcoded stream (NOT used; kept for compat)
 *   /hls/<itemId>/playlist.m3u8?q=low|medium  — HLS playlist for fluid/medium
 *   /hls/<itemId>/seg/<seq>.ts?... — HLS TS segment (rewritten URL from playlist)
 *
 * HLS approach: Android 4 MediaPlayer supports HLS natively. Jellyfin's
 * /Videos/{id}/main.m3u8 endpoint returns a VOD HLS playlist with 3-second
 * TS segments. This avoids the fragmented MP4 problem (which Android 4
 * cannot parse) and the progressive MPEG-TS problem (which gives error
 * 1/-1004 on Android 4).
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

    /** Build the local URL for direct/original stream. */
    public String getLocalUrl(String itemId) {
        return "http://127.0.0.1:" + PORT + "/stream/" + itemId;
    }

    /** Build the local HLS playlist URL for transcoded streaming.
     *  @param itemId  the Jellyfin item ID
     *  @param quality "low" for 480p, "medium" for 720p
     */
    public String getHlsLocalUrl(String itemId, String quality) {
        return "http://127.0.0.1:" + PORT + "/hls/" + itemId + "/playlist.m3u8?q=" + quality;
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

            // Route dispatch
            if (path != null && path.startsWith("/hls/")) {
                handleHlsRoute(path, method, out);
            } else {
                handleStreamRoute(path, method, headers, out);
            }

            client.close();
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // ===== Route: /stream/<itemId>[?q=low|medium] =====
    private void handleStreamRoute(String path, String method,
                                   Map<String, String> headers, OutputStream out) throws IOException {
        String itemId = null;
        String quality = null;
        if (path != null && path.startsWith("/stream/")) {
            itemId = path.substring("/stream/".length());
            int q = itemId.indexOf('?');
            if (q >= 0) {
                String query = itemId.substring(q + 1);
                itemId = itemId.substring(0, q);
                if (query.contains("q=low")) {
                    quality = "low";
                } else if (query.contains("q=medium")) {
                    quality = "medium";
                }
            }
        }
        if (itemId == null || itemId.isEmpty()) {
            sendSimple(out, "400 Bad Request", "text/plain", "missing item id");
            return;
        }

        // Build upstream URL
        String upstream;
        if ("medium".equals(quality)) {
            upstream = jfClient.getMediumStreamUrl(itemId);
        } else if ("low".equals(quality)) {
            upstream = jfClient.getFluidStreamUrl(itemId);
        } else {
            upstream = jfClient.getStreamUrl(itemId);
        }

        proxyUpstream(upstream, method, headers, out);
    }

    // ===== Route: /hls/<itemId>/playlist.m3u8?q=low|medium  or  /hls/<itemId>/seg/<seq>.ts?... =====
    private void handleHlsRoute(String path, String method, OutputStream out) throws IOException {
        // path example: /hls/{itemId}/playlist.m3u8?q=low
        // path example: /hls/{itemId}/seg/0.ts?api_key=...&params...
        String rest = path.substring("/hls/".length()); // {itemId}/playlist.m3u8?q=low
        int slash = rest.indexOf('/');
        if (slash < 0) {
            sendSimple(out, "400 Bad Request", "text/plain", "invalid hls path");
            return;
        }
        String itemId = rest.substring(0, slash);
        String subPath = rest.substring(slash + 1); // playlist.m3u8?q=low or seg/0.ts?params

        // Parse query params from subPath
        int qIdx = subPath.indexOf('?');
        String query = (qIdx >= 0) ? subPath.substring(qIdx + 1) : "";
        subPath = (qIdx >= 0) ? subPath.substring(0, qIdx) : subPath;

        if (subPath.equals("playlist.m3u8")) {
            handleHlsPlaylist(itemId, query, method, out);
        } else if (subPath.startsWith("seg/")) {
            handleHlsSegment(itemId, subPath, query, method, out);
        } else {
            sendSimple(out, "404 Not Found", "text/plain", "unknown hls path");
        }
    }

    /** Fetch the HLS playlist from Jellyfin, rewrite segment URLs to go through proxy. */
    private void handleHlsPlaylist(String itemId, String query, String method, OutputStream out) throws IOException {
        // Extract quality from query
        String quality = "low";
        if (query.contains("q=medium")) {
            quality = "medium";
        }

        // Build upstream HLS playlist URL
        String upstream = jfClient.getHlsPlaylistUrl(itemId, quality);

        // Fetch the playlist
        HttpURLConnection conn = (HttpURLConnection) new URL(upstream).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Authorization", "MediaBrowser Token=\"" + jfClient.getToken() + "\"");
        conn.setRequestProperty("User-Agent", "Jellyfin-TVBox/1.0");

        int code = conn.getResponseCode();
        if (code != 200) {
            sendSimple(out, String.valueOf(code), "text/plain", "upstream error");
            conn.disconnect();
            return;
        }

        // Read the playlist content
        InputStream upstreamIn = conn.getInputStream();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = upstreamIn.read(buf)) >= 0) {
            baos.write(buf, 0, n);
        }
        upstreamIn.close();
        conn.disconnect();

        String playlist = baos.toString("UTF-8");

        // Rewrite segment URLs: hls1/main/{seq}.ts?params -> http://127.0.0.1:PORT/hls/{itemId}/seg/{seq}.ts?params
        String proxyBase = "http://127.0.0.1:" + PORT + "/hls/" + itemId + "/seg/";
        playlist = playlist.replaceAll("hls1/main/(\\d+\\.ts\\?[^\\s]*)", proxyBase + "$1");

        // Send response
        StringBuilder resp = new StringBuilder();
        resp.append("HTTP/1.1 200 OK\r\n");
        resp.append("Content-Type: application/vnd.apple.mpegurl\r\n");
        resp.append("Content-Length: ").append(playlist.getBytes("UTF-8").length).append("\r\n");
        resp.append("Connection: close\r\n");
        resp.append("X-Proxy: JellyfinTV-HLS\r\n");
        resp.append("\r\n");
        resp.append(playlist);
        out.write(resp.toString().getBytes("UTF-8"));
        out.flush();
    }

    /** Fetch a single HLS TS segment from Jellyfin and proxy it. */
    private void handleHlsSegment(String itemId, String subPath, String query, String method, OutputStream out) throws IOException {
        // subPath: seg/0.ts, query: api_key=...&VideoCodec=...&...
        String seq = subPath.substring("seg/".length()); // 0.ts
        if (seq.isEmpty()) {
            sendSimple(out, "400 Bad Request", "text/plain", "missing segment");
            return;
        }

        // Build upstream URL: {serverUrl}/Videos/{itemId}/hls1/main/{seq.ts}?{query}
        // The query params come from the rewritten playlist URL (includes api_key, etc.)
        String upstream = jfClient.getServerUrl() + "/Videos/" + itemId + "/hls1/main/" + seq;
        if (query != null && !query.isEmpty()) {
            upstream += "?" + query;
        }

        // Simple proxy: fetch and pipe bytes
        HttpURLConnection conn = (HttpURLConnection) new URL(upstream).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Authorization", "MediaBrowser Token=\"" + jfClient.getToken() + "\"");
        conn.setRequestProperty("User-Agent", "Jellyfin-TVBox/1.0");

        int code = conn.getResponseCode();
        if (code == 200 || code == 206) {
            InputStream upstreamIn = conn.getInputStream();

            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 200 OK\r\n");
            resp.append("Content-Type: video/mp2t\r\n");
            String cl = conn.getHeaderField("Content-Length");
            if (cl != null) resp.append("Content-Length: ").append(cl).append("\r\n");
            resp.append("Connection: close\r\n");
            resp.append("X-Proxy: JellyfinTV-HLS\r\n");
            resp.append("\r\n");
            out.write(resp.toString().getBytes("ISO-8859-1"));
            out.flush();

            // Pipe the TS data
            byte[] buffer = new byte[CHUNK_SIZE];
            while (true) {
                int read = upstreamIn.read(buffer);
                if (read < 0) break;
                out.write(buffer, 0, read);
            }
            out.flush();
            upstreamIn.close();
        } else {
            sendSimple(out, String.valueOf(code), "text/plain", "segment error");
        }
        conn.disconnect();
    }

    /** Generic upstream proxy with multi-threaded streaming. */
    private void proxyUpstream(String upstream, String method,
                                Map<String, String> headers, OutputStream out) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(upstream).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Authorization", "MediaBrowser Token=\"" + jfClient.getToken() + "\"");
        conn.setRequestProperty("User-Agent", "Jellyfin-TVBox/1.0");

        String range = headers.get("range");
        if (range != null) {
            conn.setRequestProperty("Range", range);
        }

        int code = conn.getResponseCode();
        if (code == 200 || code == 206 || code == 202) {
            final InputStream upstreamIn = conn.getInputStream();
            final boolean headOnly = "HEAD".equalsIgnoreCase(method);

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
                return;
            }

            // Multi-threaded streaming
            final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(QUEUE_CAPACITY);
            final AtomicBoolean producerDone = new AtomicBoolean(false);
            final AtomicBoolean producerError = new AtomicBoolean(false);

            Thread producer = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[CHUNK_SIZE];
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            int n = upstreamIn.read(buf);
                            if (n < 0) break;
                            byte[] chunk = new byte[n];
                            System.arraycopy(buf, 0, chunk, 0, n);
                            // Use offer with timeout instead of blocking put(), so
                            // the producer won't hang forever if the consumer exits
                            // (e.g. client disconnect). Returns false if queue full
                            // after 1 second — re-check interrupt flag and retry.
                            while (!queue.offer(chunk, 1, TimeUnit.SECONDS)) {
                                if (Thread.currentThread().isInterrupted()) {
                                    return; // consumer exited, bail out
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        // Interrupted by consumer exit — normal shutdown
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        producerError.set(true);
                    } finally {
                        producerDone.set(true);
                        if (!Thread.currentThread().isInterrupted()) {
                            try { queue.offer(END_SENTINEL, 1, TimeUnit.SECONDS); } catch (Exception ignored) {}
                        }
                        try { upstreamIn.close(); } catch (Exception ignored) {}
                    }
                }
            });
            producer.setDaemon(true);
            producer.start();

            try {
                while (true) {
                    byte[] chunk = queue.take();
                    if (chunk.length == 0) break;
                    out.write(chunk, 0, chunk.length);
                    out.flush();
                }
            } catch (Exception e) {
                // Client disconnected or error — stop consuming and interrupt
                // the producer so it doesn't block forever on a full queue,
                // leaking the upstream Jellyfin transcode connection.
                producer.interrupt();
            }
            try { producer.join(5000); } catch (Exception ignored) {}
        } else {
            sendSimple(out, String.valueOf(code), "text/plain", "upstream error");
        }
        conn.disconnect();
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