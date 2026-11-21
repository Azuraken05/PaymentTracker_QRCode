package com.example.qr_paymentscan_cash_n_online.util;

import android.content.Context;
import androidx.annotation.Nullable;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class RoomPolicyGuard {

    private RoomPolicyGuard() {
        // Utility class
    }

    /**
     * Checks if write actions (adding payments, adding expenses, creating/editing folders)
     * are permitted in the specified room.
     * Blocks if the room is archived, or if the current user does not have write permissions.
     */
    public static boolean canWriteToRoom(Context context, @Nullable RoomEntity room) {
        if (room != null && room.isArchived) {
            return false;
        }
        return UserSessionManager.getInstance(context).canCreateOrEditRecords();
    }

    /**
     * Returns true if the given room is marked as read-only archived.
     */
    public static boolean isRoomArchived(@Nullable RoomEntity room) {
        return room != null && room.isArchived;
    }

    /**
     * Shows a modal alert explaining that the room is in read-only freeze.
     */
    public static void showArchivedRoomNotice(Context context) {
        new MaterialAlertDialogBuilder(context)
                .setTitle("This Room is Archived")
                .setMessage("This room is archived and read-only. No new payments, expenses, or folders can be added or edited, but all existing receipts, QR verification, and Excel exports remain fully accessible.")
                .setPositiveButton("Understood", null)
                .show();
    }
}
