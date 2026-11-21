package com.example.qr_paymentscan_cash_n_online.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "room_members",
        indices = {
                @Index(value = {"room_id", "user_id"}, unique = true),
                @Index(value = {"room_id"})
        }
)
public class RoomMemberEntity {

    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_COLLECTOR = "COLLECTOR";
    public static final String ROLE_AUDITOR = "AUDITOR";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_BANNED = "BANNED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "room_id")
    public String roomId;

    @NonNull
    @ColumnInfo(name = "user_id")
    public String userId;

    @NonNull
    @ColumnInfo(name = "user_name")
    public String userName;

    @NonNull
    @ColumnInfo(name = "user_email")
    public String userEmail;

    @NonNull
    @ColumnInfo(name = "role")
    public String role;

    @ColumnInfo(name = "joined_at")
    public long joinedAt;

    @NonNull
    @ColumnInfo(name = "status", defaultValue = "'ACTIVE'")
    public String status;

    @ColumnInfo(name = "device_info")
    public String deviceInfo;

    @Ignore
    public RoomMemberEntity(
            @NonNull String roomId,
            @NonNull String userId,
            @NonNull String userName,
            @NonNull String userEmail,
            @NonNull String role,
            long joinedAt
    ) {
        this(roomId, userId, userName, userEmail, role, joinedAt, STATUS_ACTIVE, null);
    }

    public RoomMemberEntity(
            @NonNull String roomId,
            @NonNull String userId,
            @NonNull String userName,
            @NonNull String userEmail,
            @NonNull String role,
            long joinedAt,
            @NonNull String status,
            String deviceInfo
    ) {
        this.roomId = roomId;
        this.userId = userId;
        this.userName = userName;
        this.userEmail = userEmail;
        this.role = role;
        this.joinedAt = joinedAt;
        this.status = (status != null && !status.trim().isEmpty()) ? status.trim() : STATUS_ACTIVE;
        this.deviceInfo = deviceInfo;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    public boolean isPendingApproval() {
        return STATUS_PENDING_APPROVAL.equalsIgnoreCase(status);
    }

    public boolean isBanned() {
        return STATUS_BANNED.equalsIgnoreCase(status);
    }
}
