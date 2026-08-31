package com.configscanner;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    /** Kept in sync with versionName in build.gradle (single source of truth). */
    public static final String VERSION = BuildConfig.VERSION_NAME;

    // ---------------------------------------------------------------- fields
    private EditText input;
    private EditText channelEdit;
    private MaterialSwitch channelSwitch;
    private SeekBar parallelBar;
    private TextView parallelValue;
    private SeekBar timeoutBar;
    private TextView timeoutValue;
    private MaterialButton btnStart;
    private MaterialCardView progressCard;
    private WaterCircleView waterCircle;
    private TextView progressLabel;
    private TextView progressCount;
    private TextView progressStatus;
    private ScrollView pageTest;
    private ScrollView pageSettings;
    private BottomNavigationView bottomNav;
    private ScrollView outputScroll;
    private TextView outputView;
    private TextView outCount;
    private TextView headerChip;
    private TextView coreStatus;
    private TextView coreVersionLabel;
    private MaterialButton btnCoreTest;
    private MaterialButton btnUpdateFile;
    private MaterialButton btnCoreUpdate;
    private com.google.android.material.checkbox.MaterialCheckBox coreBetaCheck;
    private MaterialButton btnAppUpdate;
    private TextView appUpdateStatus;
    private TextView appVersionLabel;
    private MaterialButton btnAbout;
    private MaterialButton btnLog;
    private android.widget.ProgressBar coreProgressBar;
    private android.widget.ProgressBar appProgressBar;
    private TextView flagStrip;
    private android.view.View themeHeader, themeOptions, langHeader, langOptions;
    private TextView themeValue, themeChevron, langValue, langChevron;
    private MaterialButton btnThemeSystem, btnThemeDark, btnThemeLight;
    private MaterialButton btnLangSystem, btnLangFa, btnLangEn;

    /** Last selected bottom-nav tab; static so it survives activity recreation. */
    private static int sLastNav = 0; // 0=test 1=settings

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private int themeMode = 0; // 0=system 1=dark 2=light

    private ExecutorService pool;
    private volatile boolean running = false;

    private final List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
    private final List<String> flagList = new ArrayList<>();
    private int basePort = 21000;
    private final java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private int totalCount = 0;
    private final AtomicBoolean runFinished = new AtomicBoolean(false);
    private volatile boolean destroyed = false;
    private final java.util.Set<Process> activeEngines = java.util.Collections
            .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private ActivityResultLauncher<String[]> fileImportLauncher;
    private ActivityResultLauncher<String> fileExportLauncher;
    private ActivityResultLauncher<String[]> coreFileLauncher;
    private ActivityResultLauncher<String> logExportLauncher;

    // ------------------------------------------------------------------- setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        installCrashLogger();
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        themeMode = prefs.getInt("theme_mode", 0);
        applyTheme();
        setContentView(R.layout.activity_main);

        fileImportLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onImportFile);
        fileExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), this::onExportFile);
        coreFileLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onCoreFilePicked);
        logExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), this::onLogExport);

        bindViews();
        restorePrefs();
        wireEvents();
        updateStartState();
    }

    private void bindViews() {
        input = findViewById(R.id.input);
        channelEdit = findViewById(R.id.channelEdit);
        channelSwitch = findViewById(R.id.channelSwitch);
        parallelBar = findViewById(R.id.parallelBar);
        parallelValue = findViewById(R.id.parallelValue);
        timeoutBar = findViewById(R.id.timeoutBar);
        timeoutValue = findViewById(R.id.timeoutValue);
        btnStart = findViewById(R.id.btnStart);
        progressCard = findViewById(R.id.progressCard);
        waterCircle = findViewById(R.id.waterCircle);
        progressLabel = findViewById(R.id.progressLabel);
        progressCount = findViewById(R.id.progressCount);
        progressStatus = findViewById(R.id.progressStatus);
        pageTest = findViewById(R.id.pageTest);
        pageSettings = findViewById(R.id.pageSettings);
        bottomNav = findViewById(R.id.bottomNav);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        outCount = findViewById(R.id.outCount);
        headerChip = findViewById(R.id.headerChip);
        coreStatus = findViewById(R.id.coreStatus);
        coreVersionLabel = findViewById(R.id.coreVersionLabel);
        btnCoreTest = findViewById(R.id.btnCoreTest);
        btnUpdateFile = findViewById(R.id.btnUpdateFile);
        btnCoreUpdate = findViewById(R.id.btnCoreUpdate);
        coreBetaCheck = findViewById(R.id.coreBetaCheck);
        btnAppUpdate = findViewById(R.id.btnAppUpdate);
        appUpdateStatus = findViewById(R.id.appUpdateStatus);
        appVersionLabel = findViewById(R.id.appVersionLabel);
        btnAbout = findViewById(R.id.btnAbout);
        btnLog = findViewById(R.id.btnLog);
        coreProgressBar = findViewById(R.id.coreProgressBar);
        appProgressBar = findViewById(R.id.appProgressBar);
        flagStrip = findViewById(R.id.flagStrip);
        themeHeader = findViewById(R.id.themeHeader);
        themeOptions = findViewById(R.id.themeOptions);
        langHeader = findViewById(R.id.langHeader);
        langOptions = findViewById(R.id.langOptions);
        themeValue = findViewById(R.id.themeValue);
        themeChevron = findViewById(R.id.themeChevron);
        langValue = findViewById(R.id.langValue);
        langChevron = findViewById(R.id.langChevron);
        btnThemeSystem = findViewById(R.id.btnThemeSystem);
        btnThemeDark = findViewById(R.id.btnThemeDark);
        btnThemeLight = findViewById(R.id.btnThemeLight);
        btnLangSystem = findViewById(R.id.btnLangSystem);
        btnLangFa = findViewById(R.id.btnLangFa);
        btnLangEn = findViewById(R.id.btnLangEn);
        headerChip.setText("App v" + VERSION);
        appVersionLabel.setText("v" + VERSION);
        themeValue.setText(themeLabel());
        updateLangHeader();
    }

    private void updateLangHeader() {
        int i = currentLangIndex();
        langValue.setText(i == 1 ? getString(R.string.lang_fa)
                : i == 2 ? getString(R.string.lang_en)
                : getString(R.string.lang_system));
    }

    private void setThemeOpen(boolean open) {
        themeOptions.setVisibility(open ? View.VISIBLE : View.GONE);
        themeChevron.setText(open ? "⌃" : "⌄");
    }

    private void setLangOpen(boolean open) {
        langOptions.setVisibility(open ? View.VISIBLE : View.GONE);
        langChevron.setText(open ? "⌃" : "⌄");
    }

    private void applyThemeMode(int which) {
        themeMode = which;
        prefs.edit().putInt("theme_mode", themeMode).apply();
        applyTheme(); // triggers activity recreate
        setThemeOpen(false);
    }

    /** Remove the APK copy of the version we are currently running (already installed). */
    private void cleanupStaleApks() {
        try {
            File dir = new File(getExternalFilesDir(null), "updates");
            if (dir == null || !dir.exists()) return;
            File stale = new File(dir, "ConfigScanner-v" + VERSION + ".apk");
            if (stale.exists() && stale.delete()) {
                AppLog.i("appupdate", "removed installed apk copy (now running v" + VERSION + ")");
            }
        } catch (Exception ignored) { }
    }

    private void applyTheme() {
        int m = themeMode == 1 ? AppCompatDelegate.MODE_NIGHT_YES
                : themeMode == 2 ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(m);
    }

    private String themeLabel() {
        return getString(themeMode == 1 ? R.string.theme_dark
                : themeMode == 2 ? R.string.theme_light
                : R.string.theme_system);
    }

    private void restorePrefs() {
        channelEdit.setText(prefs.getString("channel", ""));
        channelSwitch.setChecked(prefs.getBoolean("include_channel", true));
        int parallel = prefs.getInt("parallel", 5);
        int timeoutSec = prefs.getInt("timeout_sec", 15);
        parallelBar.setProgress(parallel - 1, false);
        parallelValue.setText(String.valueOf(parallel));
        timeoutBar.setProgress(timeoutSec - 5, false);
        timeoutValue.setText(getString(R.string.timeout_seconds, timeoutSec));
    }

    private void savePrefs() {
        String ch = channelEdit.getText().toString().trim();
        if (!ch.isEmpty()) prefs.edit().putString("channel", ch).apply();
        prefs.edit()
                .putBoolean("include_channel", channelSwitch.isChecked())
                .putInt("parallel", parallelBar.getProgress() + 1)
                .putInt("timeout_sec", timeoutBar.getProgress() + 5)
                .apply();
    }

    // ------------------------------------------------------------------- language

    private int currentLangIndex() {
        String t = prefs.getString("lang", "");
        if ("fa".equals(t)) return 1;
        if ("en".equals(t)) return 2;
        return 0;
    }

    private void applyLanguage(int idx) {
        String tag = idx == 1 ? "fa" : idx == 2 ? "en" : "";
        prefs.edit().putString("lang", tag).apply();
        // AppCompat persists the choice automatically and recreates the
        // activity so every view re-inflates with the new locale.
        AppCompatDelegate.setApplicationLocales(tag.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag));
    }

    // ------------------------------------------------------------------- events

    private void wireEvents() {
        parallelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                parallelValue.setText(String.valueOf(p + 1));
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                savePrefs();
            }
        });
        timeoutBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                timeoutValue.setText(getString(R.string.timeout_seconds, p + 5));
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                savePrefs();
            }
        });

        ((MaterialButton) findViewById(R.id.btnFile))
                .setOnClickListener(v -> fileImportLauncher
                        .launch(new String[]{"text/*"}));
        ((MaterialButton) findViewById(R.id.btnPaste)).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData c = cm.getPrimaryClip();
                if (c != null && c.getItemCount() > 0 && c.getItemAt(0).getText() != null) {
                    input.append("\n" + c.getItemAt(0).getText());
                    updateStartState();
                }
            }
        });
        ((MaterialButton) findViewById(R.id.btnClear)).setOnClickListener(v -> {
            input.setText("");
            updateStartState();
        });
        ((MaterialButton) findViewById(R.id.btnCopy)).setOnClickListener(v -> copyAll());
        ((MaterialButton) findViewById(R.id.btnSave)).setOnClickListener(v -> {
            if (outputLines.isEmpty()) {
                toast(getString(R.string.toast_output_empty));
                return;
            }
            String fn = "configs_" + new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date()) + ".txt";
            fileExportLauncher.launch(fn);
        });
        ((MaterialButton) findViewById(R.id.btnClearOut)).setOnClickListener(v -> {
            outputLines.clear();
            refreshOutput();
        });

        btnStart.setOnClickListener(v -> {
            if (running) {
                stopRun();
            } else {
                startRun();
            }
        });

        btnCoreTest.setOnClickListener(v -> testCore());
        btnUpdateFile.setOnClickListener(v -> coreFileLauncher.launch(new String[]{"application/zip", "application/octet-stream", "*/*"}));
        coreBetaCheck.setChecked(prefs.getBoolean("core_beta", false));
        coreBetaCheck.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("core_beta", checked).apply());
        btnCoreUpdate.setOnClickListener(v -> updateFromGithub());
        btnAppUpdate.setOnClickListener(v -> checkForAppUpdate());

        btnAbout.setOnClickListener(v -> showAbout());
        btnLog.setOnClickListener(v -> showLog());
        flagStrip.setOnClickListener(v -> {
            synchronized (flagList) {
                if (flagList.isEmpty()) { toast(getString(R.string.toast_no_flags)); return; }
                String all = String.join("  ", flagList);
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("flags", all));
                toast(getString(R.string.toast_flags_copied));
            }
        });

        themeHeader.setOnClickListener(v -> {
            boolean open = themeOptions.getVisibility() != View.VISIBLE;
            setThemeOpen(open);
            if (open) setLangOpen(false);
        });
        langHeader.setOnClickListener(v -> {
            boolean open = langOptions.getVisibility() != View.VISIBLE;
            setLangOpen(open);
            if (open) setThemeOpen(false);
        });
        btnThemeSystem.setOnClickListener(v -> applyThemeMode(0));
        btnThemeDark.setOnClickListener(v -> applyThemeMode(1));
        btnThemeLight.setOnClickListener(v -> applyThemeMode(2));
        btnLangSystem.setOnClickListener(v -> { applyLanguage(0); setLangOpen(false); });
        btnLangFa.setOnClickListener(v -> { applyLanguage(1); setLangOpen(false); });
        btnLangEn.setOnClickListener(v -> { applyLanguage(2); setLangOpen(false); });
        cleanupStaleApks();

        bottomNav.setOnItemSelectedListener(item -> {
            boolean test = item.getItemId() == R.id.navTest;
            sLastNav = test ? 0 : 1;
            applyNavPage(test);
            return true;
        });
        // restore the tab the user was on (language/theme switches recreate the
        // activity and would otherwise drop back to the test page)
        if (sLastNav == 1) {
            pageTest.setVisibility(View.GONE);
            pageSettings.setVisibility(View.VISIBLE);
            bottomNav.getMenu().findItem(R.id.navSettings).setChecked(true);
        }

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                updateStartState();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
    }

    // ------------------------------------------------------------------- input

    private void onImportFile(Uri uri) {
        if (uri == null) return;
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            input.append("\n" + sb);
            updateStartState();
            toast(getString(R.string.toast_file_loaded));
        } catch (Exception e) {
            toast(getString(R.string.toast_file_error, String.valueOf(e.getMessage())));
        }
    }

    private void onLogExport(Uri uri) {
        if (uri == null) return;
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            String raw = AppLog.dump();
            if (raw == null || raw.isEmpty()) raw = "(log is empty)";
            os.write(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            toast(getString(R.string.toast_log_saved));
        } catch (Exception e) {
            toast(getString(R.string.toast_save_error, String.valueOf(e.getMessage())));
        }
    }

    private void onExportFile(Uri uri) {
        if (uri == null) return;
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            String all = String.join("\n", outputLines) + "\n";
            os.write(all.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            toast(getString(R.string.toast_saved));
        } catch (Exception e) {
            toast(getString(R.string.toast_save_error, String.valueOf(e.getMessage())));
        }
    }

    private void onCoreFilePicked(Uri uri) {
        if (uri == null) return;
        toast(getString(R.string.toast_checking_core));
        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                // The zip contains a single binary named "xray" (flat layout)
                // write stream to temp file first
                File tmp = new File(getCacheDir(), "core_tmp.zip");
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                java.util.zip.ZipFile zf2 = new java.util.zip.ZipFile(tmp);
                java.util.zip.ZipEntry e = zf2.getEntry("xray");
                if (e == null) {
                    // fallback: first entry
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf2.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry ze = en.nextElement();
                        if (!ze.isDirectory() && ze.getSize() > 1_000_000) {
                            e = ze;
                            break;
                        }
                    }
                }
                if (e == null) throw new Exception("xray binary not found in zip");
                String vline;
                try (InputStream es = zf2.getInputStream(e)) {
                    vline = XrayManager.installNewBinary(this, es);
                }
                zf2.close();
                tmp.delete();
                final String vl = vline;
                postUi(() -> {
                    toast(getString(R.string.toast_core_updated, vl));
                    refreshCoreStatus(true);
                });
            } catch (XrayManager.CoreExecBlockedException ex) {
                postUi(() -> coreBlocked(ex.getMessage()));
            } catch (Exception ex) {
                final String m = ex.getMessage();
                postUi(() -> toast(getString(R.string.toast_update_file_failed, String.valueOf(m))));
            }
        }).start();
    }

    // ------------------------------------------------------------------- run

    private void updateStartState() {
        if (input != null) {
            btnStart.setEnabled(!input.getText().toString().trim().isEmpty() || running);
        }
    }

    private void applyNavPage(boolean test) {
        pageTest.setVisibility(test ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(test ? View.GONE : View.VISIBLE);
    }

    /** Crashes are written to the in-app log so a bug report is one tap away. */
    private void installCrashLogger() {
        Thread.UncaughtExceptionHandler defaultH = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                String trace = "v" + VERSION + " thread=" + t.getName() + "\n" + sw.toString();
                AppLog.e("crash", trace);
                // belt & braces: direct append so a crash is never lost
                java.io.File f = new java.io.File(getFilesDir(), "app.log");
                java.io.FileWriter fw = new java.io.FileWriter(f, true);
                fw.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " E crash: " + trace + "\n");
                fw.close();
                android.util.Log.e("ConfigScanner", "uncaught", e);
            } catch (Exception ignored) { }
            if (defaultH != null) defaultH.uncaughtException(t, e);
        });
    }

    private void startRun() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            toast(getString(R.string.toast_paste_first));
            return;
        }
        savePrefs();

        // parse all lines
        List<ServerSpec> servers = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            ServerSpec s = ServerSpec.parse(t);
            if (s != null) servers.add(s);
        }
        if (servers.isEmpty()) {
            toast(getString(R.string.toast_no_config));
            return;
        }

        synchronized (flagList) { flagList.clear(); }
        flagStrip.setText("");

        File dir = XrayManager.coreDir(this);
        if (!dir.exists()) dir.mkdirs();
        cleanupEngineLogs(dir);

        // ensure binary
        XrayManager.ensureBinary(this);
        File bin = XrayManager.binary(this);
        if (!bin.exists()) {
            toast(getString(R.string.toast_core_missing));
            return;
        }

        boolean hasHy2 = false;
        for (ServerSpec s : servers) {
            if ("hysteria2".equals(s.protocol)) {
                hasHy2 = true;
                break;
            }
        }
        running = true;
        runFinished.set(false);
        doneCount.set(0);
        totalCount = servers.size();
        basePort = 21000 + (int) (Math.random() * 500);
        AppLog.d("run", "basePort=" + basePort + " servers=" + servers.size()
                + " xray=" + XrayManager.version(bin)
                + (hasHy2 ? " hy2=" + HysteriaManager.version(HysteriaManager.binary(this)) : ""));
        outputLines.clear();
        refreshOutput();
        btnStart.setText(R.string.btn_stop);

        // The progress card (big water circle) is only visible while a run
        // is active — hidden again is handled when the run finishes/stops.
        progressCard.setVisibility(View.VISIBLE);
        waterCircle.setProgress(0f);
        waterCircle.setRunning(true);
        updateProgress();

        pool = Executors.newFixedThreadPool(Math.max(1, parallelBar.getProgress() + 1));
        final int timeoutSec = timeoutBar.getProgress() + 5;

        new Thread(() -> {
            for (int i = 0; i < servers.size(); i++) {
                final ServerSpec s = servers.get(i);
                final int idx = i;
                final int port = findFreePort(basePort + idx);
                pool.submit(() -> testOne(s, port, timeoutSec));
            }
        }).start();
    }

    /** Keep at most the 24 most recent engine log/config files. */
    private void cleanupEngineLogs(File dir) {
        try {
            File[] logs = dir.listFiles((d, n) ->
                    n.startsWith("xray_") || n.startsWith("xrayw_")
                    || n.startsWith("hy2_") || n.startsWith("cfg_"));
            if (logs == null || logs.length <= 24) return;
            java.util.Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 24; i < logs.length; i++) logs[i].delete();
        } catch (Exception ignored) { }
    }

    /** Returns the first TCP port not already listening, starting at `start`. */
    private int findFreePort(int start) {
        for (int p = start; p < start + 500; p++) {
            if (!XrayManager.portInUse(p)) return p;
        }
        AppLog.w("run", "no free port found in [" + start + "," + (start + 500) + ")");
        return start + 500;
    }

    /** Post a UI update, skipping it if the activity is gone (rotations/
     *  background kills would otherwise touch stale views). */
    private void postUi(Runnable r) {
        if (!destroyed) main.post(r);
    }

    private void stopRun() {
        if (pool != null) pool.shutdownNow();
        for (Process p : activeEngines) {
            try { p.destroyForcibly(); } catch (Exception ignored) { }
        }
        running = false;
        postUi(() -> {
            btnStart.setText(R.string.btn_start);
            waterCircle.setRunning(false);
            progressStatus.setText(R.string.progress_stopped);
            toast(getString(R.string.toast_stopped));
            main.postDelayed(() -> {
                if (!running) progressCard.setVisibility(View.GONE);
            }, 2500);
        });
    }

    private void testOne(ServerSpec s, int port, int timeoutSec) {
        String hostport = s.host + ":" + s.port;
        AppLog.d("test", ">> " + s.protocol + " " + hostport);
        status(s.protocol + " " + hostport);

        // Engine choice: Xray-core's salamander/gecko UDP obfs is broken
        // upstream (its finalmask wrapper never sends or reads packets —
        // verified by packet capture), so every hysteria2 link runs through
        // the native Hysteria client. All other protocols use Xray.
        Process engine = null;
        File engineLog;
        try {
            // obfs type without a password: cannot be tested at all.
            // (inside the try so the finally below still counts the server and
            //  finishes the run when every server takes this path)
            if ("hysteria2".equals(s.protocol)
                    && s.obfs != null && !s.obfs.isEmpty()
                    && !"plain".equalsIgnoreCase(s.obfs)
                    && (s.obfsParam == null || s.obfsParam.isEmpty())) {
                doneCount.incrementAndGet();
                String base = s.name.isEmpty() ? hostport : s.name;
                outputLines.add("⚠️ " + base + " — " + getString(R.string.res_obfs_nopass));
                AppLog.w("test", "SKIP " + hostport + " (hysteria2 obfs without password)");
                refreshOutput();
                autoScroll();
                return;
            }
            if ("hysteria2".equals(s.protocol)) {
                engineLog = new File(XrayManager.coreDir(this), "hy2_" + port + ".log");
                engine = HysteriaManager.start(this, s, port, engineLog);
            } else {
                // insecure=1 with plain TLS: fetch the server's leaf cert and
                // pin it (allowInsecure no longer exists in modern Xray)
                if (s.allowInsecure && "tls".equals(s.security)) {
                    String sni = (s.sni != null && !s.sni.isEmpty()) ? s.sni : s.host;
                    s.pinnedCertHash = CertPinner.pin(s.host, s.port, sni, 8000);
                    AppLog.d("test", "certpin " + s.host + ":" + s.port + " sni=" + sni
                            + " hash=" + (s.pinnedCertHash.isEmpty() ? "FAILED" : s.pinnedCertHash));
                }
                File xrayOwnLog = new File(XrayManager.coreDir(this), "xrayw_" + port + ".log");
                String cfg = XrayConfig.buildFull(s, port, xrayOwnLog.getAbsolutePath());
                File cfgFile = new File(XrayManager.coreDir(this), "cfg_" + port + ".json");
                try (FileOutputStream fos = new FileOutputStream(cfgFile)) {
                    fos.write(cfg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                // xray writes its error log to this file ("error" field,
                // renamed from "logPath" in 26.x); the app tails it on failure
                engineLog = xrayOwnLog;
                engine = XrayManager.start(XrayManager.binary(this), cfgFile,
                        new File(XrayManager.coreDir(this), "xray_" + port + ".log"));
            }

            activeEngines.add(engine);
            Thread.sleep(300);
            if (!engine.isAlive()) {
                String earlyTail = AppLog.fileTail(engineLog, 8);
                AppLog.w("test", "engine exited early rc=" + engine.exitValue()
                        + " log=[" + earlyTail + "]");
                // The official Xray core no longer ships some protocols
                // (ssr, tuic, shadowtls, anytls, snici) — surface that clearly
                if (earlyTail.contains("unknown config id")) {
                    fail(s, getString(R.string.res_core_unsupported, s.protocol));
                    return;
                }
                fail(s, getString(R.string.res_engine_error));
                return;
            }

            // wait for SOCKS port
            long waitMs = Math.min(8000, 3000 + 100L * timeoutSec);
            boolean up = XrayManager.waitForPort(port, waitMs);
            if (!up) {
                AppLog.w("test", "port " + port + " not up after " + waitMs + "ms"
                        + " engine alive=" + engine.isAlive()
                        + " log=[" + AppLog.fileTail(engineLog, 8) + "]");
                fail(s, getString(R.string.res_connect_failed));
                return;
            }
            AppLog.d("test", "port " + port + " up; engine alive=" + engine.isAlive()
                    + " logSize=" + engineLog.length());

            // geo check via SOCKS
            long t0 = System.currentTimeMillis();
            GeoChecker.Result geo = GeoChecker.check(port, timeoutSec);
            long took = (System.currentTimeMillis() - t0) / 1000;
            AppLog.d("test", "geo code=" + geo.code + " country=" + geo.country
                    + " ip=" + geo.ip + " ok=" + geo.ok
                    + " votes=" + geo.votes + "/" + geo.answered
                    + (geo.singleVote ? " (single-vote, low confidence)" : "")
                    + " took=" + took + "s");

            if (geo.ok && !geo.code.isEmpty()) {
                String countryName = geo.country.isEmpty()
                        ? geo.code : geo.country;
                String flag = GeoChecker.flag(geo.code);
                String channel = prefs.getString("channel", "");
                boolean incCh = prefs.getBoolean("include_channel", true);
                String suffix = (incCh && !channel.isEmpty())
                        ? " | " + channel : "";
                String renamed = flag + " " + countryName + suffix;
                String renamedRaw = renameUri(s.raw, renamed);
                AppLog.d("test", "OK " + geo.code + " -> " + renamed);
                doneCount.incrementAndGet();
                status(String.format("✓ [%d/%d] %s = %s", doneCount.get(), totalCount,
                        hostport, geo.code));
                success(renamedRaw, flag);
            } else {
                doneCount.incrementAndGet();
                AppLog.w("test", "connected but country unknown — engine log tail: ["
                        + AppLog.fileTail(engineLog, 8) + "]");
                fail(s, getString(R.string.res_country_unknown));
            }
        } catch (Exception e) {
            AppLog.e("test", "error " + hostport + " " + e.getMessage());
            doneCount.incrementAndGet();
            fail(s, String.valueOf(e.getMessage()));
        } finally {
            activeEngines.remove(engine);
            if (engine != null) {
                try {
                    engine.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
            updateProgress();
            if (doneCount.get() >= totalCount) {
                finishRun();
            }
        }
    }

    /** Called once when every server of the current run has finished. */
    private void finishRun() {
        if (runFinished.compareAndSet(false, true)) {
            postUi(() -> {
                running = false;
                btnStart.setText(R.string.btn_start);
                btnStart.setEnabled(true);
                waterCircle.setRunning(false);
                waterCircle.setProgress(totalCount == 0 ? 0 : 100f);
                progressLabel.setText(R.string.progress_done);
                progressStatus.setText(R.string.progress_finished);
                // back to idle: hide the circle a few seconds after finishing
                main.postDelayed(() -> {
                    if (!running) progressCard.setVisibility(View.GONE);
                }, 2500);
            });
        }
    }

    private void success(String renamedLine, String flag) {
        outputLines.add(renamedLine);
        if (flag != null && !flag.isEmpty()) {
            final List<String> copy;
            synchronized (flagList) {
                flagList.add(flag);
                copy = new ArrayList<>(flagList);
            }
            postUi(() -> flagStrip.setText(String.join("  ", copy)));
        }
        refreshOutput();
        autoScroll();
    }

    private void fail(ServerSpec s, String reason) {
        String base = s.name.isEmpty() ? (s.host + ":" + s.port) : s.name;
        outputLines.add("❌ " + base + " — " + reason);
        AppLog.e("test", "FAIL " + base + ": " + reason);
        refreshOutput();
        autoScroll();
    }

    /** Replace the last #name segment of the raw URI */
    private String renameUri(String raw, String newName) {
        String enc = encodeFragment(newName);
        int i = raw.lastIndexOf('#');
        if (i < 0) return raw + "#" + enc;
        return raw.substring(0, i + 1) + enc;
    }

    /**
     * Percent-encode only characters that are illegal in a URI fragment
     * (spaces, control chars) and leave Unicode (flags, Persian, ...) raw —
     * that keeps exported links both valid and human-readable.
     */
    private String encodeFragment(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c < 0x20 || c == 0x7f) {
                sb.append('%').append(String.format(java.util.Locale.US, "%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------- ui

    private void updateProgress() {
        final int done = doneCount.get();
        final int total = totalCount;
        postUi(() -> {
            int pct = total == 0 ? 0 : (int) (100.0 * done / total);
            waterCircle.setProgress(pct);
            progressCount.setText(done + "/" + total);
            if (running && done < total) {
                progressLabel.setText(R.string.progress_testing);
            } else if (!running && done > 0) {
                progressLabel.setText(R.string.progress_done);
            }
        });
    }

    private void status(String s) {
        postUi(() -> progressStatus.setText(s));
    }

    private void refreshOutput() {
        final int n;
        StringBuilder sb = new StringBuilder();
        synchronized (outputLines) {
            n = outputLines.size();
            for (String l : outputLines) sb.append(l).append("\n");
        }
        // Capture scroll state BEFORE replacing the text so the user's
        // reading position survives the update (no jitter).
        final int curY = outputScroll.getScrollY();
        final boolean nearBottom = isNearBottom();
        postUi(() -> {
            outputView.setText(sb.toString());
            outCount.setText(getString(R.string.lines_count, n));
            if (nearBottom) {
                outputScroll.fullScroll(ScrollView.FOCUS_DOWN);
            } else {
                View child = outputScroll.getChildAt(0);
                int max = child == null ? 0
                        : Math.max(0, child.getHeight() - outputScroll.getHeight());
                outputScroll.scrollTo(0, Math.min(curY, max));
            }
        });
    }

    private boolean isNearBottom() {
        View child = outputScroll.getChildAt(0);
        if (child == null) return true;
        return (outputScroll.getScrollY() + outputScroll.getHeight())
                >= (child.getHeight() - 60);
    }

    private void autoScroll() {
        // Only follow the bottom if the user is already at the bottom;
        // otherwise their reading position must not be disturbed.
        main.postDelayed(() -> {
            if (isNearBottom()) outputScroll.fullScroll(ScrollView.FOCUS_DOWN);
        }, 50);
    }

    private void copyAll() {
        String all = String.join("\n", outputLines);
        if (all.isEmpty()) {
            toast(getString(R.string.toast_output_empty));
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("configs", all));
        toast(getString(R.string.toast_copy_done));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------- xray

    private void refreshCoreStatus(boolean force) {
        new Thread(() -> {
            File bin = XrayManager.binary(this);
            String v = XrayManager.version(bin);
            postUi(() -> {
                coreStatus.setText(bin.exists()
                        ? getString(R.string.core_ok) : getString(R.string.core_missing));
                coreVersionLabel.setText(getString(R.string.core_version, v));
            });
        }).start();
    }

    private void testCore() {
        toast(getString(R.string.toast_core_testing));
        new Thread(() -> {
            File bin = XrayManager.binary(this);
            if (!bin.exists()) {
                postUi(() -> toast(getString(R.string.toast_core_missing)));
                return;
            }
            String v = XrayManager.version(bin);
            postUi(() -> {
                coreStatus.setText("✓ " + v);
                coreVersionLabel.setText(getString(R.string.core_version, v));
                toast(getString(R.string.toast_core_ok, v));
            });
        }).start();
    }

    /** One button: beta tick on -> newest release incl. pre-release, off -> newest stable.
     *  The downloaded zip is kept in filesDir/core_updates, so a failed install
     *  is retried from the cache (no re-download). On success the zip is deleted. */
    private void updateFromGithub() {
        final boolean pre = coreBetaCheck.isChecked();
        coreProgressBar.setVisibility(View.VISIBLE);
        coreProgressBar.setIndeterminate(false);
        coreProgressBar.setProgress(0);
        toast(getString(R.string.toast_core_searching));
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.MINUTES)
                        .writeTimeout(10, TimeUnit.MINUTES)
                        .build();
                String tag = null;
                String zipUrl = null;
                if (pre) {
                    Request req = new Request.Builder()
                            .url("https://api.github.com/repos/XTLS/Xray-core/releases")
                            .get().build();
                    try (Response resp = client.newCall(req).execute()) {
                        if (!resp.isSuccessful()) throw new Exception("GitHub error " + resp.code());
                        JSONArray arr = new JSONArray(resp.body().string());
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject r = arr.getJSONObject(i);
                            boolean prerelease = r.optBoolean("prerelease", false);
                            if (prerelease || !r.optBoolean("draft", true)) {
                                tag = r.getString("tag_name");
                                zipUrl = "https://github.com/XTLS/Xray-core/releases/download/"
                                        + tag + "/Xray-android-arm64-v8a.zip";
                                break;
                            }
                        }
                    }
                    if (tag == null) throw new Exception("pre-release not found");
                } else {
                    Request req = new Request.Builder()
                            .url("https://api.github.com/repos/XTLS/Xray-core/releases/latest")
                            .get().build();
                    try (Response resp = client.newCall(req).execute()) {
                        if (!resp.isSuccessful()) throw new Exception("GitHub error " + resp.code());
                        JSONObject r = new JSONObject(resp.body().string());
                        tag = r.getString("tag_name");
                        zipUrl = "https://github.com/XTLS/Xray-core/releases/download/"
                                + tag + "/Xray-android-arm64-v8a.zip";
                    }
                }
                AppLog.i("update", "candidate " + tag + " from " + zipUrl);

                // never downgrade the running core
                String cand = tag.startsWith("v") ? tag.substring(1) : tag;
                String cur = currentCoreVersion();
                if (cur != null && !isNewer(cand, cur)) {
                    AppLog.i("update", "skip: candidate " + cand + " not newer than current " + cur);
                    postUi(() -> {
                        coreStatus.setText(getString(R.string.core_update_up_to_date, cur));
                        coreProgressBar.setVisibility(View.GONE);
                    });
                    return;
                }

                File updDir = new File(getFilesDir(), "core_updates");
                if (!updDir.exists()) updDir.mkdirs();
                File zipFile = new File(updDir, "xray-" + cand + ".zip");

                if (zipFile.exists() && zipFile.length() > 0 && zipHasXray(zipFile)) {
                    AppLog.i("update", "using cached zip " + zipFile.getName()
                            + " (" + zipFile.length() + " bytes) — no download");
                    postUi(() -> coreStatus.setText(R.string.core_update_cached));
                } else {
                    zipFile.delete();
                    for (int attempt = 1; attempt <= 2; attempt++) {
                        boolean ok = false;
                        try {
                            Request dz = new Request.Builder().url(zipUrl).get().build();
                            try (Response resp = client.newCall(dz).execute()) {
                                if (!resp.isSuccessful() || resp.body() == null)
                                    throw new Exception("download failed: HTTP " + resp.code());
                                long total = resp.body().contentLength();
                                AppLog.i("update", "zip download start, total=" + total
                                        + " bytes (attempt " + attempt + ")");
                                long got = 0;
                                int lastStep = -1;
                                try (InputStream zin = resp.body().byteStream();
                                     FileOutputStream out = new FileOutputStream(zipFile)) {
                                    byte[] buf = new byte[32768];
                                    int n;
                                    while ((n = zin.read(buf)) > 0) {
                                        out.write(buf, 0, n);
                                        got += n;
                                        int step = total > 0 ? (int) (got * 1000 / total) : 0;
                                        if (step > lastStep) {
                                            lastStep = step;
                                            final int p = step;
                                            final long fg = got;
                                            final long ft = total;
                                            postUi(() -> {
                                                coreProgressBar.setProgress(p);
                                                coreStatus.setText(getString(R.string.core_update_prog,
                                                        ft > 0 ? (int) (fg * 100 / ft) : 0, mb(fg)));
                                            });
                                        }
                                    }
                                }
                                AppLog.i("update", "zip download complete: " + zipFile.length() + " bytes");
                                ok = true;
                            }
                        } catch (IOException ioe) {
                            AppLog.e("update", "download attempt " + attempt + " failed: " + ioe.getMessage());
                            zipFile.delete();
                            if (attempt == 2) throw ioe;
                        }
                        if (ok) break;
                    }
                    if (!zipFile.exists() || zipFile.length() == 0)
                        throw new Exception("empty download");
                }

                postUi(() -> {
                    coreStatus.setText(R.string.core_update_installing);
                    coreProgressBar.setIndeterminate(true);
                });
                boolean installed = false;
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                    ZipEntry ze;
                    while ((ze = zis.getNextEntry()) != null) {
                        String name = ze.getName();
                        if (name.equalsIgnoreCase("xray") || name.endsWith("/xray")) {
                            AppLog.i("update", "extracting entry: " + name);
                            String vline = XrayManager.installNewBinary(this, zis);
                            final String vl = vline;
                            boolean deleted = zipFile.delete();
                            AppLog.i("update", "install OK — cached zip removed=" + deleted);
                            postUi(() -> {
                                toast(getString(R.string.toast_core_updated, vl));
                                coreProgressBar.setVisibility(View.GONE);
                                refreshCoreStatus(true);
                            });
                            installed = true;
                            break;
                        }
                    }
                }
                if (!installed) {
                    AppLog.e("update", "zip had no xray entry — keeping zip for manual check");
                    throw new Exception("zip had no xray entry");
                }
            } catch (XrayManager.CoreExecBlockedException e) {
                postUi(() -> {
                    coreProgressBar.setVisibility(View.GONE);
                    coreBlocked(e.getMessage());
                });
            } catch (Exception e) {
                final String m = e.getMessage();
                AppLog.e("update", "update failed (will keep cache, no re-download): " + m);
                postUi(() -> {
                    coreProgressBar.setVisibility(View.GONE);
                    coreStatus.setText("");
                    toast(getString(R.string.toast_update_failed, String.valueOf(m)));
                });
            }
        }).start();
    }

    /** Numeric version of the currently active core binary, e.g. "26.7.28". */
    private String currentCoreVersion() {
        try {
            File bin = XrayManager.binary(this);
            String v = XrayManager.version(bin); // e.g. "26.7.28"
            return (v != null && v.matches("\\d+(\\.\\d+)*")) ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Quick check that a cached zip really contains an xray entry. */
    private static boolean zipHasXray(File zip) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String n = ze.getName();
                if (n.equalsIgnoreCase("xray") || n.endsWith("/xray")) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ------------------------------------------------------------------- app update

    private static final String OUR_REPO = "baddarksss/ConfigScanner";

    /**
     * In-app self update: checks the GitHub releases for a newer version
     * and, if found, downloads the APK and hands it to the system installer
     * (the user grants "install unknown apps" once on first use).
     */
    private void checkForAppUpdate() {
        appUpdateStatus.setText(getString(R.string.app_update_checking));
        btnAppUpdate.setEnabled(false);
        new Thread(() -> {
            String fail = null;
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build();
                Request req = new Request.Builder()
                        .url("https://api.github.com/repos/" + OUR_REPO + "/releases/latest")
                        .get().build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null)
                        throw new Exception("GitHub HTTP " + resp.code());
                    JSONObject rel = new JSONObject(resp.body().string());
                    String tag = rel.optString("tag_name", ""); // e.g. v1.0.11
                    String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                    if (latest.isEmpty()) throw new Exception("no version");
                    if (!isNewer(latest, VERSION)) {
                        postUi(() -> {
                            appUpdateStatus.setText(getString(R.string.app_update_latest, VERSION));
                            btnAppUpdate.setEnabled(true);
                        });
                        return;
                    }
                    // find the apk asset
                    String apkUrl = null;
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a != null && a.optString("name", "").endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url");
                                break;
                            }
                        }
                    }
                    if (apkUrl == null) throw new Exception("no apk asset");
                    File ext = getExternalFilesDir(null);
                    File dir = new File(ext != null ? ext : getFilesDir(), "updates");
                    if (!dir.exists()) dir.mkdirs();
                    File apk = new File(dir, "ConfigScanner-v" + latest + ".apk");
                    if (apk.exists() && apk.length() > 0) {
                        AppLog.i("appupdate", "using cached apk " + apk.getName()
                                + " (" + apk.length() + " bytes) — no download");
                        final File f = apk;
                        postUi(() -> {
                            appUpdateStatus.setText(getString(R.string.app_update_cached, latest));
                            btnAppUpdate.setEnabled(true);
                            installApk(f);
                        });
                        return;
                    }
                    AppLog.i("appupdate", "downloading v" + latest + " from " + apkUrl);
                    postUi(() -> {
                        appUpdateStatus.setText(getString(R.string.app_update_download, latest));
                        appProgressBar.setVisibility(View.VISIBLE);
                        appProgressBar.setIndeterminate(false);
                        appProgressBar.setProgress(0);
                    });
                    Request dz = new Request.Builder().url(apkUrl).get().build();
                    try (Response dr = client.newCall(dz).execute()) {
                        if (!dr.isSuccessful() || dr.body() == null)
                            throw new Exception("download HTTP " + dr.code());
                        long total = dr.body().contentLength();
                        long got = 0;
                        int lastStep = -1;
                        try (FileOutputStream fos = new FileOutputStream(apk);
                             InputStream is = dr.body().byteStream()) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = is.read(buf)) > 0) {
                                fos.write(buf, 0, n);
                                got += n;
                                int step = total > 0 ? (int) (got * 1000 / total) : 0;
                                if (step > lastStep) {
                                    lastStep = step;
                                    final int p = step;
                                    final long fg = got;
                                    final long ft = total;
                                    postUi(() -> {
                                        appProgressBar.setProgress(p);
                                        appUpdateStatus.setText(getString(R.string.app_update_prog,
                                                ft > 0 ? (int) (fg * 100 / ft) : 0, mb(fg), mb(ft)));
                                    });
                                }
                            }
                        }
                        AppLog.i("appupdate", "apk download complete: " + apk.length() + " bytes");
                        final File f = apk;
                        postUi(() -> {
                            appProgressBar.setVisibility(View.GONE);
                            appUpdateStatus.setText(getString(R.string.app_update_ready, latest));
                            btnAppUpdate.setEnabled(true);
                            installApk(f);
                        });
                    }
                }
            } catch (Exception e) {
                fail = e.getClass().getSimpleName() + ": " + e.getMessage();
                AppLog.e("appupdate", fail);
            }
            final String ff = fail;
            postUi(() -> {
                appProgressBar.setVisibility(View.GONE);
                if (ff != null) {
                    appUpdateStatus.setText(getString(R.string.app_update_failed, ff));
                    btnAppUpdate.setEnabled(true);
                }
            });
        }).start();
    }

    private File pendingInstall;

    /**
     * canRequestPackageInstalls() throws SecurityException on Android 12+
     * when the permission is not declared (and on pre-26 it doesn't exist),
     * so the result must never take the app down with it.
     */
    private boolean canInstallPkgs() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return true;
            return getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            AppLog.e("appupdate", "canRequestPackageInstalls failed: " + e.getMessage());
            return false;
        }
    }

    private void installApk(File apk) {
        if (!canInstallPkgs()) {
            // Ask once, on demand (the app no longer declares this permission,
            // which is what made Play Protect nervous).
            pendingInstall = apk;
            toast(getString(R.string.install_grant_hint));
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.fromParts("package", getPackageName(), null)));
                postUi(() -> appUpdateStatus.setText(R.string.app_update_grant));
            } catch (Exception e) {
                pendingInstall = null;
                AppLog.e("appupdate", "permission page failed: " + e.getMessage());
            }
            return;
        }
        doInstall(apk);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstall != null && canInstallPkgs()) {
            File f = pendingInstall;
            pendingInstall = null;
            AppLog.i("appupdate", "permission granted — continuing install");
            postUi(() -> {
                appUpdateStatus.setText(getString(R.string.app_update_ready,
                        f.getName().replace("ConfigScanner-v", "").replace(".apk", "")));
            });
            doInstall(f);
        }
    }

    private void doInstall(File apk) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apk);
            if (isFinishing()) return;
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            AppLog.e("appupdate", "fileprovider install failed (" + e.getMessage()
                    + ") — falling back to public Downloads");
            tryFallbackInstall(apk, e.getMessage());
        }
    }

    /**
     * Fallback: copy the APK to the public Downloads folder and hand that URI
     * to the installer. Works even when the FileProvider route misbehaves.
     */
    private void tryFallbackInstall(File apk, String why) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, apk.getName());
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE,
                        "application/vnd.android.package-archive");
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download");
                android.net.Uri pub = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (pub == null) throw new Exception("no media uri");
                try (java.io.OutputStream os = getContentResolver().openOutputStream(pub);
                     java.io.InputStream is = new FileInputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
                if (isFinishing()) return;
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                i.setDataAndType(pub, "application/vnd.android.package-archive");
                startActivity(i);
                AppLog.i("appupdate", "fallback install via Downloads OK (why: " + why + ")");
            } else {
                throw new Exception("fallback needs Android 10+ (why: " + why + ")");
            }
        } catch (Exception e2) {
            AppLog.e("appupdate", "fallback install failed: " + e2.getMessage());
            appUpdateStatus.setText(getString(R.string.app_update_failed,
                    String.valueOf(e2.getMessage())));
        }
    }

    /** Megabytes with one decimal, e.g. 12.3 */
    private static String mb(long b) {
        return String.format(java.util.Locale.US, "%.1f", b / 1048576.0);
    }

    /** Simple dotted version comparison (works for x.y.z). */
    private static boolean isNewer(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /**
     * The device's SELinux refused to exec an updated core. Direct the user
     * to the releases page — installing a newer APK is the only way to get
     * a newer Xray on this device.
     */
    private void coreBlocked(String reason) {
        AppLog.w("update", "core update blocked: " + reason);
        coreStatus.setText(R.string.core_update_blocked_status);
        new AlertDialog.Builder(this)
                .setTitle(R.string.core_blocked_title)
                .setMessage(getString(R.string.core_blocked_msg, String.valueOf(reason)))
                .setPositiveButton(R.string.core_blocked_btn_app, (d, w) -> checkForAppUpdate())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------- dialogs

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.about_title, VERSION))
                .setMessage(getString(R.string.changelog))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showLog() {
        String raw = AppLog.dump();
        final String log = raw.isEmpty() ? getString(R.string.log_empty) : raw;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.log_title)
                .setNeutralButton(R.string.log_clear, (d, w) -> AppLog.clear())
                .setNegativeButton(R.string.log_save, (d, w) ->
                        logExportLauncher.launch("cfgscan_log_"
                                + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                                .format(new java.util.Date()) + ".txt"));
        EditText et = new EditText(this);
        et.setText(log);
        et.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        et.setTextIsSelectable(true);
        b.setView(et);
        b.setPositiveButton(R.string.log_copy, (d, w) -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", log));
            toast(getString(R.string.toast_copy_done));
        });
        b.show();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (running) stopRun();
        super.onDestroy();
        if (pool != null) pool.shutdownNow();
    }
}
