package com.example.qr_paymentscan_cash_n_online;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
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

import com.example.qr_paymentscan_cash_n_online.data.AuditLogEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.data.RoomMemberEntity;
import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;
import com.example.qr_paymentscan_cash_n_online.sync.FirestoreRealtimeSyncManager;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.ui.LoadingOverlayController;
import com.example.qr_paymentscan_cash_n_online.util.AppLockManager;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.RoomDeletionManager;
import com.example.qr_paymentscan_cash_n_online.util.RoomSecurityHelper;
import com.example.qr_paymentscan_cash_n_online.util.SecuritySignatureHelper;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class RoomMembersActivity extends BaseThemedActivity {

    private PaymentRepository repository;
    private UserSessionManager sessionManager;
    private String activeRoomId;
    private RoomEntity currentRoom;

    private View rootView;
    private TextView tvMembersRoomTitle;
    private TextView tvMembersMyRoleSubtitle;

    // Security Hero Card
    private ImageView ivSecurityPostureIcon;
    private TextView tvSecurityPostureTitle;
    private TextView tvSecurityPostureSubtitle;
    private TextView tvCountActiveMembers;
    private LinearLayout layoutSummaryPendingBadge;
    private TextView tvCountPendingRequests;
    private TextView tvCountSecurityAlerts;

    // Room Key & Invite
    private TextView tvInviteRoomKey;
    private MaterialButton btnCopyInviteKey;
    private MaterialButton btnShareInvite;
    private MaterialButton btnShowRoomQr;

    // Room Profile & Branding Card
    private MaterialCardView cardRoomProfileSettings;
    private TextView tvRoomProfileOwnerBadge;
    private TextInputLayout tilSettingsRoomName;
    private TextInputEditText etSettingsRoomName;
    private TextInputLayout tilSettingsOrgSubtitle;
    private TextInputEditText etSettingsOrgSubtitle;
    private TextInputLayout tilSettingsReceiptPrefix;
    private TextInputEditText etSettingsReceiptPrefix;
    private TextInputLayout tilSettingsReceiptFooter;
    private TextInputEditText etSettingsReceiptFooter;
    private MaterialButton btnSaveRoomProfile;

    // Collection & Member Setup Card
    private MaterialCardView cardCollectionSetup;
    private ChipGroup chipGroupPaymentChannels;
    private Chip chipModeCash, chipModeGcash, chipModeMaya, chipModeGoTyme, chipModeBank;
    private MaterialSwitch switchRequireProofOnline;
    private ChipGroup chipGroupDefaultRole;
    private Chip chipDefaultAuditor, chipDefaultCollector;
    private MaterialSwitch switchArchiveRoom;

    // Access Controls Card
    private MaterialCardView cardAccessControlToggles;
    private MaterialSwitch switchRequireApproval;
    private MaterialSwitch switchEmergencyLock;
    private ChipGroup chipGroupInviteExpiration;
    private Chip chipExpNone, chipExp1h, chipExp24h, chipExp7d;
    private TextView tvInviteExpirationCountdown;

    // Credentials Card
    private MaterialCardView cardCredentialProtection;
    private MaterialButton btnRotateRoomPassword;
    private MaterialButton btnRotateSigningSecret;
    private MaterialButton btnViewSecurityAuditLogs;
    private MaterialSwitch switchBiometricAppLock;

    // Pending Section
    private MaterialCardView cardPendingJoinRequestsSection;
    private TextView tvPendingRequestsHeader;
    private LinearLayout layoutPendingRequestsContainer;

    // Member Management Directory
    private TextInputEditText etSearchMembers;
    private ChipGroup chipGroupMemberFilters;
    private LinearLayout layoutEmptyMembers;

    // Danger Zone
    private MaterialCardView cardDangerZone;
    private MaterialButton btnDeleteRoomPermanent;

    private MembersAdapter adapter;
    private LoadingOverlayController loadingOverlay;

    private ListenerRegistration liveMembersListener;
    private final List<RoomMemberEntity> rawMembersList = new ArrayList<>();
    private String currentSearchQuery = "";
    private String currentFilter = "ALL";
    private boolean isUpdatingSwitches = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room_members);

        repository = new PaymentRepository(this);
        sessionManager = UserSessionManager.getInstance(this);
        sessionManager.syncFromFirebaseAuthIfAvailable();
        activeRoomId = sessionManager.getActiveRoomId();
        loadingOverlay = new LoadingOverlayController(this);

        rootView = findViewById(R.id.roomMembersRoot);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ImageButton btnBack = findViewById(R.id.btnBackRoomMembers);
        btnBack.setOnClickListener(v -> finish());

        initViews();
        setupClickListeners();

        adapter = new MembersAdapter(
                this::showMemberRoleActionDialog,
                this::showQuickApproveRolePickerDialog,
                member -> rejectOrBanMember(member, false),
                member -> rejectOrBanMember(member, true)
        );
        RecyclerView rvRoomMembers = findViewById(R.id.rvRoomMembers);
        rvRoomMembers.setLayoutManager(new LinearLayoutManager(this));
        rvRoomMembers.setAdapter(adapter);

        refreshRoomHeader();

        if (activeRoomId != null && !activeRoomId.isEmpty()) {
            repository.observeRoomById(activeRoomId).observe(this, room -> {
                currentRoom = room;
                refreshRoomHeader();
            });
            repository.observeMembersByRoom(activeRoomId).observe(this, members -> {
                rawMembersList.clear();
                if (members != null) {
                    rawMembersList.addAll(members);
                }
                updateMembersUi(rawMembersList);
                refreshRoomHeader();
            });
            repository.observeAllAuditLogs().observe(this, logs -> {
                int securityEvents = 0;
                if (logs != null) {
                    for (AuditLogEntity log : logs) {
                        if (log.actionType != null && log.actionType.startsWith("SECURITY")) {
                            securityEvents++;
                        }
                    }
                }
                if (tvCountSecurityAlerts != null) {
                    tvCountSecurityAlerts.setText(String.valueOf(securityEvents));
                }
            });
        }
    }

    private void initViews() {
        tvMembersRoomTitle = findViewById(R.id.tvMembersRoomTitle);
        tvMembersMyRoleSubtitle = findViewById(R.id.tvMembersMyRoleSubtitle);

        ivSecurityPostureIcon = findViewById(R.id.ivSecurityPostureIcon);
        tvSecurityPostureTitle = findViewById(R.id.tvSecurityPostureTitle);
        tvSecurityPostureSubtitle = findViewById(R.id.tvSecurityPostureSubtitle);
        tvCountActiveMembers = findViewById(R.id.tvCountActiveMembers);
        layoutSummaryPendingBadge = findViewById(R.id.layoutSummaryPendingBadge);
        tvCountPendingRequests = findViewById(R.id.tvCountPendingRequests);
        tvCountSecurityAlerts = findViewById(R.id.tvCountSecurityAlerts);

        tvInviteRoomKey = findViewById(R.id.tvInviteRoomKey);
        btnCopyInviteKey = findViewById(R.id.btnCopyInviteKey);
        btnShareInvite = findViewById(R.id.btnShareInvite);
        btnShowRoomQr = findViewById(R.id.btnShowRoomQr);

        cardRoomProfileSettings = findViewById(R.id.cardRoomProfileSettings);
        tvRoomProfileOwnerBadge = findViewById(R.id.tvRoomProfileOwnerBadge);
        tilSettingsRoomName = findViewById(R.id.tilSettingsRoomName);
        etSettingsRoomName = findViewById(R.id.etSettingsRoomName);
        tilSettingsOrgSubtitle = findViewById(R.id.tilSettingsOrgSubtitle);
        etSettingsOrgSubtitle = findViewById(R.id.etSettingsOrgSubtitle);
        tilSettingsReceiptPrefix = findViewById(R.id.tilSettingsReceiptPrefix);
        etSettingsReceiptPrefix = findViewById(R.id.etSettingsReceiptPrefix);
        tilSettingsReceiptFooter = findViewById(R.id.tilSettingsReceiptFooter);
        etSettingsReceiptFooter = findViewById(R.id.etSettingsReceiptFooter);
        btnSaveRoomProfile = findViewById(R.id.btnSaveRoomProfile);

        cardCollectionSetup = findViewById(R.id.cardCollectionSetup);
        chipGroupPaymentChannels = findViewById(R.id.chipGroupPaymentChannels);
        chipModeCash = findViewById(R.id.chipModeCash);
        chipModeGcash = findViewById(R.id.chipModeGcash);
        chipModeMaya = findViewById(R.id.chipModeMaya);
        chipModeGoTyme = findViewById(R.id.chipModeGoTyme);
        chipModeBank = findViewById(R.id.chipModeBank);
        switchRequireProofOnline = findViewById(R.id.switchRequireProofOnline);
        chipGroupDefaultRole = findViewById(R.id.chipGroupDefaultRole);
        chipDefaultAuditor = findViewById(R.id.chipDefaultAuditor);
        chipDefaultCollector = findViewById(R.id.chipDefaultCollector);
        switchArchiveRoom = findViewById(R.id.switchArchiveRoom);

        cardAccessControlToggles = findViewById(R.id.cardAccessControlToggles);
        switchRequireApproval = findViewById(R.id.switchRequireApproval);
        switchEmergencyLock = findViewById(R.id.switchEmergencyLock);
        chipGroupInviteExpiration = findViewById(R.id.chipGroupInviteExpiration);
        chipExpNone = findViewById(R.id.chipExpNone);
        chipExp1h = findViewById(R.id.chipExp1h);
        chipExp24h = findViewById(R.id.chipExp24h);
        chipExp7d = findViewById(R.id.chipExp7d);
        tvInviteExpirationCountdown = findViewById(R.id.tvInviteExpirationCountdown);

        cardCredentialProtection = findViewById(R.id.cardCredentialProtection);
        btnRotateRoomPassword = findViewById(R.id.btnRotateRoomPassword);
        btnRotateSigningSecret = findViewById(R.id.btnRotateSigningSecret);
        btnViewSecurityAuditLogs = findViewById(R.id.btnViewSecurityAuditLogs);
        switchBiometricAppLock = findViewById(R.id.switchBiometricAppLock);

        cardPendingJoinRequestsSection = findViewById(R.id.cardPendingJoinRequestsSection);
        tvPendingRequestsHeader = findViewById(R.id.tvPendingRequestsHeader);
        layoutPendingRequestsContainer = findViewById(R.id.layoutPendingRequestsContainer);

        etSearchMembers = findViewById(R.id.etSearchMembers);
        chipGroupMemberFilters = findViewById(R.id.chipGroupMemberFilters);
        layoutEmptyMembers = findViewById(R.id.layoutEmptyMembers);

        cardDangerZone = findViewById(R.id.cardDangerZone);
        btnDeleteRoomPermanent = findViewById(R.id.btnDeleteRoomPermanent);
    }

    private void setupClickListeners() {
        if (btnDeleteRoomPermanent != null) {
            btnDeleteRoomPermanent.setOnClickListener(v -> handleDeleteRoomClicked());
        }

        btnCopyInviteKey.setOnClickListener(v -> copyInviteKeyOnly());
        btnShareInvite.setOnClickListener(v -> shareInviteIntent());
        btnShowRoomQr.setOnClickListener(v -> showRoomQrModalDialog());

        if (btnSaveRoomProfile != null) {
            btnSaveRoomProfile.setOnClickListener(v -> saveRoomProfileAndSettings());
        }

        if (switchArchiveRoom != null) {
            switchArchiveRoom.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isUpdatingSwitches || currentRoom == null) return;
                if (isChecked) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Archive Room (Read-Only Freeze)?")
                            .setMessage("Freezes this room so no new payments, expenses, or folders can be added, while preserving all receipts, QR verification, and Excel exports.")
                            .setPositiveButton("Archive Room", (d, w) -> saveRoomProfileAndSettings())
                            .setNegativeButton("Cancel", (d, w) -> {
                                isUpdatingSwitches = true;
                                switchArchiveRoom.setChecked(false);
                                isUpdatingSwitches = false;
                            })
                            .setCancelable(false)
                            .show();
                } else {
                    saveRoomProfileAndSettings();
                }
            });
        }

        switchRequireApproval.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitches || currentRoom == null) return;
            updateSecurityFlags(currentRoom.isLocked, isChecked, currentRoom.keyExpiresAt);
        });

        switchEmergencyLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitches || currentRoom == null) return;
            updateSecurityFlags(isChecked, currentRoom.requireApproval, currentRoom.keyExpiresAt);
        });

        chipGroupInviteExpiration.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (isUpdatingSwitches || currentRoom == null) return;
            long now = System.currentTimeMillis();
            long newExp = 0L;
            if (checkedIds.contains(R.id.chipExp1h)) {
                newExp = now + TimeUnit.HOURS.toMillis(1);
            } else if (checkedIds.contains(R.id.chipExp24h)) {
                newExp = now + TimeUnit.HOURS.toMillis(24);
            } else if (checkedIds.contains(R.id.chipExp7d)) {
                newExp = now + TimeUnit.DAYS.toMillis(7);
            }
            updateSecurityFlags(currentRoom.isLocked, currentRoom.requireApproval, newExp);
        });

        switchBiometricAppLock.setChecked(AppLockManager.getInstance(this).isAppLockEnabled());
        switchBiometricAppLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppLockManager.getInstance(this).setAppLockEnabled(isChecked);
            String msg = isChecked
                    ? "App Lock enabled: Fingerprint / Device PIN will be required on app launch."
                    : "App Lock disabled.";
            Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show();
        });

        btnRotateRoomPassword.setOnClickListener(v -> showRotateRoomPasswordDialog());
        btnRotateSigningSecret.setOnClickListener(v -> confirmRotateSigningSecret());
        btnViewSecurityAuditLogs.setOnClickListener(v -> {
            Intent intent = new Intent(this, AuditLogActivity.class);
            startActivity(intent);
        });

        etSearchMembers.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                applyMemberFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        chipGroupMemberFilters.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chipFilterPending)) {
                currentFilter = "PENDING";
            } else if (checkedIds.contains(R.id.chipFilterCollectors)) {
                currentFilter = "COLLECTOR";
            } else if (checkedIds.contains(R.id.chipFilterAuditors)) {
                currentFilter = "AUDITOR";
            } else {
                currentFilter = "ALL";
            }
            applyMemberFilter();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        attachLiveRoomMembersListener();
    }

    @Override
    protected void onStop() {
        detachLiveRoomMembersListener();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (loadingOverlay != null) {
            loadingOverlay.release();
        }
        detachLiveRoomMembersListener();
        super.onDestroy();
    }

    private void attachLiveRoomMembersListener() {
        if (activeRoomId == null || activeRoomId.trim().isEmpty()) {
            return;
        }
        if (!FirebaseSyncManager.isFirebaseConfigured(this)) {
            return;
        }
        detachLiveRoomMembersListener();
        try {
            liveMembersListener = FirebaseFirestore.getInstance()
                    .collection("rooms")
                    .document(activeRoomId)
                    .collection("members")
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null || snapshots == null) {
                            return;
                        }
                        FirestoreRealtimeSyncManager.getInstance(RoomMembersActivity.this)
                                .syncRemoteMembersSnapshot(activeRoomId, snapshots);

                        List<RoomMemberEntity> liveList = new ArrayList<>();
                        for (DocumentSnapshot doc : snapshots.getDocuments()) {
                            if (!doc.exists()) {
                                continue;
                            }
                            String userId = safeStr(doc.getString("userId"), doc.getId());
                            String userName = safeStr(doc.getString("userName"), "Officer");
                            String userEmail = safeStr(doc.getString("userEmail"), "");
                            String role = safeStr(doc.getString("role"), RoomMemberEntity.ROLE_COLLECTOR);
                            String status = safeStr(doc.getString("status"), RoomMemberEntity.STATUS_ACTIVE);
                            String deviceInfo = doc.getString("deviceInfo");
                            Long joinedAtVal = doc.getLong("joinedAt");
                            long joinedAt = joinedAtVal != null ? joinedAtVal : System.currentTimeMillis();

                            liveList.add(new RoomMemberEntity(
                                    activeRoomId,
                                    userId,
                                    userName,
                                    userEmail,
                                    role,
                                    joinedAt,
                                    status,
                                    deviceInfo
                            ));
                        }
                        rawMembersList.clear();
                        rawMembersList.addAll(liveList);
                        updateMembersUi(rawMembersList);
                        refreshRoomHeader();
                    });
        } catch (Exception ignored) {
        }
    }

    private void detachLiveRoomMembersListener() {
        if (liveMembersListener != null) {
            try {
                liveMembersListener.remove();
            } catch (Exception ignored) {
            }
            liveMembersListener = null;
        }
    }

    private void updateMembersUi(List<RoomMemberEntity> allMembers) {
        List<RoomMemberEntity> pendingList = new ArrayList<>();
        int activeCount = 0;
        for (RoomMemberEntity m : allMembers) {
            if (m.isPendingApproval()) {
                pendingList.add(m);
            } else if (RoomMemberEntity.STATUS_ACTIVE.equalsIgnoreCase(m.status)) {
                activeCount++;
            }
        }

        renderPendingJoinRequestsSection(pendingList);
        if (tvCountActiveMembers != null) {
            tvCountActiveMembers.setText(String.valueOf(activeCount));
        }
        if (tvCountPendingRequests != null) {
            tvCountPendingRequests.setText(String.valueOf(pendingList.size()));
        }
        if (layoutSummaryPendingBadge != null) {
            int bgRes = pendingList.isEmpty() ? R.color.surface_variant : R.color.status_pending_container;
            layoutSummaryPendingBadge.setBackgroundTintList(ContextCompat.getColorStateList(this, bgRes));
        }

        applyMemberFilter();
    }

    private void applyMemberFilter() {
        List<RoomMemberEntity> filtered = new ArrayList<>();
        for (RoomMemberEntity m : rawMembersList) {
            if (m.isPendingApproval() && !"PENDING".equalsIgnoreCase(currentFilter) && !"ALL".equalsIgnoreCase(currentFilter)) {
                continue;
            }
            if ("PENDING".equalsIgnoreCase(currentFilter) && !m.isPendingApproval()) {
                continue;
            }
            if ("COLLECTOR".equalsIgnoreCase(currentFilter) && !RoomMemberEntity.ROLE_COLLECTOR.equalsIgnoreCase(m.role)) {
                continue;
            }
            if ("AUDITOR".equalsIgnoreCase(currentFilter) && !RoomMemberEntity.ROLE_AUDITOR.equalsIgnoreCase(m.role)) {
                continue;
            }

            if (!currentSearchQuery.isEmpty()) {
                String name = m.userName != null ? m.userName.toLowerCase(Locale.US) : "";
                String email = m.userEmail != null ? m.userEmail.toLowerCase(Locale.US) : "";
                if (!name.contains(currentSearchQuery) && !email.contains(currentSearchQuery)) {
                    continue;
                }
            }
            filtered.add(m);
        }

        adapter.setOwnerViewing(isCurrentUserRoomOwner());
        adapter.submitList(filtered);
        if (layoutEmptyMembers != null) {
            layoutEmptyMembers.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private boolean isCurrentUserRoomOwner() {
        if (sessionManager.isOwner()) {
            return true;
        }
        String currentUid = sessionManager.getCurrentUserId();
        return currentRoom != null
                && currentUid != null
                && currentUid.equals(currentRoom.ownerUserId);
    }

    private void renderPendingJoinRequestsSection(List<RoomMemberEntity> pendingMembers) {
        if (cardPendingJoinRequestsSection == null || layoutPendingRequestsContainer == null) {
            return;
        }
        if (pendingMembers == null || pendingMembers.isEmpty()) {
            cardPendingJoinRequestsSection.setVisibility(View.GONE);
            layoutPendingRequestsContainer.removeAllViews();
            return;
        }

        cardPendingJoinRequestsSection.setVisibility(View.VISIBLE);
        tvPendingRequestsHeader.setText("Pending Join Requests (" + pendingMembers.size() + ")");
        layoutPendingRequestsContainer.removeAllViews();

        boolean isOwner = isCurrentUserRoomOwner();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (RoomMemberEntity pending : pendingMembers) {
            View itemView = inflater.inflate(R.layout.item_room_member, layoutPendingRequestsContainer, false);
            TextView tvAvatar = itemView.findViewById(R.id.tvMemberAvatarInitial);
            TextView tvName = itemView.findViewById(R.id.tvMemberName);
            TextView tvEmailAndDate = itemView.findViewById(R.id.tvMemberEmailAndDate);
            TextView tvDeviceModel = itemView.findViewById(R.id.tvMemberDeviceModel);
            TextView tvRoleBadge = itemView.findViewById(R.id.tvMemberRoleBadge);
            LinearLayout layoutActions = itemView.findViewById(R.id.layoutPendingMemberActions);
            MaterialButton btnApprove = itemView.findViewById(R.id.btnApprovePendingMember);
            MaterialButton btnReject = itemView.findViewById(R.id.btnRejectPendingMember);
            MaterialButton btnBan = itemView.findViewById(R.id.btnBanPendingMember);

            String initial = (pending.userName != null && !pending.userName.trim().isEmpty())
                    ? pending.userName.trim().substring(0, 1).toUpperCase(Locale.US)
                    : "P";
            if (tvAvatar != null) tvAvatar.setText(initial);
            tvName.setText(pending.userName);

            String email = (pending.userEmail != null && !pending.userEmail.isEmpty())
                    ? pending.userEmail
                    : "No email provided";
            tvEmailAndDate.setText(email + " • Requested " + CurrencyFormatter.formatShortDate(pending.joinedAt));

            if (tvDeviceModel != null) {
                if (pending.deviceInfo != null && !pending.deviceInfo.trim().isEmpty()) {
                    tvDeviceModel.setText("Device: " + pending.deviceInfo.trim());
                    tvDeviceModel.setVisibility(View.VISIBLE);
                } else {
                    tvDeviceModel.setVisibility(View.GONE);
                }
            }

            tvRoleBadge.setText("PENDING");
            tvRoleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_amber));
            tvRoleBadge.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_pending_container));

            if (isOwner) {
                layoutActions.setVisibility(View.VISIBLE);
                btnApprove.setOnClickListener(v -> showQuickApproveRolePickerDialog(pending));
                btnReject.setOnClickListener(v -> rejectOrBanMember(pending, false));
                btnBan.setOnClickListener(v -> rejectOrBanMember(pending, true));
                itemView.setOnClickListener(v -> showMemberRoleActionDialog(pending));
            } else {
                layoutActions.setVisibility(View.GONE);
            }

            layoutPendingRequestsContainer.addView(itemView);
        }
    }

    private void refreshRoomHeader() {
        String activeName = currentRoom != null ? currentRoom.roomName : sessionManager.getActiveRoomName();
        tvMembersRoomTitle.setText(activeName);

        boolean isOwner = isCurrentUserRoomOwner();
        String role = isOwner ? RoomMemberEntity.ROLE_OWNER : sessionManager.getActiveRoomRole();
        String roleDesc;
        if (isOwner) {
            roleDesc = "OWNER (Full Security & Access Control)";
        } else if (RoomMemberEntity.ROLE_COLLECTOR.equalsIgnoreCase(role)) {
            roleDesc = "COLLECTOR (Record Payments & Expenses)";
        } else {
            roleDesc = "AUDITOR (Read-Only Transparency & QR Verification)";
        }
        tvMembersMyRoleSubtitle.setText("Your Role: " + roleDesc);

        String roomKey = currentRoom != null ? currentRoom.roomKey : sessionManager.getActiveRoomKey();
        tvInviteRoomKey.setText(roomKey != null ? roomKey : "KEY-XXXXXX");

        // Update Security Posture Hero Card
        if (currentRoom != null) {
            boolean isProtected = !currentRoom.isLocked
                    && currentRoom.requireApproval
                    && currentRoom.roomSalt != null && !currentRoom.roomSalt.isEmpty();

            if (isProtected) {
                ivSecurityPostureIcon.setImageResource(R.drawable.ic_verified_user);
                tvSecurityPostureTitle.setText("Room Protected");
                tvSecurityPostureTitle.setTextColor(ContextCompat.getColor(this, R.color.status_paid_green));
                tvSecurityPostureSubtitle.setText("Owner approval gate active • PBKDF2-SHA256 salted hash • Strong status");
            } else if (currentRoom.isLocked) {
                ivSecurityPostureIcon.setImageResource(R.drawable.ic_gpp_maybe);
                tvSecurityPostureTitle.setText("Room Locked (Emergency)");
                tvSecurityPostureTitle.setTextColor(ContextCompat.getColor(this, R.color.status_alert_crimson));
                tvSecurityPostureSubtitle.setText("Emergency Room Lock active • All new join attempts are blocked");
            } else {
                ivSecurityPostureIcon.setImageResource(R.drawable.ic_gpp_maybe);
                tvSecurityPostureTitle.setText("Security Attention");
                tvSecurityPostureTitle.setTextColor(ContextCompat.getColor(this, R.color.status_warning_amber));
                String sub = !currentRoom.requireApproval
                        ? "Approval gate disabled • Any user with credentials can join without verification"
                        : "Legacy password hash in use • Upgrade to salted PBKDF2 recommended";
                tvSecurityPostureSubtitle.setText(sub);
            }

            // Update switches and expiration chips without re-triggering listeners
            isUpdatingSwitches = true;
            if (switchRequireApproval != null) {
                switchRequireApproval.setChecked(currentRoom.requireApproval);
            }
            if (switchEmergencyLock != null) {
                switchEmergencyLock.setChecked(currentRoom.isLocked);
            }
            long now = System.currentTimeMillis();
            if (currentRoom.keyExpiresAt <= 0L) {
                chipExpNone.setChecked(true);
                tvInviteExpirationCountdown.setText("No expiration timer active (Key valid permanently until rotated)");
            } else if (currentRoom.isKeyExpired(now)) {
                tvInviteExpirationCountdown.setText("Invite Key is EXPIRED! Joining members will be rejected until rotated.");
            } else {
                long diffHours = TimeUnit.MILLISECONDS.toHours(currentRoom.keyExpiresAt - now);
                tvInviteExpirationCountdown.setText("Expires in ~" + (diffHours > 0 ? diffHours + "h" : "<1h") + " ("
                        + CurrencyFormatter.formatDateTime(currentRoom.keyExpiresAt) + ")");
                if (diffHours >= 24 * 6) {
                    chipExp7d.setChecked(true);
                } else if (diffHours >= 12) {
                    chipExp24h.setChecked(true);
                } else {
                    chipExp1h.setChecked(true);
                }
            }
            isUpdatingSwitches = false;
        }

        // Show/hide owner-only management cards
        if (cardAccessControlToggles != null) {
            cardAccessControlToggles.setVisibility(isOwner ? View.VISIBLE : View.GONE);
        }
        if (cardCredentialProtection != null) {
            cardCredentialProtection.setVisibility(isOwner ? View.VISIBLE : View.GONE);
        }
        if (cardDangerZone != null) {
            cardDangerZone.setVisibility(isOwner ? View.VISIBLE : View.GONE);
        }

        // Room Profile & Setup Controls
        if (currentRoom != null) {
            isUpdatingSwitches = true;
            if (etSettingsRoomName != null && !etSettingsRoomName.hasFocus()) {
                etSettingsRoomName.setText(currentRoom.roomName);
            }
            if (etSettingsOrgSubtitle != null && !etSettingsOrgSubtitle.hasFocus()) {
                etSettingsOrgSubtitle.setText(currentRoom.organizationSubtitle != null ? currentRoom.organizationSubtitle : "");
            }
            if (etSettingsReceiptPrefix != null && !etSettingsReceiptPrefix.hasFocus()) {
                etSettingsReceiptPrefix.setText(currentRoom.getCleanReceiptPrefix());
            }
            if (etSettingsReceiptFooter != null && !etSettingsReceiptFooter.hasFocus()) {
                etSettingsReceiptFooter.setText(currentRoom.receiptFooterNote != null ? currentRoom.receiptFooterNote : "");
            }

            if (chipModeCash != null) chipModeCash.setChecked(currentRoom.isPaymentModeEnabled("CASH"));
            if (chipModeGcash != null) chipModeGcash.setChecked(currentRoom.isPaymentModeEnabled("GCASH"));
            if (chipModeMaya != null) chipModeMaya.setChecked(currentRoom.isPaymentModeEnabled("MAYA"));
            if (chipModeGoTyme != null) chipModeGoTyme.setChecked(currentRoom.isPaymentModeEnabled("GOTYME"));
            if (chipModeBank != null) chipModeBank.setChecked(currentRoom.isPaymentModeEnabled("BANK"));

            if (switchRequireProofOnline != null) {
                switchRequireProofOnline.setChecked(currentRoom.requireProofPhotoForOnline);
            }

            if (chipGroupDefaultRole != null) {
                if (RoomMemberEntity.ROLE_COLLECTOR.equalsIgnoreCase(currentRoom.defaultJoinRole)) {
                    if (chipDefaultCollector != null) chipDefaultCollector.setChecked(true);
                } else {
                    if (chipDefaultAuditor != null) chipDefaultAuditor.setChecked(true);
                }
            }

            if (switchArchiveRoom != null) {
                switchArchiveRoom.setChecked(currentRoom.isArchived);
            }
            isUpdatingSwitches = false;
        }

        if (etSettingsRoomName != null) etSettingsRoomName.setEnabled(isOwner);
        if (etSettingsOrgSubtitle != null) etSettingsOrgSubtitle.setEnabled(isOwner);
        if (etSettingsReceiptPrefix != null) etSettingsReceiptPrefix.setEnabled(isOwner);
        if (etSettingsReceiptFooter != null) etSettingsReceiptFooter.setEnabled(isOwner);
        if (btnSaveRoomProfile != null) btnSaveRoomProfile.setVisibility(isOwner ? View.VISIBLE : View.GONE);
        if (tvRoomProfileOwnerBadge != null) {
            tvRoomProfileOwnerBadge.setText(isOwner ? "OWNER ONLY" : "READ ONLY");
            tvRoomProfileOwnerBadge.setTextColor(ContextCompat.getColor(this, isOwner ? R.color.status_paid_green : R.color.text_secondary));
        }

        if (chipGroupPaymentChannels != null) {
            for (int i = 0; i < chipGroupPaymentChannels.getChildCount(); i++) {
                chipGroupPaymentChannels.getChildAt(i).setEnabled(isOwner);
            }
        }
        if (switchRequireProofOnline != null) switchRequireProofOnline.setEnabled(isOwner);
        if (chipDefaultAuditor != null) chipDefaultAuditor.setEnabled(isOwner);
        if (chipDefaultCollector != null) chipDefaultCollector.setEnabled(isOwner);
        if (switchArchiveRoom != null) switchArchiveRoom.setEnabled(isOwner);
    }

    private void saveRoomProfileAndSettings() {
        if (!isCurrentUserRoomOwner()) {
            Snackbar.make(rootView, "Only the Room OWNER can modify room settings.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (currentRoom == null) return;

        String name = etSettingsRoomName.getText() != null ? etSettingsRoomName.getText().toString().trim() : "";
        if (name.length() < 3 || name.length() > 50) {
            tilSettingsRoomName.setError("Room Name must be between 3 and 50 characters.");
            return;
        }
        tilSettingsRoomName.setError(null);

        String subtitle = etSettingsOrgSubtitle.getText() != null ? etSettingsOrgSubtitle.getText().toString().trim() : "";
        String prefix = etSettingsReceiptPrefix.getText() != null ? etSettingsReceiptPrefix.getText().toString().trim().toUpperCase(Locale.US) : "RCP";
        prefix = prefix.replaceAll("[^A-Z0-9]", "");
        if (prefix.length() < 2) {
            prefix = "RCP";
        } else if (prefix.length() > 6) {
            prefix = prefix.substring(0, 6);
        }
        etSettingsReceiptPrefix.setText(prefix);

        String footer = etSettingsReceiptFooter.getText() != null ? etSettingsReceiptFooter.getText().toString().trim() : "";

        // Payment channels
        List<String> enabledModes = new ArrayList<>();
        if (chipModeCash != null && chipModeCash.isChecked()) enabledModes.add("CASH");
        if (chipModeGcash != null && chipModeGcash.isChecked()) enabledModes.add("GCASH");
        if (chipModeMaya != null && chipModeMaya.isChecked()) enabledModes.add("MAYA");
        if (chipModeGoTyme != null && chipModeGoTyme.isChecked()) enabledModes.add("GOTYME");
        if (chipModeBank != null && chipModeBank.isChecked()) enabledModes.add("BANK");

        if (enabledModes.isEmpty()) {
            Snackbar.make(rootView, "At least one payment channel must remain enabled.", Snackbar.LENGTH_LONG).show();
            if (chipModeCash != null) chipModeCash.setChecked(true);
            enabledModes.add("CASH");
        }
        String modesStr = String.join(",", enabledModes);

        boolean requireProof = switchRequireProofOnline != null && switchRequireProofOnline.isChecked();
        String defaultRole = (chipDefaultCollector != null && chipDefaultCollector.isChecked())
                ? RoomMemberEntity.ROLE_COLLECTOR
                : RoomMemberEntity.ROLE_AUDITOR;
        boolean isArchived = switchArchiveRoom != null && switchArchiveRoom.isChecked();

        loadingOverlay.show("Saving Room Settings...", "Updating room profile and branding");
        repository.updateRoomSettings(
                currentRoom.roomId,
                name,
                subtitle,
                prefix,
                footer,
                modesStr,
                requireProof,
                defaultRole,
                isArchived,
                new PaymentRepository.ResultCallback<RoomEntity>() {
                    @Override
                    public void onSuccess(RoomEntity updated) {
                        loadingOverlay.hide();
                        currentRoom = updated;
                        refreshRoomHeader();
                        Snackbar.make(rootView, "Room Profile & Settings saved successfully.", Snackbar.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void handleDeleteRoomClicked() {
        if (currentRoom == null) return;
        FirebaseUser fbUser = FirebaseAuth.getInstance().getCurrentUser();
        if (fbUser == null) {
            Toast.makeText(this, "You must be signed in to delete a room.", Toast.LENGTH_LONG).show();
            return;
        }

        loadingOverlay.show("Checking Email Verification...", "Validating owner security credentials");
        fbUser.reload().addOnCompleteListener(reloadTask -> {
            loadingOverlay.hide();
            if (isFinishing() || isDestroyed()) return;

            FirebaseUser refreshedUser = FirebaseAuth.getInstance().getCurrentUser();
            if (refreshedUser == null) return;

            if (!refreshedUser.isEmailVerified()) {
                showEmailVerificationRequiredDialog(refreshedUser);
            } else {
                showDeleteRoomConfirmationDialog(refreshedUser);
            }
        });
    }

    private void showEmailVerificationRequiredDialog(FirebaseUser user) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Verified Email Required")
                .setMessage("For security, deleting a room permanently requires verified ownership of your account email (" 
                        + user.getEmail() + ").\n\nPlease check your inbox and verify your email to continue.")
                .setNeutralButton("Cancel", null)
                .setNegativeButton("Send Verification Email", (dialog, which) -> {
                    loadingOverlay.show("Sending Email...", "Sending verification link to " + user.getEmail());
                    user.sendEmailVerification().addOnCompleteListener(task -> {
                        loadingOverlay.hide();
                        if (task.isSuccessful()) {
                            Toast.makeText(this, "Verification link sent to " + user.getEmail() + ". Please check your inbox.", Toast.LENGTH_LONG).show();
                        } else {
                            String err = task.getException() != null ? task.getException().getMessage() : "Failed to send email";
                            Toast.makeText(this, "Failed to send verification email: " + err, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setPositiveButton("I've Verified — Continue", (dialog, which) -> {
                    loadingOverlay.show("Checking Email...", "Verifying email status");
                    user.reload().addOnCompleteListener(task -> {
                        loadingOverlay.hide();
                        if (user.isEmailVerified()) {
                            showDeleteRoomConfirmationDialog(user);
                        } else {
                            Toast.makeText(this, "Email is not verified yet. Please click the link sent to your inbox.", Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .show();
    }

    private void showDeleteRoomConfirmationDialog(FirebaseUser user) {
        if (currentRoom == null) return;
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_delete_room_confirmation, null);
        TextView tvVerifiedEmail = dialogView.findViewById(R.id.tvDeleteRoomVerifiedEmail);
        TextInputLayout tilPassword = dialogView.findViewById(R.id.tilDeleteRoomPassword);
        TextInputEditText etPassword = dialogView.findViewById(R.id.etDeleteRoomPassword);
        TextInputLayout tilConfirmKey = dialogView.findViewById(R.id.tilDeleteRoomConfirmKey);
        TextInputEditText etConfirmKey = dialogView.findViewById(R.id.etDeleteRoomConfirmKey);
        TextView tvKeyPrompt = dialogView.findViewById(R.id.tvDeleteRoomKeyPrompt);

        tvVerifiedEmail.setText(user.getEmail() != null ? user.getEmail() + " (Verified)" : "Email Verified");
        tvKeyPrompt.setText("Type \"" + currentRoom.roomKey + "\" to confirm deletion");

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Delete Workspace Permanently")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete Room Forever", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveBtn.setTextColor(ContextCompat.getColor(this, R.color.status_alert_crimson));
            positiveBtn.setOnClickListener(v -> {
                String pass = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";
                String typedKey = etConfirmKey.getText() != null ? etConfirmKey.getText().toString().trim() : "";

                if (pass.isEmpty()) {
                    tilPassword.setError("Account password is required");
                    return;
                }
                tilPassword.setError(null);

                if (!typedKey.equalsIgnoreCase(currentRoom.roomKey)) {
                    tilConfirmKey.setError("Room Key does not match. Expected: " + currentRoom.roomKey);
                    return;
                }
                tilConfirmKey.setError(null);

                dialog.dismiss();
                loadingOverlay.show("Permanently Deleting Room...", "Cascading deletion across cloud and local storage");

                RoomDeletionManager.deleteRoomCascade(
                        RoomMembersActivity.this,
                        currentRoom.roomId,
                        pass,
                        new RoomDeletionManager.DeletionCallback() {
                            @Override
                            public void onSuccess() {
                                loadingOverlay.hide();
                                Toast.makeText(RoomMembersActivity.this, "Room permanently deleted", Toast.LENGTH_LONG).show();
                                Intent intent = new Intent(RoomMembersActivity.this, RoomLobbyActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            }

                            @Override
                            public void onError(String errorMessage) {
                                loadingOverlay.hide();
                                new MaterialAlertDialogBuilder(RoomMembersActivity.this)
                                        .setTitle("Deletion Failed")
                                        .setMessage(errorMessage)
                                        .setPositiveButton("OK", null)
                                        .show();
                            }
                        }
                );
            });
        });

        dialog.show();
    }

    private void copyInviteKeyOnly() {
        String key = currentRoom != null ? currentRoom.roomKey : sessionManager.getActiveRoomKey();
        if (key == null || key.isEmpty()) return;

        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Room Key", key));
        }
        Snackbar.make(rootView, "Room Key copied: " + key, Snackbar.LENGTH_SHORT).show();
    }

    private void shareInviteIntent() {
        String roomName = currentRoom != null ? currentRoom.roomName : sessionManager.getActiveRoomName();
        String orgSubtitle = currentRoom != null ? currentRoom.organizationSubtitle : "";
        String roomKey = currentRoom != null ? currentRoom.roomKey : sessionManager.getActiveRoomKey();

        StringBuilder sb = new StringBuilder();
        sb.append("Join Private Room \"").append(roomName).append("\"\n");
        if (orgSubtitle != null && !orgSubtitle.trim().isEmpty()) {
            sb.append(orgSubtitle.trim()).append("\n");
        }
        sb.append("on Student QR Payment Tracker\n\n");
        sb.append("Room Key: ").append(roomKey).append("\n\n");
        sb.append("How to join:\n");
        sb.append("1. Open QR Payment Tracker app\n");
        sb.append("2. Tap Active Room at the top > Join Private Room\n");
        sb.append("3. Enter the Room Key and Room Password (ask the Room Owner)\n");

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Invite to " + roomName);
        shareIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(shareIntent, "Share Room Invite"));
    }

    private void showRoomQrModalDialog() {
        String key = currentRoom != null ? currentRoom.roomKey : sessionManager.getActiveRoomKey();
        if (key == null || key.isEmpty()) {
            Toast.makeText(this, "Room Key not available.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(android.view.Gravity.CENTER);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        ImageView ivQr = new ImageView(this);
        int sizePx = (int) (240 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
        ivQr.setLayoutParams(lp);

        Bitmap qrBitmap = SecuritySignatureHelper.generateQrCodeBitmap("ROOM_INVITE|" + key, 600);
        if (qrBitmap != null) {
            ivQr.setImageBitmap(qrBitmap);
        }
        container.addView(ivQr);

        TextView tvKey = new TextView(this);
        tvKey.setText(key);
        tvKey.setTextSize(18f);
        tvKey.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tvKey.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tvKey.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        textLp.topMargin = pad / 2;
        tvKey.setLayoutParams(textLp);
        container.addView(tvKey);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Scan Room Invite QR")
                .setMessage("Have other officers scan this QR code directly from their Room Lobby to automatically populate the Room Key.")
                .setView(container)
                .setPositiveButton("Close", null)
                .show();
    }

    private void updateSecurityFlags(boolean isLocked, boolean requireApproval, long keyExpiresAt) {
        loadingOverlay.show("Updating Security Rules...", "Syncing across all devices");
        repository.updateRoomSecuritySettings(
                activeRoomId,
                isLocked,
                requireApproval,
                keyExpiresAt,
                new PaymentRepository.ResultCallback<RoomEntity>() {
                    @Override
                    public void onSuccess(RoomEntity result) {
                        loadingOverlay.hide();
                        currentRoom = result;
                        refreshRoomHeader();
                        Snackbar.make(rootView, "Room security settings updated.", Snackbar.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void showRotateRoomPasswordDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_room_password, null);
        TextInputLayout tilNewPass = dialogView.findViewById(R.id.tilNewRoomPassword);
        TextInputEditText etNewPass = dialogView.findViewById(R.id.etNewRoomPassword);
        LinearProgressIndicator progressStrength = dialogView.findViewById(R.id.progressNewPassStrength);
        TextView tvStrengthLabel = dialogView.findViewById(R.id.tvNewPassStrengthLabel);
        TextView tvPolicyHint = dialogView.findViewById(R.id.tvNewPassPolicyHint);
        CheckBox cbAlsoRotateKey = dialogView.findViewById(R.id.cbAlsoRotateKey);

        etNewPass.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String pass = s != null ? s.toString() : "";
                if (pass.isEmpty()) {
                    progressStrength.setProgress(0);
                    tvStrengthLabel.setText("Weak");
                    tvStrengthLabel.setTextColor(ContextCompat.getColor(RoomMembersActivity.this, R.color.status_void_red));
                    progressStrength.setIndicatorColor(ContextCompat.getColor(RoomMembersActivity.this, R.color.status_void_red));
                    tvPolicyHint.setText("Must include 8+ chars with uppercase, lowercase, and numbers");
                    return;
                }
                String err = RoomSecurityHelper.validatePasswordStrength(pass);
                String strength = RoomSecurityHelper.getPasswordStrengthLabel(pass);
                if ("Strong".equalsIgnoreCase(strength) && err == null) {
                    progressStrength.setProgress(100);
                    tvStrengthLabel.setText("Strong");
                    tvStrengthLabel.setTextColor(ContextCompat.getColor(RoomMembersActivity.this, R.color.status_paid_green));
                    progressStrength.setIndicatorColor(ContextCompat.getColor(RoomMembersActivity.this, R.color.status_paid_green));
                    tvPolicyHint.setText("PBKDF2-SHA256 Ready • Excellent security");
                } else if ("Fair".equalsIgnoreCase(strength)) {
                    progressStrength.setProgress(60);
                    tvStrengthLabel.setText("Fair");
                    tvStrengthLabel.setTextColor(ContextCompat.getColor(RoomMembersActivity.this, R.color.status_warning_amber));
                    progressStrength.setIndicatorColor(ContextCompat.getColor(RoomMembersActivity.this, R.color.status_warning_amber));
                    tvPolicyHint.setText(err != null ? err : "Add uppercase, lowercase and numbers for maximum strength");
                } else {
                    progressStrength.setProgress(25);
                    tvStrengthLabel.setText("Weak");
                    tvStrengthLabel.setTextColor(ContextCompat.getColor(RoomMembersActivity.this, R.color.status_void_red));
                    progressStrength.setIndicatorColor(ContextCompat.getColor(RoomMembersActivity.this, R.color.status_void_red));
                    tvPolicyHint.setText(err != null ? err : "Min 8 chars with uppercase, lowercase, and numbers");
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Change Room Password")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Update Password", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newPass = etNewPass.getText() != null ? etNewPass.getText().toString().trim() : "";
            String passErr = RoomSecurityHelper.validatePasswordStrength(newPass);
            if (passErr != null) {
                tilNewPass.setError(passErr);
                return;
            }
            tilNewPass.setError(null);

            boolean rotateKey = cbAlsoRotateKey.isChecked();
            loadingOverlay.show("Updating Password...", "Hashing with PBKDF2-SHA256 & 16-byte salt");

            if (rotateKey) {
                long currentExp = currentRoom != null ? currentRoom.keyExpiresAt : 0L;
                repository.rotateRoomKeyAndPassword(activeRoomId, newPass, currentExp, new PaymentRepository.ResultCallback<RoomEntity>() {
                    @Override
                    public void onSuccess(RoomEntity result) {
                        loadingOverlay.hide();
                        dialog.dismiss();
                        currentRoom = result;
                        refreshRoomHeader();
                        new MaterialAlertDialogBuilder(RoomMembersActivity.this)
                                .setTitle("New Room Key Generated!")
                                .setMessage("New Room Key: " + result.roomKey
                                        + "\n\nOld Room Keys and invite links have been invalidated. Approved members remain in the room.")
                                .setPositiveButton("Got It", null)
                                .show();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        tilNewPass.setError(message);
                    }
                });
            } else {
                repository.changeRoomPassword(activeRoomId, newPass, new PaymentRepository.ResultCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        loadingOverlay.hide();
                        dialog.dismiss();
                        Snackbar.make(rootView, "Room Password updated (PBKDF2-SHA256).", Snackbar.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        tilNewPass.setError(message);
                    }
                });
            }
        }));

        dialog.show();
    }

    private void confirmRotateSigningSecret() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Rotate 256-bit QR Signing Secret?")
                .setMessage("Generates a new 256-bit HMAC-SHA256 signing secret for future QR payment receipts issued in this room and syncs it to active room members.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rotate Secret", (d, w) -> {
                    loadingOverlay.show("Rotating Secret...", "Syncing new secret to active members");
                    repository.rotateRoomSigningSecret(
                            activeRoomId,
                            new PaymentRepository.ResultCallback<RoomEntity>() {
                                @Override
                                public void onSuccess(RoomEntity result) {
                                    loadingOverlay.hide();
                                    currentRoom = result;
                                    Snackbar.make(rootView, "Rotated 256-bit QR Signing Secret.", Snackbar.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(String message) {
                                    loadingOverlay.hide();
                                    Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                                }
                            }
                    );
                })
                .show();
    }

    private void showQuickApproveRolePickerDialog(RoomMemberEntity pendingMember) {
        if (!isCurrentUserRoomOwner()) {
            Toast.makeText(this, "Only the Room OWNER can approve join requests.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] roleOptions = new String[]{
                "Approve as COLLECTOR (Record payments, expenses & rename folders)",
                "Approve as AUDITOR (Read-only transparency & QR verification)"
        };
        int defaultIndex = (currentRoom != null && RoomMemberEntity.ROLE_COLLECTOR.equalsIgnoreCase(currentRoom.defaultJoinRole)) ? 0 : 1;
        final int[] checkedIndex = new int[]{defaultIndex};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Approve " + pendingMember.userName)
                .setSingleChoiceItems(roleOptions, defaultIndex, (dialog, which) -> checkedIndex[0] = which)
                .setPositiveButton("Approve", (dialog, which) -> {
                    String selectedRole = (checkedIndex[0] == 1)
                            ? RoomMemberEntity.ROLE_AUDITOR
                            : RoomMemberEntity.ROLE_COLLECTOR;
                    approveMember(pendingMember, selectedRole);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showMemberRoleActionDialog(RoomMemberEntity member) {
        if (!isCurrentUserRoomOwner()) {
            Toast.makeText(this, "Only the Room OWNER can manage members and approvals.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (member.userId.equals(sessionManager.getCurrentUserId())) {
            Toast.makeText(this, "You are the Room OWNER of this workspace.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (member.isPendingApproval()) {
            String[] pendingOptions = new String[]{
                    "Approve as COLLECTOR (Can record payments/expenses)",
                    "Approve as AUDITOR (Read-only transparency & QR verification)",
                    "Reject Join Request (Remove from Waiting Room)",
                    "Ban User Permanently from Room"
            };
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Pending Approval: " + member.userName)
                    .setItems(pendingOptions, (dialog, which) -> {
                        if (which == 0) {
                            approveMember(member, RoomMemberEntity.ROLE_COLLECTOR);
                        } else if (which == 1) {
                            approveMember(member, RoomMemberEntity.ROLE_AUDITOR);
                        } else if (which == 2) {
                            rejectOrBanMember(member, false);
                        } else if (which == 3) {
                            rejectOrBanMember(member, true);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        String[] options = new String[]{
                "Set as COLLECTOR (Can record payments/expenses & rename folders)",
                "Set as AUDITOR (Read-only transparency & QR verification)",
                "Promote to OWNER (Full admin access)",
                "Remove Member from Room",
                "Ban Member Permanently (Revoke & Block Future Joins)"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Manage Member: " + member.userName)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        changeRole(member, RoomMemberEntity.ROLE_COLLECTOR);
                    } else if (which == 1) {
                        changeRole(member, RoomMemberEntity.ROLE_AUDITOR);
                    } else if (which == 2) {
                        changeRole(member, RoomMemberEntity.ROLE_OWNER);
                    } else if (which == 3) {
                        rejectOrBanMember(member, false);
                    } else if (which == 4) {
                        rejectOrBanMember(member, true);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void approveMember(RoomMemberEntity member, String role) {
        loadingOverlay.show("Updating Member Access...", "Updating across all connected devices");
        repository.approvePendingMember(
                activeRoomId,
                member.userId,
                member.userName,
                role,
                new PaymentRepository.ResultCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        loadingOverlay.hide();
                        Snackbar.make(rootView, "Approved " + member.userName + " as " + role, Snackbar.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void changeRole(RoomMemberEntity member, String newRole) {
        loadingOverlay.show("Updating Member Access...", "Updating across all connected devices");
        repository.updateMemberRole(
                activeRoomId,
                member.userId,
                member.userName,
                newRole,
                new PaymentRepository.ResultCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        loadingOverlay.hide();
                        Snackbar.make(rootView, member.userName + " is now " + newRole, Snackbar.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void rejectOrBanMember(RoomMemberEntity member, boolean banPermanently) {
        String title = banPermanently
                ? "Ban Member Permanently?"
                : (member.isPendingApproval() ? "Reject Join Request?" : "Remove Member?");
        String msg = banPermanently
                ? "Ban " + member.userName + " from \"" + sessionManager.getActiveRoomName() + "\"? Their active session and cached QR signing secret will be revoked immediately, and future join attempts will be blocked."
                : (member.isPendingApproval()
                    ? "Reject and remove " + member.userName + "'s join request from \"" + sessionManager.getActiveRoomName() + "\"?"
                    : "Remove " + member.userName + " from \"" + sessionManager.getActiveRoomName() + "\"?");
        String positiveLabel = banPermanently
                ? "Ban Permanently"
                : (member.isPendingApproval() ? "Reject Request" : "Remove");
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(positiveLabel, (d, w) -> {
                    loadingOverlay.show("Updating Member Access...", "Updating across all connected devices");
                    repository.rejectOrBanMember(
                            activeRoomId,
                            member.userId,
                            member.userName,
                            banPermanently,
                            new PaymentRepository.ResultCallback<Void>() {
                                @Override
                                public void onSuccess(Void result) {
                                    loadingOverlay.hide();
                                    String notice = banPermanently
                                            ? "Banned " + member.userName + " and revoked room access."
                                            : "Removed " + member.userName + " from room.";
                                    Snackbar.make(rootView, notice, Snackbar.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(String message) {
                                    loadingOverlay.hide();
                                    Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                                }
                            }
                    );
                })
                .show();
    }

    private static String safeStr(String value, String fallback) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : fallback;
    }

    private static class MembersAdapter extends RecyclerView.Adapter<MembersAdapter.MemberViewHolder> {

        private final List<RoomMemberEntity> items = new ArrayList<>();
        private final OnMemberClickListener listener;
        private final OnMemberClickListener approveListener;
        private final OnMemberClickListener rejectListener;
        private final OnMemberClickListener banListener;
        private boolean isOwnerViewing = false;

        MembersAdapter(
                OnMemberClickListener listener,
                OnMemberClickListener approveListener,
                OnMemberClickListener rejectListener,
                OnMemberClickListener banListener
        ) {
            this.listener = listener;
            this.approveListener = approveListener;
            this.rejectListener = rejectListener;
            this.banListener = banListener;
        }

        void setOwnerViewing(boolean ownerViewing) {
            this.isOwnerViewing = ownerViewing;
        }

        void submitList(List<RoomMemberEntity> newList) {
            items.clear();
            items.addAll(newList);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public MemberViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_room_member, parent, false);
            return new MemberViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull MemberViewHolder holder, int position) {
            RoomMemberEntity m = items.get(position);
            Context context = holder.itemView.getContext();

            String name = m.userName != null ? m.userName.trim() : "Member";
            String initial = !name.isEmpty() ? name.substring(0, 1).toUpperCase(Locale.US) : "M";
            if (holder.tvAvatarInitial != null) {
                holder.tvAvatarInitial.setText(initial);
            }
            holder.tvName.setText(name);

            String emailPart = (m.userEmail != null && !m.userEmail.isEmpty()) ? m.userEmail + " • " : "";
            holder.tvEmailAndDate.setText(emailPart + "Joined " + CurrencyFormatter.formatShortDate(m.joinedAt));

            if (holder.tvDeviceModel != null) {
                if (m.deviceInfo != null && !m.deviceInfo.trim().isEmpty()) {
                    holder.tvDeviceModel.setText("Device: " + m.deviceInfo.trim());
                    holder.tvDeviceModel.setVisibility(View.VISIBLE);
                } else {
                    holder.tvDeviceModel.setVisibility(View.GONE);
                }
            }

            if (m.isPendingApproval()) {
                holder.tvRoleBadge.setText("PENDING");
                holder.tvRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.status_warning_amber));
                holder.tvRoleBadge.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.status_pending_container));
                if (isOwnerViewing && holder.layoutPendingActions != null) {
                    holder.layoutPendingActions.setVisibility(View.VISIBLE);
                    holder.btnApprove.setOnClickListener(v -> approveListener.onMemberClicked(m));
                    holder.btnReject.setOnClickListener(v -> rejectListener.onMemberClicked(m));
                    holder.btnBan.setOnClickListener(v -> banListener.onMemberClicked(m));
                } else if (holder.layoutPendingActions != null) {
                    holder.layoutPendingActions.setVisibility(View.GONE);
                }
            } else if (m.isBanned()) {
                holder.tvRoleBadge.setText("BANNED");
                holder.tvRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.status_void_red));
                holder.tvRoleBadge.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.status_void_bg));
                if (holder.layoutPendingActions != null) {
                    holder.layoutPendingActions.setVisibility(View.GONE);
                }
            } else if (RoomMemberEntity.ROLE_OWNER.equalsIgnoreCase(m.role)) {
                holder.tvRoleBadge.setText("OWNER");
                holder.tvRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.white));
                holder.tvRoleBadge.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.brand_primary));
                if (holder.layoutPendingActions != null) {
                    holder.layoutPendingActions.setVisibility(View.GONE);
                }
            } else if (RoomMemberEntity.ROLE_COLLECTOR.equalsIgnoreCase(m.role)) {
                holder.tvRoleBadge.setText("COLLECTOR");
                holder.tvRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.status_paid_green));
                holder.tvRoleBadge.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.status_paid_bg));
                if (holder.layoutPendingActions != null) {
                    holder.layoutPendingActions.setVisibility(View.GONE);
                }
            } else {
                holder.tvRoleBadge.setText("AUDITOR");
                holder.tvRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.status_warning_amber));
                holder.tvRoleBadge.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.status_warning_bg));
                if (holder.layoutPendingActions != null) {
                    holder.layoutPendingActions.setVisibility(View.GONE);
                }
            }
            holder.itemView.setOnClickListener(v -> listener.onMemberClicked(m));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class MemberViewHolder extends RecyclerView.ViewHolder {
            final TextView tvAvatarInitial;
            final TextView tvName;
            final TextView tvEmailAndDate;
            final TextView tvDeviceModel;
            final TextView tvRoleBadge;
            final LinearLayout layoutPendingActions;
            final MaterialButton btnApprove;
            final MaterialButton btnReject;
            final MaterialButton btnBan;

            MemberViewHolder(@NonNull View itemView) {
                super(itemView);
                tvAvatarInitial = itemView.findViewById(R.id.tvMemberAvatarInitial);
                tvName = itemView.findViewById(R.id.tvMemberName);
                tvEmailAndDate = itemView.findViewById(R.id.tvMemberEmailAndDate);
                tvDeviceModel = itemView.findViewById(R.id.tvMemberDeviceModel);
                tvRoleBadge = itemView.findViewById(R.id.tvMemberRoleBadge);
                layoutPendingActions = itemView.findViewById(R.id.layoutPendingMemberActions);
                btnApprove = itemView.findViewById(R.id.btnApprovePendingMember);
                btnReject = itemView.findViewById(R.id.btnRejectPendingMember);
                btnBan = itemView.findViewById(R.id.btnBanPendingMember);
            }
        }
    }

    private interface OnMemberClickListener {
        void onMemberClicked(RoomMemberEntity member);
    }
}
