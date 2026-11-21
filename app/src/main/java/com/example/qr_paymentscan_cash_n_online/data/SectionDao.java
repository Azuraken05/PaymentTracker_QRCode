package com.example.qr_paymentscan_cash_n_online.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SectionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insertSection(SectionEntity section);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertOrReplaceSection(SectionEntity section);

    @Query("SELECT * FROM sections WHERE LOWER(section_name) = LOWER(:name) LIMIT 1")
    SectionEntity findByNameSync(String name);

    @Query("SELECT * FROM sections WHERE LOWER(section_name) = LOWER(:name) " +
            "AND (room_id = :roomId OR (:roomId IS NULL AND room_id IS NULL)) " +
            "AND ((:parentId IS NULL AND parent_folder_id IS NULL) OR parent_folder_id = :parentId) LIMIT 1")
    SectionEntity findSiblingByNameSync(String roomId, Long parentId, String name);

    @Query("SELECT * FROM sections WHERE id = :sectionId LIMIT 1")
    SectionEntity findByIdSync(long sectionId);

    @Query("SELECT * FROM sections WHERE id = :sectionId LIMIT 1")
    LiveData<SectionEntity> observeById(long sectionId);

    @Query("SELECT * FROM sections ORDER BY created_at DESC")
    List<SectionEntity> getAllSectionsSync();

    @Query("SELECT * FROM sections WHERE room_id = :roomId OR room_id IS NULL ORDER BY created_at DESC")
    List<SectionEntity> getSectionsByRoomSync(String roomId);

    @Query("SELECT * FROM sections WHERE parent_folder_id = :parentId ORDER BY created_at ASC")
    List<SectionEntity> getSubfoldersSync(long parentId);

    @Query("SELECT * FROM sections WHERE sync_status = 'PENDING'")
    List<SectionEntity> getPendingSyncSections();

    @Query("UPDATE sections SET section_name = :newName, sync_status = 'PENDING', updated_at = :updatedAt WHERE id = :sectionId")
    void renameSection(long sectionId, String newName, long updatedAt);

    @Query("UPDATE sections SET sync_status = :syncStatus, updated_at = :updatedAt WHERE id = :sectionId")
    void updateSyncStatus(long sectionId, String syncStatus, long updatedAt);

    @Query("SELECT COUNT(*) FROM sections WHERE room_id IS NULL")
    int countLegacySectionsWithoutRoom();

    @Query("UPDATE sections SET room_id = :roomId, sync_status = 'PENDING', updated_at = :updatedAt WHERE room_id IS NULL")
    int claimLegacySections(String roomId, long updatedAt);

    @Query("DELETE FROM sections WHERE id IN (:sectionIds)")
    void deleteSectionsByIds(List<Long> sectionIds);

    @Query("DELETE FROM sections WHERE room_id = :roomId")
    void deleteSectionsByRoomId(String roomId);

    @Query("SELECT s.id, s.section_name, s.description, s.created_at, s.parent_folder_id, s.sync_status, s.room_id, " +
            "(SELECT COUNT(*) FROM sections child WHERE child.parent_folder_id = s.id) AS subfolder_count, " +
            "COALESCE(SUM(CASE WHEN p.status = 'PAID' THEN p.amount ELSE 0 END), 0.0) AS total_collected, " +
            "COALESCE(SUM(CASE WHEN p.status = 'PAID' THEN 1 ELSE 0 END), 0) AS valid_count, " +
            "COALESCE(SUM(CASE WHEN p.status = 'VOIDED' THEN 1 ELSE 0 END), 0) AS voided_count " +
            "FROM sections s " +
            "LEFT JOIN payments p ON s.id = p.section_id " +
            "WHERE s.parent_folder_id IS NULL AND (s.room_id = :roomId OR s.room_id IS NULL) " +
            "GROUP BY s.id " +
            "ORDER BY s.created_at DESC")
    LiveData<List<SectionSummary>> observeRootSectionSummariesByRoom(String roomId);

    @Query("SELECT s.id, s.section_name, s.description, s.created_at, s.parent_folder_id, s.sync_status, s.room_id, " +
            "(SELECT COUNT(*) FROM sections child WHERE child.parent_folder_id = s.id) AS subfolder_count, " +
            "COALESCE(SUM(CASE WHEN p.status = 'PAID' THEN p.amount ELSE 0 END), 0.0) AS total_collected, " +
            "COALESCE(SUM(CASE WHEN p.status = 'PAID' THEN 1 ELSE 0 END), 0) AS valid_count, " +
            "COALESCE(SUM(CASE WHEN p.status = 'VOIDED' THEN 1 ELSE 0 END), 0) AS voided_count " +
            "FROM sections s " +
            "LEFT JOIN payments p ON s.id = p.section_id " +
            "WHERE s.parent_folder_id IS NULL " +
            "GROUP BY s.id " +
            "ORDER BY s.created_at DESC")
    LiveData<List<SectionSummary>> observeRootSectionSummaries();

    @Query("SELECT s.id, s.section_name, s.description, s.created_at, s.parent_folder_id, s.sync_status, s.room_id, " +
            "(SELECT COUNT(*) FROM sections child WHERE child.parent_folder_id = s.id) AS subfolder_count, " +
            "COALESCE(SUM(CASE WHEN p.status = 'PAID' THEN p.amount ELSE 0 END), 0.0) AS total_collected, " +
            "COALESCE(SUM(CASE WHEN p.status = 'PAID' THEN 1 ELSE 0 END), 0) AS valid_count, " +
            "COALESCE(SUM(CASE WHEN p.status = 'VOIDED' THEN 1 ELSE 0 END), 0) AS voided_count " +
            "FROM sections s " +
            "LEFT JOIN payments p ON s.id = p.section_id " +
            "WHERE s.parent_folder_id = :parentId " +
            "GROUP BY s.id " +
            "ORDER BY s.created_at ASC")
    LiveData<List<SectionSummary>> observeSubfolderSummaries(long parentId);

    @Query("SELECT COUNT(*) FROM sections WHERE sync_status = 'PENDING'")
    LiveData<Integer> observePendingCount();
}
