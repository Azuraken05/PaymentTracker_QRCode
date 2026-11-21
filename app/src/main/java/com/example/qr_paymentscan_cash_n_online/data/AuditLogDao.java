package com.example.qr_paymentscan_cash_n_online.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AuditLogDao {

    @Insert
    long insertAuditLog(AuditLogEntity log);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertOrReplaceAuditLog(AuditLogEntity log);

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    LiveData<List<AuditLogEntity>> observeAllLogs();

    @Query("SELECT * FROM audit_logs WHERE room_id = :roomId OR room_id IS NULL ORDER BY timestamp DESC")
    LiveData<List<AuditLogEntity>> observeLogsByRoom(String roomId);

    @Query("SELECT * FROM audit_logs WHERE section_id = :sectionId ORDER BY timestamp DESC")
    LiveData<List<AuditLogEntity>> observeLogsBySection(long sectionId);

    @Query("SELECT * FROM audit_logs WHERE section_id = :sectionId ORDER BY timestamp DESC")
    List<AuditLogEntity> getLogsBySectionSync(long sectionId);

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    List<AuditLogEntity> getAllLogsSync();

    @Query("SELECT * FROM audit_logs WHERE sync_status = 'PENDING'")
    List<AuditLogEntity> getPendingSyncAuditLogs();

    @Query("UPDATE audit_logs SET sync_status = :syncStatus, updated_at = :updatedAt WHERE id = :id")
    void updateSyncStatus(long id, String syncStatus, long updatedAt);

    @Query("UPDATE audit_logs SET section_name = :newSectionName WHERE section_id = :sectionId")
    void updateSectionNameForSection(long sectionId, String newSectionName);

    @Query("SELECT COUNT(*) FROM audit_logs WHERE room_id IS NULL")
    int countLegacyAuditLogsWithoutRoom();

    @Query("UPDATE audit_logs SET room_id = :roomId, actor_user_id = COALESCE(actor_user_id, :actorUserId), actor_name = COALESCE(actor_name, :actorName), actor_role = COALESCE(actor_role, :actorRole), sync_status = 'PENDING', updated_at = :updatedAt WHERE room_id IS NULL")
    int claimLegacyAuditLogs(String roomId, String actorUserId, String actorName, String actorRole, long updatedAt);

    @Query("DELETE FROM audit_logs WHERE room_id = :roomId")
    void deleteLogsByRoomId(String roomId);
}
