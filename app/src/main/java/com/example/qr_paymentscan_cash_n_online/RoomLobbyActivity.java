package com.example.qr_paymentscan_cash_n_online;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.util.RoomSecurityHelper;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class RoomLobbyActivity extends BaseThemedActivity {

    private PaymentRepository repository;
    private UserSessionManager sessionManager;
    private TextView tvRoomLobbyUserSubtitle;
    private JoinedRoomsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room_lobby);

        repository = new PaymentRepository(this);
        sessionManager = UserSessionManager.getInstance(this);

        View root = findViewById(R.id.roomLobbyRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ImageButton btnBack = findViewById(R.id.btnBackRoomLobby);
        btnBack.setOnClickListener(v -> finish());

        tvRoomLobbyUserSubtitle = findViewById(R.id.tvRoomLobbyUserSubtitle);
        MaterialButton btnOpenAuthProfile = findViewById(R.id.btnOpenAuthProfile);
        MaterialButton btnCreatePrivateRoom = findViewById(R.id.btnCreatePrivateRoom);
        MaterialButton btnJoinPrivateRoom = findViewById(R.id.btnJoinPrivateRoom);
        RecyclerView rvJoinedRooms = findViewById(R.id.rvJoinedRooms);

        btnOpenAuthProfile.setOnClickListener(v ->
                startActivity(new Intent(this, AuthActivity.class)));
        btnCreatePrivateRoom.setOnClickListener(v -> showCreatePrivateRoomDialog());
        btnJoinPrivateRoom.setOnClickListener(v -> showJoinPrivateRoomDialog());

        adapter = new JoinedRoomsAdapter(room -> repository.switchActiveRoom(room.roomId, new PaymentRepository.ResultCallback<RoomEntity>() {
            @Override
            public void onSuccess(RoomEntity result) {
                adapter.notifyDataSetChanged();
                if (sessionManager.isPendingApproval()) {
                    showWaitingForOwnerApprovalDialog(result);
                } else {
                    Toast.makeText(
                            RoomLobbyActivity.this,
                            "Switched to room: " + result.roomName + " (" + sessionManager.getActiveRoomRole() + ")",
                            Toast.LENGTH_SHORT
                    ).show();
                    finish();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(RoomLobbyActivity.this, message, Toast.LENGTH_LONG).show();
            }
        }));
        rvJoinedRooms.setLayoutManager(new LinearLayoutManager(this));
        rvJoinedRooms.setAdapter(adapter);

        repository.observeAllRooms().observe(this, rooms -> {
            adapter.submitList(rooms != null ? rooms : new ArrayList<>());
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        String statusSuffix = sessionManager.isPendingApproval()
                ? " (PENDING OWNER APPROVAL)"
                : "";
        tvRoomLobbyUserSubtitle.setText("Officer: " + sessionManager.getCurrentUserName()
                + " • Active Role: " + sessionManager.getActiveRoomRole() + statusSuffix);
        adapter.notifyDataSetChanged();
    }

    private void showCreatePrivateRoomDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etRoomName = new EditText(this);
        etRoomName.setHint("Room Name (e.g., BSCS 3A Class Funds)");
        etRoomName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        container.addView(etRoomName);

        EditText etRoomPassword = new EditText(this);
        etRoomPassword.setHint("Strong Password (8+ chars, A-Z, a-z, 0-9)");
        etRoomPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = pad / 2;
        etRoomPassword.setLayoutParams(lp);
        container.addView(etRoomPassword);

        TextView tvStrength = new TextView(this);
        tvStrength.setText("Password Policy: Min 8 chars with uppercase, lowercase & number");
        tvStrength.setTextSize(12f);
        tvStrength.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        container.addView(tvStrength);

        etRoomPassword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String pass = s != null ? s.toString() : "";
                String err = RoomSecurityHelper.validatePasswordStrength(pass);
                String strength = RoomSecurityHelper.getPasswordStrengthLabel(pass);
                if (err != null) {
                    tvStrength.setText("Strength: " + strength + " • " + err);
                    tvStrength.setTextColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_void_red));
                } else {
                    tvStrength.setText("Strength: " + strength + " • PBKDF2-SHA256 Ready");
                    tvStrength.setTextColor(ContextCompat.getColor(RoomLobbyActivity.this, R.color.status_paid_green));
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        CheckBox cbRequireApproval = new CheckBox(this);
        cbRequireApproval.setText("Require Owner Approval before new members can view data");
        cbRequireApproval.setChecked(true);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cbLp.topMargin = pad / 2;
        cbRequireApproval.setLayoutParams(cbLp);
        container.addView(cbRequireApproval);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Create Hardened Private Room")
                .setMessage("Generates a unique Room Key (KEY-XXXXXX), 16-byte cryptographic salt (PBKDF2-SHA256), and 256-bit HMAC QR signing secret.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create Room", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etRoomName.getText().toString().trim();
            String pass = etRoomPassword.getText().toString().trim();
            boolean requireApproval = cbRequireApproval.isChecked();
            repository.createPrivateRoom(name, pass, requireApproval, new PaymentRepository.ResultCallback<RoomEntity>() {
                @Override
                public void onSuccess(RoomEntity room) {
                    dialog.dismiss();
                    new MaterialAlertDialogBuilder(RoomLobbyActivity.this)
                            .setTitle("Private Room Created!")
                            .setMessage("Room Name: " + room.roomName
                                    + "\nRoom Key: " + room.roomKey
                                    + "\nYour Role: OWNER"
                                    + "\nOwner Approval Gate: " + (room.requireApproval ? "ENABLED" : "OFF")
                                    + "\n\nShare the Room Key and Password with trusted collectors or auditors from the Members screen.")
                            .setPositiveButton("Open Workspace", (dlg, which) -> finish())
                            .show();
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(RoomLobbyActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }));

        dialog.show();
    }

    private void showJoinPrivateRoomDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etRoomKey = new EditText(this);
        etRoomKey.setHint("Room Key (e.g., KEY-4829AX)");
        etRoomKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        container.addView(etRoomKey);

        EditText etRoomPassword = new EditText(this);
        etRoomPassword.setHint("Room Password");
        etRoomPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = pad / 2;
        etRoomPassword.setLayoutParams(lp);
        container.addView(etRoomPassword);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Join Private Room")
                .setMessage("Enter both the Room Key and the Room Password shared by the Room Owner. Protected by brute-force lockout (5 attempts -> 15m cooldown).")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Verify & Join", (d, w) -> {
                    String key = etRoomKey.getText().toString().trim();
                    String pass = etRoomPassword.getText().toString().trim();
                    repository.joinPrivateRoomWithKeyAndPassword(key, pass, new PaymentRepository.ResultCallback<RoomEntity>() {
                        @Override
                        public void onSuccess(RoomEntity room) {
                            if (sessionManager.isPendingApproval()) {
                                showWaitingForOwnerApprovalDialog(room);
                            } else {
                                Toast.makeText(
                                        RoomLobbyActivity.this,
                                        "Joined \"" + room.roomName + "\" as " + sessionManager.getActiveRoomRole(),
                                        Toast.LENGTH_LONG
                                ).show();
                                finish();
                            }
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(RoomLobbyActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .show();
    }

    private void showWaitingForOwnerApprovalDialog(RoomEntity room) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Waiting for Owner Approval")
                .setMessage("Your password was verified for \"" + room.roomName + "\", and your join request has been sent to Room Owner (" + room.ownerName + ").\n\n"
                        + "For room security, folders, balances, student receipts, and QR signing keys remain locked until the Owner approves your membership in the Room Members screen.")
                .setPositiveButton("OK", null)
                .show();
    }

    private class JoinedRoomsAdapter extends RecyclerView.Adapter<JoinedRoomsAdapter.RoomViewHolder> {

        private final List<RoomEntity> items = new ArrayList<>();
        private final OnRoomClickListener listener;

        JoinedRoomsAdapter(OnRoomClickListener listener) {
            this.listener = listener;
        }

        void submitList(List<RoomEntity> newList) {
            items.clear();
            items.addAll(newList);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RoomViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_room_card, parent, false);
            return new RoomViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RoomViewHolder holder, int position) {
            RoomEntity room = items.get(position);
            holder.tvName.setText(room.roomName);
            String lockStatus = room.isLocked ? " • LOCKED" : "";
            holder.tvKeyAndOwner.setText("Room Key: " + room.roomKey + " • Owner: " + room.ownerName + lockStatus);

            boolean isActive = room.roomId.equals(sessionManager.getActiveRoomId());
            if (isActive) {
                if (sessionManager.isPendingApproval()) {
                    holder.tvBadge.setText("WAITING FOR OWNER APPROVAL");
                } else {
                    holder.tvBadge.setText("ACTIVE • " + sessionManager.getActiveRoomRole());
                }
                holder.tvBadge.setVisibility(View.VISIBLE);
            } else {
                holder.tvBadge.setText("TAP TO SWITCH");
                holder.tvBadge.setVisibility(View.VISIBLE);
            }
            holder.itemView.setOnClickListener(v -> listener.onRoomClicked(room));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class RoomViewHolder extends RecyclerView.ViewHolder {
            final TextView tvName;
            final TextView tvKeyAndOwner;
            final TextView tvBadge;

            RoomViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvItemRoomName);
                tvKeyAndOwner = itemView.findViewById(R.id.tvItemRoomKeyAndOwner);
                tvBadge = itemView.findViewById(R.id.tvItemRoomActiveBadge);
            }
        }
    }

    private interface OnRoomClickListener {
        void onRoomClicked(RoomEntity room);
    }
}
