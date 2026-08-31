package com.wpnfa.configscan;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Xray binary.
 *
 * IMPORTANT: Since Android 10 (Q), apps with targetSdk >= 29 are blocked by
 * SELinux from exec'ing binaries in their writable private data directory
 * (WX violation, issuetracker.google.com/issues/128554619). The official
 * workaround is to ship the binary via jniLibs so the package installer
 * extracts it to the read-only /data/app native library directory, where
 * exec is still allowed.
 */
public class XrayManager {

    /** Name the binary is packaged under in jniLibs (lib*.so convention). */
    public static final String BIN_NAME = "libxray.so";

    public static File coreDir(Context ctx) {
        return new File(ctx.getFilesDir(), "core");
    }

    /**
     * The active xray binary. An in-app-updated copy living in the writable
     * core dir (filesDir/core) wins; otherwise the jniLibs copy extracted by
     * the OS at install time (read-only, always exec-allowed) is used.
     */
    public static File binary(Context ctx) {
        File updated = new File(coreDir(ctx), "xray");
        if (updated.exists() && updated.canExecute()) return updated;
        return new File(ctx.getApplicationInfo().nativeLibraryDir, BIN_NAME);
    }

    /** True if a binary updated in-app (in the writable core dir) exists. */
    public static boolean hasUpdatedBinary(Context ctx) {
        File updated = new File(coreDir(ctx), "xray");
        return updated.exists() && updated.canExecute();
    }

    /** Logs device + binary state. Called once at app start. */
    public static void diagnose(Context ctx) {
        try {
            AppLog.i("diag", "android=" + Build.VERSION.RELEASE
                    + " api=" + Build.VERSION.SDK_INT
                    + " model=" + Build.MANUFACTURER + " " + Build.MODEL
                    + " abis=" + Build.SUPPORTED_ABIS[0]);
        } catch (Exception e) {
            AppLog.e("diag", "diag failed", e);
        }
        ensureBinary(ctx);
    }

    /**
     * The binary is extracted by the OS at install time into
     * nativeLibraryDir. We just verify it's there and executable.
     */
    public static void ensureBinary(Context ctx) {
        File bin = binary(ctx);
        AppLog.i("xray", "core at " + bin.getAbsolutePath()
                + " exists=" + bin.exists()
                + " size=" + (bin.exists() ? bin.length() : -1)
                + " exec=" + (bin.exists() ? bin.canExecute() : false));
        if (!bin.exists()) {
            AppLog.e("xray", "BINARY MISSING in nativeLibraryDir — reinstall app");
            return;
        }
        if (!bin.canExecute()) {
            AppLog.w("xray", "no exec bit — trying to fix");
            bin.setExecutable(true, true);
            if (!bin.canExecute()) {
                tryChmod(bin);
            }
            AppLog.i("xray", "exec after fix=" + bin.canExecute());
        }
    }

    static void tryChmod(File bin) {
        try {
            Process ch = new ProcessBuilder("/system/bin/sh", "-c",
                    "chmod 755 " + bin.getAbsolutePath())
                    .redirectErrorStream(true).start();
            String out = readAll(ch);
            int rc = -1;
            try { rc = ch.waitFor(); } catch (Exception ignored) { }
            AppLog.i("xray", "chmod rc=" + rc + " out=" + out);
        } catch (Exception e2) {
            AppLog.e("xray", "chmod failed: " + e2.getMessage());
        }
    }

    static ProcessBuilder pbOf(File binFile, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = binFile.getAbsolutePath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        try {
            pb.directory(binFile.getParentFile());
        } catch (Exception ignored) {
        }
        return pb;
    }

    /**
     * Starts xray with the given config. On exec failure, logs the exact
     * error, tries chmod 755 and retries once.
     */
    public static Process start(File binFile, File configFile, File logFile) throws Exception {
        try {
            return launch(binFile, configFile, logFile);
        } catch (Exception e) {
            AppLog.e("xray", "start failed: " + e.getMessage() + " — trying chmod+retry");
            tryChmod(binFile);
            try {
                return launch(binFile, configFile, logFile);
            } catch (Exception e3) {
                throw new Exception("cannot start xray: " + e3.getMessage(), e3);
            }
        }
    }

    private static Process launch(File binFile, File configFile, File logFile) throws Exception {
        ProcessBuilder pb = pbOf(binFile, "-c", configFile.getAbsolutePath());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        return pb.start();
    }

    public static void stop(Process p) {
        if (p == null) return;
        try {
            p.destroy();
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        try {
            p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    public static boolean waitForPort(int port, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                try { s.close(); } catch (Exception ignored) { }
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    return false;
                }
            }
        }
        return false;
    }

    /** True if something is already listening on the given TCP port. */
    public static boolean portInUse(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Runs `xray version`. Returns "26.3.27" style version, or a descriptive
     * failure reason (never throws) so the UI can show why.
     */
    public static String version(File bin) {
        try {
            ProcessBuilder pb = pbOf(bin, "version");
            Process p = pb.start();
            String out = readAll(p);
            if (out.trim().isEmpty()) out = readError(p);
            int rc = -1;
            try {
                if (p.waitFor(3, TimeUnit.SECONDS)) rc = p.exitValue();
            } catch (Exception ignored) { }
            p.destroy();
            String first = out.trim();
            if (first.length() > 200) first = first.substring(0, 200);
            AppLog.i("xray", "version rc=" + rc + " out=" + first.replace("\n", " / "));
            if (first.isEmpty()) return "unknown (no output, rc=" + rc + ")";
            String[] parts = first.split(" ");
            // "Xray 26.3.27 (Xray, Penetrates Everything.) ..."
            if (parts.length >= 2) return parts[1];
            return first;
        } catch (Exception e) {
            AppLog.e("xray", "version failed: " + e.getMessage());
            return "unknown (" + e.getMessage() + ")";
        }
    }

    /**
     * Runs `xray version` on the given binary and throws if it doesn't work.
     * Returns the first output line.
     */
    public static String verify(File bin) throws Exception {
        ProcessBuilder pb = pbOf(bin, "version");
        Process p = pb.start();
        String out = readAll(p);
        if (out.trim().isEmpty()) out = readError(p);
        int rc = -1;
        try {
            if (p.waitFor(5, TimeUnit.SECONDS)) rc = p.exitValue();
        } catch (Exception ignored) { }
        p.destroy();
        String first = out.trim();
        if (first.length() > 150) first = first.substring(0, 150);
        if (first.isEmpty() || rc != 0) {
            throw new Exception("verify failed rc=" + rc + " out=" + first);
        }
        return first;
    }

    /** Thrown when the device's SELinux refuses to exec the updated core. */
    public static class CoreExecBlockedException extends Exception {
        public CoreExecBlockedException(String msg) { super(msg); }
    }

    /**
     * Installs a new core binary into the WRITABLE core dir
     * (filesDir/core) — the read-only native library dir can never be
     * updated from the app. Strategy: stage as a temp file, verify it by
     * exec, then move it over coreDir/xray (which binary() prefers from
     * then on). If anything fails the working core stays untouched.
     *
     * On stock Android 10+ devices SELinux may refuse to exec binaries from
     * the app's data dir (WX rule). In that case the staged file is
     * removed and a CoreExecBlockedException is thrown; the caller should
     * then direct the user to install a newer APK instead.
     *
     * @return the verified first line of `xray version`
     */
    public static String installNewBinary(Context ctx, InputStream xrayStream) throws Exception {
        File dir = coreDir(ctx);
        if (!dir.exists()) dir.mkdirs();
        File newBin = new File(dir, "xray_new");
        try { newBin.delete(); } catch (Exception ignored) { }
        FileOutputStream out = new FileOutputStream(newBin);
        byte[] buf = new byte[16384];
        int n;
        while ((n = xrayStream.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        AppLog.i("update", "staged " + newBin.getAbsolutePath() + " size=" + newBin.length());

        newBin.setExecutable(true, true);
        if (!newBin.canExecute()) {
            AppLog.w("update", "staged file has no exec bit — trying chmod");
            tryChmod(newBin);
        }

        String vline;
        try {
            vline = verify(newBin);
        } catch (Exception ve) {
            try { newBin.delete(); } catch (Exception ignored) { }
            AppLog.e("update", "verify failed: " + ve.getMessage());
            throw new CoreExecBlockedException(
                    "this device blocks running an updated core from the app storage ("
                    + ve.getMessage() + ") — a newer APK is required");
        }
        AppLog.i("update", "verified staged binary: " + vline);

        File target = new File(dir, "xray");
        boolean renamed = newBin.renameTo(target);
        if (!renamed) {
            try { target.delete(); } catch (Exception ignored) { }
            renamed = newBin.renameTo(target);
        }
        if (!renamed) {
            try { newBin.delete(); } catch (Exception ignored) { }
            throw new Exception("could not move updated core into " + dir);
        }
        AppLog.i("update", "core updated OK -> " + target.getAbsolutePath());
        return vline;
    }

    static String readAll(Process p) {
        return readStream(p.getInputStream());
    }

    static String readError(Process p) {
        return readStream(p.getErrorStream());
    }

    static String readStream(InputStream is) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
                if (sb.length() > 4000) break;
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
