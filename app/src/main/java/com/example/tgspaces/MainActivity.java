package com.example.tgspaces;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.content.res.Configuration;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TGSpaces";
    private static final String PREFS_NAME = "slot_names";
    private static final String KEY_SLOT_COUNT = "slot_count";
    private static final String KEY_PENDING_APK = "pending_apk_path";
    private static final String KEY_PENDING_SLOT = "pending_slot";
    private static final String KEY_PENDING_DOWNLOAD_ID = "pending_download_id";
    private static final String KEY_DOWNLOAD_SLOT_PREFIX = "download_slot_";
    private static final String KEY_SLOT_DOWNLOAD_PREFIX = "slot_download_";
    private static final String KEY_SLOT_ERROR_PREFIX = "slot_error_";
    private static final String KEY_SLOT_AUTO_OPENED_PREFIX = "slot_auto_opened_";
    private static final String KEY_SLOT_HIDDEN_PREFIX = "slot_hidden_";
    private static final String KEY_SLOT_VISIBILITY_INITIALIZED = "slot_visibility_initialized";
    static final String KEY_ONBOARDING_SEEN = "onboarding_seen";
    static final String EXTRA_SHOW_ONBOARDING = "show_onboarding";
    private static final String KEY_DISPLAY_MODE_COMPACT = "display_mode_compact";
    private static final String KEY_DISPLAY_MODE = "display_mode";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String THEME_MODE_SYSTEM = "system";
    private static final String THEME_MODE_LIGHT = "light";
    private static final String THEME_MODE_DARK = "dark";
    private static final String KEY_APP_UPDATE_DOWNLOAD_ID = "app_update_download_id";
    private static final String KEY_APP_UPDATE_APK_PATH = "app_update_apk_path";
    private static final String KEY_APP_UPDATE_DOWNLOAD_FAILED = "app_update_download_failed";
    private static final String KEY_SETTINGS_CATALOG_REFRESHED = "settings_catalog_refreshed";
    private static final int MAX_CLONE_APKS = 10;
    private static final String CATALOG_URL = "https://raw.githubusercontent.com/DesperadoBoi/TGSpaces/main/catalog/clones.json";
    private static final String APP_CATALOG_URL = "https://raw.githubusercontent.com/DesperadoBoi/TGSpaces/main/catalog/app.json";
    private static final String RELEASE_BASE_URL = "https://github.com/DesperadoBoi/TGSpaces/releases/download/v0.2-release/";
    private static final long PROGRESS_REFRESH_MS = 1200L;

    private final Map<Long, Integer> downloadSlots = new HashMap<>();
    private final Map<Integer, CloneInfo> cloneCatalog = new HashMap<>();
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private AppUpdateInfo appUpdateInfo;
    private SharedPreferences preferences;
    private LinearLayout appUpdateBanner;
    private TextView appUpdateText;
    private Button appUpdateButton;
    private LinearLayout slotsContainer;
    private LinearLayout slotEmptyState;
    private TextView displayModeCardsButton;
    private TextView displayModeCompactButton;
    private TextView displayModeGridButton;
    private EditText slotSearchInput;
    private TextView filterAllButton;
    private TextView filterInstalledButton;
    private TextView filterFreeButton;
    private TextView filterUpdatesButton;
    private AlertDialog onboardingDialog;
    private int visibleSlotCount = 1;
    private boolean slotVisibilityLogged;
    private DisplayMode displayMode = DisplayMode.CARDS;
    private String slotSearchQuery = "";
    private SlotFilter activeSlotFilter = SlotFilter.ALL;
    private String themeMode = THEME_MODE_DARK;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            checkDownloadsForTerminalStates(true);
            renderSlots();
            if (hasActiveDownloads()) {
                progressHandler.postDelayed(this, PROGRESS_REFRESH_MS);
            }
        }
    };

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (downloadId > 0 && downloadId == preferences.getLong(KEY_APP_UPDATE_DOWNLOAD_ID, -1L)) {
                handleDownloadedAppUpdate(downloadId, true);
                return;
            }

            Integer slot = downloadSlots.remove(downloadId);
            if (slot == null) {
                int savedSlot = preferences.getInt(downloadSlotKey(downloadId), 0);
                slot = savedSlot > 0 ? savedSlot : null;
            }
            if (slot != null) {
                preferences.edit().remove(downloadSlotKey(downloadId)).apply();
                handleDownloadedApk(downloadId, slot, true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences startupPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        themeMode = startupPreferences.getString(KEY_THEME_MODE, THEME_MODE_DARK);
        applyThemeMode(themeMode);

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        appUpdateBanner = findViewById(R.id.appUpdateBanner);
        appUpdateText = findViewById(R.id.appUpdateText);
        appUpdateButton = findViewById(R.id.buttonDownloadAppUpdate);
        slotsContainer = findViewById(R.id.slotsContainer);
        slotEmptyState = findViewById(R.id.slotEmptyState);
        displayModeCardsButton = findViewById(R.id.modeCards);
        displayModeCompactButton = findViewById(R.id.modeCompact);
        displayModeGridButton = findViewById(R.id.modeGrid);
        slotSearchInput = findViewById(R.id.inputSlotSearch);
        filterAllButton = findViewById(R.id.filterAll);
        filterInstalledButton = findViewById(R.id.filterInstalled);
        filterFreeButton = findViewById(R.id.filterFree);
        filterUpdatesButton = findViewById(R.id.filterUpdates);
        displayMode = loadDisplayMode();
        themeMode = preferences.getString(KEY_THEME_MODE, THEME_MODE_DARK);
        Log.d(TAG, "display mode loaded: " + displayMode.preferenceValue);

        findViewById(R.id.buttonAddSlot).setOnClickListener(view -> addSlot());
        displayModeCardsButton.setOnClickListener(view -> setDisplayMode(DisplayMode.CARDS));
        displayModeCompactButton.setOnClickListener(view -> setDisplayMode(DisplayMode.COMPACT));
        displayModeGridButton.setOnClickListener(view -> setDisplayMode(DisplayMode.GRID));
        findViewById(R.id.buttonSettings).setOnClickListener(view -> openSettings());
        findViewById(R.id.buttonThemeToggle).setOnClickListener(view -> toggleLightDarkTheme());
        appUpdateButton.setOnClickListener(view -> downloadAppUpdate());
        updateDisplayModeControls();
        updateThemeToggleButton();
        setupSlotSearchAndFilters();

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        checkDownloadsForTerminalStates(false);
        checkAppUpdateDownload(false);
        renderSlots();
        loadCloneCatalog();
        loadAppCatalog();
        showOnboardingIfNeeded();
        if (getIntent().getBooleanExtra(EXTRA_SHOW_ONBOARDING, false)) {
            showOnboarding();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_ONBOARDING, false)) {
            showOnboarding();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String newThemeMode = preferences.getString(KEY_THEME_MODE, THEME_MODE_DARK);
        if (!newThemeMode.equals(themeMode)) {
            themeMode = newThemeMode;
            applyThemeMode(themeMode);
            recreate();
            return;
        }
        DisplayMode newDisplayMode = loadDisplayMode();
        if (displayMode != newDisplayMode) {
            displayMode = newDisplayMode;
            updateDisplayModeControls();
        }
        if (preferences.getBoolean(KEY_SETTINGS_CATALOG_REFRESHED, false)) {
            preferences.edit().remove(KEY_SETTINGS_CATALOG_REFRESHED).apply();
            loadCloneCatalog();
            loadAppCatalog();
        }
        checkDownloadsForTerminalStates(false);
        checkAppUpdateDownload(true);
        renderSlots();
    }

    @Override
    protected void onDestroy() {
        progressHandler.removeCallbacks(progressRunnable);
        unregisterReceiver(downloadReceiver);
        super.onDestroy();
    }

    private void renderSlots() {
        ensureSlotVisibilityInitialized();
        int savedSlotCount = Math.max(1, preferences.getInt(KEY_SLOT_COUNT, 1));
        visibleSlotCount = Math.min(MAX_CLONE_APKS, Math.max(savedSlotCount, nextRequiredSlotCount()));
        if (visibleSlotCount != savedSlotCount) {
            preferences.edit().putInt(KEY_SLOT_COUNT, visibleSlotCount).apply();
        }
        logSlotVisibilityStateOnce();

        slotsContainer.removeAllViews();
        GridLayout grid = null;
        if (displayMode == DisplayMode.GRID) {
            grid = new GridLayout(this);
            grid.setColumnCount(gridColumnCount());
            grid.setUseDefaultMargins(false);
            grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
            grid.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            slotsContainer.addView(grid);
        }
        int renderedSlots = 0;
        for (int slot = 1; slot <= visibleSlotCount; slot++) {
            if (isSlotHidden(slot) && !isAppInstalled(packageName(slot))) {
                continue;
            }
            SlotState state = getSlotState(slot);
            if (!matchesSlotFilters(slot, state)) {
                continue;
            }
            if (displayMode == DisplayMode.GRID) {
                grid.addView(renderGridSlot(slot));
            } else {
                slotsContainer.addView(displayMode == DisplayMode.COMPACT ? renderCompactSlot(slot) : renderCardSlot(slot));
            }
            renderedSlots++;
        }
        slotEmptyState.setVisibility(renderedSlots == 0 ? View.VISIBLE : View.GONE);
        scheduleProgressRefreshIfNeeded();
    }

    private int gridColumnCount() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp >= 840) {
            return 4;
        }
        if (widthDp >= 600) {
            return 3;
        }
        return 2;
    }

    private void setupSlotSearchAndFilters() {
        slotSearchInput.setHint("Поиск по слоту");
        filterAllButton.setText("Все");
        filterInstalledButton.setText("Установлены");
        filterFreeButton.setText("Свободные");
        filterUpdatesButton.setText("Обновления");

        slotSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                slotSearchQuery = text != null ? text.toString().trim().toLowerCase(Locale.US) : "";
                renderSlots();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });

        filterAllButton.setOnClickListener(view -> setSlotFilter(SlotFilter.ALL));
        filterInstalledButton.setOnClickListener(view -> setSlotFilter(SlotFilter.INSTALLED));
        filterFreeButton.setOnClickListener(view -> setSlotFilter(SlotFilter.FREE));
        filterUpdatesButton.setOnClickListener(view -> setSlotFilter(SlotFilter.UPDATES));
        updateSlotFilterChips();
    }

    private void setSlotFilter(SlotFilter filter) {
        if (activeSlotFilter == filter) {
            return;
        }
        activeSlotFilter = filter;
        updateSlotFilterChips();
        renderSlots();
    }

    private void updateSlotFilterChips() {
        styleSlotFilterChip(filterAllButton, activeSlotFilter == SlotFilter.ALL);
        styleSlotFilterChip(filterInstalledButton, activeSlotFilter == SlotFilter.INSTALLED);
        styleSlotFilterChip(filterFreeButton, activeSlotFilter == SlotFilter.FREE);
        styleSlotFilterChip(filterUpdatesButton, activeSlotFilter == SlotFilter.UPDATES);
    }

    private void styleSlotFilterChip(TextView button, boolean active) {
        button.setTextColor(ContextCompat.getColor(this, active ? R.color.button_primary_text : R.color.button_secondary_text));
        button.setBackgroundResource(active ? R.drawable.button_segment_active_background : R.drawable.button_segment_inactive_background);
        button.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
    }

    private boolean matchesSlotFilters(int slot, SlotState state) {
        if (!matchesSlotFilter(state)) {
            return false;
        }
        if (slotSearchQuery.isEmpty()) {
            return true;
        }

        String slotNumber = String.valueOf(slot);
        String paddedSlotNumber = String.format(Locale.US, "%02d", slot);
        String displayName = slotName(slot).toLowerCase(Locale.US);
        String defaultName = defaultSlotName(slot).toLowerCase(Locale.US);
        String catalogName = cloneName(slot).toLowerCase(Locale.US);

        return slotNumber.contains(slotSearchQuery)
                || paddedSlotNumber.contains(slotSearchQuery)
                || displayName.contains(slotSearchQuery)
                || defaultName.contains(slotSearchQuery)
                || catalogName.contains(slotSearchQuery);
    }

    private boolean matchesSlotFilter(SlotState state) {
        switch (activeSlotFilter) {
            case INSTALLED:
                return state.type == SlotStateType.INSTALLED || state.type == SlotStateType.UPDATE_AVAILABLE;
            case FREE:
                return state.type == SlotStateType.NOT_INSTALLED
                        || state.type == SlotStateType.DOWNLOADING
                        || state.type == SlotStateType.WAITING_INSTALL
                        || state.type == SlotStateType.DOWNLOAD_ERROR;
            case UPDATES:
                return state.type == SlotStateType.UPDATE_AVAILABLE;
            case ALL:
            default:
                return true;
        }
    }

    private DisplayMode loadDisplayMode() {
        String savedMode = preferences.getString(KEY_DISPLAY_MODE, null);
        if (savedMode != null) {
            return DisplayMode.fromPreferenceValue(savedMode);
        }
        return preferences.getBoolean(KEY_DISPLAY_MODE_COMPACT, false) ? DisplayMode.COMPACT : DisplayMode.CARDS;
    }

    private void setDisplayMode(DisplayMode mode) {
        if (displayMode == mode) {
            return;
        }
        displayMode = mode;
        preferences.edit()
                .putString(KEY_DISPLAY_MODE, displayMode.preferenceValue)
                .putBoolean(KEY_DISPLAY_MODE_COMPACT, displayMode == DisplayMode.COMPACT)
                .apply();
        Log.d(TAG, "display mode changed: " + displayMode.preferenceValue);
        updateDisplayModeControls();
        renderSlots();
    }

    private void updateDisplayModeControls() {
        if (displayModeCardsButton == null || displayModeCompactButton == null || displayModeGridButton == null) {
            return;
        }

        styleDisplayModeButton(displayModeCardsButton, displayMode == DisplayMode.CARDS);
        styleDisplayModeButton(displayModeCompactButton, displayMode == DisplayMode.COMPACT);
        styleDisplayModeButton(displayModeGridButton, displayMode == DisplayMode.GRID);
    }

    private void styleDisplayModeButton(TextView button, boolean active) {
        button.setTextColor(ContextCompat.getColor(this, active ? R.color.button_primary_text : R.color.button_secondary_text));
        button.setBackgroundResource(active ? R.drawable.button_segment_active_background : R.drawable.button_segment_inactive_background);
        button.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void setThemeMode(String mode) {
        if (mode.equals(themeMode)) {
            return;
        }

        themeMode = mode;
        preferences.edit().putString(KEY_THEME_MODE, themeMode).apply();
        applyThemeMode(themeMode);
        recreate();
    }

    private void toggleLightDarkTheme() {
        boolean currentlyDark = isCurrentlyDarkTheme();
        setThemeMode(currentlyDark ? THEME_MODE_LIGHT : THEME_MODE_DARK);
    }

    private boolean isCurrentlyDarkTheme() {
        if (THEME_MODE_DARK.equals(themeMode)) {
            return true;
        }
        if (THEME_MODE_LIGHT.equals(themeMode)) {
            return false;
        }
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void updateThemeToggleButton() {
        Button button = findViewById(R.id.buttonThemeToggle);
        if (button == null) {
            return;
        }
        button.setText(isCurrentlyDarkTheme() ? "☀" : "☾");
        button.setTextColor(ContextCompat.getColor(this, R.color.button_secondary_text));
        button.setBackgroundResource(R.drawable.button_overflow_background);
    }

    private void applyThemeMode(String mode) {
        int nightMode;
        if (THEME_MODE_LIGHT.equals(mode)) {
            nightMode = AppCompatDelegate.MODE_NIGHT_NO;
        } else if (THEME_MODE_DARK.equals(mode)) {
            nightMode = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    private int statusColor(SlotState state) {
        return state.statusColorRes;
    }

    private View renderCardSlot(int slot) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(12));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardParams);

        SlotState state = getSlotState(slot);
        card.setBackgroundResource(
                state.type == SlotStateType.UPDATE_AVAILABLE
                        ? R.drawable.slot_card_update_background
                        : R.drawable.slot_card_background
        );

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        textParams.setMargins(0, 0, dp(10), 0);
        textColumn.setLayoutParams(textParams);

        TextView title = new TextView(this);
        title.setText(slotName(slot));
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(title);

        TextView status = new TextView(this);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(2);
        status.setLayoutParams(statusParams);
        status.setText(compactStatusText(slot, state));
        status.setTextColor(ContextCompat.getColor(this, statusColor(state)));
        status.setTextSize(14);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(status);

        header.addView(textColumn);

        Button menuButton = createMenuButton(false, view -> showSlotMenu(view, slot, state.type));
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        menuButton.setLayoutParams(menuParams);
        header.addView(menuButton);
        card.addView(header);

        if (state.hintText != null
                && state.type != SlotStateType.INSTALLED
                && state.type != SlotStateType.UPDATE_AVAILABLE) {
            TextView hint = new TextView(this);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            hintParams.topMargin = dp(6);
            hint.setLayoutParams(hintParams);
            hint.setText(state.hintText);
            hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            hint.setTextSize(13);
            hint.setLineSpacing(dp(2), 1.0f);
            card.addView(hint);
        }

        addSingleAction(card, createPrimarySlotActionButton(slot, state, false), false);
        return card;
    }

    private View renderCompactSlot(int slot) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(5), dp(6), dp(5));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, 0, 0, dp(3));
        row.setLayoutParams(rowParams);

        SlotState state = getSlotState(slot);
        row.setBackgroundResource(
                state.type == SlotStateType.UPDATE_AVAILABLE
                        ? R.drawable.slot_compact_update_background
                        : R.drawable.slot_compact_background
        );

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        textParams.setMargins(0, 0, dp(8), 0);
        textColumn.setLayoutParams(textParams);

        TextView title = new TextView(this);
        title.setText(slotName(slot));
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(title);

        TextView status = new TextView(this);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(2);
        status.setLayoutParams(statusParams);
        status.setText(compactStatusText(slot, state));
        status.setTextColor(ContextCompat.getColor(this, statusColor(state)));
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(status);

        row.addView(textColumn);
        addSlotPrimaryAction(row, slot, state, true);
        return row;
    }

    private View renderGridSlot(int slot) {
        SlotState state = getSlotState(slot);

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(6), dp(7), dp(6), dp(8));
        tile.setBackgroundResource(
                state.type == SlotStateType.UPDATE_AVAILABLE
                        ? R.drawable.slot_grid_update_background
                        : R.drawable.slot_grid_background
        );
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> handleGridSlotClick(slot, state.type));
        tile.setOnLongClickListener(view -> {
            showSlotMenu(view, slot, state.type);
            return true;
        });

        GridLayout.LayoutParams tileParams = new GridLayout.LayoutParams();
        tileParams.width = 0;
        tileParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
        tileParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        tileParams.setMargins(dp(3), dp(3), dp(3), dp(7));
        tile.setLayoutParams(tileParams);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView icon = new TextView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        iconParams.setMargins(0, 0, dp(4), 0);
        icon.setLayoutParams(iconParams);
        icon.setGravity(Gravity.CENTER);
        icon.setBackgroundResource(R.drawable.slot_grid_icon_background);
        icon.setText(String.format(Locale.US, "%02d", slot));
        icon.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        icon.setTextSize(15);
        icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        topRow.addView(icon);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        topRow.addView(spacer);

        Button menuButton = createMenuButton(true, view -> showSlotMenu(view, slot, state.type));
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        menuButton.setLayoutParams(menuParams);
        topRow.addView(menuButton);
        tile.addView(topRow);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(7);
        title.setLayoutParams(titleParams);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(slotName(slot));
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tile.addView(title);

        TextView status = new TextView(this);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(3);
        status.setLayoutParams(statusParams);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setText(gridStatusText(state));
        status.setTextColor(ContextCompat.getColor(this, statusColor(state)));
        status.setTextSize(10);
        tile.addView(status);

        if (state.type == SlotStateType.UPDATE_AVAILABLE) {
            TextView badge = new TextView(this);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(24)
            );
            badgeParams.topMargin = dp(6);
            badge.setLayoutParams(badgeParams);
            badge.setGravity(Gravity.CENTER);
            badge.setIncludeFontPadding(false);
            badge.setSingleLine(true);
            badge.setPadding(dp(8), 0, dp(8), 0);
            badge.setText("Обновить");
            badge.setTextColor(ContextCompat.getColor(this, R.color.button_primary_text));
            badge.setTextSize(10);
            badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            badge.setBackgroundResource(R.drawable.slot_update_badge_background);
            tile.addView(badge);
        }

        return tile;
    }

    private void handleGridSlotClick(int slot, SlotStateType stateType) {
        switch (stateType) {
            case INSTALLED:
                openSlot(slot);
                break;
            case UPDATE_AVAILABLE:
                downloadCloneApk(slot);
                break;
            case NOT_INSTALLED:
                showInstallDialog(slot);
                break;
            case WAITING_INSTALL:
                openInstallerForDownloadedApk(slot, getKnownDownloadId(slot));
                break;
            case DOWNLOAD_ERROR:
                retryCloneDownload(slot);
                break;
            case DOWNLOADING:
            default:
                Toast.makeText(this, "Загрузка уже идет", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private String gridStatusText(SlotState state) {
        switch (state.type) {
            case UPDATE_AVAILABLE:
                return "Доступно обновление";
            case INSTALLED:
                return "Установлено";
            case DOWNLOADING:
                return "Скачивается";
            case WAITING_INSTALL:
                return "Не установлен";
            case DOWNLOAD_ERROR:
                return "Ошибка";
            case NOT_INSTALLED:
            default:
                return "Не установлен";
        }
    }

    private Button createPrimarySlotActionButton(int slot, SlotState state, boolean compact) {
        switch (state.type) {
            case UPDATE_AVAILABLE:
                return createButton("Обновить", true, true, compact, view -> downloadCloneApk(slot));
            case INSTALLED:
                return createButton("Открыть", true, true, compact, view -> openSlot(slot));
            case DOWNLOADING:
                return createButton("Отменить", true, true, compact, view -> cancelCloneDownload(slot));
            case WAITING_INSTALL:
                return createButton("Установить", true, true, compact, view -> openInstallerForDownloadedApk(slot, getKnownDownloadId(slot)));
            case DOWNLOAD_ERROR:
                return createButton("Повторить", true, true, compact, view -> retryCloneDownload(slot));
            case NOT_INSTALLED:
            default:
                return createButton("Установить", true, true, compact, view -> showInstallDialog(slot));
        }
    }

    private void addSlotPrimaryAction(LinearLayout card, int slot, SlotState state, boolean compact) {
        if (state != null) {
            addActionRow(
                    card,
                    createPrimarySlotActionButton(slot, state, compact),
                    createMenuButton(compact, view -> showSlotMenu(view, slot, state.type)),
                    compact
            );
            return;
        }

        switch (state.type) {
            case UPDATE_AVAILABLE:
                addActionRow(card,
                        createButton("Обновить", true, true, compact, view -> downloadCloneApk(slot)),
                        createMenuButton(compact, view -> showSlotMenu(view, slot, state.type)),
                        compact);
                break;
            case INSTALLED:
                addActionRow(card,
                        createButton("Открыть", true, true, compact, view -> openSlot(slot)),
                        createMenuButton(compact, view -> showSlotMenu(view, slot, state.type)),
                        compact);
                break;
            case DOWNLOADING:
                addSingleAction(card, createButton("Отменить", true, true, compact, view -> cancelCloneDownload(slot)), compact);
                break;
            case WAITING_INSTALL:
                addActionRow(card,
                        createButton("Продолжить установку", true, true, compact, view -> openInstallerForDownloadedApk(slot, getKnownDownloadId(slot))),
                        createMenuButton(compact, view -> showSlotMenu(view, slot, state.type)),
                        compact);
                break;
            case DOWNLOAD_ERROR:
                addActionRow(card,
                        createButton("Повторить загрузку", true, true, compact, view -> retryCloneDownload(slot)),
                        createMenuButton(compact, view -> showSlotMenu(view, slot, state.type)),
                        compact);
                break;
            case NOT_INSTALLED:
            default:
                addActionRow(card,
                        createButton("Скачать и установить", true, true, compact, view -> showInstallDialog(slot)),
                        createMenuButton(compact, view -> showSlotMenu(view, slot, state.type)),
                        compact);
                break;
        }
    }

    private String compactStatusText(int slot, SlotState state) {
        switch (state.type) {
            case INSTALLED: {
                InstalledCloneInfo installedCloneInfo = getInstalledCloneInfo(slot);
                String versionName = installedCloneInfo != null ? installedCloneInfo.versionName : "";
                return versionName == null || versionName.isEmpty()
                        ? "Установлено"
                        : "Установлено · " + versionName;
            }
            case UPDATE_AVAILABLE: {
                InstalledCloneInfo installedCloneInfo = getInstalledCloneInfo(slot);
                CloneInfo cloneInfo = cloneCatalog.get(slot);
                String installed = installedCloneInfo != null ? versionText(installedCloneInfo.versionName) : "неизвестно";
                String remote = cloneInfo != null ? versionText(cloneInfo.versionName) : "неизвестно";
                return "Обновление · " + installed + " → " + remote;
            }
            case DOWNLOADING:
            case WAITING_INSTALL:
            case DOWNLOAD_ERROR:
            case NOT_INSTALLED:
            default:
                return state.statusText;
        }
    }

    private void addButtonRow(LinearLayout card, Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        firstParams.setMargins(0, 0, dp(4), 0);
        first.setLayoutParams(firstParams);

        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        secondParams.setMargins(dp(4), 0, 0, 0);
        second.setLayoutParams(secondParams);

        row.addView(first);
        row.addView(second);
        card.addView(row);
    }

    private void addActionRow(LinearLayout card, Button primaryButton, Button menuButton, boolean compact) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                compact ? LinearLayout.LayoutParams.WRAP_CONTENT : LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = compact ? 0 : dp(8);
        row.setLayoutParams(rowParams);

        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
                compact ? LinearLayout.LayoutParams.WRAP_CONTENT : 0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                compact ? 0 : 1
        );
        primaryParams.setMargins(0, 0, dp(6), 0);
        primaryButton.setLayoutParams(primaryParams);

        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(
                dp(48),
                dp(48)
        );
        menuButton.setLayoutParams(menuParams);

        row.addView(primaryButton);
        row.addView(menuButton);
        card.addView(row);
    }

    private void addSingleAction(LinearLayout card, Button primaryButton, boolean compact) {
        if (!compact) {
            card.addView(primaryButton);
            return;
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        primaryButton.setLayoutParams(params);
        card.addView(primaryButton);
    }

    private Button createButton(String text, boolean primary, boolean enabled, boolean compact, View.OnClickListener listener) {
        Button button = new Button(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = compact ? 0 : dp(8);
        button.setLayoutParams(params);
        button.setText(text);
        button.setAllCaps(false);
        button.setEnabled(enabled);
        button.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setMinHeight(dp(compact ? 40 : 44));
        button.setMinimumHeight(dp(compact ? 40 : 44));
        button.setPadding(dp(compact ? 12 : 14), 0, dp(compact ? 12 : 14), 0);
        button.setTextSize(compact ? 12 : 13);
        button.setTextColor(ContextCompat.getColor(this, primary ? R.color.slot_action_text : R.color.button_secondary_text));
        button.setBackgroundResource(primary ? R.drawable.button_slot_action_background : R.drawable.button_secondary_background);
        if (listener != null) {
            button.setOnClickListener(listener);
        }
        return button;
    }

    private Button createMenuButton(boolean compact, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText("⋮");
        button.setAllCaps(false);
        button.setEnabled(true);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(0, 0, 0, 0);
        button.setTextSize(20);
        button.setTextColor(ContextCompat.getColor(this, R.color.button_secondary_text));
        button.setBackgroundResource(R.drawable.button_overflow_background);
        button.setOnClickListener(listener);
        return button;
    }

    private void showSlotMenu(View anchor, int slot, SlotStateType stateType) {
        PopupMenu menu = new PopupMenu(this, anchor);

        switch (stateType) {
            case INSTALLED:
                menu.getMenu().add("Переименовать");
                menu.getMenu().add("Настройки");
                menu.getMenu().add("Удалить клон");
                break;
            case UPDATE_AVAILABLE:
                menu.getMenu().add("Открыть");
                menu.getMenu().add("Настройки");
                menu.getMenu().add("Переименовать");
                menu.getMenu().add("Удалить клон");
                break;
            case WAITING_INSTALL:
                menu.getMenu().add("Скачать заново");
                menu.getMenu().add("Скрыть слот");
                break;
            case DOWNLOAD_ERROR:
                menu.getMenu().add("Скрыть слот");
                break;
            case NOT_INSTALLED:
                menu.getMenu().add("Переименовать");
                menu.getMenu().add("Скрыть слот");
                break;
            case DOWNLOADING:
                menu.getMenu().add("Отменить загрузку");
                break;
            default:
                return;
        }

        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Открыть".equals(title)) {
                openSlot(slot);
            } else if ("Настройки".equals(title)) {
                openAppSettings(slot);
            } else if ("Переименовать".equals(title)) {
                showRenameDialog(slot);
            } else if ("Удалить клон".equals(title)) {
                requestUninstallClone(slot);
            } else if ("Скачать заново".equals(title)) {
                restartCloneDownload(slot);
            } else if ("Скрыть слот".equals(title)) {
                hideSlot(slot);
            } else if ("Отменить загрузку".equals(title)) {
                cancelCloneDownload(slot);
            }
            return true;
        });
        menu.show();
    }

    private void showOnboardingIfNeeded() {
        if (preferences.getBoolean(KEY_ONBOARDING_SEEN, false)) {
            return;
        }

        showOnboarding();
    }

    private void showOnboarding() {
        if (onboardingDialog != null && onboardingDialog.isShowing()) {
            return;
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_onboarding, null);
        onboardingDialog = new AlertDialog.Builder(this)
                .setView(content)
                .create();
        content.findViewById(R.id.buttonOnboardingDone).setOnClickListener(view -> {
            preferences.edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply();
            onboardingDialog.dismiss();
        });
        content.findViewById(R.id.buttonOnboardingHelp).setOnClickListener(view -> showHelpDialog());
        onboardingDialog.setOnDismissListener(dialog -> onboardingDialog = null);
        onboardingDialog.show();
    }

    private void showHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Помощь / О приложении")
                .setMessage(
                        "TGSpaces — менеджер отдельных Telegram-клонов.\n\n"
                                + "Что такое слот:\n"
                                + "Слот — это отдельная копия Telegram с отдельным входом в аккаунт.\n\n"
                                + "Как установить клон:\n"
                                + "1. Нажмите \"Добавить слот\".\n"
                                + "2. Нажмите \"Скачать и установить\".\n"
                                + "3. Дождитесь загрузки APK.\n"
                                + "4. Подтвердите установку в системном установщике Android.\n\n"
                                + "Важно:\n"
                                + "- Для установки может потребоваться разрешение \"Установка неизвестных приложений\".\n"
                                + "- TGSpaces не является официальным приложением Telegram."
                )
                .setPositiveButton("ОК", null)
                .show();
    }

    private SlotState getSlotState(int slot) {
        InstalledCloneInfo installedCloneInfo = getInstalledCloneInfo(slot);
        long downloadId = preferences.getLong(slotDownloadKey(slot), -1L);
        if (downloadId > 0) {
            DownloadInfo info = queryDownload(downloadId);
            if (info == null) {
                String message = "Загрузка не найдена в DownloadManager";
                storeDownloadError(slot, message);
                logDownloadProblem(slot, downloadId, null, 0, 0);
                clearSlotDownload(slot);
                return SlotState.error(message);
            }
            if (info.status == DownloadManager.STATUS_SUCCESSFUL) {
                markDownloadReadyForInstall(slot, downloadId, info);
                return SlotState.waitingInstall();
            }
            if (info.status == DownloadManager.STATUS_FAILED) {
                String message = downloadReasonToMessage(info.reason);
                storeDownloadError(slot, message);
                logDownloadProblem(slot, downloadId, info.localUri, info.status, info.reason);
                clearSlotDownload(slot);
                return SlotState.error(message);
            }
            if (info.status == DownloadManager.STATUS_PAUSED) {
                return SlotState.downloading("Загрузка приостановлена", downloadReasonToMessage(info.reason));
            }
            return SlotState.downloading(progressText(info), null);
        }

        if (hasPendingApk(slot)) {
            if (installedCloneInfo != null && isInstalledCloneCurrent(slot, installedCloneInfo)) {
                clearPendingCloneInstall(slot);
                return installedSlotState(slot, installedCloneInfo);
            }
            return SlotState.waitingInstall();
        }

        if (installedCloneInfo != null) {
            return installedSlotState(slot, installedCloneInfo);
        }

        String error = preferences.getString(slotErrorKey(slot), null);
        if (error != null) {
            return SlotState.error(error);
        }

        return SlotState.notInstalled();
    }

    private boolean isInstalledCloneCurrent(int slot, InstalledCloneInfo installedCloneInfo) {
        CloneInfo cloneInfo = cloneCatalog.get(slot);
        return cloneInfo == null
                || cloneInfo.versionCode == null
                || installedCloneInfo.versionCode >= cloneInfo.versionCode;
    }

    private String progressText(DownloadInfo info) {
        if (info.totalBytes > 0 && info.downloadedBytes >= 0) {
            int percent = (int) Math.min(100, Math.max(0, (info.downloadedBytes * 100L) / info.totalBytes));
            return "Скачивание APK... " + percent + "%";
        }
        return "Скачивание APK...";
    }

    private void addSlot() {
        int hiddenSlot = firstHiddenSlot();
        if (hiddenSlot > 0) {
            preferences.edit()
                    .remove(slotHiddenKey(hiddenSlot))
                    .putInt(KEY_SLOT_COUNT, Math.max(visibleSlotCount, hiddenSlot))
                    .apply();
            Log.d(TAG, "hidden slot restored: slot=" + hiddenSlot);
            renderSlots();
            showSlotAddedFilterHintIfNeeded(hiddenSlot);
            return;
        }

        if (visibleSlotCount >= MAX_CLONE_APKS) {
            new AlertDialog.Builder(this)
                    .setTitle("Слоты закончились")
                    .setMessage("Сейчас доступно максимум " + MAX_CLONE_APKS + " клонов.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        preferences.edit().putInt(KEY_SLOT_COUNT, visibleSlotCount + 1).apply();
        renderSlots();
        showSlotAddedFilterHintIfNeeded(visibleSlotCount);
    }

    private void showSlotAddedFilterHintIfNeeded(int slot) {
        SlotState state = getSlotState(slot);
        if (!matchesSlotFilters(slot, state)) {
            Toast.makeText(this, "Слот добавлен. Измените фильтр, чтобы увидеть его.", Toast.LENGTH_SHORT).show();
        }
    }

    private int firstHiddenSlot() {
        for (int slot = 1; slot <= MAX_CLONE_APKS; slot++) {
            if (isSlotHidden(slot) && !isAppInstalled(packageName(slot))) {
                return slot;
            }
        }
        return 0;
    }

    private int highestRequiredSlot() {
        int highest = 0;
        for (int slot = 1; slot <= MAX_CLONE_APKS; slot++) {
            if (isSlotRequired(slot)) {
                highest = slot;
            }
        }
        return highest;
    }

    private int nextRequiredSlotCount() {
        int highest = highestRequiredSlot();
        if (highest <= 0) {
            return 1;
        }
        return Math.min(MAX_CLONE_APKS, highest + 1);
    }

    private void ensureSlotVisibilityInitialized() {
        if (preferences.getBoolean(KEY_SLOT_VISIBILITY_INITIALIZED, false)) {
            return;
        }

        int initialSlotCount = nextRequiredSlotCount();
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KEY_SLOT_VISIBILITY_INITIALIZED, true)
                .putInt(KEY_SLOT_COUNT, initialSlotCount);

        if (highestRequiredSlot() > 0) {
            for (int slot = 1; slot < initialSlotCount; slot++) {
                if (!isSlotRequired(slot)) {
                    editor.putBoolean(slotHiddenKey(slot), true);
                }
            }
        }

        editor.apply();
        Log.d(TAG, "slot visibility initialized: visibleSlotCount=" + initialSlotCount);
    }

    private boolean isSlotRequired(int slot) {
        return isAppInstalled(packageName(slot))
                || hasPendingApk(slot)
                || preferences.getLong(slotDownloadKey(slot), -1L) > 0
                || preferences.getString(slotErrorKey(slot), null) != null;
    }

    private boolean isSlotHidden(int slot) {
        return preferences.getBoolean(slotHiddenKey(slot), false);
    }

    private void hideSlot(int slot) {
        if (isAppInstalled(packageName(slot))) {
            renderSlots();
            return;
        }

        preferences.edit().putBoolean(slotHiddenKey(slot), true).apply();
        Log.d(TAG, "slot hidden: slot=" + slot);
        renderSlots();
    }

    private void logSlotVisibilityStateOnce() {
        if (slotVisibilityLogged) {
            return;
        }
        slotVisibilityLogged = true;
        Log.d(TAG, "slot visibility state loaded: visibleSlotCount=" + visibleSlotCount
                + ", hiddenSlots=" + hiddenSlotsForLog());
    }

    private String hiddenSlotsForLog() {
        StringBuilder builder = new StringBuilder();
        for (int slot = 1; slot <= MAX_CLONE_APKS; slot++) {
            if (isSlotHidden(slot)) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(slot);
            }
        }
        return builder.length() == 0 ? "none" : builder.toString();
    }

    private void showRenameDialog(int slot) {
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setText(slotName(slot));
        editText.setSelection(editText.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Переименовать слот")
                .setView(editText)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String newName = editText.getText().toString().trim();
                    SharedPreferences.Editor editor = preferences.edit();
                    if (newName.isEmpty()) {
                        editor.remove(slotNameKey(slot));
                    } else {
                        editor.putString(slotNameKey(slot), newName);
                    }
                    editor.apply();
                    renderSlots();
                })
                .setNeutralButton("Сбросить", (dialog, which) -> {
                    preferences.edit().remove(slotNameKey(slot)).apply();
                    renderSlots();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void openSlot(int slot) {
        String packageName = packageName(slot);
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this, "Слот " + slotName(slot) + " не установлен", Toast.LENGTH_SHORT).show();
            renderSlots();
        }
    }

    private void openAppSettings(int slot) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + packageName(slot)));
        startActivity(intent);
    }

    private void requestUninstallClone(int slot) {
        String packageName = packageName(slot);
        if (!isAppInstalled(packageName)) {
            Log.d(TAG, "clone already not installed: slot=" + slot + ", packageName=" + packageName);
            Toast.makeText(this, "Клон уже не установлен", Toast.LENGTH_SHORT).show();
            renderSlots();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Удалить клон?")
                .setMessage("Откроются настройки " + cloneName(slot) + ". На системном экране нажмите «Удалить».")
                .setPositiveButton("Открыть настройки", (dialog, which) -> openCloneSettingsForUninstall(slot, packageName))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void openCloneSettingsForUninstall(int slot, String packageName) {
        Log.d(TAG, "uninstall settings requested: slot=" + slot + ", packageName=" + packageName);
        try {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            settings.setData(Uri.parse("package:" + packageName));
            startActivity(settings);
            Log.d(TAG, "uninstall settings opened: slot=" + slot + ", packageName=" + packageName);
            Toast.makeText(this, "На экране настроек нажмите «Удалить»", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.w(TAG, "uninstall settings failed: slot=" + slot + ", packageName=" + packageName, e);
            Toast.makeText(this, "Не удалось открыть настройки клона", Toast.LENGTH_SHORT).show();
        }
    }

    private void showInstallDialog(int slot) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нужно разрешение Android")
                    .setMessage("Чтобы установить клон, разрешите TGSpaces установку APK из этого источника.")
                    .setPositiveButton("Открыть настройки", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }

        long activeDownload = preferences.getLong(slotDownloadKey(slot), -1L);
        if (activeDownload > 0 && isDownloadActive(activeDownload)) {
            Toast.makeText(this, "APK для этого слота уже скачивается", Toast.LENGTH_SHORT).show();
            renderSlots();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Установка " + cloneName(slot))
                .setMessage("TGSpaces скачает APK этого клона. После скачивания откроется системный установщик Android. Без подтверждения в Android клон не установится.")
                .setPositiveButton("Скачать и установить", (dialog, which) -> downloadCloneApk(slot))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void loadAppCatalog() {
        new Thread(() -> {
            try {
                AppUpdateInfo loadedAppUpdateInfo = parseAppUpdateInfo(downloadText(APP_CATALOG_URL));
                runOnUiThread(() -> {
                    appUpdateInfo = loadedAppUpdateInfo;
                    renderAppUpdateBanner();
                });
            } catch (Exception e) {
                Log.w(TAG, "TGSpaces app catalog load failed, app update check skipped", e);
            }
        }, "TGSpacesAppCatalogLoader").start();
    }

    private String downloadText(String urlText) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException("HTTP " + responseCode);
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            return body.toString();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private AppUpdateInfo parseAppUpdateInfo(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String packageName = requiredString(root, "packageName");
        if (!getPackageName().equals(packageName)) {
            throw new IllegalArgumentException("Unexpected TGSpaces packageName: " + packageName);
        }

        return new AppUpdateInfo(
                requiredString(root, "appName"),
                packageName,
                requiredString(root, "releaseTag"),
                requiredString(root, "apkFileName"),
                requiredString(root, "apkUrl"),
                requiredString(root, "versionName"),
                root.getLong("versionCode"),
                optionalString(root, "sha256")
        );
    }

    private void renderAppUpdateBanner() {
        if (appUpdateBanner == null) {
            return;
        }

        InstalledCloneInfo installedAppInfo = getInstalledAppInfo();
        if (installedAppInfo == null || appUpdateInfo == null) {
            appUpdateBanner.setVisibility(View.GONE);
            return;
        }

        Log.d(TAG, "Installed TGSpaces version: versionCode=" + installedAppInfo.versionCode
                + ", versionName=" + installedAppInfo.versionName);
        Log.d(TAG, "Remote TGSpaces version: versionCode=" + appUpdateInfo.versionCode
                + ", versionName=" + appUpdateInfo.versionName);

        if (appUpdateInfo.versionCode > installedAppInfo.versionCode) {
            Log.d(TAG, "TGSpaces update available: installedVersionCode=" + installedAppInfo.versionCode
                    + ", remoteVersionCode=" + appUpdateInfo.versionCode);
            appUpdateText.setText("Текущая версия: " + versionText(installedAppInfo.versionName)
                    + "\nНовая версия: " + versionText(appUpdateInfo.versionName));
            updateAppUpdateButtonState();
            appUpdateBanner.setVisibility(View.VISIBLE);
        } else {
            Log.d(TAG, "No TGSpaces update available");
            appUpdateBanner.setVisibility(View.GONE);
        }
    }

    private void updateAppUpdateButtonState() {
        if (appUpdateButton == null) {
            return;
        }

        long activeDownload = preferences.getLong(KEY_APP_UPDATE_DOWNLOAD_ID, -1L);
        if (activeDownload > 0 && isDownloadActive(activeDownload)) {
            appUpdateButton.setEnabled(false);
            appUpdateButton.setText("Скачивается...");
        } else {
            appUpdateButton.setEnabled(true);
            appUpdateButton.setText("Скачать обновление");
        }
    }

    private void downloadAppUpdate() {
        if (appUpdateInfo == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нужно разрешение Android")
                    .setMessage("Чтобы установить обновление TGSpaces, разрешите установку APK из этого источника.")
                    .setPositiveButton("Открыть настройки", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }

        long activeDownload = preferences.getLong(KEY_APP_UPDATE_DOWNLOAD_ID, -1L);
        if (activeDownload > 0 && isDownloadActive(activeDownload)) {
            Log.d(TAG, "tgspaces update download already running");
            updateAppUpdateButtonState();
            Toast.makeText(this, "Обновление TGSpaces уже скачивается", Toast.LENGTH_SHORT).show();
            return;
        }

        if (preferences.getBoolean(KEY_APP_UPDATE_DOWNLOAD_FAILED, false)) {
            Log.d(TAG, "tgspaces update retry");
        }

        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            Toast.makeText(this, "Недоступна папка загрузок приложения", Toast.LENGTH_SHORT).show();
            return;
        }

        File target = new File(downloadsDir, appUpdateInfo.apkFileName);
        if (target.exists() && !target.delete()) {
            Toast.makeText(this, "Не удалось заменить старый APK обновления", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "Downloading TGSpaces update APK: url=" + appUpdateInfo.apkUrl);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(appUpdateInfo.apkUrl));
        request.setTitle(appUpdateInfo.apkFileName);
        request.setDescription("Скачивание обновления TGSpaces");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, appUpdateInfo.apkFileName);

        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        long downloadId = manager.enqueue(request);
        preferences.edit()
                .putLong(KEY_APP_UPDATE_DOWNLOAD_ID, downloadId)
                .remove(KEY_APP_UPDATE_APK_PATH)
                .remove(KEY_APP_UPDATE_DOWNLOAD_FAILED)
                .apply();
        updateAppUpdateButtonState();
        Toast.makeText(this, "Скачивание обновления началось", Toast.LENGTH_SHORT).show();
    }

    private void downloadCloneApk(int slot) {
        long activeDownload = preferences.getLong(slotDownloadKey(slot), -1L);
        if (activeDownload > 0 && isDownloadActive(activeDownload)) {
            Toast.makeText(this, "APK для этого слота уже скачивается", Toast.LENGTH_SHORT).show();
            renderSlots();
            return;
        }

        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            showDownloadError(slot, "Недоступна папка загрузок приложения");
            return;
        }

        String fileName = apkFileName(slot);
        File target = new File(downloadsDir, fileName);
        if (target.exists() && !target.delete()) {
            showDownloadError(slot, "Не удалось заменить старый APK");
            return;
        }

        String url = apkUrl(slot);
        Log.d(TAG, "Downloading clone APK: slot=" + slot + ", url=" + url);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(fileName);
        request.setDescription("Скачивание " + cloneName(slot));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        long downloadId = manager.enqueue(request);
        downloadSlots.put(downloadId, slot);
        preferences.edit()
                .putLong(slotDownloadKey(slot), downloadId)
                .putInt(downloadSlotKey(downloadId), slot)
                .remove(KEY_PENDING_APK)
                .remove(KEY_PENDING_SLOT)
                .remove(KEY_PENDING_DOWNLOAD_ID)
                .remove(slotErrorKey(slot))
                .remove(slotAutoOpenedKey(slot))
                .apply();
        Toast.makeText(this, "Скачивание началось", Toast.LENGTH_SHORT).show();
        renderSlots();
    }

    private void cancelCloneDownload(int slot) {
        long downloadId = preferences.getLong(slotDownloadKey(slot), -1L);
        if (downloadId > 0) {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            manager.remove(downloadId);
            downloadSlots.remove(downloadId);
        }

        clearSlotDownload(slot);
        Log.d(TAG, "clone download canceled: slot=" + slot + ", downloadId=" + downloadId);
        Toast.makeText(this, "Загрузка отменена", Toast.LENGTH_SHORT).show();
        renderSlots();
    }

    private void retryCloneDownload(int slot) {
        Log.d(TAG, "clone download retry: slot=" + slot);
        preferences.edit().remove(slotErrorKey(slot)).apply();
        downloadCloneApk(slot);
    }

    private void restartCloneDownload(int slot) {
        long downloadId = getKnownDownloadId(slot);
        if (downloadId > 0) {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            manager.remove(downloadId);
            downloadSlots.remove(downloadId);
        }

        String pendingPath = preferences.getString(KEY_PENDING_APK, null);
        if (preferences.getInt(KEY_PENDING_SLOT, 0) == slot && pendingPath != null) {
            File pendingFile = new File(pendingPath);
            if (pendingFile.exists() && !pendingFile.delete()) {
                Log.w(TAG, "Could not delete pending clone APK before restart: slot=" + slot + ", path=" + pendingPath);
            }
        }

        clearPendingCloneInstall(slot);
        clearSlotDownload(slot);
        Log.d(TAG, "clone download restarted: slot=" + slot + ", previousDownloadId=" + downloadId);
        downloadCloneApk(slot);
    }

    private void handleDownloadedApk(long downloadId, int slot, boolean autoOpen) {
        DownloadInfo info = queryDownload(downloadId);
        if (info == null) {
            String message = "Не удалось проверить загрузку APK";
            logDownloadProblem(slot, downloadId, null, 0, 0);
            showDownloadError(slot, message);
            clearSlotDownload(slot);
            return;
        }

        if (info.status != DownloadManager.STATUS_SUCCESSFUL) {
            String message = downloadReasonToMessage(info.reason);
            logDownloadProblem(slot, downloadId, info.localUri, info.status, info.reason);
            showDownloadError(slot, message);
            clearSlotDownload(slot);
            return;
        }

        if (!verifyDownloadedCloneApk(slot, info)) {
            showDownloadError(slot, apkSecurityCheckErrorMessage());
            clearSlotDownload(slot);
            return;
        }

        markDownloadReadyForInstall(slot, downloadId, info);
        renderSlots();
        if (autoOpen) {
            openInstallerOnce(slot, downloadId);
        }
    }

    private void checkDownloadsForTerminalStates(boolean autoOpen) {
        for (int slot = 1; slot <= MAX_CLONE_APKS; slot++) {
            long downloadId = preferences.getLong(slotDownloadKey(slot), -1L);
            if (downloadId <= 0) {
                continue;
            }
            DownloadInfo info = queryDownload(downloadId);
            if (info == null) {
                continue;
            }
            if (info.status == DownloadManager.STATUS_SUCCESSFUL) {
                if (!verifyDownloadedCloneApk(slot, info)) {
                    storeDownloadError(slot, apkSecurityCheckErrorMessage());
                    clearSlotDownload(slot);
                    continue;
                }
                markDownloadReadyForInstall(slot, downloadId, info);
                if (autoOpen) {
                    openInstallerOnce(slot, downloadId);
                }
            } else if (info.status == DownloadManager.STATUS_FAILED) {
                String message = downloadReasonToMessage(info.reason);
                storeDownloadError(slot, message);
                logDownloadProblem(slot, downloadId, info.localUri, info.status, info.reason);
                clearSlotDownload(slot);
            }
        }
    }

    private void checkAppUpdateDownload(boolean autoOpen) {
        long downloadId = preferences.getLong(KEY_APP_UPDATE_DOWNLOAD_ID, -1L);
        if (downloadId <= 0) {
            return;
        }

        DownloadInfo info = queryDownload(downloadId);
        if (info == null) {
            return;
        }

        if (info.status == DownloadManager.STATUS_SUCCESSFUL) {
            handleDownloadedAppUpdate(downloadId, autoOpen);
        } else if (info.status == DownloadManager.STATUS_FAILED) {
            Log.w(TAG, "TGSpaces update download failed: downloadId=" + downloadId
                    + ", reason=" + info.reason
                    + " (" + downloadReasonToMessage(info.reason) + ")");
            preferences.edit()
                    .remove(KEY_APP_UPDATE_DOWNLOAD_ID)
                    .remove(KEY_APP_UPDATE_APK_PATH)
                    .putBoolean(KEY_APP_UPDATE_DOWNLOAD_FAILED, true)
                    .apply();
            renderAppUpdateBanner();
        }
    }

    private void handleDownloadedAppUpdate(long downloadId, boolean autoOpen) {
        DownloadInfo info = queryDownload(downloadId);
        if (info == null) {
            Log.w(TAG, "Could not query TGSpaces update download: downloadId=" + downloadId);
            return;
        }

        if (info.status != DownloadManager.STATUS_SUCCESSFUL) {
            Log.w(TAG, "TGSpaces update download is not successful: downloadId=" + downloadId
                    + ", status=" + info.status
                    + ", reason=" + info.reason);
            preferences.edit()
                    .remove(KEY_APP_UPDATE_DOWNLOAD_ID)
                    .remove(KEY_APP_UPDATE_APK_PATH)
                    .putBoolean(KEY_APP_UPDATE_DOWNLOAD_FAILED, true)
                    .apply();
            renderAppUpdateBanner();
            return;
        }

        File apkFile = resolveDownloadedAppUpdateFile(info);
        if (apkFile == null || !apkFile.exists()) {
            Log.w(TAG, "TGSpaces update APK was downloaded but file was not found");
            preferences.edit()
                    .remove(KEY_APP_UPDATE_DOWNLOAD_ID)
                    .remove(KEY_APP_UPDATE_APK_PATH)
                    .putBoolean(KEY_APP_UPDATE_DOWNLOAD_FAILED, true)
                    .apply();
            renderAppUpdateBanner();
            return;
        }

        Uri hashUri = downloadInfoUri(info);
        if (hashUri == null) {
            hashUri = Uri.fromFile(apkFile);
        }
        if (!verifyApkSha256("TGSpaces update", appUpdateInfo != null ? appUpdateInfo.sha256 : null, hashUri, null)) {
            preferences.edit()
                    .remove(KEY_APP_UPDATE_DOWNLOAD_ID)
                    .remove(KEY_APP_UPDATE_APK_PATH)
                    .putBoolean(KEY_APP_UPDATE_DOWNLOAD_FAILED, true)
                    .apply();
            renderAppUpdateBanner();
            new AlertDialog.Builder(this)
                    .setTitle("Ошибка загрузки")
                    .setMessage("SHA-256 скачанного обновления TGSpaces не совпадает с каталогом. Установщик не будет открыт.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        preferences.edit()
                .putString(KEY_APP_UPDATE_APK_PATH, apkFile.getAbsolutePath())
                .remove(KEY_APP_UPDATE_DOWNLOAD_ID)
                .remove(KEY_APP_UPDATE_DOWNLOAD_FAILED)
                .apply();
        renderAppUpdateBanner();

        if (autoOpen) {
            openAppUpdateInstaller(apkFile);
        }
    }

    private File resolveDownloadedAppUpdateFile(DownloadInfo info) {
        if (info != null && info.localUri != null) {
            Uri localUri = Uri.parse(info.localUri);
            if ("file".equals(localUri.getScheme()) && localUri.getPath() != null) {
                return new File(localUri.getPath());
            }
        }

        String savedPath = preferences.getString(KEY_APP_UPDATE_APK_PATH, null);
        if (savedPath != null) {
            File savedFile = new File(savedPath);
            if (savedFile.exists()) {
                return savedFile;
            }
        }

        if (appUpdateInfo != null) {
            File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (downloadsDir != null) {
                return new File(downloadsDir, appUpdateInfo.apkFileName);
            }
        }

        return null;
    }

    private void openAppUpdateInstaller(File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not open TGSpaces update installer, path=" + apkFile.getAbsolutePath(), e);
            new AlertDialog.Builder(this)
                    .setTitle("Не удалось открыть установщик")
                    .setMessage("Обновление TGSpaces скачано, но приложение не смогло открыть системный установщик. Откройте APK вручную из файлов приложения.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void markDownloadReadyForInstall(int slot, long downloadId, DownloadInfo info) {
        String path = resolveDownloadedApkPath(slot, info);
        preferences.edit()
                .putString(KEY_PENDING_APK, path)
                .putInt(KEY_PENDING_SLOT, slot)
                .putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
                .remove(slotDownloadKey(slot))
                .remove(slotErrorKey(slot))
                .apply();
    }

    private void openInstallerOnce(int slot, long downloadId) {
        if (preferences.getBoolean(slotAutoOpenedKey(slot), false)) {
            return;
        }
        preferences.edit().putBoolean(slotAutoOpenedKey(slot), true).apply();
        openInstallerForDownloadedApk(slot, downloadId);
    }

    private boolean openInstallerForDownloadedApk(int slot, long downloadId) {
        Uri uri = null;
        String pathForLog = preferences.getString(KEY_PENDING_APK, null);
        try {
            uri = resolveInstallerUri(slot, downloadId);
            if (uri == null) {
                throw new IllegalStateException("No APK URI available");
            }

            if (!verifyCloneInstallerUri(slot, uri)) {
                showDownloadError(slot, apkSecurityCheckErrorMessage());
                return false;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not open installer"
                    + ", slot=" + slot
                    + ", downloadId=" + downloadId
                    + ", uri=" + uri
                    + ", path=" + pathForLog, e);
            new AlertDialog.Builder(this)
                    .setTitle("Не удалось открыть установщик")
                    .setMessage("APK скачан, но TGSpaces не смог открыть установщик. Откройте файл из загрузок вручную или повторите попытку.")
                    .setPositiveButton("OK", null)
                    .show();
            return false;
        }
    }

    private Uri resolveInstallerUri(int slot, long downloadId) {
        String pendingPath = preferences.getString(KEY_PENDING_APK, null);
        if (pendingPath != null) {
            File file = new File(pendingPath);
            if (file.exists()) {
                return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            }
        }

        if (downloadId > 0) {
            DownloadInfo info = queryDownload(downloadId);
            if (info != null && info.localUri != null) {
                Uri localUri = Uri.parse(info.localUri);
                if ("file".equals(localUri.getScheme())) {
                    File file = new File(localUri.getPath());
                    if (file.exists()) {
                        preferences.edit().putString(KEY_PENDING_APK, file.getAbsolutePath()).apply();
                        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                    }
                } else if ("content".equals(localUri.getScheme())) {
                    return localUri;
                }
            }
        }

        File fallback = expectedApkFile(slot);
        if (fallback.exists()) {
            preferences.edit().putString(KEY_PENDING_APK, fallback.getAbsolutePath()).apply();
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", fallback);
        }

        return null;
    }

    private String resolveDownloadedApkPath(int slot, DownloadInfo info) {
        if (info != null && info.localUri != null) {
            Uri localUri = Uri.parse(info.localUri);
            if ("file".equals(localUri.getScheme()) && localUri.getPath() != null) {
                return localUri.getPath();
            }
        }
        return expectedApkFile(slot).getAbsolutePath();
    }

    private boolean hasPendingApk(int slot) {
        int pendingSlot = preferences.getInt(KEY_PENDING_SLOT, 0);
        String pendingPath = preferences.getString(KEY_PENDING_APK, null);
        if (pendingSlot != slot || pendingPath == null) {
            return false;
        }
        return new File(pendingPath).exists();
    }

    private long getKnownDownloadId(int slot) {
        long pendingDownloadId = preferences.getLong(KEY_PENDING_DOWNLOAD_ID, -1L);
        if (preferences.getInt(KEY_PENDING_SLOT, 0) == slot && pendingDownloadId > 0) {
            return pendingDownloadId;
        }
        return preferences.getLong(slotDownloadKey(slot), -1L);
    }

    private DownloadInfo queryDownload(long downloadId) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            String localUri = null;
            int localUriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            if (localUriColumn >= 0) {
                localUri = cursor.getString(localUriColumn);
            }
            return new DownloadInfo(status, reason, downloaded, total, localUri);
        }
    }

    private boolean isDownloadActive(long downloadId) {
        DownloadInfo info = queryDownload(downloadId);
        if (info == null) {
            return false;
        }
        return info.status == DownloadManager.STATUS_PENDING
                || info.status == DownloadManager.STATUS_RUNNING
                || info.status == DownloadManager.STATUS_PAUSED;
    }

    private boolean hasActiveDownloads() {
        for (int slot = 1; slot <= visibleSlotCount; slot++) {
            long downloadId = preferences.getLong(slotDownloadKey(slot), -1L);
            if (downloadId > 0 && isDownloadActive(downloadId)) {
                return true;
            }
        }
        return false;
    }

    private void scheduleProgressRefreshIfNeeded() {
        progressHandler.removeCallbacks(progressRunnable);
        if (hasActiveDownloads()) {
            progressHandler.postDelayed(progressRunnable, PROGRESS_REFRESH_MS);
        }
    }

    private void showDownloadError(int slot, String message) {
        storeDownloadError(slot, message);
        renderSlots();
        new AlertDialog.Builder(this)
                .setTitle("Ошибка загрузки")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String apkSecurityCheckErrorMessage() {
        return "APK не прошёл проверку безопасности. Файл повреждён или не совпадает с каталогом. Установка остановлена.";
    }

    private void storeDownloadError(int slot, String message) {
        preferences.edit().putString(slotErrorKey(slot), message).apply();
    }

    private void clearSlotDownload(int slot) {
        long downloadId = preferences.getLong(slotDownloadKey(slot), -1L);
        SharedPreferences.Editor editor = preferences.edit().remove(slotDownloadKey(slot));
        if (downloadId > 0) {
            editor.remove(downloadSlotKey(downloadId));
            downloadSlots.remove(downloadId);
        }
        editor.apply();
    }

    private void clearPendingCloneInstall(int slot) {
        if (preferences.getInt(KEY_PENDING_SLOT, 0) != slot) {
            return;
        }

        preferences.edit()
                .remove(KEY_PENDING_APK)
                .remove(KEY_PENDING_SLOT)
                .remove(KEY_PENDING_DOWNLOAD_ID)
                .remove(slotAutoOpenedKey(slot))
                .apply();
    }

    private void logDownloadProblem(int slot, long downloadId, String uriOrPath, int status, int reason) {
        Log.e(TAG, "Download failed"
                + ", slot=" + slot
                + ", downloadId=" + downloadId
                + ", url=" + apkUrl(slot)
                + ", uriOrPath=" + uriOrPath
                + ", status=" + status
                + ", reason=" + reason
                + " (" + downloadReasonToMessage(reason) + ")");
    }

    private boolean verifyDownloadedCloneApk(int slot, DownloadInfo info) {
        Uri uri = downloadInfoUri(info);
        if (uri == null) {
            File fallback = expectedApkFile(slot);
            if (fallback.exists()) {
                uri = Uri.fromFile(fallback);
            }
        }
        return verifyApkSha256("slot=" + slot, expectedCloneSha256(slot), uri, slot);
    }

    private boolean verifyCloneInstallerUri(int slot, Uri uri) {
        return verifyApkSha256("slot=" + slot, expectedCloneSha256(slot), uri, slot);
    }

    private String expectedCloneSha256(int slot) {
        CloneInfo cloneInfo = cloneCatalog.get(slot);
        return cloneInfo != null ? cloneInfo.sha256 : null;
    }

    private boolean verifyApkSha256(String label, String expectedSha256, Uri uri, Integer slot) {
        if (expectedSha256 == null || expectedSha256.trim().isEmpty()) {
            Log.w(TAG, "APK SHA-256 missing, skipping verification: " + label);
            return true;
        }
        if (uri == null) {
            Log.w(TAG, "APK SHA-256 mismatch: " + label + ", expected=" + expectedSha256 + ", actual=missing-uri");
            return false;
        }

        try {
            Log.d(TAG, "Verifying APK SHA-256... " + label);
            String actualSha256 = calculateSha256(uri);
            if (expectedSha256.equalsIgnoreCase(actualSha256)) {
                Log.d(TAG, "APK SHA-256 verified: " + label);
                return true;
            }

            String slotText = slot != null ? ", slot=" + slot : "";
            Log.w(TAG, "APK SHA-256 mismatch: " + label + slotText
                    + ", expected=" + expectedSha256
                    + ", actual=" + actualSha256);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "APK SHA-256 mismatch: " + label + ", expected=" + expectedSha256 + ", actual=read-error", e);
            return false;
        }
    }

    private String calculateSha256(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream inputStream = openUriForHash(uri)) {
            if (inputStream == null) {
                throw new IllegalStateException("Could not open APK uri for hashing: " + uri);
            }

            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return bytesToHex(digest.digest());
    }

    private InputStream openUriForHash(Uri uri) throws Exception {
        if ("file".equals(uri.getScheme())) {
            return new FileInputStream(new File(uri.getPath()));
        }
        if ("content".equals(uri.getScheme())) {
            return getContentResolver().openInputStream(uri);
        }
        if (uri.getScheme() == null && uri.getPath() != null) {
            return new FileInputStream(new File(uri.getPath()));
        }
        return getContentResolver().openInputStream(uri);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private Uri downloadInfoUri(DownloadInfo info) {
        if (info != null && info.localUri != null) {
            return Uri.parse(info.localUri);
        }
        return null;
    }

    private void loadCloneCatalog() {
        new Thread(() -> {
            try {
                Map<Integer, CloneInfo> loadedCatalog = parseCloneCatalog(downloadText(CATALOG_URL));
                runOnUiThread(() -> {
                    cloneCatalog.clear();
                    cloneCatalog.putAll(loadedCatalog);
                    Log.d(TAG, "Clone catalog loaded: " + cloneCatalog.size() + " clones");
                    renderSlots();
                });
            } catch (Exception e) {
                Log.w(TAG, "Clone catalog load failed, using fallback clone URLs", e);
            }
        }, "TGSpacesCatalogLoader").start();
    }

    private Map<Integer, CloneInfo> parseCloneCatalog(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray clones = root.getJSONArray("clones");
        Map<Integer, CloneInfo> parsedCatalog = new HashMap<>();

        for (int i = 0; i < clones.length(); i++) {
            JSONObject item = clones.getJSONObject(i);
            int slot = item.getInt("slot");
            if (slot < 1 || slot > MAX_CLONE_APKS) {
                continue;
            }

            CloneInfo cloneInfo = new CloneInfo(
                    slot,
                    requiredString(item, "name"),
                    requiredString(item, "packageName"),
                    requiredString(item, "apkFileName"),
                    requiredString(item, "apkUrl"),
                    item.optString("versionName", ""),
                    item.has("versionCode") && !item.isNull("versionCode") ? item.getLong("versionCode") : null,
                    optionalString(item, "sha256")
            );
            parsedCatalog.put(slot, cloneInfo);
        }

        if (parsedCatalog.isEmpty()) {
            throw new IllegalArgumentException("Catalog has no usable clones");
        }
        return parsedCatalog;
    }

    private static String requiredString(JSONObject object, String name) throws Exception {
        String value = object.getString(name).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private static String optionalString(JSONObject object, String name) {
        if (!object.has(name) || object.isNull(name)) {
            return null;
        }
        String value = object.optString(name, "").trim();
        return value.isEmpty() ? null : value;
    }

    private String downloadReasonToMessage(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "Загрузка не может быть продолжена";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Устройство хранения недоступно";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "Файл APK уже существует";
            case DownloadManager.ERROR_FILE_ERROR:
                return "Ошибка файла при сохранении APK";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "Ошибка данных при загрузке APK";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Недостаточно места для APK";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "Слишком много перенаправлений при загрузке";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "APK недоступен в GitHub Release. Возможно, файл ещё не загружен или ссылка устарела.";
            case DownloadManager.ERROR_UNKNOWN:
                return "Неизвестная ошибка загрузки";
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return "Загрузка ждёт Wi-Fi";
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return "Загрузка ждёт подключение к интернету";
            case DownloadManager.PAUSED_WAITING_TO_RETRY:
                return "Загрузка временно приостановлена, Android попробует снова";
            case DownloadManager.PAUSED_UNKNOWN:
                return "Загрузка приостановлена по неизвестной причине";
            default:
                return "Неизвестная ошибка загрузки, код: " + reason;
        }
    }

    private boolean isAppInstalled(String packageName) {
        return getInstalledPackageInfo(packageName) != null;
    }

    private SlotState installedSlotState(int slot, InstalledCloneInfo installedCloneInfo) {
        CloneInfo cloneInfo = cloneCatalog.get(slot);
        Log.d(TAG, "Installed clone version: slot=" + slot
                + ", packageName=" + installedCloneInfo.packageName
                + ", versionCode=" + installedCloneInfo.versionCode
                + ", versionName=" + installedCloneInfo.versionName);

        if (cloneInfo == null || cloneInfo.versionCode == null) {
            Log.w(TAG, "Remote clone versionCode unavailable, update check skipped: slot=" + slot);
            return SlotState.installed(installedCloneInfo.versionName);
        }

        Log.d(TAG, "Remote clone version: slot=" + slot
                + ", packageName=" + cloneInfo.packageName
                + ", versionCode=" + cloneInfo.versionCode
                + ", versionName=" + cloneInfo.versionName);

        if (cloneInfo.versionCode > installedCloneInfo.versionCode) {
            Log.d(TAG, "Clone update available: slot=" + slot
                    + ", installedVersionCode=" + installedCloneInfo.versionCode
                    + ", remoteVersionCode=" + cloneInfo.versionCode);
            return SlotState.updateAvailable(installedCloneInfo.versionName, cloneInfo.versionName);
        }

        return SlotState.installed(installedCloneInfo.versionName);
    }

    private InstalledCloneInfo getInstalledCloneInfo(int slot) {
        String packageName = packageName(slot);
        PackageInfo packageInfo = getInstalledPackageInfo(packageName);
        if (packageInfo == null) {
            return null;
        }

        return installedInfoFromPackageInfo(packageName, packageInfo);
    }

    private InstalledCloneInfo getInstalledAppInfo() {
        PackageInfo packageInfo = getInstalledPackageInfo(getPackageName());
        if (packageInfo == null) {
            return null;
        }

        return installedInfoFromPackageInfo(getPackageName(), packageInfo);
    }

    private InstalledCloneInfo installedInfoFromPackageInfo(String packageName, PackageInfo packageInfo) {
        long versionCode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            versionCode = packageInfo.getLongVersionCode();
        } else {
            versionCode = packageInfo.versionCode;
        }
        String versionName = packageInfo.versionName != null ? packageInfo.versionName : "";
        return new InstalledCloneInfo(packageName, versionCode, versionName);
    }

    private static String versionText(String versionName) {
        return versionName == null || versionName.isEmpty() ? "неизвестно" : versionName;
    }

    private PackageInfo getInstalledPackageInfo(String packageName) {
        try {
            return getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String slotName(int slot) {
        return preferences.getString(slotNameKey(slot), cloneName(slot));
    }

    private File expectedApkFile(int slot) {
        return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkFileName(slot));
    }

    private static String slotNameKey(int slot) {
        return "slot_name_" + (slot - 1);
    }

    private static String defaultSlotName(int slot) {
        return String.format(Locale.US, "TGClone %02d", slot);
    }

    private String cloneName(int slot) {
        CloneInfo cloneInfo = cloneCatalog.get(slot);
        return cloneInfo != null ? cloneInfo.name : defaultSlotName(slot);
    }

    private String packageName(int slot) {
        CloneInfo cloneInfo = cloneCatalog.get(slot);
        if (cloneInfo != null) {
            return cloneInfo.packageName;
        }
        return String.format(Locale.US, "com.desperadoboi.tgclone%02d", slot);
    }

    private String apkFileName(int slot) {
        CloneInfo cloneInfo = cloneCatalog.get(slot);
        if (cloneInfo != null) {
            return cloneInfo.apkFileName;
        }
        return String.format(Locale.US, "TGClone%02d-release.apk", slot);
    }

    private String apkUrl(int slot) {
        CloneInfo cloneInfo = cloneCatalog.get(slot);
        if (cloneInfo != null) {
            return cloneInfo.apkUrl;
        }
        return RELEASE_BASE_URL + apkFileName(slot);
    }

    private static String downloadSlotKey(long downloadId) {
        return KEY_DOWNLOAD_SLOT_PREFIX + downloadId;
    }

    private static String slotDownloadKey(int slot) {
        return KEY_SLOT_DOWNLOAD_PREFIX + slot;
    }

    private static String slotErrorKey(int slot) {
        return KEY_SLOT_ERROR_PREFIX + slot;
    }

    private static String slotAutoOpenedKey(int slot) {
        return KEY_SLOT_AUTO_OPENED_PREFIX + slot;
    }

    private static String slotHiddenKey(int slot) {
        return KEY_SLOT_HIDDEN_PREFIX + slot;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private enum SlotStateType {
        UPDATE_AVAILABLE,
        INSTALLED,
        NOT_INSTALLED,
        DOWNLOADING,
        WAITING_INSTALL,
        DOWNLOAD_ERROR
    }

    private enum SlotFilter {
        ALL,
        INSTALLED,
        FREE,
        UPDATES
    }

    private enum DisplayMode {
        CARDS("cards", "Компактно"),
        COMPACT("compact", "Сетка"),
        GRID("grid", "Карточки");

        final String preferenceValue;
        final String buttonText;

        DisplayMode(String preferenceValue, String buttonText) {
            this.preferenceValue = preferenceValue;
            this.buttonText = buttonText;
        }

        DisplayMode next() {
            switch (this) {
                case CARDS:
                    return COMPACT;
                case COMPACT:
                    return GRID;
                case GRID:
                default:
                    return CARDS;
            }
        }

        static DisplayMode fromPreferenceValue(String value) {
            for (DisplayMode mode : values()) {
                if (mode.preferenceValue.equals(value)) {
                    return mode;
                }
            }
            return CARDS;
        }
    }

    private static class SlotState {
        final SlotStateType type;
        final String statusText;
        final String hintText;
        final int statusColorRes;

        SlotState(SlotStateType type, String statusText, String hintText, int statusColorRes) {
            this.type = type;
            this.statusText = statusText;
            this.hintText = hintText;
            this.statusColorRes = statusColorRes;
        }

        static SlotState installed(String versionName) {
            String hintText = versionName == null || versionName.isEmpty() ? null : "Версия: " + versionName;
            return new SlotState(SlotStateType.INSTALLED, "Установлено", hintText, R.color.status_success);
        }

        static SlotState updateAvailable(String installedVersionName, String remoteVersionName) {
            String installed = installedVersionName == null || installedVersionName.isEmpty()
                    ? "неизвестно"
                    : installedVersionName;
            String remote = remoteVersionName == null || remoteVersionName.isEmpty()
                    ? "неизвестно"
                    : remoteVersionName;
            return new SlotState(
                    SlotStateType.UPDATE_AVAILABLE,
                    "Доступно обновление",
                    "Установлена: " + installed + "\nНовая: " + remote,
                    R.color.status_update
            );
        }

        static SlotState notInstalled() {
            return new SlotState(SlotStateType.NOT_INSTALLED, "Не установлен", null, R.color.status_error);
        }

        static SlotState downloading(String statusText, String hintText) {
            return new SlotState(SlotStateType.DOWNLOADING, statusText, hintText, R.color.status_update);
        }

        static SlotState waitingInstall() {
            return new SlotState(
                    SlotStateType.WAITING_INSTALL,
                    "APK скачан, установка не завершена",
                    "Нажмите «Продолжить установку» и подтвердите установку в Android. Если вы отменили установку, APK уже скачан.",
                    R.color.status_info
            );
        }

        static SlotState error(String message) {
            return new SlotState(SlotStateType.DOWNLOAD_ERROR, "Ошибка загрузки", message, R.color.status_error);
        }
    }

    private static class DownloadInfo {
        final int status;
        final int reason;
        final long downloadedBytes;
        final long totalBytes;
        final String localUri;

        DownloadInfo(int status, int reason, long downloadedBytes, long totalBytes, String localUri) {
            this.status = status;
            this.reason = reason;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.localUri = localUri;
        }
    }

    private static class InstalledCloneInfo {
        final String packageName;
        final long versionCode;
        final String versionName;

        InstalledCloneInfo(String packageName, long versionCode, String versionName) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.versionName = versionName;
        }
    }

    private static class AppUpdateInfo {
        final String appName;
        final String packageName;
        final String releaseTag;
        final String apkFileName;
        final String apkUrl;
        final String versionName;
        final long versionCode;
        final String sha256;

        AppUpdateInfo(String appName, String packageName, String releaseTag, String apkFileName, String apkUrl, String versionName, long versionCode, String sha256) {
            this.appName = appName;
            this.packageName = packageName;
            this.releaseTag = releaseTag;
            this.apkFileName = apkFileName;
            this.apkUrl = apkUrl;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.sha256 = sha256;
        }
    }

    private static class CloneInfo {
        final int slot;
        final String name;
        final String packageName;
        final String apkFileName;
        final String apkUrl;
        final String versionName;
        final Long versionCode;
        final String sha256;

        CloneInfo(int slot, String name, String packageName, String apkFileName, String apkUrl, String versionName, Long versionCode, String sha256) {
            this.slot = slot;
            this.name = name;
            this.packageName = packageName;
            this.apkFileName = apkFileName;
            this.apkUrl = apkUrl;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.sha256 = sha256;
        }
    }
}
