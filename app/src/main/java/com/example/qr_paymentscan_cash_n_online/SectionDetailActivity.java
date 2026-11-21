package com.example.qr_paymentscan_cash_n_online;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.AuditLogEntity;
import com.example.qr_paymentscan_cash_n_online.data.ExpenseEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.data.SectionEntity;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.ui.ExpenseAdapter;
import com.example.qr_paymentscan_cash_n_online.ui.PaymentAdapter;
import com.example.qr_paymentscan_cash_n_online.ui.ReceiptVerifierBottomSheet;
import com.example.qr_paymentscan_cash_n_online.ui.SectionAdapter;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.LocalStorageManager;
import com.example.qr_paymentscan_cash_n_online.util.SecuritySignatureHelper;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SectionDetailActivity extends BaseThemedActivity {

    public static final String EXTRA_SECTION_ID = "extra_section_id";
    public static final String EXTRA_SECTION_NAME = "extra_section_name";

    private long sectionId;
    private String sectionName = "";
    private PaymentRepository repository;
    private UserSessionManager sessionManager;
    private SectionAdapter subfolderAdapter;
    private PaymentAdapter paymentAdapter;
    private ExpenseAdapter expenseAdapter;

    private final List<PaymentEntity> allSectionPayments = new ArrayList<>();
    private final List<ExpenseEntity> allSectionExpenses = new ArrayList<>();
    private String currentSearchQuery = "";
    private int currentFilterChipId = R.id.chipFilterAll;
    private boolean showingExpensesTab = false;

    private double directGrossCollected = 0.0;
    private double directActiveExpenses = 0.0;

    private View rootView;
    private TextView tvSectionTitle;
    private TextView tvSectionSubtitle;
    private LinearLayout layoutBreadcrumbContainer;
    private ImageButton btnRenameSectionTop;
    private ImageButton btnDeleteSectionTop;
    private MaterialButton btnActionNewPayment;
    private MaterialButton btnActionAddExpense;
    private MaterialButton btnNewSubfolder;

    private TextView tvSectionNetBalance;
    private TextView tvCombinedTreeSubline;
    private TextView tvSectionGrossCollected;
    private TextView tvSectionExpensesTotal;
    private TextView tvSectionValidCount;

    private TextView tvSubfoldersHeader;
    private RecyclerView rvSubfolders;
    private RecyclerView rvPayments;
    private RecyclerView rvExpenses;
    private HorizontalScrollView scrollPaymentFilterChips;
    private LinearLayout layoutEmptyPayments;
    private TextView tvEmptySectionListTitle;
    private TextView tvEmptySectionListSubtitle;
    private ExtendedFloatingActionButton fabNewPayment;

    private final ActivityResultLauncher<ScanOptions> qrCameraScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    ReceiptVerifierBottomSheet.newInstance(result.getContents())
                            .show(getSupportFragmentManager(), "ReceiptVerifierBottomSheet");
                }
            });

    private final ActivityResultLauncher<String> qrGalleryImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    decodeQrFromGalleryUri(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_section_detail);

        rootView = findViewById(R.id.sectionDetailRoot);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sectionId = getIntent().getLongExtra(EXTRA_SECTION_ID, -1L);
        sectionName = getIntent().getStringExtra(EXTRA_SECTION_NAME);
        if (sectionName == null) {
            sectionName = "Section";
        }

        repository = new PaymentRepository(this);
        sessionManager = UserSessionManager.getInstance(this);

        ImageButton btnBackSection = findViewById(R.id.btnBackSection);
        btnRenameSectionTop = findViewById(R.id.btnRenameSectionTop);
        btnDeleteSectionTop = findViewById(R.id.btnDeleteSectionTop);
        tvSectionTitle = findViewById(R.id.tvSectionTitle);
        tvSectionSubtitle = findViewById(R.id.tvSectionSubtitle);
        layoutBreadcrumbContainer = findViewById(R.id.layoutBreadcrumbContainer);

        tvSectionNetBalance = findViewById(R.id.tvSectionNetBalance);
        tvCombinedTreeSubline = findViewById(R.id.tvCombinedTreeSubline);
        tvSectionGrossCollected = findViewById(R.id.tvSectionGrossCollected);
        tvSectionExpensesTotal = findViewById(R.id.tvSectionExpensesTotal);
        tvSectionValidCount = findViewById(R.id.tvSectionValidCount);

        tvSubfoldersHeader = findViewById(R.id.tvSubfoldersHeader);
        btnNewSubfolder = findViewById(R.id.btnNewSubfolder);
        rvSubfolders = findViewById(R.id.rvSubfolders);

        MaterialButton btnVerifyQrSection = findViewById(R.id.btnVerifyQrSection);
        btnActionNewPayment = findViewById(R.id.btnActionNewPayment);
        btnActionAddExpense = findViewById(R.id.btnActionAddExpense);
        MaterialButton btnDownloadExcel = findViewById(R.id.btnDownloadExcel);
        MaterialButton btnSectionAuditLog = findViewById(R.id.btnSectionAuditLog);
        fabNewPayment = findViewById(R.id.fabNewPayment);

        MaterialButtonToggleGroup toggleSectionMode = findViewById(R.id.toggleSectionMode);
        TextInputEditText etSearchPayments = findViewById(R.id.etSearchPayments);
        scrollPaymentFilterChips = findViewById(R.id.scrollPaymentFilterChips);
        ChipGroup chipGroupFilter = findViewById(R.id.chipGroupPaymentFilter);
        rvPayments = findViewById(R.id.rvPayments);
        rvExpenses = findViewById(R.id.rvExpenses);
        layoutEmptyPayments = findViewById(R.id.layoutEmptyPayments);
        tvEmptySectionListTitle = findViewById(R.id.tvEmptySectionListTitle);
        tvEmptySectionListSubtitle = findViewById(R.id.tvEmptySectionListSubtitle);

        tvSectionTitle.setText(sectionName);
        btnBackSection.setOnClickListener(v -> finish());
        btnRenameSectionTop.setOnClickListener(v -> showRenameFolderDialog(sectionId, sectionName));
        btnDeleteSectionTop.setOnClickListener(v -> confirmDeleteFolder(sectionId));

        // Sub-folders Grid
        subfolderAdapter = new SectionAdapter(
                subfolder -> {
                    Intent intent = new Intent(SectionDetailActivity.this, SectionDetailActivity.class);
                    intent.putExtra(EXTRA_SECTION_ID, subfolder.id);
                    intent.putExtra(EXTRA_SECTION_NAME, subfolder.sectionName);
                    startActivity(intent);
                },
                subfolder -> showRenameFolderDialog(subfolder.id, subfolder.sectionName),
                subfolder -> confirmDeleteFolder(subfolder.id)
        );
        rvSubfolders.setLayoutManager(new GridLayoutManager(this, calculateAdaptiveSpanCount()));
        rvSubfolders.setAdapter(subfolderAdapter);

        // Payments List
        paymentAdapter = new PaymentAdapter(payment -> {
            Intent intent = new Intent(SectionDetailActivity.this, ReceiptDetailActivity.class);
            intent.putExtra(ReceiptDetailActivity.EXTRA_RECEIPT_ID, payment.receiptId);
            startActivity(intent);
        });
        rvPayments.setLayoutManager(new LinearLayoutManager(this));
        rvPayments.setAdapter(paymentAdapter);

        // Expenses List
        expenseAdapter = new ExpenseAdapter(new ExpenseAdapter.OnExpenseActionListener() {
            @Override
            public void onViewProofClicked(ExpenseEntity expense) {
                if (expense.proofImagePath != null) {
                    showFullProofDialog(new File(expense.proofImagePath));
                }
            }

            @Override
            public void onVoidExpenseClicked(ExpenseEntity expense) {
                showVoidExpenseDialog(expense);
            }
        });
        rvExpenses.setLayoutManager(new LinearLayoutManager(this));
        rvExpenses.setAdapter(expenseAdapter);

        // Tabs: Payments vs Expenses
        toggleSectionMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            showingExpensesTab = (checkedId == R.id.btnTabExpenses);
            scrollPaymentFilterChips.setVisibility(showingExpensesTab ? View.GONE : View.VISIBLE);
            rvPayments.setVisibility(showingExpensesTab ? View.GONE : View.VISIBLE);
            rvExpenses.setVisibility(showingExpensesTab ? View.VISIBLE : View.GONE);

            if (showingExpensesTab) {
                fabNewPayment.setText("Add Expense");
                fabNewPayment.setIconResource(R.drawable.ic_trending_down);
            } else {
                fabNewPayment.setText(R.string.new_payment);
                fabNewPayment.setIconResource(R.drawable.ic_add);
            }
            applyFilters();
        });

        etSearchPayments.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                applyFilters();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) {
                currentFilterChipId = checkedIds.get(0);
                applyFilters();
            }
        });

        btnNewSubfolder.setOnClickListener(v -> showCreateSubfolderDialog());
        btnVerifyQrSection.setOnClickListener(v -> showQrScanModeChooserDialog());
        btnActionNewPayment.setOnClickListener(v -> openAddPaymentScreen());
        btnActionAddExpense.setOnClickListener(v -> openAddExpenseScreen());
        btnDownloadExcel.setOnClickListener(v -> exportSectionToExcelFile());
        btnSectionAuditLog.setOnClickListener(v -> {
            Intent intent = new Intent(SectionDetailActivity.this, AuditLogActivity.class);
            intent.putExtra(AuditLogActivity.EXTRA_SECTION_ID, sectionId);
            intent.putExtra(AuditLogActivity.EXTRA_SECTION_NAME, sectionName);
            startActivity(intent);
        });

        fabNewPayment.setOnClickListener(v -> {
            if (showingExpensesTab) {
                openAddExpenseScreen();
            } else {
                openAddPaymentScreen();
            }
        });

        applyRolePermissionsUi();
        observeSectionData();
        buildBreadcrumbNavigation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyRolePermissionsUi();
        refreshCombinedTreeBalance();
    }

    private void applyRolePermissionsUi() {
        boolean canWrite = sessionManager.canCreateOrEditRecords();
        boolean canDeleteOrVoid = sessionManager.canVoidOrDeleteOrManageMembers();

        btnRenameSectionTop.setVisibility(canWrite ? View.VISIBLE : View.GONE);
        btnDeleteSectionTop.setVisibility(canDeleteOrVoid ? View.VISIBLE : View.GONE);
        btnActionNewPayment.setVisibility(canWrite ? View.VISIBLE : View.GONE);
        btnActionAddExpense.setVisibility(canWrite ? View.VISIBLE : View.GONE);
        btnNewSubfolder.setVisibility(canWrite ? View.VISIBLE : View.GONE);
        fabNewPayment.setVisibility(canWrite ? View.VISIBLE : View.GONE);
    }

    private int calculateAdaptiveSpanCount() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float screenWidthDp = dm.widthPixels / dm.density;
        int cols = (int) (screenWidthDp / 340f);
        return Math.max(1, Math.min(3, cols));
    }

    private void openAddPaymentScreen() {
        if (!sessionManager.canCreateOrEditRecords()) {
            Snackbar.make(rootView, "Auditors have read-only access.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(SectionDetailActivity.this, AddPaymentActivity.class);
        intent.putExtra(AddPaymentActivity.EXTRA_SECTION_ID, sectionId);
        intent.putExtra(AddPaymentActivity.EXTRA_SECTION_NAME, sectionName);
        startActivity(intent);
    }

    private void openAddExpenseScreen() {
        if (!sessionManager.canCreateOrEditRecords()) {
            Snackbar.make(rootView, "Auditors have read-only access.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(SectionDetailActivity.this, AddExpenseActivity.class);
        intent.putExtra(AddExpenseActivity.EXTRA_SECTION_ID, sectionId);
        intent.putExtra(AddExpenseActivity.EXTRA_SECTION_NAME, sectionName);
        startActivity(intent);
    }

    private void buildBreadcrumbNavigation() {
        repository.loadBreadcrumbChain(sectionId, new PaymentRepository.ResultCallback<List<SectionEntity>>() {
            @Override
            public void onSuccess(List<SectionEntity> chain) {
                layoutBreadcrumbContainer.removeAllViews();

                TextView homeChip = createBreadcrumbLabel("Home", true);
                homeChip.setOnClickListener(v -> {
                    Intent intent = new Intent(SectionDetailActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                });
                layoutBreadcrumbContainer.addView(homeChip);

                for (int i = 0; i < chain.size(); i++) {
                    SectionEntity node = chain.get(i);
                    TextView sep = createBreadcrumbLabel("  >  ", false);
                    layoutBreadcrumbContainer.addView(sep);

                    boolean isCurrent = (i == chain.size() - 1);
                    TextView nodeChip = createBreadcrumbLabel(node.sectionName, !isCurrent);
                    if (!isCurrent) {
                        nodeChip.setOnClickListener(v -> {
                            Intent intent = new Intent(SectionDetailActivity.this, SectionDetailActivity.class);
                            intent.putExtra(EXTRA_SECTION_ID, node.id);
                            intent.putExtra(EXTRA_SECTION_NAME, node.sectionName);
                            startActivity(intent);
                            finish();
                        });
                    }
                    layoutBreadcrumbContainer.addView(nodeChip);
                }
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    private TextView createBreadcrumbLabel(String text, boolean clickable) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(ContextCompat.getColor(
                this,
                clickable ? R.color.primary_container : R.color.white
        ));
        if (!clickable) {
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return tv;
    }

    private void observeSectionData() {
        repository.observeSectionById(sectionId).observe(this, section -> {
            if (section != null) {
                sectionName = section.sectionName;
                tvSectionTitle.setText(section.sectionName);
                if (section.description != null && !section.description.isEmpty()) {
                    tvSectionSubtitle.setText(section.description);
                } else {
                    tvSectionSubtitle.setText("Section Payment & Expense Ledger");
                }
                buildBreadcrumbNavigation();
            }
        });

        repository.observeSubfolderSummaries(sectionId).observe(this, subfolders -> {
            int count = subfolders != null ? subfolders.size() : 0;
            tvSubfoldersHeader.setText("Sub-Folders (" + count + ")");
            subfolderAdapter.submitList(subfolders);
            rvSubfolders.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            refreshCombinedTreeBalance();
        });

        repository.observePaymentsBySection(sectionId).observe(this, payments -> {
            allSectionPayments.clear();
            double activeSum = 0.0;
            int validCount = 0;
            int voidCount = 0;

            if (payments != null) {
                allSectionPayments.addAll(payments);
                for (PaymentEntity p : payments) {
                    if (p.isVoided()) {
                        voidCount++;
                    } else {
                        validCount++;
                        activeSum += p.amount;
                    }
                }
            }

            directGrossCollected = activeSum;
            tvSectionValidCount.setText(validCount + " Paid • " + voidCount + " Voided");
            updateFinancialSummaryDisplay();
            applyFilters();
        });

        repository.observeExpensesBySection(sectionId).observe(this, expenses -> {
            allSectionExpenses.clear();
            double activeExpenseSum = 0.0;
            if (expenses != null) {
                allSectionExpenses.addAll(expenses);
                for (ExpenseEntity ex : expenses) {
                    if (!ex.isVoided()) {
                        activeExpenseSum += ex.amount;
                    }
                }
            }
            directActiveExpenses = activeExpenseSum;
            updateFinancialSummaryDisplay();
            applyFilters();
        });
    }

    private void updateFinancialSummaryDisplay() {
        double netBalance = directGrossCollected - directActiveExpenses;
        tvSectionGrossCollected.setText(CurrencyFormatter.formatPhp(directGrossCollected));
        tvSectionExpensesTotal.setText("- " + CurrencyFormatter.formatPhp(directActiveExpenses));
        tvSectionNetBalance.setText(CurrencyFormatter.formatPhp(netBalance));
        tvSectionNetBalance.setTextColor(ContextCompat.getColor(
                this,
                netBalance < 0 ? R.color.status_void_red : R.color.primary
        ));
        refreshCombinedTreeBalance();
    }

    private void refreshCombinedTreeBalance() {
        repository.loadCombinedTreeBalance(sectionId, new PaymentRepository.ResultCallback<PaymentRepository.CombinedTreeBalance>() {
            @Override
            public void onSuccess(PaymentRepository.CombinedTreeBalance result) {
                if (result.descendantSubfolderCount > 0) {
                    tvCombinedTreeSubline.setVisibility(View.VISIBLE);
                    tvCombinedTreeSubline.setText("Combined with " + result.descendantSubfolderCount
                            + " sub-folder(s): Gross " + CurrencyFormatter.formatPhp(result.combinedGrossCollected)
                            + " | Expenses -" + CurrencyFormatter.formatPhp(result.combinedExpensesDeducted)
                            + " | Net " + CurrencyFormatter.formatPhp(result.combinedNetBalance));
                } else {
                    tvCombinedTreeSubline.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(String message) {
                tvCombinedTreeSubline.setVisibility(View.GONE);
            }
        });
    }

    private void applyFilters() {
        if (showingExpensesTab) {
            List<ExpenseEntity> filteredExpenses = new ArrayList<>();
            for (ExpenseEntity ex : allSectionExpenses) {
                if (!currentSearchQuery.isEmpty()) {
                    String title = ex.title.toLowerCase(Locale.US);
                    String cat = ex.category.toLowerCase(Locale.US);
                    String notes = ex.notes != null ? ex.notes.toLowerCase(Locale.US) : "";
                    if (!title.contains(currentSearchQuery)
                            && !cat.contains(currentSearchQuery)
                            && !notes.contains(currentSearchQuery)) {
                        continue;
                    }
                }
                filteredExpenses.add(ex);
            }
            expenseAdapter.submitList(filteredExpenses);
            layoutEmptyPayments.setVisibility(filteredExpenses.isEmpty() ? View.VISIBLE : View.GONE);
            tvEmptySectionListTitle.setText("No Expenses Recorded");
            tvEmptySectionListSubtitle.setText("Tap \"Add Expense\" to record deductions (e.g., printing, supplies, snacks) for this folder.");
            return;
        }

        List<PaymentEntity> filtered = new ArrayList<>();
        for (PaymentEntity p : allSectionPayments) {
            boolean matchesChip = true;
            if (currentFilterChipId == R.id.chipFilterCash) {
                matchesChip = PaymentEntity.METHOD_CASH.equalsIgnoreCase(p.paymentMethod) && !p.isVoided();
            } else if (currentFilterChipId == R.id.chipFilterGcash) {
                matchesChip = PaymentEntity.METHOD_GCASH.equalsIgnoreCase(p.paymentMethod) && !p.isVoided();
            } else if (currentFilterChipId == R.id.chipFilterVoided) {
                matchesChip = p.isVoided();
            }

            if (!matchesChip) {
                continue;
            }

            if (!currentSearchQuery.isEmpty()) {
                String fullName = p.getFullName().toLowerCase(Locale.US);
                String studentNo = p.studentNumber != null ? p.studentNumber.toLowerCase(Locale.US) : "";
                String receipt = p.receiptId.toLowerCase(Locale.US);
                String purpose = p.purpose.toLowerCase(Locale.US);
                String ref = p.gcashReferenceNo != null ? p.gcashReferenceNo.toLowerCase(Locale.US) : "";
                if (!fullName.contains(currentSearchQuery)
                        && !studentNo.contains(currentSearchQuery)
                        && !receipt.contains(currentSearchQuery)
                        && !purpose.contains(currentSearchQuery)
                        && !ref.contains(currentSearchQuery)) {
                    continue;
                }
            }

            filtered.add(p);
        }

        paymentAdapter.submitList(filtered);
        layoutEmptyPayments.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        tvEmptySectionListTitle.setText("No Payment Receipts Found");
        tvEmptySectionListSubtitle.setText("Tap \"New Payment\" to record a Cash or GCash payment and generate a tamper-evident QR receipt.");
    }

    private void showCreateSubfolderDialog() {
        if (!sessionManager.canCreateOrEditRecords()) {
            Snackbar.make(rootView, "Auditors have read-only access.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_section, null);
        TextInputLayout tilName = dialogView.findViewById(R.id.tilSectionName);
        TextInputEditText etName = dialogView.findViewById(R.id.etSectionName);
        TextInputEditText etDesc = dialogView.findViewById(R.id.etSectionDescription);
        tilName.setHint("Sub-Folder Name (e.g., Event T-Shirt, Org Fee) *");

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Create Sub-Folder in " + sectionName)
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create Sub-Folder", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = etName.getText() != null ? etName.getText().toString().trim() : "";
                String desc = etDesc.getText() != null ? etDesc.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    tilName.setError("Sub-folder name is required");
                    return;
                }
                tilName.setError(null);

                repository.createSection(name, desc, sectionId, new PaymentRepository.ResultCallback<SectionEntity>() {
                    @Override
                    public void onSuccess(SectionEntity result) {
                        dialog.dismiss();
                        Snackbar.make(rootView, "Created sub-folder: " + result.sectionName, Snackbar.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        tilName.setError(message);
                    }
                });
            });
        });

        dialog.show();
    }

    private void showRenameFolderDialog(long targetFolderId, String currentFolderName) {
        if (!sessionManager.canCreateOrEditRecords()) {
            Snackbar.make(rootView, "Auditors have read-only access and cannot rename folders.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etNewName = new EditText(this);
        etNewName.setHint("New Folder Name");
        etNewName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etNewName.setText(currentFolderName);
        etNewName.setSelection(etNewName.getText().length());
        container.addView(etNewName);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Rename Folder")
                .setMessage("Rename \"" + currentFolderName + "\" without affecting any child sub-folders, payment receipts, or expenses.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save Name", (d, w) -> {
                    String newName = etNewName.getText().toString().trim();
                    repository.renameSection(targetFolderId, newName, new PaymentRepository.ResultCallback<SectionEntity>() {
                        @Override
                        public void onSuccess(SectionEntity result) {
                            if (targetFolderId == sectionId) {
                                sectionName = result.sectionName;
                                tvSectionTitle.setText(result.sectionName);
                            }
                            buildBreadcrumbNavigation();
                            Snackbar.make(rootView, "Renamed folder to \"" + result.sectionName + "\"", Snackbar.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                        }
                    });
                })
                .show();
    }

    private void confirmDeleteFolder(long targetFolderId) {
        if (!sessionManager.canVoidOrDeleteOrManageMembers()) {
            Snackbar.make(rootView, "Only the Room OWNER can permanently delete folders.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        repository.previewFolderDeletionImpact(targetFolderId, new PaymentRepository.ResultCallback<PaymentRepository.FolderDeleteSummary>() {
            @Override
            public void onSuccess(PaymentRepository.FolderDeleteSummary summary) {
                String message = "Are you sure you want to permanently delete folder \"" + summary.folderName + "\"?\n\n"
                        + "This will remove:\n"
                        + "• " + summary.subfolderCount + " nested sub-folder(s)\n"
                        + "• " + summary.paymentCount + " student payment receipt(s)\n"
                        + "• " + summary.expenseCount + " expense deduction record(s)\n\n"
                        + "An immutable FOLDER_DELETED entry will be recorded in the Audit Trail.";

                new MaterialAlertDialogBuilder(SectionDetailActivity.this)
                        .setTitle("Delete Folder Permanently?")
                        .setMessage(message)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete Permanently", (d, w) -> {
                            repository.deleteFolderCascade(targetFolderId, new PaymentRepository.ResultCallback<PaymentRepository.FolderDeleteSummary>() {
                                @Override
                                public void onSuccess(PaymentRepository.FolderDeleteSummary deleted) {
                                    if (targetFolderId == sectionId) {
                                        finish();
                                    } else {
                                        Snackbar.make(rootView, "Deleted sub-folder \"" + deleted.folderName + "\".", Snackbar.LENGTH_LONG).show();
                                    }
                                }

                                @Override
                                public void onError(String err) {
                                    Snackbar.make(rootView, err, Snackbar.LENGTH_LONG).show();
                                }
                            });
                        })
                        .show();
            }

            @Override
            public void onError(String message) {
                Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void showVoidExpenseDialog(ExpenseEntity expense) {
        if (!sessionManager.canVoidOrDeleteOrManageMembers()) {
            Snackbar.make(rootView, "Only the Room OWNER can void expense deductions.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_void_payment, null);
        TextInputLayout tilVoidReason = dialogView.findViewById(R.id.tilVoidReason);
        TextInputEditText etVoidReason = dialogView.findViewById(R.id.etVoidReason);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Void Expense Deduction?")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Confirm Void Expense", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String reason = etVoidReason.getText() != null ? etVoidReason.getText().toString().trim() : "";
                if (reason.isEmpty()) {
                    tilVoidReason.setError("A mandatory reason is required to void this expense");
                    return;
                }
                tilVoidReason.setError(null);

                repository.voidExpense(expense.id, reason, new PaymentRepository.ResultCallback<ExpenseEntity>() {
                    @Override
                    public void onSuccess(ExpenseEntity result) {
                        dialog.dismiss();
                        Snackbar.make(rootView, "Expense voided. Amount restored to Net Remaining Balance.", Snackbar.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        tilVoidReason.setError(message);
                    }
                });
            });
        });

        dialog.show();
    }

    private void showFullProofDialog(File proofFile) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_image_viewer, null);
        ImageView ivFull = dialogView.findViewById(R.id.ivFullProofDialog);
        Glide.with(this).load(proofFile).into(ivFull);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Expense Receipt / Proof Photo")
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show();
    }

    private void exportSectionToExcelFile() {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(this);
                SectionEntity section = db.sectionDao().findByIdSync(sectionId);
                if (section == null) {
                    runOnUiThread(() -> Snackbar.make(rootView, "Section not found.", Snackbar.LENGTH_SHORT).show());
                    return;
                }
                List<PaymentEntity> chronologicalPayments = db.paymentDao().getPaymentsBySectionSync(sectionId);
                List<ExpenseEntity> chronologicalExpenses = db.expenseDao().getExpensesBySectionSync(sectionId);
                List<AuditLogEntity> sectionLogs = db.auditLogDao().getLogsBySectionSync(sectionId);

                LocalStorageManager.SavedMediaResult saved = LocalStorageManager.exportSectionToExcel(
                        this,
                        section,
                        chronologicalPayments,
                        chronologicalExpenses,
                        sectionLogs
                );

                repository.logExcelExport(sectionId, section.sectionName, chronologicalPayments.size(), saved.fileName);

                runOnUiThread(() -> showExcelExportSuccessDialog(saved));
            } catch (Exception e) {
                runOnUiThread(() -> Snackbar.make(
                        rootView,
                        "Excel export failed: " + e.getMessage(),
                        Snackbar.LENGTH_LONG
                ).show());
            }
        });
    }

    private void showExcelExportSuccessDialog(LocalStorageManager.SavedMediaResult saved) {
        Snackbar.make(rootView, "Saved to " + saved.displayPath, Snackbar.LENGTH_LONG)
                .setAction("Open", v -> openExcelUri(saved.uri))
                .show();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Excel (.xlsx) Saved to Downloads")
                .setMessage("File saved offline at:\n" + saved.displayPath + "\n\nIncludes:\n• Worksheet 1: Section Payments (with Recorded By & Summary)\n• Worksheet 2: Section Expenses (Deductions & Recorded By)\n• Worksheet 3: Section Audit Log (with Actor & Role)")
                .setPositiveButton("Open Excel", (d, w) -> openExcelUri(saved.uri))
                .setNeutralButton("Share File", (d, w) -> shareExcelUri(saved.uri))
                .setNegativeButton("Done", null)
                .show();
    }

    private void openExcelUri(Uri uri) {
        try {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(viewIntent, "Open Excel Spreadsheet"));
        } catch (Exception e) {
            Snackbar.make(rootView, "No Excel viewer app installed. File is saved in Downloads/PaymentTracker.", Snackbar.LENGTH_LONG).show();
        }
    }

    private void shareExcelUri(Uri uri) {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share Section Excel Report"));
        } catch (Exception e) {
            Snackbar.make(rootView, "Unable to share file: " + e.getMessage(), Snackbar.LENGTH_SHORT).show();
        }
    }

    private void showQrScanModeChooserDialog() {
        String[] options = new String[]{
                "Scan QR via Live Camera",
                "Verify Saved QR Receipt Image from Gallery"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Verify Student QR Receipt Authenticity")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        ScanOptions scanOptions = new ScanOptions();
                        scanOptions.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
                        scanOptions.setPrompt("Align the Student Payment QR Receipt inside the viewfinder");
                        scanOptions.setBeepEnabled(true);
                        scanOptions.setOrientationLocked(false);
                        qrCameraScanLauncher.launch(scanOptions);
                    } else {
                        qrGalleryImageLauncher.launch("image/*");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void decodeQrFromGalleryUri(Uri imageUri) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            String decodedPayload = SecuritySignatureHelper.decodeQrFromImageUri(this, imageUri);
            runOnUiThread(() -> {
                if (decodedPayload == null || decodedPayload.trim().isEmpty()) {
                    repository.logSecurityQrFailure("(Unreadable image)", "No QR code detected in selected gallery image");
                    Snackbar.make(rootView, "No valid QR code found in selected image.", Snackbar.LENGTH_LONG).show();
                } else {
                    ReceiptVerifierBottomSheet.newInstance(decodedPayload)
                            .show(getSupportFragmentManager(), "ReceiptVerifierBottomSheet");
                }
            });
        });
    }
}
