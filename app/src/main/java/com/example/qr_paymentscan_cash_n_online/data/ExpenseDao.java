package com.example.qr_paymentscan_cash_n_online.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ExpenseDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insertExpense(ExpenseEntity expense);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertOrReplaceExpense(ExpenseEntity expense);

    @Query("SELECT * FROM expenses WHERE section_id = :sectionId ORDER BY created_at DESC")
    LiveData<List<ExpenseEntity>> observeExpensesBySection(long sectionId);

    @Query("SELECT * FROM expenses WHERE section_id = :sectionId ORDER BY created_at ASC")
    List<ExpenseEntity> getExpensesBySectionSync(long sectionId);

    @Query("SELECT * FROM expenses WHERE section_id IN (:sectionIds) ORDER BY created_at ASC")
    List<ExpenseEntity> getExpensesBySectionIdsSync(List<Long> sectionIds);

    @Query("SELECT * FROM expenses ORDER BY created_at DESC")
    List<ExpenseEntity> getAllExpensesSync();

    @Query("SELECT * FROM expenses WHERE id = :expenseId LIMIT 1")
    ExpenseEntity findByIdSync(long expenseId);

    @Query("UPDATE expenses SET status = 'VOIDED', void_reason = :voidReason, voided_at = :voidedAt, sync_status = 'PENDING', updated_at = :voidedAt WHERE id = :expenseId")
    int voidExpenseById(long expenseId, String voidReason, long voidedAt);

    @Query("UPDATE expenses SET section_name = :newSectionName, sync_status = 'PENDING', updated_at = :updatedAt WHERE section_id = :sectionId")
    void updateSectionNameForSection(long sectionId, String newSectionName, long updatedAt);

    @Query("SELECT COALESCE(SUM(CASE WHEN status = 'ACTIVE' THEN amount ELSE 0 END), 0.0) FROM expenses WHERE section_id IN (:sectionIds)")
    double sumActiveExpensesForSectionIdsSync(List<Long> sectionIds);

    @Query("SELECT * FROM expenses WHERE sync_status = 'PENDING'")
    List<ExpenseEntity> getPendingSyncExpenses();

    @Query("UPDATE expenses SET sync_status = :syncStatus, remote_proof_url = :remoteProofUrl, updated_at = :updatedAt WHERE id = :id")
    void updateSyncState(long id, String syncStatus, String remoteProofUrl, long updatedAt);

    @Query("SELECT COUNT(*) FROM expenses WHERE room_id IS NULL")
    int countLegacyExpensesWithoutRoom();

    @Query("UPDATE expenses SET room_id = :roomId, actor_user_id = COALESCE(actor_user_id, :actorUserId), actor_name = COALESCE(actor_name, :actorName), actor_role = COALESCE(actor_role, :actorRole), sync_status = 'PENDING', updated_at = :updatedAt WHERE room_id IS NULL")
    int claimLegacyExpenses(String roomId, String actorUserId, String actorName, String actorRole, long updatedAt);

    @Query("DELETE FROM expenses WHERE section_id IN (:sectionIds)")
    void deleteExpensesBySectionIds(List<Long> sectionIds);

    @Query("DELETE FROM expenses WHERE room_id = :roomId")
    void deleteExpensesByRoomId(String roomId);

    @Query("SELECT COUNT(*) FROM expenses WHERE sync_status = 'PENDING'")
    LiveData<Integer> observePendingCount();
}
