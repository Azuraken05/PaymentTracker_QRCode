package com.example.qr_paymentscan_cash_n_online.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "roster_students",
        indices = {@Index(value = {"section_id"})}
)
public class RosterStudentEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "section_id")
    public long sectionId;

    @NonNull
    @ColumnInfo(name = "first_name")
    public String firstName;

    @NonNull
    @ColumnInfo(name = "last_name")
    public String lastName;

    @ColumnInfo(name = "student_number")
    public String studentNumber;

    @NonNull
    @ColumnInfo(name = "sync_status", defaultValue = "'PENDING'")
    public String syncStatus;

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    public long updatedAt;

    @ColumnInfo(name = "room_id")
    public String roomId;

    @Ignore
    public RosterStudentEntity(
            long sectionId,
            @NonNull String firstName,
            @NonNull String lastName,
            String studentNumber,
            @NonNull String syncStatus,
            long updatedAt
    ) {
        this(sectionId, firstName, lastName, studentNumber, syncStatus, updatedAt, null);
    }

    public RosterStudentEntity(
            long sectionId,
            @NonNull String firstName,
            @NonNull String lastName,
            String studentNumber,
            @NonNull String syncStatus,
            long updatedAt,
            String roomId
    ) {
        this.sectionId = sectionId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.studentNumber = studentNumber;
        this.syncStatus = syncStatus;
        this.updatedAt = updatedAt;
        this.roomId = roomId;
    }
}
