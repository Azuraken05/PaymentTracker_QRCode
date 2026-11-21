package com.example.qr_paymentscan_cash_n_online.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.data.RoomMemberEntity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.UUID;

/**
 * Manages the signed-in user's identity (Firebase Auth + offline-first SharedPreferences cache),
 * active Private Room context, role permissions (OWNER, COLLECTOR, AUDITOR), member approval status
 * (ACTIVE, PENDING_APPROVAL, BANNED), and instant session revocation when kicked/banned.
 */
public final class UserSessionManager {

    private static final String PREFS_NAME = "user_room_session_prefs";

    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_NAME = "user_display_name";
    private static final String KEY_USER_EMAIL = "user_email";
    private static final String KEY_IS_SIGNED_IN = "is_signed_in";

    private static final String KEY_ACTIVE_ROOM_ID = "active_room_id";
    private static final String KEY_ACTIVE_ROOM_NAME = "active_room_name";
    private static final String KEY_ACTIVE_ROOM_KEY = "active_room_key";
    private static final String KEY_ACTIVE_ROOM_ROLE = "active_room_role";
    private static final String KEY_ACTIVE_ROOM_STATUS = "active_room_member_status";
    private static final String KEY_ACTIVE_ROOM_SECRET = "active_room_signing_secret";

    private static volatile UserSessionManager INSTANCE;
    private final SharedPreferences prefs;

    private UserSessionManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        syncFromFirebaseAuthIfAvailable();
    }

    public static UserSessionManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (UserSessionManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new UserSessionManager(context);
                }
            }
        }
        return INSTANCE;
    }

    public synchronized void syncFromFirebaseAuthIfAvailable() {
        try {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null) {
                String uid = currentUser.getUid();
                String email = currentUser.getEmail() != null ? currentUser.getEmail() : "";
                String displayName = currentUser.getDisplayName();
                String cachedName = prefs.getString(KEY_USER_NAME, null);
                String resolvedName;
                if (displayName != null && !displayName.trim().isEmpty()) {
                    resolvedName = displayName.trim();
                } else if (cachedName != null && !cachedName.trim().isEmpty()) {
                    resolvedName = cachedName.trim();
                } else if (!email.isEmpty() && email.contains("@")) {
                    resolvedName = email.substring(0, email.indexOf('@'));
                } else {
                    resolvedName = "Treasurer";
                }
                prefs.edit()
                        .putString(KEY_USER_ID, uid)
                        .putString(KEY_USER_NAME, resolvedName)
                        .putString(KEY_USER_EMAIL, email)
                        .putBoolean(KEY_IS_SIGNED_IN, true)
                        .apply();
            }
        } catch (Exception ignored) {
            // FirebaseAuth may not be initialized in unit tests or offline boot
        }
    }

    public synchronized boolean isSignedIn() {
        syncFromFirebaseAuthIfAvailable();
        return prefs.getBoolean(KEY_IS_SIGNED_IN, false)
                && prefs.getString(KEY_USER_ID, null) != null;
    }

    public synchronized void setSignedInUser(String userId, String displayName, String email) {
        String safeId = (userId != null && !userId.trim().isEmpty())
                ? userId.trim()
                : "local-" + UUID.randomUUID().toString().substring(0, 8);
        String safeName = (displayName != null && !displayName.trim().isEmpty())
                ? displayName.trim()
                : "Treasurer";
        String safeEmail = email != null ? email.trim() : "";
        prefs.edit()
                .putString(KEY_USER_ID, safeId)
                .putString(KEY_USER_NAME, safeName)
                .putString(KEY_USER_EMAIL, safeEmail)
                .putBoolean(KEY_IS_SIGNED_IN, true)
                .apply();
    }

    public synchronized void ensureLocalFallbackUserIfNeeded() {
        if (!isSignedIn()) {
            String existingId = prefs.getString(KEY_USER_ID, null);
            if (existingId == null || existingId.trim().isEmpty()) {
                existingId = "local-" + UUID.randomUUID().toString().substring(0, 8);
            }
            String existingName = prefs.getString(KEY_USER_NAME, "Class Treasurer");
            if (existingName == null || existingName.trim().isEmpty()) {
                existingName = "Class Treasurer";
            }
            prefs.edit()
                    .putString(KEY_USER_ID, existingId)
                    .putString(KEY_USER_NAME, existingName)
                    .putString(KEY_USER_EMAIL, prefs.getString(KEY_USER_EMAIL, "offline@local.workspace"))
                    .apply();
        }
    }

    public synchronized String getCurrentUserId() {
        String id = prefs.getString(KEY_USER_ID, null);
        if (id == null || id.trim().isEmpty()) {
            ensureLocalFallbackUserIfNeeded();
            id = prefs.getString(KEY_USER_ID, "local-owner");
        }
        return id;
    }

    public synchronized String getCurrentUserName() {
        String name = prefs.getString(KEY_USER_NAME, null);
        if (name == null || name.trim().isEmpty()) {
            return "Class Treasurer";
        }
        return name.trim();
    }

    public synchronized String getCurrentUserEmail() {
        String email = prefs.getString(KEY_USER_EMAIL, "");
        return email != null ? email : "";
    }

    public synchronized void updateDisplayName(String newDisplayName) {
        if (newDisplayName != null && !newDisplayName.trim().isEmpty()) {
            prefs.edit().putString(KEY_USER_NAME, newDisplayName.trim()).apply();
        }
    }

    public synchronized void setActiveRoom(RoomEntity room, String role) {
        setActiveRoom(room, role, RoomMemberEntity.STATUS_ACTIVE);
    }

    public synchronized void setActiveRoom(RoomEntity room, String role, String memberStatus) {
        if (room == null) {
            return;
        }
        String resolvedRole = (role != null && !role.trim().isEmpty())
                ? role.trim().toUpperCase()
                : RoomMemberEntity.ROLE_COLLECTOR;
        String resolvedStatus = (memberStatus != null && !memberStatus.trim().isEmpty())
                ? memberStatus.trim().toUpperCase()
                : RoomMemberEntity.STATUS_ACTIVE;
        boolean isActive = RoomMemberEntity.STATUS_ACTIVE.equalsIgnoreCase(resolvedStatus);
        prefs.edit()
                .putString(KEY_ACTIVE_ROOM_ID, room.roomId)
                .putString(KEY_ACTIVE_ROOM_NAME, room.roomName)
                .putString(KEY_ACTIVE_ROOM_KEY, room.roomKey)
                .putString(KEY_ACTIVE_ROOM_ROLE, resolvedRole)
                .putString(KEY_ACTIVE_ROOM_STATUS, resolvedStatus)
                .putString(KEY_ACTIVE_ROOM_SECRET, isActive ? room.roomSigningSecret : null)
                .apply();
    }

    public synchronized void updateActiveRoomName(String roomName) {
        if (roomName != null && !roomName.trim().isEmpty()) {
            prefs.edit().putString(KEY_ACTIVE_ROOM_NAME, roomName.trim()).apply();
        }
    }

    public synchronized void updateActiveRoomRole(String role) {
        if (role != null && !role.trim().isEmpty()) {
            prefs.edit().putString(KEY_ACTIVE_ROOM_ROLE, role.trim().toUpperCase()).apply();
        }
    }

    public synchronized void updateActiveRoomMemberStatus(String status, String signingSecretIfActive) {
        if (status == null || status.trim().isEmpty()) {
            return;
        }
        String cleanStatus = status.trim().toUpperCase();
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_ACTIVE_ROOM_STATUS, cleanStatus);
        if (!RoomMemberEntity.STATUS_ACTIVE.equalsIgnoreCase(cleanStatus)) {
            editor.remove(KEY_ACTIVE_ROOM_SECRET);
        } else if (signingSecretIfActive != null && !signingSecretIfActive.isEmpty()) {
            editor.putString(KEY_ACTIVE_ROOM_SECRET, signingSecretIfActive);
        }
        editor.apply();
    }

    public synchronized void updateActiveRoomSigningSecret(String newSigningSecret) {
        if (isActiveMember() && newSigningSecret != null && !newSigningSecret.trim().isEmpty()) {
            prefs.edit().putString(KEY_ACTIVE_ROOM_SECRET, newSigningSecret.trim()).apply();
        }
    }

    /**
     * Immediately wipes the active room session and cached cryptographic signing secret
     * when the member is removed or banned by the Room Owner.
     */
    public synchronized void revokeActiveRoomAccess() {
        clearActiveRoom();
    }

    public synchronized void clearActiveRoom() {
        prefs.edit()
                .remove(KEY_ACTIVE_ROOM_ID)
                .remove(KEY_ACTIVE_ROOM_NAME)
                .remove(KEY_ACTIVE_ROOM_KEY)
                .remove(KEY_ACTIVE_ROOM_ROLE)
                .remove(KEY_ACTIVE_ROOM_STATUS)
                .remove(KEY_ACTIVE_ROOM_SECRET)
                .apply();
    }

    public synchronized String getActiveRoomId() {
        return prefs.getString(KEY_ACTIVE_ROOM_ID, null);
    }

    public synchronized String getActiveRoomName() {
        String name = prefs.getString(KEY_ACTIVE_ROOM_NAME, null);
        return (name != null && !name.trim().isEmpty()) ? name : "Personal Workspace";
    }

    public synchronized String getActiveRoomKey() {
        String key = prefs.getString(KEY_ACTIVE_ROOM_KEY, null);
        return key != null ? key : "";
    }

    public synchronized String getActiveRoomRole() {
        String role = prefs.getString(KEY_ACTIVE_ROOM_ROLE, RoomMemberEntity.ROLE_OWNER);
        return (role != null && !role.trim().isEmpty()) ? role : RoomMemberEntity.ROLE_OWNER;
    }

    public synchronized String getActiveRoomMemberStatus() {
        String status = prefs.getString(KEY_ACTIVE_ROOM_STATUS, RoomMemberEntity.STATUS_ACTIVE);
        return (status != null && !status.trim().isEmpty()) ? status : RoomMemberEntity.STATUS_ACTIVE;
    }

    public synchronized boolean isActiveMember() {
        return RoomMemberEntity.STATUS_ACTIVE.equalsIgnoreCase(getActiveRoomMemberStatus());
    }

    public synchronized boolean isPendingApproval() {
        return RoomMemberEntity.STATUS_PENDING_APPROVAL.equalsIgnoreCase(getActiveRoomMemberStatus());
    }

    public synchronized String getActiveRoomSigningSecret() {
        if (!isActiveMember()) {
            return null;
        }
        return prefs.getString(KEY_ACTIVE_ROOM_SECRET, null);
    }

    public synchronized boolean isOwner() {
        return isActiveMember() && RoomMemberEntity.ROLE_OWNER.equalsIgnoreCase(getActiveRoomRole());
    }

    public synchronized boolean isCollector() {
        return isActiveMember() && RoomMemberEntity.ROLE_COLLECTOR.equalsIgnoreCase(getActiveRoomRole());
    }

    public synchronized boolean isAuditor() {
        return isActiveMember() && RoomMemberEntity.ROLE_AUDITOR.equalsIgnoreCase(getActiveRoomRole());
    }

    /**
     * Returns true if the current user is an ACTIVE member and has OWNER or COLLECTOR role.
     */
    public synchronized boolean canCreateOrEditRecords() {
        if (!isActiveMember()) {
            return false;
        }
        String role = getActiveRoomRole();
        return RoomMemberEntity.ROLE_OWNER.equalsIgnoreCase(role)
                || RoomMemberEntity.ROLE_COLLECTOR.equalsIgnoreCase(role);
    }

    /**
     * Returns true if the current user can void receipts/expenses or edit records.
     * Allowed for both ACTIVE OWNER and COLLECTOR (AUDITOR remains read-only).
     */
    public synchronized boolean canVoidOrEditRecords() {
        return canCreateOrEditRecords();
    }

    /**
     * Returns true if the current user can void receipts/expenses, delete folders, or manage members.
     * Voiding is also permitted for collectors via canVoidOrEditRecords(), while folder deletion
     * and member management remain restricted strictly to an ACTIVE OWNER.
     */
    public synchronized boolean canVoidOrDeleteOrManageMembers() {
        return isOwner();
    }

    public synchronized void signOut() {
        try {
            FirebaseAuth.getInstance().signOut();
        } catch (Exception ignored) {
        }
        prefs.edit().putBoolean(KEY_IS_SIGNED_IN, false).apply();
    }
}
