package com.example.qr_paymentscan_cash_n_online.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "rooms",
        indices = {@Index(value = {"room_key"}, unique = true)}
)
public class RoomEntity {

    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_COLLECTOR = "COLLECTOR";
    public static final String ROLE_AUDITOR = "AUDITOR";

    public static final String DEFAULT_PERSONAL_ROOM_ID = "room_personal_default";

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "room_id")
    public String roomId;

    @NonNull
    @ColumnInfo(name = "room_name")
    public String roomName;

    @NonNull
    @ColumnInfo(name = "room_key")
    public String roomKey;

    @NonNull
    @ColumnInfo(name = "room_password_hash")
    public String roomPasswordHash;

    @NonNull
    @ColumnInfo(name = "room_signing_secret")
    public String roomSigningSecret;

    @NonNull
    @ColumnInfo(name = "owner_user_id")
    public String ownerUserId;

    @NonNull
    @ColumnInfo(name = "owner_name")
    public String ownerName;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    @ColumnInfo(name = "room_salt")
    public String roomSalt;

    @ColumnInfo(name = "is_locked", defaultValue = "0")
    public boolean isLocked;

    @ColumnInfo(name = "require_approval", defaultValue = "1")
    public boolean requireApproval;

    @ColumnInfo(name = "key_expires_at", defaultValue = "0")
    public long keyExpiresAt;

    @ColumnInfo(name = "failed_join_count", defaultValue = "0")
    public int failedJoinCount;

    @ColumnInfo(name = "lockout_until", defaultValue = "0")
    public long lockoutUntil;

    @ColumnInfo(name = "is_cloud_room", defaultValue = "1")
    public boolean isCloudRoom;

    @NonNull
    @ColumnInfo(name = "cached_for_user_id", defaultValue = "''")
    public String cachedForUserId;

    public static final String DEFAULT_ENABLED_PAYMENT_MODES = "CASH,GCASH,MAYA,GOTYME,BANK";
    public static final String DEFAULT_RECEIPT_PREFIX = "RCP";

    @NonNull
    @ColumnInfo(name = "organization_subtitle", defaultValue = "''")
    public String organizationSubtitle = "";

    @NonNull
    @ColumnInfo(name = "receipt_prefix", defaultValue = "'RCP'")
    public String receiptPrefix = DEFAULT_RECEIPT_PREFIX;

    @NonNull
    @ColumnInfo(name = "receipt_footer_note", defaultValue = "''")
    public String receiptFooterNote = "";

    @NonNull
    @ColumnInfo(name = "enabled_payment_modes", defaultValue = "'CASH,GCASH,MAYA,GOTYME,BANK'")
    public String enabledPaymentModes = DEFAULT_ENABLED_PAYMENT_MODES;

    @ColumnInfo(name = "require_proof_photo_for_online", defaultValue = "0")
    public boolean requireProofPhotoForOnline = false;

    @NonNull
    @ColumnInfo(name = "default_join_role", defaultValue = "'AUDITOR'")
    public String defaultJoinRole = ROLE_AUDITOR;

    @ColumnInfo(name = "is_archived", defaultValue = "0")
    public boolean isArchived = false;

    @Ignore
    public RoomEntity(
            @NonNull String roomId,
            @NonNull String roomName,
            @NonNull String roomKey,
            @NonNull String roomPasswordHash,
            @NonNull String roomSigningSecret,
            @NonNull String ownerUserId,
            @NonNull String ownerName,
            long createdAt,
            long updatedAt
    ) {
        this(
                roomId,
                roomName,
                roomKey,
                roomPasswordHash,
                roomSigningSecret,
                ownerUserId,
                ownerName,
                createdAt,
                updatedAt,
                null,
                false,
                true,
                0L,
                0,
                0L
        );
    }

    public RoomEntity(
            @NonNull String roomId,
            @NonNull String roomName,
            @NonNull String roomKey,
            @NonNull String roomPasswordHash,
            @NonNull String roomSigningSecret,
            @NonNull String ownerUserId,
            @NonNull String ownerName,
            long createdAt,
            long updatedAt,
            String roomSalt,
            boolean isLocked,
            boolean requireApproval,
            long keyExpiresAt,
            int failedJoinCount,
            long lockoutUntil
    ) {
        this(
                roomId,
                roomName,
                roomKey,
                roomPasswordHash,
                roomSigningSecret,
                ownerUserId,
                ownerName,
                createdAt,
                updatedAt,
                roomSalt,
                isLocked,
                requireApproval,
                keyExpiresAt,
                failedJoinCount,
                lockoutUntil,
                true,
                ""
        );
    }

    @Ignore
    public RoomEntity(
            @NonNull String roomId,
            @NonNull String roomName,
            @NonNull String roomKey,
            @NonNull String roomPasswordHash,
            @NonNull String roomSigningSecret,
            @NonNull String ownerUserId,
            @NonNull String ownerName,
            long createdAt,
            long updatedAt,
            String roomSalt,
            boolean isLocked,
            boolean requireApproval,
            long keyExpiresAt,
            int failedJoinCount,
            long lockoutUntil,
            boolean isCloudRoom,
            String cachedForUserId
    ) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.roomKey = roomKey;
        this.roomPasswordHash = roomPasswordHash;
        this.roomSigningSecret = roomSigningSecret;
        this.ownerUserId = ownerUserId;
        this.ownerName = ownerName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.roomSalt = roomSalt;
        this.isLocked = isLocked;
        this.requireApproval = requireApproval;
        this.keyExpiresAt = keyExpiresAt;
        this.failedJoinCount = failedJoinCount;
        this.lockoutUntil = lockoutUntil;
        this.isCloudRoom = isCloudRoom;
        this.cachedForUserId = cachedForUserId != null ? cachedForUserId : "";
    }

    public boolean isCloudRoom() {
        return isCloudRoom;
    }

    public void setCloudRoom(boolean cloudRoom) {
        isCloudRoom = cloudRoom;
    }

    @NonNull
    public String getCachedForUserId() {
        return cachedForUserId;
    }

    public void setCachedForUserId(String cachedForUserId) {
        this.cachedForUserId = cachedForUserId != null ? cachedForUserId : "";
    }

    public boolean isKeyExpired(long nowMillis) {
        return keyExpiresAt > 0L && nowMillis > keyExpiresAt;
    }

    public boolean isPaymentModeEnabled(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return true;
        }
        String cleanMode = mode.trim().toUpperCase(java.util.Locale.US);
        if ("CASH".equals(cleanMode)) {
            if (enabledPaymentModes == null || enabledPaymentModes.trim().isEmpty()) {
                return true;
            }
        }
        String[] modes = (enabledPaymentModes != null ? enabledPaymentModes : DEFAULT_ENABLED_PAYMENT_MODES).split(",");
        for (String m : modes) {
            if (m.trim().equalsIgnoreCase(cleanMode)) {
                return true;
            }
        }
        return false;
    }

    public String getCleanReceiptPrefix() {
        if (receiptPrefix == null || receiptPrefix.trim().isEmpty()) {
            return DEFAULT_RECEIPT_PREFIX;
        }
        String clean = receiptPrefix.replaceAll("[^a-zA-Z0-9]", "").toUpperCase(java.util.Locale.US);
        if (clean.length() < 2) {
            return DEFAULT_RECEIPT_PREFIX;
        }
        if (clean.length() > 6) {
            return clean.substring(0, 6);
        }
        return clean;
    }
}
