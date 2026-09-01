package com.configscanner;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds a full Xray client config that listens on a local SOCKS5 port
 * and routes everything through the given server.
 */
public class XrayConfig {

    public static String buildFull(ServerSpec s, int port) throws Exception {
        return buildFull(s, port, null);
    }

    public static String buildFull(ServerSpec s, int port, String logPath) throws Exception {
        JSONObject c = new JSONObject();

        JSONObject log = new JSONObject();
        // "info" (not "warning"): failed dial/TLS/DNS errors are logged at info
        // level, and the app reads the tail of this file when a geo check fails.
        log.put("loglevel", "info");
        // xray >= 26.1 renamed the log fields: "error" (was "logPath") and
        // "access" (was "accessLog"). The old names are silently ignored,
        // which is why the log file used to stay empty.
        if (logPath != null && !logPath.isEmpty()) {
            log.put("error", logPath);
            log.put("access", logPath);
        }
        c.put("log", log);

        JSONObject in = new JSONObject();
        in.put("tag", "in");
        in.put("listen", "127.0.0.1");
        in.put("port", port);
        in.put("protocol", "socks");
        JSONObject inSet = new JSONObject();
        inSet.put("udp", false);
        inSet.put("auth", "noauth");
        inSet.put("ip", "127.0.0.1");
        in.put("settings", inSet);
        JSONArray ins = new JSONArray();
        ins.put(in);
        c.put("inbounds", ins);

        JSONObject proxyOut = new JSONObject(s.buildOutbound());
        JSONObject fragOut = null;
        JSONObject fragSet = ServerSpec.fragmentSettings(s.fragmentRaw);
        if (fragSet != null) {
            // v2rayNG-style fragment: the dial to the server goes through a
            // freedom outbound that applies the TCP fragment settings
            proxyOut.put("tag", "proxy");
            JSONObject st = proxyOut.optJSONObject("streamSettings");
            if (st == null) { st = new JSONObject(); proxyOut.put("streamSettings", st); }
            JSONObject so = st.optJSONObject("sockopt");
            if (so == null) { so = new JSONObject(); st.put("sockopt", so); }
            so.put("dialerProxy", "fragment");
            so.put("tcpNoDelay", true);
            fragOut = new JSONObject();
            fragOut.put("tag", "fragment");
            fragOut.put("protocol", "freedom");
            fragOut.put("settings", fragSet);
            fragOut.put("streamSettings", new JSONObject()
                    .put("sockopt", new JSONObject().put("tcpNoDelay", true)));
        }
        JSONArray outs = new JSONArray();
        outs.put(proxyOut);
        JSONObject bh = new JSONObject();
        bh.put("protocol", "blackhole");
        bh.put("tag", "block");
        outs.put(bh);
        if (fragOut != null) outs.put(fragOut);
        c.put("outbounds", outs);

        return c.toString();
    }
}
