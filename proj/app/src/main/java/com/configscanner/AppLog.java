package com.configscanner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Simple thread-safe app log: kept in memory (for the log dialog)
 * and appended to a persistent file so nothing is lost after a crash.
 */
public class AppLog {

    private static final Object LOCK = new Object();
    private static final StringBuilder BUF = new StringBuilder();
    private static final int MAX = 150_000;
    /** hard cap for the on-disk log (bytes); the tail is kept when exceeded */
    private static final long MAXFILE = 1_000_000;
    private static File logFile;

    public static void init(Context ctx) {
        logFile = new File(ctx.getFilesDir(), "app.log");
        // Reload the previous session so a crash logged before a restart is
        // still visible in the Log dialog (memory is fresh on every start).
        try {
            if (logFile.exists() && logFile.length() > 0) {
                BufferedReader br = new BufferedReader(new java.io.FileReader(logFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                synchronized (LOCK) {
                    if (BUF.length() == 0) {
                        if (sb.length() > MAX) sb.delete(0, sb.length() - MAX);
                        BUF.append(sb);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    public static void d(String tag, String msg) { write("D", tag, msg); }
    public static void i(String tag, String msg) { write("I", tag, msg); }
    public static void w(String tag, String msg) { write("W", tag, msg); }
    public static void e(String tag, String msg) { write("E", tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        write("E", tag, msg + "\n" + stackTrace(t));
    }

    public static String dump() {
        synchronized (LOCK) {
            return BUF.toString();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            BUF.setLength(0);
        }
        if (logFile != null) {
            try { logFile.delete(); } catch (Exception ignored) { }
        }
    }

    private static void write(String level, String tag, String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = ts + " " + level + " " + tag + ": " + msg + "\n";
        synchronized (LOCK) {
            BUF.append(line);
            if (BUF.length() > MAX) {
                BUF.delete(0, BUF.length() - MAX);
            }
        }
        if (logFile != null) {
            try {
                trimFile();
                FileWriter fw = new FileWriter(logFile, true);
                fw.write(line);
                fw.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Keep the on-disk log bounded: above the cap, keep only the last 200 KB. */
    private static void trimFile() {
        try {
            long len = logFile.length();
            if (len <= MAXFILE) return;
            long keep = 200_000;
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r");
            raf.seek(len - keep);
            byte[] rest = new byte[(int) keep];
            raf.readFully(rest);
            raf.close();
            FileWriter fw = new FileWriter(logFile, false);
            fw.write(new String(rest, java.nio.charset.StandardCharsets.UTF_8));
            fw.close();
        } catch (Exception ignored) {
        }
    }

    public static String stackTrace(Throwable t) {
        if (t == null) return "";
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** Last maxLines of a text file, joined. For xray error logs. */
    public static String fileTail(File f, int maxLines) {
        if (f == null || !f.exists()) return "";
        try {
            java.util.List<String> lines = java.util.Collections
                    .nCopies(maxLines, null);
            int i = 0;
            try (BufferedReader br = new BufferedReader(new java.io.FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.set(i % maxLines, line);
                    i++;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int k = Math.max(0, i - maxLines); k < i; k++) {
                String l = lines.get(k % maxLines);
                if (l != null) sb.append(l).append(" | ");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
