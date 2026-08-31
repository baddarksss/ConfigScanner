package com.wpnfa.configscan;

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
    private MaterialButton btnTheme;
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
    private MaterialButton btnLanguage;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private int themeMode = 0; // 0=system 1=dark 2=light

    private ExecutorService pool;
    private volatile boolean running = false;

    private final List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
    private int basePort = 21000;
    private final java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private int totalCount = 0;
    private final AtomicBoolean runFinished = new AtomicBoolean(false);

    private ActivityResultLauncher<String[]> fileImportLauncher;
    private ActivityResultLauncher<String> fileExportLauncher;
    private ActivityResultLauncher<String[]> coreFileLauncher;
    private ActivityResultLauncher<String> logExportLauncher;

    // ------------------------------------------------------------------- setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        themeMode = prefs.getInt("theme_mode", 0);
        applyTheme();
        setContentView(R.layout.activity_main);
        AppLog.init(this);

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
        btnTheme = findViewById(R.id.btnTheme);
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
        btnLanguage = findViewById(R.id.btnLanguage);
        headerChip.setText("App v" + VERSION);
        appVersionLabel.setText("v" + VERSION);
        btnTheme.setText(themeLabel());
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

    private void showThemeDialog() {
        String[] options = {
                getString(R.string.theme_system),
                getString(R.string.theme_dark),
                getString(R.string.theme_light)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_title)
                .setSingleChoiceItems(options, themeMode, (d, which) -> {
                    d.dismiss();
                    themeMode = which;
                    prefs.edit().putInt("theme_mode", themeMode).apply();
                    applyTheme(); // triggers activity recreate
                })
                .show();
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

    private void showLanguageDialog() {
        String[] options = {
                getString(R.string.lang_system),
                getString(R.string.lang_fa),
                getString(R.string.lang_en)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.lang_title)
                .setSingleChoiceItems(options, currentLangIndex(), (d, which) -> {
                    applyLanguage(which);
                    d.dismiss();
                })
                .show();
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
        btnLanguage.setOnClickListener(v -> showLanguageDialog());
        btnTheme.setOnClickListener(v -> showThemeDialog());

        bottomNav.setOnItemSelectedListener(item -> {
            boolean test = item.getItemId() == R.id.navTest;
            pageTest.setVisibility(test ? android.view.View.VISIBLE : android.view.View.GONE);
            pageSettings.setVisibility(test ? android.view.View.GONE : android.view.View.VISIBLE);
            return true;
        });

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
                main.post(() -> {
                    toast(getString(R.string.toast_core_updated, vl));
                    refreshCoreStatus(true);
                });
            } catch (XrayManager.CoreExecBlockedException ex) {
                main.post(() -> coreBlocked(ex.getMessage()));
            } catch (Exception ex) {
                final String m = ex.getMessage();
                main.post(() -> toast(getString(R.string.toast_update_file_failed, String.valueOf(m))));
            }
        }).start();
    }

    // ------------------------------------------------------------------- run

    private void updateStartState() {
        if (input != null) {
            btnStart.setEnabled(!input.getText().toString().trim().isEmpty() || running);
        }
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

        File dir = XrayManager.coreDir(this);
        if (!dir.exists()) dir.mkdirs();

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

    /** Returns the first TCP port not already listening, starting at `start`. */
    private int findFreePort(int start) {
        for (int p = start; p < start + 500; p++) {
            if (!XrayManager.portInUse(p)) return p;
        }
        AppLog.w("run", "no free port found in [" + start + "," + (start + 500) + ")");
        return start + 500;
    }

    private void stopRun() {
        if (pool != null) pool.shutdownNow();
        running = false;
        main.post(() -> {
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
        status(String.format("[%d/%d] %s %s", doneCount.get() + 1, totalCount, s.protocol, hostport));

        // obfs type without a password: cannot be tested at all
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
            updateProgress();
            return;
        }

        // Engine choice: Xray-core's salamander/gecko UDP obfs is broken
        // upstream (its finalmask wrapper never sends or reads packets —
        // verified by packet capture), so every hysteria2 link runs through
        // the native Hysteria client. All other protocols use Xray.
        Process engine = null;
        File engineLog;
        try {
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
                    + " ip=" + geo.ip + " ok=" + geo.ok + " took=" + took + "s");

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
                success(renamedRaw);
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
            main.post(() -> {
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

    private void success(String renamedLine) {
        outputLines.add(renamedLine);
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
        int i = raw.lastIndexOf('#');
        if (i < 0) return raw + "#" + newName;
        return raw.substring(0, i + 1) + newName;
    }

    // ------------------------------------------------------------------- ui

    private void updateProgress() {
        final int done = doneCount.get();
        final int total = totalCount;
        main.post(() -> {
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
        main.post(() -> progressStatus.setText(s));
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
        main.post(() -> {
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
            main.post(() -> {
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
                main.post(() -> toast(getString(R.string.toast_core_missing)));
                return;
            }
            String v = XrayManager.version(bin);
            main.post(() -> {
                coreStatus.setText("✓ " + v);
                coreVersionLabel.setText(getString(R.string.core_version, v));
                toast(getString(R.string.toast_core_ok, v));
            });
        }).start();
    }

    /** One button: beta tick on → latest release including pre-releases, off → latest stable. */
    private void updateFromGithub() {
        final boolean pre = coreBetaCheck.isChecked();
        toast(getString(R.string.toast_core_searching));
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build();
                // beta tick: first release in the list (pre-releases included)
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

                AppLog.i("update", "downloading " + tag + " from " + zipUrl);
                final String ftag = tag;
                main.post(() -> coreStatus.setText(getString(R.string.core_update_dl, ftag)));

                // 1) download the release zip (progress + one retry on network hiccup)
                File zipFile = new File(getCacheDir(), "xray_update.zip");
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
                                    int step = total > 0 ? (int) (got * 20 / total) : 0;
                                    if (step > lastStep) {
                                        lastStep = step;
                                        final long fg = got;
                                        final long ft = total;
                                        main.post(() -> coreStatus.setText(
                                                getString(R.string.core_update_prog,
                                                        ft > 0 ? (int) (fg * 100 / ft) : 0,
                                                        mb(fg))));
                                    }
                                }
                            }
                            AppLog.i("update", "zip download complete: " + zipFile.length() + " bytes");
                            ok = true;
                        }
                    } catch (IOException ioe) {
                        AppLog.e("update", "download attempt " + attempt + " failed: " + ioe.getMessage());
                        if (attempt == 2) throw ioe;
                    }
                    if (ok) break;
                }
                if (!zipFile.exists() || zipFile.length() == 0)
                    throw new Exception("empty download");

                // 2) extract the "xray" binary out of the zip and install it
                main.post(() -> coreStatus.setText(R.string.core_update_installing));
                boolean installed = false;
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                    ZipEntry ze;
                    while ((ze = zis.getNextEntry()) != null) {
                        String name = ze.getName();
                        if (name.equalsIgnoreCase("xray") || name.endsWith("/xray")) {
                            AppLog.i("update", "extracting entry: " + name);
                            String vline = XrayManager.installNewBinary(this, zis);
                            final String vl = vline;
                            main.post(() -> {
                                toast(getString(R.string.toast_core_updated, vl));
                                refreshCoreStatus(true);
                            });
                            installed = true;
                            break;
                        }
                    }
                }
                zipFile.delete();
                if (!installed) throw new Exception("zip had no xray entry");
            } catch (XrayManager.CoreExecBlockedException e) {
                main.post(() -> coreBlocked(e.getMessage()));
            } catch (Exception e) {
                final String m = e.getMessage();
                main.post(() -> toast(getString(R.string.toast_update_failed, String.valueOf(m))));
            }
        }).start();
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
                        main.post(() -> {
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
                    AppLog.i("appupdate", "downloading v" + latest + " from " + apkUrl);
                    main.post(() -> appUpdateStatus.setText(getString(R.string.app_update_download, latest)));
                    Request dz = new Request.Builder().url(apkUrl).get().build();
                    try (Response dr = client.newCall(dz).execute()) {
                        if (!dr.isSuccessful() || dr.body() == null)
                            throw new Exception("download HTTP " + dr.code());
                        File dir = new File(getExternalFilesDir(null), "updates");
                        if (!dir.exists()) dir.mkdirs();
                        File apk = new File(dir, "ConfigScanner-v" + latest + ".apk");
                        try (FileOutputStream fos = new FileOutputStream(apk);
                             InputStream is = dr.body().byteStream()) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                        }
                        final File f = apk;
                        main.post(() -> {
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
            main.post(() -> {
                if (ff != null) {
                    appUpdateStatus.setText(getString(R.string.app_update_failed, ff));
                    btnAppUpdate.setEnabled(true);
                }
            });
        }).start();
    }

    private File pendingInstall;

    private void installApk(File apk) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            // Ask once, on demand (the app no longer declares this permission,
            // which is what made Play Protect nervous).
            pendingInstall = apk;
            toast(getString(R.string.install_grant_hint));
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.fromParts("package", getPackageName(), null)));
                main.post(() -> appUpdateStatus.setText(R.string.app_update_grant));
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
        if (pendingInstall != null && getPackageManager().canRequestPackageInstalls()) {
            File f = pendingInstall;
            pendingInstall = null;
            AppLog.i("appupdate", "permission granted — continuing install");
            main.post(() -> {
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
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            AppLog.e("appupdate", "install intent failed: " + e.getMessage());
            appUpdateStatus.setText(getString(R.string.app_update_failed, String.valueOf(e.getMessage())));
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
        toast(getString(R.string.toast_core_blocked));
        AppLog.w("update", "core update blocked: " + reason);
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/baddarksss/ConfigScanner/releases")));
        } catch (Exception ignored) { }
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
        super.onDestroy();
        if (pool != null) pool.shutdownNow();
    }
}
