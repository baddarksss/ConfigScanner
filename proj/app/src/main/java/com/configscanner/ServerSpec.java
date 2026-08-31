package com.configscanner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Base64;

/**
 * Parsed representation of a single proxy server line.
 * Supports: vless (reality/tls, tcp/ws/grpc), vmess, trojan, shadowsocks,
 * hysteria2 (incl. salamander obfs)
 */
public class ServerSpec {
    public String raw = "";
    public String protocol = "";
    public String name = "";
    public String host = "";
    public int port = 0;

    // vless / shared
    public String uuid = "";
    public String flow = "";
    public String vlessEncryption = "";  // e.g. mlkem768x25519plus.native.0rtt.<key>
    public String xPaddingBytes = "";    // xhttp padding, e.g. "100-1000"
    public String security = "";      // none | tls | reality
    public String sni = "";
    public String fingerprint = "";
    public String pbk = "";
    public String sid = "";
    public String spx = "";
    public String network = "tcp";    // tcp | ws | grpc
    public String path = "";
    public String hostHeader = "";
    public String serviceName = "";
    public boolean allowInsecure = false;

    // trojan / ss / hysteria2
    public String password = "";
    public String method = "";

    // ssr
    public String ssrObfs = "plain";
    public String ssrObfsParam = "";

    // shadowtls
    public int stlsVersion = 3;
    public String stlsPublic = "";

    // hysteria2 obfs (salamander)
    public String obfs = "";
    public String obfsParam = "";

    // leaf-cert SHA256 (hex) filled by the app for insecure=1 links
    public String pinnedCertHash = "";

    // vmess
    public int alterId = 0;
    public String cipher = "auto";

    // ------------------------------------------------------------------

    public static String urlDecode(String s) {
        if (s == null) return "";
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    public static String b64decode(String s) {
        if (s == null) return "";
        s = s.trim();
        String std = s.replace('-', '+').replace('_', '/');
        switch (std.length() % 4) {
            case 2: std += "=="; break;
            case 3: std += "="; break;
            case 1: return "";
        }
        try {
            return new String(Base64.getDecoder().decode(std), StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                String urlSafe = s.replace("+", "-").replace("/", "_");
                while (urlSafe.length() % 4 != 0) urlSafe += "=";
                return new String(Base64.getUrlDecoder().decode(urlSafe), StandardCharsets.UTF_8);
            } catch (Exception e2) {
                return "";
            }
        }
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            String k = kv.substring(0, i).toLowerCase().trim();
            String v = urlDecode(kv.substring(i + 1));
            if (!k.isEmpty()) m.put(k, v);
        }
        return m;
    }

    /** Splits host:port, tolerating [ipv6]:port */
    static String[] splitHostPort(String hp) {
        if (hp == null) return new String[]{"", ""};
        hp = hp.trim();
        int c = hp.lastIndexOf(':');
        if (c < 0) return new String[]{hp, ""};
        return new String[]{hp.substring(0, c).replace("[", "").replace("]", ""), hp.substring(c + 1)};
    }

    static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    static String firstNonEmptyOrNull(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    /**
     * Parses one line. Returns null if it's not a recognizable config line.
     */
    public static ServerSpec parse(String line) {
        if (line == null) return null;
        line = line.trim();
        if (line.isEmpty()) return null;
        String l = line.toLowerCase();
        if (l.startsWith("vless://")) return parseVless(line);
        if (l.startsWith("vmess://")) return parseVmess(line);
        if (l.startsWith("trojan://")) return parseTrojan(line);
        if (l.startsWith("hysteria2://") || l.startsWith("hysteria://")) return parseHysteria(line);
        if (l.startsWith("ss://")) return parseSS(line);
        if (l.startsWith("ssr://")) return parseSSR(line);
        if (l.startsWith("tuic://")) return parseTUIC(line);
        if (l.startsWith("shadowtls://")) return parseShadowTLS(line);
        if (l.startsWith("anytls://")) return parseAnyTLS(line);
        if (l.startsWith("snic://")) return parseSNIc(line);
        return null;
    }

    private static ServerSpec parseVless(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "vless";
        String body = line.substring(8);
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        int at = body.lastIndexOf('@');
        if (at < 0) return null;
        s.uuid = urlDecode(body.substring(0, at));
        String rest = body.substring(at + 1);
        int qi = rest.indexOf('?');
        Map<String, String> q = new HashMap<>();
        String hostport = rest;
        if (qi >= 0) {
            hostport = rest.substring(0, qi);
            q = parseQuery(rest.substring(qi + 1));
        }
        String[] hp = splitHostPort(hostport);
        s.host = hp[0];
        try { s.port = Integer.parseInt(hp[1]); } catch (Exception e) { s.port = 443; }

        s.network = firstNonEmpty(q.get("type"), q.get("network"), "tcp");
        if (s.network.equals("h2") || s.network.equals("http")) s.network = "tcp";
        s.security = firstNonEmpty(q.get("security"), "none");
        s.flow = q.get("flow");
        // VLESS user-level encryption: modern panels emit post-quantum
        // hybrid key exchange like
        // "mlkem768x25519plus.native.0rtt.<base64-key>" — pass it through,
        // "none" (the classic default) when absent.
        s.vlessEncryption = q.get("encryption");
        // xhttp transport options
        s.xPaddingBytes = firstNonEmpty(q.get("x_padding_bytes"),
                q.get("xpaddingbytes"));
        s.sni = firstNonEmpty(q.get("sni"), q.get("servername"), q.get("peer"));
        s.pbk = q.get("pbk");
        s.sid = q.get("sid");
        s.spx = q.get("spx");
        s.fingerprint = firstNonEmpty(q.get("fp"), "chrome");
        s.path = q.get("path");
        s.hostHeader = q.get("host");
        s.serviceName = q.get("servicename");
        if ("true".equals(q.get("insecure")) || "1".equals(q.get("insecure"))
                || "true".equals(q.get("allowinsecure")) || "1".equals(q.get("allowinsecure")))
            s.allowInsecure = true;

        if (s.host.isEmpty() || s.uuid.isEmpty()) return null;
        return s;
    }

    private static ServerSpec parseVmess(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "vmess";
        String body = line.substring(8);
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        String json = b64decode(body.trim());
        if (json.isEmpty()) {
            json = b64decode(urlDecode(body.trim()));
        }
        if (json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            s.host = o.optString("add", "");
            s.port = o.optInt("port", 443);
            s.uuid = o.optString("id", "");
            String ps = o.optString("ps", "");
            if (s.name.isEmpty() && !ps.isEmpty()) s.name = ps;
            s.alterId = o.optInt("aid", 0);
            s.cipher = firstNonEmpty(o.optString("scy", ""), "auto");
            s.network = o.optString("net", "tcp");
            if (s.network.equals("h2") || s.network.equals("http")) s.network = "tcp";
            if (o.optString("type", "").equals("none")) s.network = "tcp";
            String tls = o.optString("tls", "none");
            s.security = (tls.equals("tls") || tls.equals("reality")) ? tls : "none";
            s.sni = o.optString("sni", "");
            s.fingerprint = firstNonEmpty(o.optString("fp", ""), "chrome");
            s.path = o.optString("path", "");
            s.hostHeader = o.optString("host", "");
            s.flow = o.optString("flow", "");
            if (s.host.isEmpty() || s.uuid.isEmpty()) return null;
        } catch (Exception e) {
            return null;
        }
        return s;
    }

    private static ServerSpec parseTrojan(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "trojan";
        String body = line.substring(9); // "trojan://" is 9 chars
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        int at = body.lastIndexOf('@');
        if (at < 0) return null;
        s.password = urlDecode(body.substring(0, at));
        String rest = body.substring(at + 1);
        int qi = rest.indexOf('?');
        Map<String, String> q = new HashMap<>();
        String hostport = rest;
        if (qi >= 0) {
            hostport = rest.substring(0, qi);
            q = parseQuery(rest.substring(qi + 1));
        }
        String[] hp = splitHostPort(hostport);
        s.host = hp[0];
        try { s.port = Integer.parseInt(hp[1]); } catch (Exception e) { s.port = 443; }

        s.network = firstNonEmpty(q.get("type"), "tcp");
        if (s.network.equals("h2") || s.network.equals("http")) s.network = "tcp";
        s.security = firstNonEmpty(q.get("security"), "tls");
        s.sni = firstNonEmpty(q.get("sni"), q.get("servername"));
        s.fingerprint = firstNonEmpty(q.get("fp"), "chrome");
        s.path = q.get("path");
        s.hostHeader = q.get("host");
        s.serviceName = q.get("servicename");
        if ("true".equals(q.get("insecure")) || "1".equals(q.get("insecure"))
                || "true".equals(q.get("allowinsecure")) || "1".equals(q.get("allowinsecure")))
            s.allowInsecure = true;

        if (s.host.isEmpty() || s.password.isEmpty()) return null;
        return s;
    }

    private static ServerSpec parseSS(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "ss";
        String body = line.substring(5);
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            String userinfo = b64decode(body.substring(0, at));
            String rest = body.substring(at + 1);
            int qi = rest.indexOf('?');
            if (qi >= 0) {
                String query = rest.substring(qi + 1);
                if (query.startsWith("plugin=") || query.contains("&plugin=")) {
                    // v2-plugin shadowsocks is not what the core's ss outbound
                    // speaks — the base ss connection is still what we can test
                    System.out.println("cfgscan: ss plugin ignored (testing base ss only)");
                }
                rest = rest.substring(0, qi);
            }
            String[] hp = splitHostPort(rest);
            s.host = hp[0];
            try { s.port = Integer.parseInt(hp[1]); } catch (Exception e) { return null; }
            int ci = userinfo.indexOf(':');
            if (ci <= 0) return null;
            s.method = userinfo.substring(0, ci);
            s.password = userinfo.substring(ci + 1);
        } else {
            // legacy form: base64(method:password:address:port)
            // The password itself may contain ':', so parse from BOTH ends:
            // first segment = method, last = port, second-to-last = host,
            // everything in between (rejoined) = password.
            String decoded = b64decode(body);
            String[] parts = decoded.split(":");
            if (parts.length < 4) return null;
            s.method = parts[0];
            StringBuilder pw = new StringBuilder(parts[1]);
            for (int i = 2; i < parts.length - 2; i++) pw.append(':').append(parts[i]);
            s.password = pw.toString();
            s.host = parts[parts.length - 2];
            try { s.port = Integer.parseInt(parts[parts.length - 1]); } catch (Exception e) { return null; }
        }
        if (s.host.isEmpty() || s.method.isEmpty()) return null;
        return s;
    }

    // ------------------------------------------------------------------
    // SSR / TUIC / ShadowTLS / AnyTLS / SNIc
    // Note: the current official Xray-core release does not ship these
    // outbounds; the app still parses them and reports a clear
    // "not in core" message at test time (see MainActivity).
    // ------------------------------------------------------------------

    private static ServerSpec parseSSR(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "ssr";
        String body = line.substring(6);
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            // ssr://method:password@host:port?obfs=...
            int ci = body.indexOf(':');
            if (ci <= 0) return null;
            s.method = urlDecode(body.substring(0, ci));
            String rest = body.substring(ci + 1);
            int at2 = rest.indexOf('@');
            if (at2 < 0) return null;
            s.password = urlDecode(rest.substring(0, at2));
            String hostport = rest.substring(at2 + 1);
            int qi = hostport.indexOf('?');
            Map<String, String> q = new HashMap<>();
            if (qi >= 0) {
                q = parseQuery(hostport.substring(qi + 1));
                hostport = hostport.substring(0, qi);
            }
            String[] hp = splitHostPort(hostport);
            s.host = hp[0];
            try { s.port = Integer.parseInt(hp[1]); } catch (Exception e) { return null; }
            s.ssrObfs = firstNonEmpty(q.get("obfs"), "plain");
            s.ssrObfsParam = firstNonEmpty(q.get("obfsparam"), q.get("protoparam"));
        } else {
            // legacy: base64(method:password:server:port[:obfs[:obfsparam]])
            // password may contain ':', so pin the tail (host:port[:obfs[:obfsparam]])
            // and treat everything between method and host as the password.
            String decoded = b64decode(body);
            String[] parts = decoded.split(":");
            int n = parts.length;
            if (n < 4) return null;
            int hostIdx;
            if (n >= 6) {
                s.ssrObfsParam = parts[n - 1];
                s.ssrObfs = parts[n - 2];
                hostIdx = n - 4;
            } else if (n == 5) {
                s.ssrObfs = parts[n - 1];
                hostIdx = n - 3;
            } else {
                hostIdx = n - 2;
            }
            s.method = parts[0];
            s.host = parts[hostIdx];
            try { s.port = Integer.parseInt(parts[hostIdx + 1]); } catch (Exception e) { return null; }
            StringBuilder pw = new StringBuilder(parts[1]);
            for (int i = 2; i < hostIdx; i++) pw.append(':').append(parts[i]);
            s.password = pw.toString();
            if (s.ssrObfs.isEmpty()) s.ssrObfs = "plain";
        }
        if (s.host.isEmpty() || s.method.isEmpty()) return null;
        return s;
    }

    private static ServerSpec parseTUIC(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "tuic";
        String body = line.substring(7);
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        int qi = body.indexOf('?');
        Map<String, String> q = new HashMap<>();
        String rest = body;
        if (qi >= 0) {
            rest = body.substring(0, qi);
            q = parseQuery(body.substring(qi + 1));
        }
        // common share form: tuic://uuid:password@host:port
        String hostport = rest;
        int at = rest.lastIndexOf('@');
        String userUuid = null, userPass = null;
        if (at >= 0) {
            String userinfo = rest.substring(0, at);
            hostport = rest.substring(at + 1);
            int ci = userinfo.indexOf(':');
            if (ci > 0) {
                userUuid = userinfo.substring(0, ci);
                userPass = userinfo.substring(ci + 1);
            } else {
                userUuid = userinfo;
            }
        }
        String[] hp = splitHostPort(hostport);
        s.host = hp[0];
        try { s.port = Integer.parseInt(hp[1]); } catch (Exception e) { return null; }
        s.uuid = firstNonEmptyOrNull(q.get("uuid"), userUuid);
        s.password = firstNonEmptyOrNull(q.get("password"), userPass);
        s.sni = firstNonEmpty(q.get("sni"), q.get("servername"));
        if ("true".equals(q.get("allowinsecure")) || "1".equals(q.get("allowinsecure"))
                || "true".equals(q.get("insecure")) || "1".equals(q.get("insecure")))
            s.allowInsecure = true;
        if (s.host.isEmpty() || s.uuid == null) return null;
        return s;
    }

    private static ServerSpec parseShadowTLS(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "shadowtls";
        String body = line.substring(12);
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        int qi = body.indexOf('?');
        Map<String, String> q = new HashMap<>();
        String hostport = body;
        if (qi >= 0) {
            hostport = body.substring(0, qi);
            q = parseQuery(body.substring(qi + 1));
        }
        String[] hp = splitHostPort(hostport);
        s.host = hp[0];
        try { s.port = Integer.parseInt(hp[1]); } catch (Exception e) { return null; }
        s.password = firstNonEmpty(q.get("private"), q.get("password"));
        s.stlsPublic = firstNonEmpty(q.get("public"), q.get("sni"));
        String v = q.get("v");
        if ("v2".equalsIgnoreCase(v)) s.stlsVersion = 2;
        else if ("v3".equalsIgnoreCase(v)) s.stlsVersion = 3;
        s.network = firstNonEmpty(q.get("type"), "tcp");
        s.path = q.get("path");
        s.hostHeader = q.get("host");
        if (s.host.isEmpty() || s.password.isEmpty()) return null;
        return s;
    }

    private static ServerSpec parseAnyTLS(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "anytls";
        String body = line.substring(9);
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        // official share form: anytls://password@host:port — the userinfo
        // segment is the auth password, not a uuid
        int at = body.lastIndexOf('@');
        String userinfoPass = null;
        if (at >= 0) {
            userinfoPass = urlDecode(body.substring(0, at));
            body = body.substring(at + 1);
        }
        int qi = body.indexOf('?');
        Map<String, String> q = new HashMap<>();
        String hostport = body;
        if (qi >= 0) {
            hostport = body.substring(0, qi);
            q = parseQuery(body.substring(qi + 1));
        }
        String[] hp = splitHostPort(hostport);
        s.host = hp[0];
        try { s.port = Integer.parseInt(hp[1]); } catch (Exception e) { return null; }
        s.password = firstNonEmptyOrNull(q.get("password"), userinfoPass);
        s.uuid = q.get("uuid");
        s.sni = firstNonEmpty(q.get("sni"), q.get("servername"));
        if (s.host.isEmpty()) return null;
        return s;
    }

    private static ServerSpec parseSNIc(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "snic";
        String body = line.substring(7);
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        int qi = body.indexOf('?');
        Map<String, String> q = new HashMap<>();
        String hostport = body;
        if (qi >= 0) {
            hostport = body.substring(0, qi);
            q = parseQuery(body.substring(qi + 1));
        }
        String[] hp = splitHostPort(hostport);
        s.host = hp[0];
        try { s.port = Integer.parseInt(hp[1]); } catch (Exception e) { return null; }
        s.sni = firstNonEmpty(q.get("sni"), q.get("servername"));
        if (s.host.isEmpty()) return null;
        return s;
    }

    private static ServerSpec parseHysteria(String line) {
        ServerSpec s = new ServerSpec();
        s.raw = line;
        s.protocol = "hysteria2";
        int schemeEnd = line.indexOf("://");
        String body = line.substring(schemeEnd + 3);
        int fi = body.lastIndexOf('#');
        if (fi >= 0) {
            s.name = urlDecode(body.substring(fi + 1));
            body = body.substring(0, fi);
        }
        int at = body.lastIndexOf('@');
        if (at < 0) return null;
        s.password = urlDecode(body.substring(0, at));
        String rest = body.substring(at + 1);
        int qi = rest.indexOf('?');
        Map<String, String> q = new HashMap<>();
        String hostport = rest;
        if (qi >= 0) {
            hostport = rest.substring(0, qi);
            q = parseQuery(rest.substring(qi + 1));
        }
        String[] hp = splitHostPort(hostport);
        s.host = hp[0];
        try { s.port = Integer.parseInt(hp[1]); } catch (Exception e) { s.port = 443; }

        s.security = "tls";
        s.sni = firstNonEmpty(q.get("sni"), q.get("servername"));
        s.fingerprint = firstNonEmpty(q.get("fp"), "chrome");
        s.path = q.get("path");
        s.hostHeader = q.get("host");
        s.obfs = q.get("obfs");
        // URL variants seen in the wild: obfsparam / obfspassword /
        // obfs_password / obfs-password (the hyphen form is the common one)
        s.obfsParam = firstNonEmpty(q.get("obfsparam"), q.get("obfspassword"),
                q.get("obfs_password"), q.get("obfs-password"));
        if ("true".equals(q.get("insecure")) || "1".equals(q.get("insecure"))
                || "true".equals(q.get("allowinsecure")) || "1".equals(q.get("allowinsecure")))
            s.allowInsecure = true;

        if (s.host.isEmpty() || s.password.isEmpty()) return null;
        return s;
    }

    // ------------------------------------------------------------------
    // Xray outbound builder
    // ------------------------------------------------------------------

    public JSONObject buildStreamSettings() throws Exception {
        JSONObject st = new JSONObject();
        if (network != null && !network.isEmpty() && !network.equals("tcp")) {
            st.put("network", network);
        }
        if ("tls".equals(security) || "reality".equals(security)) {
            st.put("security", security);
            if ("reality".equals(security)) {
                JSONObject r = new JSONObject();
                r.put("show", false);
                r.put("fingerprint", fingerprint.isEmpty() ? "chrome" : fingerprint);
                if (sni != null && !sni.isEmpty()) r.put("serverName", sni);
                if (pbk != null && !pbk.isEmpty()) r.put("publicKey", pbk);
                if (sid != null && !sid.isEmpty()) r.put("shortId", sid);
                if (spx != null && !spx.isEmpty()) r.put("spiderX", spx);
                st.put("realitySettings", r);
            } else {
                JSONObject t = new JSONObject();
                if (sni != null && !sni.isEmpty()) t.put("serverName", sni);
                // Force classic X25519 key exchange: several servers (notably
                // Hysteria2) crash on the post-quantum X25519MLKEM768 key share
                // that recent Xray builds offer by default (QUIC "tls: internal
                // error" / handshake failure).
                t.put("curvePreferences", new JSONArray().put("x25519"));
                // "allowInsecure" was REMOVED in Xray 26.2.6 (fatal config error
                // since 2026-06-01). For insecure=1 links the app pins the
                // server's leaf cert (pinnedPeerCertSha256); without a fetched
                // cert we fall back to a name check (strict CA verification).
                if (allowInsecure) {
                    if (pinnedCertHash != null && !pinnedCertHash.isEmpty()) {
                        t.put("pinnedPeerCertSha256", pinnedCertHash);
                    } else {
                        String names = (sni != null && !sni.isEmpty() && !sni.equals(host)) ? sni + "," + host : host;
                        t.put("verifyPeerCertByName", names);
                    }
                }
                if (fingerprint != null && !fingerprint.isEmpty()) t.put("fingerprint", fingerprint);
                st.put("tlsSettings", t);
            }
        }
        if ("ws".equals(network)) {
            JSONObject w = new JSONObject();
            if (path != null && !path.isEmpty()) w.put("path", path);
            if (hostHeader != null && !hostHeader.isEmpty()) {
                JSONObject h = new JSONObject();
                h.put("Host", hostHeader);
                w.put("headers", h);
            }
            st.put("wsSettings", w);
        }
        if ("grpc".equals(network)) {
            JSONObject g = new JSONObject();
            if (serviceName != null && !serviceName.isEmpty()) g.put("serviceName", serviceName);
            g.put("multiMode", false);
            st.put("grpcSettings", g);
        }
        if ("xhttp".equals(network)) {
            JSONObject x = new JSONObject();
            if (path != null && !path.isEmpty()) x.put("path", path);
            if (hostHeader != null && !hostHeader.isEmpty()) x.put("host", hostHeader);
            String pad = sanitizePadding(xPaddingBytes);
            if (pad != null) x.put("xPaddingBytes", pad);
            st.put("xhttpSettings", x);
        }
        return st;
    }

    /** Xray rejects a padding range whose minimum is 0 ("cannot be disabled"). */
    static String sanitizePadding(String v) {
        if (v == null || v.isEmpty()) return null;
        int i = v.indexOf('-');
        if (i > 0) {
            try {
                int min = Integer.parseInt(v.substring(0, i).trim());
                if (min <= 0) return "1" + v.substring(i);
            } catch (NumberFormatException ignored) { }
        }
        return v;
    }

    public String buildOutbound() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray serversArr = new JSONArray();
        switch (protocol) {
            case "vless": {
                o.put("protocol", "vless");
                JSONObject user = new JSONObject();
                user.put("id", uuid);
                // classic default is "none"; newer panels may require the
                // post-quantum hybrid exchange (mlkem768x25519plus…)
                user.put("encryption",
                        vlessEncryption == null || vlessEncryption.isEmpty()
                                ? "none" : vlessEncryption);
                if (flow != null && !flow.isEmpty()) user.put("flow", flow);
                JSONArray vnext = new JSONArray();
                JSONObject vObj = new JSONObject();
                vObj.put("address", host);
                vObj.put("port", port);
                JSONArray users = new JSONArray();
                users.put(user);
                vObj.put("users", users);
                vnext.put(vObj);
                JSONObject settings = new JSONObject();
                settings.put("vnext", vnext);
                o.put("settings", settings);
                o.put("streamSettings", buildStreamSettings());
                break;
            }
            case "vmess": {
                o.put("protocol", "vmess");
                JSONObject user = new JSONObject();
                user.put("id", uuid);
                user.put("alterId", alterId);
                user.put("security", cipher == null || cipher.isEmpty() ? "auto" : cipher);
                JSONObject vObj = new JSONObject();
                vObj.put("address", host);
                vObj.put("port", port);
                JSONArray users = new JSONArray();
                users.put(user);
                vObj.put("users", users);
                JSONArray vnext = new JSONArray();
                vnext.put(vObj);
                JSONObject settings = new JSONObject();
                settings.put("vnext", vnext);
                o.put("settings", settings);
                o.put("streamSettings", buildStreamSettings());
                break;
            }
            case "trojan": {
                o.put("protocol", "trojan");
                JSONObject srv = new JSONObject();
                srv.put("address", host);
                srv.put("port", port);
                srv.put("password", password);
                serversArr.put(srv);
                JSONObject settings = new JSONObject();
                settings.put("servers", serversArr);
                o.put("settings", settings);
                o.put("streamSettings", buildStreamSettings());
                break;
            }
            case "ss": {
                o.put("protocol", "shadowsocks");
                JSONObject srv = new JSONObject();
                srv.put("address", host);
                srv.put("port", port);
                srv.put("method", method);
                srv.put("password", password);
                srv.put("uot", true);
                serversArr.put(srv);
                JSONObject settings = new JSONObject();
                settings.put("servers", serversArr);
                o.put("settings", settings);
                break;
            }
            case "hysteria2": {
                // Xray >= v26.1.23: Hysteria2 lives under protocol "hysteria" with version 2
                o.put("protocol", "hysteria");
                JSONObject settings = new JSONObject();
                settings.put("version", 2);
                settings.put("address", host);
                settings.put("port", port);
                o.put("settings", settings);
                JSONObject st = new JSONObject();
                st.put("network", "hysteria");
                JSONObject hy = new JSONObject();
                hy.put("version", 2);
                hy.put("auth", password);
                st.put("hysteriaSettings", hy);
                st.put("security", "tls");
                JSONObject tls = new JSONObject();
                // hy2 verifies the peer cert against this name; without an
                // explicit serverName xray falls back to the internal name
                // "hysteria" and CA validation fails. Always set it.
                String hySni = (sni != null && !sni.isEmpty()) ? sni : host;
                tls.put("serverName", hySni);
                // see buildStreamSettings: classic x25519 only, pin self-signed certs
                tls.put("curvePreferences", new JSONArray().put("x25519"));
                if (allowInsecure) {
                    if (pinnedCertHash != null && !pinnedCertHash.isEmpty()) {
                        tls.put("pinnedPeerCertSha256", pinnedCertHash);
                    } else {
                        String names = (sni != null && !sni.isEmpty() && !sni.equals(host)) ? sni + "," + host : host;
                        tls.put("verifyPeerCertByName", names);
                    }
                }
                tls.put("alpn", new JSONArray().put("h3"));
                st.put("tlsSettings", tls);
                // salamander obfuscation (popular on many providers)
                if ("salamander".equalsIgnoreCase(obfs)
                        && obfsParam != null && !obfsParam.isEmpty()) {
                    JSONObject mask = new JSONObject();
                    mask.put("type", "salamander");
                    mask.put("settings", new JSONObject().put("password", obfsParam));
                    st.put("udpmasks", new JSONArray().put(mask));
                }
                o.put("streamSettings", st);
                break;
            }
            case "ssr": {
                o.put("protocol", "ssr");
                JSONObject srv = new JSONObject();
                srv.put("address", host);
                srv.put("port", port);
                srv.put("method", method);
                srv.put("password", password);
                srv.put("obfs", ssrObfs == null || ssrObfs.isEmpty() ? "plain" : ssrObfs);
                srv.put("obfsparam", ssrObfsParam == null ? "" : ssrObfsParam);
                srv.put("level", 0);
                serversArr.put(srv);
                JSONObject settings = new JSONObject();
                settings.put("servers", serversArr);
                o.put("settings", settings);
                break;
            }
            case "tuic": {
                o.put("protocol", "tuic");
                JSONObject settings = new JSONObject();
                settings.put("address", host);
                settings.put("port", port);
                settings.put("uuid", uuid);
                if (password != null && !password.isEmpty()) settings.put("password", password);
                o.put("settings", settings);
                JSONObject st = new JSONObject();
                st.put("security", "tls");
                JSONObject t = new JSONObject();
                String tSni = (sni != null && !sni.isEmpty()) ? sni : host;
                t.put("serverName", tSni);
                t.put("alpn", new JSONArray().put("h2"));
                if (fingerprint != null && !fingerprint.isEmpty()) t.put("fingerprint", fingerprint);
                if (allowInsecure) {
                    if (pinnedCertHash != null && !pinnedCertHash.isEmpty()) {
                        t.put("pinnedPeerCertSha256", pinnedCertHash);
                    } else {
                        t.put("verifyPeerCertByName", tSni);
                    }
                }
                st.put("tlsSettings", t);
                st.put("quicSettings", new JSONObject().put("congestionControl", "bbr"));
                o.put("streamSettings", st);
                break;
            }
            case "shadowtls": {
                o.put("protocol", "shadowtls");
                JSONObject settings = new JSONObject();
                settings.put("version", stlsVersion);
                // v3: server = the real (public) SNI; v2: server = host
                settings.put("server", stlsPublic != null && !stlsPublic.isEmpty() && stlsVersion == 3
                        ? stlsPublic : host);
                settings.put("password", password);
                o.put("settings", settings);
                JSONObject st = new JSONObject();
                if (network != null && !network.isEmpty() && !network.equals("tcp")) {
                    st.put("network", network);
                }
                o.put("streamSettings", st);
                break;
            }
            case "anytls": {
                o.put("protocol", "anytls");
                JSONObject user = new JSONObject();
                if (uuid != null && !uuid.isEmpty()) user.put("uuid", uuid);
                if (password != null) user.put("password", password);
                JSONArray users = new JSONArray();
                users.put(user);
                JSONObject settings = new JSONObject();
                settings.put("users", users);
                o.put("settings", settings);
                JSONObject st = new JSONObject();
                st.put("security", "tls");
                JSONObject t = new JSONObject();
                String aSni = (sni != null && !sni.isEmpty()) ? sni : host;
                t.put("serverName", aSni);
                if (fingerprint != null && !fingerprint.isEmpty()) t.put("fingerprint", fingerprint);
                if (allowInsecure) {
                    if (pinnedCertHash != null && !pinnedCertHash.isEmpty()) {
                        t.put("pinnedPeerCertSha256", pinnedCertHash);
                    } else {
                        t.put("verifyPeerCertByName", aSni);
                    }
                }
                st.put("tlsSettings", t);
                o.put("streamSettings", st);
                break;
            }
            case "snic": {
                o.put("protocol", "snici");
                o.put("settings", new JSONObject());
                JSONObject st = new JSONObject();
                st.put("security", "tls");
                JSONObject t = new JSONObject();
                String sSni = (sni != null && !sni.isEmpty()) ? sni : host;
                t.put("serverName", sSni);
                if (fingerprint != null && !fingerprint.isEmpty()) t.put("fingerprint", fingerprint);
                st.put("tlsSettings", t);
                o.put("streamSettings", st);
                break;
            }
            default:
                throw new Exception("unsupported protocol: " + protocol);
        }
        return o.toString();
    }
}
