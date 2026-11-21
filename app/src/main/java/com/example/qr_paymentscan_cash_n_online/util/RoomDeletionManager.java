package com.example.qr_paymentscan_cash_n_online.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.sync.FirestoreRealtimeSyncManager;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.concurrent.TimeUnit;

public class RoomDeletionManager {

    public interface DeletionCallback {
        void onSuccess();
        void onError(String errorMessage);
    }

    /**
     * Re-authenticates owner account, deletes all subcollections & parent room document from Firestore,
     * deletes local SQLite data, and clears active session.
     */
    public static void deleteRoomCascade(
            @NonNull Context context,
            @NonNull String roomId,
            @NonNull String accountPassword,
            @NonNull DeletionCallback callback
    ) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user == null || user.getEmail() == null) {
                    postError(callback, "No authenticated cloud user found.");
                    return;
                }

                // 1. Re-authenticate with account password
                AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), accountPassword);
                Tasks.await(user.reauthenticate(credential), 10, TimeUnit.SECONDS);

                // 2. Cascade delete from Firestore
                FirebaseFirestore firestore = FirebaseFirestore.getInstance();
                DocumentReference roomRef = firestore.collection("rooms").document(roomId);

                String[] subcollections = {
                        "payments",
                        "expenses",
                        "sections",
                        "roster_students",
                        "payment_locks",
                        "audit_logs",
                        "private",
                        "members"
                };

                for (String subName : subcollections) {
                    try {
                        QuerySnapshot snapshot = Tasks.await(
                                roomRef.collection(subName).get(),
                                10,
                                TimeUnit.SECONDS
                        );
                        if (snapshot != null && !snapshot.isEmpty()) {
                            WriteBatch batch = firestore.batch();
                            int count = 0;
                            for (DocumentSnapshot doc : snapshot.getDocuments()) {
                                batch.delete(doc.getReference());
                                count++;
                                if (count % 400 == 0) {
                                    Tasks.await(batch.commit(), 10, TimeUnit.SECONDS);
                                    batch = firestore.batch();
                                }
                            }
                            if (count % 400 != 0) {
                                Tasks.await(batch.commit(), 10, TimeUnit.SECONDS);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                // Delete parent room doc
                try {
                    Tasks.await(roomRef.delete(), 10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }

                // 3. Delete local Room SQLite records
                AppDatabase db = AppDatabase.getInstance(context);
                db.paymentDao().deletePaymentsByRoomId(roomId);
                db.expenseDao().deleteExpensesByRoomId(roomId);
                db.sectionDao().deleteSectionsByRoomId(roomId);
                db.rosterStudentDao().deleteRosterByRoomId(roomId);
                db.auditLogDao().deleteLogsByRoomId(roomId);
                db.roomMemberDao().deleteMembersByRoom(roomId);
                db.roomDao().deleteRoomById(roomId);

                // 4. Session cleanup & listener detachment
                UserSessionManager session = UserSessionManager.getInstance(context);
                if (roomId.equals(session.getActiveRoomId())) {
                    session.clearActiveRoom();
                }
                FirestoreRealtimeSyncManager.getInstance(context).stopListening();

                postSuccess(callback);

            } catch (Exception e) {
                postError(callback, e.getMessage() != null ? e.getMessage() : "Failed to delete room");
            }
        });
    }

    private static void postSuccess(DeletionCallback callback) {
        new Handler(Looper.getMainLooper()).post(callback::onSuccess);
    }

    private static void postError(DeletionCallback callback, String message) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(message));
    }
}
