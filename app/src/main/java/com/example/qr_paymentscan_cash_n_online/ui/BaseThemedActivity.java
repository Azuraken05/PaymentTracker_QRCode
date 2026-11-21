package com.example.qr_paymentscan_cash_n_online.ui;

import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Base Activity that caps excessive OS font-scale / display-zoom inflation so that
 * Material 3 buttons, headers, badges, and receipt cards remain crisp, proportional,
 * and never wrap mid-word across all Android devices.
 */
public abstract class BaseThemedActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        if (newBase != null) {
            Configuration config = new Configuration(newBase.getResources().getConfiguration());
            boolean changed = false;

            // Normalize fontScale to 1.0f so device "Large/Largest" system font settings
            // do not break multi-button rows, badges, and receipt layouts.
            if (config.fontScale != 1.0f) {
                config.fontScale = 1.0f;
                changed = true;
            }

            // Cap excessive display zoom (> 380dp minimum width equivalent on 1080p screens)
            int widthPx = newBase.getResources().getDisplayMetrics().widthPixels;
            if (widthPx > 0 && config.densityDpi > 0) {
                float effectiveWidthDp = widthPx / (config.densityDpi / 160f);
                if (effectiveWidthDp < 392f) {
                    int targetDpi = Math.round((widthPx * 160f) / 400f);
                    if (targetDpi > 120 && targetDpi < config.densityDpi) {
                        config.densityDpi = targetDpi;
                        changed = true;
                    }
                }
            }

            if (changed) {
                Context scaledContext = newBase.createConfigurationContext(config);
                super.attachBaseContext(scaledContext);
                return;
            }
        }
        super.attachBaseContext(newBase);
    }
}
