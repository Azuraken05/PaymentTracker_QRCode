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
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
                    sessionManager.setActiveRoom(activeRoom, RoomMemberEntity.ROLE_OWNER, RoomMemberEntity.STATUS_ACTIVE);
                } else {
                    RoomMemberEntity existingMember = roomMemberDao.findMemberSync(activeRoom.roomId, userId);
                    String role;
                    String memberStatus;
                    if (existingMember != null) {
                        role = existingMember.role;
                        memberStatus = (existingMember.status != null && !existingMember.status.trim().isEmpty())
                                ? existingMember.status
                                : RoomMemberEntity.STATUS_ACTIVE;
                        if (!userName.equals(existingMember.userName)) {
                            roomMemberDao.updateMemberDisplayName(userId, userName);
                        }
                    } else {
                        role = userId.equals(activeRoom.ownerUserId)
                                ? RoomMemberEntity.ROLE_OWNER
                                : RoomMemberEntity.ROLE_COLLECTOR;
                        memberStatus = RoomMemberEntity.STATUS_ACTIVE;
                        roomMemberDao.insertOrReplaceMember(new RoomMemberEntity(
                                activeRoom.roomId,
                                userId,
                                userName,
                                userEmail,
                                role,
                                now
                        ));
                    }
                    sessionManager.setActiveRoom(activeRoom, role, memberStatus);
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

                sessionManager.syncFromFirebaseAuthIfAvailable();
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

                String deviceInfo = RoomSecurityHelper.getDeviceInfo();
                RoomMemberEntity ownerMember = new RoomMemberEntity(
                        roomId,
                        userId,
                        userName,
                        userEmail,
                        RoomMemberEntity.ROLE_OWNER,
                        now,
                        RoomMemberEntity.STATUS_ACTIVE,
                        deviceInfo
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

                // Push newly created room, private/security, and owner membership immediately when online
                if (FirebaseSyncManager.isFirebaseConfigured(appContext)
                        && FirebaseSyncManager.isNetworkConnected(appContext)) {
                    try {
                        FirebaseFirestore firestore = FirebaseFirestore.getInstance();
                        Map<String, Object> roomDoc = new HashMap<>();
                        roomDoc.put("roomId", room.roomId);
                        roomDoc.put("roomName", room.roomName);
                        roomDoc.put("roomKey", room.roomKey);
                        roomDoc.put("roomPasswordHash", room.roomPasswordHash);
                        roomDoc.put("roomSalt", room.roomSalt != null ? room.roomSalt : "");
                        roomDoc.put("roomSigningSecret", room.roomSigningSecret);
                        roomDoc.put("ownerUserId", room.ownerUserId);
                        roomDoc.put("ownerName", room.ownerName);
                        roomDoc.put("isLocked", room.isLocked);
                        roomDoc.put("requireApproval", room.requireApproval);
                        roomDoc.put("keyExpiresAt", room.keyExpiresAt);
                        roomDoc.put("failedJoinCount", 0);
                        roomDoc.put("lockoutUntil", 0L);
                        roomDoc.put("createdAt", room.createdAt);
                        roomDoc.put("updatedAt", room.updatedAt);
                        firestore.collection("rooms").document(roomId).set(roomDoc, SetOptions.merge());

                        Map<String, Object> securityDoc = new HashMap<>();
                        securityDoc.put("roomPasswordHash", room.roomPasswordHash);
                        securityDoc.put("roomSalt", room.roomSalt != null ? room.roomSalt : "");
                        securityDoc.put("roomSigningSecret", room.roomSigningSecret);
                        securityDoc.put("updatedAt", room.updatedAt);
                        firestore.collection("rooms").document(roomId)
                                .collection("private").document("security")
                                .set(securityDoc, SetOptions.merge());

                        Map<String, Object> memberDoc = new HashMap<>();
                        memberDoc.put("roomId", roomId);
                        memberDoc.put("userId", userId);
                        memberDoc.put("userName", userName);
                        memberDoc.put("userEmail", userEmail);
                        memberDoc.put("role", RoomMemberEntity.ROLE_OWNER);
                        memberDoc.put("status", RoomMemberEntity.STATUS_ACTIVE);
                        memberDoc.put("deviceInfo", deviceInfo);
                        memberDoc.put("joinedAt", now);
                        firestore.collection("rooms").document(roomId)
                                .collection("members").document(userId)
                                .set(memberDoc, SetOptions.merge());
                    } catch (Exception ignored) {
                    }
                }

                FirestoreRealtimeSyncManager.getInstance(appContext).startListeningForRoom(roomId);
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, room);
            } catch (Exception e) {
                postError(callback, "Failed to create room: " + e.getMessage());
            }
        });
    }

    /**
     * Saves a verified joined room and member record into local Room SQLite after Firestore
     * confirms the member document write, and updates the active room session.
     */
    public void saveJoinedRoomAndMemberLocally(
            RoomEntity matchedRoom,
            RoomMemberEntity member,
            ResultCallback<RoomEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                matchedRoom.failedJoinCount = 0;
                matchedRoom.lockoutUntil = 0L;
                roomDao.insertOrReplaceRoom(matchedRoom);

                roomMemberDao.insertOrReplaceMember(member);
                sessionManager.setActiveRoom(matchedRoom, member.role, member.status);

                long now = System.currentTimeMillis();
                String joinLogMsg = RoomMemberEntity.STATUS_PENDING_APPROVAL.equalsIgnoreCase(member.status)
                        ? member.userName + " (" + member.userEmail + ", " + member.deviceInfo + ") requested to join room \""
                        + matchedRoom.roomName + "\" — Waiting for Owner Approval."
                        : member.userName + " (" + member.deviceInfo + ") joined Private Room \"" + matchedRoom.roomName + "\" as " + member.role;

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
                postError(callback, "Failed to save room locally: " + e.getMessage());
            }
        });
    }

    /**
     * Updates local Room SQLite and active session immediately when a PENDING_APPROVAL joiner
     * is approved by the Room Owner in real time.
     */
    public void activateApprovedMembershipLocally(
            String roomId,
            String userId,
            String approvedRole,
            String remoteSigningSecret,
            ResultCallback<RoomEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                String safeRole = (approvedRole != null && !approvedRole.trim().isEmpty())
                        ? approvedRole.trim().toUpperCase(Locale.US)
                        : RoomMemberEntity.ROLE_COLLECTOR;
                roomMemberDao.updateMemberStatusAndRole(roomId, userId, RoomMemberEntity.STATUS_ACTIVE, safeRole);

                RoomEntity room = roomDao.findByIdSync(roomId);
                if (room != null) {
                    if (remoteSigningSecret != null && !remoteSigningSecret.trim().isEmpty()) {
                        room.roomSigningSecret = remoteSigningSecret.trim();
                        roomDao.insertOrReplaceRoom(room);
                    }
                    sessionManager.setActiveRoom(room, safeRole, RoomMemberEntity.STATUS_ACTIVE);
                } else {
                    sessionManager.updateActiveRoomRole(safeRole);
                    sessionManager.updateActiveRoomMemberStatus(RoomMemberEntity.STATUS_ACTIVE, remoteSigningSecret);
                }
                FirestoreRealtimeSyncManager.getInstance(appContext).stopListening();
                FirestoreRealtimeSyncManager.getInstance(appContext).startListeningForRoom(roomId);
                postSuccess(callback, room);
            } catch (Exception e) {
                postError(callback, "Failed to activate approved room membership: " + e.getMessage());
            }
        });
    }

    public RoomEntity findLocalRoomByKeySync(String roomKey) {
        if (roomKey == null || roomKey.trim().isEmpty()) {
            return null;
        }
        return roomDao.findByKeySync(roomKey.trim().toUpperCase(Locale.US));
    }

    public RoomMemberEntity findLocalMemberSync(String roomId, String userId) {
        if (roomId == null || userId == null) {
            return null;
        }
        return roomMemberDao.findMemberSync(roomId, userId);
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
                String privatePasswordHash = null;
                String privateSalt = null;
                String privateSigningSecret = null;
                String remoteMemberStatus = null;
                String remoteMemberRole = null;

                sessionManager.syncFromFirebaseAuthIfAvailable();
                sessionManager.ensureLocalFallbackUserIfNeeded();
                String userId = sessionManager.getCurrentUserId();
                String userName = sessionManager.getCurrentUserName();
                String userEmail = sessionManager.getCurrentUserEmail();
                String deviceInfo = RoomSecurityHelper.getDeviceInfo();
                long now = System.currentTimeMillis();

                boolean online = FirebaseSyncManager.isFirebaseConfigured(appContext)
                        && FirebaseSyncManager.isNetworkConnected(appContext);

                // Query Firestore by roomKey and also inspect rooms/{roomId}/private/security
                if (online) {
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
                            Long cAt = doc.getLong("createdAt");
                            Long uAt = doc.getLong("updatedAt");

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

                            try {
                                DocumentSnapshot memDoc = Tasks.await(
                                        firestore.collection("rooms")
                                                .document(rId)
                                                .collection("members")
                                                .document(userId)
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
                                    : (rPassHash != null ? rPassHash : "");
                            String effectiveSalt = (privatePasswordHash != null && !privatePasswordHash.trim().isEmpty())
                                    ? privateSalt
                                    : rSalt;
                            String effectiveSecret = (privateSigningSecret != null && !privateSigningSecret.trim().isEmpty())
                                    ? privateSigningSecret
                                    : (rSecret != null ? rSecret : SecuritySignatureHelper.getOrCreateSecretKey(appContext));

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
                            // Verify against both private/security and parent rooms/{roomId} fields
                            if (!RoomSecurityHelper.verifyRoomPasswordWithFallback(
                                    cleanPass,
                                    privatePasswordHash,
                                    privateSalt,
                                    rPassHash,
                                    rSalt
                            )) {
                                postError(callback, "Invalid Room Key or Password. Please try again.");
                                return;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (matchedRoom == null) {
                    postError(callback, "Invalid Room Key or Password. Please try again.");
                    return;
                }

                // Check password against matchedRoom if not already verified above
                boolean passwordValid = RoomSecurityHelper.verifyRoomPasswordWithFallback(
                        cleanPass,
                        privatePasswordHash,
                        privateSalt,
                        matchedRoom.roomPasswordHash,
                        matchedRoom.roomSalt
                );
                if (!passwordValid) {
                    postError(callback, "Invalid Room Key or Password. Please try again.");
                    return;
                }

                boolean isOwnerAccount = userId.equals(matchedRoom.ownerUserId);
                RoomMemberEntity existingMember = roomMemberDao.findMemberSync(matchedRoom.roomId, userId);

                boolean isBanned = (existingMember != null && existingMember.isBanned())
                        || RoomMemberEntity.STATUS_BANNED.equalsIgnoreCase(remoteMemberStatus);
                if (isBanned && !isOwnerAccount) {
                    postError(callback, "Access Denied: Your account has been banned from this room by the Owner.");
                    return;
                }

                boolean alreadyActive = (existingMember != null && existingMember.isActive())
                        || RoomMemberEntity.STATUS_ACTIVE.equalsIgnoreCase(remoteMemberStatus);

                if (matchedRoom.isLocked && !isOwnerAccount && !alreadyActive) {
                    postError(callback, "This room is currently LOCKED by the Room Owner. New joins are blocked.");
                    return;
                }

                if (matchedRoom.isKeyExpired(now) && !isOwnerAccount && !alreadyActive) {
                    postError(callback, "This Room Invite Key has expired. Ask the Room Owner to renew or rotate the Room Key.");
                    return;
                }

                String assignedRole;
                String memberStatus;
                if (isOwnerAccount) {
                    assignedRole = RoomMemberEntity.ROLE_OWNER;
                    memberStatus = RoomMemberEntity.STATUS_ACTIVE;
                } else if (alreadyActive) {
                    assignedRole = (remoteMemberRole != null && !remoteMemberRole.isEmpty())
                            ? remoteMemberRole
                            : (existingMember != null ? existingMember.role : RoomMemberEntity.ROLE_COLLECTOR);
                    memberStatus = RoomMemberEntity.STATUS_ACTIVE;
                } else {
                    assignedRole = RoomMemberEntity.ROLE_COLLECTOR;
                    memberStatus = matchedRoom.requireApproval
                            ? RoomMemberEntity.STATUS_PENDING_APPROVAL
                            : RoomMemberEntity.STATUS_ACTIVE;
                }

                if (online) {
                    Map<String, Object> memberMap = new HashMap<>();
                    memberMap.put("userId", userId);
                    memberMap.put("userName", userName);
                    memberMap.put("userEmail", userEmail);
                    memberMap.put("role", assignedRole);
                    memberMap.put("status", memberStatus);
                    memberMap.put("roomId", matchedRoom.roomId);
                    memberMap.put("joinedAt", now);
                    memberMap.put("deviceInfo", deviceInfo);

                    try {
                        Tasks.await(
                                FirebaseFirestore.getInstance()
                                        .collection("rooms")
                                        .document(matchedRoom.roomId)
                                        .collection("members")
                                        .document(userId)
                                        .set(memberMap, SetOptions.merge()),
                                15,
                                TimeUnit.SECONDS
                        );
                    } catch (Exception writeEx) {
                        postError(callback, "Failed to submit join request to Firestore: " + writeEx.getMessage());
                        return;
                    }
                }

                RoomMemberEntity member = new RoomMemberEntity(
                        matchedRoom.roomId,
                        userId,
                        userName,
                        userEmail,
                        assignedRole,
                        now,
                        memberStatus,
                        deviceInfo
                );
                saveJoinedRoomAndMemberLocally(matchedRoom, member, callback);
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

                if (FirebaseSyncManager.isFirebaseConfigured(appContext) && FirebaseSyncManager.isNetworkConnected(appContext)) {
                    Map<String, Object> update = new HashMap<>();
                    update.put("status", RoomMemberEntity.STATUS_ACTIVE);
                    update.put("role", role);
                    Tasks.await(
                            FirebaseFirestore.getInstance()
                                    .collection("rooms")
                                    .document(roomId)
                                    .collection("members")
                                    .document(targetUserId)
                                    .set(update, SetOptions.merge()),
                            15,
                            TimeUnit.SECONDS
                    );
                }

                roomMemberDao.updateMemberStatusAndRole(roomId, targetUserId, RoomMemberEntity.STATUS_ACTIVE, role);

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_SECURITY_MEMBER_APPROVED,
                        null,
                        sessionManager.getActiveRoomName(),
                        null,
                        "Approved pending member " + targetUserName + " as " + role,
                        now
                );

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
                    if (FirebaseSyncManager.isFirebaseConfigured(appContext) && FirebaseSyncManager.isNetworkConnected(appContext)) {
                        Map<String, Object> update = new HashMap<>();
                        update.put("status", RoomMemberEntity.STATUS_BANNED);
                        Tasks.await(
                                FirebaseFirestore.getInstance()
                                        .collection("rooms")
                                        .document(roomId)
                                        .collection("members")
                                        .document(targetUserId)
                                        .set(update, SetOptions.merge()),
                                15,
                                TimeUnit.SECONDS
                        );
                    }
                    roomMemberDao.updateMemberStatus(roomId, targetUserId, RoomMemberEntity.STATUS_BANNED);
                    insertAttributedAuditLogSync(
                            AuditLogEntity.ACTION_SECURITY_MEMBER_BANNED,
                            null,
                            sessionManager.getActiveRoomName(),
                            null,
                            "Permanently BANNED member " + targetUserName + " from room \"" + sessionManager.getActiveRoomName() + "\"",
                            now
                    );
                } else {
                    if (FirebaseSyncManager.isFirebaseConfigured(appContext) && FirebaseSyncManager.isNetworkConnected(appContext)) {
                        Tasks.await(
                                FirebaseFirestore.getInstance()
                                        .collection("rooms")
                                        .document(roomId)
                                        .collection("members")
                                        .document(targetUserId)
                                        .delete(),
                                15,
                                TimeUnit.SECONDS
                        );
                    } else {
                        FirebaseSyncManager.queueRemoteDeletion(appContext, roomId, "members", targetUserId);
                    }
                    roomMemberDao.deleteMember(roomId, targetUserId);
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
        createSection(sectionName, description, parentFolderId, false, callback);
    }

    public void createSection(String sectionName, String description, Long parentFolderId, boolean mergeGrossToParent, ResultCallback<SectionEntity> callback) {
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
                if (parentFolderId != null && parentFolderId > 0) {
                    SectionEntity parent = sectionDao.findByIdSync(parentFolderId);
                    if (parent != null && parent.isSubfolder()) {
                        postError(callback, "Subfolders can only be created inside a main section folder.");
                        return;
                    }
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
                        activeRoomId,
                        mergeGrossToParent
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
     * Updates mergeGrossToParent setting for a subfolder, writes audit log, and queues sync.
     */
    public void updateFolderMergeGross(long sectionId, boolean merge, ResultCallback<SectionEntity> callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canCreateOrEditRecords()) {
                    postError(callback, "Auditors have read-only access and cannot change folder settings.");
                    return;
                }
                SectionEntity target = sectionDao.findByIdSync(sectionId);
                if (target == null) {
                    postError(callback, "Folder not found.");
                    return;
                }
                long now = System.currentTimeMillis();
                sectionDao.updateMergeGrossToParent(sectionId, merge, now);

                String msg = merge
                        ? "Enabled gross collected merge to parent folder for \"" + target.sectionName + "\""
                        : "Disabled gross collected merge to parent folder for \"" + target.sectionName + "\"";

                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_FOLDER_MERGE_UPDATED,
                        sectionId,
                        target.sectionName,
                        null,
                        msg,
                        now
                );

                SectionEntity updated = sectionDao.findByIdSync(sectionId);
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, updated);
            } catch (Exception e) {
                postError(callback, "Failed to update folder merge setting: " + e.getMessage());
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

                List<RosterStudentEntity> rosterRows = new ArrayList<>();
                for (Long secId : allSectionIds) {
                    List<RosterStudentEntity> rows = rosterStudentDao.getRosterBySectionSync(secId);
                    if (rows != null) {
                        rosterRows.addAll(rows);
                    }
                }
                String roomId = (target.roomId != null && !target.roomId.isEmpty())
                        ? target.roomId
                        : sessionManager.getActiveRoomId();

                Set<String> lockDocIds = new LinkedHashSet<>();
                for (PaymentEntity p : payments) {
                    SectionEntity paymentSection = sectionDao.findByIdSync(p.sectionId);
                    double fee = paymentSection != null ? paymentSection.targetFeeAmount : 0.0;
                    lockDocIds.add(DuplicatePaymentGuard.buildLockDocumentId(
                            p.sectionId, p.firstName, p.lastName, p.purpose, fee));
                }

                // 1) Cascade the delete to Firestore atomically so every connected device drops the folder tree
                boolean remoteDeleted = deleteFolderTreeFromFirestore(
                        roomId, allSectionIds, payments, expenses, rosterRows, lockDocIds);

                for (PaymentEntity p : payments) {
                    if (p.proofImagePath != null) {
                        File f = new File(p.proofImagePath);
                        if (f.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                    if (!remoteDeleted) {
                        FirebaseSyncManager.queueRemoteDeletion(appContext, roomId, "payments", p.receiptId);
                    }
                }
                for (ExpenseEntity ex : expenses) {
                    if (ex.proofImagePath != null) {
                        File f = new File(ex.proofImagePath);
                        if (f.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                    if (!remoteDeleted) {
                        FirebaseSyncManager.queueRemoteDeletion(appContext, roomId, "expenses", "EXP-" + ex.id);
                    }
                }
                if (!remoteDeleted) {
                    for (RosterStudentEntity r : rosterRows) {
                        FirebaseSyncManager.queueRemoteDeletion(appContext, roomId, "roster_students", "ROSTER-" + r.id);
                    }
                    for (String lockId : lockDocIds) {
                        FirebaseSyncManager.queueRemoteDeletion(appContext, roomId, "payment_locks", lockId);
                    }
                    for (Long secId : allSectionIds) {
                        FirebaseSyncManager.queueRemoteDeletion(appContext, roomId, "sections", "SEC-" + secId);
                    }
                }

                // 2) Delete the same rows locally and record one FOLDER_DELETED audit entry
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

    /**
     * Atomically deletes a folder tree from Firestore using WriteBatch commits: the section docs, payments,
     * expenses, roster students and the related payment_locks, in both rooms/{roomId}/... and the top-level
     * mirror collections. Returns true only when every batch was committed; false means the caller should
     * fall back to the offline deletion queue.
     */
    private boolean deleteFolderTreeFromFirestore(
            String roomId,
            List<Long> sectionIds,
            List<PaymentEntity> payments,
            List<ExpenseEntity> expenses,
            List<RosterStudentEntity> rosterRows,
            Set<String> lockDocIds
    ) {
        if (roomId == null || roomId.isEmpty()
                || !FirebaseSyncManager.isFirebaseConfigured(appContext)
                || !FirebaseSyncManager.isNetworkConnected(appContext)) {
            return false;
        }
        try {
            FirebaseFirestore firestore = FirebaseFirestore.getInstance();
            DocumentReference roomRef = firestore.collection("rooms").document(roomId);
            Map<String, DocumentReference> refs = new HashMap<>();

            for (Long secId : sectionIds) {
                addDeleteRef(refs, roomRef.collection("sections").document("SEC-" + secId));
                addDeleteRef(refs, firestore.collection("sections").document("SEC-" + secId));
            }
            for (PaymentEntity p : payments) {
                addDeleteRef(refs, roomRef.collection("payments").document(p.receiptId));
                addDeleteRef(refs, firestore.collection("payments").document(p.receiptId));
            }
            for (ExpenseEntity ex : expenses) {
                addDeleteRef(refs, roomRef.collection("expenses").document("EXP-" + ex.id));
                addDeleteRef(refs, firestore.collection("expenses").document("EXP-" + ex.id));
            }
            for (RosterStudentEntity r : rosterRows) {
                addDeleteRef(refs, roomRef.collection("roster_students").document("ROSTER-" + r.id));
                addDeleteRef(refs, firestore.collection("roster_students").document("ROSTER-" + r.id));
            }
            for (String lockId : lockDocIds) {
                addDeleteRef(refs, roomRef.collection("payment_locks").document(lockId));
            }
            // Also release any payment_locks that reference these sections
            for (Long secId : sectionIds) {
                QuerySnapshot lockSnap = Tasks.await(
                        roomRef.collection("payment_locks").whereEqualTo("sectionId", secId).get(),
                        20,
                        TimeUnit.SECONDS
                );
                if (lockSnap != null) {
                    for (DocumentSnapshot lockDoc : lockSnap.getDocuments()) {
                        addDeleteRef(refs, lockDoc.getReference());
                    }
                }
            }

            List<DocumentReference> ordered = new ArrayList<>(refs.values());
            final int batchLimit = 400;
            for (int start = 0; start < ordered.size(); start += batchLimit) {
                WriteBatch batch = firestore.batch();
                int end = Math.min(ordered.size(), start + batchLimit);
                for (int i = start; i < end; i++) {
                    batch.delete(ordered.get(i));
                }
                Tasks.await(batch.commit(), 30, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void addDeleteRef(Map<String, DocumentReference> refs, DocumentReference ref) {
        refs.put(ref.getPath(), ref);
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
                if (!sessionManager.canVoidOrEditRecords()) {
                    postError(callback, "Auditors have read-only access and cannot void payment receipts.");
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
                if (!sessionManager.canVoidOrEditRecords()) {
                    postError(callback, "Auditors have read-only access and cannot void expense deductions.");
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

    /**
     * Full Receipt Editing with automatic balance recalculation, QR HMAC signature regeneration,
     * duplicate lock update, diff audit logging, and cloud sync.
     */
    public void updateReceipt(
            String receiptId,
            String newFirstName,
            String newLastName,
            String newStudentNumber,
            double newAmount,
            String newPaymentMethod,
            String newGcashRef,
            String newPurpose,
            String newProofImagePath,
            ResultCallback<PaymentEntity> callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                if (!sessionManager.canVoidOrEditRecords()) {
                    postError(callback, "Auditors have read-only access and cannot edit receipts.");
                    return;
                }

                PaymentEntity existing = paymentDao.findByReceiptIdSync(receiptId);
                if (existing == null) {
                    postError(callback, "Receipt not found.");
                    return;
                }
                if (existing.isVoided()) {
                    postError(callback, "Voided receipts cannot be edited.");
                    return;
                }

                String cleanFirst = newFirstName != null ? newFirstName.trim() : "";
                String cleanLast = newLastName != null ? newLastName.trim() : "";
                if (cleanFirst.isEmpty() || cleanLast.isEmpty()) {
                    postError(callback, "Student first and last name are required.");
                    return;
                }
                if (newAmount <= 0.0) {
                    postError(callback, "Amount must be greater than ₱0.00.");
                    return;
                }

                String cleanStudentNo = (newStudentNumber != null && !newStudentNumber.trim().isEmpty())
                        ? newStudentNumber.trim()
                        : null;
                String cleanPurpose = (newPurpose != null && !newPurpose.trim().isEmpty())
                        ? newPurpose.trim()
                        : existing.purpose;
                String cleanMethod = (newPaymentMethod != null && !newPaymentMethod.trim().isEmpty())
                        ? newPaymentMethod.trim().toUpperCase(Locale.US)
                        : existing.paymentMethod;
                String cleanRef = (newGcashRef != null && !newGcashRef.trim().isEmpty())
                        ? newGcashRef.trim()
                        : null;

                SectionEntity section = sectionDao.findByIdSync(existing.sectionId);
                double targetFee = section != null ? section.targetFeeAmount : 0.0;

                // Check duplicate collisions if identity changed
                String newIdentityKey = DuplicatePaymentGuard.buildStudentIdentityKey(
                        existing.sectionId,
                        cleanFirst,
                        cleanLast,
                        cleanPurpose,
                        targetFee
                );

                String oldIdentityKey = existing.studentIdentityKey != null
                        ? existing.studentIdentityKey
                        : DuplicatePaymentGuard.buildStudentIdentityKey(
                                existing.sectionId,
                                existing.firstName,
                                existing.lastName,
                                existing.purpose,
                                targetFee
                        );

                if (!newIdentityKey.equals(oldIdentityKey)) {
                    List<PaymentEntity> duplicates = paymentDao.findActiveDuplicatesForStudentSync(
                            existing.sectionId,
                            newIdentityKey,
                            cleanStudentNo != null ? cleanStudentNo : "",
                            DuplicatePaymentGuard.normalizeText(cleanFirst),
                            DuplicatePaymentGuard.normalizeText(cleanLast)
                    );
                    for (PaymentEntity d : duplicates) {
                        if (!d.receiptId.equals(receiptId) && !d.isVoided()) {
                            postError(callback, "Cannot update: Another active receipt (" + d.receiptId
                                    + ") already exists for " + cleanFirst + " " + cleanLast + " in this folder.");
                            return;
                        }
                    }
                }

                // Balance recalculation
                double remainingBalance = 0.0;
                String paymentStatus = PaymentEntity.STATUS_PAID;
                if (targetFee > 0.005 && newAmount < targetFee - 0.005) {
                    remainingBalance = targetFee - newAmount;
                    paymentStatus = PaymentEntity.STATUS_PARTIAL;
                }

                // Regenerate Cryptographic QR HMAC-SHA256 signature
                String activeRoomSecret = sessionManager.getActiveRoomSigningSecret();
                String secretToUse = (activeRoomSecret != null && activeRoomSecret.length() >= 32)
                        ? activeRoomSecret
                        : SecuritySignatureHelper.getOrCreateSecretKey(appContext);

                String newQrSig = SecuritySignatureHelper.computeReceiptSignatureWithSecret(
                        secretToUse,
                        receiptId,
                        existing.sectionId,
                        cleanFirst,
                        cleanLast,
                        newAmount,
                        existing.createdAt
                );
                String newQrPayload = SecuritySignatureHelper.buildQrPayload(receiptId, newQrSig);

                // Proof image handling
                String finalProofPath = newProofImagePath != null ? newProofImagePath : existing.proofImagePath;
                String finalProofUrl = existing.proofImageUrl;
                String imageSyncStatus = existing.imageSyncStatus != null ? existing.imageSyncStatus : PaymentEntity.IMAGE_STATUS_NONE;
                if (newProofImagePath != null && !newProofImagePath.equals(existing.proofImagePath)) {
                    imageSyncStatus = PaymentEntity.IMAGE_STATUS_PENDING_UPLOAD;
                    finalProofUrl = null;
                }

                long now = System.currentTimeMillis();
                String editorName = sessionManager.getCurrentUserName();

                // Compute audit diff
                List<String> diffs = new ArrayList<>();
                if (!cleanFirst.equals(existing.firstName) || !cleanLast.equals(existing.lastName)) {
                    diffs.add("Name: " + existing.getFullName() + " -> " + cleanFirst + " " + cleanLast);
                }
                if (Math.abs(newAmount - existing.amount) > 0.005) {
                    diffs.add("Amount: " + CurrencyFormatter.formatPhp(existing.amount) + " -> " + CurrencyFormatter.formatPhp(newAmount));
                }
                if (!cleanMethod.equals(existing.paymentMethod)) {
                    diffs.add("Mode: " + existing.paymentMethod + " -> " + cleanMethod);
                }
                if (!cleanPurpose.equals(existing.purpose)) {
                    diffs.add("Purpose: " + existing.purpose + " -> " + cleanPurpose);
                }
                if (cleanStudentNo != null && !cleanStudentNo.equals(existing.studentNumber)) {
                    diffs.add("Student No: " + (existing.studentNumber != null ? existing.studentNumber : "none") + " -> " + cleanStudentNo);
                }
                if (diffs.isEmpty()) {
                    diffs.add("Proof photo / metadata updated");
                }
                String diffSummary = "Edited Receipt " + receiptId + ": " + String.join(", ", diffs);

                // Update duplicate locks in Firestore if identity changed
                String targetRoomId = existing.roomId != null ? existing.roomId : sessionManager.getActiveRoomId();
                if (targetRoomId != null && !targetRoomId.isEmpty()
                        && !newIdentityKey.equals(oldIdentityKey)
                        && FirebaseSyncManager.isFirebaseConfigured(appContext)
                        && FirebaseSyncManager.isNetworkConnected(appContext)) {
                    try {
                        DuplicatePaymentGuard.releaseRemotePaymentLock(appContext, targetRoomId, existing, targetFee);
                        PaymentEntity dummyForLock = new PaymentEntity(
                                receiptId,
                                existing.sectionId,
                                existing.sectionName,
                                cleanFirst,
                                cleanLast,
                                cleanStudentNo,
                                cleanPurpose,
                                newAmount,
                                cleanMethod,
                                cleanRef,
                                finalProofPath,
                                finalProofUrl,
                                PaymentEntity.STATUS_PAID,
                                paymentStatus,
                                remainingBalance,
                                null,
                                existing.createdAt,
                                null,
                                newQrSig,
                                newQrPayload,
                                PaymentEntity.SYNC_PENDING,
                                now,
                                targetRoomId,
                                existing.actorUserId,
                                existing.actorName,
                                existing.actorRole,
                                newIdentityKey,
                                existing.duplicateOverrideReason
                        );
                        DuplicatePaymentGuard.acquireRemotePaymentLockAndCommit(
                                appContext,
                                targetRoomId,
                                dummyForLock,
                                targetFee,
                                false
                        );
                    } catch (Exception ignoredLockErr) {
                    }
                }

                // Update Room SQLite
                paymentDao.updateReceiptFull(
                        receiptId,
                        cleanFirst,
                        cleanLast,
                        cleanStudentNo,
                        newAmount,
                        cleanMethod,
                        cleanRef,
                        cleanPurpose,
                        finalProofPath,
                        finalProofUrl,
                        imageSyncStatus,
                        remainingBalance,
                        paymentStatus,
                        PaymentEntity.STATUS_PAID,
                        newIdentityKey,
                        newQrSig,
                        newQrPayload,
                        editorName,
                        now,
                        now
                );

                // Insert immutable audit log
                insertAttributedAuditLogSync(
                        AuditLogEntity.ACTION_RECEIPT_EDITED,
                        existing.sectionId,
                        existing.sectionName,
                        receiptId,
                        diffSummary,
                        now
                );

                PaymentEntity updated = paymentDao.findByReceiptIdSync(receiptId);
                FirebaseSyncManager.enqueueImmediateSyncIfAvailable(appContext);
                postSuccess(callback, updated);
            } catch (Exception e) {
                postError(callback, "Failed to update receipt: " + e.getMessage());
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
