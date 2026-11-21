package com.example.qr_paymentscan_cash_n_online.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.AuditLogEntity;
import com.example.qr_paymentscan_cash_n_online.data.ExpenseEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.data.RoomMemberEntity;
import com.example.qr_paymentscan_cash_n_online.data.RosterStudentEntity;
import com.example.qr_paymentscan_cash_n_online.data.SectionEntity;
import com.example.qr_paymentscan_cash_n_online.util.SectionFinancialCalculator;
import com.example.qr_paymentscan_cash_n_online.util.DuplicatePaymentGuard;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages live Firestore addSnapshotListeners scoped to the active Private Room (rooms/{roomId})
 * so multi-device treasurers, collectors, and auditors see folder creations, renames, payments,
 * expenses, void actions, pending join requests, member role/approval changes, and instant
 * session revocations in real time while maintaining offline-first Room SQLite persistence.
 */
public final class FirestoreRealtimeSyncManager {

    public interface RoomAccessRevokedListener {
        void onAccessRevoked(String roomId, String reason);
    }

    private static final String COL_SECTIONS = "sections";
    private static final String COL_PAYMENTS = "payments";
    private static final String COL_EXPENSES = "expenses";
    private static final String COL_ROSTER = "roster_students";
    private static final String COL_AUDIT_LOGS = "audit_logs";

    /** Single-threaded so ADDED / MODIFIED / REMOVED changes are applied to Room strictly in order. */
    private static final ExecutorService SNAPSHOT_EXECUTOR = Executors.newSingleThreadExecutor();

    private static volatile FirestoreRealtimeSyncManager INSTANCE;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ListenerRegistration> activeRegistrations = new ArrayList<>();
    private String listeningRoomId = null;
    private RoomAccessRevokedListener accessRevokedListener;

    private FirestoreRealtimeSyncManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static FirestoreRealtimeSyncManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (FirestoreRealtimeSyncManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new FirestoreRealtimeSyncManager(context);
                }
            }
        }
        return INSTANCE;
    }

    public synchronized void setRoomAccessRevokedListener(RoomAccessRevokedListener listener) {
        this.accessRevokedListener = listener;
    }

    public synchronized void startListeningForRoom(String roomId) {
        if (roomId == null || roomId.trim().isEmpty()) {
            stopListening();
            return;
        }
        if (!FirebaseSyncManager.isFirebaseConfigured(appContext)) {
            return;
        }
        if (roomId.equals(listeningRoomId) && !activeRegistrations.isEmpty()) {
            return;
        }
        stopListening();
        listeningRoomId = roomId;

        try {
            FirebaseFirestore firestore = FirebaseFirestore.getInstance();
            AppDatabase db = AppDatabase.getInstance(appContext);
            UserSessionManager session = UserSessionManager.getInstance(appContext);
            session.syncFromFirebaseAuthIfAvailable();
            String currentUserId = resolveCurrentUserId(session);

            // 1. Listen to Room metadata (room name, lock state, approval gate, expiration)
            ListenerRegistration roomReg = firestore.collection("rooms")
                    .document(roomId)
                    .addSnapshotListener((snapshot, error) -> {
                        if (error != null || snapshot == null || !snapshot.exists()) {
                            return;
                        }
                        AppDatabase.DB_EXECUTOR.execute(() -> applyRemoteRoomSnapshot(db, snapshot));
                    });
            activeRegistrations.add(roomReg);

            // 1b. Listen to isolated room security secrets (rooms/{roomId}/private/security)
            ListenerRegistration privateSecReg = firestore.collection("rooms")
                    .document(roomId)
                    .collection("private")
                    .document("security")
                    .addSnapshotListener((snapshot, error) -> {
                        if (error != null || snapshot == null || !snapshot.exists()) {
                            return;
                        }
                        AppDatabase.DB_EXECUTOR.execute(() -> applyRemotePrivateSecuritySnapshot(db, roomId, snapshot));
                    });
            activeRegistrations.add(privateSecReg);

            // 2a. Listen directly to current user's own member document (works even while PENDING_APPROVAL)
            if (currentUserId != null && !currentUserId.isEmpty()) {
                ListenerRegistration selfMemberReg = firestore.collection("rooms")
                        .document(roomId)
                        .collection("members")
                        .document(currentUserId)
                        .addSnapshotListener((snapshot, error) -> {
                            if (error != null || snapshot == null) {
                                return;
                            }
                            AppDatabase.DB_EXECUTOR.execute(() -> {
                                if (!snapshot.exists()) {
                                    RoomMemberEntity localSelf = db.roomMemberDao().findMemberSync(roomId, currentUserId);
                                    RoomEntity localRoom = db.roomDao().findByIdSync(roomId);
                                    boolean isOwner = localRoom != null && currentUserId.equals(localRoom.ownerUserId);
                                    if (localSelf != null && !isOwner) {
                                        db.roomMemberDao().deleteMember(roomId, currentUserId);
                                        handleCurrentUserRevoked(roomId, "Your membership or join request in this room was removed by the Room Owner.");
                                    }
                                    return;
                                }
                                boolean wasPending = session.isPendingApproval();
                                applyRemoteMemberSnapshot(db, roomId, snapshot, currentUserId);
                                String newStatus = snapshot.getString("status");
                                if (wasPending && RoomMemberEntity.STATUS_ACTIVE.equalsIgnoreCase(newStatus)) {
                                    // Restart listeners so sections, payments, expenses, and audit_logs stream now that user is ACTIVE
                                    mainHandler.post(() -> {
                                        synchronized (FirestoreRealtimeSyncManager.this) {
                                            listeningRoomId = null;
                                            startListeningForRoom(roomId);
                                        }
                                    });
                                }
                            });
                        });
                activeRegistrations.add(selfMemberReg);
            }

            // 2b. Listen to entire Room Members collection (ACTIVE, PENDING_APPROVAL, BANNED, and remote deletions)
            ListenerRegistration membersReg = firestore.collection("rooms")
                    .document(roomId)
                    .collection("members")
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null || snapshots == null) {
                            return;
                        }
                        syncRemoteMembersSnapshot(roomId, snapshots);
                    });
            activeRegistrations.add(membersReg);

            // 3. Listen to Room Sections / Sub-folders
            ListenerRegistration sectionsReg = firestore.collection("rooms")
                    .document(roomId)
                    .collection("sections")
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null || snapshots == null) {
                            return;
                        }
                        SNAPSHOT_EXECUTOR.execute(() -> processCollectionSnapshot(db, roomId, COL_SECTIONS, snapshots));
                    });
            activeRegistrations.add(sectionsReg);

            // 4. Listen to Room Payments
            ListenerRegistration paymentsReg = firestore.collection("rooms")
                    .document(roomId)
                    .collection("payments")
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null || snapshots == null) {
                            return;
                        }
                        SNAPSHOT_EXECUTOR.execute(() -> processCollectionSnapshot(db, roomId, COL_PAYMENTS, snapshots));
                    });
            activeRegistrations.add(paymentsReg);

            // 5. Listen to Room Expenses
            ListenerRegistration expensesReg = firestore.collection("rooms")
                    .document(roomId)
                    .collection("expenses")
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null || snapshots == null) {
                            return;
                        }
                        SNAPSHOT_EXECUTOR.execute(() -> processCollectionSnapshot(db, roomId, COL_EXPENSES, snapshots));
                    });
            activeRegistrations.add(expensesReg);

            // 6. Listen to Room Audit Logs
            ListenerRegistration logsReg = firestore.collection("rooms")
                    .document(roomId)
                    .collection("audit_logs")
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null || snapshots == null) {
                            return;
                        }
                        SNAPSHOT_EXECUTOR.execute(() -> processCollectionSnapshot(db, roomId, COL_AUDIT_LOGS, snapshots));
                    });
            activeRegistrations.add(logsReg);

            // 7. Listen to Room Roster Students
            ListenerRegistration rosterReg = firestore.collection("rooms")
                    .document(roomId)
                    .collection(COL_ROSTER)
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null || snapshots == null) {
                            return;
                        }
                        SNAPSHOT_EXECUTOR.execute(() -> processCollectionSnapshot(db, roomId, COL_ROSTER, snapshots));
                    });
            activeRegistrations.add(rosterReg);
        } catch (Exception ignored) {
        }
    }

    /**
     * Reconciles a live Firestore QuerySnapshot of rooms/{roomId}/members with local Room SQLite:
     * 1. Upserts every remote member document (ACTIVE, PENDING_APPROVAL, and BANNED) into local room_members.
     * 2. Removes local room_members entries that were deleted/rejected remotely.
     */
    public void syncRemoteMembersSnapshot(String roomId, QuerySnapshot snapshots) {
        if (roomId == null || snapshots == null) {
            return;
        }
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appContext);
                UserSessionManager session = UserSessionManager.getInstance(appContext);
                session.syncFromFirebaseAuthIfAvailable();
                String currentUserId = resolveCurrentUserId(session);

                Set<String> remoteUserIds = new HashSet<>();
                for (DocumentSnapshot doc : snapshots.getDocuments()) {
                    if (!doc.exists()) {
                        continue;
                    }
                    String docUserId = safeStr(doc.getString("userId"), doc.getId());
                    remoteUserIds.add(docUserId);
                    applyRemoteMemberSnapshot(db, roomId, doc, currentUserId);
                }

                // Remove any local member rows that were deleted/rejected remotely
                if (!remoteUserIds.isEmpty()) {
                    RoomEntity localRoom = db.roomDao().findByIdSync(roomId);
                    String ownerUserId = localRoom != null ? localRoom.ownerUserId : null;
                    List<RoomMemberEntity> localMembers = db.roomMemberDao().getMembersByRoomSync(roomId);
                    for (RoomMemberEntity localMember : localMembers) {
                        if (!remoteUserIds.contains(localMember.userId)) {
                            // Keep owner entry if owner doc hasn't synced yet
                            if (ownerUserId != null && ownerUserId.equals(localMember.userId)) {
                                continue;
                            }
                            db.roomMemberDao().deleteMember(roomId, localMember.userId);
                            if (localMember.userId.equals(currentUserId)) {
                                handleCurrentUserRevoked(roomId, "Your membership in this room was removed by the Room Owner.");
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Applies a live Firestore QuerySnapshot of a room sub-collection to local Room SQLite:
     * ADDED / MODIFIED documents are upserted as SYNCED (unless a newer local PENDING edit exists),
     * REMOVED documents delete the matching local row, and on a full server snapshot any local SYNCED
     * row for this room that no longer exists in Firestore is removed (deleted from another device).
     */
    private void processCollectionSnapshot(AppDatabase db, String roomId, String collection, QuerySnapshot snapshots) {
        try {
            for (DocumentChange change : snapshots.getDocumentChanges()) {
                DocumentSnapshot doc = change.getDocument();
                if (change.getType() == DocumentChange.Type.REMOVED) {
                    removeLocalDocument(db, collection, doc);
                } else {
                    upsertLocalDocument(db, roomId, collection, doc);
                }
            }
            boolean fullServerSnapshot = !snapshots.getMetadata().isFromCache()
                    && !snapshots.getMetadata().hasPendingWrites();
            if (fullServerSnapshot) {
                Set<String> remoteKeys = new HashSet<>();
                for (DocumentSnapshot doc : snapshots.getDocuments()) {
                    String key = remoteKeyFor(collection, doc);
                    if (key != null) {
                        remoteKeys.add(key);
                    }
                }
                reconcileLocalWithRemote(db, roomId, collection, remoteKeys);
            }
        } catch (Exception ignored) {
        }
    }

    private void upsertLocalDocument(AppDatabase db, String roomId, String collection, DocumentSnapshot doc) {
        switch (collection) {
            case COL_SECTIONS:
                applyRemoteSectionSnapshot(db, roomId, doc);
                break;
            case COL_PAYMENTS:
                applyRemotePaymentSnapshot(db, roomId, doc);
                break;
            case COL_EXPENSES:
                applyRemoteExpenseSnapshot(db, roomId, doc);
                break;
            case COL_ROSTER:
                applyRemoteRosterStudentSnapshot(db, roomId, doc);
                break;
            case COL_AUDIT_LOGS:
                applyRemoteAuditLogSnapshot(db, roomId, doc);
                break;
            default:
                break;
        }
    }

    private void removeLocalDocument(AppDatabase db, String collection, DocumentSnapshot doc) {
        switch (collection) {
            case COL_SECTIONS: {
                Long id = remoteNumericId(doc, "SEC-");
                if (id != null) {
                    List<Long> allDescendants = SectionFinancialCalculator.getAllDescendantSectionIdsSync(db, id);
                    db.paymentDao().deletePaymentsBySectionIds(allDescendants);
                    db.expenseDao().deleteExpensesBySectionIds(allDescendants);
                    db.rosterStudentDao().deleteRosterBySectionIds(allDescendants);
                    db.sectionDao().deleteSectionsByIds(allDescendants);
                }
                break;
            }
            case COL_PAYMENTS: {
                String receiptId = safeStr(doc.getString("receiptId"), doc.getId());
                db.paymentDao().deletePaymentByReceiptId(receiptId);
                break;
            }
            case COL_EXPENSES: {
                Long id = remoteNumericId(doc, "EXP-");
                if (id != null) {
                    db.expenseDao().deleteExpenseById(id);
                }
                break;
            }
            case COL_ROSTER: {
                Long id = remoteNumericId(doc, "ROSTER-");
                if (id != null) {
                    db.rosterStudentDao().deleteRosterStudentById(id);
                }
                break;
            }
            case COL_AUDIT_LOGS: {
                long timestamp = safeLong(doc.getLong("timestamp"), 0L);
                String actionType = doc.getString("actionType");
                if (timestamp > 0L && actionType != null) {
                    AuditLogEntity existing = db.auditLogDao().findByTimestampAndActionSync(timestamp, actionType);
                    if (existing != null && AuditLogEntity.SYNC_SYNCED.equals(existing.syncStatus)) {
                        db.auditLogDao().deleteLogById(existing.id);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private String remoteKeyFor(String collection, DocumentSnapshot doc) {
        switch (collection) {
            case COL_SECTIONS:
                return stringOrNull(remoteNumericId(doc, "SEC-"));
            case COL_PAYMENTS:
                return safeStr(doc.getString("receiptId"), doc.getId());
            case COL_EXPENSES:
                return stringOrNull(remoteNumericId(doc, "EXP-"));
            case COL_ROSTER:
                return stringOrNull(remoteNumericId(doc, "ROSTER-"));
            case COL_AUDIT_LOGS: {
                long timestamp = safeLong(doc.getLong("timestamp"), 0L);
                String actionType = doc.getString("actionType");
                return (timestamp > 0L && actionType != null) ? (timestamp + "|" + actionType) : null;
            }
            default:
                return null;
        }
    }

    private void reconcileLocalWithRemote(AppDatabase db, String roomId, String collection, Set<String> remoteKeys) {
        switch (collection) {
            case COL_SECTIONS: {
                List<SectionEntity> synced = db.sectionDao().getSyncedSectionsByRoomSync(roomId);
                List<Long> toDelete = new ArrayList<>();
                for (SectionEntity s : synced) {
                    if (!remoteKeys.contains(String.valueOf(s.id))) {
                        toDelete.add(s.id);
                    }
                }
                for (Long delId : toDelete) {
                    List<Long> descendants = SectionFinancialCalculator.getAllDescendantSectionIdsSync(db, delId);
                    db.paymentDao().deletePaymentsBySectionIds(descendants);
                    db.expenseDao().deleteExpensesBySectionIds(descendants);
                    db.rosterStudentDao().deleteRosterBySectionIds(descendants);
                    db.sectionDao().deleteSectionsByIds(descendants);
                }
                break;
            }
            case COL_PAYMENTS: {
                for (PaymentEntity p : db.paymentDao().getSyncedPaymentsByRoomSync(roomId)) {
                    if (!remoteKeys.contains(p.receiptId)) {
                        db.paymentDao().deletePaymentByReceiptId(p.receiptId);
                    }
                }
                break;
            }
            case COL_EXPENSES: {
                for (ExpenseEntity ex : db.expenseDao().getSyncedExpensesByRoomSync(roomId)) {
                    if (!remoteKeys.contains(String.valueOf(ex.id))) {
                        db.expenseDao().deleteExpenseById(ex.id);
                    }
                }
                break;
            }
            case COL_ROSTER: {
                for (RosterStudentEntity r : db.rosterStudentDao().getSyncedRosterByRoomSync(roomId)) {
                    if (!remoteKeys.contains(String.valueOf(r.id))) {
                        db.rosterStudentDao().deleteRosterStudentById(r.id);
                    }
                }
                break;
            }
            case COL_AUDIT_LOGS: {
                for (AuditLogEntity log : db.auditLogDao().getSyncedLogsByRoomSync(roomId)) {
                    if (!remoteKeys.contains(log.timestamp + "|" + log.actionType)) {
                        db.auditLogDao().deleteLogById(log.id);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private void applyRemoteRosterStudentSnapshot(AppDatabase db, String roomId, DocumentSnapshot doc) {
        try {
            Long id = remoteNumericId(doc, "ROSTER-");
            if (id == null) {
                return;
            }
            long remoteUpdatedAt = safeLong(doc.getLong("updatedAt"), 0L);
            RosterStudentEntity local = db.rosterStudentDao().findByIdSync(id);
            if (local != null
                    && "PENDING".equals(local.syncStatus)
                    && local.updatedAt > remoteUpdatedAt) {
                return;
            }
            long sectionId = safeLong(doc.getLong("sectionId"), 0L);
            String firstName = safeStr(doc.getString("firstName"), "");
            String lastName = safeStr(doc.getString("lastName"), "");
            if (sectionId <= 0L || (firstName.isEmpty() && lastName.isEmpty())) {
                return;
            }
            RosterStudentEntity entity = new RosterStudentEntity(
                    sectionId,
                    firstName,
                    lastName,
                    doc.getString("studentNumber"),
                    "SYNCED",
                    remoteUpdatedAt > 0 ? remoteUpdatedAt : System.currentTimeMillis(),
                    roomId
            );
            entity.id = id;
            db.rosterStudentDao().insertOrReplaceRosterStudent(entity);
        } catch (Exception ignored) {
        }
    }

    private static Long remoteNumericId(DocumentSnapshot doc, String docIdPrefix) {
        Long id = doc.getLong("id");
        if (id != null) {
            return id;
        }
        String docId = doc.getId();
        if (docId != null && docId.startsWith(docIdPrefix)) {
            try {
                return Long.parseLong(docId.substring(docIdPrefix.length()));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String stringOrNull(Long value) {
        return value != null ? String.valueOf(value) : null;
    }

    private String resolveCurrentUserId(UserSessionManager session) {
        try {
            FirebaseUser fbUser = FirebaseAuth.getInstance().getCurrentUser();
            if (fbUser != null && !fbUser.getUid().isEmpty()) {
                return fbUser.getUid();
            }
        } catch (Exception ignored) {
        }
        return session.getCurrentUserId();
    }

    /**
     * Forces every listener for the room to detach and re-attach so a fresh server snapshot is delivered
     * (used by pull-to-refresh).
     */
    public synchronized void restartListeningForRoom(String roomId) {
        stopListening();
        startListeningForRoom(roomId);
    }

    public synchronized void stopListening() {
        for (ListenerRegistration reg : activeRegistrations) {
            try {
                reg.remove();
            } catch (Exception ignored) {
            }
        }
        activeRegistrations.clear();
        listeningRoomId = null;
    }

    private void handleCurrentUserRevoked(String roomId, String reason) {
        UserSessionManager session = UserSessionManager.getInstance(appContext);
        if (roomId.equals(session.getActiveRoomId())) {
            session.revokeActiveRoomAccess();
        }
        stopListening();
        final RoomAccessRevokedListener listener;
        synchronized (this) {
            listener = this.accessRevokedListener;
        }
        if (listener != null) {
            mainHandler.post(() -> listener.onAccessRevoked(roomId, reason));
        }
    }

    private void applyRemoteRoomSnapshot(AppDatabase db, DocumentSnapshot doc) {
        try {
            String roomId = doc.getString("roomId");
            if (roomId == null || roomId.isEmpty()) {
                roomId = doc.getId();
            }
            RoomEntity existing = db.roomDao().findByIdSync(roomId);
            String roomName = safeStr(doc.getString("roomName"), existing != null ? existing.roomName : "Shared Room");
            String roomKey = safeStr(doc.getString("roomKey"), existing != null ? existing.roomKey : "");
            String passwordHash = safeStr(doc.getString("roomPasswordHash"), existing != null ? existing.roomPasswordHash : "");
            String roomSalt = safeStr(doc.getString("roomSalt"), existing != null ? existing.roomSalt : null);
            String signingSecret = safeStr(doc.getString("roomSigningSecret"), existing != null ? existing.roomSigningSecret : "");
            String ownerUserId = safeStr(doc.getString("ownerUserId"), existing != null ? existing.ownerUserId : "");
            String ownerName = safeStr(doc.getString("ownerName"), existing != null ? existing.ownerName : "Owner");
            long createdAt = safeLong(doc.getLong("createdAt"), existing != null ? existing.createdAt : System.currentTimeMillis());
            long updatedAt = safeLong(doc.getLong("updatedAt"), createdAt);

            Boolean lockedVal = doc.getBoolean("isLocked");
            boolean isLocked = lockedVal != null ? lockedVal : (existing != null && existing.isLocked);
            Boolean approvalVal = doc.getBoolean("requireApproval");
            boolean requireApproval = approvalVal != null ? approvalVal : (existing == null || existing.requireApproval);
            long keyExpiresAt = safeLong(doc.getLong("keyExpiresAt"), existing != null ? existing.keyExpiresAt : 0L);

            String organizationSubtitle = safeStr(doc.getString("organizationSubtitle"), existing != null ? existing.organizationSubtitle : "");
            String receiptPrefix = safeStr(doc.getString("receiptPrefix"), existing != null ? existing.receiptPrefix : RoomEntity.DEFAULT_RECEIPT_PREFIX);
            String receiptFooterNote = safeStr(doc.getString("receiptFooterNote"), existing != null ? existing.receiptFooterNote : "");
            String enabledPaymentModes = safeStr(doc.getString("enabledPaymentModes"), existing != null ? existing.enabledPaymentModes : RoomEntity.DEFAULT_ENABLED_PAYMENT_MODES);
            Boolean proofRequiredVal = doc.getBoolean("requireProofPhotoForOnline");
            boolean requireProofPhotoForOnline = proofRequiredVal != null ? proofRequiredVal : (existing != null && existing.requireProofPhotoForOnline);
            String defaultJoinRole = safeStr(doc.getString("defaultJoinRole"), existing != null ? existing.defaultJoinRole : RoomEntity.ROLE_AUDITOR);
            Boolean archivedVal = doc.getBoolean("isArchived");
            boolean isArchived = archivedVal != null ? archivedVal : (existing != null && existing.isArchived);

            if (roomKey.isEmpty()) {
                return;
            }
            RoomEntity entity = new RoomEntity(
                    roomId,
                    roomName,
                    roomKey,
                    passwordHash,
                    signingSecret,
                    ownerUserId,
                    ownerName,
                    createdAt,
                    updatedAt,
                    roomSalt,
                    isLocked,
                    requireApproval,
                    keyExpiresAt,
                    0,
                    0L
            );
            entity.organizationSubtitle = organizationSubtitle;
            entity.receiptPrefix = receiptPrefix;
            entity.receiptFooterNote = receiptFooterNote;
            entity.enabledPaymentModes = enabledPaymentModes;
            entity.requireProofPhotoForOnline = requireProofPhotoForOnline;
            entity.defaultJoinRole = defaultJoinRole;
            entity.isArchived = isArchived;
            if (existing != null) {
                entity.isCloudRoom = existing.isCloudRoom;
                entity.cachedForUserId = existing.cachedForUserId;
            }

            db.roomDao().insertOrReplaceRoom(entity);

            UserSessionManager session = UserSessionManager.getInstance(appContext);
            if (roomId.equals(session.getActiveRoomId())) {
                session.updateActiveRoomName(roomName);
                if (!signingSecret.isEmpty()) {
                    session.updateActiveRoomSigningSecret(signingSecret);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void applyRemotePrivateSecuritySnapshot(AppDatabase db, String roomId, DocumentSnapshot doc) {
        try {
            RoomEntity existing = db.roomDao().findByIdSync(roomId);
            if (existing == null) {
                return;
            }
            String secret = safeStr(doc.getString("roomSigningSecret"), existing.roomSigningSecret);
            String passHash = safeStr(doc.getString("roomPasswordHash"), existing.roomPasswordHash);
            String salt = safeStr(doc.getString("roomSalt"), existing.roomSalt);
            long updatedAt = safeLong(doc.getLong("updatedAt"), System.currentTimeMillis());

            existing.roomSigningSecret = secret;
            existing.roomPasswordHash = passHash;
            existing.roomSalt = salt;
            existing.updatedAt = Math.max(existing.updatedAt, updatedAt);
            db.roomDao().insertOrReplaceRoom(existing);

            UserSessionManager session = UserSessionManager.getInstance(appContext);
            if (roomId.equals(session.getActiveRoomId()) && !secret.isEmpty()) {
                session.updateActiveRoomSigningSecret(secret);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyRemoteMemberSnapshot(
            AppDatabase db,
            String roomId,
            DocumentSnapshot doc,
            String currentUserId
    ) {
        try {
            String userId = safeStr(doc.getString("userId"), doc.getId());
            String userName = safeStr(doc.getString("userName"), "Member");
            String userEmail = safeStr(doc.getString("userEmail"), "");
            String role = safeStr(doc.getString("role"), RoomMemberEntity.ROLE_COLLECTOR);
            String status = safeStr(doc.getString("status"), RoomMemberEntity.STATUS_ACTIVE);
            String deviceInfo = doc.getString("deviceInfo");
            long joinedAt = safeLong(doc.getLong("joinedAt"), System.currentTimeMillis());

            RoomMemberEntity member = new RoomMemberEntity(
                    roomId,
                    userId,
                    userName,
                    userEmail,
                    role,
                    joinedAt,
                    status,
                    deviceInfo
            );
            db.roomMemberDao().insertOrReplaceMember(member);

            if (userId.equals(currentUserId)) {
                UserSessionManager session = UserSessionManager.getInstance(appContext);
                if (RoomMemberEntity.STATUS_BANNED.equalsIgnoreCase(status)) {
                    handleCurrentUserRevoked(roomId, "Your access to this room was revoked and banned by the Room Owner.");
                    return;
                }
                if (roomId.equals(session.getActiveRoomId())) {
                    session.updateActiveRoomRole(role);
                    RoomEntity room = db.roomDao().findByIdSync(roomId);
                    session.updateActiveRoomMemberStatus(status, room != null ? room.roomSigningSecret : null);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void applyRemoteSectionSnapshot(AppDatabase db, String roomId, DocumentSnapshot doc) {
        try {
            Long id = doc.getLong("id");
            String sectionName = doc.getString("sectionName");
            if (id == null || sectionName == null || sectionName.trim().isEmpty()) {
                return;
            }
            long remoteUpdatedAt = safeLong(doc.getLong("updatedAt"), 0L);
            SectionEntity local = db.sectionDao().findByIdSync(id);
            if (local != null) {
                if (SectionEntity.SYNC_PENDING.equals(local.syncStatus) && local.updatedAt > remoteUpdatedAt) {
                    return;
                }
            }
            String description = safeStr(doc.getString("description"), "");
            long createdAt = safeLong(doc.getLong("createdAt"), System.currentTimeMillis());
            Long parentFolderId = doc.getLong("parentFolderId");
            double targetFee = safeDouble(doc.getDouble("targetFeeAmount"), 0.0);
            Boolean mergeGrossVal = doc.getBoolean("mergeGrossToParent");
            boolean mergeGross = mergeGrossVal != null ? mergeGrossVal : (local != null && local.mergeGrossToParent);

            SectionEntity entity = new SectionEntity(
                    sectionName.trim(),
                    description,
                    createdAt,
                    parentFolderId,
                    targetFee,
                    SectionEntity.SYNC_SYNCED,
                    remoteUpdatedAt > 0 ? remoteUpdatedAt : createdAt,
                    roomId,
                    mergeGross
            );
            entity.id = id;
            db.sectionDao().insertOrReplaceSection(entity);
        } catch (Exception ignored) {
        }
    }

    private void applyRemotePaymentSnapshot(AppDatabase db, String roomId, DocumentSnapshot doc) {
        try {
            String receiptId = doc.getString("receiptId");
            if (receiptId == null || receiptId.trim().isEmpty()) {
                return;
            }
            long remoteUpdatedAt = safeLong(doc.getLong("updatedAt"), 0L);
            PaymentEntity local = db.paymentDao().findByReceiptIdSync(receiptId);
            if (local != null) {
                if (PaymentEntity.SYNC_PENDING.equals(local.syncStatus) && local.updatedAt > remoteUpdatedAt) {
                    return;
                }
            }

            long sectionId = safeLong(doc.getLong("sectionId"), local != null ? local.sectionId : 0L);
            if (sectionId <= 0L) {
                return;
            }
            String sectionName = safeStr(doc.getString("sectionName"), "Section");
            String firstName = safeStr(doc.getString("firstName"), "");
            String lastName = safeStr(doc.getString("lastName"), "");
            String studentNumber = doc.getString("studentNumber");
            String purpose = safeStr(doc.getString("purpose"), "Payment");
            double amount = safeDouble(doc.getDouble("amount"), 0.0);
            String paymentMethod = safeStr(doc.getString("paymentMethod"), PaymentEntity.METHOD_CASH);
            String gcashRef = doc.getString("gcashReferenceNo");
            String remoteProofUrl = doc.getString("remoteProofUrl");
            String status = safeStr(doc.getString("status"), PaymentEntity.STATUS_PAID);
            String paymentStatus = safeStr(doc.getString("paymentStatus"), status);
            double remainingBal = safeDouble(doc.getDouble("remainingBalance"), 0.0);
            String voidReason = doc.getString("voidReason");
            long createdAt = safeLong(doc.getLong("createdAt"), System.currentTimeMillis());
            Long voidedAt = doc.getLong("voidedAt");
            String qrSignature = safeStr(doc.getString("qrSignature"), "");
            String qrPayload = safeStr(doc.getString("qrPayload"), "");
            String actorUserId = doc.getString("actorUserId");
            String actorName = doc.getString("actorName");
            String actorRole = doc.getString("actorRole");
            String studentIdentityKey = safeStr(
                    doc.getString("studentIdentityKey"),
                    DuplicatePaymentGuard.buildStudentIdentityKey(sectionId, firstName, lastName, purpose, 0.0)
            );
            String overrideReason = doc.getString("duplicateOverrideReason");

            PaymentEntity entity = new PaymentEntity(
                    receiptId,
                    sectionId,
                    sectionName,
                    firstName,
                    lastName,
                    studentNumber,
                    purpose,
                    amount,
                    paymentMethod,
                    gcashRef,
                    local != null ? local.proofImagePath : null,
                    remoteProofUrl,
                    status,
                    paymentStatus,
                    remainingBal,
                    voidReason,
                    createdAt,
                    voidedAt,
                    qrSignature,
                    qrPayload,
                    PaymentEntity.SYNC_SYNCED,
                    remoteUpdatedAt > 0 ? remoteUpdatedAt : createdAt,
                    roomId,
                    actorUserId,
                    actorName,
                    actorRole,
                    studentIdentityKey,
                    overrideReason
            );
            entity.proofImageUrl = doc.getString("proofImageUrl");
            if (entity.proofImageUrl == null && local != null) {
                entity.proofImageUrl = local.proofImageUrl;
            }
            if (entity.proofImageUrl != null && !entity.proofImageUrl.isEmpty()) {
                entity.imageSyncStatus = PaymentEntity.IMAGE_STATUS_UPLOADED;
            } else if (local != null) {
                entity.imageSyncStatus = local.imageSyncStatus;
            }
            entity.lastEditedByName = doc.getString("lastEditedByName");
            if (entity.lastEditedByName == null && local != null) {
                entity.lastEditedByName = local.lastEditedByName;
            }
            Long remoteEditedAt = doc.getLong("lastEditedAt");
            if (remoteEditedAt != null && remoteEditedAt > 0L) {
                entity.lastEditedAt = remoteEditedAt;
            } else if (local != null) {
                entity.lastEditedAt = local.lastEditedAt;
            }
            if (local != null) {
                entity.id = local.id;
            }
            db.paymentDao().insertOrReplacePayment(entity);
        } catch (Exception ignored) {
        }
    }

    private void applyRemoteExpenseSnapshot(AppDatabase db, String roomId, DocumentSnapshot doc) {
        try {
            Long id = doc.getLong("id");
            if (id == null) {
                return;
            }
            long remoteUpdatedAt = safeLong(doc.getLong("updatedAt"), 0L);
            ExpenseEntity local = db.expenseDao().findByIdSync(id);
            if (local != null) {
                if (ExpenseEntity.SYNC_PENDING.equals(local.syncStatus) && local.updatedAt > remoteUpdatedAt) {
                    return;
                }
            }
            long sectionId = safeLong(doc.getLong("sectionId"), local != null ? local.sectionId : 0L);
            if (sectionId <= 0L) {
                return;
            }
            String sectionName = safeStr(doc.getString("sectionName"), "Section");
            String title = safeStr(doc.getString("title"), "Expense");
            double amount = safeDouble(doc.getDouble("amount"), 0.0);
            String category = safeStr(doc.getString("category"), "Other");
            String notes = doc.getString("notes");
            String remoteProofUrl = doc.getString("remoteProofUrl");
            String status = safeStr(doc.getString("status"), ExpenseEntity.STATUS_ACTIVE);
            String voidReason = doc.getString("voidReason");
            long createdAt = safeLong(doc.getLong("createdAt"), System.currentTimeMillis());
            Long voidedAt = doc.getLong("voidedAt");
            String actorUserId = doc.getString("actorUserId");
            String actorName = doc.getString("actorName");
            String actorRole = doc.getString("actorRole");

            ExpenseEntity entity = new ExpenseEntity(
                    sectionId,
                    sectionName,
                    title,
                    amount,
                    category,
                    notes,
                    local != null ? local.proofImagePath : null,
                    remoteProofUrl,
                    status,
                    voidReason,
                    createdAt,
                    voidedAt,
                    ExpenseEntity.SYNC_SYNCED,
                    remoteUpdatedAt > 0 ? remoteUpdatedAt : createdAt,
                    roomId,
                    actorUserId,
                    actorName,
                    actorRole
            );
            entity.id = id;
            entity.proofImageUrl = doc.getString("proofImageUrl");
            if (entity.proofImageUrl == null && local != null) {
                entity.proofImageUrl = local.proofImageUrl;
            }
            if (entity.proofImageUrl != null && !entity.proofImageUrl.isEmpty()) {
                entity.imageSyncStatus = ExpenseEntity.IMAGE_STATUS_UPLOADED;
            } else if (local != null) {
                entity.imageSyncStatus = local.imageSyncStatus;
            }
            db.expenseDao().insertOrReplaceExpense(entity);
        } catch (Exception ignored) {
        }
    }

    private void applyRemoteAuditLogSnapshot(AppDatabase db, String roomId, DocumentSnapshot doc) {
        try {
            long timestamp = safeLong(doc.getLong("timestamp"), 0L);
            String actionType = doc.getString("actionType");
            String details = doc.getString("details");
            if (actionType == null || details == null || timestamp <= 0L) {
                return;
            }
            if (db.auditLogDao().findByTimestampAndActionSync(timestamp, actionType) != null) {
                return;
            }
            AuditLogEntity log = new AuditLogEntity(
                    actionType,
                    doc.getLong("sectionId"),
                    doc.getString("sectionName"),
                    doc.getString("receiptId"),
                    details,
                    timestamp,
                    AuditLogEntity.SYNC_SYNCED,
                    timestamp,
                    roomId,
                    doc.getString("actorUserId"),
                    doc.getString("actorName"),
                    doc.getString("actorRole")
            );
            db.auditLogDao().insertAuditLog(log);
        } catch (Exception ignored) {
        }
    }

    private static String safeStr(String value, String fallback) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : fallback;
    }

    private static long safeLong(Long value, long fallback) {
        return value != null ? value : fallback;
    }

    private static double safeDouble(Double value, double fallback) {
        return value != null ? value : fallback;
    }
}
