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
import androidx.core.content.FileProvider;
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
    private static final int MAX_CLONE_APKS = 10;
    private static final String CATALOG_URL = "https://raw.githubusercontent.com/DesperadoBoi/TGSpaces/main/catalog/clones.json";
    private static final String RELEASE_BASE_URL = "https://github.com/DesperadoBoi/TGSpaces/releases/download/v0.2-release/";
    private static final long PROGRESS_REFRESH_MS = 1200L;

    private final Map<Long, Integer> downloadSlots = new HashMap<>();
    private final Map<Integer, CloneInfo> cloneCatalog = new HashMap<>();
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private LinearLayout slotsContainer;
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
        slotsContainer = findViewById(R.id.slotsContainer);

        findViewById(R.id.buttonAddSlot).setOnClickListener(view -> addSlot());
        findViewById(R.id.buttonRefresh).setOnClickListener(view -> {
            checkDownloadsForTerminalStates(false);
            renderSlots();
        });

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }

        checkDownloadsForTerminalStates(false);
        renderSlots();
        loadCloneCatalog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkDownloadsForTerminalStates(false);
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
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackgroundResource(R.drawable.slot_card_background);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);

        SlotState state = getSlotState(slot);

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
            hint.setTextSize(14);
            card.addView(hint);
        }

        switch (state.type) {
            case INSTALLED:
                card.addView(createButton("Открыть", true, true, view -> openSlot(slot)));
                card.addView(createButton("Настройки", false, true, view -> openAppSettings(slot)));
                card.addView(createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
            case DOWNLOADING:
                card.addView(createButton("Скачивается...", true, false, null));
                card.addView(createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
            case WAITING_INSTALL:
                card.addView(createButton("Открыть установщик", true, true, view -> openInstallerForDownloadedApk(slot, getKnownDownloadId(slot))));
                card.addView(createButton("Повторить загрузку", false, true, view -> showInstallDialog(slot)));
                card.addView(createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
            case DOWNLOAD_ERROR:
                card.addView(createButton("Повторить", true, true, view -> showInstallDialog(slot)));
                card.addView(createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
            case NOT_INSTALLED:
            default:
                card.addView(createButton("Установить клон", true, true, view -> showInstallDialog(slot)));
                card.addView(createButton("Переименовать", false, true, view -> showRenameDialog(slot)));
                break;
        }

        return card;
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
        if (listener != null) {
            button.setOnClickListener(listener);
        }
        return button;
    }

    private SlotState getSlotState(int slot) {
        if (isAppInstalled(packageName(slot))) {
            clearSlotDownload(slot);
            if (preferences.getInt(KEY_PENDING_SLOT, 0) == slot) {
                showInstalledCloneNoticeOnce(slot);
                preferences.edit()
                        .remove(KEY_PENDING_APK)
                        .remove(KEY_PENDING_SLOT)
                        .remove(KEY_PENDING_DOWNLOAD_ID)
                        .remove(slotAutoOpenedKey(slot))
                        .apply();
            }
            return SlotState.installed();
        }

        if (hasPendingApk(slot)) {
            return SlotState.waitingInstall();
        }

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

        String error = preferences.getString(slotErrorKey(slot), null);
        if (error != null) {
            return SlotState.error(error);
        }

        return SlotState.notInstalled();
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

    private void downloadCloneApk(int slot) {
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
        preferences.edit().remove(slotDownloadKey(slot)).apply();
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

    private void loadCloneCatalog() {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(CATALOG_URL);
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

                Map<Integer, CloneInfo> loadedCatalog = parseCloneCatalog(body.toString());
                runOnUiThread(() -> {
                    cloneCatalog.clear();
                    cloneCatalog.putAll(loadedCatalog);
                    Log.d(TAG, "Clone catalog loaded: " + cloneCatalog.size() + " clones");
                    renderSlots();
                });
            } catch (Exception e) {
                Log.w(TAG, "Clone catalog load failed, using fallback clone URLs", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
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
                    item.optString("versionName", "")
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
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
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

        static SlotState installed() {
            return new SlotState(SlotStateType.INSTALLED, "Установлено", null, Color.parseColor("#86EFAC"));
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

    private static class CloneInfo {
        final int slot;
        final String name;
        final String packageName;
        final String apkFileName;
        final String apkUrl;
        final String versionName;

        CloneInfo(int slot, String name, String packageName, String apkFileName, String apkUrl, String versionName) {
            this.slot = slot;
            this.name = name;
            this.packageName = packageName;
            this.apkFileName = apkFileName;
            this.apkUrl = apkUrl;
            this.versionName = versionName;
        }
    }
}
