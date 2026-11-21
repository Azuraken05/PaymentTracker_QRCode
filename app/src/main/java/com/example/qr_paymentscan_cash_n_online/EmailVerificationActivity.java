package com.example.qr_paymentscan_cash_n_online;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.ui.LoadingOverlayController;
import com.example.qr_paymentscan_cash_n_online.util.RoomDiscoveryManager;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class EmailVerificationActivity extends BaseThemedActivity {

    private FirebaseAuth auth;
    private FirebaseUser currentUser;
    private LoadingOverlayController loadingOverlay;
    private MaterialButton btnResendVerification;
    private CountDownTimer countDownTimer;
    private View rootView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_email_verification);

        rootView = findViewById(R.id.emailVerificationRoot);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        auth = FirebaseAuth.getInstance();
        currentUser = auth.getCurrentUser();
        loadingOverlay = new LoadingOverlayController(this);

        if (currentUser == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }

        TextView tvEmail = findViewById(R.id.tvVerificationEmailAddress);
        tvEmail.setText(currentUser.getEmail() != null ? currentUser.getEmail() : "Your Email");

        MaterialButton btnVerified = findViewById(R.id.btnVerifiedCheck);
        MaterialButton btnOpenEmail = findViewById(R.id.btnOpenEmailApp);
        btnResendVerification = findViewById(R.id.btnResendVerification);
        TextView tvSignOut = findViewById(R.id.tvSignOutOrDifferentAccount);

        btnVerified.setOnClickListener(v -> checkEmailVerificationStatus());
        btnOpenEmail.setOnClickListener(v -> openDefaultEmailApp());
        btnResendVerification.setOnClickListener(v -> resendVerificationEmail());
        tvSignOut.setOnClickListener(v -> signOutAndReturnToAuth());
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkEmailVerificationSilently();
    }

    @Override
    protected void onDestroy() {
        if (loadingOverlay != null) {
            loadingOverlay.release();
        }
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        super.onDestroy();
    }

    private void checkEmailVerificationSilently() {
        if (currentUser == null) return;
        currentUser.reload().addOnSuccessListener(aVoid -> {
            if (currentUser.isEmailVerified()) {
                proceedToApp();
            }
        });
    }

    private void checkEmailVerificationStatus() {
        if (currentUser == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }

        loadingOverlay.show("Checking Verification...", "Confirming email status with Firebase Auth");
        currentUser.reload().addOnCompleteListener(task -> {
            loadingOverlay.hide();
            if (currentUser.isEmailVerified()) {
                Toast.makeText(this, "Email verified successfully!", Toast.LENGTH_SHORT).show();
                proceedToApp();
            } else {
                Snackbar.make(
                        rootView,
                        "Email not verified yet. Please check your inbox or spam folder.",
                        Snackbar.LENGTH_LONG
                ).show();
            }
        });
    }

    private void openDefaultEmailApp() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_APP_EMAIL);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(intent, "Open Email Client"));
        } catch (Exception e) {
            Snackbar.make(rootView, "No email app found on this device.", Snackbar.LENGTH_SHORT).show();
        }
    }

    private void resendVerificationEmail() {
        if (currentUser == null) return;

        btnResendVerification.setEnabled(false);
        currentUser.sendEmailVerification().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Snackbar.make(rootView, "Verification link sent to " + currentUser.getEmail(), Snackbar.LENGTH_SHORT).show();
                startCountdownTimer();
            } else {
                btnResendVerification.setEnabled(true);
                String err = task.getException() != null ? task.getException().getMessage() : "Failed to send link";
                Snackbar.make(rootView, err, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void startCountdownTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        countDownTimer = new CountDownTimer(60000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long sec = millisUntilFinished / 1000;
                btnResendVerification.setText("Resend link in " + sec + "s");
            }

            @Override
            public void onFinish() {
                btnResendVerification.setEnabled(true);
                btnResendVerification.setText("Resend Verification Link");
            }
        }.start();
    }

    private void proceedToApp() {
        UserSessionManager.getInstance(this).syncFromFirebaseAuthIfAvailable();
        RoomDiscoveryManager.discoverAndRouteUser(this, loadingOverlay, true);
    }

    private void signOutAndReturnToAuth() {
        UserSessionManager.getInstance(this).signOut();
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
