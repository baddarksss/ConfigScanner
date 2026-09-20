package com.configscanner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v1.0.71: pulls proxy servers out of JSON config dumps so they can be
 * scanned like normal links. Supported shapes (found ANYWHERE inside the
 * pasted text - one config, several concatenated configs, or JSON mixed
 * with links/logs):
 *
 *   - Xray / v2ray-core:   {"outbounds":[{"protocol":"vless|vmess|trojan|
 *                            shadowsocks","settings":...,"streamSettings":...}]}
 *   - sing-box:            {"outbounds":[{"type":"vless|vmess|trojan|
 *                            shadowsocks|hysteria2","server":...,
 *                            "server_port":...}]}
 *   - v2rayN share dumps:  [{"add":"host","port":"443","id":"uuid",...}]
 *   - Clash style lists:   {"proxies":[{"type":"ss|vmess|vless|trojan|
 *                            hysteria2","server":...,"port":...}]}
 *   - bare URI arrays:     ["vless://...", "ss://..."]
 *
 * Every discovered server is converted back to the canonical URI the app
 * already parses, so scanning, stats and filters behave exactly like for
 * pasted links. This class never throws and never touches Android APIs,
 * so the extraction logic is unit-testable off-device.
 */
public final class JsonConfigs {

    private JsonConfigs() { }

    /** hard caps so a hostile paste can never wedge the run */
    private static final int MAX_DOCS = 200;
    private static final int MAX_SERVERS = 3000;
    private static final int MAX_WALK_NODES = 20000;
    private static final int MAX_DEPTH = 48;
    private static final int MAX_DOC_LEN = 2_000_000;

    /**
     * All proxy URIs found in JSON embedded anywhere in the given text,
     * deduplicated, in discovery order. Plain proxy links that are already
     * strings inside the JSON are passed through unchanged.
     */
    public static List<String> extract(String text) {
        LinkedHashSet<String> uris = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) return new ArrayList<>(uris);
        int[] budget = {MAX_WALK_NODES};
        int docs = 0;
        int n = text.length();
        int i = 0;
        while (i < n && uris.size() < MAX_SERVERS && docs < MAX_DOCS) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') { i++; continue; }
            String cand = balanced(text, i);
            if (cand == null) { i++; continue; }
            Object doc = tryParse(cand);
            if (doc == null) { i++; continue; }
            docs++;
            walk(doc, uris, budget, 0);
            i += cand.length();
        }
        return new ArrayList<>(uris);
    }

    // ------------------------------------------------------------- doc scan

    /**
     * The balanced JSON document starting at start ('{' or '['), honoring
     * strings and escapes, or null when it never closes / mismatches /
     * exceeds the length or depth cap.
     */
    private static String balanced(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        int max = Math.min(s.length(), start + MAX_DOC_LEN);
        for (int i = start; i < max; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                depth++;
                if (depth > MAX_DEPTH) return null;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
                // a mismatched closer only breaks this candidate when it
                // appears BEFORE the document balances (org.json re-checks
                // the extracted candidate anyway)
                if (depth < 0) return null;
            }
        }
        return null;
    }

    private static Object tryParse(String s) {
        try {
            if (s.charAt(0) == '{') return new JSONObject(s);
            return new JSONArray(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------- walker

    private static void walk(Object node, LinkedHashSet<String> out,
                             int[] budget, int depth) {
        if (budget[0] <= 0 || out.size() >= MAX_SERVERS || depth > MAX_DEPTH) return;
        budget[0]--;
        if (node == null) return;
        if (node instanceof String) {
            String t = ((String) node).trim();
            if (isProxyScheme(t)) out.add(t);
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && budget[0] > 0; i++) {
                walk(a.opt(i), out, budget, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        if (o.length() == 0) return;

        String uri = convert(o);
        if (uri != null) {
            out.add(uri);
            return; // a converted server object is not walked deeper
        }
        // containers / wrapper documents: recurse into every value
        Iterator<String> it = o.keys();
        while (it.hasNext() && budget[0] > 0 && out.size() < MAX_SERVERS) {
            walk(o.opt(it.next()), out, budget, depth + 1);
        }
    }

    private static boolean isProxyScheme(String t) {
        String l = t.toLowerCase(Locale.US);
        return l.startsWith("vless://") || l.startsWith("vmess://")
                || l.startsWith("trojan://") || l.startsWith("ss://")
                || l.startsWith("ssr://") || l.startsWith("hysteria2://")
                || l.startsWith("hy2://") || l.startsWith("tuic://")
                || l.startsWith("shadowtls://") || l.startsWith("anytls://")
                || l.startsWith("snic://") || l.startsWith("socks://");
    }

    // ------------------------------------------------------------- dispatch

    /** One JSON object -> canonical proxy URI, or null if not a server. */
    static String convert(JSONObject o) {
        if (o.has("protocol") && o.has("settings")) {
            try { return fromXray(o); } catch (Exception e) { return null; }
        }
        if (o.has("type") && o.has("server")) {
            try {
                if (o.has("server_port")) return fromSingBox(o);
                if (o.has("port")) return fromClash(o);
            } catch (Exception e) { return null; }
        }
        if (o.has("add") && o.has("port") && o.has("id")) {
            try { return fromV2rayShare(o); } catch (Exception e) { return null; }
        }
        return null;
    }

    // ------------------------------------------------------- Xray / v2ray-core

    private static String fromXray(JSONObject ob) {
        String proto = ob.optString("protocol", "");
        JSONObject settings = ob.optJSONObject("settings");
        if (settings == null) return null;
        String tag = ob.optString("tag", "");

        if ("vless".equals(proto) || "vmess".equals(proto)) {
            JSONArray vnext = settings.optJSONArray("vnext");
            if (vnext == null || vnext.length() == 0) return null;
            JSONObject srv = vnext.optJSONObject(0);
            if (srv == null) return null;
            JSONArray users = srv.optJSONArray("users");
            JSONObject user = users == null ? null : users.optJSONObject(0);
            if (user == null) return null;
            String host = srv.optString("address", "").trim();
            int port = srv.optInt("port", 0);
            String id = user.optString("id", "").trim();
            if (badHost(host) || badPort(port) || id.isEmpty()) return null;

            Transport t = readStream(ob.optJSONObject("streamSettings"));
            String name = first(tag, host + ":" + port);
            if ("vless".equals(proto)) {
                Map<String, String> q = t.params();
                String flow = user.optString("flow", "");
                if (!flow.isEmpty()) q.put("flow", flow);
                String enc = user.optString("encryption", "");
                if (!enc.isEmpty() && !"none".equals(enc)) q.put("encryption", enc);
                return vlessUri(host, port, id, name, q);
            }
            // vmess -> v2rayN share JSON (the app's vmess parser speaks it)
            JSONObject v = new JSONObject();
            try {
                v.put("v", "2");
                v.put("ps", name);
                v.put("add", host);
                v.put("port", String.valueOf(port));
                v.put("id", id);
                v.put("aid", String.valueOf(user.optInt("alterId", 0)));
                v.put("scy", first(user.optString("security", ""), "auto"));
                v.put("net", t.net);
                v.put("type", "");
                v.put("host", t.hostHeader);
                v.put("path", t.path);
                v.put("tls", "tls".equals(t.security) ? "tls" : "");
                if (!t.sni.isEmpty()) v.put("sni", t.sni);
                if (!t.fingerprint.isEmpty()) v.put("fp", t.fingerprint);
                if (!t.alpn.isEmpty()) v.put("alpn", t.alpn);
                if (!t.serviceName.isEmpty()) v.put("servicename", t.serviceName);
                if (t.insecure) v.put("allowInsecure", "1");
            } catch (Exception ignored) { }
            return "vmess://" + b64(v.toString());
        }

        if ("trojan".equals(proto) || "shadowsocks".equals(proto)) {
            JSONArray servers = settings.optJSONArray("servers");
            if (servers == null || servers.length() == 0) return null;
            JSONObject srv = servers.optJSONObject(0);
            if (srv == null) return null;
            String host = srv.optString("address", "").trim();
            int port = srv.optInt("port", 0);
            if (badHost(host) || badPort(port)) return null;
            String name = first(tag, host + ":" + port);
            if ("trojan".equals(proto)) {
                String pass = srv.optString("password", "");
                if (pass.isEmpty()) return null;
                return trojanUri(host, port, pass, name, readStream(
                        ob.optJSONObject("streamSettings")).params());
            }
            String method = srv.optString("method", "");
            String pass = srv.optString("password", "");
            if (method.isEmpty() || pass.isEmpty()) return null;
            return ssUri(host, port, method, pass, name);
        }
        return null; // freedom / blackhole / dns / wireguard / ...
    }

    /** streamSettings (Xray) -> normalized transport fields. */
    private static Transport readStream(JSONObject stream) {
        Transport t = new Transport();
        if (stream == null) return t;
        t.net = stream.optString("network", "tcp");
        t.security = stream.optString("security", "none");
        JSONObject tlsS = stream.optJSONObject("tlsSettings");
        if (tlsS != null) {
            t.sni = first(tlsS.optString("serverName", ""),
                    tlsS.optString("server_name", ""));
            t.fingerprint = tlsS.optString("fingerprint", "");
            t.alpn = tlsS.optString("alpn", "");
            t.insecure = tlsS.optBoolean("allowInsecure", false)
                    || tlsS.optBoolean("allow_insecure", false);
        }
        JSONObject realS = stream.optJSONObject("realitySettings");
        if (realS != null) {
            t.sni = first(realS.optString("serverName", ""),
                    realS.optString("server_name", ""), t.sni);
            t.pbk = first(realS.optString("publicKey", ""),
                    realS.optString("public_key", ""));
            t.sid = first(realS.optString("shortId", ""),
                    realS.optString("short_id", ""));
            t.spx = first(realS.optString("spiderX", ""),
                    realS.optString("spider_x", ""));
            t.fingerprint = first(realS.optString("fingerprint", ""), t.fingerprint);
        }
        JSONObject ws = stream.optJSONObject("wsSettings");
        if (ws != null) {
            t.path = ws.optString("path", "");
            t.hostHeader = headersHost(ws.optJSONObject("headers"));
        }
        JSONObject grpc = stream.optJSONObject("grpcSettings");
        if (grpc != null) {
            t.serviceName = first(grpc.optString("serviceName", ""),
                    grpc.optString("service_name", ""));
        }
        JSONObject hu = stream.optJSONObject("httpupgradeSettings");
        if (hu != null) {
            t.path = hu.optString("path", "");
            t.hostHeader = first(headersHost(hu.optJSONObject("headers")), t.hostHeader);
        }
        JSONObject sh = stream.optJSONObject("splithttpSettings");
        if (sh == null) sh = stream.optJSONObject("xhttpSettings");
        if (sh != null) {
            t.net = first(t.net, "xhttp");
            t.path = sh.optString("path", "");
            t.hostHeader = first(headersHost(sh.optJSONObject("host")), t.hostHeader);
        }
        return t;
    }

    // ------------------------------------------------------------- sing-box

    private static String fromSingBox(JSONObject o) {
        String type = o.optString("type", "");
        String host = o.optString("server", "").trim();
        int port = o.optInt("server_port", 0);
        if (badHost(host) || badPort(port)) return null;

        JSONObject tls = o.optJSONObject("tls");
        boolean tlsOn = tls != null && tls.optBoolean("enabled", false);
        String sni = tls == null ? "" : tls.optString("server_name", "");
        boolean insecure = tls != null && tls.optBoolean("insecure", false);
        String fp = "";
        if (tls != null) {
            JSONObject u = tls.optJSONObject("utls");
            if (u != null) fp = u.optString("fingerprint", "");
        }
        String pbk = "", sid = "";
        if (tls != null) {
            JSONObject r = tls.optJSONObject("reality");
            if (r != null && r.optBoolean("enabled", false)) {
                pbk = r.optString("public_key", "");
                sid = r.optString("short_id", "");
            }
        }
        String net = "tcp", path = "", hostHdr = "", service = "";
        JSONObject tr = o.optJSONObject("transport");
        if (tr != null) {
            String tt = tr.optString("type", "");
            if ("ws".equals(tt) || "httpupgrade".equals(tt)) {
                net = tt;
                path = tr.optString("path", "");
                hostHdr = headersHost(tr.optJSONObject("headers"));
            } else if ("grpc".equals(tt)) {
                net = "grpc";
                service = tr.optString("service_name", "");
            } else if ("http".equals(tt) || "h2".equals(tt)) {
                net = "tcp";
                path = tr.optString("path", "");
                hostHdr = headersHost(tr.optJSONObject("headers"));
            } else if (!"tcp".equals(tt)) {
                net = tt; // xhttp/splithttp and friends pass through
            }
        }
        String name = first(o.optString("tag", ""), host + ":" + port);

        if ("vless".equals(type)) {
            String id = o.optString("uuid", "").trim();
            if (id.isEmpty()) return null;
            Map<String, String> q = new LinkedHashMap<>();
            q.put("type", net);
            String sec = !pbk.isEmpty() ? "reality" : (tlsOn ? "tls" : "none");
            q.put("security", sec);
            if (!sni.isEmpty()) q.put("sni", sni);
            if (!fp.isEmpty()) q.put("fp", fp);
            if (!pbk.isEmpty()) q.put("pbk", pbk);
            if (!sid.isEmpty()) q.put("sid", sid);
            if (!path.isEmpty()) q.put("path", path);
            if (!hostHdr.isEmpty()) q.put("host", hostHdr);
            if (!service.isEmpty()) q.put("serviceName", service);
            if (insecure) q.put("allowInsecure", "1");
            String flow = o.optString("flow", "");
            if (!flow.isEmpty()) q.put("flow", flow);
            return vlessUri(host, port, id, name, q);
        }
        if ("vmess".equals(type)) {
            String id = o.optString("uuid", "").trim();
            if (id.isEmpty()) return null;
            JSONObject v = new JSONObject();
            try {
                v.put("v", "2");
                v.put("ps", name);
                v.put("add", host);
                v.put("port", String.valueOf(port));
                v.put("id", id);
                v.put("aid", String.valueOf(o.optInt("alter_id", 0)));
                v.put("scy", first(o.optString("security", ""), "auto"));
                v.put("net", net);
                v.put("type", "");
                v.put("host", hostHdr);
                v.put("path", path);
                v.put("tls", tlsOn ? "tls" : "");
                if (!sni.isEmpty()) v.put("sni", sni);
                if (!fp.isEmpty()) v.put("fp", fp);
                if (insecure) v.put("allowInsecure", "1");
            } catch (Exception ignored) { }
            return "vmess://" + b64(v.toString());
        }
        if ("trojan".equals(type)) {
            String pass = first(o.optString("password", ""), o.optString("auth", ""));
            if (pass.isEmpty()) return null;
            Map<String, String> q = new LinkedHashMap<>();
            q.put("type", net);
            q.put("security", tlsOn ? "tls" : "none");
            if (!sni.isEmpty()) q.put("sni", sni);
            if (!fp.isEmpty()) q.put("fp", fp);
            if (!path.isEmpty()) q.put("path", path);
            if (!hostHdr.isEmpty()) q.put("host", hostHdr);
            if (!service.isEmpty()) q.put("serviceName", service);
            if (insecure) q.put("allowInsecure", "1");
            return trojanUri(host, port, pass, name, q);
        }
        if ("shadowsocks".equals(type)) {
            String method = o.optString("method", "");
            String pass = o.optString("password", "");
            if (method.isEmpty() || pass.isEmpty()) return null;
            return ssUri(host, port, method, pass, name);
        }
        if ("hysteria2".equals(type)) {
            String pass = first(o.optString("password", ""), o.optString("auth", ""));
            if (pass.isEmpty()) return null;
            Map<String, String> q = new LinkedHashMap<>();
            if (!sni.isEmpty()) q.put("sni", sni);
            if (!fp.isEmpty()) q.put("fp", fp);
            if (insecure) q.put("insecure", "1");
            JSONObject ob = o.optJSONObject("obfs");
            if (ob != null && !ob.optString("password", "").isEmpty()) {
                q.put("obfs", first(ob.optString("type", ""), "salamander"));
                q.put("obfs-password", ob.optString("password", ""));
            }
            return "hy2://" + userInfo(pass) + "@" + hostPort(host, port)
                    + (q.isEmpty() ? "" : "?" + joinQ(q)) + "#" + frag(name);
        }
        return null;
    }

    // ------------------------------------------------------- v2rayN share

    /** v2rayN share dump entry: {"add","port","id","aid","net","path",...}. */
    private static String fromV2rayShare(JSONObject o) {
        String host = o.optString("add", "").trim();
        String id = o.optString("id", "").trim();
        int port = o.optInt("port", 0);
        if (badHost(host) || badPort(port) || id.isEmpty()) return null;
        JSONObject v = new JSONObject();
        try {
            v.put("v", "2");
            v.put("ps", first(o.optString("ps", ""), o.optString("name", ""),
                    o.optString("remark", ""), host + ":" + port));
            v.put("add", host);
            v.put("port", String.valueOf(port));
            v.put("id", id);
            v.put("aid", String.valueOf(o.optInt("aid", 0)));
            v.put("scy", first(o.optString("scy", ""), "auto"));
            String net = first(o.optString("net", ""), "tcp");
            if ("h2".equals(net) || "http".equals(net)) net = "tcp";
            if ("none".equals(o.optString("type", ""))) net = "tcp";
            v.put("net", net);
            v.put("type", "");
            v.put("host", o.optString("host", ""));
            v.put("path", o.optString("path", ""));
            String tls = o.optString("tls", "");
            v.put("tls", "tls".equalsIgnoreCase(tls) || "reality".equalsIgnoreCase(tls)
                    ? "tls" : "");
            if (!o.optString("sni", "").isEmpty()) v.put("sni", o.optString("sni"));
            if (!o.optString("fp", "").isEmpty()) v.put("fp", o.optString("fp"));
        } catch (Exception ignored) { }
        return "vmess://" + b64(v.toString());
    }

    // ------------------------------------------------------- Clash style

    private static String fromClash(JSONObject o) {
        String type = o.optString("type", "").toLowerCase(Locale.US);
        String host = o.optString("server", "").trim();
        int port = o.optInt("port", 0);
        if (badHost(host) || badPort(port)) return null;
        String name = first(o.optString("name", ""), host + ":" + port);
        boolean skipVerify = o.optBoolean("skip-cert-verify", false);
        String sni = first(o.optString("servername", ""), o.optString("sni", ""));

        if ("vless".equals(type)) {
            String id = o.optString("uuid", "").trim();
            if (id.isEmpty()) return null;
            Map<String, String> q = new LinkedHashMap<>();
            String net = first(o.optString("network", ""), "tcp");
            q.put("type", net);
            JSONObject reality = o.optJSONObject("reality-opts");
            boolean tls = o.optBoolean("tls", false);
            q.put("security", reality != null ? "reality" : (tls ? "tls" : "none"));
            if (!sni.isEmpty()) q.put("sni", sni);
            if (!o.optString("client-fingerprint", "").isEmpty())
                q.put("fp", o.optString("client-fingerprint"));
            if (reality != null) {
                String pbk = first(reality.optString("public-key", ""),
                        reality.optString("pbk", ""));
                if (pbk.isEmpty()) return null; // reality without pbk is invalid
                q.put("pbk", pbk);
                String sid = first(reality.optString("short-id", ""),
                        reality.optString("sid", ""));
                if (!sid.isEmpty()) q.put("sid", sid);
            }
            applyClashWsGrpc(o, net, q);
            if (skipVerify) q.put("allowInsecure", "1");
            if (!o.optString("flow", "").isEmpty()) q.put("flow", o.optString("flow"));
            return vlessUri(host, port, id, name, q);
        }
        if ("vmess".equals(type)) {
            String id = o.optString("uuid", "").trim();
            if (id.isEmpty()) return null;
            JSONObject v = new JSONObject();
            try {
                v.put("v", "2");
                v.put("ps", name);
                v.put("add", host);
                v.put("port", String.valueOf(port));
                v.put("id", id);
                v.put("aid", String.valueOf(o.optInt("alterId",
                        o.optInt("alterid", 0))));
                v.put("scy", first(o.optString("cipher", ""), "auto"));
                String net = first(o.optString("network", ""), "tcp");
                v.put("net", net);
                v.put("type", "");
                Map<String, String> q = new LinkedHashMap<>();
                applyClashWsGrpc(o, net, q);
                v.put("host", orEmpty(q.get("host")));
                v.put("path", orEmpty(q.get("path")));
                boolean tls = o.optBoolean("tls", false);
                v.put("tls", tls ? "tls" : "");
                if (!sni.isEmpty()) v.put("sni", sni);
                if (skipVerify) v.put("allowInsecure", "1");
            } catch (Exception ignored) { }
            return "vmess://" + b64(v.toString());
        }
        if ("trojan".equals(type)) {
            String pass = o.optString("password", "");
            if (pass.isEmpty()) return null;
            Map<String, String> q = new LinkedHashMap<>();
            q.put("type", first(o.optString("network", ""), "tcp"));
            q.put("security", "tls");
            if (!sni.isEmpty()) q.put("sni", sni);
            if (skipVerify) q.put("allowInsecure", "1");
            return trojanUri(host, port, pass, name, q);
        }
        if ("ss".equals(type)) {
            String method = first(o.optString("cipher", ""), o.optString("method", ""));
            String pass = o.optString("password", "");
            if (method.isEmpty() || pass.isEmpty()) return null;
            return ssUri(host, port, method, pass, name);
        }
        if ("hysteria2".equals(type) || "hy2".equals(type)) {
            String pass = first(o.optString("password", ""), o.optString("auth", ""));
            if (pass.isEmpty()) return null;
            Map<String, String> q = new LinkedHashMap<>();
            if (!sni.isEmpty()) q.put("sni", sni);
            if (skipVerify) q.put("insecure", "1");
            if (!o.optString("obfs", "").isEmpty()) {
                q.put("obfs", o.optString("obfs"));
                if (!o.optString("obfs-password", "").isEmpty())
                    q.put("obfs-password", o.optString("obfs-password"));
            }
            return "hy2://" + userInfo(pass) + "@" + hostPort(host, port)
                    + (q.isEmpty() ? "" : "?" + joinQ(q)) + "#" + frag(name);
        }
        return null;
    }

    private static void applyClashWsGrpc(JSONObject o, String net, Map<String, String> q) {
        if ("ws".equals(net)) {
            JSONObject ws = o.optJSONObject("ws-opts");
            if (ws != null) {
                q.put("path", ws.optString("path", ""));
                String h = headersHost(ws.optJSONObject("headers"));
                if (!h.isEmpty()) q.put("host", h);
            }
        } else if ("grpc".equals(net)) {
            if (!o.optString("grpc-service-name", "").isEmpty())
                q.put("serviceName", o.optString("grpc-service-name"));
        }
    }

    // ------------------------------------------------------- URI builders

    private static String vlessUri(String host, int port, String id,
                                   String name, Map<String, String> q) {
        return "vless://" + userInfo(id) + "@" + hostPort(host, port)
                + (q.isEmpty() ? "" : "?" + joinQ(q)) + "#" + frag(name);
    }

    private static String trojanUri(String host, int port, String pass,
                                    String name, Map<String, String> q) {
        return "trojan://" + userInfo(pass) + "@" + hostPort(host, port)
                + (q.isEmpty() ? "" : "?" + joinQ(q)) + "#" + frag(name);
    }

    private static String ssUri(String host, int port, String method,
                                String pass, String name) {
        String userinfo = b64Url(method + ":" + pass);
        return "ss://" + userinfo + "@" + hostPort(host, port) + "#" + frag(name);
    }

    private static String joinQ(Map<String, String> q) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(qEnc(v));
        }
        return sb.toString();
    }

    /** percent-encode a query value minimally (& # % space + control) */
    private static String qEnc(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '%' || c == '&' || c == '#' || c == ' ' || c == '+'
                    || c < 0x20 || c == 0x7F) {
                sb.append('%').append(String.format(Locale.US, "%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * fragment/remark: percent-encode only characters that would break the
     * URI (% # space, control) and keep Unicode (flags, Persian) readable -
     * same convention as the app's rename output.
     */
    private static String frag(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' || c == '#' || c == ' ' || c < 0x20 || c == 0x7F) {
                sb.append('%').append(String.format(Locale.US, "%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** percent-encode userinfo (uuid/password): keep unreserved, encode @:/?# */
    private static String userInfo(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.' || c == '~';
            if (safe) {
                sb.append(c);
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte x : b) {
                    sb.append('%').append(String.format(Locale.US, "%02X", x & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    private static String hostPort(String host, int port) {
        // bare IPv6 must be bracketed in a URI authority
        if (host.indexOf(':') >= 0 && host.indexOf(']') < 0) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    private static String b64(String s) {
        return Base64.getEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String headersHost(JSONObject headers) {
        if (headers == null) return "";
        return first(headers.optString("Host", ""), headers.optString("host", ""));
    }

    private static String first(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean badHost(String h) {
        if (h == null || h.isEmpty()) return true;
        String l = h.toLowerCase(Locale.US);
        // loopback entries of real configs (socks/http inbounds mirrored in
        // outbounds) are never testable targets
        return l.equals("127.0.0.1") || l.equals("localhost")
                || l.equals("0.0.0.0") || l.equals("::1") || l.equals("[::1]");
    }

    private static boolean badPort(int p) {
        return p < 1 || p > 65535;
    }

    /** normalized transport fields shared by the Xray/sing-box builders */
    private static final class Transport {
        String net = "tcp";
        String security = "none";
        String sni = "";
        String fingerprint = "";
        String pbk = "";
        String sid = "";
        String spx = "";
        String alpn = "";
        String path = "";
        String hostHeader = "";
        String serviceName = "";
        boolean insecure = false;

        Map<String, String> params() {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("type", net);
            q.put("security", security);
            if (!sni.isEmpty()) q.put("sni", sni);
            if (!fingerprint.isEmpty()) q.put("fp", fingerprint);
            if (!pbk.isEmpty()) q.put("pbk", pbk);
            if (!sid.isEmpty()) q.put("sid", sid);
            if (!spx.isEmpty()) q.put("spx", spx);
            if (!alpn.isEmpty()) q.put("alpn", alpn);
            if (!path.isEmpty()) q.put("path", path);
            if (!hostHeader.isEmpty()) q.put("host", hostHeader);
            if (!serviceName.isEmpty()) q.put("serviceName", serviceName);
            if (insecure) q.put("allowInsecure", "1");
            return q;
        }
    }
}
