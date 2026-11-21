package com.example.qr_paymentscan_cash_n_online.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.AuditLogEntity;
import com.example.qr_paymentscan_cash_n_online.data.ExpenseEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.data.RoomMemberEntity;
import com.example.qr_paymentscan_cash_n_online.data.RosterStudentEntity;
import com.example.qr_paymentscan_cash_n_online.data.SectionEntity;
import com.example.qr_paymentscan_cash_n_online.util.DuplicatePaymentGuard;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class FirebaseSyncManager {

    public enum SyncUiState {
        LOCAL_MODE_UNCONFIGURED,
        OFFLINE_WAITING_WIFI,
        SYNCING,
        SYNCED,
        PENDING_RECORDS
    }

    private static final String UNIQUE_ONE_TIME_SYNC = "PaymentTrackerImmediateSync";
    private static final String UNIQUE_PERIODIC_SYNC = "PaymentTrackerPeriodicSync";
    private static final String PREFS_DELETED_QUEUE = "firebase_deleted_records_queue";
    private static final String KEY_DELETED_JSON = "deleted_items_json";

    private static final MutableLiveData<SyncUiState> SYNC_STATE_LIVE =
            new MutableLiveData<>(SyncUiState.LOCAL_MODE_UNCONFIGURED);

    private static boolean networkCallbackRegistered = false;

    private FirebaseSyncManager() {
    }

    public static LiveData<SyncUiState> observeSyncUiState() {
        return SYNC_STATE_LIVE;
    }

    /**
     * Safely checks whether Firebase is configured with a real google-services.json.
     * Never throws or crashes even when google-services.json is absent.
     */
    public static boolean isFirebaseConfigured(Context context) {
        try {
            Context appCtx = context.getApplicationContext();
            List<FirebaseApp> apps = FirebaseApp.getApps(appCtx);
            if (apps == null || apps.isEmpty()) {
                FirebaseApp initialized = FirebaseApp.initializeApp(appCtx);
                if (initialized == null) {
                    return false;
                }
            }
            FirebaseApp defaultApp = FirebaseApp.getInstance();
            FirebaseOptions options = defaultApp.getOptions();
            String appId = options.getApplicationId();
            String projectId = options.getProjectId();
            return appId != null && !appId.trim().isEmpty()
                    && projectId != null && !projectId.trim().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isNetworkConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Registers a live ConnectivityManager.NetworkCallback and PeriodicWorkRequest so whenever Wi-Fi/internet
     * becomes available and Firebase is configured, all local PENDING records automatically migrate online
     * and real-time room listeners attach.
     */
    public static synchronized void initializeAutoSync(Context context) {
        final Context appContext = context.getApplicationContext();
        refreshSyncStatusUi(appContext);

        String activeRoomId = UserSessionManager.getInstance(appContext).getActiveRoomId();
        if (activeRoomId != null && !activeRoomId.isEmpty() && isNetworkConnected(appContext)) {
            FirestoreRealtimeSyncManager.getInstance(appContext).startListeningForRoom(activeRoomId);
        }

        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            PeriodicWorkRequest periodicRequest = new PeriodicWorkRequest.Builder(
                    SyncWorker.class,
                    15,
                    TimeUnit.MINUTES
            )
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build();

            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC_SYNC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest
            );
        } catch (Exception ignored) {
        }

        if (!networkCallbackRegistered) {
            try {
                ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    NetworkRequest request = new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();
                    cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(@NonNull Network network) {
                            refreshSyncStatusUi(appContext);
                            enqueueImmediateSyncIfAvailable(appContext);
                            String roomId = UserSessionManager.getInstance(appContext).getActiveRoomId();
                            if (roomId != null && !roomId.isEmpty()) {
                                FirestoreRealtimeSyncManager.getInstance(appContext).startListeningForRoom(roomId);
                            }
                        }

                        @Override
                        public void onLost(@NonNull Network network) {
                            refreshSyncStatusUi(appContext);
                        }
                    });
                    networkCallbackRegistered = true;
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void refreshSyncStatusUi(Context context) {
        final Context appCtx = context.getApplicationContext();
        AppDatabase.DB_EXECUTOR.execute(() -> {
            if (!isFirebaseConfigured(appCtx)) {
                SYNC_STATE_LIVE.postValue(SyncUiState.LOCAL_MODE_UNCONFIGURED);
                return;
            }
            if (!isNetworkConnected(appCtx)) {
                SYNC_STATE_LIVE.postValue(SyncUiState.OFFLINE_WAITING_WIFI);
                return;
            }
            AppDatabase db = AppDatabase.getInstance(appCtx);
            int pendingCount = db.sectionDao().getPendingSyncSections().size()
                    + db.paymentDao().getPendingSyncPayments().size()
                    + db.expenseDao().getPendingSyncExpenses().size();
            if (pendingCount > 0) {
                SYNC_STATE_LIVE.postValue(SyncUiState.PENDING_RECORDS);
            } else {
                SYNC_STATE_LIVE.postValue(SyncUiState.SYNCED);
            }
        });
    }

    public static void enqueueImmediateSyncIfAvailable(Context context) {
        final Context appCtx = context.getApplicationContext();
        refreshSyncStatusUi(appCtx);
        if (!isFirebaseConfigured(appCtx)) {
            return;
        }
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(SyncWorker.class)
                    .setConstraints(constraints)
                    .build();

            WorkManager.getInstance(appCtx).enqueueUniqueWork(
                    UNIQUE_ONE_TIME_SYNC,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
            );
        } catch (Exception ignored) {
        }
    }

    public static synchronized void queueRemoteDeletion(Context context, String collection, String docId) {
        queueRemoteDeletion(context, UserSessionManager.getInstance(context).getActiveRoomId(), collection, docId);
    }

    public static synchronized void queueRemoteDeletion(Context context, String roomId, String collection, String docId) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_DELETED_QUEUE, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_DELETED_JSON, "[]");
            JSONArray arr = new JSONArray(raw);
            JSONObject entry = new JSONObject();
            entry.put("roomId", roomId != null ? roomId : "");
            entry.put("collection", collection);
            entry.put("docId", docId);
            arr.put(entry);
            prefs.edit().putString(KEY_DELETED_JSON, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Synchronous worker execution called from SyncWorker on a background thread.
     * Pushes all local Rooms, Room Members, PENDING Room records, and local GCash/Expense proof images
     * to Firebase Firestore (both top-level and rooms/{roomId}/... subcollections) + Storage.
     */
    public static boolean performBlockingCloudSync(Context context) {
        Context appCtx = context.getApplicationContext();
        if (!isFirebaseConfigured(appCtx) || !isNetworkConnected(appCtx)) {
            refreshSyncStatusUi(appCtx);
            return true;
        }

        SYNC_STATE_LIVE.postValue(SyncUiState.SYNCING);
        try {
            FirebaseFirestore firestore = FirebaseFirestore.getInstance();
            FirebaseStorage storage = FirebaseStorage.getInstance();
            AppDatabase db = AppDatabase.getInstance(appCtx);
            String defaultRoomId = UserSessionManager.getInstance(appCtx).getActiveRoomId();

            // 1. Process queued folder/record deletions first
            flushQueuedDeletions(appCtx, firestore);

            // 2. Sync all local Rooms & Room Members so newly created rooms, security flags, and role assignments exist in Firestore
            List<RoomEntity> rooms = db.roomDao().getAllRoomsSync();
            for (RoomEntity room : rooms) {
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
                roomDoc.put("failedJoinCount", room.failedJoinCount);
                roomDoc.put("lockoutUntil", room.lockoutUntil);
                roomDoc.put("createdAt", room.createdAt);
                roomDoc.put("updatedAt", room.updatedAt);

                Tasks.await(
                        firestore.collection("rooms")
                                .document(room.roomId)
                                .set(roomDoc, SetOptions.merge()),
                        20,
                        TimeUnit.SECONDS
                );

                // Isolate secrets in rooms/{roomId}/private/security
                Map<String, Object> securityDoc = new HashMap<>();
                securityDoc.put("roomPasswordHash", room.roomPasswordHash);
                securityDoc.put("roomSalt", room.roomSalt != null ? room.roomSalt : "");
                securityDoc.put("roomSigningSecret", room.roomSigningSecret);
                securityDoc.put("updatedAt", room.updatedAt);
                try {
                    Tasks.await(
                            firestore.collection("rooms")
                                    .document(room.roomId)
                                    .collection("private")
                                    .document("security")
                                    .set(securityDoc, SetOptions.merge()),
                            15,
                            TimeUnit.SECONDS
                    );
                } catch (Exception ignored) {
                }

                List<RoomMemberEntity> members = db.roomMemberDao().getMembersByRoomSync(room.roomId);
                for (RoomMemberEntity m : members) {
                    Map<String, Object> memberDoc = new HashMap<>();
                    memberDoc.put("roomId", m.roomId);
                    memberDoc.put("userId", m.userId);
                    memberDoc.put("userName", m.userName);
                    memberDoc.put("userEmail", m.userEmail);
                    memberDoc.put("role", m.role);
                    memberDoc.put("status", m.status != null ? m.status : RoomMemberEntity.STATUS_ACTIVE);
                    memberDoc.put("deviceInfo", m.deviceInfo != null ? m.deviceInfo : "");
                    memberDoc.put("joinedAt", m.joinedAt);

                    Tasks.await(
                            firestore.collection("rooms")
                                    .document(room.roomId)
                                    .collection("members")
                                    .document(m.userId)
                                    .set(memberDoc, SetOptions.merge()),
                            15,
                            TimeUnit.SECONDS
                    );
                }
            }

            // 3. Sync Pending Sections
            List<SectionEntity> pendingSections = db.sectionDao().getPendingSyncSections();
            for (SectionEntity s : pendingSections) {
                String targetRoomId = (s.roomId != null && !s.roomId.isEmpty()) ? s.roomId : defaultRoomId;
                long now = System.currentTimeMillis();
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", s.id);
                doc.put("roomId", targetRoomId);
                doc.put("sectionName", s.sectionName);
                doc.put("description", s.description);
                doc.put("createdAt", s.createdAt);
                doc.put("parentFolderId", s.parentFolderId);
                doc.put("targetFeeAmount", s.targetFeeAmount);
                doc.put("updatedAt", now);

                Tasks.await(
                        firestore.collection("sections")
                                .document("SEC-" + s.id)
                                .set(doc, SetOptions.merge()),
                        25,
                        TimeUnit.SECONDS
                );
                if (targetRoomId != null && !targetRoomId.isEmpty()) {
                    Tasks.await(
                            firestore.collection("rooms")
                                    .document(targetRoomId)
                                    .collection("sections")
                                    .document("SEC-" + s.id)
                                    .set(doc, SetOptions.merge()),
                            25,
                            TimeUnit.SECONDS
                    );
                }
                db.sectionDao().updateSyncStatus(s.id, SectionEntity.SYNC_SYNCED, now);
            }

            // 4. Sync Pending Payments + Upload GCash Proof Image if present + Offline Duplicate Conflict Check
            List<PaymentEntity> pendingPayments = db.paymentDao().getPendingSyncPayments();
            for (PaymentEntity p : pendingPayments) {
                String targetRoomId = (p.roomId != null && !p.roomId.isEmpty()) ? p.roomId : defaultRoomId;
                String remoteProofUrl = p.remoteProofUrl;
                if ((remoteProofUrl == null || remoteProofUrl.isEmpty())
                        && p.proofImagePath != null) {
                    File localFile = new File(p.proofImagePath);
                    if (localFile.exists()) {
                        remoteProofUrl = uploadProofFileToFirebaseStorage(
                                storage,
                                "payment_proofs/" + p.receiptId + "_" + localFile.getName(),
                                localFile
                        );
                    }
                }

                SectionEntity sec = db.sectionDao().findByIdSync(p.sectionId);
                double targetFee = sec != null ? sec.targetFeeAmount : 0.0;
                String identityKey = (p.studentIdentityKey != null && !p.studentIdentityKey.isEmpty())
                        ? p.studentIdentityKey
                        : DuplicatePaymentGuard.buildStudentIdentityKey(p.sectionId, p.firstName, p.lastName, p.purpose, targetFee);

                long now = System.currentTimeMillis();
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", p.id);
                doc.put("roomId", targetRoomId);
                doc.put("receiptId", p.receiptId);
                doc.put("sectionId", p.sectionId);
                doc.put("sectionName", p.sectionName);
                doc.put("firstName", p.firstName);
                doc.put("lastName", p.lastName);
                doc.put("studentNumber", p.studentNumber);
                doc.put("studentIdentityKey", identityKey);
                doc.put("duplicateOverrideReason", p.duplicateOverrideReason != null ? p.duplicateOverrideReason : "");
                doc.put("purpose", p.purpose);
                doc.put("amount", p.amount);
                doc.put("paymentMethod", p.paymentMethod);
                doc.put("gcashReferenceNo", p.gcashReferenceNo);
                doc.put("remoteProofUrl", remoteProofUrl);
                doc.put("status", p.status);
                doc.put("paymentStatus", p.paymentStatus);
                doc.put("remainingBalance", p.remainingBalance);
                doc.put("voidReason", p.voidReason);
                doc.put("createdAt", p.createdAt);
                doc.put("voidedAt", p.voidedAt);
                doc.put("qrSignature", p.qrSignature);
                doc.put("qrPayload", p.qrPayload);
                doc.put("actorUserId", p.actorUserId);
                doc.put("actorName", p.actorName);
                doc.put("actorRole", p.actorRole);
                doc.put("updatedAt", now);

                Tasks.await(
                        firestore.collection("payments")
                                .document(p.receiptId)
                                .set(doc, SetOptions.merge()),
                        25,
                        TimeUnit.SECONDS
                );
                if (targetRoomId != null && !targetRoomId.isEmpty()) {
                    // Check or update payment_locks for offline duplicate conflict detection
                    String lockDocId = DuplicatePaymentGuard.buildLockDocumentId(
                            p.sectionId,
                            p.firstName,
                            p.lastName,
                            p.purpose,
                            targetFee
                    );
                    DocumentReference lockRef = firestore.collection("rooms")
                            .document(targetRoomId)
                            .collection("payment_locks")
                            .document(lockDocId);
                    try {
                        if (p.isVoided()) {
                            Tasks.await(lockRef.delete(), 10, TimeUnit.SECONDS);
                        } else if (p.duplicateOverrideReason == null || p.duplicateOverrideReason.trim().isEmpty()) {
                            DocumentSnapshot lockSnap = Tasks.await(lockRef.get(), 10, TimeUnit.SECONDS);
                            if (lockSnap != null && lockSnap.exists()) {
                                String existingReceiptId = lockSnap.getString("receiptId");
                                String existingStatus = lockSnap.getString("status");
                                String existingActor = lockSnap.getString("actorName");
                                if (existingReceiptId != null && !existingReceiptId.isEmpty()
                                        && !existingReceiptId.equals(p.receiptId)
                                        && !PaymentEntity.STATUS_VOIDED.equalsIgnoreCase(existingStatus)) {
                                    // Offline sync conflict detected: log DUPLICATE_SYNC_CONFLICT for the Room Owner
                                    db.auditLogDao().insertAuditLog(new AuditLogEntity(
                                            AuditLogEntity.ACTION_DUPLICATE_SYNC_CONFLICT,
                                            p.sectionId,
                                            p.sectionName,
                                            p.receiptId,
                                            "OFFLINE DUPLICATE CONFLICT: Offline receipt " + p.receiptId + " for "
                                                    + p.getFullName() + " conflicts with receipt " + existingReceiptId
                                                    + " already recorded by " + (existingActor != null ? existingActor : "Another Cashier")
                                                    + ". Owner review required.",
                                            now,
                                            AuditLogEntity.SYNC_PENDING,
                                            now,
                                            targetRoomId,
                                            p.actorUserId,
                                            p.actorName,
                                            p.actorRole
                                    ));
                                }
                            } else {
                                Map<String, Object> lockData = new HashMap<>();
                                lockData.put("lockKey", lockDocId);
                                lockData.put("roomId", targetRoomId);
                                lockData.put("sectionId", p.sectionId);
                                lockData.put("studentIdentityKey", identityKey);
                                lockData.put("studentNumber", p.studentNumber != null ? p.studentNumber : "");
                                lockData.put("studentName", p.getFullName());
                                lockData.put("receiptId", p.receiptId);
                                lockData.put("amount", p.amount);
                                lockData.put("remainingBalance", p.remainingBalance);
                                lockData.put("paymentMethod", p.paymentMethod);
                                lockData.put("status", p.status);
                                lockData.put("paymentStatus", p.paymentStatus);
                                lockData.put("actorUserId", p.actorUserId != null ? p.actorUserId : "");
                                lockData.put("actorName", p.actorName != null ? p.actorName : "");
                                lockData.put("actorRole", p.actorRole != null ? p.actorRole : "");
                                lockData.put("updatedAt", now);
                                Tasks.await(lockRef.set(lockData, SetOptions.merge()), 10, TimeUnit.SECONDS);
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    Tasks.await(
                            firestore.collection("rooms")
                                    .document(targetRoomId)
                                    .collection("payments")
                                    .document(p.receiptId)
                                    .set(doc, SetOptions.merge()),
                            25,
                            TimeUnit.SECONDS
                    );
                }
                db.paymentDao().updateSyncState(p.id, PaymentEntity.SYNC_SYNCED, remoteProofUrl, now);
            }

            // 5. Sync Pending Expenses + Upload Expense Proof Image if present
            List<ExpenseEntity> pendingExpenses = db.expenseDao().getPendingSyncExpenses();
            for (ExpenseEntity ex : pendingExpenses) {
                String targetRoomId = (ex.roomId != null && !ex.roomId.isEmpty()) ? ex.roomId : defaultRoomId;
                String remoteProofUrl = ex.remoteProofUrl;
                if ((remoteProofUrl == null || remoteProofUrl.isEmpty())
                        && ex.proofImagePath != null) {
                    File localFile = new File(ex.proofImagePath);
                    if (localFile.exists()) {
                        remoteProofUrl = uploadProofFileToFirebaseStorage(
                                storage,
                                "expense_proofs/EXP-" + ex.id + "_" + localFile.getName(),
                                localFile
                        );
                    }
                }

                long now = System.currentTimeMillis();
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", ex.id);
                doc.put("roomId", targetRoomId);
                doc.put("sectionId", ex.sectionId);
                doc.put("sectionName", ex.sectionName);
                doc.put("title", ex.title);
                doc.put("amount", ex.amount);
                doc.put("category", ex.category);
                doc.put("notes", ex.notes);
                doc.put("remoteProofUrl", remoteProofUrl);
                doc.put("status", ex.status);
                doc.put("voidReason", ex.voidReason);
                doc.put("createdAt", ex.createdAt);
                doc.put("voidedAt", ex.voidedAt);
                doc.put("actorUserId", ex.actorUserId);
                doc.put("actorName", ex.actorName);
                doc.put("actorRole", ex.actorRole);
                doc.put("updatedAt", now);

                Tasks.await(
                        firestore.collection("expenses")
                                .document("EXP-" + ex.id)
                                .set(doc, SetOptions.merge()),
                        25,
                        TimeUnit.SECONDS
                );
                if (targetRoomId != null && !targetRoomId.isEmpty()) {
                    Tasks.await(
                            firestore.collection("rooms")
                                    .document(targetRoomId)
                                    .collection("expenses")
                                    .document("EXP-" + ex.id)
                                    .set(doc, SetOptions.merge()),
                            25,
                            TimeUnit.SECONDS
                    );
                }
                db.expenseDao().updateSyncState(ex.id, ExpenseEntity.SYNC_SYNCED, remoteProofUrl, now);
            }

            // 6. Sync Pending Roster Students
            List<RosterStudentEntity> pendingRoster = db.rosterStudentDao().getPendingSyncRosterStudents();
            for (RosterStudentEntity r : pendingRoster) {
                String targetRoomId = (r.roomId != null && !r.roomId.isEmpty()) ? r.roomId : defaultRoomId;
                long now = System.currentTimeMillis();
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", r.id);
                doc.put("roomId", targetRoomId);
                doc.put("sectionId", r.sectionId);
                doc.put("firstName", r.firstName);
                doc.put("lastName", r.lastName);
                doc.put("studentNumber", r.studentNumber);
                doc.put("updatedAt", now);

                Tasks.await(
                        firestore.collection("roster_students")
                                .document("ROSTER-" + r.id)
                                .set(doc, SetOptions.merge()),
                        25,
                        TimeUnit.SECONDS
                );
                if (targetRoomId != null && !targetRoomId.isEmpty()) {
                    Tasks.await(
                            firestore.collection("rooms")
                                    .document(targetRoomId)
                                    .collection("roster_students")
                                    .document("ROSTER-" + r.id)
                                    .set(doc, SetOptions.merge()),
                            25,
                            TimeUnit.SECONDS
                    );
                }
                db.rosterStudentDao().updateSyncStatus(r.id, "SYNCED", now);
            }

            // 7. Sync Pending Audit Logs
            List<AuditLogEntity> pendingLogs = db.auditLogDao().getPendingSyncAuditLogs();
            for (AuditLogEntity log : pendingLogs) {
                String targetRoomId = (log.roomId != null && !log.roomId.isEmpty()) ? log.roomId : defaultRoomId;
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", log.id);
                doc.put("roomId", targetRoomId);
                doc.put("actionType", log.actionType);
                doc.put("sectionId", log.sectionId);
                doc.put("sectionName", log.sectionName);
                doc.put("receiptId", log.receiptId);
                doc.put("details", log.details);
                doc.put("timestamp", log.timestamp);
                doc.put("actorUserId", log.actorUserId);
                doc.put("actorName", log.actorName);
                doc.put("actorRole", log.actorRole);

                String logDocId = "LOG-" + log.id + "-" + log.timestamp;
                Tasks.await(
                        firestore.collection("audit_logs")
                                .document(logDocId)
                                .set(doc, SetOptions.merge()),
                        20,
                        TimeUnit.SECONDS
                );
                if (targetRoomId != null && !targetRoomId.isEmpty()) {
                    Tasks.await(
                            firestore.collection("rooms")
                                    .document(targetRoomId)
                                    .collection("audit_logs")
                                    .document(logDocId)
                                    .set(doc, SetOptions.merge()),
                            20,
                            TimeUnit.SECONDS
                    );
                }
                db.auditLogDao().updateSyncStatus(log.id, AuditLogEntity.SYNC_SYNCED, System.currentTimeMillis());
            }

            SYNC_STATE_LIVE.postValue(SyncUiState.SYNCED);
            return true;
        } catch (Exception e) {
            refreshSyncStatusUi(appCtx);
            return false;
        }
    }

    private static void flushQueuedDeletions(Context context, FirebaseFirestore firestore) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_DELETED_QUEUE, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_DELETED_JSON, "[]");
            JSONArray arr = new JSONArray(raw);
            if (arr.length() == 0) {
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String roomId = item.optString("roomId", "");
                String col = item.getString("collection");
                String docId = item.getString("docId");
                Tasks.await(firestore.collection(col).document(docId).delete(), 15, TimeUnit.SECONDS);
                if (!roomId.isEmpty()) {
                    Tasks.await(
                            firestore.collection("rooms")
                                    .document(roomId)
                                    .collection(col)
                                    .document(docId)
                                    .delete(),
                            15,
                            TimeUnit.SECONDS
                    );
                }
            }
            prefs.edit().putString(KEY_DELETED_JSON, "[]").apply();
        } catch (Exception ignored) {
        }
    }

    private static String uploadProofFileToFirebaseStorage(
            FirebaseStorage storage,
            String storagePath,
            File localFile
    ) {
        try {
            StorageReference ref = storage.getReference().child(storagePath);
            Tasks.await(ref.putFile(Uri.fromFile(localFile)), 45, TimeUnit.SECONDS);
            Uri downloadUri = Tasks.await(ref.getDownloadUrl(), 20, TimeUnit.SECONDS);
            return downloadUri != null ? downloadUri.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
