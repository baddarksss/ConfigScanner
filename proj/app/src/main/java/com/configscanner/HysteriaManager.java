package com.configscanner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Manages the native Hysteria2 client binary (libhysteria.so in jniLibs).
 *
 * Why a second core? Xray-core's Hysteria2 support cannot do salamander/gecko
 * obfuscation: its finalmask salamander implementation never sends or reads
 * packets (verified by packet capture — the handshake dies instantly with
 * "tls: internal error"), and upstream closed the feature request as
 * not-planned (XTLS/Xray-core#5712). The Hysteria reference client handles
 * plain hy2, salamander, gecko, insecure, SNI and auth out of the box, so
 * every hysteria2 link is tested through it while all other protocols keep
 * using Xray.
 */
public class HysteriaManager {

    public static final String BIN_NAME = "libhysteria.so";

    public static File binary(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, BIN_NAME);
    }

    /** Hysteria is shipped as a native library, so fail clearly on an ABI
     * without a packaged binary instead of producing a misleading connection
     * failure later. */
    public static boolean isSupportedAbi() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        if (abis == null) return false;
        for (String abi : abis) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    public static File coreDir(Context ctx) {
        return XrayManager.coreDir(ctx);
    }

    /**
     * Writes the client config for this server and starts the hysteria
     * client with a local SOCKS5 listener on `port`. Returns the process.
     */
    public static Process start(Context ctx, ServerSpec s, int port, File logFile) throws Exception {
        if (!isSupportedAbi()) {
            throw new Exception("Hysteria2 is unavailable on this CPU ABI. Supported ABI: arm64-v8a");
        }
        File bin = binary(ctx);
        if (!bin.isFile() || !bin.canExecute()) {
            throw new Exception("Hysteria2 native binary is missing for this device");
        }
        StringBuilder y = new StringBuilder();
        // a bare IPv6 literal ("server: 2001:db8::1:443") is invalid — the
        // client needs brackets: [2001:db8::1]:443
        String serverAddr = (s.host != null && s.host.contains(":") && s.host.matches("[0-9a-fA-F:]+"))
                ? "[" + s.host + "]:" + s.port
                : s.host + ":" + s.port;
        y.append("server: ").append(yamlQuote(serverAddr)).append("\n");
        y.append("auth: ").append(yamlQuote(s.password)).append("\n");
        y.append("tls:\n");
        String sni = (s.sni != null && !s.sni.isEmpty()) ? s.sni : s.host;
        y.append("  sni: ").append(yamlQuote(sni)).append("\n");
        if (s.allowInsecure) {
            y.append("  insecure: true\n");
        }
        if ("salamander".equalsIgnoreCase(s.obfs) || "gecko".equalsIgnoreCase(s.obfs)) {
            if (s.obfsParam == null || s.obfsParam.isEmpty()) {
                throw new Exception("obfs password missing in link");
            }
            String t = s.obfs.toLowerCase();
            y.append("obfs:\n");
            y.append("  type: ").append(t).append("\n");
            y.append("  ").append(t).append(":\n");
            y.append("    password: ").append(yamlQuote(s.obfsParam)).append("\n");
        }
        y.append("socks5:\n");
        y.append("  listen: 127.0.0.1:").append(port).append("\n");

        File cfg = new File(coreDir(ctx), "hy2_" + port + ".yaml");
        try (FileOutputStream fos = new FileOutputStream(cfg)) {
            fos.write(y.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        AppLog.d("hy2", "cfg " + cfg.getName() + ":\n" + y);

        ProcessBuilder pb = new ProcessBuilder(
                bin.getAbsolutePath(), "client", "-c", cfg.getAbsolutePath(), "-l", "debug");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        try {
            pb.directory(coreDir(ctx));
        } catch (Exception ignored) {
        }
        return pb.start();
    }

    /** Runs `hysteria version`. Returns the first line or a failure reason. */
    public static String version(File bin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "version");
            // Hysteria may print failures to stderr. Merge it into stdout so
            // version errors are visible instead of becoming "unknown".
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                    if (sb.length() > 300) break;
                }
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
            // `hysteria version` prints an ASCII banner first; the version
            // number is on the line starting with "Version:".
            String v = "unknown";
            for (String line : sb.toString().split("\n")) {
                String t = line.trim();
                if (t.startsWith("Version:")) {
                    v = t.substring("Version:".length()).trim();
                    break;
                }
            }
            AppLog.i("hy2", "version out=" + v);
            return v;
        } catch (Exception e) {
            return "unknown (" + e.getMessage() + ")";
        }
    }

    static String yamlQuote(String v) {
        if (v == null) return "\"\"";
        // full double-quoted YAML scalar escaping: backslash, quote and all
        // control characters (a raw newline inside a password would abort
        // the whole config parse)
        StringBuilder sb = new StringBuilder(v.length() + 8);
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\x%02x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
