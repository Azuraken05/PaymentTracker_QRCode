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
import com.example.qr_paymentscan_cash_n_online.data.SectionEntity;
import com.example.qr_paymentscan_cash_n_online.util.DuplicatePaymentGuard;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages live Firestore addSnapshotListeners scoped to the active Private Room (rooms/{roomId})
 * so multi-device treasurers, collectors, and auditors see folder creations, renames, payments,
 * expenses, void actions, member role/approval changes, and instant session revocations in real time
 * while maintaining offline-first Room SQLite persistence.
 */
public final class FirestoreRealtimeSyncManager {

    public interface RoomAccessRevokedListener {
        void onAccessRevoked(String roomId, String reason);
    }

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

            // 2. Listen to Room Members (roles, PENDING_APPROVAL, ACTIVE, BANNED, and instant kick revocation)
            ListenerRegistration membersReg = firestore.collection("rooms")
                    .document(roomId)
                    .collection("members")
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null || snapshots == null) {
                            return;
                        }
                        AppDatabase.DB_EXECUTOR.execute(() -> {
                            String currentUserId = UserSessionManager.getInstance(appContext).getCurrentUserId();
                            for (DocumentChange dc : snapshots.getDocumentChanges()) {
                                DocumentSnapshot doc = dc.getDocument();
                                String docUserId = safeStr(doc.getString("userId"), doc.getId());
                                if (dc.getType() == DocumentChange.Type.REMOVED) {
                                    db.roomMemberDao().deleteMember(roomId, docUserId);
                                    if (docUserId.equals(currentUserId)) {
                                        handleCurrentUserRevoked(roomId, "Your membership in this room was removed by the Room Owner.");
                                    }
                                } else {
                                    applyRemoteMemberSnapshot(db, roomId, doc, currentUserId);
                                }
                            }
                        });
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
                        AppDatabase.DB_EXECUTOR.execute(() -> {
                            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                                applyRemoteSectionSnapshot(db, roomId, doc);
                            }
                        });
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
                        AppDatabase.DB_EXECUTOR.execute(() -> {
                            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                                applyRemotePaymentSnapshot(db, roomId, doc);
                            }
                        });
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
                        AppDatabase.DB_EXECUTOR.execute(() -> {
                            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                                applyRemoteExpenseSnapshot(db, roomId, doc);
                            }
                        });
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
                        AppDatabase.DB_EXECUTOR.execute(() -> {
                            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                                applyRemoteAuditLogSnapshot(db, roomId, doc);
                            }
                        });
                    });
            activeRegistrations.add(logsReg);
        } catch (Exception ignored) {
        }
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
            int failedJoinCount = (int) safeLong(doc.getLong("failedJoinCount"), existing != null ? existing.failedJoinCount : 0L);
            long lockoutUntil = safeLong(doc.getLong("lockoutUntil"), existing != null ? existing.lockoutUntil : 0L);

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
                    failedJoinCount,
                    lockoutUntil
            );
            db.roomDao().insertOrReplaceRoom(entity);

            UserSessionManager session = UserSessionManager.getInstance(appContext);
            if (roomId.equals(session.getActiveRoomId()) && !signingSecret.isEmpty()) {
                session.updateActiveRoomSigningSecret(signingSecret);
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

            RoomMemberEntity existing = db.roomMemberDao().findMemberSync(roomId, userId);
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
            if (existing != null) {
                member.id = existing.id;
            }
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

            SectionEntity entity = new SectionEntity(
                    sectionName.trim(),
                    description,
                    createdAt,
                    parentFolderId,
                    targetFee,
                    SectionEntity.SYNC_SYNCED,
                    remoteUpdatedAt > 0 ? remoteUpdatedAt : createdAt,
                    roomId
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
            db.expenseDao().insertOrReplaceExpense(entity);
        } catch (Exception ignored) {
        }
    }

    private void applyRemoteAuditLogSnapshot(AppDatabase db, String roomId, DocumentSnapshot doc) {
        try {
            Long id = doc.getLong("id");
            long timestamp = safeLong(doc.getLong("timestamp"), 0L);
            String actionType = doc.getString("actionType");
            String details = doc.getString("details");
            if (actionType == null || details == null || timestamp <= 0L) {
                return;
            }
            if (id != null) {
                List<AuditLogEntity> existingLogs = db.auditLogDao().getAllLogsSync();
                for (AuditLogEntity ex : existingLogs) {
                    if (ex.timestamp == timestamp && actionType.equals(ex.actionType)) {
                        return;
                    }
                }
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
