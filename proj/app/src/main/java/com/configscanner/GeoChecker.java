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
 * Detects the exit country of a local SOCKS5 proxy by querying multiple
 * free IP-geolocation services in parallel.
 */
public class GeoChecker {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    public static class Result {
        public boolean ok = false;
        public String code = "";
        public String country = "";
        public String ip = "";
        public int votes = 0;
        public int answered = 0;
        public boolean deadTunnel = false;
        /** true when exactly ONE service answered */
        public boolean singleVote = false;
    }

    /** {url, countryField, codeField, successField (nullable)} */
    private static final String[][] SERVICES = {
            {"https://www.cloudflare.com/cdn-cgi/trace", "@@trace", "loc", null},
            {"https://ipwho.is/", "country", "country_code", "success"},
            {"https://api.country.is/", "", "country", null},
            {"https://api.ip.sb/geoip", "country", "country_code", null},
            {"https://ipinfo.io/json", "", "country", null},
            {"http://ip-api.com/json/", "country", "countryCode", null},
    };

    public static Result check(int proxyPort, int connectTimeoutSec) {
        int timeout = Math.max(8, connectTimeoutSec);
        long deadline = System.currentTimeMillis() + timeout * 1000L;

        List<String[]> votes = new ArrayList<>();
        ExecutorService ex = Executors.newFixedThreadPool(SERVICES.length);
        CompletionService<String[]> cs = new ExecutorCompletionService<>(ex);

        try {
            for (final String[] svc : SERVICES) {
                cs.submit(() -> query(newProxyClient(proxyPort, timeout), svc));
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
                    String[] res = f.get();
                    if (res != null && res[0] != null && !res[0].isEmpty()) {
                        votes.add(res);
                        // If 2 or more services agree on the same country, we can return early
                        if (topVote(votes) >= 2) break;
                    }
                } catch (Exception ignored) { }
            }
        } finally {
            ex.shutdownNow();
        }

        return makeResult(votes);
    }

    private static int topVote(List<String[]> votes) {
        Map<String, Integer> count = new HashMap<>();
        for (String[] v : votes) {
            if (v == null || v[0] == null || v[0].isEmpty()) continue;
            count.merge(v[0].toUpperCase(), 1, Integer::sum);
        }
        int top = 0;
        for (int n : count.values()) top = Math.max(top, n);
        return top;
    }

    private static Result makeResult(List<String[]> votes) {
        Map<String, Integer> count = new HashMap<>();
        int answered = 0;
        for (String[] v : votes) {
            if (v == null || v[0] == null || v[0].isEmpty()) continue;
            answered++;
            String code = v[0].toUpperCase();
            count.put(code, count.getOrDefault(code, 0) + 1);
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
                if (v != null && v[0] != null && v[0].equalsIgnoreCase(best)) {
                    if (v[1] != null && !v[1].isEmpty()) r.country = v[1];
                    if (v[2] != null && !v[2].isEmpty()) r.ip = v[2];
                }
            }
            if (r.country.isEmpty()) {
                CountryData.C c = CountryData.byCode(r.code);
                if (c != null) r.country = c.en;
            }
        }
        return r;
    }

    private static OkHttpClient newProxyClient(int proxyPort, int timeoutSec) {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", proxyPort));
        return new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    private static String[] query(OkHttpClient client, String[] svc) {
        String url = svc[0];
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get().build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    AppLog.w("geo", url + " failed: HTTP " + resp.code());
                    return null;
                }
                String body = resp.body().string();
                String country = "", code = "", ip = "";
                if ("@@trace".equals(svc[1])) {
                    for (String line : body.split("\\n")) {
                        if (line.startsWith("loc=")) {
                            code = line.substring(4).trim();
                            break;
                        }
                    }
                } else {
                    JSONObject o = new JSONObject(body);
                    if (svc[3] != null && !o.optBoolean(svc[3], false)) {
                        AppLog.w("geo", url + " failed: success=false");
                        return null;
                    }
                    if (!svc[1].isEmpty()) country = o.optString(svc[1], "");
                    if (!svc[2].isEmpty()) code = o.optString(svc[2], "");
                    ip = o.optString("ip", o.optString("query", ""));
                }
                if (code.isEmpty() && country.length() == 2) {
                    code = country;
                    country = "";
                }
                if (code.isEmpty() && country.isEmpty()) {
                    AppLog.w("geo", url + " failed: no country in response");
                    return null;
                }
                if (!code.isEmpty() && country.isEmpty()) {
                    CountryData.C c = CountryData.byCode(code);
                    if (c != null) country = c.en;
                }
                code = code.toUpperCase();
                AppLog.d("geo", url + " -> " + code + " " + country);
                return new String[]{code, country, ip};
            }
        } catch (Exception e) {
            AppLog.w("geo", url + " error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
