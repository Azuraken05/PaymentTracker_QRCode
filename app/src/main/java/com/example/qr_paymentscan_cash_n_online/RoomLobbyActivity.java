package com.example.qr_paymentscan_cash_n_online;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.data.RoomMemberEntity;
import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.ui.LoadingOverlayController;
import com.example.qr_paymentscan_cash_n_online.util.RoomSecurityHelper;
import com.example.qr_paymentscan_cash_n_online.util.SecuritySignatureHelper;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import androidx.activity.result.ActivityResultLauncher;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

public class RoomLobbyActivity extends BaseThemedActivity {

    public static final String EXTRA_FROM_LOGIN = "extra_from_login";

    private PaymentRepository repository;
    private UserSessionManager sessionManager;
    private TextView tvRoomLobbyUserSubtitle;
    private JoinedRoomsAdapter adapter;

    private ListenerRegistration pendingApprovalListener;
    private AlertDialog waitingApprovalDialog;
    private String listeningPendingRoomId;
    private LoadingOverlayController loadingOverlay;

    private TextInputEditText activeJoinKeyEditText;

    private final ActivityResultLauncher<ScanOptions> roomQrScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null && !result.getContents().trim().isEmpty()) {
                    String raw = result.getContents().trim();
                    String key = raw;
                    if (raw.startsWith("ROOM_INVITE|")) {
                        key = raw.substring("ROOM_INVITE|".length()).trim();
                    }
                    if (activeJoinKeyEditText != null) {
                        activeJoinKeyEditText.setText(key);
                        activeJoinKeyEditText.setSelection(key.length());
                    }
                    Toast.makeText(this, "Room Key scanned: " + key, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room_lobby);

        repository = new PaymentRepository(this);
        sessionManager = UserSessionManager.getInstance(this);
        loadingOverlay = new LoadingOverlayController(this);

        View root = findViewById(R.id.roomLobbyRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ImageButton btnBack = findViewById(R.id.btnBackRoomLobby);
        btnBack.setOnClickListener(v -> finish());

        tvRoomLobbyUserSubtitle = findViewById(R.id.tvRoomLobbyUserSubtitle);
        MaterialButton btnOpenAuthProfile = findViewById(R.id.btnOpenAuthProfile);
        MaterialButton btnCreatePrivateRoom = findViewById(R.id.btnCreatePrivateRoom);
        MaterialButton btnJoinPrivateRoom = findViewById(R.id.btnJoinPrivateRoom);
        RecyclerView rvJoinedRooms = findViewById(R.id.rvJoinedRooms);

        btnOpenAuthProfile.setOnClickListener(v -> {
            Class<?> targetActivity = sessionManager.isSignedIn() ? ProfileActivity.class : AuthActivity.class;
            startActivity(new Intent(this, targetActivity));
        });
        btnCreatePrivateRoom.setOnClickListener(v -> showCreatePrivateRoomDialog());
        btnJoinPrivateRoom.setOnClickListener(v -> showJoinPrivateRoomDialog());

        adapter = new JoinedRoomsAdapter(room -> repository.switchActiveRoom(room.roomId, new PaymentRepository.ResultCallback<RoomEntity>() {
            @Override
            public void onSuccess(RoomEntity result) {
                adapter.notifyDataSetChanged();
                refreshUserSubtitle();
                if (sessionManager.isPendingApproval()) {
                    showWaitingForOwnerApprovalDialog(result);
                } else {
                    stopPendingApprovalListener();
                    Toast.makeText(
                            RoomLobbyActivity.this,
                            "Switched to room: " + result.roomName + " (" + sessionManager.getActiveRoomRole() + ")",
                            Toast.LENGTH_SHORT
                    ).show();
                    Intent intent = new Intent(RoomLobbyActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(RoomLobbyActivity.this, message, Toast.LENGTH_LONG).show();
            }
        }));
        rvJoinedRooms.setLayoutManager(new LinearLayoutManager(this));
        rvJoinedRooms.setAdapter(adapter);

        repository.observeAllRooms().observe(this, rooms -> {
            adapter.submitList(rooms != null ? rooms : new ArrayList<>());
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        sessionManager.syncFromFirebaseAuthIfAvailable();
        refreshUserSubtitle();
        adapter.notifyDataSetChanged();

        // Refresh cloud workspaces in background
        com.example.qr_paymentscan_cash_n_online.util.RoomDiscoveryManager.discoverRooms(this, null);

        // If the active room is currently waiting for Owner approval, attach real-time listener
        if (sessionManager.isPendingApproval()) {
            String activeRoomId = sessionManager.getActiveRoomId();
            if (activeRoomId != null && !activeRoomId.isEmpty()) {
                attachPendingApprovalSnapshotListener(activeRoomId, sessionManager.getActiveRoomName());
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (loadingOverlay != null) {
            loadingOverlay.release();
        }
        stopPendingApprovalListener();
        if (waitingApprovalDialog != null && waitingApprovalDialog.isShowing()) {
            waitingApprovalDialog.dismiss();
        }
        super.onDestroy();
    }

    private void refreshUserSubtitle() {
        String statusSuffix = sessionManager.isPendingApproval()
                ? " (PENDING OWNER APPROVAL)"
                : "";
        tvRoomLobbyUserSubtitle.setText("Officer: " + sessionManager.getCurrentUserName()
                + " • Active Role: " + sessionManager.getActiveRoomRole() + statusSuffix);
    }

    private void showCreatePrivateRoomDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_room, null);
        TextInputLayout tilRoomName = dialogView.findViewById(R.id.tilCreateRoomName);
        TextInputEditText etRoomName = dialogView.findViewById(R.id.etCreateRoomName);
        TextInputLayout tilRoomPassword = dialogView.findViewById(R.id.tilCreateRoomPassword);
        TextInputEditText etRoomPassword = dialogView.findViewById(R.id.etCreateRoomPassword);
        TextInputLayout tilConfirmPassword = dialogView.findViewById(R.id.tilCreateRoomConfirmPassword);
        TextInputEditText etConfirmPassword = dialogView.findViewById(R.id.etCreateRoomConfirmPassword);
        LinearProgressIndicator progressStrength = dialogView.findViewById(R.id.progressPasswordStrength);
        TextView tvStrengthLabel = dialogView.findViewById(R.id.tvPasswordStrengthLabel);
        TextView tvPolicyHint = dialogView.findViewById(R.id.tvPasswordPolicyHint);
        CheckBox cbRequireApproval = dialogView.findViewById(R.id.cbRequireApproval);

        etRoomPassword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String pass = s != null ? s.toString() : "";
                if (pass.isEmpty()) {
                    progressStrength.setProgress(0);
                    tvStrengthLabel.setText("Weak");
                    tvStrengthLabel.setTextColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_void_red));
                    progressStrength.setIndicatorColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_void_red));
                    tvPolicyHint.setText("Must include 8+ chars with uppercase, lowercase, and numbers");
                    return;
                }
                String err = RoomSecurityHelper.validatePasswordStrength(pass);
                String strength = RoomSecurityHelper.getPasswordStrengthLabel(pass);
                if ("Strong".equalsIgnoreCase(strength) && err == null) {
                    progressStrength.setProgress(100);
                    tvStrengthLabel.setText("Strong");
                    tvStrengthLabel.setTextColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_paid_green));
                    progressStrength.setIndicatorColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_paid_green));
                    tvPolicyHint.setText("PBKDF2-SHA256 Ready • Excellent security");
                } else if ("Fair".equalsIgnoreCase(strength)) {
                    progressStrength.setProgress(60);
                    tvStrengthLabel.setText("Fair");
                    tvStrengthLabel.setTextColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_warning_amber));
                    progressStrength.setIndicatorColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_warning_amber));
                    tvPolicyHint.setText(err != null ? err : "Add uppercase, lowercase and numbers for maximum strength");
                } else {
                    progressStrength.setProgress(25);
                    tvStrengthLabel.setText("Weak");
                    tvStrengthLabel.setTextColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_void_red));
                    progressStrength.setIndicatorColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_void_red));
                    tvPolicyHint.setText(err != null ? err : "Min 8 chars with uppercase, lowercase, and numbers");
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Create Hardened Private Room")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create Room", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etRoomName.getText() != null ? etRoomName.getText().toString().trim() : "";
            String pass = etRoomPassword.getText() != null ? etRoomPassword.getText().toString().trim() : "";
            String confirmPass = etConfirmPassword.getText() != null ? etConfirmPassword.getText().toString().trim() : "";

            if (name.isEmpty()) {
                tilRoomName.setError("Room name is required");
                return;
            }
            tilRoomName.setError(null);

            String passErr = RoomSecurityHelper.validatePasswordStrength(pass);
            if (passErr != null) {
                tilRoomPassword.setError(passErr);
                return;
            }
            tilRoomPassword.setError(null);

            if (!pass.equals(confirmPass)) {
                tilConfirmPassword.setError("Passwords do not match");
                return;
            }
            tilConfirmPassword.setError(null);

            boolean requireApproval = cbRequireApproval.isChecked();
            loadingOverlay.show("Creating Private Room...", "Generating PBKDF2 salt and HMAC key");
            repository.createPrivateRoom(name, pass, requireApproval, new PaymentRepository.ResultCallback<RoomEntity>() {
                @Override
                public void onSuccess(RoomEntity room) {
                    loadingOverlay.hide();
                    dialog.dismiss();
                    new MaterialAlertDialogBuilder(RoomLobbyActivity.this)
                            .setTitle("Private Room Created!")
                            .setMessage("Room Name: " + room.roomName
                                    + "\nRoom Key: " + room.roomKey
                                    + "\nYour Role: OWNER"
                                    + "\nOwner Approval Gate: " + (room.requireApproval ? "ENABLED" : "OFF")
                                    + "\n\nShare the Room Key and Password with trusted collectors or auditors from the Members screen.")
                            .setPositiveButton("Open Workspace", (dlg, which) -> finish())
                            .show();
                }

                @Override
                public void onError(String message) {
                    loadingOverlay.hide();
                    tilRoomPassword.setError(message);
                }
            });
        }));

        dialog.show();
    }

    private void showJoinPrivateRoomDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_join_room, null);
        TextInputLayout tilKey = dialogView.findViewById(R.id.tilJoinRoomKey);
        TextInputEditText etKey = dialogView.findViewById(R.id.etJoinRoomKey);
        MaterialButton btnScanQr = dialogView.findViewById(R.id.btnScanRoomQr);
        TextInputLayout tilPass = dialogView.findViewById(R.id.tilJoinRoomPassword);
        TextInputEditText etPass = dialogView.findViewById(R.id.etJoinRoomPassword);

        activeJoinKeyEditText = etKey;

        btnScanQr.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setPrompt("Align Room Invite QR code inside viewfinder");
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setOrientationLocked(false);
            options.setBeepEnabled(true);
            roomQrScanLauncher.launch(options);
        });

        AlertDialog joinDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Join Private Room")
                .setView(dialogView)
                .setNegativeButton("Cancel", (d, w) -> activeJoinKeyEditText = null)
                .setPositiveButton("Verify & Join", null)
                .create();

        joinDialog.setOnDismissListener(d -> activeJoinKeyEditText = null);

        joinDialog.setOnShowListener(d -> {
            final MaterialButton positiveBtn = (MaterialButton) joinDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            joinDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String key = etKey.getText() != null ? etKey.getText().toString().trim() : "";
                String pass = etPass.getText() != null ? etPass.getText().toString().trim() : "";
                if (key.isEmpty()) {
                    tilKey.setError("Room Key is required");
                    return;
                }
                tilKey.setError(null);
                if (pass.isEmpty()) {
                    tilPass.setError("Room Password is required");
                    return;
                }
                tilPass.setError(null);

                if (positiveBtn != null) {
                    positiveBtn.setEnabled(false);
                }
                loadingOverlay.show("Connecting to Room...", "Verifying credentials and membership");
                verifyAndSubmitRoomJoinRequest(
                        key,
                        pass,
                        joinDialog,
                        () -> {
                            loadingOverlay.hide();
                            if (positiveBtn != null) {
                                positiveBtn.setEnabled(true);
                            }
                        }
                );
            });
        });

        joinDialog.show();
    }

    /**
     * End-to-End Room Join & Credential Verification:
     * 1. Looks up the room by roomKey in Firestore (or local Room SQLite if offline).
     * 2. Validates the entered password against rooms/{roomId}/private/security first;
     *    if missing or empty, falls back to roomPasswordHash & roomSalt (including empty "")
     *    on the parent rooms/{roomId} document.
     * 3. On mismatch, immediately shows "Invalid Room Key or Password. Please try again."
     *    with zero cooldown/lockout.
     * 4. When credentials match, writes rooms/{roomId}/members/{currentUserId} directly to
     *    Firestore with .set(memberMap, SetOptions.merge()) and only saves local PENDING_APPROVAL
     *    state + shows "Waiting for Owner Approval" inside .addOnSuccessListener(...).
     */
    private void verifyAndSubmitRoomJoinRequest(
            String inputRoomKey,
            String inputRawPassword,
            AlertDialog joinDialog,
            Runnable onEnableButton
    ) {
        final String cleanKey = inputRoomKey.trim().toUpperCase(Locale.US);
        final String cleanPass = inputRawPassword.trim();

        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                sessionManager.syncFromFirebaseAuthIfAvailable();
                sessionManager.ensureLocalFallbackUserIfNeeded();

                FirebaseUser currentUser = null;
                try {
                    currentUser = FirebaseAuth.getInstance().getCurrentUser();
                } catch (Exception ignored) {
                }

                final String currentUserId = (currentUser != null)
                        ? currentUser.getUid()
                        : sessionManager.getCurrentUserId();
                final String currentUserEmail = (currentUser != null && currentUser.getEmail() != null)
                        ? currentUser.getEmail()
                        : sessionManager.getCurrentUserEmail();

                String resolvedName = (currentUser != null && currentUser.getDisplayName() != null)
                        ? currentUser.getDisplayName().trim()
                        : "";
                if (resolvedName.isEmpty()) {
                    String sessionName = sessionManager.getCurrentUserName();
                    if (sessionName != null && !sessionName.trim().isEmpty()
                            && !"Class Treasurer".equalsIgnoreCase(sessionName.trim())
                            && !"Treasurer".equalsIgnoreCase(sessionName.trim())) {
                        resolvedName = sessionName.trim();
                    } else if (currentUserEmail != null && currentUserEmail.contains("@")) {
                        resolvedName = currentUserEmail.substring(0, currentUserEmail.indexOf('@'));
                    } else if (sessionName != null && !sessionName.trim().isEmpty()) {
                        resolvedName = sessionName.trim();
                    } else {
                        resolvedName = "Collector";
                    }
                }
                final String currentUserName = resolvedName;
                final String deviceInfo = Build.MANUFACTURER + " " + Build.MODEL;
                final long now = System.currentTimeMillis();

                RoomEntity matchedRoom = repository.findLocalRoomByKeySync(cleanKey);
                String privatePasswordHash = null;
                String privateSalt = null;
                String privateSigningSecret = null;
                String parentPasswordHash = matchedRoom != null ? matchedRoom.roomPasswordHash : null;
                String parentSalt = matchedRoom != null ? matchedRoom.roomSalt : null;
                String remoteMemberStatus = null;
                String remoteMemberRole = null;

                boolean online = FirebaseSyncManager.isFirebaseConfigured(RoomLobbyActivity.this)
                        && FirebaseSyncManager.isNetworkConnected(RoomLobbyActivity.this);

                if (online) {
                    FirebaseFirestore firestore = FirebaseFirestore.getInstance();
                    QuerySnapshot query = Tasks.await(
                            firestore.collection("rooms")
                                    .whereEqualTo("roomKey", cleanKey)
                                    .limit(1)
                                    .get(),
                            12,
                            TimeUnit.SECONDS
                    );
                    if (query != null && !query.isEmpty()) {
                        DocumentSnapshot doc = query.getDocuments().get(0);
                        String rId = doc.getString("roomId");
                        if (rId == null || rId.isEmpty()) {
                            rId = doc.getId();
                        }
                        String rName = doc.getString("roomName");
                        String rKey = doc.getString("roomKey");
                        parentPasswordHash = doc.getString("roomPasswordHash");
                        parentSalt = doc.getString("roomSalt");
                        String rSecret = doc.getString("roomSigningSecret");
                        String rOwnerId = doc.getString("ownerUserId");
                        String rOwnerName = doc.getString("ownerName");
                        Boolean rLocked = doc.getBoolean("isLocked");
                        Boolean rApproval = doc.getBoolean("requireApproval");
                        Long rExpires = doc.getLong("keyExpiresAt");
                        Long cAt = doc.getLong("createdAt");
                        Long uAt = doc.getLong("updatedAt");

                        // 1. Check rooms/{roomId}/private/security first
                        try {
                            DocumentSnapshot secDoc = Tasks.await(
                                    firestore.collection("rooms")
                                            .document(rId)
                                            .collection("private")
                                            .document("security")
                                            .get(),
                                    8,
                                    TimeUnit.SECONDS
                            );
                            if (secDoc != null && secDoc.exists()) {
                                privatePasswordHash = secDoc.getString("roomPasswordHash");
                                privateSalt = secDoc.getString("roomSalt");
                                privateSigningSecret = secDoc.getString("roomSigningSecret");
                            }
                        } catch (Exception ignored) {
                        }

                        // 2. Check existing member document in rooms/{roomId}/members/{currentUserId}
                        try {
                            DocumentSnapshot memDoc = Tasks.await(
                                    firestore.collection("rooms")
                                            .document(rId)
                                            .collection("members")
                                            .document(currentUserId)
                                            .get(),
                                    8,
                                    TimeUnit.SECONDS
                            );
                            if (memDoc != null && memDoc.exists()) {
                                remoteMemberStatus = memDoc.getString("status");
                                remoteMemberRole = memDoc.getString("role");
                            }
                        } catch (Exception ignored) {
                        }

                        String effectiveHash = (privatePasswordHash != null && !privatePasswordHash.trim().isEmpty())
                                ? privatePasswordHash
                                : (parentPasswordHash != null ? parentPasswordHash : "");
                        String effectiveSalt = (privatePasswordHash != null && !privatePasswordHash.trim().isEmpty())
                                ? privateSalt
                                : parentSalt;
                        String effectiveSecret = (privateSigningSecret != null && !privateSigningSecret.trim().isEmpty())
                                ? privateSigningSecret
                                : (rSecret != null ? rSecret : SecuritySignatureHelper.getOrCreateSecretKey(RoomLobbyActivity.this));

                        matchedRoom = new RoomEntity(
                                rId,
                                rName != null ? rName : "Shared Room",
                                rKey != null ? rKey : cleanKey,
                                effectiveHash,
                                effectiveSecret,
                                rOwnerId != null ? rOwnerId : "",
                                rOwnerName != null ? rOwnerName : "Owner",
                                cAt != null ? cAt : now,
                                uAt != null ? uAt : now,
                                effectiveSalt,
                                rLocked != null && rLocked,
                                rApproval == null || rApproval,
                                rExpires != null ? rExpires : 0L,
                                0,
                                0L
                        );
                    }
                }

                if (matchedRoom == null) {
                    runOnUiThread(() -> {
                        onEnableButton.run();
                        Toast.makeText(
                                RoomLobbyActivity.this,
                                "Invalid Room Key or Password. Please try again.",
                                Toast.LENGTH_LONG
                        ).show();
                    });
                    return;
                }

                // Validate password checking rooms/{roomId}/private/security first, then parent rooms/{roomId}
                boolean passwordValid = RoomSecurityHelper.verifyRoomPasswordWithFallback(
                        cleanPass,
                        privatePasswordHash,
                        privateSalt,
                        parentPasswordHash,
                        parentSalt
                );
                if (!passwordValid) {
                    runOnUiThread(() -> {
                        onEnableButton.run();
                        Toast.makeText(
                                RoomLobbyActivity.this,
                                "Invalid Room Key or Password. Please try again.",
                                Toast.LENGTH_LONG
                        ).show();
                    });
                    return;
                }

                final RoomEntity verifiedRoom = matchedRoom;
                final boolean isOwnerAccount = currentUserId.equals(verifiedRoom.ownerUserId);
                RoomMemberEntity localMember = repository.findLocalMemberSync(verifiedRoom.roomId, currentUserId);

                boolean isBanned = (localMember != null && localMember.isBanned())
                        || RoomMemberEntity.STATUS_BANNED.equalsIgnoreCase(remoteMemberStatus);
                if (isBanned && !isOwnerAccount) {
                    runOnUiThread(() -> {
                        onEnableButton.run();
                        Toast.makeText(
                                RoomLobbyActivity.this,
                                "Access Denied: Your account has been banned from this room by the Owner.",
                                Toast.LENGTH_LONG
                        ).show();
                    });
                    return;
                }

                boolean alreadyActive = (localMember != null && localMember.isActive())
                        || RoomMemberEntity.STATUS_ACTIVE.equalsIgnoreCase(remoteMemberStatus);

                if (verifiedRoom.isLocked && !isOwnerAccount && !alreadyActive) {
                    runOnUiThread(() -> {
                        onEnableButton.run();
                        Toast.makeText(
                                RoomLobbyActivity.this,
                                "This room is currently LOCKED by the Room Owner. New joins are blocked.",
                                Toast.LENGTH_LONG
                        ).show();
                    });
                    return;
                }

                if (verifiedRoom.isKeyExpired(now) && !isOwnerAccount && !alreadyActive) {
                    runOnUiThread(() -> {
                        onEnableButton.run();
                        Toast.makeText(
                                RoomLobbyActivity.this,
                                "This Room Invite Key has expired. Ask the Room Owner to renew or rotate the Room Key.",
                                Toast.LENGTH_LONG
                        ).show();
                    });
                    return;
                }

                final String assignedRole;
                final String memberStatus;
                if (isOwnerAccount) {
                    assignedRole = RoomMemberEntity.ROLE_OWNER;
                    memberStatus = RoomMemberEntity.STATUS_ACTIVE;
                } else if (alreadyActive) {
                    assignedRole = (remoteMemberRole != null && !remoteMemberRole.trim().isEmpty())
                            ? remoteMemberRole.trim().toUpperCase(Locale.US)
                            : RoomMemberEntity.ROLE_COLLECTOR;
                    memberStatus = RoomMemberEntity.STATUS_ACTIVE;
                } else {
                    assignedRole = RoomMemberEntity.ROLE_COLLECTOR;
                    memberStatus = verifiedRoom.requireApproval
                            ? RoomMemberEntity.STATUS_PENDING_APPROVAL
                            : RoomMemberEntity.STATUS_ACTIVE;
                }

                final RoomMemberEntity memberEntity = new RoomMemberEntity(
                        verifiedRoom.roomId,
                        currentUserId,
                        currentUserName,
                        currentUserEmail != null ? currentUserEmail : "",
                        assignedRole,
                        now,
                        memberStatus,
                        deviceInfo
                );

                if (online) {
                    Map<String, Object> memberMap = new HashMap<>();
                    memberMap.put("userId", currentUserId);
                    memberMap.put("userName", currentUserName);
                    memberMap.put("userEmail", currentUserEmail != null ? currentUserEmail : "");
                    memberMap.put("role", assignedRole);
                    memberMap.put("status", memberStatus);
                    memberMap.put("roomId", verifiedRoom.roomId);
                    memberMap.put("joinedAt", now);
                    memberMap.put("deviceInfo", deviceInfo);

                    FirebaseFirestore.getInstance()
                            .collection("rooms")
                            .document(verifiedRoom.roomId)
                            .collection("members")
                            .document(currentUserId)
                            .set(memberMap, SetOptions.merge())
                            .addOnSuccessListener(unused -> repository.saveJoinedRoomAndMemberLocally(
                                    verifiedRoom,
                                    memberEntity,
                                    new PaymentRepository.ResultCallback<RoomEntity>() {
                                        @Override
                                        public void onSuccess(RoomEntity room) {
                                            loadingOverlay.hide();
                                            if (joinDialog.isShowing()) {
                                                joinDialog.dismiss();
                                            }
                                            refreshUserSubtitle();
                                            adapter.notifyDataSetChanged();
                                            if (RoomMemberEntity.STATUS_PENDING_APPROVAL.equalsIgnoreCase(memberStatus)) {
                                                showWaitingForOwnerApprovalDialog(room);
                                            } else {
                                                Toast.makeText(
                                                        RoomLobbyActivity.this,
                                                        "Joined \"" + room.roomName + "\" as " + assignedRole,
                                                        Toast.LENGTH_LONG
                                                ).show();
                                                Intent intent = new Intent(RoomLobbyActivity.this, MainActivity.class);
                                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                                startActivity(intent);
                                                finish();
                                            }
                                        }

                                        @Override
                                        public void onError(String message) {
                                            onEnableButton.run();
                                            Toast.makeText(RoomLobbyActivity.this, message, Toast.LENGTH_LONG).show();
                                        }
                                    }
                            ))
                            .addOnFailureListener(e -> {
                                onEnableButton.run();
                                Toast.makeText(
                                        RoomLobbyActivity.this,
                                        "Failed to write join request to Firestore: " + e.getMessage(),
                                        Toast.LENGTH_LONG
                                ).show();
                            });
                } else {
                    repository.saveJoinedRoomAndMemberLocally(
                            verifiedRoom,
                            memberEntity,
                            new PaymentRepository.ResultCallback<RoomEntity>() {
                                @Override
                                public void onSuccess(RoomEntity room) {
                                    if (joinDialog.isShowing()) {
                                        joinDialog.dismiss();
                                    }
                                    refreshUserSubtitle();
                                    adapter.notifyDataSetChanged();
                                    if (RoomMemberEntity.STATUS_PENDING_APPROVAL.equalsIgnoreCase(memberStatus)) {
                                        showWaitingForOwnerApprovalDialog(room);
                                    } else {
                                        Toast.makeText(
                                                RoomLobbyActivity.this,
                                                "Joined \"" + room.roomName + "\" as " + assignedRole,
                                                Toast.LENGTH_LONG
                                        ).show();
                                        Intent intent = new Intent(RoomLobbyActivity.this, MainActivity.class);
                                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                        startActivity(intent);
                                        finish();
                                    }
                                }

                                @Override
                                public void onError(String message) {
                                    onEnableButton.run();
                                    Toast.makeText(RoomLobbyActivity.this, message, Toast.LENGTH_LONG).show();
                                }
                            }
                    );
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    onEnableButton.run();
                    Toast.makeText(
                            RoomLobbyActivity.this,
                            "Failed to verify room credentials: " + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void showWaitingForOwnerApprovalDialog(RoomEntity room) {
        attachPendingApprovalSnapshotListener(room.roomId, room.roomName);

        if (waitingApprovalDialog != null && waitingApprovalDialog.isShowing()) {
            waitingApprovalDialog.dismiss();
        }
        waitingApprovalDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Waiting for Owner Approval")
                .setMessage("Your password was verified for \"" + room.roomName + "\", and your join request has been sent to Room Owner (" + room.ownerName + ").\n\n"
                        + "Keep this screen open or wait in the lobby — the moment the Owner approves your request, this room will unlock and open automatically.")
                .setPositiveButton("Stay in Lobby", null)
                .create();
        waitingApprovalDialog.show();
    }

    /**
     * Attaches a real-time Firestore addSnapshotListener on rooms/{roomId}/members/{currentUserId}.
     * The moment status changes from "PENDING_APPROVAL" to "ACTIVE", updates local Room SQLite,
     * shows "Your join request has been approved!", and automatically opens the room.
     */
    private void attachPendingApprovalSnapshotListener(String roomId, String roomName) {
        if (roomId == null || roomId.trim().isEmpty()) {
            return;
        }
        if (!FirebaseSyncManager.isFirebaseConfigured(this)) {
            return;
        }
        if (roomId.equals(listeningPendingRoomId) && pendingApprovalListener != null) {
            return;
        }
        stopPendingApprovalListener();
        listeningPendingRoomId = roomId;

        sessionManager.syncFromFirebaseAuthIfAvailable();
        FirebaseUser currentUser = null;
        try {
            currentUser = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Exception ignored) {
        }
        final String currentUserId = (currentUser != null)
                ? currentUser.getUid()
                : sessionManager.getCurrentUserId();

        try {
            pendingApprovalListener = FirebaseFirestore.getInstance()
                    .collection("rooms")
                    .document(roomId)
                    .collection("members")
                    .document(currentUserId)
                    .addSnapshotListener((snapshot, error) -> {
                        if (error != null || snapshot == null) {
                            return;
                        }
                        if (!snapshot.exists()) {
                            return;
                        }
                        String status = snapshot.getString("status");
                        String role = snapshot.getString("role");
                        if (RoomMemberEntity.STATUS_ACTIVE.equalsIgnoreCase(status)) {
                            stopPendingApprovalListener();
                            repository.activateApprovedMembershipLocally(
                                    roomId,
                                    currentUserId,
                                    role != null ? role : RoomMemberEntity.ROLE_COLLECTOR,
                                    null,
                                    new PaymentRepository.ResultCallback<RoomEntity>() {
                                        @Override
                                        public void onSuccess(RoomEntity approvedRoom) {
                                            if (isFinishing() || isDestroyed()) {
                                                return;
                                            }
                                            if (waitingApprovalDialog != null && waitingApprovalDialog.isShowing()) {
                                                waitingApprovalDialog.dismiss();
                                            }
                                            refreshUserSubtitle();
                                            adapter.notifyDataSetChanged();
                                            Toast.makeText(
                                                    RoomLobbyActivity.this,
                                                    "Your join request has been approved!",
                                                    Toast.LENGTH_LONG
                                            ).show();
                                            Intent intent = new Intent(RoomLobbyActivity.this, MainActivity.class);
                                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                            startActivity(intent);
                                            finish();
                                        }

                                        @Override
                                        public void onError(String message) {
                                            Toast.makeText(RoomLobbyActivity.this, message, Toast.LENGTH_LONG).show();
                                        }
                                    }
                            );
                        } else if (RoomMemberEntity.STATUS_BANNED.equalsIgnoreCase(status)) {
                            stopPendingApprovalListener();
                            if (waitingApprovalDialog != null && waitingApprovalDialog.isShowing()) {
                                waitingApprovalDialog.dismiss();
                            }
                            Toast.makeText(
                                    RoomLobbyActivity.this,
                                    "Your join request for \"" + roomName + "\" was denied.",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private void stopPendingApprovalListener() {
        if (pendingApprovalListener != null) {
            try {
                pendingApprovalListener.remove();
            } catch (Exception ignored) {
            }
            pendingApprovalListener = null;
        }
        listeningPendingRoomId = null;
    }

    private class JoinedRoomsAdapter extends RecyclerView.Adapter<JoinedRoomsAdapter.RoomViewHolder> {

        private final List<RoomEntity> items = new ArrayList<>();
        private final OnRoomClickListener listener;

        JoinedRoomsAdapter(OnRoomClickListener listener) {
            this.listener = listener;
        }

        void submitList(List<RoomEntity> newList) {
            items.clear();
            items.addAll(newList);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RoomViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_room_card, parent, false);
            return new RoomViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RoomViewHolder holder, int position) {
            RoomEntity room = items.get(position);
            holder.tvName.setText(room.roomName);
            String lockStatus = room.isLocked ? " • LOCKED" : "";
            holder.tvKeyAndOwner.setText("Room Key: " + room.roomKey + " • Owner: " + room.ownerName + lockStatus);

            boolean isActive = room.roomId.equals(sessionManager.getActiveRoomId());
            if (isActive) {
                if (sessionManager.isPendingApproval()) {
                    holder.tvBadge.setText("WAITING FOR OWNER APPROVAL");
                } else {
                    holder.tvBadge.setText("ACTIVE • " + sessionManager.getActiveRoomRole());
                }
                holder.tvBadge.setVisibility(View.VISIBLE);
            } else {
                holder.tvBadge.setText("TAP TO SWITCH");
                holder.tvBadge.setVisibility(View.VISIBLE);
            }
            holder.itemView.setOnClickListener(v -> listener.onRoomClicked(room));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class RoomViewHolder extends RecyclerView.ViewHolder {
            final TextView tvName;
            final TextView tvKeyAndOwner;
            final TextView tvBadge;

            RoomViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvItemRoomName);
                tvKeyAndOwner = itemView.findViewById(R.id.tvItemRoomKeyAndOwner);
                tvBadge = itemView.findViewById(R.id.tvItemRoomActiveBadge);
            }
        }
    }

    private interface OnRoomClickListener {
        void onRoomClicked(RoomEntity room);
    }
}
