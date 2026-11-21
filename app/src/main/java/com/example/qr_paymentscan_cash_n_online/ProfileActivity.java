package com.example.qr_paymentscan_cash_n_online;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.ui.LoadingOverlayController;
import com.example.qr_paymentscan_cash_n_online.util.AppLockManager;
import com.example.qr_paymentscan_cash_n_online.util.RoomSecurityHelper;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProfileActivity extends BaseThemedActivity {

    private UserSessionManager sessionManager;
    private LoadingOverlayController loadingOverlay;

    private View rootView;
    private TextView tvProfileInitials;
    private TextView tvProfileDisplayName;
    private TextView tvProfileEmail;
    private Chip chipVerificationStatus;
    private Chip chipUserUid;

    private TextInputLayout tilProfileFullName;
    private TextInputEditText etProfileFullName;
    private TextInputLayout tilProfileStudentId;
    private TextInputEditText etProfileStudentId;
    private TextInputLayout tilProfilePosition;
    private TextInputEditText etProfilePosition;
    private MaterialButton btnSaveProfile;

    private MaterialButton btnChangePassword;
    private MaterialSwitch switchAppLock;
    private MaterialButton btnProfileSignOut;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);

        rootView = findViewById(R.id.profileRoot);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        sessionManager = UserSessionManager.getInstance(this);
        loadingOverlay = new LoadingOverlayController(this);

        ImageButton btnBack = findViewById(R.id.btnBackProfile);
        btnBack.setOnClickListener(v -> finish());

        tvProfileInitials = findViewById(R.id.tvProfileInitials);
        tvProfileDisplayName = findViewById(R.id.tvProfileDisplayName);
        tvProfileEmail = findViewById(R.id.tvProfileEmail);
        chipVerificationStatus = findViewById(R.id.chipVerificationStatus);
        chipUserUid = findViewById(R.id.chipUserUid);

        tilProfileFullName = findViewById(R.id.tilProfileFullName);
        etProfileFullName = findViewById(R.id.etProfileFullName);
        tilProfileStudentId = findViewById(R.id.tilProfileStudentId);
        etProfileStudentId = findViewById(R.id.etProfileStudentId);
        tilProfilePosition = findViewById(R.id.tilProfilePosition);
        etProfilePosition = findViewById(R.id.etProfilePosition);
        btnSaveProfile = findViewById(R.id.btnSaveProfile);

        btnChangePassword = findViewById(R.id.btnChangePassword);
        switchAppLock = findViewById(R.id.switchAppLock);
        btnProfileSignOut = findViewById(R.id.btnProfileSignOut);

        btnSaveProfile.setOnClickListener(v -> handleSaveProfile());
        btnChangePassword.setOnClickListener(v -> showChangePasswordDialog());
        btnProfileSignOut.setOnClickListener(v -> handleSignOut());

        chipUserUid.setOnClickListener(v -> {
            String uid = sessionManager.getCurrentUserId();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Officer UID", uid);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Snackbar.make(rootView, "Copied Officer UID to clipboard", Snackbar.LENGTH_SHORT).show();
            }
        });

        setupAppLockSwitch();
        populateProfileData();
        fetchFirestoreProfileDetails();
    }

    @Override
    protected void onResume() {
        super.onResume();
        populateProfileData();
    }

    @Override
    protected void onDestroy() {
        if (loadingOverlay != null) {
            loadingOverlay.release();
        }
        super.onDestroy();
    }

    private void setupAppLockSwitch() {
        AppLockManager lockManager = AppLockManager.getInstance(this);
        switchAppLock.setChecked(lockManager.isAppLockEnabled());
        switchAppLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            lockManager.setAppLockEnabled(isChecked);
            String msg = isChecked
                    ? "Biometric / PIN lock enabled on launch"
                    : "App lock disabled";
            Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show();
        });
    }

    private void populateProfileData() {
        sessionManager.ensureLocalFallbackUserIfNeeded();
        sessionManager.syncFromFirebaseAuthIfAvailable();

        String displayName = sessionManager.getCurrentUserName();
        String email = sessionManager.getCurrentUserEmail();
        String uid = sessionManager.getCurrentUserId();

        tvProfileDisplayName.setText(displayName);
        tvProfileInitials.setText(extractInitials(displayName));
        tvProfileEmail.setText(email.isEmpty() ? "Offline Local Officer" : email);

        chipUserUid.setText("UID: " + (uid.length() > 8 ? uid.substring(0, 8) + "..." : uid));

        if (etProfileFullName.getText() == null || etProfileFullName.getText().toString().isEmpty()) {
            etProfileFullName.setText(displayName);
        }

        FirebaseUser user = null;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Exception ignored) {
        }

        if (user != null && user.isEmailVerified()) {
            chipVerificationStatus.setText("Verified Account");
            chipVerificationStatus.setTextColor(ContextCompat.getColor(this, R.color.status_paid_green));
            chipVerificationStatus.setChipIconResource(R.drawable.ic_verified_user);
            chipVerificationStatus.setChipIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_paid_green)));
            chipVerificationStatus.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_paid_green)));
            chipVerificationStatus.setChipBackgroundColor(ColorStateList.valueOf(0xFFF0FFF4));
            chipVerificationStatus.setOnClickListener(null);
        } else if (user != null) {
            chipVerificationStatus.setText("Unverified — Tap to Verify");
            chipVerificationStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning_amber));
            chipVerificationStatus.setChipIconResource(R.drawable.ic_gpp_maybe);
            chipVerificationStatus.setChipIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_warning_amber)));
            chipVerificationStatus.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_warning_amber)));
            chipVerificationStatus.setChipBackgroundColor(ColorStateList.valueOf(0xFFFFFBEB));
            chipVerificationStatus.setOnClickListener(v -> {
                startActivity(new Intent(ProfileActivity.this, EmailVerificationActivity.class));
            });
        } else {
            chipVerificationStatus.setText("Local Workspace");
            chipVerificationStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            chipVerificationStatus.setChipIconResource(R.drawable.ic_account_circle);
            chipVerificationStatus.setChipIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary)));
            chipVerificationStatus.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.divider_color)));
            chipVerificationStatus.setChipBackgroundColor(ColorStateList.valueOf(0xFFF1F5F9));
            chipVerificationStatus.setOnClickListener(null);
        }
    }

    private void fetchFirestoreProfileDetails() {
        FirebaseUser user = null;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Exception ignored) {
        }
        if (user == null || !FirebaseSyncManager.isFirebaseConfigured(this)) {
            return;
        }

        FirebaseFirestore.getInstance().collection("users")
                .document(user.getUid())
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot != null && snapshot.exists()) {
                        String studentId = snapshot.getString("studentId");
                        String position = snapshot.getString("position");
                        if (studentId != null && !studentId.isEmpty() && etProfileStudentId.getText() != null && etProfileStudentId.getText().toString().isEmpty()) {
                            etProfileStudentId.setText(studentId);
                        }
                        if (position != null && !position.isEmpty() && etProfilePosition.getText() != null && etProfilePosition.getText().toString().isEmpty()) {
                            etProfilePosition.setText(position);
                        }
                    }
                });
    }

    private void handleSaveProfile() {
        tilProfileFullName.setError(null);
        String newName = etProfileFullName.getText() != null ? etProfileFullName.getText().toString().trim() : "";
        String studentId = etProfileStudentId.getText() != null ? etProfileStudentId.getText().toString().trim() : "";
        String position = etProfilePosition.getText() != null ? etProfilePosition.getText().toString().trim() : "";

        if (newName.isEmpty()) {
            tilProfileFullName.setError("Display Name cannot be empty");
            return;
        }

        sessionManager.updateDisplayName(newName);
        tvProfileDisplayName.setText(newName);
        tvProfileInitials.setText(extractInitials(newName));

        FirebaseUser user = null;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Exception ignored) {
        }

        if (user != null) {
            loadingOverlay.show("Saving Profile...", "Updating cloud identity");
            final FirebaseUser finalUser = user;
            user.updateProfile(new UserProfileChangeRequest.Builder()
                    .setDisplayName(newName)
                    .build())
                    .addOnCompleteListener(task -> {
                        Map<String, Object> data = new HashMap<>();
                        data.put("userId", finalUser.getUid());
                        data.put("displayName", newName);
                        data.put("email", finalUser.getEmail() != null ? finalUser.getEmail() : "");
                        data.put("studentId", studentId);
                        data.put("position", position);
                        data.put("updatedAt", System.currentTimeMillis());

                        FirebaseFirestore firestore = FirebaseFirestore.getInstance();
                        firestore.collection("users")
                                .document(finalUser.getUid())
                                .set(data, SetOptions.merge())
                                .addOnSuccessListener(unused -> {
                                    propagateNameUpdatesAcrossRooms(finalUser.getUid(), newName);
                                    loadingOverlay.hide();
                                    Snackbar.make(rootView, "Profile updated successfully!", Snackbar.LENGTH_SHORT).show();
                                })
                                .addOnFailureListener(e -> {
                                    loadingOverlay.hide();
                                    Snackbar.make(rootView, "Saved locally. Cloud sync failed: " + e.getMessage(), Snackbar.LENGTH_LONG).show();
                                });
                    });
        } else {
            Snackbar.make(rootView, "Local profile updated to " + newName, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void propagateNameUpdatesAcrossRooms(String uid, String newName) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            // 1. Update RoomMemberEntity locally
            db.roomMemberDao().updateMemberDisplayName(uid, newName);

            // 2. Propagate to owned rooms locally
            db.roomDao().updateOwnerNameByUser(uid, newName);

            // 3. Propagate to Firestore if online
            if (FirebaseSyncManager.isFirebaseConfigured(this) && FirebaseSyncManager.isNetworkConnected(this)) {
                try {
                    FirebaseFirestore firestore = FirebaseFirestore.getInstance();
                    List<RoomEntity> allRooms = db.roomDao().getAllRoomsSync();
                    if (allRooms != null) {
                        for (RoomEntity r : allRooms) {
                            Map<String, Object> memberUpdate = new HashMap<>();
                            memberUpdate.put("userName", newName);
                            firestore.collection("rooms")
                                    .document(r.roomId)
                                    .collection("members")
                                    .document(uid)
                                    .set(memberUpdate, SetOptions.merge());

                            if (uid.equals(r.ownerUserId)) {
                                Map<String, Object> roomUpdate = new HashMap<>();
                                roomUpdate.put("ownerName", newName);
                                firestore.collection("rooms")
                                        .document(r.roomId)
                                        .set(roomUpdate, SetOptions.merge());
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void showChangePasswordDialog() {
        FirebaseUser user = null;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Exception ignored) {
        }
        if (user == null || user.getEmail() == null || user.getEmail().isEmpty()) {
            Snackbar.make(rootView, "Password changes are only available for cloud accounts.", Snackbar.LENGTH_LONG).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_user_password, null);
        TextInputLayout tilCurrent = dialogView.findViewById(R.id.tilCurrentPassword);
        TextInputEditText etCurrent = dialogView.findViewById(R.id.etCurrentPassword);
        TextInputLayout tilNew = dialogView.findViewById(R.id.tilNewPassword);
        TextInputEditText etNew = dialogView.findViewById(R.id.etNewPassword);
        TextInputLayout tilConfirm = dialogView.findViewById(R.id.tilConfirmNewPassword);
        TextInputEditText etConfirm = dialogView.findViewById(R.id.etConfirmNewPassword);
        LinearProgressIndicator progress = dialogView.findViewById(R.id.progressUserPassStrength);
        TextView tvLabel = dialogView.findViewById(R.id.tvUserPassStrengthLabel);
        TextView tvHint = dialogView.findViewById(R.id.tvUserPassPolicyHint);

        etNew.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String pass = s != null ? s.toString() : "";
                if (pass.isEmpty()) {
                    progress.setProgress(0);
                    tvLabel.setText("Weak");
                    tvLabel.setTextColor(ContextCompat.getColor(ProfileActivity.this, R.color.status_void_red));
                    progress.setIndicatorColor(ContextCompat.getColor(ProfileActivity.this, R.color.status_void_red));
                    tvHint.setText("Must include 6+ chars with uppercase, lowercase, and numbers");
                    return;
                }
                String err = RoomSecurityHelper.validatePasswordStrength(pass);
                String strength = RoomSecurityHelper.getPasswordStrengthLabel(pass);
                if ("Strong".equalsIgnoreCase(strength) && err == null) {
                    progress.setProgress(100);
                    tvLabel.setText("Strong");
                    tvLabel.setTextColor(ContextCompat.getColor(ProfileActivity.this, R.color.status_paid_green));
                    progress.setIndicatorColor(ContextCompat.getColor(ProfileActivity.this, R.color.status_paid_green));
                    tvHint.setText("Excellent password security");
                } else if ("Fair".equalsIgnoreCase(strength)) {
                    progress.setProgress(60);
                    tvLabel.setText("Fair");
                    tvLabel.setTextColor(ContextCompat.getColor(ProfileActivity.this, R.color.status_warning_amber));
                    progress.setIndicatorColor(ContextCompat.getColor(ProfileActivity.this, R.color.status_warning_amber));
                    tvHint.setText(err != null ? err : "Add uppercase, lowercase and numbers for maximum strength");
                } else {
                    progress.setProgress(25);
                    tvLabel.setText("Weak");
                    tvLabel.setTextColor(ContextCompat.getColor(ProfileActivity.this, R.color.status_void_red));
                    progress.setIndicatorColor(ContextCompat.getColor(ProfileActivity.this, R.color.status_void_red));
                    tvHint.setText(err != null ? err : "Min 6 chars with uppercase, lowercase, and numbers");
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        final FirebaseUser targetUser = user;
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Change Cloud Password")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Update Password", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String currentPass = etCurrent.getText() != null ? etCurrent.getText().toString().trim() : "";
                String newPass = etNew.getText() != null ? etNew.getText().toString().trim() : "";
                String confirmPass = etConfirm.getText() != null ? etConfirm.getText().toString().trim() : "";

                if (currentPass.isEmpty()) {
                    tilCurrent.setError("Enter your current password");
                    return;
                }
                tilCurrent.setError(null);

                if (newPass.length() < 6) {
                    tilNew.setError("Password must be at least 6 characters");
                    return;
                }
                tilNew.setError(null);

                if (!newPass.equals(confirmPass)) {
                    tilConfirm.setError("Passwords do not match");
                    return;
                }
                tilConfirm.setError(null);

                if (!FirebaseSyncManager.isNetworkConnected(ProfileActivity.this)) {
                    Snackbar.make(rootView, "Internet connection required to change cloud password.", Snackbar.LENGTH_LONG).show();
                    return;
                }

                loadingOverlay.show("Authenticating...", "Verifying current password");
                AuthCredential credential = EmailAuthProvider.getCredential(targetUser.getEmail(), currentPass);
                targetUser.reauthenticate(credential)
                        .addOnSuccessListener(unused -> {
                            loadingOverlay.show("Updating Password...", "Encrypting new credentials");
                            targetUser.updatePassword(newPass)
                                    .addOnSuccessListener(aVoid -> {
                                        loadingOverlay.hide();
                                        dialog.dismiss();
                                        new MaterialAlertDialogBuilder(ProfileActivity.this)
                                                .setTitle("Password Updated")
                                                .setMessage("Your cloud account password has been changed successfully.")
                                                .setPositiveButton("OK", null)
                                                .show();
                                    })
                                    .addOnFailureListener(e -> {
                                        loadingOverlay.hide();
                                        tilNew.setError(e.getMessage() != null ? e.getMessage() : "Failed to update password");
                                    });
                        })
                        .addOnFailureListener(e -> {
                            loadingOverlay.hide();
                            tilCurrent.setError("Incorrect current password");
                        });
            });
        });

        dialog.show();
    }

    private void handleSignOut() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Sign Out of Cloud Account?")
                .setMessage("You will be signed out of this officer account. All cached workspaces and offline records in your local database remain intact.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign Out", (d, w) -> {
                    sessionManager.signOut();
                    Intent intent = new Intent(ProfileActivity.this, AuthActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .show();
    }

    private String extractInitials(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "OF";
        }
        String clean = name.trim();
        String[] parts = clean.split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.US);
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase(Locale.US);
    }
}
