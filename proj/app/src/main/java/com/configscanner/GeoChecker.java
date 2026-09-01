package com.configscanner;

import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Detects the exit country of a local SOCKS5 proxy by querying several
 * free IP-geolocation services IN PARALLEL and taking a plurality vote
 * (a 2+ service agreement is "confident"; a lone answer is flagged
 * singleVote / low confidence).
 *
 * Parallelism matters: some exits block or slow down one specific service
 * (MITM proxies, SNI filters). With 6 independent services run at once,
 * at least one usually answers even on slow or filtered exits, and the
 * total wait is bounded by one service's timeout instead of N x timeout.
 */
public class GeoChecker {

    public static class Result {
        public boolean ok = false;
        public String code = "";
        public String country = "";
        public String ip = "";
        public int votes = 0;
        public int answered = 0;
        /** true when exactly ONE service answered — treat as low confidence */
        public boolean singleVote = false;
    }

    /** {url, countryField, codeField, successField (nullable)}
     *  Order = preference; all run in parallel and the first answers win. */
    private static final String[][] SERVICES = {
            {"https://ipwho.is/", "country", "country_code", "success"},
            {"https://api.country.is/", "country", "country_code", null},
            {"https://api.ip.sb/geoip", "country", "country_code", null},
            {"https://ipinfo.io/json", "", "country", null},
            {"https://www.cloudflare.com/cdn-cgi/trace", "@@trace", "loc", null},
            {"https://ipapi.co/json/", "country_name", "country_code", "success"},
            {"https://ifconfig.co/json", "country_name", "country_code", null},
            {"https://ip-api.com/json/", "country", "country_code", null},
    };

    public static Result check(int proxyPort, int connectTimeoutSec) {
        // slow exits can need >10s for their first upstream connection, so the
        // geo budget has a 30s floor (capped at 40s) independent of the user's
        // per-server timeout
        long deadline = System.currentTimeMillis()
                + Math.max(30, Math.min(connectTimeoutSec, 40)) * 1000L;

        List<String[]> votes = new ArrayList<>();
        ExecutorService ex = Executors.newFixedThreadPool(SERVICES.length);
        // CompletionService: the FIRST service to answer is used immediately —
        // a slow service can no longer hold the shared deadline hostage.
        CompletionService<String[]> cs = new ExecutorCompletionService<>(ex);
        try {
            for (final String[] svc : SERVICES) {
                cs.submit(() ->
                        query(newProxyClient(proxyPort, connectTimeoutSec), svc));
            }
            for (int i = 0; i < SERVICES.length; i++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                Future<String[]> f;
                try {
                    f = cs.poll(left, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (f == null) break;
                try {
                    votes.add(f.get());
                } catch (Exception e) {
                    votes.add(null);
                }
            }
        } finally {
            ex.shutdownNow();
        }

        Map<String, Integer> count = new HashMap<>();
        int answered = 0;
        for (String[] v : votes) {
            if (v == null) continue;
            answered++;
            String code = v[0] == null ? "" : v[0].toUpperCase();
            if (code.isEmpty()) continue;
            Integer n = count.get(code);
            count.put(code, n == null ? 1 : n + 1);
        }

        String best = "";
        int bestN = 0;
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }

        Result r = new Result();
        r.answered = answered;
        if (!best.isEmpty()) {
            r.code = best;
            r.ok = true;
            r.votes = bestN;
            r.singleVote = (bestN < 2);
            for (String[] v : votes) {
                if (v != null && v[0] != null && v[0].toUpperCase().equals(best)) {
                    if (v[1] != null && !v[1].isEmpty()) r.country = v[1];
                    if (v[2] != null && !v[2].isEmpty()) r.ip = v[2];
                }
            }
        }
        return r;
    }

    private static OkHttpClient newProxyClient(int proxyPort, int connectTimeoutSec) {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", proxyPort));
        return new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(Math.max(8, connectTimeoutSec), TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    private static String[] query(OkHttpClient client, String[] svc) {
        String url = svc[0];
        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    AppLog.w("geo", url + " failed: HTTP " + resp.code());
                    return null;
                }
                String body = resp.body().string();
                String country, code, ip;
                if ("@@trace".equals(svc[1])) {
                    // cloudflare trace: plain-text "loc=XX" line — works on
                    // almost every tunnel and is rarely blocked
                    code = "";
                    for (String line : body.split("\\n")) {
                        if (line.startsWith("loc=")) {
                            code = line.substring(4).trim();
                            break;
                        }
                    }
                    country = "";
                    ip = "";
                } else {
                    JSONObject o = new JSONObject(body);
                    if (svc[3] != null && !o.optBoolean(svc[3], false)) {
                        AppLog.w("geo", url + " failed: success=false");
                        return null;
                    }
                    country = o.optString(svc[1], "");
                    code = o.optString(svc[2], "");
                    ip = o.optString("ip", o.optString("query", ""));
                }
                if (code.isEmpty() && country.isEmpty()) {
                    AppLog.w("geo", url + " failed: no country in response");
                    return null;
                }
                // code-only answers (ipinfo, cloudflare): fill the name locally
                if (!code.isEmpty() && country.isEmpty()) {
                    CountryData.C c = CountryData.byCode(code);
                    if (c != null) country = c.en;
                }
                code = code.toUpperCase();
                AppLog.d("geo", url + " -> " + code + " " + country);
                return new String[]{code, country, ip};
            }
        } catch (Exception e) {
            AppLog.w("geo", url + " failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /** ISO 3166-1 alpha-2 -> flag emoji */
    public static String flag(String code) {
        if (code == null || code.length() != 2) return "🏳️";
        char c1 = Character.toUpperCase(code.charAt(0));
        char c2 = Character.toUpperCase(code.charAt(1));
        if (c1 < 'A' || c1 > 'Z' || c2 < 'A' || c2 > 'Z') return "🏳️";
        return new StringBuilder()
                .appendCodePoint(0x1F1E6 + (c1 - 'A'))
                .appendCodePoint(0x1F1E6 + (c2 - 'A'))
                .toString();
    }
}
