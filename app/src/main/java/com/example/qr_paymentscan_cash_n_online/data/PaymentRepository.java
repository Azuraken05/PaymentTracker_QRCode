package com.example.qr_paymentscan_cash_n_online.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;

import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;
import com.example.qr_paymentscan_cash_n_online.sync.FirestoreRealtimeSyncManager;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.DuplicatePaymentGuard;
import com.example.qr_paymentscan_cash_n_online.util.RoomSecurityHelper;
import com.example.qr_paymentscan_cash_n_online.util.SecuritySignatureHelper;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PaymentRepository {

    public interface ResultCallback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    public static class FolderDeleteSummary {
        public final String folderName;
        public final int subfolderCount;
        public final int paymentCount;
        public final int expenseCount;

        public FolderDeleteSummary(String folderName, int subfolderCount, int paymentCount, int expenseCount) {
            this.folderName = folderName;
            this.subfolderCount = subfolderCount;
            this.paymentCount = paymentCount;
            this.expenseCount = expenseCount;
        }
    }

    public static class CombinedTreeBalance {
        public final int descendantSubfolderCount;
        public final double combinedGrossCollected;
        public final double combinedExpensesDeducted;
        public final double combinedNetBalance;

        public CombinedTreeBalance(
                int descendantSubfolderCount,
                double combinedGrossCollected,
                double combinedExpensesDeducted,
                double combinedNetBalance
        ) {
            this.descendantSubfolderCount = descendantSubfolderCount;
            this.combinedGrossCollected = combinedGrossCollected;
            this.combinedExpensesDeducted = combinedExpensesDeducted;
            this.combinedNetBalance = combinedNetBalance;
        }
    }

    private final AppDatabase database;
    private final RoomDao roomDao;
    private final RoomMemberDao roomMemberDao;
    private final SectionDao sectionDao;
    private final PaymentDao paymentDao;
    private final ExpenseDao expenseDao;
    private final RosterStudentDao rosterStudentDao;
    private final AuditLogDao auditLogDao;
    private final Context appContext;
    private final UserSessionManager sessionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PaymentRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.database = AppDatabase.getInstance(appContext);
        this.roomDao = database.roomDao();
        this.roomMemberDao = database.roomMemberDao();
        this.sectionDao = database.sectionDao();
        this.paymentDao = database.paymentDao();
        this.expenseDao = database.expenseDao();
        this.rosterStudentDao = database.rosterStudentDao();
        this.auditLogDao = database.auditLogDao();
        this.sessionManager = UserSessionManager.getInstance(appContext);
    }

    /**
     * Zero-Data-Loss Legacy Auto-Claim:
     * Ensures that an active Private Room exists for the current user and automatically claims
     * all pre-update legacy sections, payments, expenses, roster students, and audit logs
     * (where room_id IS NULL) into the user's default room as OWNER.
     */
    public void ensureActiveRoomAndClaimLegacyData(ResultCallback<RoomEntity> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                sessionManager.ensureLocalFallbackUserIfNeeded();
                String userId = sessionManager.getCurrentUserId();
                String userName = sessionManager.getCurrentUserName();
                String userEmail = sessionManager.getCurrentUserEmail();

                String activeRoomId = sessionManager.getActiveRoomId();
                RoomEntity activeRoom = null;
                if (activeRoomId != null && !activeRoomId.isEmpty()) {
                    activeRoom = roomDao.findByIdSync(activeRoomId);
                }
                if (activeRoom == null) {
                    List<RoomEntity> existingRooms = roomDao.getAllRoomsSync();
                    if (!existingRooms.isEmpty()) {
                        activeRoom = existingRooms.get(0);
                    }
                }
                long now = System.currentTimeMillis();
                if (activeRoom == null) {
                    String roomId = "ROOM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.US);
                    String roomKey = generateUniqueLocalRoomKey();
                    String defaultPinHash = SecuritySignatureHelper.sha256Hex("123456");
                    // Reuse existing device HMAC secret for the first room so all existing student QR receipts verify identically
                    String signingSecret = SecuritySignatureHelper.getOrCreateSecretKey(appContext);
                    activeRoom = new RoomEntity(
                            roomId,
                            userName + "'s Workspace",
                            roomKey,
                            defaultPinHash,
                            signingSecret,
                            userId,
                            userName,
                            now,
                            now
                    );
                    roomDao.insertOrReplaceRoom(activeRoom);

                    RoomMemberEntity ownerMember = new RoomMemberEntity(
                            roomId,
                            userId,
                            userName,
                            userEmail,
                            RoomMemberEntity.ROLE_OWNER,
                            now
                    );
                    roomMemberDao.insertOrReplaceMember(ownerMember);
                    sessionManager.setActiveRoom(activeRoom, RoomMemberEntity.ROLE_OWNER);
                } else {
                    RoomMemberEntity existingMember = roomMemberDao.findMemberSync(activeRoom.roomId, userId);
                    String role;
                    if (existingMember != null) {
                        role = existingMember.role;
                        if (!userName.equals(existingMember.userName)) {
                            roomMemberDao.updateMemberDisplayName(userId, userName);
                        }
                    } else {
                        role = userId.equals(activeRoom.ownerUserId)
                                ? RoomMemberEntity.ROLE_OWNER
                                : RoomMemberEntity.ROLE_COLLECTOR;
                        roomMemberDao.insertOrReplaceMember(new RoomMemberEntity(
                                activeRoom.roomId,
                                userId,
                                userName,
                                userEmail,
                                role,
                                now
                        ));
                    }
                    sessionManager.setActiveRoom(activeRoom, role);
                }

                // Auto-claim any pre-update records where room_id IS NULL into this active room
                final String targetRoomId = activeRoom.roomId;
                final String actorRole = sessionManager.getActiveRoomRole();
                int legacySections = sectionDao.countLegacySectionsWithoutRoom();
                int legacyPayments = paymentDao.countLegacyPaymentsWithoutRoom();
                int legacyExpenses = expenseDao.countLegacyExpensesWithoutRoom();
                int legacyLogs = auditLogDao.countLegacyAuditLogsWithoutRoom();

                if (legacySections > 0 || legacyPayments > 0 || legacyExpenses > 0 || legacyLogs > 0) {
                    database.runInTransaction(() -> {
                        sectionDao.claimLegacySections(targetRoomId, now);
                        paymentDao.claimLegacyPayments(targetRoomId, userId, userName, actorRole, now);
                        expenseDao.claimLegacyExpenses(targetRoomId, userId, userName, actorRole, now);
                        rosterStudentDao.claimLegacyRosterStudents(targetRoomId, now);
                        auditLogDao.claimLegacyAuditLogs(targetRoomId, userId, userName, actorRole, now);
                    });
                    FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                }

                if (FirebaseSyncManager.isNetworkConnected(appContext)) {
                    FirestoreRealtimeSyncManager.getInstance(appContext).startListeningForRoom(targetRoomId);
                }
                postSuccess(callback, activeRoom);
            } catch (Exception e) {
                postError(callback, "Failed to initialize workspace: " + e.getMessage());
            }
        });
    }

    public LiveData<List<RoomEntity>> observeAllRooms() {
        return roomDao.observeAllRooms();
    }

    public LiveData<RoomEntity> observeRoomById(String roomId) {
        return roomDao.observeById(roomId);
    }

    public LiveData<List<RoomMemberEntity>> observeMembersByRoom(String roomId) {
        return roomMemberDao.observeMembersByRoom(roomId);
    }

    public LiveData<RoomMemberEntity> observeCurrentMemberInRoom(String roomId) {
        return roomMemberDao.observeMember(roomId, sessionManager.getCurrentUserId());
    }

    public void createPrivateRoom(
            String roomName,
            String rawPassword,
            ResultCallback<RoomEntity> callback
    ) {
        createPrivateRoom(roomName, rawPassword, true, callback);
    }

    public void createPrivateRoom(
            String roomName,
            String rawPassword,
            boolean requireApproval,
            ResultCallback<RoomEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                String cleanName = roomName != null ? roomName.trim() : "";
                if (cleanName.isEmpty()) {
                    postError(callback, "Room Name is required.");
                    return;
                }
                String cleanPass = rawPassword != null ? rawPassword.trim() : "";
                String passwordError = RoomSecurityHelper.validatePasswordStrength(cleanPass);
                if (passwordError != null) {
                    postError(callback, passwordError);
                    return;
                }

                sessionManager.ensureLocalFallbackUserIfNeeded();
                String userId = sessionManager.getCurrentUserId();
                String userName = sessionManager.getCurrentUserName();
                String userEmail = sessionManager.getCurrentUserEmail();

                long now = System.currentTimeMillis();
                String roomId = "ROOM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.US);
                String roomKey = generateUniqueLocalRoomKey();
                String roomSalt = RoomSecurityHelper.generateSaltBase64();
                String passwordHash = RoomSecurityHelper.hashPasswordPbkdf2(cleanPass, roomSalt);
                String signingSecret = SecuritySignatureHelper.generateRoomSigningSecretHex();

                RoomEntity room = new RoomEntity(
                        roomId,
                        cleanName,
                        roomKey,
                        passwordHash,
                        signingSecret,
                        userId,
                        userName,
                        now,
                        now,
                        roomSalt,
                        false,
                        requireApproval,
                        0L,
                        0,
                        0L
                );
                roomDao.insertOrReplaceRoom(room);

                RoomMemberEntity ownerMember = new RoomMemberEntity(
                        roomId,
                        userId,
                        userName,
                        userEmail,
                        RoomMemberEntity.ROLE_OWNER,
                        now,
                        RoomMemberEntity.STATUS_ACTIVE,
                        RoomSecurityHelper.getDeviceInfo()
                );
                roomMemberDao.insertOrReplaceMember(ownerMember);
                sessionManager.setActiveRoom(room, RoomMemberEntity.ROLE_OWNER, RoomMemberEntity.STATUS_ACTIVE);

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_ROOM_CREATED,
                        null,
                        cleanName,
                        null,
                        "Created Private Room \"" + cleanName + "\" (Room Key: " + roomKey
                                + ", PBKDF2 Hardened, Require Approval: " + (requireApproval ? "ON" : "OFF") + ")",
                        now
                );

                FirestoreRealtimeSyncManager.getInstance(appContext).startListeningForRoom(roomId);
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, room);
            } catch (Exception e) {
                postError(callback, "Failed to create room: " + e.getMessage());
            }
        });
    }

    public void joinPrivateRoomWithKeyAndPassword(
            String inputRoomKey,
            String inputRawPassword,
            ResultCallback<RoomEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                String cleanKey = inputRoomKey != null ? inputRoomKey.trim().toUpperCase(Locale.US) : "";
                String cleanPass = inputRawPassword != null ? inputRawPassword.trim() : "";
                if (cleanKey.isEmpty() || cleanPass.isEmpty()) {
                    postError(callback, "Both Room Key and Room Password are required.");
                    return;
                }

                RoomEntity matchedRoom = roomDao.findByKeySync(cleanKey);

                long remainingLockout = RoomSecurityHelper.getRemainingLockoutMillis(
                        appContext,
                        cleanKey,
                        matchedRoom != null ? matchedRoom.lockoutUntil : 0L
                );
                if (remainingLockout > 0L) {
                    postError(callback, RoomSecurityHelper.formatLockoutCountdown(remainingLockout));
                    return;
                }

                // Query Firestore if not cached locally or if online
                if (FirebaseSyncManager.isFirebaseConfigured(appContext)
                        && FirebaseSyncManager.isNetworkConnected(appContext)) {
                    try {
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
                            String rPassHash = doc.getString("roomPasswordHash");
                            String rSalt = doc.getString("roomSalt");
                            String rSecret = doc.getString("roomSigningSecret");
                            String rOwnerId = doc.getString("ownerUserId");
                            String rOwnerName = doc.getString("ownerName");
                            Boolean rLocked = doc.getBoolean("isLocked");
                            Boolean rApproval = doc.getBoolean("requireApproval");
                            Long rExpires = doc.getLong("keyExpiresAt");
                            Long rFailCount = doc.getLong("failedJoinCount");
                            Long rLockoutUntil = doc.getLong("lockoutUntil");
                            Long cAt = doc.getLong("createdAt");
                            Long uAt = doc.getLong("updatedAt");
                            long now = System.currentTimeMillis();
                            matchedRoom = new RoomEntity(
                                    rId,
                                    rName != null ? rName : "Shared Room",
                                    rKey != null ? rKey : cleanKey,
                                    rPassHash != null ? rPassHash : "",
                                    rSecret != null ? rSecret : SecuritySignatureHelper.getOrCreateSecretKey(appContext),
                                    rOwnerId != null ? rOwnerId : "",
                                    rOwnerName != null ? rOwnerName : "Owner",
                                    cAt != null ? cAt : now,
                                    uAt != null ? uAt : now,
                                    rSalt,
                                    rLocked != null && rLocked,
                                    rApproval == null || rApproval,
                                    rExpires != null ? rExpires : 0L,
                                    rFailCount != null ? rFailCount.intValue() : 0,
                                    rLockoutUntil != null ? rLockoutUntil : 0L
                            );
                        }
                    } catch (Exception ignored) {
                    }
                }

                sessionManager.ensureLocalFallbackUserIfNeeded();
                String userId = sessionManager.getCurrentUserId();
                String userName = sessionManager.getCurrentUserName();
                String userEmail = sessionManager.getCurrentUserEmail();
                String deviceInfo = RoomSecurityHelper.getDeviceInfo();
                long now = System.currentTimeMillis();

                if (matchedRoom == null) {
                    int fails = RoomSecurityHelper.recordFailedJoinAttempt(appContext, cleanKey);
                    if (fails >= RoomSecurityHelper.MAX_FAILED_JOIN_ATTEMPTS) {
                        insertAttributedAuditLogSync(
                                AuditLogEntity.ACTION_SECURITY_BRUTE_FORCE_ALERT,
                                null,
                                cleanKey,
                                null,
                                "Brute-force lockout triggered on Room Key " + cleanKey + " after " + fails
                                        + " failed attempts by " + userName + " (" + userEmail + ") on " + deviceInfo,
                                now
                        );
                        FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                        long rem = RoomSecurityHelper.getRemainingLockoutMillis(appContext, cleanKey, 0L);
                        postError(callback, RoomSecurityHelper.formatLockoutCountdown(rem));
                        return;
                    }
                    postError(callback, "Invalid Room Key or Password (" + (RoomSecurityHelper.MAX_FAILED_JOIN_ATTEMPTS - fails) + " attempts remaining).");
                    return;
                }

                // Re-check lockout with remote lockoutUntil
                remainingLockout = RoomSecurityHelper.getRemainingLockoutMillis(appContext, cleanKey, matchedRoom.lockoutUntil);
                if (remainingLockout > 0L) {
                    postError(callback, RoomSecurityHelper.formatLockoutCountdown(remainingLockout));
                    return;
                }

                boolean isOwnerAccount = userId.equals(matchedRoom.ownerUserId);

                // Check if user is BANNED
                RoomMemberEntity existingMember = roomMemberDao.findMemberSync(matchedRoom.roomId, userId);
                if (existingMember != null && existingMember.isBanned() && !isOwnerAccount) {
                    insertAttributedAuditLogSync(
                            AuditLogEntity.ACTION_SECURITY_JOIN_DENIED,
                            null,
                            matchedRoom.roomName,
                            null,
                            "Blocked join attempt by BANNED user " + userName + " (" + userEmail + ") on " + deviceInfo,
                            now
                    );
                    FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                    postError(callback, "Access Denied: Your account has been banned from this room by the Owner.");
                    return;
                }

                // Check if Room is Locked
                if (matchedRoom.isLocked && !isOwnerAccount && (existingMember == null || !existingMember.isActive())) {
                    insertAttributedAuditLogSync(
                            AuditLogEntity.ACTION_SECURITY_JOIN_DENIED,
                            null,
                            matchedRoom.roomName,
                            null,
                            "Blocked join attempt by " + userName + " (" + userEmail + "): Room is LOCKED by Owner.",
                            now
                    );
                    FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                    postError(callback, "This room is currently LOCKED by the Room Owner. New joins are blocked.");
                    return;
                }

                // Check if Room Invite Key has Expired
                if (matchedRoom.isKeyExpired(now) && !isOwnerAccount && (existingMember == null || !existingMember.isActive())) {
                    insertAttributedAuditLogSync(
                            AuditLogEntity.ACTION_SECURITY_JOIN_DENIED,
                            null,
                            matchedRoom.roomName,
                            null,
                            "Blocked join attempt by " + userName + " (" + userEmail + "): Room Invite Key expired.",
                            now
                    );
                    FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                    postError(callback, "This Room Invite Key has expired. Ask the Room Owner to renew or rotate the Room Key.");
                    return;
                }

                // Constant-Time Salted PBKDF2 (or legacy SHA-256) Password Verification
                boolean passwordValid = RoomSecurityHelper.verifyRoomPassword(
                        cleanPass,
                        matchedRoom.roomPasswordHash,
                        matchedRoom.roomSalt
                );
                if (!passwordValid) {
                    int fails = RoomSecurityHelper.recordFailedJoinAttempt(appContext, cleanKey);
                    if (fails >= RoomSecurityHelper.MAX_FAILED_JOIN_ATTEMPTS) {
                        long lockoutUntil = now + RoomSecurityHelper.LOCKOUT_DURATION_MS;
                        matchedRoom.failedJoinCount = fails;
                        matchedRoom.lockoutUntil = lockoutUntil;
                        roomDao.insertOrReplaceRoom(matchedRoom);
                        insertAttributedAuditLogSync(
                                AuditLogEntity.ACTION_SECURITY_BRUTE_FORCE_ALERT,
                                null,
                                matchedRoom.roomName,
                                null,
                                "SECURITY ALERT: 5 consecutive failed password attempts on room \"" + matchedRoom.roomName
                                        + "\" (" + cleanKey + ") by " + userName + " (" + userEmail + ") on device "
                                        + deviceInfo + ". 15-minute lockout activated.",
                                now
                        );
                        FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                        postError(callback, RoomSecurityHelper.formatLockoutCountdown(RoomSecurityHelper.LOCKOUT_DURATION_MS));
                        return;
                    }
                    postError(callback, "Invalid Room Key or Password (" + (RoomSecurityHelper.MAX_FAILED_JOIN_ATTEMPTS - fails) + " attempts remaining).");
                    return;
                }

                RoomSecurityHelper.clearFailedJoinAttempts(appContext, cleanKey);
                matchedRoom.failedJoinCount = 0;
                matchedRoom.lockoutUntil = 0L;
                roomDao.insertOrReplaceRoom(matchedRoom);

                String assignedRole;
                String memberStatus;
                if (isOwnerAccount) {
                    assignedRole = RoomMemberEntity.ROLE_OWNER;
                    memberStatus = RoomMemberEntity.STATUS_ACTIVE;
                } else if (existingMember != null && existingMember.isActive()) {
                    assignedRole = existingMember.role;
                    memberStatus = RoomMemberEntity.STATUS_ACTIVE;
                } else {
                    assignedRole = existingMember != null ? existingMember.role : RoomMemberEntity.ROLE_COLLECTOR;
                    memberStatus = matchedRoom.requireApproval
                            ? RoomMemberEntity.STATUS_PENDING_APPROVAL
                            : RoomMemberEntity.STATUS_ACTIVE;
                }

                RoomMemberEntity member = new RoomMemberEntity(
                        matchedRoom.roomId,
                        userId,
                        userName,
                        userEmail,
                        assignedRole,
                        existingMember != null ? existingMember.joinedAt : now,
                        memberStatus,
                        deviceInfo
                );
                if (existingMember != null) {
                    member.id = existingMember.id;
                }
                roomMemberDao.insertOrReplaceMember(member);
                sessionManager.setActiveRoom(matchedRoom, assignedRole, memberStatus);

                String joinLogMsg = RoomMemberEntity.STATUS_PENDING_APPROVAL.equals(memberStatus)
                        ? userName + " (" + userEmail + ", " + deviceInfo + ") requested to join room \""
                        + matchedRoom.roomName + "\" — Waiting for Owner Approval."
                        : userName + " (" + deviceInfo + ") joined Private Room \"" + matchedRoom.roomName + "\" as " + assignedRole;

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_MEMBER_JOINED,
                        null,
                        matchedRoom.roomName,
                        null,
                        joinLogMsg,
                        now
                );

                FirestoreRealtimeSyncManager.getInstance(appContext).startListeningForRoom(matchedRoom.roomId);
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, matchedRoom);
            } catch (Exception e) {
                postError(callback, "Could not join room: " + e.getMessage());
            }
        });
    }

    public void switchActiveRoom(String roomId, ResultCallback<RoomEntity> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                RoomEntity room = roomDao.findByIdSync(roomId);
                if (room == null) {
                    postError(callback, "Room not found locally.");
                    return;
                }
                String userId = sessionManager.getCurrentUserId();
                RoomMemberEntity member = roomMemberDao.findMemberSync(roomId, userId);
                if (member != null && member.isBanned() && !userId.equals(room.ownerUserId)) {
                    postError(callback, "Your access to this room has been banned by the Room Owner.");
                    return;
                }
                String role = member != null
                        ? member.role
                        : (userId.equals(room.ownerUserId) ? RoomMemberEntity.ROLE_OWNER : RoomMemberEntity.ROLE_COLLECTOR);
                String status = (member != null && !userId.equals(room.ownerUserId))
                        ? member.status
                        : RoomMemberEntity.STATUS_ACTIVE;
                sessionManager.setActiveRoom(room, role, status);
                FirestoreRealtimeSyncManager.getInstance(appContext).startListeningForRoom(roomId);
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, room);
            } catch (Exception e) {
                postError(callback, "Failed to switch room: " + e.getMessage());
            }
        });
    }

    public void approvePendingMember(
            String roomId,
            String targetUserId,
            String targetUserName,
            String assignedRole,
            ResultCallback<Void> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.isOwner()) {
                    postError(callback, "Only the Room OWNER can approve pending members.");
                    return;
                }
                String role = (assignedRole != null && !assignedRole.trim().isEmpty())
                        ? assignedRole.trim().toUpperCase(Locale.US)
                        : RoomMemberEntity.ROLE_COLLECTOR;
                long now = System.currentTimeMillis();
                roomMemberDao.updateMemberStatusAndRole(roomId, targetUserId, RoomMemberEntity.STATUS_ACTIVE, role);

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_SECURITY_MEMBER_APPROVED,
                        null,
                        sessionManager.getActiveRoomName(),
                        null,
                        "Approved pending member " + targetUserName + " as " + role,
                        now
                );

                if (FirebaseSyncManager.isFirebaseConfigured(appContext) && FirebaseSyncManager.isNetworkConnected(appContext)) {
                    Map<String, Object> update = new HashMap<>();
                    update.put("status", RoomMemberEntity.STATUS_ACTIVE);
                    update.put("role", role);
                    FirebaseFirestore.getInstance()
                            .collection("rooms")
                            .document(roomId)
                            .collection("members")
                            .document(targetUserId)
                            .set(update, SetOptions.merge());
                }
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, null);
            } catch (Exception e) {
                postError(callback, "Failed to approve member: " + e.getMessage());
            }
        });
    }

    public void rejectOrBanMember(
            String roomId,
            String targetUserId,
            String targetUserName,
            boolean banPermanently,
            ResultCallback<Void> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.isOwner()) {
                    postError(callback, "Only the Room OWNER can reject or ban members.");
                    return;
                }
                if (targetUserId.equals(sessionManager.getCurrentUserId())) {
                    postError(callback, "You cannot ban or remove yourself as Room Owner.");
                    return;
                }
                long now = System.currentTimeMillis();
                if (banPermanently) {
                    roomMemberDao.updateMemberStatus(roomId, targetUserId, RoomMemberEntity.STATUS_BANNED);
                    if (FirebaseSyncManager.isFirebaseConfigured(appContext) && FirebaseSyncManager.isNetworkConnected(appContext)) {
                        Map<String, Object> update = new HashMap<>();
                        update.put("status", RoomMemberEntity.STATUS_BANNED);
                        FirebaseFirestore.getInstance()
                                .collection("rooms")
                                .document(roomId)
                                .collection("members")
                                .document(targetUserId)
                                .set(update, SetOptions.merge());
                    }
                    insertAttributedAuditLogSync(
                            AuditLogEntity.ACTION_SECURITY_MEMBER_BANNED,
                            null,
                            sessionManager.getActiveRoomName(),
                            null,
                            "Permanently BANNED member " + targetUserName + " from room \"" + sessionManager.getActiveRoomName() + "\"",
                            now
                    );
                } else {
                    roomMemberDao.deleteMember(roomId, targetUserId);
                    FirebaseSyncManager.queueRemoteDeletion(appContext, roomId, "members", targetUserId);
                    insertAttributedAuditLogSync(
                            AuditLogEntity.ACTION_MEMBER_REMOVED,
                            null,
                            sessionManager.getActiveRoomName(),
                            null,
                            "Rejected / removed member " + targetUserName + " from room \"" + sessionManager.getActiveRoomName() + "\"",
                            now
                    );
                }
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, null);
            } catch (Exception e) {
                postError(callback, "Failed to update member access: " + e.getMessage());
            }
        });
    }

    public void updateRoomSecuritySettings(
            String roomId,
            boolean isLocked,
            boolean requireApproval,
            long keyExpiresAt,
            ResultCallback<RoomEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.isOwner()) {
                    postError(callback, "Only the Room OWNER can modify room security settings.");
                    return;
                }
                RoomEntity existing = roomDao.findByIdSync(roomId);
                if (existing == null) {
                    postError(callback, "Room not found.");
                    return;
                }
                boolean lockChanged = existing.isLocked != isLocked;
                long now = System.currentTimeMillis();
                roomDao.updateRoomSecuritySettings(roomId, isLocked, requireApproval, keyExpiresAt, now);
                RoomEntity updated = roomDao.findByIdSync(roomId);

                String actionType = lockChanged
                        ? (isLocked ? AuditLogEntity.ACTION_SECURITY_ROOM_LOCKED : AuditLogEntity.ACTION_SECURITY_ROOM_UNLOCKED)
                        : AuditLogEntity.ACTION_MEMBER_ROLE_CHANGED;
                String details = "Updated Room Security for \"" + existing.roomName + "\": "
                        + "Room Lock=" + (isLocked ? "LOCKED" : "OPEN")
                        + ", Owner Approval Gate=" + (requireApproval ? "ENABLED" : "DISABLED")
                        + ", Invite Key Expiration=" + (keyExpiresAt > 0 ? "ACTIVE" : "NONE");

                insertAttributedAuditLogSync(
                        actionType,
                        null,
                        existing.roomName,
                        null,
                        details,
                        now
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, updated);
            } catch (Exception e) {
                postError(callback, "Failed to update security settings: " + e.getMessage());
            }
        });
    }

    public void rotateRoomKeyAndPassword(
            String roomId,
            String newRawPassword,
            long keyExpiresAt,
            ResultCallback<RoomEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.isOwner()) {
                    postError(callback, "Only the Room OWNER can rotate the Room Key and Password.");
                    return;
                }
                String cleanPass = newRawPassword != null ? newRawPassword.trim() : "";
                String passwordError = RoomSecurityHelper.validatePasswordStrength(cleanPass);
                if (passwordError != null) {
                    postError(callback, passwordError);
                    return;
                }
                RoomEntity existing = roomDao.findByIdSync(roomId);
                if (existing == null) {
                    postError(callback, "Room not found.");
                    return;
                }
                long now = System.currentTimeMillis();
                String newRoomKey = generateUniqueLocalRoomKey();
                String newSalt = RoomSecurityHelper.generateSaltBase64();
                String newHash = RoomSecurityHelper.hashPasswordPbkdf2(cleanPass, newSalt);

                roomDao.rotateRoomCredentials(roomId, newRoomKey, newHash, newSalt, keyExpiresAt, now);
                RoomEntity updated = roomDao.findByIdSync(roomId);
                if (updated != null && roomId.equals(sessionManager.getActiveRoomId())) {
                    sessionManager.setActiveRoom(updated, RoomMemberEntity.ROLE_OWNER, RoomMemberEntity.STATUS_ACTIVE);
                }

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_SECURITY_KEY_ROTATED,
                        null,
                        existing.roomName,
                        null,
                        "Rotated Room Key (New Key: " + newRoomKey + ") and PBKDF2 Password Hash for \"" + existing.roomName + "\"",
                        now
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, updated);
            } catch (Exception e) {
                postError(callback, "Failed to rotate Room Key & Password: " + e.getMessage());
            }
        });
    }

    public void rotateRoomSigningSecret(
            String roomId,
            ResultCallback<RoomEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.isOwner()) {
                    postError(callback, "Only the Room OWNER can rotate the QR Signing Secret.");
                    return;
                }
                RoomEntity existing = roomDao.findByIdSync(roomId);
                if (existing == null) {
                    postError(callback, "Room not found.");
                    return;
                }
                long now = System.currentTimeMillis();
                String newSecret = SecuritySignatureHelper.generateRoomSigningSecretHex();
                roomDao.updateRoomSigningSecret(roomId, newSecret, now);
                RoomEntity updated = roomDao.findByIdSync(roomId);
                if (updated != null && roomId.equals(sessionManager.getActiveRoomId())) {
                    sessionManager.updateActiveRoomSigningSecret(newSecret);
                }

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_SECURITY_SECRET_ROTATED,
                        null,
                        existing.roomName,
                        null,
                        "Rotated 256-bit HMAC-SHA256 QR Signing Secret for room \"" + existing.roomName + "\"",
                        now
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, updated);
            } catch (Exception e) {
                postError(callback, "Failed to rotate QR signing secret: " + e.getMessage());
            }
        });
    }

    public void updateMemberRole(
            String roomId,
            String targetUserId,
            String targetUserName,
            String newRole,
            ResultCallback<Void> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.isOwner()) {
                    postError(callback, "Only the Room OWNER can change member roles.");
                    return;
                }
                long now = System.currentTimeMillis();
                roomMemberDao.updateMemberRole(roomId, targetUserId, newRole);
                if (targetUserId.equals(sessionManager.getCurrentUserId()) && roomId.equals(sessionManager.getActiveRoomId())) {
                    sessionManager.updateActiveRoomRole(newRole);
                }

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_MEMBER_ROLE_CHANGED,
                        null,
                        sessionManager.getActiveRoomName(),
                        null,
                        "Changed role of " + targetUserName + " to " + newRole,
                        now
                );

                if (FirebaseSyncManager.isFirebaseConfigured(appContext) && FirebaseSyncManager.isNetworkConnected(appContext)) {
                    Map<String, Object> update = new HashMap<>();
                    update.put("role", newRole);
                    FirebaseFirestore.getInstance()
                            .collection("rooms")
                            .document(roomId)
                            .collection("members")
                            .document(targetUserId)
                            .set(update, SetOptions.merge());
                }
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, null);
            } catch (Exception e) {
                postError(callback, "Failed to update role: " + e.getMessage());
            }
        });
    }

    public void removeMemberFromRoom(
            String roomId,
            String targetUserId,
            String targetUserName,
            ResultCallback<Void> callback
    ) {
        rejectOrBanMember(roomId, targetUserId, targetUserName, false, callback);
    }

    public void changeRoomPassword(
            String roomId,
            String newRawPassword,
            ResultCallback<Void> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.isOwner()) {
                    postError(callback, "Only the Room OWNER can change the Room Password.");
                    return;
                }
                String cleanPass = newRawPassword != null ? newRawPassword.trim() : "";
                String passwordError = RoomSecurityHelper.validatePasswordStrength(cleanPass);
                if (passwordError != null) {
                    postError(callback, passwordError);
                    return;
                }
                long now = System.currentTimeMillis();
                String newSalt = RoomSecurityHelper.generateSaltBase64();
                String newHash = RoomSecurityHelper.hashPasswordPbkdf2(cleanPass, newSalt);
                roomDao.updateRoomPasswordHashAndSalt(roomId, newHash, newSalt, now);

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_SECURITY_KEY_ROTATED,
                        null,
                        sessionManager.getActiveRoomName(),
                        null,
                        "Updated Room Password (PBKDF2-SHA256) for \"" + sessionManager.getActiveRoomName() + "\"",
                        now
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, null);
            } catch (Exception e) {
                postError(callback, "Failed to update password: " + e.getMessage());
            }
        });
    }

    private String generateUniqueLocalRoomKey() {
        for (int i = 0; i < 10; i++) {
            String candidate = SecuritySignatureHelper.generateRoomKey();
            if (roomDao.findByKeySync(candidate) == null) {
                return candidate;
            }
        }
        return SecuritySignatureHelper.generateRoomKey();
    }

    public LiveData<List<SectionSummary>> observeRootSectionSummaries() {
        String activeRoomId = sessionManager.getActiveRoomId();
        if (activeRoomId != null && !activeRoomId.isEmpty()) {
            return sectionDao.observeRootSectionSummariesByRoom(activeRoomId);
        }
        return sectionDao.observeRootSectionSummaries();
    }

    public LiveData<List<SectionSummary>> observeSubfolderSummaries(long parentSectionId) {
        return sectionDao.observeSubfolderSummaries(parentSectionId);
    }

    public LiveData<SectionEntity> observeSectionById(long sectionId) {
        return sectionDao.observeById(sectionId);
    }

    public LiveData<List<PaymentEntity>> observePaymentsBySection(long sectionId) {
        return paymentDao.observePaymentsBySection(sectionId);
    }

    public LiveData<List<ExpenseEntity>> observeExpensesBySection(long sectionId) {
        return expenseDao.observeExpensesBySection(sectionId);
    }

    public LiveData<PaymentEntity> observePaymentByReceiptId(String receiptId) {
        return paymentDao.observeByReceiptId(receiptId);
    }

    public LiveData<List<AuditLogEntity>> observeAllAuditLogs() {
        String activeRoomId = sessionManager.getActiveRoomId();
        if (activeRoomId != null && !activeRoomId.isEmpty()) {
            return auditLogDao.observeLogsByRoom(activeRoomId);
        }
        return auditLogDao.observeAllLogs();
    }

    public LiveData<List<AuditLogEntity>> observeAuditLogsBySection(long sectionId) {
        return auditLogDao.observeLogsBySection(sectionId);
    }

    public void createSection(String sectionName, String description, ResultCallback<SectionEntity> callback) {
        createSection(sectionName, description, null, callback);
    }

    public void createSection(String sectionName, String description, Long parentFolderId, ResultCallback<SectionEntity> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canCreateOrEditRecords()) {
                    postError(callback, "Auditors have read-only access and cannot create folders.");
                    return;
                }
                String cleanName = sectionName != null ? sectionName.trim() : "";
                if (cleanName.isEmpty()) {
                    postError(callback, "Folder / section name is required.");
                    return;
                }
                String activeRoomId = sessionManager.getActiveRoomId();
                SectionEntity existing = sectionDao.findSiblingByNameSync(activeRoomId, parentFolderId, cleanName);
                if (existing != null) {
                    postError(callback, "A folder named \"" + cleanName + "\" already exists at this level.");
                    return;
                }
                long now = System.currentTimeMillis();
                SectionEntity entity = new SectionEntity(
                        cleanName,
                        description != null ? description.trim() : "",
                        now,
                        parentFolderId,
                        0.0,
                        SectionEntity.SYNC_PENDING,
                        now,
                        activeRoomId
                );
                long id = sectionDao.insertSection(entity);
                entity.id = id;

                String parentInfo = "";
                if (parentFolderId != null) {
                    SectionEntity parent = sectionDao.findByIdSync(parentFolderId);
                    if (parent != null) {
                        parentInfo = " inside \"" + parent.sectionName + "\"";
                    }
                }

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_FOLDER_CREATED,
                        id,
                        cleanName,
                        null,
                        "Created folder \"" + cleanName + "\"" + parentInfo
                                + (entity.description.isEmpty() ? "" : " (" + entity.description + ")"),
                        now
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, entity);
            } catch (Exception e) {
                postError(callback, "Failed to create folder: " + e.getMessage());
            }
        });
    }

    /**
     * Renames an existing main section folder or nested sub-folder in-place without losing any child sub-folders,
     * payment receipts, or expenses. Validates non-empty and no duplicate sibling name, updates denormalized
     * section_name references, logs FOLDER_RENAMED with actor attribution, and queues cloud sync.
     */
    public void renameSection(long sectionId, String newSectionName, ResultCallback<SectionEntity> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canCreateOrEditRecords()) {
                    postError(callback, "Auditors have read-only access and cannot rename folders.");
                    return;
                }
                String cleanNewName = newSectionName != null ? newSectionName.trim() : "";
                if (cleanNewName.isEmpty()) {
                    postError(callback, "Folder name cannot be empty.");
                    return;
                }
                SectionEntity target = sectionDao.findByIdSync(sectionId);
                if (target == null) {
                    postError(callback, "Folder not found.");
                    return;
                }
                String oldName = target.sectionName;
                if (oldName.equals(cleanNewName)) {
                    postSuccess(callback, target);
                    return;
                }

                String roomId = target.roomId != null ? target.roomId : sessionManager.getActiveRoomId();
                SectionEntity duplicateSibling = sectionDao.findSiblingByNameSync(roomId, target.parentFolderId, cleanNewName);
                if (duplicateSibling != null && duplicateSibling.id != sectionId) {
                    postError(callback, "Another folder named \"" + cleanNewName + "\" already exists at this level.");
                    return;
                }

                long now = System.currentTimeMillis();
                database.runInTransaction(() -> {
                    sectionDao.renameSection(sectionId, cleanNewName, now);
                    paymentDao.updateSectionNameForSection(sectionId, cleanNewName, now);
                    expenseDao.updateSectionNameForSection(sectionId, cleanNewName, now);
                    auditLogDao.updateSectionNameForSection(sectionId, cleanNewName);

                    insertAttributedAuditLogSync(
                            AuditLogEntity.ACTION_FOLDER_RENAMED,
                            sectionId,
                            cleanNewName,
                            null,
                            "Renamed folder from \"" + oldName + "\" to \"" + cleanNewName + "\"",
                            now
                    );
                });

                SectionEntity updated = sectionDao.findByIdSync(sectionId);
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, updated);
            } catch (Exception e) {
                postError(callback, "Failed to rename folder: " + e.getMessage());
            }
        });
    }

    /**
     * Loads the full ancestor chain from root folder down to the current folder for breadcrumb navigation.
     */
    public void loadBreadcrumbChain(long sectionId, ResultCallback<List<SectionEntity>> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                List<SectionEntity> chain = new ArrayList<>();
                Long cursorId = sectionId;
                int guard = 0;
                while (cursorId != null && guard < 25) {
                    SectionEntity current = sectionDao.findByIdSync(cursorId);
                    if (current == null) {
                        break;
                    }
                    chain.add(current);
                    cursorId = current.parentFolderId;
                    guard++;
                }
                Collections.reverse(chain);
                postSuccess(callback, chain);
            } catch (Exception e) {
                postError(callback, "Failed to load breadcrumbs: " + e.getMessage());
            }
        });
    }

    /**
     * Computes combined Gross Collected, Expenses Deducted, and Net Balance across a folder and all its nested sub-folders.
     */
    public void loadCombinedTreeBalance(long rootSectionId, ResultCallback<CombinedTreeBalance> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                List<Long> treeIds = new ArrayList<>();
                collectDescendantSectionIds(rootSectionId, treeIds);
                int descendantCount = Math.max(0, treeIds.size() - 1);
                double gross = paymentDao.sumActivePaymentsForSectionIdsSync(treeIds);
                double expenses = expenseDao.sumActiveExpensesForSectionIdsSync(treeIds);
                double net = gross - expenses;
                postSuccess(callback, new CombinedTreeBalance(descendantCount, gross, expenses, net));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * Previews how many nested sub-folders, payment receipts, and expenses will be removed if a folder is deleted.
     */
    public void previewFolderDeletionImpact(long sectionId, ResultCallback<FolderDeleteSummary> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                SectionEntity target = sectionDao.findByIdSync(sectionId);
                if (target == null) {
                    postError(callback, "Folder not found.");
                    return;
                }
                List<Long> allSectionIds = new ArrayList<>();
                collectDescendantSectionIds(sectionId, allSectionIds);

                int subfolderCount = Math.max(0, allSectionIds.size() - 1);
                List<PaymentEntity> payments = paymentDao.getPaymentsBySectionIdsSync(allSectionIds);
                List<ExpenseEntity> expenses = expenseDao.getExpensesBySectionIdsSync(allSectionIds);

                postSuccess(callback, new FolderDeleteSummary(
                        target.sectionName,
                        subfolderCount,
                        payments.size(),
                        expenses.size()
                ));
            } catch (Exception e) {
                postError(callback, "Failed to inspect folder contents: " + e.getMessage());
            }
        });
    }

    /**
     * Cascading folder deletion: deletes child sub-folders, payments, expenses, and orphaned local proof photos,
     * queues cloud deletions for Firebase sync, and writes an immutable FOLDER_DELETED audit log entry.
     */
    public void deleteFolderCascade(long sectionId, ResultCallback<FolderDeleteSummary> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canVoidOrDeleteOrManageMembers()) {
                    postError(callback, "Only the Room OWNER can permanently delete folders.");
                    return;
                }
                SectionEntity target = sectionDao.findByIdSync(sectionId);
                if (target == null) {
                    postError(callback, "Folder no longer exists.");
                    return;
                }
                List<Long> allSectionIds = new ArrayList<>();
                collectDescendantSectionIds(sectionId, allSectionIds);

                int subfolderCount = Math.max(0, allSectionIds.size() - 1);
                List<PaymentEntity> payments = paymentDao.getPaymentsBySectionIdsSync(allSectionIds);
                List<ExpenseEntity> expenses = expenseDao.getExpensesBySectionIdsSync(allSectionIds);

                for (PaymentEntity p : payments) {
                    if (p.proofImagePath != null) {
                        File f = new File(p.proofImagePath);
                        if (f.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                    FirebaseSyncManager.queueRemoteDeletion(appContext, target.roomId, "payments", p.receiptId);
                }
                for (ExpenseEntity ex : expenses) {
                    if (ex.proofImagePath != null) {
                        File f = new File(ex.proofImagePath);
                        if (f.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                    FirebaseSyncManager.queueRemoteDeletion(appContext, target.roomId, "expenses", "EXP-" + ex.id);
                }
                for (Long secId : allSectionIds) {
                    FirebaseSyncManager.queueRemoteDeletion(appContext, target.roomId, "sections", "SEC-" + secId);
                }

                database.runInTransaction(() -> {
                    paymentDao.deletePaymentsBySectionIds(allSectionIds);
                    expenseDao.deleteExpensesBySectionIds(allSectionIds);
                    rosterStudentDao.deleteRosterBySectionIds(allSectionIds);
                    sectionDao.deleteSectionsByIds(allSectionIds);

                    long now = System.currentTimeMillis();
                    String details = "Permanently deleted folder \"" + target.sectionName + "\" ("
                            + subfolderCount + " sub-folder(s), "
                            + payments.size() + " payment receipt(s), and "
                            + expenses.size() + " expense record(s) removed).";

                    insertAttributedAuditLogSync(
                            AuditLogEntity.ACTION_FOLDER_DELETED,
                            null,
                            target.sectionName,
                            null,
                            details,
                            now
                    );
                });

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, new FolderDeleteSummary(
                        target.sectionName,
                        subfolderCount,
                        payments.size(),
                        expenses.size()
                ));
            } catch (Exception e) {
                postError(callback, "Failed to delete folder: " + e.getMessage());
            }
        });
    }

    private void collectDescendantSectionIds(long currentSectionId, List<Long> accumulator) {
        if (accumulator.contains(currentSectionId)) {
            return;
        }
        accumulator.add(currentSectionId);
        List<SectionEntity> children = sectionDao.getSubfoldersSync(currentSectionId);
        if (children != null) {
            for (SectionEntity child : children) {
                collectDescendantSectionIds(child.id, accumulator);
            }
        }
    }

    public void checkDuplicatePaymentBeforeSave(
            long sectionId,
            String studentNumber,
            String firstName,
            String lastName,
            String purpose,
            ResultCallback<DuplicatePaymentGuard.DuplicateCheckResult> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                DuplicatePaymentGuard.DuplicateCheckResult result =
                        DuplicatePaymentGuard.checkDuplicateBeforeSubmission(
                                appContext,
                                sectionId,
                                studentNumber,
                                firstName,
                                lastName,
                                purpose
                        );
                postSuccess(callback, result);
            } catch (Exception e) {
                postSuccess(callback, DuplicatePaymentGuard.DuplicateCheckResult.none());
            }
        });
    }

    public void logDuplicatePaymentBlocked(long sectionId, String sectionName, PaymentEntity existingPayment) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (existingPayment == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                String details = "Blocked duplicate payment attempt for " + existingPayment.getFullName()
                        + (existingPayment.hasStudentNumber() ? " [" + existingPayment.studentNumber + "]" : "")
                        + " — Already recorded on Receipt " + existingPayment.receiptId
                        + " (" + CurrencyFormatter.formatPhp(existingPayment.amount) + ") by "
                        + existingPayment.getFormattedActorAttribution() + ".";
                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_DUPLICATE_PAYMENT_BLOCKED,
                        sectionId,
                        sectionName,
                        existingPayment.receiptId,
                        details,
                        now
                );
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
            } catch (Exception ignored) {
            }
        });
    }

    public void applyAdditionalPaymentToPartialReceipt(
            PaymentEntity existingPartial,
            double additionalAmount,
            ResultCallback<PaymentEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canCreateOrEditRecords()) {
                    postError(callback, "Auditors have read-only access and cannot record payments.");
                    return;
                }
                if (existingPartial == null) {
                    postError(callback, "Existing partial receipt not found.");
                    return;
                }
                long now = System.currentTimeMillis();
                double newTotalAmount = existingPartial.amount + additionalAmount;
                double newRemainingBalance = Math.max(0.0, existingPartial.remainingBalance - additionalAmount);
                String newPaymentStatus = newRemainingBalance <= 0.005
                        ? PaymentEntity.STATUS_PAID
                        : PaymentEntity.STATUS_PARTIAL;

                String newSignature = SecuritySignatureHelper.computeReceiptSignature(
                        appContext,
                        existingPartial.receiptId,
                        existingPartial.sectionId,
                        existingPartial.firstName,
                        existingPartial.lastName,
                        newTotalAmount,
                        existingPartial.createdAt
                );
                String newQrPayload = SecuritySignatureHelper.buildQrPayload(existingPartial.receiptId, newSignature);

                existingPartial.amount = newTotalAmount;
                existingPartial.remainingBalance = newRemainingBalance;
                existingPartial.paymentStatus = newPaymentStatus;
                existingPartial.qrSignature = newSignature;
                existingPartial.qrPayload = newQrPayload;
                existingPartial.syncStatus = PaymentEntity.SYNC_PENDING;
                existingPartial.updatedAt = now;
                paymentDao.insertOrReplacePayment(existingPartial);

                String details = "Applied additional installment of " + CurrencyFormatter.formatPhp(additionalAmount)
                        + " to Partial Receipt " + existingPartial.receiptId + " for " + existingPartial.getFullName()
                        + " (New Total Paid: " + CurrencyFormatter.formatPhp(newTotalAmount)
                        + ", Remaining Balance: " + CurrencyFormatter.formatPhp(newRemainingBalance)
                        + ", Status: " + newPaymentStatus + ").";

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_PAYMENT_RECORDED,
                        existingPartial.sectionId,
                        existingPartial.sectionName,
                        existingPartial.receiptId,
                        details,
                        now
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, existingPartial);
            } catch (Exception e) {
                postError(callback, "Failed to update partial payment balance: " + e.getMessage());
            }
        });
    }

    public void recordPayment(
            long sectionId,
            String firstName,
            String lastName,
            String studentNumber,
            String purpose,
            double amount,
            String paymentMethod,
            String gcashRef,
            String proofImagePath,
            ResultCallback<PaymentEntity> callback
    ) {
        recordPaymentWithDuplicateSupport(
                sectionId,
                firstName,
                lastName,
                studentNumber,
                purpose,
                amount,
                paymentMethod,
                gcashRef,
                proofImagePath,
                null,
                callback
        );
    }

    public void recordPaymentWithDuplicateSupport(
            long sectionId,
            String firstName,
            String lastName,
            String studentNumber,
            String purpose,
            double amount,
            String paymentMethod,
            String gcashRef,
            String proofImagePath,
            String duplicateOverrideReason,
            ResultCallback<PaymentEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canCreateOrEditRecords()) {
                    postError(callback, "Auditors have read-only access and cannot record payments.");
                    return;
                }
                SectionEntity section = sectionDao.findByIdSync(sectionId);
                if (section == null) {
                    postError(callback, "Section folder not found in local database.");
                    return;
                }

                long createdAt = System.currentTimeMillis();
                String receiptId = SecuritySignatureHelper.generateUniqueReceiptId(createdAt);
                String signature = SecuritySignatureHelper.computeReceiptSignature(
                        appContext,
                        receiptId,
                        sectionId,
                        firstName.trim(),
                        lastName.trim(),
                        amount,
                        createdAt
                );
                String qrPayload = SecuritySignatureHelper.buildQrPayload(receiptId, signature);

                String cleanStudentNo = (studentNumber != null && !studentNumber.trim().isEmpty())
                        ? studentNumber.trim()
                        : null;

                String activeRoomId = section.roomId != null ? section.roomId : sessionManager.getActiveRoomId();
                String actorUserId = sessionManager.getCurrentUserId();
                String actorName = sessionManager.getCurrentUserName();
                String actorRole = sessionManager.getActiveRoomRole();

                double remainingBalance = 0.0;
                String paymentStatus = PaymentEntity.STATUS_PAID;
                if (section.targetFeeAmount > 0.005 && amount < section.targetFeeAmount - 0.005) {
                    remainingBalance = section.targetFeeAmount - amount;
                    paymentStatus = PaymentEntity.STATUS_PARTIAL;
                }

                String studentIdentityKey = DuplicatePaymentGuard.buildStudentIdentityKey(
                        sectionId,
                        firstName,
                        lastName,
                        purpose,
                        section.targetFeeAmount
                );
                String cleanOverrideReason = (duplicateOverrideReason != null && !duplicateOverrideReason.trim().isEmpty())
                        ? duplicateOverrideReason.trim()
                        : null;
                boolean isOverride = cleanOverrideReason != null;

                PaymentEntity payment = new PaymentEntity(
                        receiptId,
                        sectionId,
                        section.sectionName,
                        firstName.trim(),
                        lastName.trim(),
                        cleanStudentNo,
                        purpose.trim(),
                        amount,
                        paymentMethod,
                        gcashRef != null && !gcashRef.trim().isEmpty() ? gcashRef.trim() : null,
                        proofImagePath,
                        null,
                        PaymentEntity.STATUS_PAID,
                        paymentStatus,
                        remainingBalance,
                        null,
                        createdAt,
                        null,
                        signature,
                        qrPayload,
                        PaymentEntity.SYNC_PENDING,
                        createdAt,
                        activeRoomId,
                        actorUserId,
                        actorName,
                        actorRole,
                        studentIdentityKey,
                        cleanOverrideReason
                );

                // Simultaneous Tap Protection: Atomic Firestore Transaction Lock when online in a shared room
                if (activeRoomId != null && !activeRoomId.isEmpty()
                        && FirebaseSyncManager.isFirebaseConfigured(appContext)
                        && FirebaseSyncManager.isNetworkConnected(appContext)) {
                    try {
                        DuplicatePaymentGuard.acquireRemotePaymentLockAndCommit(
                                appContext,
                                activeRoomId,
                                payment,
                                section.targetFeeAmount,
                                isOverride
                        );
                    } catch (DuplicatePaymentGuard.DuplicateLockConflictException lockConflict) {
                        PaymentEntity winner = lockConflict.conflictingPayment;
                        logDuplicatePaymentBlocked(sectionId, section.sectionName, winner);
                        postError(callback, "SIMULTANEOUS DUPLICATE BLOCKED: "
                                + (winner != null ? winner.getFormattedActorAttribution() : "Another Cashier")
                                + " just recorded Receipt " + (winner != null ? winner.receiptId : "")
                                + " for " + payment.getFullName() + " seconds ago.");
                        return;
                    }
                }

                payment.id = paymentDao.insertPayment(payment);

                String methodDetail = PaymentEntity.METHOD_GCASH.equals(paymentMethod)
                        ? "GCash (Ref: " + payment.gcashReferenceNo + ")"
                        : "Cash";
                String studentNoSuffix = payment.hasStudentNumber()
                        ? " [Student No: " + payment.studentNumber + "]"
                        : "";
                String auditDetails = "Recorded " + CurrencyFormatter.formatPhp(amount)
                        + " from " + payment.getFullName() + studentNoSuffix
                        + " for \"" + payment.purpose + "\" via " + methodDetail
                        + (isOverride ? " [DUPLICATE OVERRIDE: \"" + cleanOverrideReason + "\"]" : "") + ".";

                insertAttributedAuditLogSync(
                        isOverride ? AuditLogEntity.ACTION_DUPLICATE_PAYMENT_OVERRIDE : AuditLogEntity.ACTION_PAYMENT_RECORDED,
                        sectionId,
                        section.sectionName,
                        receiptId,
                        auditDetails,
                        createdAt
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, payment);
            } catch (Exception e) {
                postError(callback, "Failed to save payment record: " + e.getMessage());
            }
        });
    }

    public void voidPayment(String receiptId, String voidReason, ResultCallback<PaymentEntity> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canVoidOrDeleteOrManageMembers()) {
                    postError(callback, "Only the Room OWNER can void payment receipts.");
                    return;
                }
                String cleanReason = voidReason != null ? voidReason.trim() : "";
                if (cleanReason.isEmpty()) {
                    postError(callback, "A valid reason is required to void a payment.");
                    return;
                }
                PaymentEntity existing = paymentDao.findByReceiptIdSync(receiptId);
                if (existing == null) {
                    postError(callback, "Receipt record not found in local database.");
                    return;
                }
                if (existing.isVoided()) {
                    postError(callback, "This receipt has already been voided.");
                    return;
                }

                long voidedAt = System.currentTimeMillis();
                paymentDao.voidPaymentByReceiptId(receiptId, cleanReason, voidedAt);

                // Release remote student payment lock so the student can legitimately pay again
                SectionEntity section = sectionDao.findByIdSync(existing.sectionId);
                double targetFee = section != null ? section.targetFeeAmount : 0.0;
                String targetRoomId = existing.roomId != null ? existing.roomId : sessionManager.getActiveRoomId();
                DuplicatePaymentGuard.releaseRemotePaymentLock(appContext, targetRoomId, existing, targetFee);

                PaymentEntity updated = paymentDao.findByReceiptIdSync(receiptId);

                String auditDetails = "VOIDED receipt " + receiptId + " ("
                        + existing.getFullName() + " • " + CurrencyFormatter.formatPhp(existing.amount)
                        + "). Reason: \"" + cleanReason + "\"";

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_PAYMENT_VOIDED,
                        existing.sectionId,
                        existing.sectionName,
                        receiptId,
                        auditDetails,
                        voidedAt
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, updated);
            } catch (Exception e) {
                postError(callback, "Failed to void receipt: " + e.getMessage());
            }
        });
    }

    public void recordExpense(
            long sectionId,
            String title,
            double amount,
            String category,
            String notes,
            String proofImagePath,
            ResultCallback<ExpenseEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canCreateOrEditRecords()) {
                    postError(callback, "Auditors have read-only access and cannot record expenses.");
                    return;
                }
                SectionEntity section = sectionDao.findByIdSync(sectionId);
                if (section == null) {
                    postError(callback, "Section folder not found.");
                    return;
                }
                long now = System.currentTimeMillis();
                String activeRoomId = section.roomId != null ? section.roomId : sessionManager.getActiveRoomId();
                String actorUserId = sessionManager.getCurrentUserId();
                String actorName = sessionManager.getCurrentUserName();
                String actorRole = sessionManager.getActiveRoomRole();

                ExpenseEntity expense = new ExpenseEntity(
                        sectionId,
                        section.sectionName,
                        title.trim(),
                        amount,
                        category != null && !category.trim().isEmpty() ? category.trim() : "Other",
                        notes != null && !notes.trim().isEmpty() ? notes.trim() : null,
                        proofImagePath,
                        null,
                        ExpenseEntity.STATUS_ACTIVE,
                        null,
                        now,
                        null,
                        ExpenseEntity.SYNC_PENDING,
                        now,
                        activeRoomId,
                        actorUserId,
                        actorName,
                        actorRole
                );
                expense.id = expenseDao.insertExpense(expense);

                String auditDetails = "Recorded expense deduction of -" + CurrencyFormatter.formatPhp(amount)
                        + " for \"" + expense.title + "\" (Category: " + expense.category + ")"
                        + (expense.notes != null ? " — Notes: " + expense.notes : "");

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_EXPENSE_RECORDED,
                        sectionId,
                        section.sectionName,
                        "EXP-" + expense.id,
                        auditDetails,
                        now
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, expense);
            } catch (Exception e) {
                postError(callback, "Failed to record expense: " + e.getMessage());
            }
        });
    }

    public void voidExpense(long expenseId, String voidReason, ResultCallback<ExpenseEntity> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canVoidOrDeleteOrManageMembers()) {
                    postError(callback, "Only the Room OWNER can void expense deductions.");
                    return;
                }
                String cleanReason = voidReason != null ? voidReason.trim() : "";
                if (cleanReason.isEmpty()) {
                    postError(callback, "A valid reason is required to void an expense.");
                    return;
                }
                ExpenseEntity existing = expenseDao.findByIdSync(expenseId);
                if (existing == null) {
                    postError(callback, "Expense record not found.");
                    return;
                }
                if (existing.isVoided()) {
                    postError(callback, "This expense is already voided.");
                    return;
                }

                long voidedAt = System.currentTimeMillis();
                expenseDao.voidExpenseById(expenseId, cleanReason, voidedAt);
                ExpenseEntity updated = expenseDao.findByIdSync(expenseId);

                String auditDetails = "VOIDED expense \"" + existing.title + "\" ("
                        + CurrencyFormatter.formatPhp(existing.amount)
                        + ") — Amount restored to section balance. Reason: \"" + cleanReason + "\"";

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_EXPENSE_VOIDED,
                        existing.sectionId,
                        existing.sectionName,
                        "EXP-" + expenseId,
                        auditDetails,
                        voidedAt
                );

                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, updated);
            } catch (Exception e) {
                postError(callback, "Failed to void expense: " + e.getMessage());
            }
        });
    }

    public void logSecurityQrFailure(String rawScannedPayload, String failureReason) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                String snippet = rawScannedPayload != null ? rawScannedPayload : "(empty)";
                if (snippet.length() > 140) {
                    snippet = snippet.substring(0, 140) + "...";
                }
                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_QR_VERIFICATION_FAILED,
                        null,
                        null,
                        null,
                        failureReason + " | Scanned Payload: " + snippet,
                        System.currentTimeMillis()
                );
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
            } catch (Exception ignored) {
            }
        });
    }

    public void logExcelExport(long sectionId, String sectionName, int recordCount, String fileName) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_EXCEL_EXPORTED,
                        sectionId,
                        sectionName,
                        null,
                        "Exported " + recordCount + " section payment record(s) to Downloads/PaymentTracker/" + fileName,
                        System.currentTimeMillis()
                );
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
            } catch (Exception ignored) {
            }
        });
    }

    private void insertAttributedAuditLogSync(
            String actionType,
            Long sectionId,
            String sectionName,
            String receiptId,
            String details,
            long timestamp
    ) {
        auditLogDao.insertAuditLog(new AuditLogEntity(
                actionType,
                sectionId,
                sectionName,
                receiptId,
                details,
                timestamp,
                AuditLogEntity.SYNC_PENDING,
                timestamp,
                sessionManager.getActiveRoomId(),
                sessionManager.getCurrentUserId(),
                sessionManager.getCurrentUserName(),
                sessionManager.getActiveRoomRole()
        ));
    }

    private <T> void postSuccess(ResultCallback<T> callback, T result) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(result));
        }
    }

    private <T> void postError(ResultCallback<T> callback, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(message));
        }
    }
}
