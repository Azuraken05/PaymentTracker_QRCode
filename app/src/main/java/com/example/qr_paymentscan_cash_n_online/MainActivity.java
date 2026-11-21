package com.example.qr_paymentscan_cash_n_online;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.content.res.ColorStateList;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.data.SectionEntity;
import com.example.qr_paymentscan_cash_n_online.data.SectionSummary;
import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;
import com.example.qr_paymentscan_cash_n_online.sync.FirestoreRealtimeSyncManager;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.ui.LoadingOverlayController;
import com.example.qr_paymentscan_cash_n_online.ui.ReceiptVerifierBottomSheet;
import com.example.qr_paymentscan_cash_n_online.ui.SectionAdapter;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.LocalStorageManager;
import com.example.qr_paymentscan_cash_n_online.util.SecuritySignatureHelper;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import androidx.core.content.ContextCompat;
import com.example.qr_paymentscan_cash_n_online.util.CloudImageStorageManager;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends BaseThemedActivity {

    private static final String PREF_CLOCK_24H = "pref_clock_24h";

    private PaymentRepository repository;
    private UserSessionManager sessionManager;
    private SectionAdapter sectionAdapter;
    private TextView tvSectionCountBadge;
    private TextView tvMainOrgSubtitle;
    private MaterialButton btnSyncStatusChip;
    private MaterialButton btnActiveRoomSwitcher;
    private ExtendedFloatingActionButton fabNewSection;
    private LinearLayout layoutEmptySections;
    private View rootView;
    private LiveData<List<SectionSummary>> currentRootSectionsLiveData;
    private LiveData<com.example.qr_paymentscan_cash_n_online.data.RoomMemberEntity> currentMemberLiveData;
    private LiveData<com.example.qr_paymentscan_cash_n_online.data.RoomEntity> currentRoomLiveData;
    private com.example.qr_paymentscan_cash_n_online.data.RoomEntity currentRoom;
    private String observedRoomId = null;
    private String observedMemberRoomId = null;
    private String observedRoomDetailsId = null;
    private String lastRoomEntrySyncId = null;
    private SwipeRefreshLayout swipeRefreshSections;
    private LoadingOverlayController loadingOverlay;

    // Live Digital Clock & Workspace Command Card Views
    private TextView tvClockGreeting;
    private TextView tvClockRoleBadge;
    private View layoutClockReadout;
    private TextClock clockHoursMinutes;
    private TextClock clockSeconds;
    private TextClock clockAmPm;
    private TextClock clockFullDate;
    private ImageView ivClockSyncDot;
    private TextView tvClockSyncStatus;

    // Live Camera QR Scanner Launcher
    private final ActivityResultLauncher<ScanOptions> qrCameraScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    showVerificationBottomSheet(result.getContents());
                }
            });

    // Scan QR from Saved Gallery Image Launcher
    private final ActivityResultLauncher<String> qrGalleryImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    decodeQrFromGalleryUri(uri);
                }
            });

    // JSON Backup Export Launcher
    private final ActivityResultLauncher<String> createBackupJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null) {
                    performJsonBackupExport(uri);
                }
            });

    // JSON Backup Restore Launcher
    private final ActivityResultLauncher<String[]> openBackupJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    performJsonBackupRestore(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        rootView = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Check Biometric / PIN App Lock if enabled
        com.example.qr_paymentscan_cash_n_online.util.AppLockManager.getInstance(this)
                .promptIfLocked(this, new com.example.qr_paymentscan_cash_n_online.util.AppLockManager.AuthCallback() {
                    @Override
                    public void onSuccess() {
                        // App unlocked
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        finish();
                    }
                });

        // Preserve existing HMAC-SHA256 secret key so all prior QR receipts remain authentic
        SecuritySignatureHelper.getOrCreateSecretKey(this);
        sessionManager = UserSessionManager.getInstance(this);
        repository = new PaymentRepository(this);

        tvSectionCountBadge = findViewById(R.id.tvSectionCountBadge);
        tvMainOrgSubtitle = findViewById(R.id.tvMainOrgSubtitle);
        btnSyncStatusChip = findViewById(R.id.btnSyncStatusChip);
        btnActiveRoomSwitcher = findViewById(R.id.btnActiveRoomSwitcher);
        MaterialButton btnRoomMembersMain = findViewById(R.id.btnRoomMembersMain);
        ImageButton btnUserProfileMain = findViewById(R.id.btnUserProfileMain);
        layoutEmptySections = findViewById(R.id.layoutEmptySections);
        RecyclerView rvSections = findViewById(R.id.rvSections);
        swipeRefreshSections = findViewById(R.id.swipeRefreshSections);
        loadingOverlay = new LoadingOverlayController(this);
        swipeRefreshSections.setColorSchemeResources(R.color.primary);
        swipeRefreshSections.setOnRefreshListener(this::handlePullToRefresh);

        // Bind Live Digital Clock & Command Card Views
        tvClockGreeting = findViewById(R.id.tvClockGreeting);
        tvClockRoleBadge = findViewById(R.id.tvClockRoleBadge);
        layoutClockReadout = findViewById(R.id.layoutClockReadout);
        clockHoursMinutes = findViewById(R.id.clockHoursMinutes);
        clockSeconds = findViewById(R.id.clockSeconds);
        clockAmPm = findViewById(R.id.clockAmPm);
        clockFullDate = findViewById(R.id.clockFullDate);
        ivClockSyncDot = findViewById(R.id.ivClockSyncDot);
        tvClockSyncStatus = findViewById(R.id.tvClockSyncStatus);
        setupLiveClockCard();

        sectionAdapter = new SectionAdapter(
                section -> {
                    Intent intent = new Intent(MainActivity.this, SectionDetailActivity.class);
                    intent.putExtra(SectionDetailActivity.EXTRA_SECTION_ID, section.id);
                    intent.putExtra(SectionDetailActivity.EXTRA_SECTION_NAME, section.sectionName);
                    startActivity(intent);
                },
                this::showRenameSectionDialog,
                this::confirmDeleteSectionFolder
        );

        int spanCount = calculateAdaptiveSpanCount();
        rvSections.setLayoutManager(new GridLayoutManager(this, spanCount));
        rvSections.setAdapter(sectionAdapter);

        MaterialButton btnVerifyQrMain = findViewById(R.id.btnVerifyQrMain);
        MaterialButton btnOpenAuditLogMain = findViewById(R.id.btnOpenAuditLogMain);
        MaterialButton btnBackupRestoreMain = findViewById(R.id.btnBackupRestoreMain);
        fabNewSection = findViewById(R.id.fabNewSection);

        btnActiveRoomSwitcher.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, RoomLobbyActivity.class)));
        btnRoomMembersMain.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, RoomMembersActivity.class)));
        btnUserProfileMain.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, ProfileActivity.class)));

        btnVerifyQrMain.setOnClickListener(v -> showQrScanModeChooserDialog());
        btnOpenAuditLogMain.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AuditLogActivity.class);
            startActivity(intent);
        });
        btnBackupRestoreMain.setOnClickListener(v -> showBackupRestoreDialog());
        fabNewSection.setOnClickListener(v -> showCreateSectionDialog());
        btnSyncStatusChip.setOnClickListener(v -> handleManualSyncClick());

        // Listen for instant member removal / ban session revocation
        FirestoreRealtimeSyncManager.getInstance(this).setRoomAccessRevokedListener((roomId, reason) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            new MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle("Room Access Revoked")
                    .setMessage(reason)
                    .setCancelable(false)
                    .setPositiveButton("Open Room Lobby", (d, w) -> {
                        startActivity(new Intent(MainActivity.this, RoomLobbyActivity.class));
                    })
                    .show();
        });

        // Auto-claim pre-update legacy records into default Private Room with zero data loss
        repository.ensureActiveRoomAndClaimLegacyData(new PaymentRepository.ResultCallback<RoomEntity>() {
            @Override
            public void onSuccess(RoomEntity result) {
                FirebaseSyncManager.initializeAutoSync(MainActivity.this);
                refreshRoomAndRoleUi();
                rebindRootSectionsObserverIfNeeded();
            }

            @Override
            public void onError(String message) {
                FirebaseSyncManager.initializeAutoSync(MainActivity.this);
                refreshRoomAndRoleUi();
                rebindRootSectionsObserverIfNeeded();
            }
        });

        observeSyncStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FirebaseSyncManager.refreshSyncStatusUi(this);
        refreshRoomAndRoleUi();
        rebindRootSectionsObserverIfNeeded();
        updateClockGreeting();
        updateClockRoleBadge();
        CloudImageStorageManager.flushPendingUploads(this);
        sectionAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        if (loadingOverlay != null) {
            loadingOverlay.release();
        }
        super.onDestroy();
    }

    private void refreshRoomAndRoleUi() {
        String roomName = (currentRoom != null && currentRoom.roomName != null && !currentRoom.roomName.trim().isEmpty())
                ? currentRoom.roomName.trim()
                : sessionManager.getActiveRoomName();
        String role = sessionManager.getActiveRoomRole();
        boolean isArchived = currentRoom != null && currentRoom.isArchived;

        if (isArchived) {
            btnActiveRoomSwitcher.setText("Room: " + roomName + " (ARCHIVED)");
            fabNewSection.setVisibility(View.GONE);
        } else if (sessionManager.isPendingApproval()) {
            btnActiveRoomSwitcher.setText("Room: " + roomName + " (PENDING APPROVAL)");
            fabNewSection.setVisibility(View.GONE);
        } else {
            btnActiveRoomSwitcher.setText("Room: " + roomName + " (" + role + ")");
            fabNewSection.setVisibility(sessionManager.canCreateOrEditRecords() ? View.VISIBLE : View.GONE);
        }
    }

    private void rebindRootSectionsObserverIfNeeded() {
        String activeRoomId = sessionManager.getActiveRoomId();
        if (activeRoomId != null && !activeRoomId.equals(observedRoomDetailsId)) {
            if (currentRoomLiveData != null) {
                currentRoomLiveData.removeObservers(this);
            }
            observedRoomDetailsId = activeRoomId;
            currentRoomLiveData = repository.observeRoomById(activeRoomId);
            currentRoomLiveData.observe(this, room -> {
                currentRoom = room;
                if (currentRoom != null && currentRoom.organizationSubtitle != null && !currentRoom.organizationSubtitle.trim().isEmpty()) {
                    if (tvMainOrgSubtitle != null) {
                        tvMainOrgSubtitle.setText(currentRoom.organizationSubtitle.trim());
                        tvMainOrgSubtitle.setVisibility(View.VISIBLE);
                    }
                } else {
                    if (tvMainOrgSubtitle != null) {
                        tvMainOrgSubtitle.setVisibility(View.GONE);
                    }
                }
                refreshRoomAndRoleUi();
            });
        }
        if (activeRoomId != null && !activeRoomId.equals(observedMemberRoomId)) {
            if (currentMemberLiveData != null) {
                currentMemberLiveData.removeObservers(this);
            }
            observedMemberRoomId = activeRoomId;
            currentMemberLiveData = repository.observeCurrentMemberInRoom(activeRoomId);
            currentMemberLiveData.observe(this, member -> {
                if (member != null) {
                    boolean wasPending = sessionManager.isPendingApproval();
                    sessionManager.updateActiveRoomRole(member.role);
                    sessionManager.updateActiveRoomMemberStatus(member.status, null);
                    refreshRoomAndRoleUi();
                    if (wasPending && member.isActive()) {
                        Snackbar.make(rootView, "Your join request has been approved!", Snackbar.LENGTH_LONG).show();
                        rebindRootSectionsObserverIfNeeded();
                    }
                }
            });
        }
        if (sessionManager.isPendingApproval()) {
            if (currentRootSectionsLiveData != null) {
                currentRootSectionsLiveData.removeObservers(this);
                currentRootSectionsLiveData = null;
            }
            observedRoomId = null;
            sectionAdapter.submitList(Collections.emptyList());
            tvSectionCountBadge.setText("Waiting for Owner Approval");
            layoutEmptySections.setVisibility(View.VISIBLE);
            return;
        }
        if (currentRootSectionsLiveData != null && activeRoomId != null && activeRoomId.equals(observedRoomId)) {
            return;
        }
        if (currentRootSectionsLiveData != null) {
            currentRootSectionsLiveData.removeObservers(this);
        }
        observedRoomId = activeRoomId;
        currentRootSectionsLiveData = repository.observeRootSectionSummaries();
        triggerRoomEntrySyncIfNeeded(activeRoomId);
        currentRootSectionsLiveData.observe(this, sections -> {
            if (sessionManager.isPendingApproval()) {
                sectionAdapter.submitList(Collections.emptyList());
                tvSectionCountBadge.setText("Waiting for Owner Approval");
                layoutEmptySections.setVisibility(View.VISIBLE);
                return;
            }
            sectionAdapter.submitList(sections);
            int count = sections != null ? sections.size() : 0;
            tvSectionCountBadge.setText(count + (count == 1 ? " Folder" : " Folders"));
            layoutEmptySections.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        });
    }

    private int calculateAdaptiveSpanCount() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float screenWidthDp = dm.widthPixels / dm.density;
        int cols = (int) (screenWidthDp / 340f);
        return Math.max(1, Math.min(3, cols));
    }

    /** First time a room becomes active on this screen, pull the latest cloud state behind the overlay. */
    private void triggerRoomEntrySyncIfNeeded(String activeRoomId) {
        if (activeRoomId == null || activeRoomId.isEmpty() || activeRoomId.equals(lastRoomEntrySyncId)) {
            return;
        }
        if (!FirebaseSyncManager.isFirebaseConfigured(this) || !FirebaseSyncManager.isNetworkConnected(this)) {
            return;
        }
        lastRoomEntrySyncId = activeRoomId;
        loadingOverlay.show("Syncing Workspace...", "Updating across all connected devices");
        FirebaseSyncManager.runForegroundSync(this, success -> loadingOverlay.hide());
    }

    private void handlePullToRefresh() {
        if (!FirebaseSyncManager.isFirebaseConfigured(this)) {
            swipeRefreshSections.setRefreshing(false);
            Snackbar.make(rootView, "Local mode: Firebase is not configured, nothing to refresh.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (!FirebaseSyncManager.isNetworkConnected(this)) {
            swipeRefreshSections.setRefreshing(false);
            Snackbar.make(rootView, "Offline: waiting for internet to refresh from the cloud.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        FirebaseSyncManager.runForegroundSync(this, success -> {
            if (swipeRefreshSections != null) {
                swipeRefreshSections.setRefreshing(false);
            }
        });
    }

    private void observeSyncStatus() {
        FirebaseSyncManager.observeSyncUiState().observe(this, state -> {
            if (state == null) {
                return;
            }
            updateClockSyncStatus(state);
            switch (state) {
                case LOCAL_MODE_UNCONFIGURED:
                    btnSyncStatusChip.setText("Local Mode");
                    btnSyncStatusChip.setIconResource(R.drawable.ic_cloud_off);
                    break;
                case OFFLINE_WAITING_WIFI:
                    btnSyncStatusChip.setText("Offline Mode");
                    btnSyncStatusChip.setIconResource(R.drawable.ic_wifi_off);
                    break;
                case SYNCING:
                    btnSyncStatusChip.setText("Syncing...");
                    btnSyncStatusChip.setIconResource(R.drawable.ic_cloud_sync);
                    break;
                case PENDING_RECORDS:
                    btnSyncStatusChip.setText("Sync Now");
                    btnSyncStatusChip.setIconResource(R.drawable.ic_cloud_sync);
                    break;
                case SYNCED:
                    btnSyncStatusChip.setText("Synced");
                    btnSyncStatusChip.setIconResource(R.drawable.ic_cloud_done);
                    break;
            }
        });
    }

    private void setupLiveClockCard() {
        boolean is24h = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean(PREF_CLOCK_24H, false);
        applyClockFormat(is24h);

        if (layoutClockReadout != null) {
            layoutClockReadout.setOnClickListener(v -> {
                boolean current = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean(PREF_CLOCK_24H, false);
                boolean next = !current;
                getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean(PREF_CLOCK_24H, next).apply();
                applyClockFormat(next);
                Snackbar.make(
                        rootView,
                        next ? "Switched to 24-hour clock" : "Switched to 12-hour clock",
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        }

        updateClockGreeting();
        updateClockRoleBadge();
        updateClockSyncStatus(FirebaseSyncManager.observeSyncUiState().getValue());
    }

    private void applyClockFormat(boolean is24Hour) {
        if (clockHoursMinutes != null) {
            clockHoursMinutes.setFormat12Hour(is24Hour ? "HH:mm" : "hh:mm");
            clockHoursMinutes.setFormat24Hour(is24Hour ? "HH:mm" : "hh:mm");
        }
        if (clockAmPm != null) {
            clockAmPm.setVisibility(is24Hour ? View.GONE : View.VISIBLE);
        }
    }

    private void updateClockGreeting() {
        if (tvClockGreeting == null) {
            return;
        }
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        String greeting;
        if (hour >= 5 && hour < 12) {
            greeting = "Good Morning";
        } else if (hour >= 12 && hour < 18) {
            greeting = "Good Afternoon";
        } else {
            greeting = "Good Evening";
        }

        String displayName = "Officer";
        com.google.firebase.auth.FirebaseUser fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (fbUser != null && fbUser.getDisplayName() != null && !fbUser.getDisplayName().trim().isEmpty()) {
            String[] parts = fbUser.getDisplayName().trim().split("\\s+");
            displayName = parts[0];
        } else if (sessionManager != null) {
            String officer = sessionManager.getCurrentUserName();
            if (officer != null && !officer.trim().isEmpty()) {
                String[] parts = officer.trim().split("\\s+");
                displayName = parts[0];
            }
        }
        tvClockGreeting.setText(greeting + ", " + displayName);
    }

    private void updateClockRoleBadge() {
        if (tvClockRoleBadge == null) {
            return;
        }
        String role = sessionManager != null ? sessionManager.getActiveRoomRole() : "OWNER";
        if (role == null || role.trim().isEmpty()) {
            role = "OWNER";
        }
        tvClockRoleBadge.setText(role.toUpperCase(Locale.US));
        if ("OWNER".equalsIgnoreCase(role)) {
            tvClockRoleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_paid_green));
        } else if ("COLLECTOR".equalsIgnoreCase(role)) {
            tvClockRoleBadge.setTextColor(ContextCompat.getColor(this, R.color.gcash_blue));
        } else {
            tvClockRoleBadge.setTextColor(ContextCompat.getColor(this, R.color.clock_muted_slate));
        }
    }

    private void updateClockSyncStatus(FirebaseSyncManager.SyncUiState state) {
        if (tvClockSyncStatus == null || ivClockSyncDot == null) {
            return;
        }
        if (state == null || state == FirebaseSyncManager.SyncUiState.LOCAL_MODE_UNCONFIGURED) {
            tvClockSyncStatus.setText("Local Mode");
            ivClockSyncDot.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_warning_amber)));
        } else if (state == FirebaseSyncManager.SyncUiState.OFFLINE_WAITING_WIFI) {
            tvClockSyncStatus.setText("Offline Mode");
            ivClockSyncDot.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_warning_amber)));
        } else {
            tvClockSyncStatus.setText("Cloud Synced");
            ivClockSyncDot.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_paid_green)));
        }
    }

    private void handleManualSyncClick() {
        if (!FirebaseSyncManager.isFirebaseConfigured(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Local Mode (Firebase Pending Setup)")
                    .setMessage("All sections, payments, expenses, and QR receipts are safely stored in your local SQLite database.\n\nWhen you add google-services.json into app/ (see FIREBASE_SETUP.md), all existing offline records will automatically migrate to Firebase Firestore & Storage over Wi-Fi.")
                    .setPositiveButton("Got It", null)
                    .show();
            return;
        }
        if (!FirebaseSyncManager.isNetworkConnected(this)) {
            Snackbar.make(rootView, "Waiting for Wi-Fi / internet connection to sync pending records.", Snackbar.LENGTH_LONG).show();
            return;
        }
        Snackbar.make(rootView, "Syncing local records to Firebase Cloud...", Snackbar.LENGTH_SHORT).show();
        loadingOverlay.show("Syncing Workspace...", "Updating across all connected devices");
        FirebaseSyncManager.runForegroundSync(this, success -> loadingOverlay.hide());
    }

    private void showCreateSectionDialog() {
        if (currentRoom != null && currentRoom.isArchived) {
            com.example.qr_paymentscan_cash_n_online.util.RoomPolicyGuard.showArchivedRoomNotice(this);
            return;
        }
        if (!sessionManager.canCreateOrEditRecords()) {
            Snackbar.make(rootView, "Auditors have read-only access and cannot create folders.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_section, null);
        TextInputLayout tilName = dialogView.findViewById(R.id.tilSectionName);
        TextInputEditText etName = dialogView.findViewById(R.id.etSectionName);
        TextInputEditText etDesc = dialogView.findViewById(R.id.etSectionDescription);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Create Main Section Folder")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create Folder", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = etName.getText() != null ? etName.getText().toString().trim() : "";
                String desc = etDesc.getText() != null ? etDesc.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    tilName.setError("Section name is required (e.g., BSCS 3A)");
                    return;
                }
                tilName.setError(null);

                loadingOverlay.show("Creating Folder...", "Updating across all connected devices");
                repository.createSection(name, desc, null, new PaymentRepository.ResultCallback<SectionEntity>() {
                    @Override
                    public void onSuccess(SectionEntity result) {
                        loadingOverlay.hide();
                        dialog.dismiss();
                        Snackbar.make(rootView, "Created section folder: " + result.sectionName, Snackbar.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        tilName.setError(message);
                    }
                });
            });
        });

        dialog.show();
    }

    private void showRenameSectionDialog(SectionSummary section) {
        if (currentRoom != null && currentRoom.isArchived) {
            com.example.qr_paymentscan_cash_n_online.util.RoomPolicyGuard.showArchivedRoomNotice(this);
            return;
        }
        if (!sessionManager.canCreateOrEditRecords()) {
            Snackbar.make(rootView, "Auditors have read-only access and cannot rename folders.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etNewName = new EditText(this);
        etNewName.setHint("New Folder Name");
        etNewName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etNewName.setText(section.sectionName);
        etNewName.setSelection(etNewName.getText().length());
        container.addView(etNewName);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Rename Folder")
                .setMessage("Rename \"" + section.sectionName + "\" without affecting any child sub-folders, payment receipts, or expenses.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save Name", (d, w) -> {
                    String newName = etNewName.getText().toString().trim();
                    loadingOverlay.show("Renaming Folder...", "Updating across all connected devices");
                    repository.renameSection(section.id, newName, new PaymentRepository.ResultCallback<SectionEntity>() {
                        @Override
                        public void onSuccess(SectionEntity result) {
                            loadingOverlay.hide();
                            Snackbar.make(rootView, "Renamed folder to \"" + result.sectionName + "\"", Snackbar.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            loadingOverlay.hide();
                            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                        }
                    });
                })
                .show();
    }

    private void confirmDeleteSectionFolder(SectionSummary section) {
        if (currentRoom != null && currentRoom.isArchived) {
            com.example.qr_paymentscan_cash_n_online.util.RoomPolicyGuard.showArchivedRoomNotice(this);
            return;
        }
        if (!sessionManager.canVoidOrDeleteOrManageMembers()) {
            Snackbar.make(rootView, "Only the Room OWNER can delete folders.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        repository.previewFolderDeletionImpact(section.id, new PaymentRepository.ResultCallback<PaymentRepository.FolderDeleteSummary>() {
            @Override
            public void onSuccess(PaymentRepository.FolderDeleteSummary summary) {
                String message = "Are you sure you want to permanently delete folder \"" + summary.folderName + "\"?\n\n"
                        + "This will remove:\n"
                        + "• " + summary.subfolderCount + " nested sub-folder(s)\n"
                        + "• " + summary.paymentCount + " student payment receipt(s)\n"
                        + "• " + summary.expenseCount + " expense deduction record(s)\n\n"
                        + "An immutable FOLDER_DELETED entry will be recorded in the Audit Trail.";

                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle("Delete Folder Permanently?")
                        .setMessage(message)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete Permanently", (d, w) -> executeFolderDeletion(section.id))
                        .show();
            }

            @Override
            public void onError(String message) {
                Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void executeFolderDeletion(long sectionId) {
        loadingOverlay.show("Deleting Folder...", "Updating across all connected devices");
        repository.deleteFolderCascade(sectionId, new PaymentRepository.ResultCallback<PaymentRepository.FolderDeleteSummary>() {
            @Override
            public void onSuccess(PaymentRepository.FolderDeleteSummary result) {
                loadingOverlay.hide();
                Snackbar.make(
                        rootView,
                        "Deleted folder \"" + result.folderName + "\" and logged in Audit Trail.",
                        Snackbar.LENGTH_LONG
                ).show();
            }

            @Override
            public void onError(String message) {
                loadingOverlay.hide();
                Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void showQrScanModeChooserDialog() {
        String[] options = new String[]{
                "Scan QR via Live Camera",
                "Verify Saved QR Receipt Image from Gallery"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Verify Student QR Receipt Authenticity")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        ScanOptions scanOptions = new ScanOptions();
                        scanOptions.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
                        scanOptions.setPrompt("Align the Student Payment QR Receipt inside the viewfinder");
                        scanOptions.setBeepEnabled(true);
                        scanOptions.setOrientationLocked(false);
                        qrCameraScanLauncher.launch(scanOptions);
                    } else {
                        qrGalleryImageLauncher.launch("image/*");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void decodeQrFromGalleryUri(Uri imageUri) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            String decodedPayload = SecuritySignatureHelper.decodeQrFromImageUri(this, imageUri);
            runOnUiThread(() -> {
                if (decodedPayload == null || decodedPayload.trim().isEmpty()) {
                    repository.logSecurityQrFailure("(Unreadable image)", "No QR code detected in selected gallery image");
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("No Valid QR Code Detected")
                            .setMessage("Could not decode a QR code from the selected image. Please make sure the QR receipt screenshot is clear and uncropped.")
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    showVerificationBottomSheet(decodedPayload);
                }
            });
        });
    }

    private void showVerificationBottomSheet(String rawQrPayload) {
        ReceiptVerifierBottomSheet sheet = ReceiptVerifierBottomSheet.newInstance(rawQrPayload);
        sheet.show(getSupportFragmentManager(), "ReceiptVerifierBottomSheet");
    }

    private void showBackupRestoreDialog() {
        String[] options = new String[]{
                "Export Local Database Backup (.json)",
                "Restore Local Database from Backup (.json)",
                "Trigger Cloud Sync Now"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Offline Backup, Restore & Cloud Sync")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        String defaultName = "PaymentTracker_Backup_"
                                + CurrencyFormatter.formatFileTimestamp(System.currentTimeMillis()) + ".json";
                        createBackupJsonLauncher.launch(defaultName);
                    } else if (which == 1) {
                        openBackupJsonLauncher.launch(new String[]{"application/json", "*/*"});
                    } else {
                        handleManualSyncClick();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performJsonBackupExport(Uri targetUri) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                int count = LocalStorageManager.exportDatabaseToJsonUri(this, targetUri);
                runOnUiThread(() -> Snackbar.make(
                        rootView,
                        "Backup saved (" + count + " payment records exported).",
                        Snackbar.LENGTH_LONG
                ).show());
            } catch (Exception e) {
                runOnUiThread(() -> Snackbar.make(
                        rootView,
                        "Backup failed: " + e.getMessage(),
                        Snackbar.LENGTH_LONG
                ).show());
            }
        });
    }

    private void performJsonBackupRestore(Uri sourceUri) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                int restored = LocalStorageManager.restoreDatabaseFromJsonUri(this, sourceUri);
                repository.ensureActiveRoomAndClaimLegacyData(null);
                runOnUiThread(() -> Snackbar.make(
                        rootView,
                        "Restored " + restored + " payment receipt(s) from backup.",
                        Snackbar.LENGTH_LONG
                ).show());
            } catch (Exception e) {
                runOnUiThread(() -> Snackbar.make(
                        rootView,
                        "Restore failed: " + e.getMessage(),
                        Snackbar.LENGTH_LONG
                ).show());
            }
        });
    }
}
