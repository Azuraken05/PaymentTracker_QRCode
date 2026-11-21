package com.example.qr_paymentscan_cash_n_online.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "payments",
        foreignKeys = @ForeignKey(
                entity = SectionEntity.class,
                parentColumns = "id",
                childColumns = "section_id",
                onDelete = ForeignKey.RESTRICT
        ),
        indices = {
                @Index(value = {"receipt_id"}, unique = true),
                @Index(value = {"section_id"}),
                @Index(value = {"section_id", "student_identity_key"})
        }
)
public class PaymentEntity {

    public static final String METHOD_CASH = "CASH";
    public static final String METHOD_GCASH = "GCASH";

    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_VOIDED = "VOIDED";

    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED = "SYNCED";

    public static final String IMAGE_STATUS_NONE = "NONE";
    public static final String IMAGE_STATUS_PENDING_UPLOAD = "PENDING_UPLOAD";
    public static final String IMAGE_STATUS_UPLOADED = "UPLOADED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "receipt_id")
    public String receiptId;

    @ColumnInfo(name = "section_id")
    public long sectionId;

    @NonNull
    @ColumnInfo(name = "section_name")
    public String sectionName;

    @NonNull
    @ColumnInfo(name = "first_name")
    public String firstName;

    @NonNull
    @ColumnInfo(name = "last_name")
    public String lastName;

    @ColumnInfo(name = "student_number")
    public String studentNumber;

    @NonNull
    @ColumnInfo(name = "purpose")
    public String purpose;

    @ColumnInfo(name = "amount")
    public double amount;

    @NonNull
    @ColumnInfo(name = "payment_method")
    public String paymentMethod;

    @ColumnInfo(name = "gcash_reference_no")
    public String gcashReferenceNo;

    @ColumnInfo(name = "proof_image_path")
    public String proofImagePath;

    @ColumnInfo(name = "remote_proof_url")
    public String remoteProofUrl;

    @NonNull
    @ColumnInfo(name = "status")
    public String status;

    @NonNull
    @ColumnInfo(name = "payment_status", defaultValue = "'PAID'")
    public String paymentStatus;

    @ColumnInfo(name = "remaining_balance", defaultValue = "0")
    public double remainingBalance;

    @ColumnInfo(name = "void_reason")
    public String voidReason;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "voided_at")
    public Long voidedAt;

    @NonNull
    @ColumnInfo(name = "qr_signature")
    public String qrSignature;

    @NonNull
    @ColumnInfo(name = "qr_payload")
    public String qrPayload;

    @NonNull
    @ColumnInfo(name = "sync_status", defaultValue = "'PENDING'")
    public String syncStatus;

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    public long updatedAt;

    @ColumnInfo(name = "room_id")
    public String roomId;

    @ColumnInfo(name = "actor_user_id")
    public String actorUserId;

    @ColumnInfo(name = "actor_name")
    public String actorName;

    @ColumnInfo(name = "actor_role")
    public String actorRole;

    @ColumnInfo(name = "student_identity_key")
    public String studentIdentityKey;

    @ColumnInfo(name = "duplicate_override_reason")
    public String duplicateOverrideReason;

    @ColumnInfo(name = "proof_image_url")
    public String proofImageUrl;

    @NonNull
    @ColumnInfo(name = "image_sync_status", defaultValue = "'NONE'")
    public String imageSyncStatus = IMAGE_STATUS_NONE;

    @ColumnInfo(name = "last_edited_by_name")
    public String lastEditedByName;

    @ColumnInfo(name = "last_edited_at", defaultValue = "0")
    public long lastEditedAt = 0L;

    @Ignore
    public PaymentEntity(
            @NonNull String receiptId,
            long sectionId,
            @NonNull String sectionName,
            @NonNull String firstName,
            @NonNull String lastName,
            String studentNumber,
            @NonNull String purpose,
            double amount,
            @NonNull String paymentMethod,
            String gcashReferenceNo,
            String proofImagePath,
            String remoteProofUrl,
            @NonNull String status,
            @NonNull String paymentStatus,
            double remainingBalance,
            String voidReason,
            long createdAt,
            Long voidedAt,
            @NonNull String qrSignature,
            @NonNull String qrPayload,
            @NonNull String syncStatus,
            long updatedAt
    ) {
        this(
                receiptId,
                sectionId,
                sectionName,
                firstName,
                lastName,
                studentNumber,
                purpose,
                amount,
                paymentMethod,
                gcashReferenceNo,
                proofImagePath,
                remoteProofUrl,
                status,
                paymentStatus,
                remainingBalance,
                voidReason,
                createdAt,
                voidedAt,
                qrSignature,
                qrPayload,
                syncStatus,
                updatedAt,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @Ignore
    public PaymentEntity(
            @NonNull String receiptId,
            long sectionId,
            @NonNull String sectionName,
            @NonNull String firstName,
            @NonNull String lastName,
            String studentNumber,
            @NonNull String purpose,
            double amount,
            @NonNull String paymentMethod,
            String gcashReferenceNo,
            String proofImagePath,
            String remoteProofUrl,
            @NonNull String status,
            @NonNull String paymentStatus,
            double remainingBalance,
            String voidReason,
            long createdAt,
            Long voidedAt,
            @NonNull String qrSignature,
            @NonNull String qrPayload,
            @NonNull String syncStatus,
            long updatedAt,
            String roomId,
            String actorUserId,
            String actorName,
            String actorRole
    ) {
        this(
                receiptId,
                sectionId,
                sectionName,
                firstName,
                lastName,
                studentNumber,
                purpose,
                amount,
                paymentMethod,
                gcashReferenceNo,
                proofImagePath,
                remoteProofUrl,
                status,
                paymentStatus,
                remainingBalance,
                voidReason,
                createdAt,
                voidedAt,
                qrSignature,
                qrPayload,
                syncStatus,
                updatedAt,
                roomId,
                actorUserId,
                actorName,
                actorRole,
                null,
                null
        );
    }

    public PaymentEntity(
            @NonNull String receiptId,
            long sectionId,
            @NonNull String sectionName,
            @NonNull String firstName,
            @NonNull String lastName,
            String studentNumber,
            @NonNull String purpose,
            double amount,
            @NonNull String paymentMethod,
            String gcashReferenceNo,
            String proofImagePath,
            String remoteProofUrl,
            @NonNull String status,
            @NonNull String paymentStatus,
            double remainingBalance,
            String voidReason,
            long createdAt,
            Long voidedAt,
            @NonNull String qrSignature,
            @NonNull String qrPayload,
            @NonNull String syncStatus,
            long updatedAt,
            String roomId,
            String actorUserId,
            String actorName,
            String actorRole,
            String studentIdentityKey,
            String duplicateOverrideReason
    ) {
        this.receiptId = receiptId;
        this.sectionId = sectionId;
        this.sectionName = sectionName;
        this.firstName = firstName;
        this.lastName = lastName;
        this.studentNumber = (studentNumber != null && !studentNumber.trim().isEmpty())
                ? studentNumber.trim()
                : null;
        this.purpose = purpose;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.gcashReferenceNo = gcashReferenceNo;
        this.proofImagePath = proofImagePath;
        this.remoteProofUrl = remoteProofUrl;
        this.status = status;
        this.paymentStatus = paymentStatus;
        this.remainingBalance = remainingBalance;
        this.voidReason = voidReason;
        this.createdAt = createdAt;
        this.voidedAt = voidedAt;
        this.qrSignature = qrSignature;
        this.qrPayload = qrPayload;
        this.syncStatus = syncStatus;
        this.updatedAt = updatedAt;
        this.roomId = roomId;
        this.actorUserId = actorUserId;
        this.actorName = actorName;
        this.actorRole = actorRole;
        this.studentIdentityKey = studentIdentityKey;
        this.duplicateOverrideReason = duplicateOverrideReason;
    }

    public String getFullName() {
        return (firstName + " " + lastName).trim();
    }

    public boolean hasStudentNumber() {
        return studentNumber != null && !studentNumber.trim().isEmpty();
    }

    public boolean isVoided() {
        return STATUS_VOIDED.equalsIgnoreCase(status);
    }

    public boolean isPartial() {
        return STATUS_PARTIAL.equalsIgnoreCase(paymentStatus) || remainingBalance > 0.009;
    }

    public boolean isOnline() {
        return paymentMethod != null && !METHOD_CASH.equalsIgnoreCase(paymentMethod.trim());
    }

    public boolean isGcash() {
        return isOnline();
    }

    public String getFormattedActorAttribution() {
        if (actorName == null || actorName.trim().isEmpty()) {
            return "Owner (Local)";
        }
        if (actorRole != null && !actorRole.trim().isEmpty()) {
            return actorName.trim() + " (" + actorRole.trim() + ")";
        }
        return actorName.trim();
    }
}
