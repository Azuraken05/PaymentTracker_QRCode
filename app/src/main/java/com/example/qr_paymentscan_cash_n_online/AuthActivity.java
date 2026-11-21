package com.example.qr_paymentscan_cash_n_online;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

public class AuthActivity extends BaseThemedActivity {

    private UserSessionManager sessionManager;
    private PaymentRepository repository;

    private TextView tvCurrentUserName;
    private TextView tvCurrentUserEmail;
    private MaterialButton btnSignOut;
    private MaterialButton btnTabSignIn;
    private MaterialButton btnTabCreateAccount;
    private TextInputLayout tilAuthFullName;
    private TextInputEditText etAuthFullName;
    private TextInputLayout tilAuthEmail;
    private TextInputEditText etAuthEmail;
    private TextInputLayout tilAuthPassword;
    private TextInputEditText etAuthPassword;
    private MaterialButton btnSubmitAuth;

    private boolean isCreateAccountMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        sessionManager = UserSessionManager.getInstance(this);
        repository = new PaymentRepository(this);

        View root = findViewById(R.id.authRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        tvCurrentUserName = findViewById(R.id.tvCurrentUserName);
        tvCurrentUserEmail = findViewById(R.id.tvCurrentUserEmail);
        btnSignOut = findViewById(R.id.btnSignOut);
        MaterialButton btnEditDisplayName = findViewById(R.id.btnEditDisplayName);

        btnTabSignIn = findViewById(R.id.btnTabSignIn);
        btnTabCreateAccount = findViewById(R.id.btnTabCreateAccount);
        tilAuthFullName = findViewById(R.id.tilAuthFullName);
        etAuthFullName = findViewById(R.id.etAuthFullName);
        tilAuthEmail = findViewById(R.id.tilAuthEmail);
        etAuthEmail = findViewById(R.id.etAuthEmail);
        tilAuthPassword = findViewById(R.id.tilAuthPassword);
        etAuthPassword = findViewById(R.id.etAuthPassword);
        btnSubmitAuth = findViewById(R.id.btnSubmitAuth);
        MaterialButton btnContinueWorkspace = findViewById(R.id.btnContinueWorkspace);

        btnTabSignIn.setOnClickListener(v -> setAuthMode(false));
        btnTabCreateAccount.setOnClickListener(v -> setAuthMode(true));
        btnEditDisplayName.setOnClickListener(v -> showEditDisplayNameDialog());
        btnSignOut.setOnClickListener(v -> handleSignOut());
        btnSubmitAuth.setOnClickListener(v -> handleSubmitAuth());
        btnContinueWorkspace.setOnClickListener(v -> proceedToWorkspace());

        setAuthMode(false);
        refreshCurrentProfileHeader();
    }

    private void setAuthMode(boolean createAccount) {
        this.isCreateAccountMode = createAccount;
        if (createAccount) {
            tilAuthFullName.setVisibility(View.VISIBLE);
            btnSubmitAuth.setText("Create Officer Account");
        } else {
            tilAuthFullName.setVisibility(View.GONE);
            btnSubmitAuth.setText("Sign In to Account");
        }
        tilAuthFullName.setError(null);
        tilAuthEmail.setError(null);
        tilAuthPassword.setError(null);
    }

    private void refreshCurrentProfileHeader() {
        sessionManager.ensureLocalFallbackUserIfNeeded();
        tvCurrentUserName.setText(sessionManager.getCurrentUserName());
        String email = sessionManager.getCurrentUserEmail();
        if (sessionManager.isSignedIn() && !email.isEmpty()) {
            tvCurrentUserEmail.setText("Cloud Account: " + email);
            btnSignOut.setVisibility(View.VISIBLE);
        } else {
            tvCurrentUserEmail.setText("Offline / Local Workspace Officer");
            btnSignOut.setVisibility(View.GONE);
        }
    }

    private void showEditDisplayNameDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etName = new EditText(this);
        etName.setHint("Full Name / Officer Display Name");
        etName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etName.setText(sessionManager.getCurrentUserName());
        etName.setSelection(etName.getText().length());
        container.addView(etName);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Edit Display Name")
                .setMessage("This name appears on payment receipts, expenses, Excel exports, and audit log events.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save Name", (d, w) -> {
                    String newName = etName.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "Display name cannot be empty.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    sessionManager.updateDisplayName(newName);
                    try {
                        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                        if (user != null) {
                            user.updateProfile(new UserProfileChangeRequest.Builder()
                                    .setDisplayName(newName)
                                    .build());
                        }
                    } catch (Exception ignored) {
                    }
                    refreshCurrentProfileHeader();
                    repository.ensureActiveRoomAndClaimLegacyData(null);
                    Toast.makeText(this, "Display name updated to " + newName, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void handleSignOut() {
        sessionManager.signOut();
        refreshCurrentProfileHeader();
        Toast.makeText(this, "Signed out of cloud account. Local data remains safe.", Toast.LENGTH_SHORT).show();
    }

    private void handleSubmitAuth() {
        tilAuthFullName.setError(null);
        tilAuthEmail.setError(null);
        tilAuthPassword.setError(null);

        String fullName = etAuthFullName.getText() != null ? etAuthFullName.getText().toString().trim() : "";
        String email = etAuthEmail.getText() != null ? etAuthEmail.getText().toString().trim() : "";
        String password = etAuthPassword.getText() != null ? etAuthPassword.getText().toString().trim() : "";

        if (isCreateAccountMode && fullName.isEmpty()) {
            tilAuthFullName.setError("Full Name / Display Name is required");
            return;
        }
        if (email.isEmpty() || !email.contains("@")) {
            tilAuthEmail.setError("Enter a valid email address");
            return;
        }
        if (password.length() < 6) {
            tilAuthPassword.setError("Password must be at least 6 characters");
            return;
        }

        if (!FirebaseSyncManager.isFirebaseConfigured(this) || !FirebaseSyncManager.isNetworkConnected(this)) {
            // Offline fallback: save the profile locally so the user can proceed immediately
            String resolvedName = !fullName.isEmpty() ? fullName : email.substring(0, email.indexOf('@'));
            sessionManager.setSignedInUser("local-" + Math.abs(email.hashCode()), resolvedName, email);
            refreshCurrentProfileHeader();
            Toast.makeText(this, "Offline mode: Profile saved locally as " + resolvedName, Toast.LENGTH_LONG).show();
            proceedToWorkspace();
            return;
        }

        btnSubmitAuth.setEnabled(false);
        FirebaseAuth auth = FirebaseAuth.getInstance();

        if (isCreateAccountMode) {
            auth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener(result -> {
                        FirebaseUser user = result.getUser();
                        if (user != null) {
                            user.updateProfile(new UserProfileChangeRequest.Builder()
                                    .setDisplayName(fullName)
                                    .build());
                            sessionManager.setSignedInUser(user.getUid(), fullName, email);
                        }
                        btnSubmitAuth.setEnabled(true);
                        refreshCurrentProfileHeader();
                        Toast.makeText(this, "Account created! Signed in as " + fullName, Toast.LENGTH_SHORT).show();
                        proceedToWorkspace();
                    })
                    .addOnFailureListener(e -> {
                        btnSubmitAuth.setEnabled(true);
                        // If Email/Password auth is not yet enabled in Firebase Console, fall back gracefully
                        String msg = e.getMessage() != null ? e.getMessage() : "Authentication failed";
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("Cloud Sign-Up Notice")
                                .setMessage(msg + "\n\nWould you like to activate this Officer Profile locally now and sync later?")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Use Profile Now", (d, w) -> {
                                    sessionManager.setSignedInUser("user-" + Math.abs(email.hashCode()), fullName, email);
                                    refreshCurrentProfileHeader();
                                    proceedToWorkspace();
                                })
                                .show();
                    });
        } else {
            auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener(result -> {
                        FirebaseUser user = result.getUser();
                        if (user != null) {
                            String name = user.getDisplayName();
                            if (name == null || name.trim().isEmpty()) {
                                name = email.substring(0, email.indexOf('@'));
                            }
                            sessionManager.setSignedInUser(user.getUid(), name, email);
                        }
                        btnSubmitAuth.setEnabled(true);
                        refreshCurrentProfileHeader();
                        Toast.makeText(this, "Signed in as " + sessionManager.getCurrentUserName(), Toast.LENGTH_SHORT).show();
                        proceedToWorkspace();
                    })
                    .addOnFailureListener(e -> {
                        btnSubmitAuth.setEnabled(true);
                        Toast.makeText(this, "Sign-In failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
        }
    }

    private void proceedToWorkspace() {
        repository.ensureActiveRoomAndClaimLegacyData(new PaymentRepository.ResultCallback<RoomEntity>() {
            @Override
            public void onSuccess(RoomEntity result) {
                if (isTaskRoot()) {
                    startActivity(new Intent(AuthActivity.this, MainActivity.class));
                }
                finish();
            }

            @Override
            public void onError(String message) {
                finish();
            }
        });
    }
}
