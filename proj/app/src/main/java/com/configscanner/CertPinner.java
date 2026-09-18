package com.configscanner;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Fetches the server's leaf certificate with a plain TLS handshake and
 * returns its SHA-256 hash (lowercase hex) for xray's
 * "pinnedPeerCertSha256" tlsSettings field.
 *
 * Used for links with insecure=1 (self-signed certs): since Xray 26.2.6
 * the old "allowInsecure" flag no longer exists, pinning the leaf cert
 * is the supported way to connect to such servers.
 */
public class CertPinner {

    public static String pin(String host, int port, String sni, int timeoutMs) {
        return pinViaSocks(-1, host, port, sni, timeoutMs);
    }

    /**
     * Fetch the leaf certificate through the same local SOCKS5 route that
     * Xray uses for the test. A direct handshake can see a different CDN
     * certificate or even a different backend than the actual proxy route.
     * proxyPort < 0 keeps the legacy direct behavior.
     */
    public static String pinViaSocks(int proxyPort, String host, int port, String sni, int timeoutMs) {
        SSLSocket s = null;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            // Trust-all for THIS handshake only: the whole point is to fetch
            // a cert we don't trust yet (self-signed). The actual proxy
            // connection in Xray still verifies the pinned hash strictly.
            ctx.init(null, new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] c, String a) { }

                        @Override
                        public void checkServerTrusted(X509Certificate[] c, String a) { }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            }, null);
            SSLSocketFactory f = ctx.getSocketFactory();
            Exception last = null;
            if (proxyPort >= 0) {
                try {
                    java.net.Proxy proxy = new java.net.Proxy(
                            java.net.Proxy.Type.SOCKS,
                            new InetSocketAddress("127.0.0.1", proxyPort));
                    java.net.Socket raw = new java.net.Socket(proxy);
                    raw.connect(InetSocketAddress.createUnresolved(host, port), timeoutMs);
                    s = (SSLSocket) f.createSocket(raw, host, port, true);
                    s.setSoTimeout(timeoutMs);
                    if (sni != null && !sni.isEmpty()) {
                        javax.net.ssl.SSLParameters sp = s.getSSLParameters();
                        sp.setServerNames(java.util.Collections.singletonList(
                                new javax.net.ssl.SNIHostName(sni)));
                        s.setSSLParameters(sp);
                    }
                    s.startHandshake();
                    Certificate[] chain = s.getSession().getPeerCertificates();
                    if (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate) {
                        byte[] der = ((X509Certificate) chain[0]).getEncoded();
                        byte[] h = MessageDigest.getInstance("SHA-256").digest(der);
                        StringBuilder sb = new StringBuilder(h.length * 2);
                        for (byte b : h) sb.append(String.format("%02x", b));
                        return sb.toString();
                    }
                } catch (Exception e) {
                    last = e;
                } finally {
                    if (s != null) {
                        try { s.close(); } catch (Exception ignored) { }
                        s = null;
                    }
                }
            } else {
                // Legacy direct fallback. Xray may select a different address
                // when a hostname has multiple A/AAAA records, so try all.
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress address : addresses) {
                    try {
                        s = (SSLSocket) f.createSocket();
                        s.connect(new InetSocketAddress(address, port), timeoutMs);
                    s.setSoTimeout(timeoutMs);
                    if (sni != null && !sni.isEmpty()) {
                        try {
                            javax.net.ssl.SSLParameters sp = s.getSSLParameters();
                            sp.setServerNames(java.util.Collections.singletonList(
                                    new javax.net.ssl.SNIHostName(sni)));
                            s.setSSLParameters(sp);
                        } catch (Exception sniErr) {
                            AppLog.w("cert", "bad SNI skipped: " + sni
                                    + " (" + sniErr.getClass().getSimpleName() + ")");
                        }
                    }
                    s.startHandshake();
                    Certificate[] chain = s.getSession().getPeerCertificates();
                    if (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate) {
                        byte[] der = ((X509Certificate) chain[0]).getEncoded();
                        byte[] h = MessageDigest.getInstance("SHA-256").digest(der);
                        StringBuilder sb = new StringBuilder(h.length * 2);
                        for (byte b : h) sb.append(String.format("%02x", b));
                        return sb.toString();
                    }
                } catch (Exception one) {
                    last = one;
                } finally {
                    if (s != null) {
                        try { s.close(); } catch (Exception ignored) { }
                        s = null;
                    }
                }
                }
            }
            if (last != null) throw last;
            return "";
        } catch (Exception e) {
            AppLog.w("certpin", "pin failed " + host + ":" + port
                    + " sni=" + sni + " -> " + e.getMessage());
            return "";
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) { }
            }
        }
    }
}
