package com.example.qr_paymentscan_cash_n_online.data;

import androidx.room.ColumnInfo;

public class SectionSummary {

    @ColumnInfo(name = "id")
    public long id;

    @ColumnInfo(name = "section_name")
    public String sectionName;

    @ColumnInfo(name = "description")
    public String description;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "parent_folder_id")
    public Long parentFolderId;

    @ColumnInfo(name = "subfolder_count")
    public int subfolderCount;

    @ColumnInfo(name = "total_collected")
    public double totalCollected;

    @ColumnInfo(name = "valid_count")
    public int validCount;

    @ColumnInfo(name = "voided_count")
    public int voidedCount;

    @ColumnInfo(name = "sync_status")
    public String syncStatus;

    @ColumnInfo(name = "room_id")
    public String roomId;
}
