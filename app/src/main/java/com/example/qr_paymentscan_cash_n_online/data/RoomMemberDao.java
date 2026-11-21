package com.example.qr_paymentscan_cash_n_online.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RoomMemberDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplaceMember(RoomMemberEntity member);

    @Query("SELECT * FROM room_members WHERE room_id = :roomId ORDER BY " +
            "CASE status WHEN 'PENDING_APPROVAL' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, " +
            "CASE role WHEN 'OWNER' THEN 0 WHEN 'COLLECTOR' THEN 1 ELSE 2 END, joined_at ASC")
    LiveData<List<RoomMemberEntity>> observeMembersByRoom(String roomId);

    @Query("SELECT * FROM room_members WHERE room_id = :roomId ORDER BY " +
            "CASE status WHEN 'PENDING_APPROVAL' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, " +
            "CASE role WHEN 'OWNER' THEN 0 WHEN 'COLLECTOR' THEN 1 ELSE 2 END, joined_at ASC")
    List<RoomMemberEntity> getMembersByRoomSync(String roomId);

    @Query("SELECT * FROM room_members WHERE room_id = :roomId AND status = 'PENDING_APPROVAL' ORDER BY joined_at ASC")
    LiveData<List<RoomMemberEntity>> observePendingMembersByRoom(String roomId);

    @Query("SELECT * FROM room_members WHERE room_id = :roomId AND user_id = :userId LIMIT 1")
    RoomMemberEntity findMemberSync(String roomId, String userId);

    @Query("SELECT * FROM room_members WHERE room_id = :roomId AND user_id = :userId LIMIT 1")
    LiveData<RoomMemberEntity> observeMember(String roomId, String userId);

    @Query("UPDATE room_members SET role = :newRole WHERE room_id = :roomId AND user_id = :userId")
    void updateMemberRole(String roomId, String userId, String newRole);

    @Query("UPDATE room_members SET status = :newStatus, role = :newRole WHERE room_id = :roomId AND user_id = :userId")
    void updateMemberStatusAndRole(String roomId, String userId, String newStatus, String newRole);

    @Query("UPDATE room_members SET status = :newStatus WHERE room_id = :roomId AND user_id = :userId")
    void updateMemberStatus(String roomId, String userId, String newStatus);

    @Query("UPDATE room_members SET user_name = :newName WHERE user_id = :userId")
    void updateMemberDisplayName(String userId, String newName);

    @Query("SELECT * FROM room_members WHERE user_id = :userId")
    List<RoomMemberEntity> getMembersByUserSync(String userId);

    @Query("SELECT * FROM room_members WHERE user_id = :userId")
    List<RoomMemberEntity> getMembershipsForUser(String userId);

    @Query("SELECT * FROM room_members WHERE user_id = :userId")
    LiveData<List<RoomMemberEntity>> observeMembershipsForUser(String userId);

    @Query("DELETE FROM room_members WHERE room_id = :roomId AND user_id = :userId")
    void deleteMember(String roomId, String userId);

    @Query("DELETE FROM room_members WHERE room_id = :roomId")
    void deleteMembersByRoom(String roomId);
}
