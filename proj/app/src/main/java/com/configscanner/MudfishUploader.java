package com.configscanner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Uploads the run output to bin.mudfish.net (Text Bin) and returns the
 * public RAW link — one config per line, exactly as posted.
 *
 * API (reverse-engineered from the site's own JS):
 *   POST https://bin.mudfish.net/api/text
 *   body: {"text": "...", "ttl": "0"}   (ttl 0 = never expires)
 *   reply: {"status":200, "tid":"154-5213-2115", ...}
 * The page button labelled "Raw" points to /r/<tid> — that IS the raw text.
 */
final class MudfishUploader {

    interface Callback {
        void done(String rawUrl, String error);
    }

    static final int MAX_TEXT_BYTES = 900_000;

    static void upload(String text, Callback cb) {
        new Thread(() -> {
            try {
                if (text == null) throw new IllegalArgumentException("text is null");
                int textBytes = text.getBytes(StandardCharsets.UTF_8).length;
                if (textBytes > MAX_TEXT_BYTES) {
                    throw new IllegalArgumentException("text bin too large (" + textBytes
                            + " bytes, max " + MAX_TEXT_BYTES + ")");
                }
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("text", text);
                body.put("ttl", "0");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://bin.mudfish.net/api/text").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(payload.length);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload);
                }
                int code = c.getResponseCode();
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        code >= 400 ? c.getErrorStream() : c.getInputStream(),
                        StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = r.readLine()) != null) sb.append(ln);
                r.close();
                if (code != 200) {
                    throw new Exception("HTTP " + code);
                }
                org.json.JSONObject res = new org.json.JSONObject(sb.toString());
                if (res.optInt("status") != 200 || !res.has("tid")) {
                    throw new Exception(res.optString("error", "unexpected reply"));
                }
                String raw = "https://bin.mudfish.net/r/" + res.getString("tid");
                cb.done(raw, null);
            } catch (Exception e) {
                cb.done(null, String.valueOf(e.getMessage()));
            }
        }, "mudfish-upload").start();
    }

    private MudfishUploader() { }
}
