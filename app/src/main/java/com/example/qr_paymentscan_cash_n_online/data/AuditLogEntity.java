package com.example.qr_paymentscan_cash_n_online.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "audit_logs",
        indices = {@Index(value = {"section_id"}), @Index(value = {"action_type"})}
)
public class AuditLogEntity {

    public static final String ACTION_ROOM_CREATED = "ROOM_CREATED";
    public static final String ACTION_MEMBER_JOINED = "MEMBER_JOINED";
    public static final String ACTION_MEMBER_ROLE_CHANGED = "MEMBER_ROLE_CHANGED";
    public static final String ACTION_MEMBER_REMOVED = "MEMBER_REMOVED";
    public static final String ACTION_FOLDER_CREATED = "FOLDER_CREATED";
    public static final String ACTION_FOLDER_RENAMED = "FOLDER_RENAMED";
    public static final String ACTION_FOLDER_DELETED = "FOLDER_DELETED";
    public static final String ACTION_PAYMENT_RECORDED = "PAYMENT_RECORDED";
    public static final String ACTION_PAYMENT_VOIDED = "PAYMENT_VOIDED";
    public static final String ACTION_EXPENSE_RECORDED = "EXPENSE_RECORDED";
    public static final String ACTION_EXPENSE_VOIDED = "EXPENSE_VOIDED";
    public static final String ACTION_EXCEL_EXPORTED = "EXCEL_EXPORTED";
    public static final String ACTION_QR_VERIFICATION_FAILED = "QR_VERIFICATION_FAILED";
    public static final String ACTION_DATABASE_BACKUP = "DATABASE_BACKUP";

    // Room Security & Anti-Hacking Audit Events
    public static final String ACTION_SECURITY_JOIN_FAILED = "SECURITY_JOIN_FAILED";
    public static final String ACTION_SECURITY_JOIN_DENIED = "SECURITY_JOIN_DENIED";
    public static final String ACTION_SECURITY_BRUTE_FORCE_ALERT = "SECURITY_BRUTE_FORCE_ALERT";
    public static final String ACTION_SECURITY_JOIN_REQUESTED = "SECURITY_JOIN_REQUESTED";
    public static final String ACTION_SECURITY_MEMBER_APPROVED = "SECURITY_MEMBER_APPROVED";
    public static final String ACTION_SECURITY_MEMBER_BANNED = "SECURITY_MEMBER_BANNED";
    public static final String ACTION_SECURITY_ROOM_LOCKED = "SECURITY_ROOM_LOCKED";
    public static final String ACTION_SECURITY_ROOM_UNLOCKED = "SECURITY_ROOM_UNLOCKED";
    public static final String ACTION_SECURITY_CREDENTIALS_ROTATED = "SECURITY_CREDENTIALS_ROTATED";
    public static final String ACTION_SECURITY_KEY_ROTATED = "SECURITY_KEY_ROTATED";
    public static final String ACTION_SECURITY_SECRET_ROTATED = "SECURITY_SECRET_ROTATED";
    public static final String ACTION_SECURITY_ACCESS_REVOKED = "SECURITY_ACCESS_REVOKED";

    // Multi-Cashier Duplicate Payment Prevention Audit Events
    public static final String ACTION_DUPLICATE_PAYMENT_BLOCKED = "DUPLICATE_PAYMENT_BLOCKED";
    public static final String ACTION_DUPLICATE_PAYMENT_OVERRIDE = "DUPLICATE_PAYMENT_OVERRIDE";
    public static final String ACTION_DUPLICATE_SYNC_CONFLICT = "DUPLICATE_SYNC_CONFLICT";

    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED = "SYNCED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "action_type")
    public String actionType;

    @ColumnInfo(name = "section_id")
    public Long sectionId;

    @ColumnInfo(name = "section_name")
    public String sectionName;

    @ColumnInfo(name = "receipt_id")
    public String receiptId;

    @NonNull
    @ColumnInfo(name = "details")
    public String details;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

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

    @Ignore
    public AuditLogEntity(
            @NonNull String actionType,
            Long sectionId,
            String sectionName,
            String receiptId,
            @NonNull String details,
            long timestamp
    ) {
        this(actionType, sectionId, sectionName, receiptId, details, timestamp, SYNC_PENDING, timestamp, null, null, null, null);
    }

    @Ignore
    public AuditLogEntity(
            @NonNull String actionType,
            Long sectionId,
            String sectionName,
            String receiptId,
            @NonNull String details,
            long timestamp,
            @NonNull String syncStatus,
            long updatedAt
    ) {
        this(actionType, sectionId, sectionName, receiptId, details, timestamp, syncStatus, updatedAt, null, null, null, null);
    }

    public AuditLogEntity(
            @NonNull String actionType,
            Long sectionId,
            String sectionName,
            String receiptId,
            @NonNull String details,
            long timestamp,
            @NonNull String syncStatus,
            long updatedAt,
            String roomId,
            String actorUserId,
            String actorName,
            String actorRole
    ) {
        this.actionType = actionType;
        this.sectionId = sectionId;
        this.sectionName = sectionName;
        this.receiptId = receiptId;
        this.details = details;
        this.timestamp = timestamp;
        this.syncStatus = syncStatus;
        this.updatedAt = updatedAt;
        this.roomId = roomId;
        this.actorUserId = actorUserId;
        this.actorName = actorName;
        this.actorRole = actorRole;
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

    public boolean isSecurityOrDuplicateAlert() {
        return actionType.startsWith("SECURITY_")
                || actionType.startsWith("DUPLICATE_")
                || ACTION_QR_VERIFICATION_FAILED.equals(actionType);
    }
}
