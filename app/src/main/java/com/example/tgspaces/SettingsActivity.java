package com.example.tgspaces;

import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "TGSpaces";
    private static final String PREFS_NAME = "slot_names";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_SETTINGS_CATALOG_REFRESHED = "settings_catalog_refreshed";
    private static final String KEY_SETTINGS_CATALOG_LABEL = "settings_catalog_label";
    private static final String THEME_MODE_SYSTEM = "system";
    private static final String THEME_MODE_LIGHT = "light";
    private static final String THEME_MODE_DARK = "dark";
    private static final String CATALOG_URL = "https://raw.githubusercontent.com/DesperadoBoi/TGSpaces/main/catalog/clones.json";
    private static final String APP_CATALOG_URL = "https://raw.githubusercontent.com/DesperadoBoi/TGSpaces/main/catalog/app.json";

    private SharedPreferences preferences;
    private View checkUpdatesRow;
    private TextView checkUpdatesLabel;
    private TextView themeValueText;
    private TextView appVersionText;
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

        findViewById(R.id.buttonBack).setOnClickListener(view -> finish());
        findViewById(R.id.themeRow).setOnClickListener(this::showThemePicker);
        checkUpdatesRow.setOnClickListener(view -> checkUpdatesManually());
        findViewById(R.id.buttonWhatIsSlot).setOnClickListener(view -> showWhatIsSlotDialog());
        findViewById(R.id.buttonHowInstall).setOnClickListener(view -> showInstallHelpDialog());
        findViewById(R.id.buttonAbout).setOnClickListener(view -> showAboutDialog());

        updateThemeValueText();
        updateVersionText();
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
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Системная");
        menu.getMenu().add("Светлая");
        menu.getMenu().add("Тёмная");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Светлая".equals(title)) {
                setThemeMode(THEME_MODE_LIGHT);
            } else if ("Тёмная".equals(title)) {
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
}
