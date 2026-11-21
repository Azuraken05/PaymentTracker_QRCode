package com.example.qr_paymentscan_cash_n_online.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RosterStudentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertOrReplaceRosterStudent(RosterStudentEntity student);

    @Query("SELECT * FROM roster_students WHERE section_id = :sectionId ORDER BY last_name ASC")
    List<RosterStudentEntity> getRosterBySectionSync(long sectionId);

    @Query("SELECT * FROM roster_students WHERE sync_status = 'PENDING'")
    List<RosterStudentEntity> getPendingSyncRosterStudents();

    @Query("UPDATE roster_students SET sync_status = :syncStatus, updated_at = :updatedAt WHERE id = :id")
    void updateSyncStatus(long id, String syncStatus, long updatedAt);

    @Query("UPDATE roster_students SET room_id = :roomId, sync_status = 'PENDING', updated_at = :updatedAt WHERE room_id IS NULL")
    int claimLegacyRosterStudents(String roomId, long updatedAt);

    @Query("DELETE FROM roster_students WHERE section_id IN (:sectionIds)")
    void deleteRosterBySectionIds(List<Long> sectionIds);

    @Query("DELETE FROM roster_students WHERE room_id = :roomId")
    void deleteRosterByRoomId(String roomId);

    @Query("DELETE FROM roster_students WHERE id = :studentId")
    void deleteRosterStudentById(long studentId);

    @Query("SELECT * FROM roster_students WHERE id = :studentId LIMIT 1")
    RosterStudentEntity findByIdSync(long studentId);

    @Query("SELECT * FROM roster_students WHERE room_id = :roomId AND sync_status = 'SYNCED'")
    List<RosterStudentEntity> getSyncedRosterByRoomSync(String roomId);
}
