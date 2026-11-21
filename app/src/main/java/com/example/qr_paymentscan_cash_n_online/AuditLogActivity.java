package com.example.qr_paymentscan_cash_n_online;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.qr_paymentscan_cash_n_online.data.AuditLogEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.ui.AuditLogAdapter;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AuditLogActivity extends BaseThemedActivity {

    public static final String EXTRA_SECTION_ID = "extra_section_id";
    public static final String EXTRA_SECTION_NAME = "extra_section_name";

    private final List<AuditLogEntity> allLogs = new ArrayList<>();
    private AuditLogAdapter adapter;
    private LinearLayout layoutEmptyAuditLogs;
    private MaterialButton btnFilterAuditByMember;
    private int currentFilterChipId = R.id.chipAuditAll;
    private String selectedMemberFilter = null; // null means All Members

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_audit_log);

        View rootView = findViewById(R.id.auditLogRoot);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        long sectionId = getIntent().getLongExtra(EXTRA_SECTION_ID, -1L);
        String sectionName = getIntent().getStringExtra(EXTRA_SECTION_NAME);

        ImageButton btnBackAudit = findViewById(R.id.btnBackAudit);
        TextView tvAuditTitle = findViewById(R.id.tvAuditTitle);
        TextView tvAuditSubtitle = findViewById(R.id.tvAuditSubtitle);
        ChipGroup chipGroupFilter = findViewById(R.id.chipGroupAuditFilter);
        btnFilterAuditByMember = findViewById(R.id.btnFilterAuditByMember);
        RecyclerView rvAuditLogs = findViewById(R.id.rvAuditLogs);
        layoutEmptyAuditLogs = findViewById(R.id.layoutEmptyAuditLogs);

        btnBackAudit.setOnClickListener(v -> finish());

        if (sectionId > 0 && sectionName != null) {
            tvAuditTitle.setText("Section Audit Trail • " + sectionName);
            tvAuditSubtitle.setText("Who did what in " + sectionName);
        }

        adapter = new AuditLogAdapter();
        rvAuditLogs.setLayoutManager(new LinearLayoutManager(this));
        rvAuditLogs.setAdapter(adapter);

        chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) {
                currentFilterChipId = checkedIds.get(0);
                applyFilter();
            }
        });

        btnFilterAuditByMember.setOnClickListener(v -> showMemberFilterDialog());

        PaymentRepository repository = new PaymentRepository(this);
        LiveData<List<AuditLogEntity>> liveData = (sectionId > 0)
                ? repository.observeAuditLogsBySection(sectionId)
                : repository.observeAllAuditLogs();

        liveData.observe(this, logs -> {
            allLogs.clear();
            if (logs != null) {
                allLogs.addAll(logs);
            }
            applyFilter();
        });
    }

    private void showMemberFilterDialog() {
        Set<String> distinctActors = new LinkedHashSet<>();
        for (AuditLogEntity log : allLogs) {
            if (log.actorName != null && !log.actorName.trim().isEmpty()) {
                distinctActors.add(log.actorName.trim());
            }
        }
        List<String> options = new ArrayList<>();
        options.add("All Members");
        options.addAll(distinctActors);

        String[] items = options.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Filter Audit Log by Member")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        selectedMemberFilter = null;
                        btnFilterAuditByMember.setText("All Members");
                    } else {
                        selectedMemberFilter = items[which];
                        btnFilterAuditByMember.setText(selectedMemberFilter);
                    }
                    applyFilter();
                })
                .show();
    }

    private void applyFilter() {
        List<AuditLogEntity> filtered = new ArrayList<>();
        for (AuditLogEntity log : allLogs) {
            if (selectedMemberFilter != null) {
                String actor = log.actorName != null ? log.actorName.trim() : "";
                if (!selectedMemberFilter.equalsIgnoreCase(actor)) {
                    continue;
                }
            }

            if (currentFilterChipId == R.id.chipAuditRecorded) {
                if (!AuditLogEntity.ACTION_PAYMENT_RECORDED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_DUPLICATE_PAYMENT_OVERRIDE.equals(log.actionType)) {
                    continue;
                }
            } else if (currentFilterChipId == R.id.chipAuditExpenses) {
                if (!AuditLogEntity.ACTION_EXPENSE_RECORDED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_EXPENSE_VOIDED.equals(log.actionType)) {
                    continue;
                }
            } else if (currentFilterChipId == R.id.chipAuditVoided) {
                if (!AuditLogEntity.ACTION_PAYMENT_VOIDED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_EXPENSE_VOIDED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_FOLDER_DELETED.equals(log.actionType)) {
                    continue;
                }
            } else if (currentFilterChipId == R.id.chipAuditFolders) {
                if (!AuditLogEntity.ACTION_FOLDER_CREATED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_FOLDER_RENAMED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_FOLDER_DELETED.equals(log.actionType)) {
                    continue;
                }
            } else if (currentFilterChipId == R.id.chipAuditMembers) {
                if (!AuditLogEntity.ACTION_ROOM_CREATED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_MEMBER_JOINED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_MEMBER_ROLE_CHANGED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_MEMBER_REMOVED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_SECURITY_MEMBER_APPROVED.equals(log.actionType)
                        && !AuditLogEntity.ACTION_SECURITY_MEMBER_BANNED.equals(log.actionType)) {
                    continue;
                }
            } else if (currentFilterChipId == R.id.chipAuditExports) {
                if (!AuditLogEntity.ACTION_EXCEL_EXPORTED.equals(log.actionType)) {
                    continue;
                }
            } else if (currentFilterChipId == R.id.chipAuditSecurity) {
                if (!log.isSecurityOrDuplicateAlert()) {
                    continue;
                }
            }
            filtered.add(log);
        }

        adapter.submitList(filtered);
        layoutEmptyAuditLogs.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
