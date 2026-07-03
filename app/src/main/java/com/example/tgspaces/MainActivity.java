package com.example.tgspaces;

import android.os.Bundle;
import android.widget.Button;
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

        for (int i = 0; i < buttonIds.length; i++) {
            Button button = findViewById(buttonIds[i]);
            String slotName = slotNames[i];

            button.setOnClickListener(view -> {
                Toast.makeText(this, "Слот " + slotName + " пока не настроен", Toast.LENGTH_SHORT).show();
            });
        }
    }
}
