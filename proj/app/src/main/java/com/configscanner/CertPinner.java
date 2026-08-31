package com.configscanner;

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
            s = (SSLSocket) f.createSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            if (sni != null && !sni.isEmpty() && !sni.equals(host)) {
                javax.net.ssl.SSLParameters sp = s.getSSLParameters();
                sp.setServerNames(java.util.Collections.singletonList(
                        new javax.net.ssl.SNIHostName(sni.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
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
