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
        /** v1.0.59: how many providers failed + the first failure reason —
         *  this is what makes "country unknown" diagnosable */
        public int failed = 0;
        public int total = 0;
        public String firstError = "";
        /** true when successful providers reported more than one exit IP */
        public boolean ipConflict = false;
    }

    /** {url, countryField, codeField, successField (nullable)} */
    private static final String[][] SERVICES = {
            {"https://www.cloudflare.com/cdn-cgi/trace", "@@trace", "loc", null},
            {"https://ipwho.is/", "country", "country_code", "success"},
            {"https://api.country.is/", "", "country", null},
            {"https://api.ip.sb/geoip", "country", "country_code", null},
            {"https://ipinfo.io/json", "", "country", null},
            // v1.0.70: two more independent HTTPS sources — several servers
            // (e.g. Alexhost/Moldova-announced ranges) failed or were
            // rate-limited on every old source and ended up "unknown"
            {"https://get.geojs.io/v1/ip/geo.json", "", "country_code", null},
            {"https://ifconfig.co/json", "", "country_iso", null},
            // ip-api.com removed (v1.0.63): plaintext HTTP — the geo answer
            // (which becomes the server remark) must not travel unencrypted
    };

    public static Result check(int proxyPort, int connectTimeoutSec) {
        // v1.0.59: hard cap at 15s. Country detection never needed more —
        // providers that CAN answer do so in <5s; the old behavior waited
        // the full user timeout (30s+) for the remaining dead providers on
        // EVERY unroutable tunnel, turning a 50-server run into 15 minutes.
        int timeout = Math.min(15, Math.max(8, connectTimeoutSec));
        long deadline = System.currentTimeMillis() + timeout * 1000L;

        List<String[]> votes = new ArrayList<>();
        // v1.0.70: per-service failure tracking (identity-keyed) — drives the
        // retry round and the failure diagnostics without double counting
        final java.util.concurrent.ConcurrentMap<String[], String[]> errBySvc =
                new java.util.concurrent.ConcurrentHashMap<>();
        ExecutorService ex = Executors.newFixedThreadPool(SERVICES.length);
        CompletionService<String[]> cs = new ExecutorCompletionService<>(ex);

        // ONE client for every geo probe (was: a new OkHttpClient per
        // service = 6 clients/sockets per server tested)
        OkHttpClient client = newProxyClient(proxyPort, timeout);
        final long hardEnd = deadline;

        try {
            for (final String[] svc : SERVICES) {
                cs.submit(() -> {
                    String[] res = query(client, svc);
                    if (res == null || res[0] == null || res[0].isEmpty())
                        errBySvc.putIfAbsent(svc, res);
                    return res;
                });
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
                        // If 2 or more services agree on the same country, we
                        // can return early
                        // Stop only at 3 votes. With 7 providers, 3 is a strict majority.
                        // Stopping at 2 could hide a later tie and resurrect the old wrong country pick.
                        if (topVote(votes) >= 3) break;
                        // v1.0.59: one vote in hand — only wait a short grace
                        // for a confirming second vote, not the full deadline
                        deadline = Math.min(deadline,
                                System.currentTimeMillis() + 6000L);
                    }
                } catch (Exception ignored) { }
            }

            Result r = makeResult(votes);

            // v1.0.70: retry round — no country decided yet, so give every
            // provider that errored ONE more shot inside the same hard
            // deadline. Fresh tunnels often fail their first probes
            // (slow TLS handshake, one-off rate limit); a retry frequently
            // turns "unknown" into a confirmed country.
            if (!r.ok && !errBySvc.isEmpty()) {
                deadline = Math.min(hardEnd, System.currentTimeMillis() + 6000L);
                for (final String[] svc : errBySvc.keySet().toArray(new String[0][])) {
                    cs.submit(() -> {
                        String[] res = query(client, svc);
                        if (res != null && res[0] != null && !res[0].isEmpty())
                            errBySvc.remove(svc);
                        else
                            errBySvc.put(svc, res);
                        return res;
                    });
                }
                for (int i = 0; i < errBySvc.size() + 1; i++) {
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
                            if (topVote(votes) >= 3) break;
                        }
                    } catch (Exception ignored) { }
                }
                r = makeResult(votes);
            }

            r.total = SERVICES.length;
            r.ipConflict = hasIpConflict(votes);
            r.failed = errBySvc.size();
            for (String[] svc : SERVICES) {
                String[] e = errBySvc.get(svc);
                if (e != null && e[3] != null && !e[3].isEmpty()) {
                    r.firstError = e[3];
                    break;
                }
            }
            return r;
        } finally {
            ex.shutdownNow();
        }
    }

    private static boolean hasIpConflict(List<String[]> votes) {
        java.util.HashSet<String> ips = new java.util.HashSet<>();
        for (String[] v : votes) {
            if (v == null || v.length < 3 || v[2] == null) continue;
            String ip = v[2].trim();
            if (!ip.isEmpty()) ips.add(ip);
        }
        return ips.size() > 1;
    }

    private static int topVote(List<String[]> votes) {
        Map<String, Integer> count = new HashMap<>();
        for (String[] v : votes) {
            if (v == null || v.length == 0 || v[0] == null || v[0].isEmpty()) continue;
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
            if (v == null || v.length == 0 || v[0] == null || v[0].isEmpty()) continue;
            answered++;
            String code = v[0].toUpperCase();
            count.put(code, count.getOrDefault(code, 0) + 1);
        }

        // deterministic pick: most votes first, tie -> alphabetically first
        // code (HashMap order must never leak into the result — review fix)
        String best = "";
        int bestN = 0, secondN = 0;
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            int n = e.getValue();
            if (n > bestN) {
                secondN = bestN;
                bestN = n;
                best = e.getKey();
            } else {
                if (n > secondN) secondN = n;
                if (n == bestN && (best.isEmpty()
                        || e.getKey().compareTo(best) < 0)) {
                    best = e.getKey();
                }
            }
        }

        Result r = new Result();
        r.answered = answered;
        // v1.0.63: ONE provider alone never decides the country.
        // v1.0.64 (review fix): an AMBIGUOUS tie (e.g. US 2 / DE 2) is not a
        // result either — no alphabetical coin flip may pick a country.
        if (bestN >= 2 && secondN < bestN) {
            r.code = best;
            r.ok = true;
            r.votes = bestN;
            r.singleVote = false;
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
        } else {
            // single vote (no confirmation) or an ambiguous tie — kept for
            // diagnostics only, never trusted as a result
            r.singleVote = (bestN < 2);
            r.code = "";
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
                    String err = "HTTP " + resp.code() + " (" + hostOf(url) + ")";
                    AppLog.w("geo", url + " failed: HTTP " + resp.code());
                    return new String[]{"", "", "", err};
                }
                String body = resp.body().string();
                String country = "", code = "", ip = "";
                if ("@@trace".equals(svc[1])) {
                    for (String line : body.split("\\n")) {
                        if (line.startsWith("loc=")) {
                            code = line.substring(4).trim();
                        } else if (line.startsWith("ip=")) {
                            ip = line.substring(3).trim();
                        }
                    }
                } else {
                    JSONObject o = new JSONObject(body);
                    if (svc[3] != null && !o.optBoolean(svc[3], false)) {
                        String err = "success=false (" + hostOf(url) + ")";
                        AppLog.w("geo", url + " failed: success=false");
                        return new String[]{"", "", "", err};
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
                    String err = "no country in response (" + hostOf(url) + ")";
                    AppLog.w("geo", url + " failed: no country in response");
                    return new String[]{"", "", "", err};
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
            String err = e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " (" + hostOf(url) + ")";
            AppLog.w("geo", url + " error: " + err);
            return new String[]{"", "", "", err};
        }
    }

    /** hostname part of a service URL for compact error lines */
    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** ISO 3166-1 alpha-2 -> flag emoji */
    public static String flag(String code) {
        if ("CDN".equalsIgnoreCase(code)) return "\u2601\uFE0F"; // ☁️
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
