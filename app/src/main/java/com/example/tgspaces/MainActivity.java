package com.example.tgspaces;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

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

        int[] buttonIds = {
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

        int[] statusIds = {
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

        String[] slotNames = {
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

        String[] packageNames = {
                "com.desperadoboi.tgclone01",
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

        for (int i = 0; i < buttonIds.length; i++) {
            Button button = findViewById(buttonIds[i]);
            TextView statusText = findViewById(statusIds[i]);
            String slotName = slotNames[i];
            String packageName = packageNames[i];
            boolean installed = isAppInstalled(packageName);

            if (installed) {
                statusText.setText("Установлено");
            } else {
                statusText.setText("Не установлено");
            }

            button.setOnClickListener(view -> {
                if (installed) {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);

                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "Слот " + slotName + " не установлен", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "Слот " + slotName + " не установлен", Toast.LENGTH_SHORT).show();
                }
            });
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
