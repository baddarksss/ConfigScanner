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
        /** tunnel is up but an immediate single-request probe was reset —
         *  the exit does not pass traffic for this client. */
        public boolean deadTunnel = false;
        /** true when exactly ONE service answered — treat as low confidence */
        public boolean singleVote = false;
    }

    /** {url, countryField, codeField, successField (nullable)}
     *  Order = preference; all run in parallel and the first answers win. */
    // Only services that actually answer through proxy exits (measured over
    // several runs). ipapi.co / ifconfig.co / ip-api.com were dead 100% of the
    // time (ip-api has no HTTPS on its free plan) so they were dropped.
    private static final String[][] SERVICES = {
            {"https://ipwho.is/", "country", "country_code", "success"},
            {"https://api.country.is/", "country", "country_code", null},
            {"https://api.ip.sb/geoip", "country", "country_code", null},
            {"https://ipinfo.io/json", "", "country", null},
            {"https://www.cloudflare.com/cdn-cgi/trace", "@@trace", "loc", null},
    };

    public static Result check(int proxyPort, int connectTimeoutSec) {
        // slow exits can need >10s for their first upstream connection, so the
        // geo budget has a 30s floor (capped at 40s) independent of the user's
        // per-server timeout
        long deadline = System.currentTimeMillis()
                + Math.max(30, Math.min(connectTimeoutSec, 40)) * 1000L;

        // Flaky exits tend to drop a whole PARALLEL burst at once (all requests
        // die together) but answer a lone request moments later — so the budget
        // is spent as small bursts with cooldowns, ending in two SEQUENTIAL
        // single requests, which is the pattern real clients (one connection at
        // a time) use and the one flaky exits tolerate.
        //   wave 1: 2 in parallel (the two most robust sources)
        //   wave 2: 2 in parallel (two more)
        //   wave 3: 1 sequential single
        //   wave 4: 1 sequential single
        String[][] wave1 = {SERVICES[4], SERVICES[2]}; // cloudflare, ip.sb
        String[][] wave2 = {SERVICES[0], SERVICES[3], SERVICES[1]}; // ipwho.is, ipinfo, country.is
        String[][] wave3 = {SERVICES[4]};              // lone cloudflare
        String[][] wave4 = {SERVICES[2]};              // lone ip.sb

        List<String[]> votes = new ArrayList<>();
        // Fast lone-request probe first: an exit that resets single requests
        // will not carry the bursts either — report it as a dead tunnel
        // instead of burning the whole geo budget on a ⚠️ result.
        Probe pr = probeTunnel(proxyPort, connectTimeoutSec);
        if (pr.kind == PKind.DEAD) {
            Result dead = new Result();
            dead.deadTunnel = true;
            return dead;
        }
        if (pr.vote != null) votes.add(pr.vote);
        collectWave(wave1, proxyPort, connectTimeoutSec, deadline, votes);
        if (topVote(votes) >= 2) return makeResult(votes);
        cooldown(deadline);
        collectWave(wave2, proxyPort, connectTimeoutSec, deadline, votes);
        if (topVote(votes) >= 2) return makeResult(votes);
        cooldown(deadline);
        collectWave(wave3, proxyPort, connectTimeoutSec, deadline, votes);
        if (topVote(votes) >= 2) return makeResult(votes);
        cooldown(deadline);
        collectWave(wave4, proxyPort, connectTimeoutSec, deadline, votes);
        return makeResult(votes);
    }

    /** ~1s pause between waves so a rate-limiting/throttling exit recovers. */
    private enum PKind { OK, DEAD, SLOW }

    private static final class Probe {
        final PKind kind;
        final String[] vote;
        Probe(PKind k, String[] v) { kind = k; vote = v; }
    }

    /** Two lone requests (cloudflare, then ip.sb on immediate reset).
     *  OK = a vote is ready; SLOW = slow exit, let the waves try;
     *  DEAD = the tunnel does not pass traffic at all. */
    private static Probe probeTunnel(int proxyPort, int connectTimeoutSec) {
        OkHttpClient client = newProxyClient(proxyPort, connectTimeoutSec);
        try {
            Request req = new Request.Builder().url(SERVICES[4][0]).get().build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    for (String line : resp.body().string().split("\n")) {
                        if (line.startsWith("loc=")) {
                            String code = line.substring(4).trim().toUpperCase();
                            if (code.length() == 2) return new Probe(PKind.OK, countryVote(code));
                        }
                    }
                }
                return new Probe(PKind.SLOW, null);
            }
        } catch (java.net.SocketTimeoutException e) {
            return new Probe(PKind.SLOW, null);
        } catch (Exception e) {
            AppLog.d("geo", "probe reset: " + e.getClass().getSimpleName());
        }
        try {
            Request req = new Request.Builder().url(SERVICES[2][0]).get().build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\"country_code\"\\s*:\\s*\"([A-Za-z]{2})\"")
                            .matcher(resp.body().string());
                    if (m.find()) return new Probe(PKind.OK, countryVote(m.group(1).toUpperCase()));
                }
                return new Probe(PKind.SLOW, null);
            }
        } catch (java.net.SocketTimeoutException e) {
            return new Probe(PKind.SLOW, null);
        } catch (Exception e) {
            AppLog.d("geo", "probe2 reset: " + e.getClass().getSimpleName());
        }
        return new Probe(PKind.DEAD, null);
    }

    private static String[] countryVote(String code) {
        String name = "";
        CountryData.C c = CountryData.byCode(code);
        if (c != null) name = c.en;
        return new String[]{code, name, ""};
    }

    private static void cooldown(long deadline) {
        long left = deadline - System.currentTimeMillis() - 1200;
        if (left <= 0) return;
        try {
            Thread.sleep(Math.min(1000, left));
        } catch (InterruptedException ignored) { }
    }

    private static void collectWave(String[][] wave, int proxyPort, int connectTimeoutSec,
                                    long deadline, List<String[]> votes) {
        if (System.currentTimeMillis() >= deadline) return;
        ExecutorService ex = Executors.newFixedThreadPool(wave.length);
        CompletionService<String[]> cs = new ExecutorCompletionService<>(ex);
        try {
            for (final String[] svc : wave) {
                cs.submit(() ->
                        query(newProxyClient(proxyPort, connectTimeoutSec), svc));
            }
            for (int i = 0; i < wave.length; i++) {
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
    }

    /** Highest number of votes for a single country code (0 when none). */
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
