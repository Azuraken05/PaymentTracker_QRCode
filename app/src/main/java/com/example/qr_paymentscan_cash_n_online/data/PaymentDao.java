package com.example.qr_paymentscan_cash_n_online.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PaymentDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insertPayment(PaymentEntity payment);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertOrReplacePayment(PaymentEntity payment);

    @Query("SELECT * FROM payments WHERE section_id = :sectionId ORDER BY created_at DESC")
    LiveData<List<PaymentEntity>> observePaymentsBySection(long sectionId);

    @Query("SELECT * FROM payments WHERE section_id = :sectionId ORDER BY created_at ASC")
    List<PaymentEntity> getPaymentsBySectionSync(long sectionId);

    @Query("SELECT * FROM payments WHERE section_id = :sectionId AND status != 'VOIDED' ORDER BY created_at DESC")
    List<PaymentEntity> getActivePaymentsBySectionSync(long sectionId);

    @Query("SELECT * FROM payments WHERE section_id = :sectionId AND status != 'VOIDED' AND (" +
            "(student_identity_key IS NOT NULL AND student_identity_key = :identityKey) " +
            "OR (:studentNumber != '' AND student_number IS NOT NULL AND LOWER(TRIM(student_number)) = LOWER(TRIM(:studentNumber))) " +
            "OR (LOWER(TRIM(first_name)) = :normalizedFirstName AND LOWER(TRIM(last_name)) = :normalizedLastName)" +
            ") ORDER BY created_at DESC")
    List<PaymentEntity> findActiveDuplicatesForStudentSync(
            long sectionId,
            String identityKey,
            String studentNumber,
            String normalizedFirstName,
            String normalizedLastName
    );

    @Query("UPDATE payments SET amount = :newAmount, remaining_balance = :newRemainingBalance, payment_status = :newPaymentStatus, sync_status = 'PENDING', updated_at = :updatedAt WHERE id = :paymentId")
    int applyAdditionalPaymentToPartialReceipt(
            long paymentId,
            double newAmount,
            double newRemainingBalance,
            String newPaymentStatus,
            long updatedAt
    );

    @Query("SELECT * FROM payments WHERE section_id IN (:sectionIds) ORDER BY created_at ASC")
    List<PaymentEntity> getPaymentsBySectionIdsSync(List<Long> sectionIds);

    @Query("SELECT * FROM payments ORDER BY created_at DESC")
    List<PaymentEntity> getAllPaymentsSync();

    @Query("SELECT * FROM payments WHERE receipt_id = :receiptId LIMIT 1")
    PaymentEntity findByReceiptIdSync(String receiptId);

    @Query("SELECT * FROM payments WHERE receipt_id = :receiptId LIMIT 1")
    LiveData<PaymentEntity> observeByReceiptId(String receiptId);

    @Query("UPDATE payments SET status = 'VOIDED', payment_status = 'VOIDED', void_reason = :voidReason, voided_at = :voidedAt, sync_status = 'PENDING', updated_at = :voidedAt WHERE receipt_id = :receiptId")
    int voidPaymentByReceiptId(String receiptId, String voidReason, long voidedAt);

    @Query("UPDATE payments SET section_name = :newSectionName, sync_status = 'PENDING', updated_at = :updatedAt WHERE section_id = :sectionId")
    void updateSectionNameForSection(long sectionId, String newSectionName, long updatedAt);

    @Query("SELECT COALESCE(SUM(CASE WHEN status = 'PAID' THEN amount ELSE 0 END), 0.0) FROM payments")
    LiveData<Double> observeGlobalActiveCollected();

    @Query("SELECT COALESCE(SUM(CASE WHEN status = 'PAID' THEN amount ELSE 0 END), 0.0) FROM payments WHERE section_id IN (:sectionIds)")
    double sumActivePaymentsForSectionIdsSync(List<Long> sectionIds);

    @Query("SELECT * FROM payments WHERE sync_status = 'PENDING'")
    List<PaymentEntity> getPendingSyncPayments();

    @Query("UPDATE payments SET sync_status = :syncStatus, remote_proof_url = :remoteProofUrl, updated_at = :updatedAt WHERE id = :id")
    void updateSyncState(long id, String syncStatus, String remoteProofUrl, long updatedAt);

    @Query("SELECT COUNT(*) FROM payments WHERE room_id IS NULL")
    int countLegacyPaymentsWithoutRoom();

    @Query("UPDATE payments SET room_id = :roomId, actor_user_id = COALESCE(actor_user_id, :actorUserId), actor_name = COALESCE(actor_name, :actorName), actor_role = COALESCE(actor_role, :actorRole), sync_status = 'PENDING', updated_at = :updatedAt WHERE room_id IS NULL")
    int claimLegacyPayments(String roomId, String actorUserId, String actorName, String actorRole, long updatedAt);

    @Query("DELETE FROM payments WHERE section_id IN (:sectionIds)")
    void deletePaymentsBySectionIds(List<Long> sectionIds);

    @Query("DELETE FROM payments WHERE room_id = :roomId")
    void deletePaymentsByRoomId(String roomId);

    @Query("SELECT COUNT(*) FROM payments WHERE sync_status = 'PENDING'")
    LiveData<Integer> observePendingCount();
}
