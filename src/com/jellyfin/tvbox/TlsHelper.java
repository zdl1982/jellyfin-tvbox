package com.jellyfin.tvbox;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Enables TLS 1.1 / TLS 1.2 on Android 4.x.
 *
 * Android 4.0-4.4 only enables TLS 1.0 by default, even though the underlying
 * OpenSSL engine supports TLS 1.2. Modern servers (e.g. Jellyfin behind a
 * reverse proxy) reject TLS 1.0, so we must explicitly enable the newer
 * protocols on every socket we create.
 */
public class TlsHelper {

    public static final String[] ENABLED_PROTOCOLS = new String[] {
        "TLSv1.2", "TLSv1.1", "TLSv1"
    };

    private static final TrustManager[] TRUST_ALL = new TrustManager[] {
        new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }
    };

    /** Build a TLSv1.2-capable SSLSocketFactory that trusts all certs. */
    public static SSLSocketFactory getSocketFactory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, TRUST_ALL, new SecureRandom());
            return new Tls12SocketFactory(ctx.getSocketFactory());
        } catch (Exception e) {
            // fallback
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    /**
     * Wraps a base SSLSocketFactory and enables TLS 1.1/1.2 on every socket.
     * This subclass only needs to override the createSocket methods that
     * Android's HttpURLConnection actually uses.
     */
    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory base) {
            this.delegate = base;
        }

        private Socket enableTls(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket s = (SSLSocket) socket;
                s.setEnabledProtocols(ENABLED_PROTOCOLS);
            }
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return enableTls(delegate.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return enableTls(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return enableTls(delegate.createSocket(host, port, localHost, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return enableTls(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
            return enableTls(delegate.createSocket(host, port, localHost, localPort));
        }
    }
}