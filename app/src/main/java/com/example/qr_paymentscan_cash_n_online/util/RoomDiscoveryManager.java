package com.example.qr_paymentscan_cash_n_online.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.example.qr_paymentscan_cash_n_online.MainActivity;
import com.example.qr_paymentscan_cash_n_online.RoomLobbyActivity;
import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.data.RoomMemberEntity;
import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;
import com.example.qr_paymentscan_cash_n_online.ui.LoadingOverlayController;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RoomDiscoveryManager {

    public interface DiscoveryCallback {
        void onDiscovered(List<RoomEntity> activeRooms, List<RoomEntity> allRooms);
    }

    /**
     * Queries Firestore for all owned rooms and collectionGroup members for current user,
     * hydrates local SQLite with discovered rooms/members, purges stale local rooms, and returns
     * the discovered rooms.
     */
    public static void discoverRooms(Context context, DiscoveryCallback callback) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(context);
            UserSessionManager session = UserSessionManager.getInstance(context);
            session.syncFromFirebaseAuthIfAvailable();

            FirebaseUser fbUser = FirebaseAuth.getInstance().getCurrentUser();
            String uid = fbUser != null ? fbUser.getUid() : session.getCurrentUserId();

            if (uid == null || uid.isEmpty() || !FirebaseSyncManager.isFirebaseConfigured(context)
                    || !FirebaseSyncManager.isNetworkConnected(context)) {
                // Offline fallback
                List<RoomEntity> localRooms = db.roomDao().getAllRoomsSync();
                List<RoomEntity> activeRooms = db.roomDao().getActiveRoomsForUserSync(uid != null ? uid : "");
                notifyCallback(callback, activeRooms, localRooms);
                return;
            }

            FirebaseFirestore firestore = FirebaseFirestore.getInstance();
            Map<String, RoomEntity> discoveredRooms = new HashMap<>();
            Map<String, RoomMemberEntity> discoveredMembers = new HashMap<>();
            Set<String> discoveredRoomIds = new HashSet<>();

            try {
                // 1. Query A: Rooms owned by user
                Task<QuerySnapshot> ownedTask = firestore.collection("rooms")
                        .whereEqualTo("ownerUserId", uid)
                        .get(Source.SERVER);

                // 2. Query B: Joined & Pending Rooms via collectionGroup("members")
                Task<QuerySnapshot> joinedTask = firestore.collectionGroup("members")
                        .whereEqualTo("userId", uid)
                        .get(Source.SERVER);

                Tasks.await(Tasks.whenAllComplete(ownedTask, joinedTask), 15, TimeUnit.SECONDS);

                // Process Query A (Owned)
                if (ownedTask.isSuccessful() && ownedTask.getResult() != null) {
                    for (DocumentSnapshot doc : ownedTask.getResult().getDocuments()) {
                        RoomEntity room = parseRoomEntity(doc);
                        if (room != null) {
                            discoveredRooms.put(room.roomId, room);
                            discoveredRoomIds.add(room.roomId);

                            // Ensure owner member entity is present
                            RoomMemberEntity ownerMember = new RoomMemberEntity(
                                    room.roomId,
                                    uid,
                                    session.getCurrentUserName(),
                                    session.getCurrentUserEmail(),
                                    RoomMemberEntity.ROLE_OWNER,
                                    room.createdAt,
                                    RoomMemberEntity.STATUS_ACTIVE,
                                    null
                            );
                            discoveredMembers.put(room.roomId, ownerMember);
                        }
                    }
                }

                // Process Query B (Joined)
                if (joinedTask.isSuccessful() && joinedTask.getResult() != null) {
                    List<Task<DocumentSnapshot>> roomFetchTasks = new ArrayList<>();
                    for (DocumentSnapshot memberDoc : joinedTask.getResult().getDocuments()) {
                        String status = memberDoc.getString("status");
                        if (RoomMemberEntity.STATUS_BANNED.equalsIgnoreCase(status)) {
                            continue; // Skip banned rooms
                        }
                        String rId = memberDoc.getString("roomId");
                        if (rId == null || rId.isEmpty()) {
                            try {
                                rId = memberDoc.getReference().getParent().getParent().getId();
                            } catch (Exception ignored) {
                            }
                        }
                        if (rId == null || rId.isEmpty()) {
                            continue;
                        }

                        discoveredRoomIds.add(rId);

                        String role = safeStr(memberDoc.getString("role"), RoomMemberEntity.ROLE_COLLECTOR);
                        String memberName = safeStr(memberDoc.getString("userName"), session.getCurrentUserName());
                        String memberEmail = safeStr(memberDoc.getString("userEmail"), session.getCurrentUserEmail());
                        Long joinedAtVal = memberDoc.getLong("joinedAt");
                        long joinedAt = joinedAtVal != null ? joinedAtVal : System.currentTimeMillis();
                        String deviceInfo = memberDoc.getString("deviceInfo");

                        RoomMemberEntity member = new RoomMemberEntity(
                                rId,
                                uid,
                                memberName,
                                memberEmail,
                                role,
                                joinedAt,
                                status != null ? status : RoomMemberEntity.STATUS_ACTIVE,
                                deviceInfo
                        );
                        discoveredMembers.put(rId, member);

                        if (!discoveredRooms.containsKey(rId)) {
                            Task<DocumentSnapshot> fetchRoomTask = firestore.collection("rooms")
                                    .document(rId)
                                    .get(Source.SERVER);
                            roomFetchTasks.add(fetchRoomTask);
                        }
                    }

                    if (!roomFetchTasks.isEmpty()) {
                        try {
                            Tasks.await(Tasks.whenAllComplete(roomFetchTasks), 15, TimeUnit.SECONDS);
                            for (Task<DocumentSnapshot> task : roomFetchTasks) {
                                if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                                    RoomEntity room = parseRoomEntity(task.getResult());
                                    if (room != null) {
                                        discoveredRooms.put(room.roomId, room);
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }

                // 3. Hydrate Local SQLite & Clean Stale Entries
                for (RoomEntity room : discoveredRooms.values()) {
                    db.roomDao().insertOrReplaceRoom(room);
                }
                for (RoomMemberEntity member : discoveredMembers.values()) {
                    db.roomMemberDao().insertOrReplaceMember(member);
                }

                // Remove local rooms that no longer exist or user was removed from
                if (!discoveredRoomIds.isEmpty()) {
                    List<RoomEntity> localRooms = db.roomDao().getAllRoomsSync();
                    for (RoomEntity local : localRooms) {
                        if (!discoveredRoomIds.contains(local.roomId)) {
                            db.roomDao().deleteRoomById(local.roomId);
                            db.roomMemberDao().deleteMembersByRoom(local.roomId);
                        }
                    }
                }

                List<RoomEntity> allDiscovered = new ArrayList<>(discoveredRooms.values());
                List<RoomEntity> activeDiscovered = new ArrayList<>();
                for (RoomEntity room : allDiscovered) {
                    RoomMemberEntity m = discoveredMembers.get(room.roomId);
                    if (m != null && m.isActive()) {
                        activeDiscovered.add(room);
                    }
                }

                notifyCallback(callback, activeDiscovered, allDiscovered);

            } catch (Exception e) {
                // On server failure, read local Room SQLite
                List<RoomEntity> localRooms = db.roomDao().getAllRoomsSync();
                List<RoomEntity> activeRooms = db.roomDao().getActiveRoomsForUserSync(uid);
                notifyCallback(callback, activeRooms, localRooms);
            }
        });
    }

    /**
     * Discovers rooms with animated overlay and routes user:
     * - If exactly 1 ACTIVE room (or last_active_room_id is active): automatically opens MainActivity.
     * - Else: opens RoomLobbyActivity.
     */
    public static void discoverAndRouteUser(Activity activity, LoadingOverlayController overlay, boolean fromLogin) {
        if (overlay != null) {
            overlay.show("Finding Your Workspaces...", "Restoring owned and joined rooms from cloud");
        }

        discoverRooms(activity, (activeRooms, allRooms) -> {
            if (overlay != null) {
                overlay.hide();
            }
            UserSessionManager session = UserSessionManager.getInstance(activity);
            String lastActive = session.getActiveRoomId();

            RoomEntity autoOpenRoom = null;
            if (lastActive != null) {
                for (RoomEntity r : activeRooms) {
                    if (r.roomId.equals(lastActive)) {
                        autoOpenRoom = r;
                        break;
                    }
                }
            }

            if (autoOpenRoom == null && activeRooms.size() == 1) {
                autoOpenRoom = activeRooms.get(0);
            }

            if (fromLogin && autoOpenRoom != null) {
                // Auto-open workspace directly
                final RoomEntity target = autoOpenRoom;
                AppDatabase.DB_EXECUTOR.execute(() -> {
                    AppDatabase db = AppDatabase.getInstance(activity);
                    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                    String uid = user != null ? user.getUid() : session.getCurrentUserId();
                    RoomMemberEntity member = db.roomMemberDao().findMemberSync(target.roomId, uid);
                    String role = member != null ? member.role : (uid.equals(target.ownerUserId) ? RoomMemberEntity.ROLE_OWNER : RoomMemberEntity.ROLE_COLLECTOR);
                    String status = member != null ? member.status : RoomMemberEntity.STATUS_ACTIVE;

                    session.setActiveRoom(target, role, status);

                    activity.runOnUiThread(() -> {
                        Intent intent = new Intent(activity, MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        activity.startActivity(intent);
                        activity.finish();
                    });
                });
            } else {
                // Open RoomLobbyActivity
                Intent intent = new Intent(activity, RoomLobbyActivity.class);
                intent.putExtra(RoomLobbyActivity.EXTRA_FROM_LOGIN, fromLogin);
                activity.startActivity(intent);
                activity.finish();
            }
        });
    }

    private static void notifyCallback(DiscoveryCallback callback, List<RoomEntity> activeRooms, List<RoomEntity> allRooms) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onDiscovered(activeRooms, allRooms));
    }

    private static RoomEntity parseRoomEntity(DocumentSnapshot doc) {
        try {
            String roomId = doc.getString("roomId");
            if (roomId == null || roomId.isEmpty()) {
                roomId = doc.getId();
            }
            String roomName = safeStr(doc.getString("roomName"), "Workspace");
            String roomKey = safeStr(doc.getString("roomKey"), "KEY-000000");
            String passHash = safeStr(doc.getString("roomPasswordHash"), "");
            String secret = safeStr(doc.getString("roomSigningSecret"), "");
            String ownerId = safeStr(doc.getString("ownerUserId"), "");
            String ownerName = safeStr(doc.getString("ownerName"), "Room Owner");
            Long created = doc.getLong("createdAt");
            Long updated = doc.getLong("updatedAt");
            String salt = doc.getString("roomSalt");
            Boolean isLocked = doc.getBoolean("isLocked");
            Boolean reqApproval = doc.getBoolean("requireApproval");
            Long expiresAt = doc.getLong("keyExpiresAt");

            return new RoomEntity(
                    roomId,
                    roomName,
                    roomKey,
                    passHash,
                    secret,
                    ownerId,
                    ownerName,
                    created != null ? created : System.currentTimeMillis(),
                    updated != null ? updated : System.currentTimeMillis(),
                    salt,
                    Boolean.TRUE.equals(isLocked),
                    reqApproval == null || reqApproval,
                    expiresAt != null ? expiresAt : 0L,
                    0,
                    0L
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeStr(String val, String def) {
        return (val != null && !val.trim().isEmpty()) ? val.trim() : def;
    }
}
