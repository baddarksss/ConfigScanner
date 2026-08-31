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

    public static File coreDir(Context ctx) {
        return XrayManager.coreDir(ctx);
    }

    /**
     * Writes the client config for this server and starts the hysteria
     * client with a local SOCKS5 listener on `port`. Returns the process.
     */
    public static Process start(Context ctx, ServerSpec s, int port, File logFile) throws Exception {
        StringBuilder y = new StringBuilder();
        y.append("server: ").append(s.host).append(":").append(s.port).append("\n");
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
                binary(ctx).getAbsolutePath(), "client", "-c", cfg.getAbsolutePath(), "-l", "debug");
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
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
