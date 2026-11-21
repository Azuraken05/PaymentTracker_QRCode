package com.example.qr_paymentscan_cash_n_online.util;

import android.content.Context;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.PaymentDao;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.SectionEntity;
import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Multi-Cashier Duplicate Payment Prevention System:
 * 1. Student Identity Normalization (Section + Student Number + Normalized Name + Variable Fee Purpose).
 * 2. Pre-Submission Duplicate Check (Local Room + Live Firestore Source.SERVER query).
 * 3. Simultaneous Tap Protection (Atomic Firestore Transaction Lock on rooms/{roomId}/payment_locks/{lockKey}).
 * 4. Lock Release on Payment Void.
 */
public final class DuplicatePaymentGuard {

    private static final String COLLECTION_ROOMS = "rooms";
    private static final String SUBCOLLECTION_PAYMENTS = "payments";
    private static final String SUBCOLLECTION_PAYMENT_LOCKS = "payment_locks";

    private DuplicatePaymentGuard() {
    }

    public static final class DuplicateCheckResult {
        public final boolean hasDuplicate;
        public final boolean isPartialPayment;
        public final PaymentEntity existingPayment;

        private DuplicateCheckResult(boolean hasDuplicate, boolean isPartialPayment, PaymentEntity existingPayment) {
            this.hasDuplicate = hasDuplicate;
            this.isPartialPayment = isPartialPayment;
            this.existingPayment = existingPayment;
        }

        public static DuplicateCheckResult none() {
            return new DuplicateCheckResult(false, false, null);
        }

        public static DuplicateCheckResult found(PaymentEntity existing) {
            boolean partial = existing != null && existing.isPartial() && existing.remainingBalance > 0.005;
            return new DuplicateCheckResult(true, partial, existing);
        }
    }

    public static final class DuplicateLockConflictException extends Exception {
        public final PaymentEntity conflictingPayment;

        public DuplicateLockConflictException(String message, PaymentEntity conflictingPayment) {
            super(message);
            this.conflictingPayment = conflictingPayment;
        }
    }

    /**
     * Normalizes text for deterministic student comparison:
     * trims leading/trailing whitespace, converts to lowercase, and collapses consecutive spaces.
     */
    public static String normalizeText(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Builds the canonical Student Identity Key stored on PaymentEntity.studentIdentityKey.
     * Always normalizes First Name + Last Name so omitting a student number cannot bypass detection.
     * For variable-fee folders (targetFeeAmount <= 0), includes the normalized purpose.
     */
    public static String buildStudentIdentityKey(
            long sectionId,
            String firstName,
            String lastName,
            String purpose,
            double targetFeeAmount
    ) {
        String normFirst = normalizeText(firstName);
        String normLast = normalizeText(lastName);
        StringBuilder sb = new StringBuilder();
        sb.append(sectionId).append("|NAME|").append(normFirst).append(' ').append(normLast);
        if (targetFeeAmount <= 0.005) {
            String normPurpose = normalizeText(purpose);
            if (!normPurpose.isEmpty()) {
                sb.append("|PURPOSE|").append(normPurpose);
            }
        }
        return sb.toString();
    }

    /**
     * Builds the deterministic SHA-256 lock document ID for rooms/{roomId}/payment_locks/{lockKey}.
     * Uses sectionId + normalized student full name (+ normalized purpose for variable-fee folders)
     * so two cashiers tapping "Save Payment" at the exact same millisecond hit the exact same lock document.
     */
    public static String buildLockDocumentId(
            long sectionId,
            String firstName,
            String lastName,
            String purpose,
            double targetFeeAmount
    ) {
        String canonicalKey = buildStudentIdentityKey(sectionId, firstName, lastName, purpose, targetFeeAmount);
        return SecuritySignatureHelper.sha256Hex("LOCK|" + canonicalKey);
    }

    public static DuplicateCheckResult checkDuplicateBeforeSubmission(
            Context context,
            long sectionId,
            String studentNumber,
            String firstName,
            String lastName,
            String purpose
    ) {
        return checkDuplicateBeforeSubmission(context, sectionId, studentNumber, firstName, lastName, purpose, null);
    }

    /**
     * Performs a two-tier pre-submission duplicate check (Local Room + Live Firestore Source.SERVER).
     * Accepts excludeReceiptId so receipt edits don't self-collide.
     * Must be called on a background thread.
     */
    public static DuplicateCheckResult checkDuplicateBeforeSubmission(
            Context context,
            long sectionId,
            String studentNumber,
            String firstName,
            String lastName,
            String purpose,
            String excludeReceiptId
    ) {
        AppDatabase db = AppDatabase.getInstance(context);
        SectionEntity section = db.sectionDao().findByIdSync(sectionId);
        double targetFee = section != null ? section.targetFeeAmount : 0.0;

        String identityKey = buildStudentIdentityKey(sectionId, firstName, lastName, purpose, targetFee);
        String normStudentNo = normalizeText(studentNumber);
        String normFirst = normalizeText(firstName);
        String normLast = normalizeText(lastName);
        String normPurpose = normalizeText(purpose);

        PaymentDao paymentDao = db.paymentDao();
        List<PaymentEntity> localCandidates = paymentDao.findActiveDuplicatesForStudentExcludingReceiptSync(
                sectionId,
                identityKey,
                normStudentNo,
                normFirst,
                normLast,
                excludeReceiptId
        );

        PaymentEntity matchedLocal = pickBestMatchingCandidate(
                localCandidates,
                identityKey,
                normStudentNo,
                normFirst,
                normLast,
                normPurpose,
                targetFee,
                excludeReceiptId
        );
        if (matchedLocal != null) {
            return DuplicateCheckResult.found(matchedLocal);
        }

        // Tier 2: When online and inside a shared room, perform a live Source.SERVER check
        String roomId = UserSessionManager.getInstance(context).getActiveRoomId();
        if (roomId != null && !roomId.trim().isEmpty() && FirebaseSyncManager.isFirebaseConfigured(context)) {
            try {
                FirebaseFirestore firestore = FirebaseFirestore.getInstance();
                QuerySnapshot serverSnapshot = Tasks.await(
                        firestore.collection(COLLECTION_ROOMS)
                                .document(roomId)
                                .collection(SUBCOLLECTION_PAYMENTS)
                                .whereEqualTo("sectionId", sectionId)
                                .get(Source.SERVER),
                        4,
                        TimeUnit.SECONDS
                );
                if (serverSnapshot != null && !serverSnapshot.isEmpty()) {
                    for (DocumentSnapshot doc : serverSnapshot.getDocuments()) {
                        String docReceiptId = getString(doc, "receiptId", doc.getId());
                        if (excludeReceiptId != null && excludeReceiptId.equals(docReceiptId)) {
                            continue;
                        }
                        String status = getString(doc, "status", PaymentEntity.STATUS_PAID);
                        if (PaymentEntity.STATUS_VOIDED.equalsIgnoreCase(status)) {
                            continue;
                        }
                        String docFirst = normalizeText(getString(doc, "firstName", ""));
                        String docLast = normalizeText(getString(doc, "lastName", ""));
                        String docStudentNo = normalizeText(getString(doc, "studentNumber", ""));
                        String docIdentityKey = getString(doc, "studentIdentityKey", "");
                        String docPurpose = normalizeText(getString(doc, "purpose", ""));

                        boolean sameIdentityKey = !docIdentityKey.isEmpty() && docIdentityKey.equals(identityKey);
                        boolean sameStudentNo = !normStudentNo.isEmpty() && !docStudentNo.isEmpty()
                                && normStudentNo.equals(docStudentNo);
                        boolean sameFullName = !normFirst.isEmpty() && normFirst.equals(docFirst)
                                && normLast.equals(docLast);

                        if (sameIdentityKey || sameStudentNo || sameFullName) {
                            if (targetFee <= 0.005 && !normPurpose.isEmpty() && !docPurpose.isEmpty()
                                    && !normPurpose.equals(docPurpose)) {
                                continue;
                            }
                            PaymentEntity remoteEntity = mapRemoteDocumentToPayment(
                                    doc,
                                    roomId,
                                    sectionId,
                                    section != null ? section.sectionName : ""
                            );
                            // Cache into local Room so cashier can view receipt immediately
                            PaymentEntity existingByReceipt = paymentDao.findByReceiptIdSync(remoteEntity.receiptId);
                            if (existingByReceipt != null) {
                                remoteEntity.id = existingByReceipt.id;
                            }
                            paymentDao.insertOrReplacePayment(remoteEntity);
                            return DuplicateCheckResult.found(remoteEntity);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Network timeout or offline: fall back gracefully to local Room check
            }
        }

        return DuplicateCheckResult.none();
    }

    private static PaymentEntity pickBestMatchingCandidate(
            List<PaymentEntity> candidates,
            String identityKey,
            String normStudentNo,
            String normFirst,
            String normLast,
            String normPurpose,
            double targetFee,
            String excludeReceiptId
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (PaymentEntity candidate : candidates) {
            if (candidate == null || candidate.isVoided()) {
                continue;
            }
            if (excludeReceiptId != null && excludeReceiptId.equals(candidate.receiptId)) {
                continue;
            }
            String candFirst = normalizeText(candidate.firstName);
            String candLast = normalizeText(candidate.lastName);
            String candStudentNo = normalizeText(candidate.studentNumber);
            String candPurpose = normalizeText(candidate.purpose);

            boolean sameIdentity = candidate.studentIdentityKey != null
                    && candidate.studentIdentityKey.equals(identityKey);
            boolean sameStudentNo = !normStudentNo.isEmpty() && !candStudentNo.isEmpty()
                    && normStudentNo.equals(candStudentNo);
            boolean sameName = normFirst.equals(candFirst) && normLast.equals(candLast);

            if (sameIdentity || sameStudentNo || sameName) {
                if (targetFee <= 0.005 && !normPurpose.isEmpty() && !candPurpose.isEmpty()
                        && !normPurpose.equals(candPurpose)) {
                    continue;
                }
                return candidate;
            }
        }
        return null;
    }

    /**
     * Simultaneous Tap Protection:
     * Executes an atomic Firestore transaction on rooms/{roomId}/payment_locks/{lockKey}.
     * If another cashier committed a PAID payment for the same student milliseconds earlier,
     * the transaction aborts with DuplicateLockConflictException.
     */
    public static void acquireRemotePaymentLockAndCommit(
            Context context,
            String roomId,
            PaymentEntity payment,
            double targetFeeAmount,
            boolean forceOverride
    ) throws DuplicateLockConflictException {
        if (roomId == null || roomId.trim().isEmpty() || !FirebaseSyncManager.isFirebaseConfigured(context)) {
            return;
        }
        String lockDocId = buildLockDocumentId(
                payment.sectionId,
                payment.firstName,
                payment.lastName,
                payment.purpose,
                targetFeeAmount
        );
        FirebaseFirestore firestore = FirebaseFirestore.getInstance();
        DocumentReference lockRef = firestore.collection(COLLECTION_ROOMS)
                .document(roomId)
                .collection(SUBCOLLECTION_PAYMENT_LOCKS)
                .document(lockDocId);
        DocumentReference paymentRef = firestore.collection(COLLECTION_ROOMS)
                .document(roomId)
                .collection(SUBCOLLECTION_PAYMENTS)
                .document(payment.receiptId);

        try {
            PaymentEntity conflicting = Tasks.await(firestore.runTransaction(transaction -> {
                DocumentSnapshot lockSnap = transaction.get(lockRef);
                if (!forceOverride && lockSnap.exists()) {
                    String lockedStatus = getString(lockSnap, "status", "");
                    String lockedReceiptId = getString(lockSnap, "receiptId", "");
                    if (!PaymentEntity.STATUS_VOIDED.equalsIgnoreCase(lockedStatus)
                            && !lockedReceiptId.isEmpty()
                            && !lockedReceiptId.equals(payment.receiptId)) {
                        long lockTime = getLong(lockSnap, "updatedAt", System.currentTimeMillis());
                        PaymentEntity conflict = new PaymentEntity(
                                lockedReceiptId,
                                payment.sectionId,
                                payment.sectionName,
                                payment.firstName,
                                payment.lastName,
                                getString(lockSnap, "studentNumber", payment.studentNumber),
                                payment.purpose,
                                getDouble(lockSnap, "amount", payment.amount),
                                getString(lockSnap, "paymentMethod", PaymentEntity.METHOD_CASH),
                                null,
                                null,
                                null,
                                PaymentEntity.STATUS_PAID,
                                getString(lockSnap, "paymentStatus", PaymentEntity.STATUS_PAID),
                                getDouble(lockSnap, "remainingBalance", 0.0),
                                null,
                                lockTime,
                                null,
                                "",
                                lockedReceiptId + "|",
                                PaymentEntity.SYNC_SYNCED,
                                lockTime,
                                roomId,
                                getString(lockSnap, "actorUserId", ""),
                                getString(lockSnap, "actorName", "Another Cashier"),
                                getString(lockSnap, "actorRole", "COLLECTOR"),
                                payment.studentIdentityKey,
                                null
                        );
                        return conflict;
                    }
                }

                if (!forceOverride) {
                    Map<String, Object> lockData = new HashMap<>();
                    lockData.put("lockKey", lockDocId);
                    lockData.put("roomId", roomId);
                    lockData.put("sectionId", payment.sectionId);
                    lockData.put("studentIdentityKey", payment.studentIdentityKey != null ? payment.studentIdentityKey : "");
                    lockData.put("studentNumber", payment.studentNumber != null ? payment.studentNumber : "");
                    lockData.put("studentName", payment.getFullName());
                    lockData.put("receiptId", payment.receiptId);
                    lockData.put("amount", payment.amount);
                    lockData.put("remainingBalance", payment.remainingBalance);
                    lockData.put("paymentMethod", payment.paymentMethod);
                    lockData.put("status", payment.status);
                    lockData.put("paymentStatus", payment.paymentStatus);
                    lockData.put("actorUserId", payment.actorUserId != null ? payment.actorUserId : "");
                    lockData.put("actorName", payment.actorName != null ? payment.actorName : "");
                    lockData.put("actorRole", payment.actorRole != null ? payment.actorRole : "");
                    lockData.put("updatedAt", payment.updatedAt);
                    transaction.set(lockRef, lockData, SetOptions.merge());
                }

                Map<String, Object> paymentData = buildPaymentFirestoreMap(payment, roomId);
                transaction.set(paymentRef, paymentData, SetOptions.merge());
                return null;
            }), 5, TimeUnit.SECONDS);

            if (conflicting != null) {
                throw new DuplicateLockConflictException(
                        "Simultaneous payment already recorded by " + conflicting.actorName,
                        conflicting
                );
            }
        } catch (DuplicateLockConflictException dupEx) {
            throw dupEx;
        } catch (Exception e) {
            if (e.getCause() instanceof DuplicateLockConflictException) {
                throw (DuplicateLockConflictException) e.getCause();
            }
            // Offline or transient Firestore error: allow local-first save and queue background sync
        }
    }

    /**
     * Releases the remote student payment lock when an OWNER voids a payment receipt,
     * allowing a legitimate replacement receipt to be recorded for the student.
     */
    public static void releaseRemotePaymentLock(
            Context context,
            String roomId,
            PaymentEntity voidedPayment,
            double targetFeeAmount
    ) {
        if (roomId == null || roomId.trim().isEmpty() || voidedPayment == null
                || !FirebaseSyncManager.isFirebaseConfigured(context)) {
            return;
        }
        try {
            String lockDocId = buildLockDocumentId(
                    voidedPayment.sectionId,
                    voidedPayment.firstName,
                    voidedPayment.lastName,
                    voidedPayment.purpose,
                    targetFeeAmount
            );
            FirebaseFirestore.getInstance()
                    .collection(COLLECTION_ROOMS)
                    .document(roomId)
                    .collection(SUBCOLLECTION_PAYMENT_LOCKS)
                    .document(lockDocId)
                    .delete();
        } catch (Exception ignored) {
        }
    }

    public static Map<String, Object> buildPaymentFirestoreMap(PaymentEntity p, String roomId) {
        Map<String, Object> data = new HashMap<>();
        data.put("localId", p.id);
        data.put("roomId", roomId);
        data.put("receiptId", p.receiptId);
        data.put("sectionId", p.sectionId);
        data.put("sectionName", p.sectionName);
        data.put("firstName", p.firstName);
        data.put("lastName", p.lastName);
        data.put("studentNumber", p.studentNumber != null ? p.studentNumber : "");
        data.put("studentFullName", p.getFullName());
        data.put("studentIdentityKey", p.studentIdentityKey != null ? p.studentIdentityKey : "");
        data.put("duplicateOverrideReason", p.duplicateOverrideReason != null ? p.duplicateOverrideReason : "");
        data.put("amount", p.amount);
        data.put("paymentMethod", p.paymentMethod);
        data.put("purpose", p.purpose);
        data.put("gcashReferenceNo", p.gcashReferenceNo != null ? p.gcashReferenceNo : "");
        data.put("remoteProofUrl", p.remoteProofUrl != null ? p.remoteProofUrl : "");
        data.put("qrSignature", p.qrSignature);
        data.put("qrPayload", p.qrPayload);
        data.put("status", p.status);
        data.put("paymentStatus", p.paymentStatus);
        data.put("remainingBalance", p.remainingBalance);
        data.put("voidReason", p.voidReason != null ? p.voidReason : "");
        data.put("createdAt", p.createdAt);
        data.put("voidedAt", p.voidedAt != null ? p.voidedAt : 0L);
        data.put("updatedAt", p.updatedAt);
        data.put("actorUserId", p.actorUserId != null ? p.actorUserId : "");
        data.put("actorName", p.actorName != null ? p.actorName : "");
        data.put("actorRole", p.actorRole != null ? p.actorRole : "");
        return data;
    }

    private static PaymentEntity mapRemoteDocumentToPayment(
            DocumentSnapshot doc,
            String roomId,
            long fallbackSectionId,
            String fallbackSectionName
    ) {
        String receiptId = getString(doc, "receiptId", doc.getId());
        long sectionId = getLong(doc, "sectionId", fallbackSectionId);
        String sectionName = getString(doc, "sectionName", fallbackSectionName);
        String firstName = getString(doc, "firstName", "");
        String lastName = getString(doc, "lastName", "");
        String studentNumber = getString(doc, "studentNumber", "");
        String purpose = getString(doc, "purpose", "");
        double amount = getDouble(doc, "amount", 0.0);
        String paymentMethod = getString(doc, "paymentMethod", PaymentEntity.METHOD_CASH);
        String refNo = getString(doc, "gcashReferenceNo", null);
        String remoteUrl = getString(doc, "remoteProofUrl", null);
        String qrSig = getString(doc, "qrSignature", "");
        String qrPayload = getString(doc, "qrPayload", SecuritySignatureHelper.buildQrPayload(receiptId, qrSig));
        String status = getString(doc, "status", PaymentEntity.STATUS_PAID);
        String paymentStatus = getString(doc, "paymentStatus", status);
        double remainingBalance = getDouble(doc, "remainingBalance", 0.0);
        String voidReason = getString(doc, "voidReason", null);
        long createdAt = getLong(doc, "createdAt", System.currentTimeMillis());
        long voidedAtRaw = getLong(doc, "voidedAt", 0L);
        Long voidedAt = voidedAtRaw > 0 ? voidedAtRaw : null;
        long updatedAt = getLong(doc, "updatedAt", createdAt);
        String actorUserId = getString(doc, "actorUserId", null);
        String actorName = getString(doc, "actorName", "Room Member");
        String actorRole = getString(doc, "actorRole", "COLLECTOR");
        String identityKey = getString(doc, "studentIdentityKey",
                buildStudentIdentityKey(sectionId, firstName, lastName, purpose, 0.0));
        String overrideReasonRaw = getString(doc, "duplicateOverrideReason", "");
        String overrideReason = overrideReasonRaw.isEmpty() ? null : overrideReasonRaw;

        return new PaymentEntity(
                receiptId,
                sectionId,
                sectionName,
                firstName,
                lastName,
                studentNumber,
                purpose,
                amount,
                paymentMethod,
                refNo,
                null,
                remoteUrl,
                status,
                paymentStatus,
                remainingBalance,
                voidReason,
                createdAt,
                voidedAt,
                qrSig,
                qrPayload,
                PaymentEntity.SYNC_SYNCED,
                updatedAt,
                roomId,
                actorUserId,
                actorName,
                actorRole,
                identityKey,
                overrideReason
        );
    }

    private static String getString(DocumentSnapshot doc, String key, String defaultVal) {
        Object val = doc.get(key);
        return val != null ? String.valueOf(val) : defaultVal;
    }

    private static long getLong(DocumentSnapshot doc, String key, long defaultVal) {
        Object val = doc.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return defaultVal;
    }

    private static double getDouble(DocumentSnapshot doc, String key, double defaultVal) {
        Object val = doc.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return defaultVal;
    }
}
