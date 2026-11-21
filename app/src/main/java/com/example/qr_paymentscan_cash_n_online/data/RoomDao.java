package com.example.qr_paymentscan_cash_n_online.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RoomDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplaceRoom(RoomEntity room);

    @Query("SELECT * FROM rooms WHERE room_id = :roomId LIMIT 1")
    RoomEntity findByIdSync(String roomId);

    @Query("SELECT * FROM rooms WHERE room_id = :roomId LIMIT 1")
    LiveData<RoomEntity> observeById(String roomId);

    @Query("SELECT * FROM rooms WHERE UPPER(room_key) = UPPER(:roomKey) LIMIT 1")
    RoomEntity findByKeySync(String roomKey);

    @Query("SELECT * FROM rooms ORDER BY updated_at DESC")
    List<RoomEntity> getAllRoomsSync();

    @Query("SELECT * FROM rooms ORDER BY updated_at DESC")
    LiveData<List<RoomEntity>> observeAllRooms();

    @Query("SELECT r.* FROM rooms r INNER JOIN room_members m ON r.room_id = m.room_id WHERE m.user_id = :userId AND m.status = 'ACTIVE' ORDER BY r.updated_at DESC")
    List<RoomEntity> getActiveRoomsForUserSync(String userId);

    @Query("SELECT room_signing_secret FROM rooms")
    List<String> getAllRoomSigningSecretsSync();

    @Query("UPDATE rooms SET room_password_hash = :newPasswordHash, updated_at = :updatedAt WHERE room_id = :roomId")
    void updateRoomPasswordHash(String roomId, String newPasswordHash, long updatedAt);

    @Query("UPDATE rooms SET room_password_hash = :newPasswordHash, room_salt = :newSalt, updated_at = :updatedAt WHERE room_id = :roomId")
    void updateRoomPasswordHashAndSalt(String roomId, String newPasswordHash, String newSalt, long updatedAt);

    @Query("UPDATE rooms SET is_locked = :isLocked, require_approval = :requireApproval, key_expires_at = :keyExpiresAt, updated_at = :updatedAt WHERE room_id = :roomId")
    void updateRoomSecuritySettings(String roomId, boolean isLocked, boolean requireApproval, long keyExpiresAt, long updatedAt);

    @Query("UPDATE rooms SET room_name = :roomName, organization_subtitle = :organizationSubtitle, receipt_prefix = :receiptPrefix, receipt_footer_note = :receiptFooterNote, enabled_payment_modes = :enabledPaymentModes, require_proof_photo_for_online = :requireProofPhoto, default_join_role = :defaultRole, is_archived = :isArchived, updated_at = :updatedAt WHERE room_id = :roomId")
    void updateRoomSettings(
            String roomId,
            String roomName,
            String organizationSubtitle,
            String receiptPrefix,
            String receiptFooterNote,
            String enabledPaymentModes,
            boolean requireProofPhoto,
            String defaultRole,
            boolean isArchived,
            long updatedAt
    );

    @Query("UPDATE rooms SET is_archived = :isArchived, updated_at = :updatedAt WHERE room_id = :roomId")
    void updateRoomArchivedStatus(String roomId, boolean isArchived, long updatedAt);

    @Query("UPDATE rooms SET room_key = :newRoomKey, room_password_hash = :newPasswordHash, room_salt = :newSalt, key_expires_at = :keyExpiresAt, failed_join_count = 0, lockout_until = 0, updated_at = :updatedAt WHERE room_id = :roomId")
    void rotateRoomCredentials(String roomId, String newRoomKey, String newPasswordHash, String newSalt, long keyExpiresAt, long updatedAt);

    @Query("UPDATE rooms SET room_signing_secret = :newSigningSecret, updated_at = :updatedAt WHERE room_id = :roomId")
    void updateRoomSigningSecret(String roomId, String newSigningSecret, long updatedAt);

    @Query("UPDATE rooms SET owner_name = :newName WHERE owner_user_id = :userId")
    void updateOwnerNameByUser(String userId, String newName);

    @Query("UPDATE rooms SET failed_join_count = :failedCount, lockout_until = :lockoutUntil, updated_at = :updatedAt WHERE room_id = :roomId")
    void updateBruteForceState(String roomId, int failedCount, long lockoutUntil, long updatedAt);

    @Query("DELETE FROM rooms WHERE room_id = :roomId")
    void deleteRoomById(String roomId);
}
