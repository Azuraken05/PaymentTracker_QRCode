package com.example.qr_paymentscan_cash_n_online.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "expenses",
        indices = {@Index(value = {"section_id"})}
)
public class ExpenseEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_VOIDED = "VOIDED";

    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED = "SYNCED";

    public static final String IMAGE_STATUS_NONE = "NONE";
    public static final String IMAGE_STATUS_PENDING_UPLOAD = "PENDING_UPLOAD";
    public static final String IMAGE_STATUS_UPLOADED = "UPLOADED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "section_id")
    public long sectionId;

    @NonNull
    @ColumnInfo(name = "section_name")
    public String sectionName;

    @NonNull
    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "amount")
    public double amount;

    @NonNull
    @ColumnInfo(name = "category")
    public String category;

    @ColumnInfo(name = "notes")
    public String notes;

    @ColumnInfo(name = "proof_image_path")
    public String proofImagePath;

    @ColumnInfo(name = "remote_proof_url")
    public String remoteProofUrl;

    @NonNull
    @ColumnInfo(name = "status", defaultValue = "'ACTIVE'")
    public String status;

    @ColumnInfo(name = "void_reason")
    public String voidReason;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "voided_at")
    public Long voidedAt;

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

    @ColumnInfo(name = "proof_image_url")
    public String proofImageUrl;

    @NonNull
    @ColumnInfo(name = "image_sync_status", defaultValue = "'NONE'")
    public String imageSyncStatus = IMAGE_STATUS_NONE;

    @Ignore
    public ExpenseEntity(
            long sectionId,
            @NonNull String sectionName,
            @NonNull String title,
            double amount,
            @NonNull String category,
            String notes,
            String proofImagePath,
            String remoteProofUrl,
            @NonNull String status,
            String voidReason,
            long createdAt,
            Long voidedAt,
            @NonNull String syncStatus,
            long updatedAt
    ) {
        this(
                sectionId,
                sectionName,
                title,
                amount,
                category,
                notes,
                proofImagePath,
                remoteProofUrl,
                status,
                voidReason,
                createdAt,
                voidedAt,
                syncStatus,
                updatedAt,
                null,
                null,
                null,
                null
        );
    }

    public ExpenseEntity(
            long sectionId,
            @NonNull String sectionName,
            @NonNull String title,
            double amount,
            @NonNull String category,
            String notes,
            String proofImagePath,
            String remoteProofUrl,
            @NonNull String status,
            String voidReason,
            long createdAt,
            Long voidedAt,
            @NonNull String syncStatus,
            long updatedAt,
            String roomId,
            String actorUserId,
            String actorName,
            String actorRole
    ) {
        this.sectionId = sectionId;
        this.sectionName = sectionName;
        this.title = title;
        this.amount = amount;
        this.category = category;
        this.notes = notes;
        this.proofImagePath = proofImagePath;
        this.remoteProofUrl = remoteProofUrl;
        this.status = status;
        this.voidReason = voidReason;
        this.createdAt = createdAt;
        this.voidedAt = voidedAt;
        this.syncStatus = syncStatus;
        this.updatedAt = updatedAt;
        this.roomId = roomId;
        this.actorUserId = actorUserId;
        this.actorName = actorName;
        this.actorRole = actorRole;
    }

    public boolean isVoided() {
        return STATUS_VOIDED.equalsIgnoreCase(status);
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
