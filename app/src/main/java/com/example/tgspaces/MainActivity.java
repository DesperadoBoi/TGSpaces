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
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
    private static final String KEY_SLOT_INSTALL_NOTICE_PREFIX = "slot_install_notice_";
    private static final String KEY_FIRST_LAUNCH_HELP_SHOWN = "first_launch_help_shown";
    private static final String KEY_APP_UPDATE_DOWNLOAD_ID = "app_update_download_id";
    private static final String KEY_APP_UPDATE_APK_PATH = "app_update_apk_path";
    private static final String KEY_APP_UPDATE_DOWNLOAD_FAILED = "app_update_download_failed";
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
    private Button manualUpdateButton;
    private int visibleSlotCount = 1;

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
        manualUpdateButton = findViewById(R.id.buttonRefresh);

        findViewById(R.id.buttonAddSlot).setOnClickListener(view -> addSlot());
        manualUpdateButton.setOnClickListener(view -> checkUpdatesManually());
        findViewById(R.id.buttonHelp).setOnClickListener(view -> showHelpDialog());
        appUpdateButton.setOnClickListener(view -> downloadAppUpdate());

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        checkDownloadsForTerminalStates(false);
        checkAppUpdateDownload(false);
        renderSlots();
        loadCloneCatalog();
        loadAppCatalog();
        showFirstLaunchDialogIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        int savedSlotCount = Math.max(1, preferences.getInt(KEY_SLOT_COUNT, 1));
        visibleSlotCount = Math.min(MAX_CLONE_APKS, Math.max(savedSlotCount, highestInstalledSlot()));
        if (visibleSlotCount != savedSlotCount) {
            preferences.edit().putInt(KEY_SLOT_COUNT, visibleSlotCount).apply();
        }

        slotsContainer.removeAllViews();
        for (int slot = 1; slot <= visibleSlotCount; slot++) {
            slotsContainer.addView(createSlotCard(slot));
        }
        scheduleProgressRefreshIfNeeded();
    }

    private View createSlotCard(int slot) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);

        SlotState state = getSlotState(slot);
        card.setBackgroundResource(
                state.type == SlotStateType.UPDATE_AVAILABLE
                        ? R.drawable.slot_card_update_background
                        : R.drawable.slot_card_background
        );

        TextView title = new TextView(this);
        title.setText(slotName(slot));
        title.setTextColor(Color.parseColor("#F8FAFC"));
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        TextView status = new TextView(this);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(4);
        status.setLayoutParams(statusParams);
        status.setText(state.statusText);
        status.setTextColor(state.statusColor);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(status);

        if (state.hintText != null) {
            TextView hint = new TextView(this);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            hintParams.topMargin = dp(6);
            hint.setLayoutParams(hintParams);
            hint.setText(state.hintText);
            hint.setTextColor(Color.parseColor("#CBD5E1"));
            hint.setTextSize(15);
            hint.setLineSpacing(dp(2), 1.0f);
            card.addView(hint);
        }

        switch (state.type) {
            case UPDATE_AVAILABLE:
                addButtonRow(card,
                        createButton("Обновить", true, true, view -> downloadCloneApk(slot)),
                        createButton("Открыть", false, true, view -> openSlot(slot)));
                addButtonRow(card,
                        createButton("Настройки", false, true, view -> openAppSettings(slot)),
                        createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
            case INSTALLED:
                addButtonRow(card,
                        createButton("Открыть", true, true, view -> openSlot(slot)),
                        createButton("Настройки", false, true, view -> openAppSettings(slot)));
                card.addView(createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
            case DOWNLOADING:
                card.addView(createButton("Скачивается...", true, false, null));
                addButtonRow(card,
                        createButton("Отменить", false, true, view -> cancelCloneDownload(slot)),
                        createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
            case WAITING_INSTALL:
                card.addView(createButton("Открыть установщик", true, true, view -> openInstallerForDownloadedApk(slot, getKnownDownloadId(slot))));
                addButtonRow(card,
                        createButton("Скачать заново", false, true, view -> restartCloneDownload(slot)),
                        createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
            case DOWNLOAD_ERROR:
                addButtonRow(card,
                        createButton("Повторить", true, true, view -> retryCloneDownload(slot)),
                        createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
            case NOT_INSTALLED:
            default:
                card.addView(createButton("Установить клон", true, true, view -> showInstallDialog(slot)));
                card.addView(createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
        }

        return card;
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

    private Button createButton(String text, boolean primary, boolean enabled, View.OnClickListener listener) {
        Button button = new Button(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        button.setText(text);
        button.setAllCaps(false);
        button.setEnabled(enabled);
        button.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setMinHeight(dp(44));
        button.setTextColor(Color.parseColor("#F8FAFC"));
        button.setBackgroundResource(primary ? R.drawable.button_primary_background : R.drawable.button_secondary_background);
        if (listener != null) {
            button.setOnClickListener(listener);
        }
        return button;
    }

    private void showFirstLaunchDialogIfNeeded() {
        if (preferences.getBoolean(KEY_FIRST_LAUNCH_HELP_SHOWN, false)) {
            return;
        }

        preferences.edit().putBoolean(KEY_FIRST_LAUNCH_HELP_SHOWN, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("TGSpaces")
                .setMessage("TGSpaces помогает устанавливать отдельные копии Telegram. Каждый слот — отдельное приложение и отдельный вход в аккаунт.")
                .setPositiveButton("Понятно", null)
                .setNegativeButton("Помощь", (dialog, which) -> showHelpDialog())
                .show();
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
                                + "2. Нажмите \"Установить клон\".\n"
                                + "3. Дождитесь загрузки APK.\n"
                                + "4. Подтвердите установку в системном установщике Android.\n\n"
                                + "Важно:\n"
                                + "- Для установки может потребоваться разрешение \"Установка неизвестных приложений\".\n"
                                + "- Если иконка в настройках Android отображается неправильно, откройте клон один раз или перезагрузите телефон.\n"
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
                return SlotState.downloading("Скачивается APK...", downloadReasonToMessage(info.reason));
            }
            return SlotState.downloading(progressText(info), null);
        }

        if (hasPendingApk(slot)) {
            if (installedCloneInfo != null && isInstalledCloneCurrent(slot, installedCloneInfo)) {
                showInstalledCloneNoticeOnce(slot);
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
            return "Скачивается APK... " + percent + "%";
        }
        return "Скачивается APK...";
    }

    private void addSlot() {
        int emptySlot = firstVisibleEmptySlot();
        if (emptySlot > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Пустой слот уже есть")
                    .setMessage("У вас уже есть пустой слот: " + cloneName(emptySlot) + ". Сначала установите клон в него.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        if (visibleSlotCount >= MAX_CLONE_APKS) {
            new AlertDialog.Builder(this)
                    .setTitle("Слоты закончились")
                    .setMessage("Сейчас доступно максимум " + MAX_CLONE_APKS + " clone APK.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        preferences.edit().putInt(KEY_SLOT_COUNT, visibleSlotCount + 1).apply();
        renderSlots();
    }

    private int firstVisibleEmptySlot() {
        for (int slot = 1; slot <= visibleSlotCount; slot++) {
            if (!isAppInstalled(packageName(slot))) {
                return slot;
            }
        }
        return 0;
    }

    private int highestInstalledSlot() {
        int highest = 0;
        for (int slot = 1; slot <= MAX_CLONE_APKS; slot++) {
            if (isAppInstalled(packageName(slot))) {
                highest = slot;
            }
        }
        return highest;
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
                .setMessage("TGSpaces скачает APK этого клона и откроет системный установщик Android. Подтвердите установку. Если Android попросит разрешить установку из TGSpaces - разрешите её в настройках.")
                .setPositiveButton("Скачать и установить", (dialog, which) -> downloadCloneApk(slot))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void checkUpdatesManually() {
        if (manualUpdateButton == null || !manualUpdateButton.isEnabled()) {
            return;
        }

        Log.d(TAG, "manual update check started");
        manualUpdateButton.setEnabled(false);
        manualUpdateButton.setText("Проверяем…");

        new Thread(() -> {
            try {
                Map<Integer, CloneInfo> loadedCloneCatalog = parseCloneCatalog(downloadText(CATALOG_URL));
                AppUpdateInfo loadedAppUpdateInfo = parseAppUpdateInfo(downloadText(APP_CATALOG_URL));

                runOnUiThread(() -> {
                    cloneCatalog.clear();
                    cloneCatalog.putAll(loadedCloneCatalog);
                    appUpdateInfo = loadedAppUpdateInfo;
                    checkDownloadsForTerminalStates(false);
                    renderSlots();
                    renderAppUpdateBanner();
                    resetManualUpdateButton();
                    Log.d(TAG, "manual update check finished");
                    Toast.makeText(this, "Обновления проверены", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.w(TAG, "manual update check failed", e);
                runOnUiThread(() -> {
                    checkDownloadsForTerminalStates(false);
                    renderSlots();
                    renderAppUpdateBanner();
                    resetManualUpdateButton();
                    Log.d(TAG, "manual update check finished");
                    Toast.makeText(this, "Не удалось проверить обновления, используется сохранённая информация", Toast.LENGTH_SHORT).show();
                });
            }
        }, "TGSpacesManualUpdateCheck").start();
    }

    private void resetManualUpdateButton() {
        if (manualUpdateButton == null) {
            return;
        }
        manualUpdateButton.setEnabled(true);
        manualUpdateButton.setText("Проверить обновления");
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
                .remove(slotInstallNoticeKey(slot))
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
            showDownloadError(slot, "Ошибка проверки SHA-256 APK");
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
                    storeDownloadError(slot, "Ошибка проверки SHA-256 APK");
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
                .remove(slotInstallNoticeKey(slot))
                .apply();
    }

    private void showInstalledCloneNoticeOnce(int slot) {
        String noticeKey = slotInstallNoticeKey(slot);
        if (preferences.getBoolean(noticeKey, false)) {
            return;
        }

        preferences.edit().putBoolean(noticeKey, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Клон установлен")
                .setMessage("Клон установлен. Если иконка в настройках Android отображается неправильно, откройте клон один раз или перезагрузите телефон.")
                .setPositiveButton("Открыть клон", (dialog, which) -> openSlot(slot))
                .setNegativeButton("ОК", null)
                .show();
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
                showDownloadError(slot, "Ошибка проверки SHA-256 APK");
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
                .remove(slotInstallNoticeKey(slot))
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
                return "GitHub вернул неожиданный HTTP-код. Проверьте, что APK загружен в Release";
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

    private static String slotInstallNoticeKey(int slot) {
        return KEY_SLOT_INSTALL_NOTICE_PREFIX + slot;
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

    private static class SlotState {
        final SlotStateType type;
        final String statusText;
        final String hintText;
        final int statusColor;

        SlotState(SlotStateType type, String statusText, String hintText, int statusColor) {
            this.type = type;
            this.statusText = statusText;
            this.hintText = hintText;
            this.statusColor = statusColor;
        }

        static SlotState installed(String versionName) {
            String hintText = versionName == null || versionName.isEmpty() ? null : "Версия: " + versionName;
            return new SlotState(SlotStateType.INSTALLED, "Установлено", hintText, Color.parseColor("#86EFAC"));
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
                    Color.parseColor("#FBBF24")
            );
        }

        static SlotState notInstalled() {
            return new SlotState(SlotStateType.NOT_INSTALLED, "Не установлен", null, Color.parseColor("#FCA5A5"));
        }

        static SlotState downloading(String statusText, String hintText) {
            return new SlotState(SlotStateType.DOWNLOADING, statusText, hintText, Color.parseColor("#FBBF24"));
        }

        static SlotState waitingInstall() {
            return new SlotState(
                    SlotStateType.WAITING_INSTALL,
                    "Ожидает установки",
                    "Подтвердите установку в системном окне Android",
                    Color.parseColor("#93C5FD")
            );
        }

        static SlotState error(String message) {
            return new SlotState(SlotStateType.DOWNLOAD_ERROR, "Ошибка загрузки", message, Color.parseColor("#FCA5A5"));
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
