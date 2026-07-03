package com.example.tgspaces;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences preferences;

    private final int[] titleIds = {
            R.id.titleTelegram01,
            R.id.titleTelegram02,
            R.id.titleTelegram03,
            R.id.titleTelegram04,
            R.id.titleTelegram05,
            R.id.titleTelegram06,
            R.id.titleTelegram07,
            R.id.titleTelegram08,
            R.id.titleTelegram09,
            R.id.titleTelegram10
    };

    private final int[] buttonIds = {
            R.id.buttonTelegram01,
            R.id.buttonTelegram02,
            R.id.buttonTelegram03,
            R.id.buttonTelegram04,
            R.id.buttonTelegram05,
            R.id.buttonTelegram06,
            R.id.buttonTelegram07,
            R.id.buttonTelegram08,
            R.id.buttonTelegram09,
            R.id.buttonTelegram10
    };

    private final int[] renameButtonIds = {
            R.id.buttonRename01,
            R.id.buttonRename02,
            R.id.buttonRename03,
            R.id.buttonRename04,
            R.id.buttonRename05,
            R.id.buttonRename06,
            R.id.buttonRename07,
            R.id.buttonRename08,
            R.id.buttonRename09,
            R.id.buttonRename10
    };

    private final int[] settingsButtonIds = {
            R.id.buttonSettings01,
            R.id.buttonSettings02,
            R.id.buttonSettings03,
            R.id.buttonSettings04,
            R.id.buttonSettings05,
            R.id.buttonSettings06,
            R.id.buttonSettings07,
            R.id.buttonSettings08,
            R.id.buttonSettings09,
            R.id.buttonSettings10
    };

    private final int[] statusIds = {
            R.id.statusTelegram01,
            R.id.statusTelegram02,
            R.id.statusTelegram03,
            R.id.statusTelegram04,
            R.id.statusTelegram05,
            R.id.statusTelegram06,
            R.id.statusTelegram07,
            R.id.statusTelegram08,
            R.id.statusTelegram09,
            R.id.statusTelegram10
    };

    private final String[] defaultSlotNames = {
            "Telegram 01",
            "Telegram 02",
            "Telegram 03",
            "Telegram 04",
            "Telegram 05",
            "Telegram 06",
            "Telegram 07",
            "Telegram 08",
            "Telegram 09",
            "Telegram 10"
    };

    private final String[] slotNames = new String[10];

    private final String[] packageNames = {
            "org.telegram.messenger",
            "com.desperadoboi.tgclone02",
            "com.desperadoboi.tgclone03",
            "com.desperadoboi.tgclone04",
            "com.desperadoboi.tgclone05",
            "com.desperadoboi.tgclone06",
            "com.desperadoboi.tgclone07",
            "com.desperadoboi.tgclone08",
            "com.desperadoboi.tgclone09",
            "com.desperadoboi.tgclone10"
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

        preferences = getSharedPreferences("slot_names", MODE_PRIVATE);
        loadSlotNames();
        updateSlotTitles();

        Button refreshButton = findViewById(R.id.buttonRefresh);
        refreshButton.setOnClickListener(view -> updateStatuses());

        for (int i = 0; i < buttonIds.length; i++) {
            Button button = findViewById(buttonIds[i]);
            Button renameButton = findViewById(renameButtonIds[i]);
            Button settingsButton = findViewById(settingsButtonIds[i]);
            int index = i;

            button.setOnClickListener(view -> openSlot(index));
            renameButton.setOnClickListener(view -> showRenameDialog(index));
            settingsButton.setOnClickListener(view -> openAppSettings(index));
        }

        updateStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatuses();
    }

    private void loadSlotNames() {
        for (int i = 0; i < slotNames.length; i++) {
            slotNames[i] = preferences.getString("slot_name_" + i, defaultSlotNames[i]);
        }
    }

    private void updateSlotTitles() {
        for (int i = 0; i < titleIds.length; i++) {
            TextView titleText = findViewById(titleIds[i]);
            titleText.setText(slotNames[i]);
        }
    }

    private void showRenameDialog(int index) {
        EditText editText = new EditText(this);
        editText.setText(slotNames[index]);
        editText.setSelection(editText.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Переименовать слот")
                .setView(editText)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String newName = editText.getText().toString().trim();

                    if (newName.isEmpty()) {
                        newName = defaultSlotNames[index];
                    }

                    slotNames[index] = newName;
                    preferences.edit().putString("slot_name_" + index, newName).apply();
                    updateSlotTitles();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void updateStatuses() {
        for (int i = 0; i < statusIds.length; i++) {
            TextView statusText = findViewById(statusIds[i]);

            if (isAppInstalled(packageNames[i])) {
                statusText.setText("Установлено");
                statusText.setTextColor(Color.parseColor("#86EFAC"));
            } else {
                statusText.setText("Не установлено");
                statusText.setTextColor(Color.parseColor("#FCA5A5"));
            }
        }
    }

    private void openSlot(int index) {
        String slotName = slotNames[index];
        String packageName = packageNames[index];

        if (isAppInstalled(packageName)) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);

            if (intent != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "Слот " + slotName + " не установлен", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Слот " + slotName + " не установлен", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppSettings(int index) {
        String slotName = slotNames[index];
        String packageName = packageNames[index];

        if (isAppInstalled(packageName)) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } else {
            Toast.makeText(this, "Слот " + slotName + " не установлен", Toast.LENGTH_SHORT).show();
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
}
