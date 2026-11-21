package com.example.qr_paymentscan_cash_n_online;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.data.RoomMemberEntity;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class RoomMembersActivity extends BaseThemedActivity {

    private PaymentRepository repository;
    private UserSessionManager sessionManager;
    private String activeRoomId;
    private RoomEntity currentRoom;

    private TextView tvMembersRoomTitle;
    private TextView tvMembersMyRoleSubtitle;
    private TextView tvInviteRoomKey;
    private TextView tvRoomSecurityStatusBanner;
    private MaterialButton btnChangeRoomPassword;
    private MembersAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room_members);

        repository = new PaymentRepository(this);
        sessionManager = UserSessionManager.getInstance(this);
        activeRoomId = sessionManager.getActiveRoomId();

        View root = findViewById(R.id.roomMembersRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ImageButton btnBack = findViewById(R.id.btnBackRoomMembers);
        btnBack.setOnClickListener(v -> finish());

        tvMembersRoomTitle = findViewById(R.id.tvMembersRoomTitle);
        tvMembersMyRoleSubtitle = findViewById(R.id.tvMembersMyRoleSubtitle);
        tvInviteRoomKey = findViewById(R.id.tvInviteRoomKey);
        tvRoomSecurityStatusBanner = findViewById(R.id.tvRoomSecurityStatusBanner);
        btnChangeRoomPassword = findViewById(R.id.btnChangeRoomPassword);
        MaterialButton btnCopyInviteKey = findViewById(R.id.btnCopyInviteKey);
        RecyclerView rvRoomMembers = findViewById(R.id.rvRoomMembers);

        refreshRoomHeader();

        btnCopyInviteKey.setOnClickListener(v -> copyAndShareInviteKey());
        btnChangeRoomPassword.setOnClickListener(v -> showSecurityConsoleDialog());

        adapter = new MembersAdapter(this::showMemberRoleActionDialog);
        rvRoomMembers.setLayoutManager(new LinearLayoutManager(this));
        rvRoomMembers.setAdapter(adapter);

        if (activeRoomId != null && !activeRoomId.isEmpty()) {
            repository.observeRoomById(activeRoomId).observe(this, room -> {
                currentRoom = room;
                refreshRoomHeader();
            });
            repository.observeMembersByRoom(activeRoomId).observe(this, members -> {
                adapter.submitList(members != null ? members : new ArrayList<>());
                refreshRoomHeader();
            });
        }
    }

    private void refreshRoomHeader() {
        tvMembersRoomTitle.setText(sessionManager.getActiveRoomName());
        String role = sessionManager.getActiveRoomRole();
        String roleDesc;
        if (RoomMemberEntity.ROLE_OWNER.equalsIgnoreCase(role)) {
            roleDesc = "OWNER (Full Access • Security Console & Approvals)";
        } else if (RoomMemberEntity.ROLE_COLLECTOR.equalsIgnoreCase(role)) {
            roleDesc = "COLLECTOR (Record Payments, Expenses & Rename Folders)";
        } else {
            roleDesc = "AUDITOR (Read-Only Transparency & QR Verification)";
        }
        tvMembersMyRoleSubtitle.setText("Your Role: " + roleDesc);
        tvInviteRoomKey.setText(currentRoom != null ? currentRoom.roomKey : sessionManager.getActiveRoomKey());
        btnChangeRoomPassword.setVisibility(sessionManager.isOwner() ? View.VISIBLE : View.GONE);

        if (tvRoomSecurityStatusBanner != null) {
            if (currentRoom != null) {
                long now = System.currentTimeMillis();
                String lockText = currentRoom.isLocked ? "LOCKED (New Joins Blocked)" : "OPEN";
                String approvalText = currentRoom.requireApproval ? "Approval Gate: ON" : "Approval Gate: OFF";
                String expText;
                if (currentRoom.keyExpiresAt <= 0L) {
                    expText = "Invite Key: No Expiration";
                } else if (currentRoom.isKeyExpired(now)) {
                    expText = "Invite Key: EXPIRED";
                } else {
                    expText = "Expires: " + CurrencyFormatter.formatDateTime(currentRoom.keyExpiresAt);
                }
                tvRoomSecurityStatusBanner.setText("Room: " + lockText + " • " + approvalText + " • " + expText);
                if (currentRoom.isLocked || currentRoom.isKeyExpired(now)) {
                    tvRoomSecurityStatusBanner.setBackgroundColor(Color.parseColor("#FEE2E2"));
                    tvRoomSecurityStatusBanner.setTextColor(Color.parseColor("#B91C1C"));
                } else {
                    tvRoomSecurityStatusBanner.setBackgroundColor(Color.parseColor("#F0F5FF"));
                    tvRoomSecurityStatusBanner.setTextColor(Color.parseColor("#1E3A8A"));
                }
            }
        }
    }

    private void copyAndShareInviteKey() {
        String roomName = sessionManager.getActiveRoomName();
        String roomKey = currentRoom != null ? currentRoom.roomKey : sessionManager.getActiveRoomKey();
        String inviteText = "Join Private Room \"" + roomName + "\" on Student QR Payment Tracker\n"
                + "Room Key: " + roomKey + "\n"
                + "(Ask the Room Owner for the Room Password)";

        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Room Invite Key", roomKey));
        }
        Toast.makeText(this, "Copied Room Key: " + roomKey, Toast.LENGTH_SHORT).show();

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Invite to " + roomName);
        shareIntent.putExtra(Intent.EXTRA_TEXT, inviteText);
        startActivity(Intent.createChooser(shareIntent, "Share Room Invite Key"));
    }

    /**
     * Owner Security & Anti-Hacking Console:
     * 1. One-tap Emergency Room Lock / Unlock
     * 2. Toggle Owner Approval Gate (Waiting Room)
     * 3. Set Invite Key Expiration Timer (No Expiration, 1 Hour, 24 Hours, 7 Days)
     * 4. Change Room Password (PBKDF2WithHmacSHA256)
     * 5. Rotate Room Key & Password (Invalidates leaked invite keys)
     * 6. Rotate 256-bit HMAC QR Signing Secret
     */
    private void showSecurityConsoleDialog() {
        if (!sessionManager.isOwner()) {
            Toast.makeText(this, "Only the Room OWNER can access the Security Console.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean isLocked = currentRoom != null && currentRoom.isLocked;
        boolean requireApproval = currentRoom == null || currentRoom.requireApproval;

        String lockOption = isLocked
                ? "Unlock Room (Allow New Officers to Join)"
                : "Emergency Lock Room (Block All New Joins)";
        String approvalOption = requireApproval
                ? "Owner Approval Gate: ON (Tap to Disable Waiting Room)"
                : "Owner Approval Gate: OFF (Tap to Require Owner Approval)";

        String[] options = new String[]{
                lockOption,
                approvalOption,
                "Set Invite Key Expiration Timer (1h / 24h / 7d)",
                "Change Room Password (PBKDF2-SHA256)",
                "Rotate Room Key & Password (Invalidate Old Key)",
                "Rotate 256-bit QR Signing Secret"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Room Security & Anti-Hacking Console")
                .setItems(options, (dialog, which) -> {
                    long currentExp = currentRoom != null ? currentRoom.keyExpiresAt : 0L;
                    if (which == 0) {
                        updateSecurityFlags(!isLocked, requireApproval, currentExp);
                    } else if (which == 1) {
                        updateSecurityFlags(isLocked, !requireApproval, currentExp);
                    } else if (which == 2) {
                        showInviteExpirationPickerDialog(isLocked, requireApproval);
                    } else if (which == 3) {
                        showChangePasswordDialog();
                    } else if (which == 4) {
                        showRotateRoomKeyAndPasswordDialog(currentExp);
                    } else if (which == 5) {
                        confirmRotateSigningSecret();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void updateSecurityFlags(boolean isLocked, boolean requireApproval, long keyExpiresAt) {
        repository.updateRoomSecuritySettings(
                activeRoomId,
                isLocked,
                requireApproval,
                keyExpiresAt,
                new PaymentRepository.ResultCallback<RoomEntity>() {
                    @Override
                    public void onSuccess(RoomEntity result) {
                        currentRoom = result;
                        refreshRoomHeader();
                        Toast.makeText(
                                RoomMembersActivity.this,
                                "Room security settings updated.",
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(RoomMembersActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void showInviteExpirationPickerDialog(boolean isLocked, boolean requireApproval) {
        String[] durations = new String[]{
                "No Expiration (Permanent until rotated)",
                "Expire in 1 Hour",
                "Expire in 24 Hours",
                "Expire in 7 Days"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Set Invite Key Expiration")
                .setItems(durations, (d, which) -> {
                    long now = System.currentTimeMillis();
                    long expiresAt = 0L;
                    if (which == 1) {
                        expiresAt = now + 60L * 60L * 1000L;
                    } else if (which == 2) {
                        expiresAt = now + 24L * 60L * 60L * 1000L;
                    } else if (which == 3) {
                        expiresAt = now + 7L * 24L * 60L * 60L * 1000L;
                    }
                    updateSecurityFlags(isLocked, requireApproval, expiresAt);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showChangePasswordDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etNewPass = new EditText(this);
        etNewPass.setHint("New Strong Password (8+ chars, A-Z, a-z, 0-9)");
        etNewPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        container.addView(etNewPass);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Change Room Password")
                .setMessage("Updates the room password with a fresh 16-byte cryptographic salt and 65,536-iteration PBKDF2-SHA256 hash.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Update Password", (d, w) -> {
                    String newPass = etNewPass.getText().toString().trim();
                    repository.changeRoomPassword(activeRoomId, newPass, new PaymentRepository.ResultCallback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            Toast.makeText(RoomMembersActivity.this, "Room Password updated (PBKDF2-SHA256).", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(RoomMembersActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .show();
    }

    private void showRotateRoomKeyAndPasswordDialog(long keyExpiresAt) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etNewPass = new EditText(this);
        etNewPass.setHint("New Strong Password (8+ chars, A-Z, a-z, 0-9)");
        etNewPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        container.addView(etNewPass);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Rotate Room Key & Password")
                .setMessage("Generates a brand-new Room Key (KEY-XXXXXX) and salted PBKDF2 password hash. Anyone holding the old Room Key will no longer be able to join. Existing approved members remain in the room.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rotate Credentials", (d, w) -> {
                    String newPass = etNewPass.getText().toString().trim();
                    repository.rotateRoomKeyAndPassword(activeRoomId, newPass, keyExpiresAt, new PaymentRepository.ResultCallback<RoomEntity>() {
                        @Override
                        public void onSuccess(RoomEntity result) {
                            currentRoom = result;
                            refreshRoomHeader();
                            Toast.makeText(
                                    RoomMembersActivity.this,
                                    "New Room Key Generated: " + result.roomKey,
                                    Toast.LENGTH_LONG
                            ).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(RoomMembersActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .show();
    }

    private void confirmRotateSigningSecret() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Rotate 256-bit QR Signing Secret?")
                .setMessage("Generates a new 256-bit HMAC-SHA256 signing secret for future QR payment receipts issued in this room and syncs it to active room members.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rotate Secret", (d, w) -> repository.rotateRoomSigningSecret(
                        activeRoomId,
                        new PaymentRepository.ResultCallback<RoomEntity>() {
                            @Override
                            public void onSuccess(RoomEntity result) {
                                currentRoom = result;
                                Toast.makeText(
                                        RoomMembersActivity.this,
                                        "Rotated 256-bit QR Signing Secret.",
                                        Toast.LENGTH_SHORT
                                ).show();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(RoomMembersActivity.this, message, Toast.LENGTH_LONG).show();
                            }
                        }
                ))
                .show();
    }

    private void showMemberRoleActionDialog(RoomMemberEntity member) {
        if (!sessionManager.isOwner()) {
            Toast.makeText(this, "Only the Room OWNER can manage members and approvals.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (member.userId.equals(sessionManager.getCurrentUserId())) {
            Toast.makeText(this, "You are the Room OWNER of this workspace.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (member.isPendingApproval()) {
            String[] pendingOptions = new String[]{
                    "Approve as COLLECTOR (Can record payments/expenses)",
                    "Approve as AUDITOR (Read-only transparency & QR verification)",
                    "Reject Join Request (Remove from Waiting Room)",
                    "Ban User Permanently from Room"
            };
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Pending Approval: " + member.userName)
                    .setItems(pendingOptions, (dialog, which) -> {
                        if (which == 0) {
                            approveMember(member, RoomMemberEntity.ROLE_COLLECTOR);
                        } else if (which == 1) {
                            approveMember(member, RoomMemberEntity.ROLE_AUDITOR);
                        } else if (which == 2) {
                            rejectOrBanMember(member, false);
                        } else if (which == 3) {
                            rejectOrBanMember(member, true);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        String[] options = new String[]{
                "Set as COLLECTOR (Can record payments/expenses & rename folders)",
                "Set as AUDITOR (Read-only transparency & QR verification)",
                "Promote to OWNER (Full admin access)",
                "Remove Member from Room",
                "Ban Member Permanently (Revoke & Block Future Joins)"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Manage Member: " + member.userName)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        changeRole(member, RoomMemberEntity.ROLE_COLLECTOR);
                    } else if (which == 1) {
                        changeRole(member, RoomMemberEntity.ROLE_AUDITOR);
                    } else if (which == 2) {
                        changeRole(member, RoomMemberEntity.ROLE_OWNER);
                    } else if (which == 3) {
                        rejectOrBanMember(member, false);
                    } else if (which == 4) {
                        rejectOrBanMember(member, true);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void approveMember(RoomMemberEntity member, String role) {
        repository.approvePendingMember(
                activeRoomId,
                member.userId,
                member.userName,
                role,
                new PaymentRepository.ResultCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        Toast.makeText(
                                RoomMembersActivity.this,
                                "Approved " + member.userName + " as " + role,
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(RoomMembersActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void changeRole(RoomMemberEntity member, String newRole) {
        repository.updateMemberRole(
                activeRoomId,
                member.userId,
                member.userName,
                newRole,
                new PaymentRepository.ResultCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        Toast.makeText(
                                RoomMembersActivity.this,
                                member.userName + " is now " + newRole,
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(RoomMembersActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void rejectOrBanMember(RoomMemberEntity member, boolean banPermanently) {
        String title = banPermanently ? "Ban Member Permanently?" : "Remove Member?";
        String msg = banPermanently
                ? "Ban " + member.userName + " from \"" + sessionManager.getActiveRoomName() + "\"? Their active session and cached QR signing secret will be revoked immediately, and future join attempts will be blocked."
                : "Remove " + member.userName + " from \"" + sessionManager.getActiveRoomName() + "\"?";
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(banPermanently ? "Ban Permanently" : "Remove", (d, w) -> repository.rejectOrBanMember(
                        activeRoomId,
                        member.userId,
                        member.userName,
                        banPermanently,
                        new PaymentRepository.ResultCallback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                Toast.makeText(
                                        RoomMembersActivity.this,
                                        (banPermanently ? "Banned " : "Removed ") + member.userName,
                                        Toast.LENGTH_SHORT
                                ).show();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(RoomMembersActivity.this, message, Toast.LENGTH_LONG).show();
                            }
                        }
                ))
                .show();
    }

    private static class MembersAdapter extends RecyclerView.Adapter<MembersAdapter.MemberViewHolder> {

        private final List<RoomMemberEntity> items = new ArrayList<>();
        private final OnMemberClickListener listener;

        MembersAdapter(OnMemberClickListener listener) {
            this.listener = listener;
        }

        void submitList(List<RoomMemberEntity> newList) {
            items.clear();
            items.addAll(newList);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public MemberViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_room_member, parent, false);
            return new MemberViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull MemberViewHolder holder, int position) {
            RoomMemberEntity m = items.get(position);
            holder.tvName.setText(m.userName);
            String emailPart = (m.userEmail != null && !m.userEmail.isEmpty()) ? m.userEmail + " • " : "";
            String devicePart = (m.deviceInfo != null && !m.deviceInfo.trim().isEmpty())
                    ? m.deviceInfo.trim() + " • "
                    : "";
            holder.tvEmailAndDate.setText(emailPart + devicePart + "Joined " + CurrencyFormatter.formatShortDate(m.joinedAt));

            if (m.isPendingApproval()) {
                holder.tvRoleBadge.setText("PENDING APPROVAL");
                holder.tvRoleBadge.setTextColor(Color.parseColor("#B45309"));
                holder.tvRoleBadge.setBackgroundColor(Color.parseColor("#FEF3C7"));
            } else if (m.isBanned()) {
                holder.tvRoleBadge.setText("BANNED");
                holder.tvRoleBadge.setTextColor(Color.parseColor("#B91C1C"));
                holder.tvRoleBadge.setBackgroundColor(Color.parseColor("#FEE2E2"));
            } else {
                holder.tvRoleBadge.setText(m.role);
                holder.tvRoleBadge.setTextColor(Color.parseColor("#1E3A8A"));
                holder.tvRoleBadge.setBackgroundColor(Color.parseColor("#E8F0FE"));
            }
            holder.itemView.setOnClickListener(v -> listener.onMemberClicked(m));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class MemberViewHolder extends RecyclerView.ViewHolder {
            final TextView tvName;
            final TextView tvEmailAndDate;
            final TextView tvRoleBadge;

            MemberViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvMemberName);
                tvEmailAndDate = itemView.findViewById(R.id.tvMemberEmailAndDate);
                tvRoleBadge = itemView.findViewById(R.id.tvMemberRoleBadge);
            }
        }
    }

    private interface OnMemberClickListener {
        void onMemberClicked(RoomMemberEntity member);
    }
}
