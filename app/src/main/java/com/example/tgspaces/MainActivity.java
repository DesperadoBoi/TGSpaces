package com.example.tgspaces;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "slot_names";
    private static final String KEY_SLOT_COUNT = "slot_count";
    private static final String KEY_PENDING_APK = "pending_apk_path";
    private static final String KEY_DOWNLOAD_SLOT_PREFIX = "download_slot_";
    private static final int MAX_CLONE_APKS = 10;
    private static final String RELEASE_BASE_URL = "https://github.com/DesperadoBoi/TGSpaces/releases/download/v0.1-debug/";

    private final Map<Long, Integer> downloadSlots = new HashMap<>();
    private SharedPreferences preferences;
    private LinearLayout slotsContainer;
    private int visibleSlotCount = 1;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            Integer slot = downloadSlots.remove(downloadId);
            if (slot == null) {
                int savedSlot = preferences.getInt(downloadSlotKey(downloadId), 0);
                slot = savedSlot > 0 ? savedSlot : null;
            }
            if (slot != null) {
                preferences.edit().remove(downloadSlotKey(downloadId)).apply();
                handleDownloadedApk(downloadId, slot);
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
        slotsContainer = findViewById(R.id.slotsContainer);

        findViewById(R.id.buttonAddSlot).setOnClickListener(view -> addSlot());
        findViewById(R.id.buttonRefresh).setOnClickListener(view -> renderSlots());

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }

        renderSlots();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryOpenPendingInstaller();
        renderSlots();
    }

    @Override
    protected void onDestroy() {
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
    }

    private View createSlotCard(int slot) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackgroundResource(R.drawable.slot_card_background);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);

        boolean installed = isAppInstalled(packageName(slot));

        TextView title = new TextView(this);
        title.setText(slotName(slot));
        title.setTextColor(Color.parseColor("#F9FAFB"));
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        TextView status = new TextView(this);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(4);
        status.setLayoutParams(statusParams);
        status.setText(installed ? "Установлено" : "Не установлен");
        status.setTextColor(Color.parseColor(installed ? "#86EFAC" : "#FCA5A5"));
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(status);

        if (installed) {
            card.addView(createButton("Открыть", true, view -> openSlot(slot)));
            card.addView(createButton("Настройки", false, view -> openAppSettings(slot)));
        } else {
            card.addView(createButton("Установить клон", true, view -> showInstallDialog(slot)));
        }
        card.addView(createButton("Переименовать", false, view -> showRenameDialog(slot)));

        return card;
    }

    private Button createButton(String text, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        button.setText(text);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setOnClickListener(listener);
        return button;
    }

    private void addSlot() {
        int emptySlot = firstVisibleEmptySlot();
        if (emptySlot > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Пустой слот уже есть")
                    .setMessage("У вас уже есть пустой слот: " + defaultSlotName(emptySlot) + ". Сначала установите клон в него.")
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
        new AlertDialog.Builder(this)
                .setTitle("Установка " + defaultSlotName(slot))
                .setMessage("TGSpaces скачает APK этого клона и откроет системный установщик Android. Подтвердите установку. Если Android попросит разрешить установку из TGSpaces — разрешите её в настройках.")
                .setPositiveButton("Скачать и установить", (dialog, which) -> downloadCloneApk(slot))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void downloadCloneApk(int slot) {
        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            Toast.makeText(this, "Недоступна папка загрузок приложения", Toast.LENGTH_LONG).show();
            return;
        }

        String fileName = apkFileName(slot);
        File target = new File(downloadsDir, fileName);
        if (target.exists() && !target.delete()) {
            Toast.makeText(this, "Не удалось заменить старый APK", Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl(slot)));
        request.setTitle(fileName);
        request.setDescription("Скачивание " + defaultSlotName(slot));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        long downloadId = manager.enqueue(request);
        downloadSlots.put(downloadId, slot);
        preferences.edit().putInt(downloadSlotKey(downloadId), slot).apply();
        Toast.makeText(this, "Скачивание началось", Toast.LENGTH_SHORT).show();
    }

    private void handleDownloadedApk(long downloadId, int slot) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                Toast.makeText(this, "Не удалось проверить загрузку APK", Toast.LENGTH_LONG).show();
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(this, "APK не скачан. Проверьте релиз и подключение к интернету.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        File apk = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkFileName(slot));
        preferences.edit().putString(KEY_PENDING_APK, apk.getAbsolutePath()).apply();
        openInstallerOrUnknownSourcesSettings(apk);
    }

    private void tryOpenPendingInstaller() {
        String pendingPath = preferences.getString(KEY_PENDING_APK, null);
        if (pendingPath == null) {
            return;
        }

        File apk = new File(pendingPath);
        if (!apk.exists()) {
            preferences.edit().remove(KEY_PENDING_APK).apply();
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
            preferences.edit().remove(KEY_PENDING_APK).apply();
            openInstaller(apk);
        }
    }

    private void openInstallerOrUnknownSourcesSettings(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нужно разрешение Android")
                    .setMessage("Разрешите TGSpaces устанавливать неизвестные приложения, затем вернитесь назад. После возврата TGSpaces откроет системный установщик APK.")
                    .setPositiveButton("Открыть настройки", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }

        preferences.edit().remove(KEY_PENDING_APK).apply();
        openInstaller(apk);
    }

    private void openInstaller(File apk) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private boolean isAppInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String slotName(int slot) {
        return preferences.getString(slotNameKey(slot), defaultSlotName(slot));
    }

    private static String slotNameKey(int slot) {
        return "slot_name_" + (slot - 1);
    }

    private static String defaultSlotName(int slot) {
        return String.format(Locale.US, "TGClone %02d", slot);
    }

    private static String packageName(int slot) {
        return String.format(Locale.US, "com.desperadoboi.tgclone%02d", slot);
    }

    private static String apkFileName(int slot) {
        return String.format(Locale.US, "TGClone%02d-debug.apk", slot);
    }

    private static String apkUrl(int slot) {
        return RELEASE_BASE_URL + apkFileName(slot);
    }

    private static String downloadSlotKey(long downloadId) {
        return KEY_DOWNLOAD_SLOT_PREFIX + downloadId;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
