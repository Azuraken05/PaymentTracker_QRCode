package com.example.qr_paymentscan_cash_n_online.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

public class AppLockManager {

    private static final String PREF_NAME = "app_lock_prefs";
    private static final String KEY_APP_LOCK_ENABLED = "key_app_lock_enabled";

    private static volatile AppLockManager instance;
    private final SharedPreferences prefs;
    private final Context appContext;
    private boolean isSessionUnlocked = false;

    public interface AuthCallback {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    private AppLockManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static AppLockManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AppLockManager.class) {
                if (instance == null) {
                    instance = new AppLockManager(context);
                }
            }
        }
        return instance;
    }

    public boolean isAppLockEnabled() {
        return prefs.getBoolean(KEY_APP_LOCK_ENABLED, false);
    }

    public void setAppLockEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply();
        if (!enabled) {
            isSessionUnlocked = true;
        }
    }

    public boolean isSessionUnlocked() {
        return !isAppLockEnabled() || isSessionUnlocked;
    }

    public void setSessionUnlocked(boolean unlocked) {
        this.isSessionUnlocked = unlocked;
    }

    public boolean isBiometricOrDevicePinAvailable() {
        try {
            BiometricManager biometricManager = BiometricManager.from(appContext);
            int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            int canAuth = biometricManager.canAuthenticate(authenticators);
            return canAuth == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    public void promptIfLocked(FragmentActivity activity, @NonNull AuthCallback callback) {
        if (!isAppLockEnabled() || isSessionUnlocked) {
            callback.onSuccess();
            return;
        }

        if (!isBiometricOrDevicePinAvailable()) {
            // No lock screen configured on device
            isSessionUnlocked = true;
            callback.onSuccess();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(activity);
        BiometricPrompt biometricPrompt = new BiometricPrompt(activity, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                isSessionUnlocked = true;
                callback.onSuccess();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                callback.onFailure(errString.toString());
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                // Individual fingerprint attempt failed, prompt remains open
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Payment Tracker")
                .setSubtitle("Verify identity with Fingerprint, Face, or Device PIN to access financial records")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }
}
