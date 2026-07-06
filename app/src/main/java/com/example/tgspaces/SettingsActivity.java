package com.example.tgspaces;

import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "TGSpaces";
    private static final String PREFS_NAME = "slot_names";
    private static final String KEY_PENDING_APK = "pending_apk_path";
    private static final String KEY_PENDING_SLOT = "pending_slot";
    private static final String KEY_PENDING_DOWNLOAD_ID = "pending_download_id";
    private static final String KEY_DOWNLOAD_SLOT_PREFIX = "download_slot_";
    private static final String KEY_SLOT_DOWNLOAD_PREFIX = "slot_download_";
    private static final String KEY_SLOT_ERROR_PREFIX = "slot_error_";
    private static final String KEY_SLOT_AUTO_OPENED_PREFIX = "slot_auto_opened_";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_APP_UPDATE_DOWNLOAD_ID = "app_update_download_id";
    private static final String KEY_APP_UPDATE_APK_PATH = "app_update_apk_path";
    private static final String KEY_APP_UPDATE_DOWNLOAD_FAILED = "app_update_download_failed";
    private static final String KEY_SETTINGS_CATALOG_REFRESHED = "settings_catalog_refreshed";
    private static final String KEY_SETTINGS_CATALOG_LABEL = "settings_catalog_label";
    private static final String THEME_MODE_SYSTEM = "system";
    private static final String THEME_MODE_LIGHT = "light";
    private static final String THEME_MODE_DARK = "dark";
    private static final String CATALOG_URL = "https://raw.githubusercontent.com/DesperadoBoi/TGSpaces/main/catalog/clones.json";
    private static final String APP_CATALOG_URL = "https://raw.githubusercontent.com/DesperadoBoi/TGSpaces/main/catalog/app.json";
    private static final int MAX_CLONE_APKS = 10;

    private SharedPreferences preferences;
    private View checkUpdatesRow;
    private TextView checkUpdatesLabel;
    private TextView themeValueText;
    private TextView appVersionText;
    private TextView storageStatusText;
    private String themeMode = THEME_MODE_DARK;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences startupPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        themeMode = startupPreferences.getString(KEY_THEME_MODE, THEME_MODE_DARK);
        applyThemeMode(themeMode);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setupSettingsInsets();

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        themeMode = preferences.getString(KEY_THEME_MODE, THEME_MODE_DARK);

        checkUpdatesRow = findViewById(R.id.buttonCheckUpdates);
        checkUpdatesLabel = findViewById(R.id.textCheckUpdatesLabel);
        themeValueText = findViewById(R.id.textThemeValue);
        appVersionText = findViewById(R.id.textAppVersion);
        storageStatusText = findViewById(R.id.textStorageStatus);

        findViewById(R.id.buttonBack).setOnClickListener(view -> finish());
        findViewById(R.id.themeRow).setOnClickListener(this::showThemePicker);
        checkUpdatesRow.setOnClickListener(view -> checkUpdatesManually());
        findViewById(R.id.buttonCleanupApks).setOnClickListener(view -> confirmCleanupDownloads());
        findViewById(R.id.buttonWhatIsSlot).setOnClickListener(view -> showWhatIsSlotDialog());
        findViewById(R.id.buttonHowInstall).setOnClickListener(view -> showInstallHelpDialog());
        findViewById(R.id.buttonShowOnboarding).setOnClickListener(view -> showOnboardingAgain());
        findViewById(R.id.buttonAbout).setOnClickListener(view -> showAboutDialog());

        updateThemeValueText();
        updateVersionText();
        updateStorageStatusText();
    }

    private void setupSettingsInsets() {
        View root = findViewById(R.id.settingsRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
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

    private void showThemePicker(View anchor) {
        final int systemId = 1;
        final int lightId = 2;
        final int darkId = 3;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, systemId, 0, "Системная");
        menu.getMenu().add(0, lightId, 1, "Светлая");
        menu.getMenu().add(0, darkId, 2, "Тёмная");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == lightId) {
                setThemeMode(THEME_MODE_LIGHT);
            } else if (item.getItemId() == darkId) {
                setThemeMode(THEME_MODE_DARK);
            } else {
                setThemeMode(THEME_MODE_SYSTEM);
            }
            return true;
        });
        menu.show();
    }

    private void updateThemeValueText() {
        if (THEME_MODE_LIGHT.equals(themeMode)) {
            themeValueText.setText("Светлая");
        } else if (THEME_MODE_SYSTEM.equals(themeMode)) {
            themeValueText.setText("Системная");
        } else {
            themeValueText.setText("Тёмная");
        }
    }


    private void updateVersionText() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;
            String versionName = packageInfo.versionName != null ? packageInfo.versionName : "неизвестно";
            appVersionText.setText(versionName + " (" + versionCode + ")");
        } catch (PackageManager.NameNotFoundException e) {
            appVersionText.setText("неизвестно");
        }
    }

    private void updateStorageStatusText() {
        if (storageStatusText == null) {
            return;
        }
        storageStatusText.setText(hasDownloadedApks()
                ? "Скачанные APK: есть. Установленные клоны не удаляются."
                : "Скачанные APK: нет. Установленные клоны не удаляются.");
    }

    private void confirmCleanupDownloads() {
        new AlertDialog.Builder(this)
                .setTitle("Очистить скачанные APK?")
                .setMessage("Установленные клоны не будут удалены. Будут удалены только APK-файлы, скачанные TGSpaces.")
                .setPositiveButton("Очистить", (dialog, which) -> cleanupDownloadedApks())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void cleanupDownloadedApks() {
        Log.d(TAG, "cleanup downloads started");
        CleanupResult result = new CleanupResult();
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        SharedPreferences.Editor editor = preferences.edit();

        for (int slot = 1; slot <= MAX_CLONE_APKS; slot++) {
            long slotDownloadId = preferences.getLong(slotDownloadKey(slot), -1L);
            if (slotDownloadId > 0) {
                removeDownload(manager, slotDownloadId, result);
                editor.remove(slotDownloadKey(slot));
                editor.remove(downloadSlotKey(slotDownloadId));
            }
            editor.remove(slotErrorKey(slot));
            editor.remove(slotAutoOpenedKey(slot));
        }

        long pendingDownloadId = preferences.getLong(KEY_PENDING_DOWNLOAD_ID, -1L);
        if (pendingDownloadId > 0) {
            removeDownload(manager, pendingDownloadId, result);
            editor.remove(downloadSlotKey(pendingDownloadId));
        }
        deleteKnownApkPath(preferences.getString(KEY_PENDING_APK, null), result);
        editor.remove(KEY_PENDING_APK)
                .remove(KEY_PENDING_SLOT)
                .remove(KEY_PENDING_DOWNLOAD_ID);

        long appUpdateDownloadId = preferences.getLong(KEY_APP_UPDATE_DOWNLOAD_ID, -1L);
        if (appUpdateDownloadId > 0) {
            removeDownload(manager, appUpdateDownloadId, result);
        }
        deleteKnownApkPath(preferences.getString(KEY_APP_UPDATE_APK_PATH, null), result);
        editor.remove(KEY_APP_UPDATE_DOWNLOAD_ID)
                .remove(KEY_APP_UPDATE_APK_PATH)
                .remove(KEY_APP_UPDATE_DOWNLOAD_FAILED);

        deleteApksInAppDownloads(result);
        editor.apply();
        updateStorageStatusText();

        Log.d(TAG, "cleanup downloads finished: removed=" + result.removed);
        Toast.makeText(this, result.removed ? "Скачанные APK очищены" : "Нечего очищать", Toast.LENGTH_SHORT).show();
    }

    private boolean hasDownloadedApks() {
        if (preferences.getLong(KEY_PENDING_DOWNLOAD_ID, -1L) > 0
                || preferences.getLong(KEY_APP_UPDATE_DOWNLOAD_ID, -1L) > 0) {
            return true;
        }
        if (isSafeApkFile(preferences.getString(KEY_PENDING_APK, null))
                || isSafeApkFile(preferences.getString(KEY_APP_UPDATE_APK_PATH, null))) {
            return true;
        }
        for (int slot = 1; slot <= MAX_CLONE_APKS; slot++) {
            if (preferences.getLong(slotDownloadKey(slot), -1L) > 0) {
                return true;
            }
        }
        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File[] files = downloadsDir != null ? downloadsDir.listFiles() : null;
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".apk")) {
                return true;
            }
        }
        return false;
    }

    private void removeDownload(DownloadManager manager, long downloadId, CleanupResult result) {
        String localUri = queryDownloadLocalUri(downloadId);
        try {
            int removed = manager.remove(downloadId);
            if (removed > 0) {
                result.removed = true;
                Log.d(TAG, "cleanup download removed: downloadId=" + downloadId);
            }
        } catch (Exception e) {
            Log.w(TAG, "cleanup download remove failed: downloadId=" + downloadId, e);
        }
        deleteKnownApkUri(localUri, result);
    }

    private String queryDownloadLocalUri(long downloadId) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int column = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            return column >= 0 ? cursor.getString(column) : null;
        } catch (Exception e) {
            Log.w(TAG, "cleanup download query failed: downloadId=" + downloadId, e);
            return null;
        }
    }

    private void deleteKnownApkUri(String uriText, CleanupResult result) {
        if (uriText == null || uriText.trim().isEmpty()) {
            return;
        }
        Uri uri = Uri.parse(uriText);
        if ("file".equals(uri.getScheme())) {
            deleteKnownApkPath(uri.getPath(), result);
        } else if ("content".equals(uri.getScheme())) {
            try {
                int deleted = getContentResolver().delete(uri, null, null);
                if (deleted > 0) {
                    result.removed = true;
                    Log.d(TAG, "cleanup file deleted: uri=" + uri);
                }
            } catch (Exception e) {
                Log.w(TAG, "cleanup content uri delete failed: uri=" + uri, e);
            }
        }
    }

    private void deleteKnownApkPath(String path, CleanupResult result) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        File file = new File(path);
        if (!isSafeApkFile(file)) {
            return;
        }
        if (!file.exists()) {
            Log.d(TAG, "cleanup skipped missing file: path=" + file.getAbsolutePath());
            return;
        }
        if (file.delete()) {
            result.removed = true;
            Log.d(TAG, "cleanup file deleted: path=" + file.getAbsolutePath());
        } else {
            Log.w(TAG, "cleanup file delete failed: path=" + file.getAbsolutePath());
        }
    }

    private void deleteApksInAppDownloads(CleanupResult result) {
        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File[] files = downloadsDir != null ? downloadsDir.listFiles() : null;
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (isSafeApkFile(file)) {
                deleteKnownApkPath(file.getAbsolutePath(), result);
            }
        }
    }

    private boolean isSafeApkFile(String path) {
        return path != null && isSafeApkFile(new File(path));
    }

    private boolean isSafeApkFile(File file) {
        if (file == null || !file.getName().toLowerCase().endsWith(".apk")) {
            return false;
        }
        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            return false;
        }
        try {
            String filePath = file.getCanonicalPath();
            String downloadsPath = downloadsDir.getCanonicalPath();
            return filePath.equals(downloadsPath) || filePath.startsWith(downloadsPath + File.separator);
        } catch (Exception e) {
            Log.w(TAG, "cleanup safe path check failed: path=" + file.getAbsolutePath(), e);
            return false;
        }
    }

    private void checkUpdatesManually() {
        if (!checkUpdatesRow.isEnabled()) {
            return;
        }

        Log.d(TAG, "manual update check started");
        checkUpdatesRow.setEnabled(false);
        checkUpdatesLabel.setText("Проверяем...");

        new Thread(() -> {
            try {
                String clonesJson = downloadText(CATALOG_URL);
                String appJson = downloadText(APP_CATALOG_URL);
                String catalogLabel = parseCloneCatalogLabel(clonesJson);
                validateAppCatalog(appJson);

                runOnUiThread(() -> {
                    preferences.edit()
                            .putBoolean(KEY_SETTINGS_CATALOG_REFRESHED, true)
                            .putString(KEY_SETTINGS_CATALOG_LABEL, catalogLabel)
                            .apply();
                    updateVersionText();
                    resetCheckUpdatesButton();
                    Log.d(TAG, "manual update check finished");
                    Toast.makeText(this, "Обновления проверены", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.w(TAG, "manual update check failed", e);
                runOnUiThread(() -> {
                    resetCheckUpdatesButton();
                    Log.d(TAG, "manual update check finished");
                    Toast.makeText(this, "Не удалось проверить обновления, используется сохранённая информация", Toast.LENGTH_SHORT).show();
                });
            }
        }, "TGSpacesSettingsUpdateCheck").start();
    }

    private void resetCheckUpdatesButton() {
        checkUpdatesRow.setEnabled(true);
        checkUpdatesLabel.setText("Проверить обновления");
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

    private String parseCloneCatalogLabel(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray clones = root.getJSONArray("clones");
        if (clones.length() == 0) {
            throw new IllegalArgumentException("Catalog has no clones");
        }
        String catalogVersion = root.optString("catalogVersion", "").trim();
        if (!catalogVersion.isEmpty()) {
            return catalogVersion;
        }
        String releaseTag = root.optString("releaseTag", "").trim();
        if (!releaseTag.isEmpty()) {
            return releaseTag;
        }
        return "проверен";
    }

    private void validateAppCatalog(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String packageName = root.getString("packageName").trim();
        if (!getPackageName().equals(packageName)) {
            throw new IllegalArgumentException("Unexpected TGSpaces packageName: " + packageName);
        }
    }

    private void showWhatIsSlotDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Что такое слот")
                .setMessage("Слот — это отдельная копия Telegram с отдельным packageName и отдельным входом в аккаунт. Номер слота не сдвигается: TGClone 05 всегда остаётся TGClone 05.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showInstallHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Как установить клон")
                .setMessage(
                        "1. На главном экране нажмите \"Добавить слот\".\n"
                                + "2. В нужном слоте нажмите \"Установить\".\n"
                                + "3. Дождитесь загрузки APK.\n"
                                + "4. Подтвердите установку в системном установщике Android.\n\n"
                                + "Для установки может потребоваться разрешение \"Установка неизвестных приложений\"."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private void showOnboardingAgain() {
        preferences.edit().putBoolean(MainActivity.KEY_ONBOARDING_SEEN, false).apply();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_SHOW_ONBOARDING, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void showAboutDialog() {
        String versionText;
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;
            String versionName = packageInfo.versionName != null ? packageInfo.versionName : "неизвестно";
            versionText = versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            versionText = "неизвестно";
        }

        new AlertDialog.Builder(this)
                .setTitle("TGSpaces")
                .setMessage("Версия: " + versionText + "\n\nНеофициальное приложение. Проект не связан с Telegram.")
                .setPositiveButton("OK", null)
                .show();
    }

    private static String slotDownloadKey(int slot) {
        return KEY_SLOT_DOWNLOAD_PREFIX + slot;
    }

    private static String downloadSlotKey(long downloadId) {
        return KEY_DOWNLOAD_SLOT_PREFIX + downloadId;
    }

    private static String slotErrorKey(int slot) {
        return KEY_SLOT_ERROR_PREFIX + slot;
    }

    private static String slotAutoOpenedKey(int slot) {
        return KEY_SLOT_AUTO_OPENED_PREFIX + slot;
    }

    private static class CleanupResult {
        boolean removed;
    }
}
