package com.configscanner;

import android.content.ClipData;
import android.text.Editable;
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
import android.widget.HorizontalScrollView;

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
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

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
    private MaterialSwitch dynColorsSwitch;
    private SeekBar parallelBar;
    private TextView parallelValue;
    private SeekBar timeoutBar;
    private TextView timeoutValue;
    private MaterialButton btnStart;
    private MaterialCardView progressCard;
    private ProgressHeroView waterCircle;
    private TextView progressLabel;
    private TextView progressCount;
    private TextView progressStatus;
    private TextView progressPercent;
    private android.widget.LinearLayout countryStatsBox;
    private android.widget.LinearLayout countryStatsList;
    private MaterialButton btnFilterCountries;
    private MaterialButton btnLimitCount;
    private HorizontalScrollView filterChipsScroll;
    private android.widget.LinearLayout filterChipsBox;
    private ScrollView pageTest;
    private ScrollView pageSettings;
    private BottomNavigationView bottomNav;
    private ScrollView outputScroll;
    private com.google.android.material.button.MaterialButton btnOutExpand;
    private boolean outExpanded;
    private TextView outputView;
    private TextView outCount;
    private TextView outCountrySummary;
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
    private ScrollView pageCaption;
    private android.view.View captionCountryHeader, captionCountryOptions;
    private android.widget.TextView captionCountryChevron;
    private MaterialButton btnOutLangFa, btnOutLangEn;
    private EditText captionSearch;
    private android.view.View msgUsersHeader, msgUsersOptions;
    private android.widget.TextView msgUsersChevron, msgUsersPreview;
    private android.widget.LinearLayout countryListContainer, captionMissingContainer;
    private android.widget.TextView countryListCount, captionFlagsBox, captionFullBox;
    private android.view.View tplDropHeader, tplDropOptions, flagsHeader, fullHeader;
    private android.view.View tplDropBody, flagsBody, fullBody;
    private android.widget.TextView tplActiveLabel, tplChevron, flagsChevron, fullChevron;
    private android.widget.LinearLayout tplListContainer;
    private com.google.android.material.checkbox.MaterialCheckBox chkCaptionCount;
    /** The last RAW link returned by bin.mudfish.net (persisted). */
    private String lastMudfishLink;
    /** ISO country code -> premium emoji code, persisted in prefs */
    private final java.util.Map<String, String> emojiCodes = new java.util.HashMap<>();
    /** ISO codes of countries seen in the current run, in order of first appearance */
    private final List<String> runCountryCodes = java.util.Collections.synchronizedList(new ArrayList<>());
    private TextView themeValue, themeChevron, langValue, langChevron;
    private MaterialButton btnThemeSystem, btnThemeDark, btnThemeLight;
    private MaterialButton btnLangSystem, btnLangFa, btnLangEn;

    /** Last selected bottom-nav tab; static so it survives activity recreation. */
    private static int sLastNav = 0; // 0=test 1=settings 2=caption

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private int themeMode = 0; // 0=system 1=dark 2=light

    private volatile ExecutorService pool;
    private volatile boolean running = false;
    private volatile boolean coreUpdating = false;
    private volatile boolean runStopped = false;
    /** Bumped on every startRun/stopRun — stale workers of an old run
     *  recognize the change and abandon their server without touching the
     *  counters/output of the new run. */
    private volatile int runGeneration = 0;


    private final List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
    private final List<String> flagList = new ArrayList<>();
    private int basePort = 21000;
    private final java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile int totalCount = 0;
    private final AtomicBoolean runFinished = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger okCount = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger noCountryCount = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger unreachableCount = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger skipCount = new java.util.concurrent.atomic.AtomicInteger();
    /** lines that ServerSpec.parse could not parse (reported, never silent) */
    private final java.util.concurrent.atomic.AtomicInteger parseFailCount = new java.util.concurrent.atomic.AtomicInteger();
    /** Raw URIs of servers that connected but whose country could not be detected. */
    /** v1.0.73: true when the last run's input was entirely JSON configs —
     *  copy-links then exports a JSON array of full client configs. */
    private boolean runAllJsonInput = false;
    private final List<String> unknownLinks =
            java.util.Collections.synchronizedList(new ArrayList<String>());

    // ------------------------- output model / filtering -------------------------
    /** One successful output entry: the renamed link + its ISO country ("" = unknown). */
    static final class OutEntry {
        final String line;
        final String iso;
        OutEntry(String line, String iso) { this.line = line; this.iso = iso; }
    }
    /** Full run output in order — the source of truth the box is rendered from. */
    private final List<OutEntry> outEntries = new ArrayList<>();
    /** ISO countries the user selected to keep (null = no filter active). */
    private java.util.Set<String> selectedCountries;
    /** Max number of entries to keep (0 = all). Applied randomly when set. */
    private int outLimit;
    private volatile boolean destroyed = false;
    private final java.util.Set<Process> activeEngines = java.util.Collections
            .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private ActivityResultLauncher<String[]> fileImportLauncher;
    private ActivityResultLauncher<String> fileExportLauncher;
    private ActivityResultLauncher<String[]> coreFileLauncher;
    private ActivityResultLauncher<String> logExportLauncher;
    private ActivityResultLauncher<String> codesBackupLauncher;
    private ActivityResultLauncher<String[]> codesImportLauncher;

    // ------------------------------------------------------------------- setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        installCrashLogger();
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        themeMode = prefs.getInt("theme_mode", 0);
        applyTheme();
        // Material You: on Android 12+ follow the wallpaper color palette
        // (toggleable in Settings). Must run before the views inflate.
        if (prefs.getBoolean("dynamic_colors", true)) {
            DynamicColors.applyToActivityIfAvailable(this);
        }
        setContentView(R.layout.activity_main);

        fileImportLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onImportFile);
        fileExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), this::onExportFile);
        coreFileLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onCoreFilePicked);
        logExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), this::onLogExport);
        codesBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), this::onCodesBackup);
        codesImportLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onCodesImport);

        bindViews();
        restorePrefs();
        wireEvents();
        updateStartState();
    }

    private void bindViews() {
        input = findViewById(R.id.input);
        channelEdit = findViewById(R.id.channelEdit);
        channelSwitch = findViewById(R.id.channelSwitch);
        dynColorsSwitch = findViewById(R.id.dynColorsSwitch);
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
        progressPercent = findViewById(R.id.progressPercent);
        countryStatsBox = findViewById(R.id.countryStatsBox);
        countryStatsList = findViewById(R.id.countryStatsList);
        btnFilterCountries = findViewById(R.id.btnFilterCountries);
        btnLimitCount = findViewById(R.id.btnLimitCount);
        filterChipsScroll = findViewById(R.id.filterChipsScroll);
        filterChipsBox = findViewById(R.id.filterChipsBox);
        pageTest = findViewById(R.id.pageTest);
        pageSettings = findViewById(R.id.pageSettings);
        pageCaption = findViewById(R.id.pageCaption);
        bottomNav = findViewById(R.id.bottomNav);
        outputScroll = findViewById(R.id.outputScroll);
        btnOutExpand = findViewById(R.id.btnOutExpand);
        outputView = findViewById(R.id.outputView);
        outCount = findViewById(R.id.outCount);
        outCountrySummary = findViewById(R.id.outCountrySummary);
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
        captionCountryHeader = findViewById(R.id.captionCountryHeader);
        captionCountryOptions = findViewById(R.id.captionCountryOptions);
        captionCountryChevron = findViewById(R.id.captionCountryChevron);
        btnOutLangFa = findViewById(R.id.btnOutLangFa);
        btnOutLangEn = findViewById(R.id.btnOutLangEn);
        captionSearch = findViewById(R.id.captionSearch);
        msgUsersHeader = findViewById(R.id.msgUsersHeader);
        msgUsersOptions = findViewById(R.id.msgUsersOptions);
        msgUsersChevron = findViewById(R.id.msgUsersChevron);
        msgUsersPreview = findViewById(R.id.msgUsersPreview);
        countryListContainer = findViewById(R.id.countryListContainer);
        captionMissingContainer = findViewById(R.id.captionMissingContainer);
        countryListCount = findViewById(R.id.countryListCount);
        tplDropHeader = findViewById(R.id.tplDropHeader);
        tplDropOptions = findViewById(R.id.tplDropOptions);
        tplDropBody = tplDropOptions;
        tplActiveLabel = findViewById(R.id.tplActiveLabel);
        tplChevron = findViewById(R.id.tplChevron);
        tplListContainer = findViewById(R.id.tplListContainer);
        flagsHeader = findViewById(R.id.flagsHeader);
        flagsChevron = findViewById(R.id.flagsChevron);
        flagsBody = findViewById(R.id.captionFlagsBox);
        fullHeader = findViewById(R.id.fullHeader);
        fullChevron = findViewById(R.id.fullChevron);
        fullBody = findViewById(R.id.fullOptions);
        captionFlagsBox = findViewById(R.id.captionFlagsBox);
        captionFullBox = findViewById(R.id.captionFullBox);
        chkCaptionCount = findViewById(R.id.chkCaptionCount);
        lastMudfishLink = prefs.getString("mudfish_link", "");
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
        // always persist — including empty. The old "only when non-empty"
        // check made a cleared channel name stick forever (the suffix could
        // never actually be removed).
        prefs.edit().putString("channel", channelEdit.getText().toString().trim())
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
        ((MaterialButton) findViewById(R.id.btnCopyLinks)).setOnClickListener(v -> copyLinksOnly());
        btnFilterCountries.setOnClickListener(v -> showFilterDialog());
        btnLimitCount.setOnClickListener(v -> showLimitDialog());
        ((android.widget.CheckBox) findViewById(R.id.chkIncludeUnknown))
                .setChecked(prefs.getBoolean("include_unknown_in_links", false));
        ((android.widget.CheckBox) findViewById(R.id.chkIncludeUnknown))
                .setOnCheckedChangeListener((b, checked) -> {
                    prefs.edit().putBoolean("include_unknown_in_links", checked).apply();
                    // v1.0.70: the toggle now governs EVERY surface unknowns
                    // can reach — output box, flag strip, country summary,
                    // copied links, filter dialog
                    refreshOutput();
                    updateFlagStrip();
                    refreshCaptionTab();
                });

        // caption count toggle (v1.0.52): compact «📦 Configs: N» line,
        // controlled by the checkbox or a {{COUNT}} placeholder
        chkCaptionCount.setChecked(prefs.getBoolean("caption_show_count", true));
        chkCaptionCount.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("caption_show_count", checked).apply();
            refreshCaptionTab();
        });
        btnOutExpand.setOnClickListener(v -> {
            outExpanded = !outExpanded;
            outputScroll.getLayoutParams().height = outExpanded
                    ? android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    : dpToPx(260);
            outputScroll.setLayoutParams(outputScroll.getLayoutParams());
            btnOutExpand.setText(outExpanded ? R.string.out_collapse
                                             : R.string.out_expand);
        });
        ((MaterialButton) findViewById(R.id.btnSave)).setOnClickListener(v -> {
            boolean empty;
            synchronized (outputLines) { empty = outputLines.isEmpty(); }
            if (empty) {
                toast(getString(R.string.toast_output_empty));
                return;
            }
            String fn = "configs_" + new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date()) + ".txt";
            fileExportLauncher.launch(fn);
        });
        ((MaterialButton) findViewById(R.id.btnClearOut)).setOnClickListener(v -> {
            synchronized (outputLines) {
                outputLines.clear();
            }
            synchronized (unknownLinks) {
                unknownLinks.clear();
            }
            synchronized (outEntries) {
                outEntries.clear();
            }
            selectedCountries = null;
            outLimit = 0;
            lastMudfishLink = "";
            prefs.edit().remove("mudfish_link").apply();
            countryStatsBox.setVisibility(View.GONE);
            renderFilterChips();
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
                String all = String.join(" ", flagList);
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("flags", all));
                toast(getString(R.string.toast_flags_copied));
            }
        });
        captionFlagsBox.setOnClickListener(v -> {
            String t = buildFlagsLine();
            if (t.isEmpty()) { toast(getString(R.string.caption_flags_empty)); return; }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("flag-codes", t));
            toast(getString(R.string.toast_copied_codes));
        });
        findViewById(R.id.btnCodesBackup).setOnClickListener(v -> {
            if (emojiCodes.isEmpty()) { toast(getString(R.string.caption_codes_empty)); return; }
            codesBackupLauncher.launch("cfgscan_country_codes_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                    .format(new java.util.Date()) + ".txt");
        });
        findViewById(R.id.btnCodesRestore).setOnClickListener(v ->
                codesImportLauncher.launch(new String[]{"text/plain", "*/*"}));
        findViewById(R.id.btnCodesCopy).setOnClickListener(v -> {
            String t = exportCodesText();
            if (t.isEmpty()) { toast(getString(R.string.caption_codes_empty)); return; }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("country-codes", t));
            toast(getString(R.string.caption_copied_clip));
        });
        findViewById(R.id.btnTemplateAdd).setOnClickListener(v -> showTplEditor(null));
        tplDropHeader.setOnClickListener(v -> {
            boolean open = tplDropOptions.getVisibility() != View.VISIBLE;
            tplDropOptions.setVisibility(open ? View.VISIBLE : View.GONE);
            tplChevron.setText(open ? "\u2303" : "\u2304");
        });
        // text sections are collapsible; the copy buttons stay visible
        flagsHeader.setOnClickListener(v -> {
            boolean open = flagsBody.getVisibility() != View.VISIBLE;
            flagsBody.setVisibility(open ? View.VISIBLE : View.GONE);
            flagsChevron.setText(open ? "\u2303" : "\u2304");
        });
        fullHeader.setOnClickListener(v -> {
            boolean open = fullBody.getVisibility() != View.VISIBLE;
            fullBody.setVisibility(open ? View.VISIBLE : View.GONE);
            fullChevron.setText(open ? "\u2303" : "\u2304");
        });
        // 🌍 Mudfish: upload the (filtered) output, get the RAW link back
        findViewById(R.id.btnMudfish).setOnClickListener(v -> uploadToMudfish());

        findViewById(R.id.btnCopyCaptionCompact).setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("caption", buildFullCaption()));
            toast(getString(R.string.caption_copied_post));
        });
        captionSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int cc) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int cc) {
                buildCountryList(c.toString());
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        captionCountryHeader.setOnClickListener(v -> {
            boolean open = captionCountryOptions.getVisibility() != View.VISIBLE;
            setCountryListOpen(open);
        });
        // message-for-users section (caption tab)
        msgUsersHeader.setOnClickListener(v -> {
            boolean open = msgUsersOptions.getVisibility() != View.VISIBLE;
            setMsgUsersOpen(open);
        });
        findViewById(R.id.btnMsgUsersEdit).setOnClickListener(v -> showMsgUsersEditor());
        findViewById(R.id.btnMsgUsersRemove).setOnClickListener(v -> confirmMsgUsersRemove());
        // big always-visible copy button on the message card (v1.0.50) —
        // no need to expand the section first
        findViewById(R.id.btnMsgUsersCopyMain).setOnClickListener(v -> copyUsersMessage());
        btnOutLangFa.setOnClickListener(v -> applyOutLang("fa"));
        btnOutLangEn.setOnClickListener(v -> applyOutLang("en"));
        updateOutLangStyle();

        loadEmojiCodes();
        loadLastRunCountries();
        buildCountryList("");
        refreshCaptionTab();

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
        dynColorsSwitch.setChecked(prefs.getBoolean("dynamic_colors", true));
        dynColorsSwitch.setOnCheckedChangeListener((b, checked) -> {
            boolean oldVal = prefs.getBoolean("dynamic_colors", true);
            if (checked == oldVal) return;
            prefs.edit().putBoolean("dynamic_colors", checked).apply();
            recreate(); // theme overlay must be applied before views inflate
        });
        btnLangSystem.setOnClickListener(v -> { applyLanguage(0); setLangOpen(false); });
        btnLangFa.setOnClickListener(v -> { applyLanguage(1); setLangOpen(false); });
        btnLangEn.setOnClickListener(v -> { applyLanguage(2); setLangOpen(false); });
        cleanupStaleApks();

        bottomNav.setOnItemSelectedListener(item -> {
            int idx = item.getItemId() == R.id.navTest ? 0
                    : item.getItemId() == R.id.navSettings ? 1 : 2;
            sLastNav = idx;
            applyNavPage(idx);
            return true;
        });
        // restore the tab the user was on (language/theme switches recreate the
        // activity and would otherwise drop back to the test page)
        if (sLastNav == 1) {
            pageTest.setVisibility(View.GONE);
            pageSettings.setVisibility(View.VISIBLE);
            bottomNav.getMenu().findItem(R.id.navSettings).setChecked(true);
        } else if (sLastNav == 2) {
            pageTest.setVisibility(View.GONE);
            pageCaption.setVisibility(View.VISIBLE);
            bottomNav.getMenu().findItem(R.id.navCaption).setChecked(true);
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

    /** Uploads the current (filtered) output to bin.mudfish.net and hands
     *  back the RAW link: saved to the clipboard, shown as a toast, and the
     *  full link is also printed to the run log. */
    private void uploadToMudfish() {
        java.util.LinkedHashSet<String> links = new java.util.LinkedHashSet<>();
        List<OutEntry> vis = visibleEntries();
        if (vis.isEmpty()) {
            synchronized (outputLines) {
                for (String line : outputLines) {
                    String t = line.trim();
                    if (isProxyLink(t)) links.add(t);
                }
            }
        } else {
            for (OutEntry e : vis) links.add(e.line);
        }
        if (links.isEmpty()) {
            toast(getString(R.string.mudfish_empty));
            return;
        }
        status(getString(R.string.mudfish_preparing));
        final String payload = String.join("\n", links) + "\n";
        MudfishUploader.upload(payload, (rawUrl, error) -> postUi(() -> {
            if (error != null) {
                status("");
                toast(getString(R.string.mudfish_failed, error));
                return;
            }
            lastMudfishLink = rawUrl;
            prefs.edit().putString("mudfish_link", rawUrl).apply();
            refreshCaptionTab();
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("mudfish-raw", rawUrl));
            toast(getString(R.string.mudfish_done));
            status(rawUrl);
            AppLog.i("mudfish", "raw link: " + rawUrl + " (" + links.size() + " lines)");
        }));
    }

    private void onExportFile(Uri uri) {
        if (uri == null) return;
        // export the FILTERED view — what the box shows is what gets saved
        List<OutEntry> vis = visibleEntries();
        final String all;
        if (vis.isEmpty()) {
            synchronized (outputLines) {
                all = String.join("\n", outputLines) + "\n";
            }
        } else {
            StringBuilder sb = new StringBuilder();
            for (OutEntry e : vis) sb.append(e.line).append("\n");
            all = sb.toString();
        }
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
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
            File tmp = new File(getCacheDir(), "core_tmp.zip");
            java.util.zip.ZipFile zf2 = null;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                // The zip contains a single binary named "xray" (flat layout)
                // write stream to temp file first
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                zf2 = new java.util.zip.ZipFile(tmp);
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
            } finally {
                if (zf2 != null) {
                    try { zf2.close(); } catch (Exception ignored) { }
                }
                tmp.delete(); // never leave the staged zip behind
            }
        }).start();
    }

    // ------------------------------------------------------------------- run

    private void updateStartState() {
        if (input != null) {
            btnStart.setEnabled(!input.getText().toString().trim().isEmpty() || running);
        }
    }

    private void applyNavPage(int which) {
        pageTest.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        pageCaption.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
        if (which == 2) refreshCaptionTab();
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
                android.util.Log.e("ConfigScanner", "uncaught", e);
            } catch (Exception ignored) { }
            if (defaultH != null) defaultH.uncaughtException(t, e);
        });
    }

    private void startRun() {
        if (coreUpdating) {
            toast(getString(R.string.core_update_busy));
            return;
        }
        ensureScanNotification();
        runStopped = false;
        runGeneration++; // invalidate workers of any previous run
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            toast(getString(R.string.toast_paste_first));
            return;
        }
        savePrefs();

        // parse all lines — unparsable lines are counted and listed in the
        // Failed block instead of silently disappearing (review fix v1.0.63)
        parseFailCount.set(0);
        failedReasons.clear();
        // v1.0.71/73: JSON config dumps (Xray / sing-box / v2rayN / Clash)
        // in the paste are pulled apart into the proxy URIs they contain.
        // Plain links are scanned FIRST and win dedup — the raw link is the
        // richest form of a server the user pasted; extracted JSON servers
        // join after, so a server pasted as BOTH a link and JSON is
        // scanned once, from its original link.
        List<String> fromJson = new ArrayList<>();
        boolean jsonFound = false;
        try {
            fromJson = JsonConfigs.extract(text);
            if (!fromJson.isEmpty()) {
                jsonFound = true;
                AppLog.i("run", "json configs: extracted " + fromJson.size()
                        + " server(s) from JSON in the input");
            }
        } catch (Exception je) {
            AppLog.w("run", "json config extraction failed: " + je.getMessage());
        }
        List<String> scanLines = new ArrayList<>();
        int plainLinks = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            // with JSON configs present, bare non-link lines are residue of
            // the pretty-printed JSON documents — not user errors
            if (jsonFound && !isProxyLink(t)) continue;
            if (isProxyLink(t)) plainLinks++;
            scanLines.add(t);
        }
        if (jsonFound) scanLines.addAll(fromJson);

        // v1.0.73: dedup by protocol+host+port+credential+transport —
        // identical endpoints from a pasted link AND a pasted JSON config
        // must not be tested (and exported) twice
        java.util.LinkedHashMap<String, ServerSpec> uniq =
                new java.util.LinkedHashMap<>();
        int parseFail = 0;
        for (String t : scanLines) {
            ServerSpec s;
            try {
                s = ServerSpec.parse(t);
            } catch (Exception pe) {
                // recognizable but invalid/unsupported — report the reason
                parseFail++;
                if (parseFail <= 50) {
                    String shortLink = t.length() > 90 ? t.substring(0, 90) + "…" : t;
                    failedReasons.put(t, "unsupported/invalid: " + pe.getMessage()
                            + " — " + shortLink);
                }
                continue;
            }
            if (s != null) {
                String key = s.protocol + "|" + s.host.toLowerCase()
                        + "|" + s.port
                        + "|" + ServerSpec.firstNonEmpty(s.uuid, s.password)
                        + "|" + s.network + "|" + s.security
                        + "|" + ServerSpec.firstNonEmpty(s.path, s.serviceName);
                ServerSpec prev = uniq.get(key);
                if (prev == null) {
                    uniq.put(key, s);
                } else {
                    AppLog.d("run", "duplicate skipped: " + s.protocol
                            + " " + s.host + ":" + s.port);
                }
            } else {
                parseFail++;
                if (parseFail <= 50) {
                    String shortLink = t.length() > 90 ? t.substring(0, 90) + "…" : t;
                    failedReasons.put(t, "parse failed — unsupported/invalid format: " + shortLink);
                }
            }
        }
        List<ServerSpec> servers = new ArrayList<>(uniq.values());
        // v1.0.73: remember that this run came purely from JSON configs —
        // "Copy links only" then exports a JSON array of full client
        // configs instead of plain links
        runAllJsonInput = jsonFound && plainLinks == 0 && !servers.isEmpty();
        parseFailCount.set(parseFail);
        if (servers.isEmpty()) {
            toast(getString(R.string.toast_no_config));
            AppLog.w("run", "no parsable config — " + parseFail
                    + " line(s) invalid, see Log \u2192 Failed block");
            return;
        }

        synchronized (flagList) { flagList.clear(); }
        flagStrip.setText("");
        runCountryCodes.clear();
        saveLastRunCountries();
        refreshCaptionTab();

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
        synchronized (assignedPorts) { assignedPorts.clear(); }
        okCount.set(0);
        noCountryCount.set(0);
        unreachableCount.set(0);
        skipCount.set(0);
        failedCount.set(0);
        procLogs.clear();
        // NOTE: parseFailCount and failedReasons are cleared BEFORE the parse
        // loop, not here — parse failures are registered during parsing and
        // were previously wiped by this reset right after (review fix).
        synchronized (unknownLinks) { unknownLinks.clear(); }
        syncCoreButtons();
        doneCount.set(0);
        totalCount = servers.size();
        basePort = 21000 + (int) (Math.random() * 500);
        synchronized (outputLines) {
            outputLines.clear();
        }
        synchronized (outEntries) {
            outEntries.clear();
        }
        selectedCountries = null; // a new run resets the country filter
        refreshOutput();
        btnStart.setText(R.string.btn_stop);

        // The progress card (big water circle) is only visible while a run
        // is active — hidden again is handled when the run finishes/stops.
        progressCard.setVisibility(View.VISIBLE);
        waterCircle.setProgress(0f);
        waterCircle.setRunning(true);
        updateProgress();

        // Read UI controls on the UI thread; everything heavy below
        // (engine version exec, per-server port probing) runs on a worker
        // thread so a big list can never ANR the click.
        final int parallelN = Math.max(1, parallelBar.getProgress() + 1);
        final int timeoutSec = timeoutBar.getProgress() + 5;
        final boolean hasHy2F = hasHy2;
        final File binF = bin;
        final int gen = runGeneration; // captured BEFORE the version checks
        new Thread(() -> {
            AppLog.d("run", "basePort=" + basePort + " servers=" + servers.size()
                    + " xray=" + XrayManager.version(binF)
                    + (hasHy2F ? " hy2=" + HysteriaManager.version(HysteriaManager.binary(this)) : ""));
            // v1.0.61: hold a foreground service for the whole run — a plain
            // notification doesn't stop Android from killing a long scan
            // when the screen is off
            ScanService.begin(this, servers.size());
            // the user may hit Stop while the version check above was running
            // — a stopped run must not start testing anything
            if (runStopped || !running || gen != runGeneration) {
                ScanService.end(this);
                return;
            }
            synchronized (runStartLock) {
                if (runStopped || !running || gen != runGeneration) {
                    ScanService.end(this); // begun above — don't leak the service
                    return;
                }
                ExecutorService newPool = Executors.newFixedThreadPool(parallelN);
                pool = newPool;
                // pool.submit only enqueues — port is allocated dynamically when the worker runs
                for (int i = 0; i < servers.size(); i++) {
                    final ServerSpec s = servers.get(i);
                    newPool.submit(() -> {
                        // a stale worker (its run was stopped/superseded) must
                        // not touch the counters/output of the current run
                        if (gen != runGeneration) return;
                        final int port = findFreePort();
                        testOne(s, port, timeoutSec, gen);
                    });
                }
            }
        }, "run-prep").start();
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

    /** Ports currently in use by active test workers in the current run */
    private final java.util.Set<Integer> assignedPorts = new java.util.HashSet<>();
    /** Serializes pool creation between startRun's prep thread and stopRun */
    private final Object runStartLock = new Object();

    /** Round-robin cursor for port allocation — deterministic, so parallel
     *  workers never race for the same candidate (each call advances the
     *  cursor under the assignedPorts lock). */
    private int portCursor = 21000;

    private int findFreePort() {
        synchronized (assignedPorts) {
            for (int i = 0; i < 30000; i++) {
                int p = portCursor;
                portCursor = (portCursor >= 50999) ? 21000 : portCursor + 1;
                if (!assignedPorts.contains(p) && !XrayManager.portInUse(p)) {
                    assignedPorts.add(p);
                    return p;
                }
            }
            // whole 21000..50999 range busy — extremely rare; let the OS pick
            try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
                int p = ss.getLocalPort();
                assignedPorts.add(p);
                return p;
            } catch (Exception e) {
                int fallback = 21000 + (portCursor++) % 30000;
                assignedPorts.add(fallback);
                return fallback;
            }
        }
    }

    /** Post a UI update, skipping it if the activity is gone (rotations/
     *  background kills would otherwise touch stale views). */
    private void postUi(Runnable r) {
        if (!destroyed) main.post(r);
    }

    private void stopRun() {
        // set the flags first so the run-prep thread sees the stop even
        // before the pool exists; bumping the generation makes every in-flight
        // worker abandon its server without touching run state
        running = false;
        runStopped = true;
        runGeneration++;
        if (pool != null) pool.shutdownNow();
        for (Process p : activeEngines) {
            try { p.destroyForcibly(); } catch (Exception ignored) { }
        }
        synchronized (assignedPorts) { assignedPorts.clear(); }
        ScanService.end(this);
        cancelScanNotification();
        postUi(() -> {
            syncCoreButtons();
            btnStart.setText(R.string.btn_start);
            waterCircle.setRunning(false);
            progressStatus.setText(R.string.progress_stopped);
            toast(getString(R.string.toast_stopped));
        });
    }

    private void testOne(ServerSpec s, int port, int timeoutSec, int gen) {
        String hostport = s.host + ":" + s.port;
        if (gen != runGeneration) return; // run was stopped/superseded
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
            if ("hysteria1".equals(s.protocol)) {
                if (gen != runGeneration) return;
                doneCount.incrementAndGet();
                skipCount.incrementAndGet();
                String base = s.name.isEmpty() ? "hysteria v1" : s.name;
                synchronized (outputLines) {
                    outputLines.add("⚠️ " + base + " — " + getString(R.string.res_hysteria1_unsupported));
                }
                AppLog.w("test", "SKIP " + base + " (hysteria v1 not supported by the core)");
                refreshOutput();
                autoScroll();
                return;
            }
            if ("hysteria2".equals(s.protocol)
                    && s.obfs != null && !s.obfs.isEmpty()
                    && !"plain".equalsIgnoreCase(s.obfs)
                    && (s.obfsParam == null || s.obfsParam.isEmpty())) {
                if (gen != runGeneration) return;
                doneCount.incrementAndGet();
                String base = s.name.isEmpty() ? hostport : s.name;
                synchronized (outputLines) {
                    outputLines.add("⚠️ " + base + " — " + getString(R.string.res_obfs_nopass));
                }
                AppLog.w("test", "SKIP " + hostport + " (hysteria2 obfs without password)");
                skipCount.incrementAndGet();
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
                    // pin THROUGH the local socks port — the cert must be the
                    // one the actual tunnel route sees, not the direct route
                    s.pinnedCertHash = CertPinner.pinViaSocks(port, s.host, s.port, sni, 8000);
                    AppLog.d("test", "certpin " + s.host + ":" + s.port + " sni=" + sni
                            + " hash=" + (s.pinnedCertHash.isEmpty() ? "FAILED" : s.pinnedCertHash));
                }
                File xrayOwnLog = new File(XrayManager.coreDir(this), "xrayw_" + port + ".log");
                String cfg = XrayConfig.buildFull(s, port, xrayOwnLog.getAbsolutePath());
                File cfgFile = new File(XrayManager.coreDir(this), "cfg_" + port + ".json");
                try (FileOutputStream fos = new FileOutputStream(cfgFile)) {
                    fos.write(cfg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                // engineLog tails xray's own error log (the "error" file from
                // the config). The process stdout/err goes to procLog — that
                // is where CONFIG-LOAD errors appear, and it is the file we
                // must show on an early exit (v1.0.57: before, the early-exit
                // path tailed the error log, which is still empty because
                // xray died before initializing logging — so failures showed
                // a blind "Engine error" with log=[]).
                engineLog = xrayOwnLog;
                File procLog = new File(XrayManager.coreDir(this), "xray_" + port + ".log");
                engine = XrayManager.start(XrayManager.binary(this), cfgFile, procLog);
                procLogs.put(port, procLog);
            }

            activeEngines.add(engine);
            Thread.sleep(300);
            if (gen != runGeneration) return; // run stopped while sleeping
            if (!engine.isAlive()) {
                File procLog = procLogs.get(port);
                String earlyTail = procLog != null
                        ? AppLog.fileTail(procLog, 12) : "";
                if (earlyTail.isEmpty()) {
                    earlyTail = AppLog.fileTail(engineLog, 8);
                }
                AppLog.w("test", "engine exited early rc=" + engine.exitValue()
                        + " log=[" + earlyTail + "]");
                failedReasons.put(s.raw, earlyTail.isEmpty()
                        ? "xray exited rc=" + engine.exitValue() + " (no output)"
                        : earlyTail);
                doneCount.incrementAndGet();
                unreachableCount.incrementAndGet();
                if (earlyTail.contains("unknown config id")) {
                    fail(s, getString(R.string.res_core_unsupported, s.protocol));
                } else {
                    fail(s, getString(R.string.res_engine_error));
                }
                return;
            }

            // wait for SOCKS port
            long waitMs = Math.min(8000, 3000 + 100L * timeoutSec);
            boolean up = XrayManager.waitForPort(port, waitMs);
            if (gen != runGeneration) return; // run stopped while waiting
            if (!up) {
                AppLog.w("test", "port " + port + " not up after " + waitMs + "ms"
                        + " engine alive=" + engine.isAlive()
                        + " log=[" + AppLog.fileTail(engineLog, 8) + "]");
                doneCount.incrementAndGet();
                unreachableCount.incrementAndGet();
                fail(s, getString(R.string.res_connect_failed));
                return;
            }
            AppLog.d("test", "port " + port + " up; engine alive=" + engine.isAlive()
                    + " logSize=" + engineLog.length());

            // geo check via SOCKS
            long t0 = System.currentTimeMillis();
            GeoChecker.Result geo = GeoChecker.check(port, timeoutSec);
            long took = (System.currentTimeMillis() - t0) / 1000;
            if (gen != runGeneration) return; // run stopped during geo check
            AppLog.d("test", "geo code=" + geo.code + " country=" + geo.country
                    + " ip=" + geo.ip + " ok=" + geo.ok
                    + " votes=" + geo.votes + "/" + geo.answered
                    + (geo.singleVote ? " (single-vote, low confidence)" : "")
                    + (geo.ipConflict ? " (providers used different exit IPs)" : "")
                    + " took=" + took + "s");

            if (geo.ok && !geo.code.isEmpty()) {
                String countryName = geo.country.isEmpty()
                        ? geo.code : geo.country;
                if ("fa".equals(prefs.getString("out_lang", "en"))) {
                    CountryData.C cc = CountryData.byCode(geo.code);
                    if (cc != null) countryName = cc.fa;
                }
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
                okCount.incrementAndGet();
                success(renamedRaw, geo.code, flag);
                noteCountry(geo.code);
            } else {
                doneCount.incrementAndGet();
                String tail = AppLog.fileTail(engineLog, 8);
                AppLog.w("test", "connected but country unknown — engine log tail: ["
                        + tail + "]");
                // v1.0.59: the geo providers' verdict is the diagnostic that
                // matters here (tunnel is up — requests just don't pass)
                String geoDiag = geo.failed > 0
                        ? "tunnel up; country unknown — geo providers failed "
                                + geo.failed + "/" + geo.total
                                + ", first: " + geo.firstError
                        : "tunnel up; country unknown — no geo provider answered in time";
                // v1.0.62: any CDN-fronted target is its own category
                // (Cloudflare, Fastly, CloudFront, Akamai, Google, …).
                // These tunnels handshake fine but their network path often
                // blocks the geo probes — that does NOT mean the config is
                // dead. Label them ☁️ CDN instead of dropping them as
                // "no country".
                if (!looksLikeEngineError(tail)
                        && ServerSpec.isCdnTarget(s.host, s.sni, s.hostHeader)) {
                    String channel = prefs.getString("channel", "");
                    boolean incCh = prefs.getBoolean("include_channel", true);
                    String suffix = (incCh && !channel.isEmpty()) ? " | " + channel : "";
                    String label = "CDN";
                    String renamed = "\u2601\uFE0F " + label + suffix;
                    String renamedRaw = renameUri(s.raw, renamed);
                    AppLog.d("test", "OK CDN (cloudflare, geo blocked) -> " + renamed);
                    status(String.format("\u2601 [%d/%d] %s = CDN",
                            doneCount.get(), totalCount, hostport));
                    okCount.incrementAndGet();
                    success(renamedRaw, "CDN", GeoChecker.flag("CDN"));
                    noteCountry("CDN");
                    return;
                }
                failedReasons.put(s.raw, geoDiag);
                if (looksLikeEngineError(tail)) {
                    // the tunnel itself is broken — report it as unreachable
                    unreachableCount.incrementAndGet();
                    fail(s, getString(R.string.res_engine_error));
                } else {
                    // v1.0.63 (review fix): a config that is UP but whose
                    // country could not be detected is a WORKING config — it
                    // must not silently disappear (v1.0.50 hid these, which
                    // read as "not processed"). Keep it in the output with a
                    // ❔ Unknown marker; the reason stays in the Failed block.
                    noCountryCount.incrementAndGet();
                    boolean fa = "fa".equals(prefs.getString("out_lang", "en"));
                    String channel = prefs.getString("channel", "");
                    boolean incCh = prefs.getBoolean("include_channel", true);
                    String suffix = (incCh && !channel.isEmpty()) ? " | " + channel : "";
                    String base = s.name.isEmpty() ? hostport : s.name;
                    String renamed = "❔ " + (fa ? "ناشناس" : "Unknown")
                            + suffix + " | " + base;
                    String renamedRaw = renameUri(s.raw, renamed);
                    AppLog.d("test", "UNKNOWN (tunnel up, country undetected) -> " + renamed);
                    status(String.format("❔ [%d/%d] %s = ? country",
                            doneCount.get(), totalCount, hostport));
                    success(renamedRaw, "", "❔");
                }
            }
        } catch (Exception e) {
            AppLog.e("test", "error " + hostport + " " + e.getMessage());
            if (gen == runGeneration) {
                doneCount.incrementAndGet();
                unreachableCount.incrementAndGet();
                failedReasons.put(s.raw, String.valueOf(e.getMessage()));
                fail(s, String.valueOf(e.getMessage()));
            }
        } finally {
            activeEngines.remove(engine);
            synchronized (assignedPorts) { assignedPorts.remove(port); }
            if (engine != null) {
                try {
                    engine.destroyForcibly();
                } catch (Exception ignored) {
                }
            }

            // Keep per-test logs/configs until the run is over so a failed
            // connection remains diagnosable. cleanupEngineLogs() trims the
            // directory after the run or when Stop is pressed.
            // only the workers of the CURRENT run drive progress/completion —
            // a stale worker of a stopped/superseded run must stay silent
            if (gen == runGeneration) {
                updateProgress();
                if (doneCount.get() >= totalCount) {
                    finishRun();
                }
            }
        }
    }

    /** Called once when every server of the current run has finished. */
    private void finishRun() {
        if (runStopped) return; // a stopped run never "finishes"
        if (runFinished.compareAndSet(false, true)) {
            if (pool != null) pool.shutdownNow(); // no leaked idle threads per run
            // Retain recent engine logs for post-run diagnostics and trim older ones.
            cleanupEngineLogs(XrayManager.coreDir(this));
            ScanService.end(this);
            cancelScanNotification();
            final String summary = buildRunSummary();
            AppLog.i("run", "finished: " + summary);
            synchronized (outputLines) {
                outputLines.add("\n—— " + summary + " ——");
            }
            refreshOutput();
            postUi(() -> {
                running = false;
                syncCoreButtons();
                btnStart.setText(R.string.btn_start);
                btnStart.setEnabled(true);
                waterCircle.setRunning(false);
                waterCircle.setProgress(totalCount == 0 ? 0 : 100f);
                progressPercent.setText("100%");
                progressLabel.setText(R.string.progress_done);
                progressStatus.setText(summary);
                buildCountryStats();
                renderFilterChips();
                vibrateOnce();
            });
        }
    }

    /** Add a successful config to the output. iso is passed in explicitly —
     *  guessing it back from the flag emoji broke the FIRST entry of every
     *  country (runCountryCodes wasn't populated yet -> iso="" -> wrong
     *  stats/filter). Review fix v1.0.64. */
    private void success(String renamedLine, String iso, String flag) {
        synchronized (outputLines) {
            outputLines.add(renamedLine);
        }
        synchronized (outEntries) {
            outEntries.add(new OutEntry(renamedLine, iso == null ? "" : iso));
        }
        // v1.0.70 fix: actually track unknown links — this list existed but
        // was never filled, so the hide-unknowns filter could never work
        if (iso == null || iso.isEmpty()) {
            synchronized (unknownLinks) {
                if (!unknownLinks.contains(renamedLine)) unknownLinks.add(renamedLine);
            }
        }
        if (flag != null && !flag.isEmpty()) {
            // keep the question mark off the flag strip while unknowns hide
            if (showUnknowns() || !UNKNOWN_FLAG.equals(flag)) {
                synchronized (flagList) {
                    if (!flagList.contains(flag)) flagList.add(flag); // one per country
                }
                updateFlagStrip();
            }
        }
        refreshOutput();
        autoScroll();
    }

    /** v1.0.70: the marker used for country-unknown entries. */
    private static final String UNKNOWN_FLAG = "\u2754"; // white question mark

    /** Toggle "show servers whose country was not detected" — v1.0.70 it
     *  governs the output box, flag strip, country summary, copied links
     *  and the filter dialog, not just the copy-links fallback. */
    private boolean showUnknowns() {
        return prefs.getBoolean("include_unknown_in_links", false);
    }

    /** Rebuild the flag strip above the output box; the question mark is
     *  filtered out while unknowns are hidden. */
    private void updateFlagStrip() {
        final List<String> copy;
        synchronized (flagList) {
            copy = new ArrayList<>();
            for (String fl : flagList) {
                if (!showUnknowns() && UNKNOWN_FLAG.equals(fl)) continue;
                copy.add(fl);
            }
        }
        final String txt = String.join(" ", copy);
        postUi(() -> flagStrip.setText(txt));
    }

    private void fail(ServerSpec s, String reason) {
        failedCount.incrementAndGet();
        String base = s.name.isEmpty() ? (s.host + ":" + s.port) : s.name;
        synchronized (outputLines) {
            outputLines.add("❌ " + base + " — " + reason);
        }
        AppLog.e("test", "FAIL " + base + ": " + reason);
        refreshOutput();
        autoScroll();
    }

    /** Replace the name in URI (including VMess JSON "ps" field and #fragment) */
    private String renameUri(String raw, String newName) {
        if (raw == null || raw.isEmpty()) return raw;
        if (raw.startsWith("vmess://")) {
            try {
                String body = raw.substring(8);
                int fi = body.lastIndexOf('#');
                if (fi >= 0) body = body.substring(0, fi);
                String json = ServerSpec.b64decode(body.trim());
                if (json.isEmpty()) json = ServerSpec.b64decode(ServerSpec.urlDecode(body.trim()));
                if (!json.isEmpty()) {
                    JSONObject o = new JSONObject(json);
                    o.put("ps", newName);
                    String encJson = java.util.Base64.getEncoder().encodeToString(
                            o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return "vmess://" + encJson;
                }
            } catch (Exception ignored) { }
        }
        String enc = encodeFragment(newName);
        int i = raw.lastIndexOf('#');
        // v1.0.72: the exported query must be client-safe — raw ["h2"]-style
        // values made strict clients (v2rayN, ...) silently drop the server
        String head = i < 0 ? raw : raw.substring(0, i);
        return ServerSpec.sanitizeForClients(head) + "#" + enc;
    }

    // ---------------------------------------------------- caption / country codes

    private static final String DEFAULT_CAPTION_TEMPLATE =
            "NpvTunnel [6050626661043411760]  \n"
            + "[5395616385734833119] لوکیشن | Location {{FLAGS}}\n"
            + "\n"
            + "[6172601958428313804] @Wpnfa  \n"
            + "\n"
            + "[5206607081334906820]  \n"
            + "#npvtunnel #vpn #v2ray\n"
            + "#فیلترشکن #کانفیگ #پروکسی";

    private void loadEmojiCodes() {
        emojiCodes.clear();
        try {
            org.json.JSONObject o = new org.json.JSONObject(prefs.getString("country_emoji_codes", "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                emojiCodes.put(k.toUpperCase(), o.getString(k));
            }
        } catch (Exception ignored) { }
    }

    private void saveEmojiCodes() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (java.util.Map.Entry<String, String> e : emojiCodes.entrySet()) o.put(e.getKey(), e.getValue());
            prefs.edit().putString("country_emoji_codes", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** The last run's countries are persisted so the caption can be rebuilt
     *  (and codes added) after a restart, without re-testing. */
    private void saveLastRunCountries() {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            synchronized (runCountryCodes) { for (String c : runCountryCodes) a.put(c); }
            prefs.edit().putString("last_run_countries", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void loadLastRunCountries() {
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs.getString("last_run_countries", "[]"));
            synchronized (runCountryCodes) {
                runCountryCodes.clear();
                for (int i = 0; i < a.length(); i++) runCountryCodes.add(a.getString(i));
            }
        } catch (Exception ignored) { }
    }

    /** Backup/restore file format: one "XX=code" per line. */
    private String exportCodesText() {
        if (emojiCodes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("# ConfigScanner country emoji codes\n");
        for (CountryData.C c : CountryData.all()) {
            String v = emojiCodes.get(c.code);
            if (v != null && !v.isEmpty()) sb.append(c.code).append('=').append(v).append('\n');
        }
        return sb.toString();
    }

    private void onCodesBackup(Uri uri) {
        if (uri == null) return;
        try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
            os.write(exportCodesText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            toast(getString(R.string.caption_backed_up));
        } catch (Exception e) {
            toast(getString(R.string.toast_save_error, String.valueOf(e.getMessage())));
        }
    }

    private void onCodesImport(Uri uri) {
        if (uri == null) return;
        try (InputStream is = getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            // decode the WHOLE stream first — decoding chunk-by-chunk with
            // new String(byte[], off, len) can split a multi-byte UTF-8
            // sequence at a buffer boundary and corrupt Persian text
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String content = new String(bos.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int count = 0;
            for (String line : content.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 2) continue;
                String iso = line.substring(0, eq).trim().toUpperCase();
                String code = line.substring(eq + 1).trim();
                if (iso.length() != 2 || code.isEmpty()) continue;
                emojiCodes.put(iso, code);
                count++;
            }
            saveEmojiCodes();
            buildCountryList(captionSearch.getText().toString());
            refreshCaptionTab();
            toast(getString(R.string.caption_restored, count));
        } catch (Exception e) {
            toast(getString(R.string.toast_save_error, String.valueOf(e.getMessage())));
        }
    }

    private void setCountryListOpen(boolean open) {
        captionCountryOptions.setVisibility(open ? View.VISIBLE : View.GONE);
        captionCountryChevron.setText(open ? "\u2303" : "\u2304");
    }

    private void applyOutLang(String tag) {
        prefs.edit().putString("out_lang", tag).apply();
        updateOutLangStyle();
    }

    private void updateOutLangStyle() {
        boolean fa = "fa".equals(prefs.getString("out_lang", "en"));
        // resolve through the theme: follows the Material You palette when
        // dynamic colors are active, the classic accent otherwise
        int accent = MaterialColors.getColor(btnOutLangFa, com.google.android.material.R.attr.colorPrimary);
        int normal = getResources().getColor(R.color.btn_text, getTheme());
        btnOutLangFa.setTextColor(fa ? accent : normal);
        btnOutLangEn.setTextColor(fa ? normal : accent);
        btnOutLangFa.setTypeface(null, fa ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        btnOutLangEn.setTypeface(null, fa ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
    }

    private void buildCountryList(String filter) {
        countryListContainer.removeAllViews();
        int accentColor = MaterialColors.getColor(countryListContainer,
                com.google.android.material.R.attr.colorPrimary);
        String f = filter == null ? "" : filter.toLowerCase();
        for (CountryData.C c : CountryData.all()) {
            if (!f.isEmpty() && !(c.en.toLowerCase().contains(f) || c.fa.contains(f)
                    || c.code.equalsIgnoreCase(f))) continue;
            final String iso = c.code;
            boolean has = emojiCodes.containsKey(iso);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            TextView flag = new TextView(this);
            flag.setText(c.flag);
            flag.setTextSize(16f);
            flag.setPadding(dpToPx(10), dpToPx(9), dpToPx(8), dpToPx(9));
            TextView name = new TextView(this);
            name.setText(c.en + "  •  " + c.fa);
            name.setTextSize(12.5f);
            name.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
            name.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView codeTv = new TextView(this);
            codeTv.setText(has ? emojiCodes.get(iso) : "\u2014");
            codeTv.setTextSize(10f);
            codeTv.setTypeface(android.graphics.Typeface.MONOSPACE);
            codeTv.setTextColor(has ? accentColor
                    : getResources().getColor(R.color.text_secondary, getTheme()));
            codeTv.setPadding(dpToPx(8), dpToPx(9), dpToPx(10), dpToPx(9));
            row.addView(flag);
            row.addView(name);
            row.addView(codeTv);
            row.setOnClickListener(v -> showCountryEmojiDialog(iso));
            countryListContainer.addView(row);
        }
        countryListCount.setText(getString(R.string.caption_list_count,
                CountryData.all().size(), emojiCodes.size()));
    }

    /** "[code1][code2]…" for the countries of the last run (in order of first hit). */
    private String buildFlagsLine() {
        StringBuilder sb = new StringBuilder();
        synchronized (runCountryCodes) {
            for (String iso : runCountryCodes) {
                String code = emojiCodes.get(iso);
                if (code != null && !code.isEmpty()) sb.append('[').append(code).append(']');
            }
        }
        return sb.toString();
    }

// ------------------------- saved caption templates -------------------------
    /** One saved caption template (v1.0.52: multiple named captions). */
    static final class CaptionTpl {
        final String id, name, tpl;
        CaptionTpl(String id, String name, String tpl) {
            this.id = id; this.name = name; this.tpl = tpl;
        }
    }

    private static final String PREF_TPLS = "caption_templates";
    private static final String PREF_TPL_ACTIVE = "caption_active_tpl";

    /** Loads the saved templates; seeds the list from the legacy single
     *  template on first run so nothing the user had is lost. */
    private java.util.List<CaptionTpl> tplLoad() {
        java.util.List<CaptionTpl> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString(PREF_TPLS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                out.add(new CaptionTpl(o.getString("id"), o.getString("name"), o.getString("tpl")));
            }
        } catch (Exception e) {
            AppLog.w("caption", "tpl parse failed, reseeding: " + e.getMessage());
        }
        if (out.isEmpty()) {
            String legacy = prefs.getString("caption_template", DEFAULT_CAPTION_TEMPLATE);
            if (legacy == null || legacy.trim().isEmpty()) legacy = DEFAULT_CAPTION_TEMPLATE;
            out.add(new CaptionTpl("def", getString(R.string.tpl_default_name), legacy));
            tplPersist(out);
        }
        return out;
    }

    private void tplPersist(java.util.List<CaptionTpl> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (CaptionTpl t : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", t.id); o.put("name", t.name); o.put("tpl", t.tpl);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString(PREF_TPLS, arr.toString()).apply();
    }

    private CaptionTpl tplActive() {
        java.util.List<CaptionTpl> list = tplLoad();
        String act = prefs.getString(PREF_TPL_ACTIVE, "");
        for (CaptionTpl t : list) if (t.id.equals(act)) return t;
        return list.get(0);
    }

    /** The ACTIVE template's raw text — the single source buildFullCaption uses. */
    private String activeTemplate() {
        return tplActive().tpl;
    }

    private void setActiveTemplate(String id) {
        prefs.edit().putString(PREF_TPL_ACTIVE, id).apply();
        refreshCaptionTab();
    }

    private void rebuildTplList() {
        tplListContainer.removeAllViews();
        CaptionTpl act = tplActive();
        for (final CaptionTpl t : tplLoad()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dpToPx(6), 0, dpToPx(6));

            TextView name = new TextView(this);
            name.setText((t.id.equals(act.id) ? "\u2705 " : "\u25CB ") + t.name);
            name.setTextSize(12.5f);
            name.setTextColor(getColor(R.color.text_primary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, lp);
            name.setOnClickListener(v -> setActiveTemplate(t.id));
            row.setOnClickListener(v -> setActiveTemplate(t.id));

            TextView edit = new TextView(this);
            edit.setText("\u270F\uFE0F");
            edit.setPadding(dpToPx(10), 0, dpToPx(10), 0);
            edit.setTextSize(14f);
            edit.setOnClickListener(v -> showTplEditor(t));
            row.addView(edit);

            TextView del = new TextView(this);
            del.setText("\uD83D\uDDD1\uFE0F");
            del.setPadding(dpToPx(10), 0, 0, 0);
            del.setTextSize(14f);
            del.setOnClickListener(v -> confirmTplDelete(t));
            row.addView(del);

            tplListContainer.addView(row);
        }
    }

    /** Add (t == null) / edit (t != null) dialog: name + text. */
    private void showTplEditor(final CaptionTpl t) {
        final EditText nameEdit = new EditText(this);
        nameEdit.setHint(R.string.tpl_name_hint);
        nameEdit.setText(t == null ? "" : t.name);
        nameEditSingleLine(nameEdit);
        final EditText textEdit = new EditText(this);
        textEdit.setHint(R.string.tpl_text_hint);
        textEdit.setText(t == null ? DEFAULT_CAPTION_TEMPLATE : t.tpl);
        textEdit.setMinLines(8);
        textEdit.setGravity(android.view.Gravity.START);
        textEdit.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), 0);
        box.addView(nameEdit);
        box.addView(textEdit);
        new AlertDialog.Builder(this)
                .setTitle(t == null ? R.string.tpl_new_title : R.string.tpl_edit_title)
                .setView(box)
                .setPositiveButton(R.string.tpl_save, (d, w) -> {
                    String name = nameEdit.getText().toString().trim();
                    if (name.isEmpty()) name = getString(R.string.tpl_default_name);
                    String text = textEdit.getText().toString();
                    if (text.trim().isEmpty()) text = DEFAULT_CAPTION_TEMPLATE;
                    java.util.List<CaptionTpl> list = tplLoad();
                    if (t == null) {
                        list.add(new CaptionTpl(String.valueOf(System.currentTimeMillis()),
                                name, text));
                        prefs.edit().putString(PREF_TPL_ACTIVE,
                                list.get(list.size() - 1).id).apply();
                    } else {
                        for (int i = 0; i < list.size(); i++) {
                            if (list.get(i).id.equals(t.id)) {
                                list.set(i, new CaptionTpl(t.id, name, text));
                                break;
                            }
                        }
                    }
                    tplPersist(list);
                    refreshCaptionTab();
                    toast(getString(R.string.tpl_saved));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void nameEditSingleLine(EditText e) {
        e.setSingleLine(false);
        e.setMaxLines(1);
    }

    private void confirmTplDelete(final CaptionTpl t) {
        java.util.List<CaptionTpl> list = tplLoad();
        if (list.size() <= 1) {
            toast(getString(R.string.tpl_last_one));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.tpl_delete_confirm, t.name))
                .setPositiveButton(R.string.tpl_delete, (d, w) -> {
                    java.util.List<CaptionTpl> rest = new ArrayList<>();
                    for (CaptionTpl x : list) if (!x.id.equals(t.id)) rest.add(x);
                    if (t.id.equals(prefs.getString(PREF_TPL_ACTIVE, ""))) {
                        prefs.edit().putString(PREF_TPL_ACTIVE, rest.get(0).id).apply();
                    }
                    tplPersist(rest);
                    refreshCaptionTab();
                    toast(getString(R.string.tpl_deleted));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String buildFullCaption() {
        String tpl = activeTemplate();
        String flags = buildFlagsLine();
        // caption ONLY — the users message is a separate block with its own
        // copy button (mixing the two made the bot-caption dirty)
        String out = tpl.contains("{{FLAGS}}")
                ? tpl.replace("{{FLAGS}}", flags)
                : tpl + "\n" + flags;
        // config count (v1.0.52): a {{COUNT}} placeholder wins for manual
        // placement; otherwise the toggle adds a compact line right after
        // the flags row. An empty count leaves no empty line behind.
        String count = captionCountLine();
        if (out.contains("{{COUNT}}")) {
            if (count.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String ln : out.split("\n", -1)) {
                    if (ln.trim().equals("{{COUNT}}")) continue;
                    sb.append(ln).append('\n');
                }
                out = sb.substring(0, sb.length() - 1).replace("{{COUNT}}", "");
            } else {
                out = out.replace("{{COUNT}}", count);
            }
        } else if (!count.isEmpty()) {
            out = insertAfterLine(out, flags, count);
        }
        // Mudfish RAW link (v1.0.52): {{LINK}} marks the exact spot; a line
        // holding ONLY the placeholder disappears cleanly while no link or
        // no output exists yet.
        if (out.contains("{{LINK}}")) {
            if (lastMudfishLink == null || lastMudfishLink.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String ln : out.split("\n", -1)) {
                    if (ln.trim().equals("{{LINK}}")) continue;
                    sb.append(ln).append('\n');
                }
                out = sb.substring(0, sb.length() - 1).replace("{{LINK}}", "");
            } else {
                out = out.replace("{{LINK}}", lastMudfishLink);
            }
        }
        return out;
    }

    /** The compact count line ("📦 Configs: 30" / "📦 تعداد کانفیگ: 30"),
     *  or "" when the toggle is off or nothing usable has been tested. */
    private String captionCountLine() {
        if (!prefs.getBoolean("caption_show_count", true)) return "";
        int n = visibleEntries().size();
        if (n <= 0) return "";
        boolean fa = "fa".equals(prefs.getString("out_lang", "en"));
        return getString(fa ? R.string.caption_count_fmt_fa
                            : R.string.caption_count_fmt, n);
    }

    /** Inserts {@code add} right after the first line that contains
     *  {@code anchor} (falls back to appending at the end). */
    private static String insertAfterLine(String text, String anchor, String add) {
        String[] ls = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean done = anchor == null || anchor.isEmpty();
        for (int i = 0; i < ls.length; i++) {
            sb.append(ls[i]);
            if (!done && ls[i].contains(anchor)) {
                sb.append('\n').append(add);
                done = true;
            }
            if (i < ls.length - 1) sb.append('\n');
        }
        if (!done) sb.append('\n').append(add);
        return sb.toString();
    }

    /** Shows a hint instead of an empty box when no users message is set. */
    private String msgUsersDisplayText() {
        String mu = prefs.getString("message_for_users", "");
        return mu.trim().isEmpty() ? getString(R.string.msg_users_empty) : mu;
    }

    /** Copies the users message (shared by both copy buttons). */
    private void copyUsersMessage() {
        String mu = prefs.getString("message_for_users", "").trim();
        if (mu.isEmpty()) {
            toast(getString(R.string.msg_users_empty));
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("message-for-users", mu));
        toast(getString(R.string.msg_users_copied));
    }

    private void setMsgUsersOpen(boolean open) {
        msgUsersOptions.setVisibility(open ? View.VISIBLE : View.GONE);
        msgUsersChevron.setText(open ? "\u2303" : "\u2304");
    }

    private void showMsgUsersEditor() {
        EditText edit = new EditText(this);
        edit.setHint(R.string.msg_users_hint);
        edit.setText(prefs.getString("message_for_users", ""));
        edit.setMinLines(4);
        edit.setGravity(android.view.Gravity.START);
        edit.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), 0);
        box.addView(edit);
        new AlertDialog.Builder(this)
                .setTitle(R.string.msg_users_title)
                .setMessage(R.string.msg_users_editor_msg)
                .setView(box)
                .setPositiveButton(R.string.msg_users_save, (d, w) -> {
                    prefs.edit().putString("message_for_users",
                            edit.getText().toString().trim()).apply();
                    refreshCaptionTab();
                    toast(getString(R.string.msg_users_saved));
                })
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmMsgUsersRemove() {
        if (prefs.getString("message_for_users", "").trim().isEmpty()) {
            toast(getString(R.string.msg_users_empty));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.msg_users_remove_title)
                .setMessage(R.string.msg_users_remove_msg)
                .setPositiveButton(R.string.msg_users_remove, (d, w) -> {
                    prefs.edit().remove("message_for_users").apply();
                    refreshCaptionTab();
                    toast(getString(R.string.msg_users_removed));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Rebuild every caption-tab output from the saved state (thread-safe). */
    private void refreshCaptionTab() {
        final String tpl = activeTemplate();
        final String flags = buildFlagsLine();
        final String full = buildFullCaption();
        final java.util.List<String> missing;
        synchronized (runCountryCodes) {
            missing = new ArrayList<>(runCountryCodes);
        }
        final String msgUsers = msgUsersDisplayText();
        final CaptionTpl actTpl = tplActive();
        postUi(() -> {
            tplActiveLabel.setText(getString(R.string.tpl_active_fmt, actTpl.name));
            rebuildTplList();
            msgUsersPreview.setText(msgUsers);
            captionFlagsBox.setText(flags.isEmpty() ? getString(R.string.caption_flags_empty) : flags);
            captionMissingContainer.removeAllViews();
            for (String iso : missing) {
                if (emojiCodes.containsKey(iso)) continue;
                CountryData.C c = CountryData.byCode(iso);
                if (c == null) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView name = new TextView(this);
                name.setText(c.flag + "  " + c.en + " \u2022 " + c.fa);
                name.setTextSize(12f);
                name.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
                name.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                MaterialButton btn = new MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
                btn.setText(R.string.caption_set_code);
                btn.setTextSize(11f);
                btn.setOnClickListener(v -> showCountryEmojiDialog(iso));
                row.addView(name);
                row.addView(btn);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dpToPx(5);
                row.setLayoutParams(lp);
                captionMissingContainer.addView(row);
            }
            captionFullBox.setText(full);
        });
    }

    private int dpToPx(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showCountryEmojiDialog(String iso) {
        CountryData.C c = CountryData.byCode(iso);
        if (c == null) return;
        EditText edit = new EditText(this);
        edit.setHint("5390843037349679256");
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        edit.setGravity(android.view.Gravity.START);
        edit.setText(emojiCodes.getOrDefault(iso, ""));
        edit.setTransformationMethod(null);
        edit.setTextDirection(View.TEXT_DIRECTION_LTR);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(dpToPx(4), dpToPx(2), dpToPx(4), 0);
        box.addView(edit);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(c.flag + "  " + c.en + " \u2022 " + c.fa)
                .setMessage(getString(R.string.country_emoji_code_label))
                .setView(box)
                .setPositiveButton(R.string.country_emoji_save, (d, w) -> {
                    String val = edit.getText().toString().trim();
                    if (val.isEmpty()) emojiCodes.remove(iso);
                    else emojiCodes.put(iso, val);
                    saveEmojiCodes();
                    buildCountryList(captionSearch.getText().toString());
                    refreshCaptionTab();
                    toast(getString(R.string.country_emoji_saved));
                });
        if (emojiCodes.containsKey(iso)) {
            b.setNegativeButton(R.string.country_emoji_remove, (d, w) -> {
                emojiCodes.remove(iso);
                saveEmojiCodes();
                buildCountryList(captionSearch.getText().toString());
                refreshCaptionTab();
            });
        }
        b.show();
    }

    /** Remember a country found in this run; the caption outputs refresh live. */
    private void noteCountry(String iso) {
        if (iso == null || (iso.length() != 2 && !"CDN".equals(iso))) return;
        boolean added;
        synchronized (runCountryCodes) {
            added = !runCountryCodes.contains(iso);
            if (added) runCountryCodes.add(iso);
        }
        if (added) {
            saveLastRunCountries();
            refreshCaptionTab();
        }
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

    private String buildRunSummary() {
        return getString(R.string.run_summary, okCount.get(), noCountryCount.get(),
                unreachableCount.get(), skipCount.get(), parseFailCount.get());
    }

    // ------------------------------------------------------- run stats & output tools

    /** Build the per-country stats box: flag + name + count of live servers. */
    private void buildCountryStats() {
        final java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        synchronized (outEntries) {
            for (OutEntry e : outEntries) {
                if (e.iso == null || e.iso.isEmpty()) continue;
                counts.merge(e.iso, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            countryStatsBox.setVisibility(View.GONE);
            return;
        }
        countryStatsList.removeAllViews();
        boolean fa = "fa".equals(prefs.getString("out_lang", "en"));
        for (java.util.Map.Entry<String, Integer> en : counts.entrySet()) {
            String iso = en.getKey();
            CountryData.C c = CountryData.byCode(iso);
            String name = c != null ? (fa ? c.fa : c.en) : iso;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView tv = new TextView(this);
            tv.setText(GeoChecker.flag(iso) + "  " + name + "  ×  " + en.getValue());
            tv.setTextSize(13f);
            tv.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            android.util.TypedValue tv2 = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv2, true);
            row.setBackgroundResource(tv2.resourceId);
            row.setPadding(0, dpToPx(6), 0, dpToPx(6));
            row.setOnClickListener(v -> showFilterDialog());
            row.addView(tv);
            countryStatsList.addView(row);
        }
        countryStatsBox.setVisibility(View.VISIBLE);
    }

    /** Entries currently visible in the box: country filter + count limit applied. */
    private List<OutEntry> visibleEntries() {
        List<OutEntry> src;
        synchronized (outEntries) {
            src = new ArrayList<>(outEntries);
        }
        // v1.0.70: unknown-country entries are hidden entirely while the
        // toggle is off — box, summary, copies and caption all read this
        if (!showUnknowns()) {
            List<OutEntry> f = new ArrayList<>();
            for (OutEntry e : src) {
                if (e.iso != null && !e.iso.isEmpty()) f.add(e);
            }
            src = f;
        }
        if (selectedCountries != null) {
            List<OutEntry> f = new ArrayList<>();
            for (OutEntry e : src) {
                if (selectedCountries.contains(e.iso)) f.add(e);
            }
            src = f; // an explicitly empty selection hides everything (Clear all)
        }
        if (outLimit > 0 && src.size() > outLimit) {
            // pick the keepers randomly, then restore the original run order
            final java.util.Map<OutEntry, Integer> idx = new java.util.IdentityHashMap<>();
            int i = 0;
            for (OutEntry e : src) idx.put(e, i++);
            java.util.Collections.shuffle(src);
            src = new ArrayList<>(src.subList(0, outLimit));
            java.util.Collections.sort(src, (a, b) -> Integer.compare(idx.get(a), idx.get(b)));
        }
        return src;
    }

    /** Re-render the output box from the entry model with filters applied. */
    private void applyOutputFilters() {
        renderFilterChips();
        refreshOutput();
    }

    /** Chip row under the buttons showing the active filters (tap a chip to clear). */
    private void renderFilterChips() {
        filterChipsBox.removeAllViews();
        boolean any = false;
        if (selectedCountries != null && !selectedCountries.isEmpty()) {
            any = true;
            String joined = new ArrayList<>(selectedCountries).toString();
            String txt = getString(R.string.filter_chip_countries,
                    selectedCountries.size()) + " " + joined;
            filterChipsBox.addView(makeFilterChip(txt, () -> {
                selectedCountries = null;
                applyOutputFilters();
                toast(getString(R.string.filter_cleared));
            }));
        }
        if (outLimit > 0) {
            any = true;
            filterChipsBox.addView(makeFilterChip(getString(R.string.filter_chip_limit, outLimit), () -> {
                outLimit = 0;
                applyOutputFilters();
                toast(getString(R.string.filter_cleared));
            }));
        }
        filterChipsScroll.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private android.view.View makeFilterChip(String text, Runnable onClear) {
        TextView chip = new TextView(this);
        chip.setText(text + "  ✕");
        chip.setTextSize(11.5f);
        chip.setTextColor(getResources().getColor(R.color.chip_text, getTheme()));
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dpToPx(8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> onClear.run());
        return chip;
    }

    /** Dialog with a checkbox per country found in the run + Apply. */
    private void showFilterDialog() {
        List<OutEntry> snapshot;
        synchronized (outEntries) {
            snapshot = new ArrayList<>(outEntries);
        }
        if (snapshot.isEmpty()) {
            toast(getString(R.string.toast_output_empty));
            return;
        }
        // count per country, preserving first-seen order
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (OutEntry e : snapshot) {
            // v1.0.70: no question-mark row while the toggle hides unknowns
            if (!showUnknowns() && (e.iso == null || e.iso.isEmpty())) continue;
            counts.merge(e.iso, 1, Integer::sum);
        }
        String unkLabel = getString(R.string.country_unknown_label);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(8);
        box.setPadding(pad, pad, pad, pad);
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.addView(sc, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        final java.util.List<String> order = new ArrayList<>(counts.keySet());
        final java.util.Map<String, com.google.android.material.checkbox.MaterialCheckBox> checks = new java.util.HashMap<>();
        boolean fa = "fa".equals(prefs.getString("out_lang", "en"));
        for (String iso : order) {
            CountryData.C c = CountryData.byCode(iso);
            String name = iso.isEmpty() ? unkLabel : (c != null ? (fa ? c.fa : c.en) : iso);
            com.google.android.material.checkbox.MaterialCheckBox cb =
                    new com.google.android.material.checkbox.MaterialCheckBox(this);
            int n = counts.get(iso);
            cb.setText((iso.isEmpty() ? "❓" : GeoChecker.flag(iso)) + "  " + name
                    + "  ×  " + n);
            cb.setTextSize(13.5f);
            boolean defChecked = selectedCountries == null || selectedCountries.contains(iso);
            cb.setChecked(defChecked);
            checks.put(iso, cb);
            box.addView(cb);
        }
        wrap.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), 0);

        new AlertDialog.Builder(this)
                .setTitle(R.string.filter_countries)
                .setView(wrap)
                .setPositiveButton(R.string.filter_apply, (d, w) -> {
                    java.util.Set<String> sel = new java.util.HashSet<>();
                    for (String iso : order) {
                        if (checks.get(iso).isChecked()) sel.add(iso);
                    }
                    // everything ticked == no filter; an empty set is now a
                    // legit "Clear all" result and hides every line
                    selectedCountries = sel.equals(new java.util.HashSet<>(order))
                            ? null : sel;
                    applyOutputFilters();
                })
                .setNeutralButton(R.string.filter_select_all, (d, w) -> {
                    selectedCountries = null;
                    applyOutputFilters();
                })
                .setNegativeButton(R.string.filter_none, (d, w) -> {
                    // untick everything: keep only what the user explicitly
                    // re-selects; an empty selection here means "nothing"
                    // stays visible, so treat it as the full set inverted —
                    // practical behavior: show nothing until All is pressed
                    selectedCountries = new java.util.HashSet<>();
                    applyOutputFilters();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Dialog asking how many servers to keep (random) in the output. */
    private void showLimitDialog() {
        List<OutEntry> visible = visibleEntries();
        int max = visible.size();
        if (max == 0) {
            toast(getString(R.string.toast_output_empty));
            return;
        }
        EditText edit = new EditText(this);
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        edit.setHint("1 - " + max);
        edit.setGravity(android.view.Gravity.CENTER);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), 0);
        box.addView(edit);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.limit_count))
                .setMessage(getString(R.string.limit_msg, max))
                .setView(box)
                .setPositiveButton(R.string.filter_apply, (d, w) -> {
                    String t = edit.getText().toString().trim();
                    int n = 0;
                    try { n = Integer.parseInt(t); } catch (Exception ignored) { }
                    if (n <= 0 || n > max) {
                        toast(getString(R.string.limit_bad_range, max));
                        return;
                    }
                    outLimit = n;
                    applyOutputFilters();
                })
                .setNeutralButton(R.string.limit_all, (d, w) -> {
                    outLimit = 0;
                    applyOutputFilters();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** True when the engine log tail contains a fatal engine error or crash. */
    private static boolean looksLikeEngineError(String tail) {
        if (tail == null || tail.isEmpty()) return false;
        String t = tail.toLowerCase();
        return t.contains("panic") || t.contains("fatal") || t.contains("segmentation fault")
                || t.contains("invalid config") || t.contains("unknown config id")
                || t.contains("bind: address already in use");
    }

    /** One short, light vibration when a run reaches 100%. */
    private void vibrateOnce() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(
                        120, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(120);
            }
        } catch (Exception ignored) { }
    }

    private void updateProgress() {
        final int done = doneCount.get();
        final int total = totalCount;
        postUi(() -> {
            int pct = total == 0 ? 0 : (int) (100.0 * done / total);
            waterCircle.setProgress(pct);
            progressPercent.setText(pct + "%");
            progressCount.setText(done + "/" + total);
            if (running && done < total) {
                progressLabel.setText(R.string.progress_testing);
            } else if (!running && done > 0) {
                progressLabel.setText(R.string.progress_done);
            }
        });
        updateScanNotification();
    }

    private void ensureScanNotification() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel("scan_progress") == null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "scan_progress", getString(R.string.scan_notif_title),
                        android.app.NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(getString(R.string.scan_notif_prog, 0, 1, 0));
                nm.createNotificationChannel(ch);
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private void updateScanNotification() {
        // v1.0.61: the ongoing notification now belongs to ScanService
        // (foreground); the activity only feeds it numbers
        ScanService.progress(doneCount.get(), totalCount);
    }

    private void cancelScanNotification() {
        ScanService.end(this);
    }

    private void status(String s) {
        postUi(() -> progressStatus.setText(s));
    }

    /** Debounced output flush — long runs used to rebuild the whole box per
     *  result (O(n^2)); now at most one rebuild per 250ms. When an output
     *  filter (country/limit) is active the FILTERED model is rendered. */
    private final Runnable outputFlusher = new Runnable() {
        @Override public void run() {
            final boolean filtered = (selectedCountries != null && !selectedCountries.isEmpty())
                    || outLimit > 0;
            int nLines;
            StringBuilder sb = new StringBuilder();
            if (filtered) {
                List<OutEntry> vis = visibleEntries();
                nLines = vis.size();
                for (OutEntry e : vis) sb.append(e.line).append("\n");
            } else {
                // v1.0.70 fix: the raw (unfiltered) path used to ignore the
                // hide-unknowns toggle — skip those lines here as well
                final java.util.HashSet<String> unk;
                synchronized (unknownLinks) {
                    unk = new java.util.HashSet<>(unknownLinks);
                }
                final boolean hideUnk = !showUnknowns();
                synchronized (outputLines) {
                    nLines = 0;
                    for (String l : outputLines) {
                        if (hideUnk && !unk.isEmpty() && unk.contains(l)) continue;
                        sb.append(l).append("\n");
                        nLines++;
                    }
                }
            }
            final int n = nLines;
            // Never scroll programmatically while a run is in progress — the
            // ScrollView keeps the user's viewport where they left it.
            final String summary = countrySummaryText();
            // corner counters: lines · processed/total · failed
            final String counter = getString(R.string.lines_count, n)
                    + "  ·  " + getString(R.string.counter_processed,
                            doneCount.get(), totalCount)
                    + "  ·  " + getString(R.string.counter_failed,
                            failedCount.get());
            postUi(() -> {
                outputView.setText(sb.toString());
                outCount.setText(counter);
                outCountrySummary.setText(summary);
                outCountrySummary.setVisibility(
                        summary.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }
    };

    /** Compact per-country totals of the visible output: "🇩🇪 5 · 🇹🇷 3 · ❓ 1". */
    private String countrySummaryText() {
        List<OutEntry> vis = visibleEntries();
        if (vis.isEmpty()) return "";
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (OutEntry e : vis) counts.merge(e.iso, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> en : counts.entrySet()) {
            if (sb.length() > 0) sb.append("  ·  ");
            String icon = en.getKey().isEmpty()
                    ? "\uD83D\uDD27" // wrench-ish fallback: unknown
                    : GeoChecker.flag(en.getKey());
            if (en.getKey().isEmpty()) icon = "\u2753"; // ❓
            sb.append(icon).append(" ").append(en.getValue());
        }
        return sb.toString();
    }

    private void refreshOutput() {
        postUi(() -> {
            main.removeCallbacks(outputFlusher);
            main.postDelayed(outputFlusher, 250);
        });
    }

    private boolean isNearBottom() {
        View child = outputScroll.getChildAt(0);
        if (child == null) return true;
        return (outputScroll.getScrollY() + outputScroll.getHeight())
                >= (child.getHeight() - 60);
    }

    /** Kept as a no-op on purpose: the run must never move the user's scroll
     *  position (the ScrollView preserves it when content grows). */
    private void autoScroll() {
    }

    private boolean isProxyLink(String t) {
        return t.startsWith("vless://") || t.startsWith("vmess://") || t.startsWith("trojan://")
                || t.startsWith("ss://") || t.startsWith("socks://") || t.startsWith("ssr://")
                || t.startsWith("tuic://") || t.startsWith("shadowtls://")
                || t.startsWith("anytls://") || t.startsWith("snic://")
                || t.startsWith("hysteria2://") || t.startsWith("hy2://");
    }

    /** Copy ONLY the working config links (one per unique URI) to the clipboard —
     *  failed lines (❌ …) are not links and are skipped. Country filter and the
     *  count limit are honored: what the box shows is what gets copied. */
    private void copyLinksOnly() {
        java.util.LinkedHashSet<String> links = new java.util.LinkedHashSet<>();
        List<OutEntry> vis = visibleEntries();
        if (vis.isEmpty()) {
            // no entries (or nothing survives the filters) — fall back to raw links
            boolean includeUnknown = prefs.getBoolean("include_unknown_in_links", false);
            synchronized (outputLines) {
                for (String line : outputLines) {
                    String t = line.trim();
                    if (isProxyLink(t)) links.add(t);
                }
            }
            if (!includeUnknown) {
                synchronized (unknownLinks) {
                    links.removeAll(unknownLinks);
                }
            }
        } else {
            for (OutEntry e : vis) links.add(e.line);
        }
        if (links.isEmpty()) {
            toast(getString(R.string.toast_output_empty));
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (runAllJsonInput) {
            // v1.0.73: an all-JSON input exports as a JSON array of full
            // client configs — one self-contained config per server,
            // nothing merged, importable by v2rayN/v2rayNG batch import
            String json = JsonConfigs.exportJsonArray(new ArrayList<>(links));
            cm.setPrimaryClip(ClipData.newPlainText("configs", json));
            toast(getString(R.string.toast_json_copied, links.size()));
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("configs",
                String.join("\n", links)));
        toast(getString(R.string.toast_links_copied, links.size()));
    }

    private void copyAll() {
        String all;
        synchronized (outputLines) {
            all = String.join("\n", outputLines);
        }
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
        if (running || coreUpdating) {
            toast(getString(R.string.core_update_busy));
            return;
        }
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
        if (running || coreUpdating) {
            toast(getString(R.string.core_update_busy));
            return;
        }
        coreUpdating = true;
        syncCoreButtons();
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
                            // beta mode = the NEWEST PRE-RELEASE, skipping
                            // both drafts and stable releases (review fix:
                            // the old condition accepted a stable release,
                            // so beta mode never reached a pre-release)
                            if (r.optBoolean("draft", false)) continue;
                            if (!r.optBoolean("prerelease", false)) continue;
                            tag = r.getString("tag_name");
                            zipUrl = "https://github.com/XTLS/Xray-core/releases/download/"
                                    + tag + "/Xray-android-arm64-v8a.zip";
                            break;
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

                // integrity: fetch the release's expected SHA-256 for this
                // asset from the API (trusted channel = same TLS endpoint
                // that told us the version) and verify after download
                String expectedSha = coreAssetSha256(client, tag,
                        "Xray-android-arm64-v8a.zip");

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

                String zipSha = null;
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

                zipSha = sha256(zipFile);
                AppLog.i("update", "zip sha256=" + zipSha
                        + " (tag " + tag + ", " + zipFile.length() + " bytes)");
                if (expectedSha != null && !zipSha.equalsIgnoreCase(expectedSha)) {
                    // wrong/corrupted artifact — remove it so the cache can
                    // never satisfy a later install attempt
                    zipFile.delete();
                    throw new Exception("core zip SHA-256 mismatch (expected "
                            + expectedSha + ", got " + zipSha + ") — download deleted");
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
                            String vline = XrayManager.installNewBinary(this, zis, cand);
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
            } finally {
                coreUpdating = false;
                postUi(() -> syncCoreButtons());
            }
        }).start();
    }

    /** Expected SHA-256 of a release asset, from GitHub's API "digest"
     *  field (HTTPS, same endpoint that provided the version). Returns null
     *  when the API does not expose a digest — the install then proceeds
     *  with the version self-check only. */
    private static String coreAssetSha256(OkHttpClient client, String tag,
                                          String assetName) throws Exception {
        Request req = new Request.Builder()
                .url("https://api.github.com/repos/XTLS/Xray-core/releases/tags/" + tag)
                .get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            JSONObject rel = new JSONObject(resp.body().string());
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null || !assetName.equals(a.optString("name", ""))) continue;
                String d = a.optString("digest", "");
                if (d.startsWith("sha256:")) d = d.substring(7);
                return d.isEmpty() ? null : d;
            }
        }
        return null;
    }

    /** Cheap APK sanity check: ZIP files start with the bytes "PK". */
    private static boolean zipMagic(File f) {
        java.io.DataInputStream in = null;
        try {
            in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                    new java.io.FileInputStream(f)));
            byte[] m = new byte[2];
            if (in.read(m) != 2) return false;
            return m[0] == 'P' && m[1] == 'K';
        } catch (Exception e) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    private static String sha256(File f) {
        try (java.io.InputStream is = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[32768];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    /** Core update/test and a live run must never overlap. */
    private void syncCoreButtons() {
        boolean busy = running || coreUpdating;
        btnCoreUpdate.setEnabled(!busy);
        btnCoreTest.setEnabled(!busy);
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
                    // find the apk asset — exact expected name for THIS app,
                    // not "the first .apk in the release" (review fix)
                    String wantApk = "ConfigScanner-v" + latest + ".apk";
                    String apkUrl = null;
                    String apkSha = null;
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a == null) continue;
                            String an = a.optString("name", "");
                            if (wantApk.equals(an)) {
                                apkUrl = a.optString("browser_download_url");
                                apkSha = a.optString("digest", "");
                                if (apkSha.startsWith("sha256:")) apkSha = apkSha.substring(7);
                                break;
                            }
                        }
                    }
                    if (apkUrl == null) throw new Exception("no apk asset " + wantApk);
                    File ext = getExternalFilesDir(null);
                    File dir = new File(ext != null ? ext : getFilesDir(), "updates");
                    if (!dir.exists()) dir.mkdirs();
                    File apk = new File(dir, "ConfigScanner-v" + latest + ".apk");
                    File part = new File(dir, apk.getName() + ".part");

                    // FIELD FIX (v1.0.69): an APK left over from an interrupted
                    // earlier download installs as garbage and the package
                    // installer reports "problem parsing the package". Verify
                    // ANY existing file before trusting it — delete if bad.
                    if (apk.exists()) {
                        boolean ok = false;
                        try {
                            if (apkSha != null && !apkSha.isEmpty()) {
                                ok = sha256(apk).equalsIgnoreCase(apkSha);
                            } else {
                                ok = apk.length() > 1_000_000L && zipMagic(apk);
                            }
                        } catch (Exception ve) { ok = false; }
                        if (!ok) {
                            AppLog.w("appupdate", "cached apk FAILED verification — deleting "
                                    + apk.getName() + " (" + apk.length() + " bytes)");
                            apk.delete();
                        }
                    }

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
                    // v1.0.69: download to a .part file — an interrupted
                    // download must never leave a half APK under the real name
                    Request dz = new Request.Builder().url(apkUrl).get().build();
                    try {
                        try (Response dr = client.newCall(dz).execute()) {
                            if (!dr.isSuccessful() || dr.body() == null)
                                throw new Exception("download HTTP " + dr.code());
                            long total = dr.body().contentLength();
                            long got = 0;
                            int lastStep = -1;
                            try (FileOutputStream fos = new FileOutputStream(part);
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
                            AppLog.i("appupdate", "apk download complete: " + part.length() + " bytes");
                            // verify the .part BEFORE it can become installable
                            if (apkSha != null && !apkSha.isEmpty()) {
                                String gotSha = sha256(part);
                                if (!gotSha.equalsIgnoreCase(apkSha)) {
                                    part.delete();
                                    throw new Exception("apk SHA-256 mismatch (expected "
                                            + apkSha + ", got " + gotSha + ") — download deleted");
                                }
                                AppLog.i("appupdate", "apk sha256 verified: " + gotSha);
                            } else if (!zipMagic(part)) {
                                part.delete();
                                throw new Exception("downloaded file is not an APK (bad magic)");
                            }
                            if (part.renameTo(apk)) {
                                AppLog.i("appupdate", "staged download moved into place");
                            } else {
                                java.nio.file.Files.move(part.toPath(), apk.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        final File f = apk;
                        postUi(() -> {
                            appProgressBar.setVisibility(View.GONE);
                            appUpdateStatus.setText(getString(R.string.app_update_ready, latest));
                            btnAppUpdate.setEnabled(true);
                            installApk(f);
                        });
                    } catch (Exception dlErr) {
                        // never leave a half-written .part behind
                        try { part.delete(); } catch (Exception ignored) { }
                        throw dlErr;
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
    /** port -> xray process stdout/err log (config-load errors land here).
     *  Synchronized wrapper — test workers put into it in parallel. */
    private final java.util.Map<Integer, File> procLogs =
            java.util.Collections.synchronizedMap(new java.util.HashMap<Integer, File>());
    /** raw link -> engine error tail of every failed/undetected config (for
     *  the copyable Failed block in the log dialog). Synchronized wrapper —
     *  test workers put into it in parallel; iteration happens under the
     *  map's monitor so the order (insertion) is stable. */
    private final java.util.Map<String, String> failedReasons =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>());
    private final java.util.concurrent.atomic.AtomicInteger failedCount = new java.util.concurrent.atomic.AtomicInteger();
    /** versionCode of the running build at the moment the installer was
     *  launched — lets onResume detect "the app updated itself" and restart
     *  cleanly even when the OEM installer's own Open button is dead. */
    private int installStartVersion = -1;

    private int runningVersionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(
                    getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

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
            return;
        }
        // The installer finished while we were in the background and the
        // build changed under us. Some OEM installers have a dead "Open"
        // button — restart THIS app ourselves so the new version is in
        // front of the user without them hunting for the icon.
        if (installStartVersion > 0 && runningVersionCode() != installStartVersion) {
            AppLog.i("appupdate", "build changed " + installStartVersion
                    + " -> " + runningVersionCode() + " — auto-restarting app");
            installStartVersion = -1;
            try {
                android.content.Intent li = getPackageManager()
                        .getLaunchIntentForPackage(getPackageName());
                if (li != null) {
                    li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    finish();
                    startActivity(li);
                    return;
                }
            } catch (Exception e) {
                AppLog.e("appupdate", "auto-restart failed: " + e.getMessage());
            }
        }
        installStartVersion = -1;
    }

    private void doInstall(File apk) {
        try {
            installStartVersion = runningVersionCode();
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apk);
            if (isFinishing()) return;
            // ACTION_INSTALL_PACKAGE is the installer's real contract (the
            // generic ACTION_VIEW package-archive intent is the one some
            // OEM installers reject silently — the "Open" button then does
            // nothing). Extras tell the installer to show Open (-> this app)
            // and to delete the staged apk afterwards.
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_INSTALL_PACKAGE);
            i.setData(uri);
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(android.content.Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            i.putExtra(android.content.Intent.EXTRA_RETURN_RESULT, true);
            i.putExtra(android.content.Intent.EXTRA_INSTALLER_PACKAGE_NAME, getPackageName());
            try {
                startActivity(i);
                return;
            } catch (Exception inner) {
                AppLog.w("appupdate", "INSTALL_PACKAGE intent rejected (" + inner.getMessage()
                        + ") — trying ACTION_VIEW");
            }
            android.content.Intent v = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            v.setDataAndType(uri, "application/vnd.android.package-archive");
            v.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(v);
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
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, apk.getName());
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE,
                        "application/vnd.android.package-archive");
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download");
                android.net.Uri pub = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (pub == null) throw new Exception("no media uri");
                try (java.io.OutputStream os = getContentResolver().openOutputStream(pub);
                     java.io.InputStream is = new FileInputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
                if (isFinishing()) return;
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_INSTALL_PACKAGE);
                i.setData(pub);
                i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.putExtra(android.content.Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                i.putExtra(android.content.Intent.EXTRA_INSTALLER_PACKAGE_NAME, getPackageName());
                try {
                    startActivity(i);
                } catch (Exception inner) {
                    android.content.Intent v = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    v.setDataAndType(pub, "application/vnd.android.package-archive");
                    v.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(v);
                }
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
        final String failedBlock = buildFailedBlock();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.log_title)
                .setNeutralButton(R.string.log_clear, (d, w) -> AppLog.clear())
                .setNegativeButton(R.string.log_save, (d, w) ->
                        logExportLauncher.launch("cfgscan_log_"
                                + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                                .format(new java.util.Date()) + ".txt"));
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        EditText et = new EditText(this);
        et.setText(log + failedBlock);
        et.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        et.setTextIsSelectable(true);
        box.addView(et, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        if (!failedReasons.isEmpty()) {
            // dedicated button under the text: copies ONLY the failed block
            // (a second neutral dialog button would overwrite "Clear log")
            android.widget.Button fbtn = new android.widget.Button(this);
            fbtn.setText(getString(R.string.failed_section_title, failedReasons.size()));
            fbtn.setOnClickListener(v -> {
                ClipboardManager cm2 =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm2.setPrimaryClip(ClipData.newPlainText("failed", failedBlock));
                toast(getString(R.string.failed_copied, failedReasons.size()));
            });
            box.addView(fbtn, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        b.setView(box);
        b.setPositiveButton(R.string.log_copy, (d, w) -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", log));
            toast(getString(R.string.toast_copy_done));
        });
        b.show();
    }

    /** The copyable block of every failed / undetected config of the LAST
     *  run with its engine reason — built for sending to debug. */
    private String buildFailedBlock() {
        if (failedReasons.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n—— ").append(getString(R.string.failed_section_title,
                failedReasons.size())).append(" ——\n");
        int i = 1;
        synchronized (failedReasons) {
            for (java.util.Map.Entry<String, String> e : failedReasons.entrySet()) {
                String reason = e.getValue() == null ? "" : e.getValue().replace("\n", " ");
                if (reason.length() > 400) reason = reason.substring(0, 400) + "…";
                sb.append(i++).append(". ").append(e.getKey())
                  .append("\n   → ").append(reason).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (running) stopRun();
        super.onDestroy();
        if (pool != null) pool.shutdownNow();
    }
}
