package com.example.qr_paymentscan_cash_n_online.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(
        entities = {
                RoomEntity.class,
                RoomMemberEntity.class,
                SectionEntity.class,
                PaymentEntity.class,
                ExpenseEntity.class,
                RosterStudentEntity.class,
                AuditLogEntity.class
        },
        version = 8,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;
    public static final ExecutorService DB_EXECUTOR = Executors.newFixedThreadPool(4);

    public abstract RoomDao roomDao();

    public abstract RoomMemberDao roomMemberDao();

    public abstract SectionDao sectionDao();

    public abstract PaymentDao paymentDao();

    public abstract ExpenseDao expenseDao();

    public abstract RosterStudentDao rosterStudentDao();

    public abstract AuditLogDao auditLogDao();

    /**
     * Explicit Non-Destructive Room Migration from version 1 to version 2.
     * Preserves 100% of existing local sections, student payment receipts, QR signatures, and audit logs.
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE sections ADD COLUMN parent_folder_id INTEGER DEFAULT NULL");
            db.execSQL("ALTER TABLE sections ADD COLUMN target_fee_amount REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sections ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'PENDING'");
            db.execSQL("ALTER TABLE sections ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");

            db.execSQL("ALTER TABLE payments ADD COLUMN student_number TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE payments ADD COLUMN remote_proof_url TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE payments ADD COLUMN payment_status TEXT NOT NULL DEFAULT 'PAID'");
            db.execSQL("ALTER TABLE payments ADD COLUMN remaining_balance REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE payments ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'PENDING'");
            db.execSQL("ALTER TABLE payments ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");

            db.execSQL("ALTER TABLE audit_logs ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'PENDING'");
            db.execSQL("ALTER TABLE audit_logs ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");

            db.execSQL("CREATE TABLE IF NOT EXISTS `expenses` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`section_id` INTEGER NOT NULL, " +
                    "`section_name` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`amount` REAL NOT NULL, " +
                    "`category` TEXT NOT NULL, " +
                    "`notes` TEXT, " +
                    "`proof_image_path` TEXT, " +
                    "`remote_proof_url` TEXT, " +
                    "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "`void_reason` TEXT, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`voided_at` INTEGER, " +
                    "`sync_status` TEXT NOT NULL DEFAULT 'PENDING', " +
                    "`updated_at` INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_section_id` ON `expenses` (`section_id`)");

            db.execSQL("CREATE TABLE IF NOT EXISTS `roster_students` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`section_id` INTEGER NOT NULL, " +
                    "`first_name` TEXT NOT NULL, " +
                    "`last_name` TEXT NOT NULL, " +
                    "`student_number` TEXT, " +
                    "`sync_status` TEXT NOT NULL DEFAULT 'PENDING', " +
                    "`updated_at` INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_roster_students_section_id` ON `roster_students` (`section_id`)");
        }
    };

    /**
     * Explicit Non-Destructive Room Migration from version 2 to version 3.
     * Adds Private Rooms, Room Members, room_id scoping, and Actor attribution columns
     * while preserving 100% of existing local records.
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // 1. Create rooms table
            db.execSQL("CREATE TABLE IF NOT EXISTS `rooms` (" +
                    "`room_id` TEXT NOT NULL, " +
                    "`room_name` TEXT NOT NULL, " +
                    "`room_key` TEXT NOT NULL, " +
                    "`room_password_hash` TEXT NOT NULL, " +
                    "`room_signing_secret` TEXT NOT NULL, " +
                    "`owner_user_id` TEXT NOT NULL, " +
                    "`owner_name` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`room_id`))");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_rooms_room_key` ON `rooms` (`room_key`)");

            // 2. Create room_members table
            db.execSQL("CREATE TABLE IF NOT EXISTS `room_members` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`room_id` TEXT NOT NULL, " +
                    "`user_id` TEXT NOT NULL, " +
                    "`user_name` TEXT NOT NULL, " +
                    "`user_email` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`joined_at` INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_room_members_room_id_user_id` ON `room_members` (`room_id`, `user_id`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_room_members_room_id` ON `room_members` (`room_id`)");

            // 3. Add room_id to sections
            db.execSQL("ALTER TABLE sections ADD COLUMN room_id TEXT DEFAULT NULL");

            // 4. Add room_id and actor attribution to payments
            db.execSQL("ALTER TABLE payments ADD COLUMN room_id TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE payments ADD COLUMN actor_user_id TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE payments ADD COLUMN actor_name TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE payments ADD COLUMN actor_role TEXT DEFAULT NULL");

            // 5. Add room_id and actor attribution to expenses
            db.execSQL("ALTER TABLE expenses ADD COLUMN room_id TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE expenses ADD COLUMN actor_user_id TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE expenses ADD COLUMN actor_name TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE expenses ADD COLUMN actor_role TEXT DEFAULT NULL");

            // 6. Add room_id to roster_students
            db.execSQL("ALTER TABLE roster_students ADD COLUMN room_id TEXT DEFAULT NULL");

            // 7. Add room_id and actor attribution to audit_logs
            db.execSQL("ALTER TABLE audit_logs ADD COLUMN room_id TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE audit_logs ADD COLUMN actor_user_id TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE audit_logs ADD COLUMN actor_name TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE audit_logs ADD COLUMN actor_role TEXT DEFAULT NULL");
        }
    };

    /**
     * Explicit Non-Destructive Room Migration from version 3 to version 4.
     * Adds PBKDF2 salt, room lock/approval/expiration/brute-force state, member approval/ban status,
     * and student duplicate payment prevention columns while preserving 100% of existing local records.
     */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // 1. Add security hardening columns to rooms
            db.execSQL("ALTER TABLE rooms ADD COLUMN room_salt TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE rooms ADD COLUMN is_locked INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE rooms ADD COLUMN require_approval INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE rooms ADD COLUMN key_expires_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE rooms ADD COLUMN failed_join_count INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE rooms ADD COLUMN lockout_until INTEGER NOT NULL DEFAULT 0");

            // 2. Add member status and device telemetry columns to room_members
            db.execSQL("ALTER TABLE room_members ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'");
            db.execSQL("ALTER TABLE room_members ADD COLUMN device_info TEXT DEFAULT NULL");

            // 3. Add duplicate payment guard columns and composite index to payments
            db.execSQL("ALTER TABLE payments ADD COLUMN student_identity_key TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE payments ADD COLUMN duplicate_override_reason TEXT DEFAULT NULL");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_section_id_student_identity_key` ON `payments` (`section_id`, `student_identity_key`)");

            // Backfill student_identity_key for existing payment rows
            db.execSQL("UPDATE payments SET student_identity_key = CAST(section_id AS TEXT) || '|NAME|' || LOWER(TRIM(first_name)) || ' ' || LOWER(TRIM(last_name)) WHERE student_identity_key IS NULL");
        }
    };

    /**
     * Explicit Non-Destructive Room Migration from version 4 to version 5.
     * Adds Cloudinary online image storage URLs/sync status, and receipt editing audit metadata
     * (last_edited_by_name, last_edited_at) while preserving 100% of existing local data.
     */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // 1. Add Cloudinary image storage and editing tracking columns to payments
            db.execSQL("ALTER TABLE payments ADD COLUMN proof_image_url TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE payments ADD COLUMN image_sync_status TEXT NOT NULL DEFAULT 'NONE'");
            db.execSQL("ALTER TABLE payments ADD COLUMN last_edited_by_name TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE payments ADD COLUMN last_edited_at INTEGER NOT NULL DEFAULT 0");

            // 2. Add Cloudinary image storage columns to expenses
            db.execSQL("ALTER TABLE expenses ADD COLUMN proof_image_url TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE expenses ADD COLUMN image_sync_status TEXT NOT NULL DEFAULT 'NONE'");
        }
    };

    /**
     * Explicit Non-Destructive Room Migration from version 5 to version 6.
     * Migrates room_members to use composite primary key (room_id, user_id)
     * while preserving 100% of existing local memberships.
     */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `room_members_new` (" +
                    "`room_id` TEXT NOT NULL, " +
                    "`user_id` TEXT NOT NULL, " +
                    "`user_name` TEXT NOT NULL, " +
                    "`user_email` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`joined_at` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "`device_info` TEXT, " +
                    "PRIMARY KEY(`room_id`, `user_id`))");

            db.execSQL("INSERT OR REPLACE INTO `room_members_new` " +
                    "(`room_id`, `user_id`, `user_name`, `user_email`, `role`, `joined_at`, `status`, `device_info`) " +
                    "SELECT `room_id`, `user_id`, `user_name`, `user_email`, `role`, `joined_at`, `status`, `device_info` " +
                    "FROM `room_members`");

            db.execSQL("DROP TABLE `room_members`");
            db.execSQL("ALTER TABLE `room_members_new` RENAME TO `room_members`");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_room_members_room_id` ON `room_members` (`room_id`)");
        }
    };

    /**
     * Explicit Non-Destructive Room Migration from version 6 to version 7.
     * Adds is_cloud_room and cached_for_user_id columns to rooms table
     * while preserving 100% of existing local records.
     */
    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE rooms ADD COLUMN is_cloud_room INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE rooms ADD COLUMN cached_for_user_id TEXT NOT NULL DEFAULT ''");
        }
    };

    /**
     * Explicit Non-Destructive Room Migration from version 7 to version 8.
     * Adds merge_gross_to_parent column to sections table
     * while preserving 100% of existing local records.
     */
    public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE sections ADD COLUMN merge_gross_to_parent INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static AppDatabase getInstance(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "payment_tracker_db"
                            )
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
