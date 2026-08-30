package com.wpnfa.configscan;

import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Detects the exit country of a local SOCKS5 proxy by querying several
 * free IP-geolocation services IN PARALLEL and taking a majority vote.
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
    }

    /** {url, countryField, codeField, successField (nullable)} */
    private static final String[][] SERVICES = {
            {"https://ipwho.is/", "country", "country_code", "success"},
            {"https://api.country.is/", "country", "country_code", null},
            {"https://ipapi.co/json/", "country_name", "country_code", "success"},
            {"https://api.ip.sb/geoip", "country", "country_code", null},
            {"https://ifconfig.co/json", "country_name", "country_code", null},
            {"https://ip-api.com/json/", "country", "country_code", null},
    };

    public static Result check(int proxyPort, int connectTimeoutSec) {
        long deadline = System.currentTimeMillis()
                + Math.max(10, Math.min(connectTimeoutSec, 25)) * 1000L;

        List<String[]> votes = new ArrayList<>();
        List<Future<String[]>> futures = new ArrayList<>();
        ExecutorService ex = Executors.newFixedThreadPool(SERVICES.length);
        try {
            for (final String[] svc : SERVICES) {
                futures.add(ex.submit(() ->
                        query(newProxyClient(proxyPort, connectTimeoutSec), svc)));
            }
            for (Future<String[]> f : futures) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    f.cancel(true);
                    continue;
                }
                try {
                    votes.add(f.get(left, TimeUnit.MILLISECONDS));
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
                JSONObject o = new JSONObject(body);
                if (svc[3] != null && !o.optBoolean(svc[3], false)) {
                    AppLog.w("geo", url + " failed: success=false");
                    return null;
                }
                String country = o.optString(svc[1], "");
                String code = o.optString(svc[2], "");
                String ip = o.optString("ip", o.optString("query", ""));
                if (code.isEmpty() && country.isEmpty()) {
                    AppLog.w("geo", url + " failed: no country in response");
                    return null;
                }
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
