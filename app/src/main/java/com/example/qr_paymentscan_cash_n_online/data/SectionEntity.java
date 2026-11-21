package com.example.qr_paymentscan_cash_n_online.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "sections",
        indices = {@Index(value = {"section_name"}, unique = true)}
)
public class SectionEntity {

    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED = "SYNCED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "section_name")
    public String sectionName;

    @ColumnInfo(name = "description")
    public String description;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "parent_folder_id")
    public Long parentFolderId;

    @ColumnInfo(name = "target_fee_amount", defaultValue = "0")
    public double targetFeeAmount;

    @NonNull
    @ColumnInfo(name = "sync_status", defaultValue = "'PENDING'")
    public String syncStatus;

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    public long updatedAt;

    @ColumnInfo(name = "room_id")
    public String roomId;

    @Ignore
    public SectionEntity(@NonNull String sectionName, String description, long createdAt) {
        this(sectionName, description, createdAt, null, 0.0, SYNC_PENDING, createdAt, null);
    }

    @Ignore
    public SectionEntity(@NonNull String sectionName, String description, long createdAt, Long parentFolderId) {
        this(sectionName, description, createdAt, parentFolderId, 0.0, SYNC_PENDING, createdAt, null);
    }

    @Ignore
    public SectionEntity(
            @NonNull String sectionName,
            String description,
            long createdAt,
            Long parentFolderId,
            double targetFeeAmount,
            @NonNull String syncStatus,
            long updatedAt
    ) {
        this(sectionName, description, createdAt, parentFolderId, targetFeeAmount, syncStatus, updatedAt, null);
    }

    public SectionEntity(
            @NonNull String sectionName,
            String description,
            long createdAt,
            Long parentFolderId,
            double targetFeeAmount,
            @NonNull String syncStatus,
            long updatedAt,
            String roomId
    ) {
        this.sectionName = sectionName;
        this.description = description != null ? description : "";
        this.createdAt = createdAt;
        this.parentFolderId = parentFolderId;
        this.targetFeeAmount = targetFeeAmount;
        this.syncStatus = syncStatus;
        this.updatedAt = updatedAt;
        this.roomId = roomId;
    }
}
