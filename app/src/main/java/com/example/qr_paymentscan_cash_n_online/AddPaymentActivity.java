package com.example.qr_paymentscan_cash_n_online;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.DuplicatePaymentGuard;
import com.example.qr_paymentscan_cash_n_online.util.LocalStorageManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;

public class AddPaymentActivity extends BaseThemedActivity {

    public static final String EXTRA_SECTION_ID = "extra_section_id";
    public static final String EXTRA_SECTION_NAME = "extra_section_name";
    private static final String STUDENT_NO_FIXED_PREFIX = "02000";

    private long sectionId;
    private String sectionName = "";
    private PaymentRepository repository;

    private View rootView;
    private TextInputLayout tilFirstName;
    private TextInputEditText etFirstName;
    private TextInputLayout tilLastName;
    private TextInputEditText etLastName;
    private TextInputLayout tilStudentNumberSuffix;
    private TextInputEditText etStudentNumberSuffix;
    private TextInputLayout tilPurpose;
    private TextInputEditText etPurpose;
    private TextInputLayout tilAmount;
    private TextInputEditText etAmount;

    private LinearLayout layoutGcashOnlyContainer;
    private TextInputLayout tilGcashRef;
    private TextInputEditText etGcashRef;
    private MaterialCardView cardProofPreview;
    private ImageView ivProofPreview;
    private TextView tvProofSavedStatus;
    private MaterialButton btnSubmitPayment;

    private String selectedPaymentMethod = PaymentEntity.METHOD_CASH;
    private String savedInternalProofPath = null;
    private Uri pendingCameraCaptureUri = null;

    // Pick GCash Proof from Gallery
    private final ActivityResultLauncher<String> pickGalleryProofLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    persistProofUriToInternalStorage(uri);
                }
            });

    // Capture GCash Proof from Camera
    private final ActivityResultLauncher<Uri> captureCameraProofLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && pendingCameraCaptureUri != null) {
                    persistProofUriToInternalStorage(pendingCameraCaptureUri);
                }
            });

    // Camera Permission Launcher
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    launchCameraProofCapture();
                } else {
                    Snackbar.make(rootView, "Camera permission is required to take a proof photo.", Snackbar.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_add_payment);

        rootView = findViewById(R.id.addPaymentRoot);
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

        ImageButton btnBack = findViewById(R.id.btnBackAddPayment);
        TextView tvSectionBadge = findViewById(R.id.tvAddPaymentSectionBadge);
        tvSectionBadge.setText("Section Folder: " + sectionName);
        btnBack.setOnClickListener(v -> finish());

        tilFirstName = findViewById(R.id.tilFirstName);
        etFirstName = findViewById(R.id.etFirstName);
        tilLastName = findViewById(R.id.tilLastName);
        etLastName = findViewById(R.id.etLastName);
        tilStudentNumberSuffix = findViewById(R.id.tilStudentNumberSuffix);
        etStudentNumberSuffix = findViewById(R.id.etStudentNumberSuffix);
        tilPurpose = findViewById(R.id.tilPurpose);
        etPurpose = findViewById(R.id.etPurpose);
        tilAmount = findViewById(R.id.tilAmount);
        etAmount = findViewById(R.id.etAmount);

        MaterialButtonToggleGroup togglePaymentMethod = findViewById(R.id.togglePaymentMethod);
        layoutGcashOnlyContainer = findViewById(R.id.layoutGcashOnlyContainer);
        tilGcashRef = findViewById(R.id.tilGcashRef);
        etGcashRef = findViewById(R.id.etGcashRef);
        MaterialButton btnPickProofGallery = findViewById(R.id.btnPickProofGallery);
        MaterialButton btnCaptureProofCamera = findViewById(R.id.btnCaptureProofCamera);
        cardProofPreview = findViewById(R.id.cardProofPreview);
        ivProofPreview = findViewById(R.id.ivProofPreview);
        tvProofSavedStatus = findViewById(R.id.tvProofSavedStatus);
        btnSubmitPayment = findViewById(R.id.btnSubmitPayment);

        togglePaymentMethod.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId == R.id.btnSelectGcash) {
                selectedPaymentMethod = PaymentEntity.METHOD_GCASH;
                layoutGcashOnlyContainer.setVisibility(View.VISIBLE);
            } else {
                selectedPaymentMethod = PaymentEntity.METHOD_CASH;
                layoutGcashOnlyContainer.setVisibility(View.GONE);
            }
        });

        btnPickProofGallery.setOnClickListener(v -> pickGalleryProofLauncher.launch("image/*"));
        btnCaptureProofCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                launchCameraProofCapture();
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnSubmitPayment.setOnClickListener(v -> validateAndSubmitPayment());
    }

    private void launchCameraProofCapture() {
        try {
            pendingCameraCaptureUri = LocalStorageManager.createTempCameraCaptureUri(this);
            captureCameraProofLauncher.launch(pendingCameraCaptureUri);
        } catch (Exception e) {
            Snackbar.make(rootView, "Unable to launch camera: " + e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }

    private void persistProofUriToInternalStorage(Uri sourceUri) {
        tvProofSavedStatus.setText("Copying proof image to local private storage...");
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                String savedPath = LocalStorageManager.copyProofImageToInternalStorage(this, sourceUri);
                runOnUiThread(() -> {
                    savedInternalProofPath = savedPath;
                    cardProofPreview.setVisibility(View.VISIBLE);
                    Glide.with(this)
                            .load(new File(savedPath))
                            .centerCrop()
                            .into(ivProofPreview);
                    tvProofSavedStatus.setText("Proof image saved to local storage: " + new File(savedPath).getName());
                    tvProofSavedStatus.setTextColor(ContextCompat.getColor(this, R.color.status_paid_green));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvProofSavedStatus.setText("Failed to attach image: " + e.getMessage());
                    tvProofSavedStatus.setTextColor(ContextCompat.getColor(this, R.color.status_void_red));
                });
            }
        });
    }

    private void validateAndSubmitPayment() {
        tilFirstName.setError(null);
        tilLastName.setError(null);
        tilStudentNumberSuffix.setError(null);
        tilPurpose.setError(null);
        tilAmount.setError(null);
        tilGcashRef.setError(null);

        String firstName = etFirstName.getText() != null ? etFirstName.getText().toString().trim() : "";
        String lastName = etLastName.getText() != null ? etLastName.getText().toString().trim() : "";
        String studentSuffix = etStudentNumberSuffix.getText() != null
                ? etStudentNumberSuffix.getText().toString().trim()
                : "";
        String purpose = etPurpose.getText() != null ? etPurpose.getText().toString().trim() : "";
        String amountStr = etAmount.getText() != null ? etAmount.getText().toString().trim() : "";

        boolean hasError = false;
        if (firstName.isEmpty()) {
            tilFirstName.setError("First name is required");
            hasError = true;
        }
        if (lastName.isEmpty()) {
            tilLastName.setError("Last name is required");
            hasError = true;
        }

        // Optional Student Number validation: either completely empty OR 6 numeric digits after 02000
        String fullStudentNumber = null;
        if (!studentSuffix.isEmpty()) {
            if (!studentSuffix.matches("\\d{6}")) {
                tilStudentNumberSuffix.setError("Enter all 6 digits after 02000, or leave blank");
                hasError = true;
            } else {
                fullStudentNumber = STUDENT_NO_FIXED_PREFIX + studentSuffix;
            }
        }

        if (purpose.isEmpty()) {
            tilPurpose.setError("Payment purpose / fee label is required");
            hasError = true;
        }

        double amount = 0.0;
        try {
            amount = Double.parseDouble(amountStr);
            if (amount <= 0.0) {
                tilAmount.setError("Amount must be greater than ₱0.00");
                hasError = true;
            }
        } catch (NumberFormatException e) {
            tilAmount.setError("Enter a valid numeric amount");
            hasError = true;
        }

        String gcashRef = null;
        String proofPath = null;

        if (PaymentEntity.METHOD_GCASH.equals(selectedPaymentMethod)) {
            gcashRef = etGcashRef.getText() != null ? etGcashRef.getText().toString().trim() : "";
            if (gcashRef.length() < 13 || !gcashRef.matches("\\d+")) {
                tilGcashRef.setError("Enter a valid numeric GCash Reference No. (minimum 13 digits)");
                hasError = true;
            }
            if (savedInternalProofPath == null || !new File(savedInternalProofPath).exists()) {
                tvProofSavedStatus.setText("REQUIRED: Please attach a GCash Proof of Payment screenshot or photo.");
                tvProofSavedStatus.setTextColor(ContextCompat.getColor(this, R.color.status_void_red));
                Snackbar.make(rootView, "GCash Proof of Payment picture is required.", Snackbar.LENGTH_LONG).show();
                hasError = true;
            } else {
                proofPath = savedInternalProofPath;
            }
        }

        if (hasError) {
            return;
        }

        final String finalStudentNo = fullStudentNumber;
        final double finalAmount = amount;
        final String finalGcashRef = gcashRef;
        final String finalProofPath = proofPath;

        btnSubmitPayment.setEnabled(false);

        // Pre-Submission Two-Tier Duplicate Check (Local Room + Live Firestore Source.SERVER)
        repository.checkDuplicatePaymentBeforeSave(
                sectionId,
                finalStudentNo,
                firstName,
                lastName,
                purpose,
                new PaymentRepository.ResultCallback<DuplicatePaymentGuard.DuplicateCheckResult>() {
                    @Override
                    public void onSuccess(DuplicatePaymentGuard.DuplicateCheckResult checkResult) {
                        if (checkResult != null && checkResult.hasDuplicate && checkResult.existingPayment != null) {
                            if (checkResult.isPartialPayment) {
                                showPartialPaymentResolutionDialog(
                                        checkResult.existingPayment,
                                        firstName,
                                        lastName,
                                        finalStudentNo,
                                        purpose,
                                        finalAmount,
                                        finalGcashRef,
                                        finalProofPath
                                );
                            } else {
                                showDuplicatePaymentBlockedDialog(
                                        checkResult.existingPayment,
                                        firstName,
                                        lastName,
                                        finalStudentNo,
                                        purpose,
                                        finalAmount,
                                        finalGcashRef,
                                        finalProofPath
                                );
                            }
                            return;
                        }
                        commitNewPaymentRecord(
                                firstName,
                                lastName,
                                finalStudentNo,
                                purpose,
                                finalAmount,
                                finalGcashRef,
                                finalProofPath,
                                null
                        );
                    }

                    @Override
                    public void onError(String message) {
                        commitNewPaymentRecord(
                                firstName,
                                lastName,
                                finalStudentNo,
                                purpose,
                                finalAmount,
                                finalGcashRef,
                                finalProofPath,
                                null
                        );
                    }
                }
        );
    }

    /**
     * Case 1: Existing PARTIAL Payment Found (remainingBalance > 0).
     * Offers "Apply Toward Remaining Balance" or "Cancel".
     */
    private void showPartialPaymentResolutionDialog(
            PaymentEntity existingPartial,
            String firstName,
            String lastName,
            String studentNumber,
            String purpose,
            double amount,
            String gcashRef,
            String proofPath
    ) {
        String studentDisplay = existingPartial.getFullName()
                + (existingPartial.hasStudentNumber() ? " (" + existingPartial.studentNumber + ")" : "");
        String msg = "An existing PARTIAL payment receipt was found for this student in \"" + sectionName + "\":\n\n"
                + "• Student: " + studentDisplay + "\n"
                + "• Existing Receipt: " + existingPartial.receiptId + "\n"
                + "• Recorded By: " + existingPartial.getFormattedActorAttribution() + "\n"
                + "• Paid So Far: " + CurrencyFormatter.formatPhp(existingPartial.amount) + "\n"
                + "• Remaining Balance: " + CurrencyFormatter.formatPhp(existingPartial.remainingBalance) + "\n\n"
                + "Would you like to apply " + CurrencyFormatter.formatPhp(amount) + " toward this student's remaining balance?";

        new MaterialAlertDialogBuilder(this)
                .setTitle("Existing Partial Payment Found")
                .setMessage(msg)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> btnSubmitPayment.setEnabled(true))
                .setNeutralButton("Record Separate Receipt", (d, w) -> showOverrideReasonDialog(
                        existingPartial,
                        firstName,
                        lastName,
                        studentNumber,
                        purpose,
                        amount,
                        gcashRef,
                        proofPath
                ))
                .setPositiveButton("Apply Toward Remaining Balance", (d, w) -> {
                    repository.applyAdditionalPaymentToPartialReceipt(
                            existingPartial,
                            amount,
                            new PaymentRepository.ResultCallback<PaymentEntity>() {
                                @Override
                                public void onSuccess(PaymentEntity updatedReceipt) {
                                    Intent intent = new Intent(AddPaymentActivity.this, ReceiptDetailActivity.class);
                                    intent.putExtra(ReceiptDetailActivity.EXTRA_RECEIPT_ID, updatedReceipt.receiptId);
                                    startActivity(intent);
                                    finish();
                                }

                                @Override
                                public void onError(String message) {
                                    btnSubmitPayment.setEnabled(true);
                                    Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                                }
                            }
                    );
                })
                .show();
    }

    /**
     * Case 2: Existing Payment Already PAID in Full.
     * Displays full transparency (Recorded By Cashier + Role, Receipt ID, Amount, Mode, Timestamp)
     * and offers:
     * 1. "Cancel / Do Not Save" (logs DUPLICATE_PAYMENT_BLOCKED)
     * 2. "View Existing Receipt" (opens ReceiptDetailActivity)
     * 3. "Record Separate Item (Override)" (requires mandatory Override Reason & logs DUPLICATE_PAYMENT_OVERRIDE)
     */
    private void showDuplicatePaymentBlockedDialog(
            PaymentEntity existing,
            String firstName,
            String lastName,
            String studentNumber,
            String purpose,
            double amount,
            String gcashRef,
            String proofPath
    ) {
        String studentDisplay = existing.getFullName()
                + (existing.hasStudentNumber() ? " (" + existing.studentNumber + ")" : "");
        String message = "This student has ALREADY PAID in folder \"" + sectionName + "\". Double collection has been blocked:\n\n"
                + "• Student: " + studentDisplay + "\n"
                + "• Already Recorded By: " + existing.getFormattedActorAttribution() + "\n"
                + "• Receipt ID: " + existing.receiptId + "\n"
                + "• Amount & Mode: " + CurrencyFormatter.formatPhp(existing.amount) + " (" + existing.paymentMethod + ")\n"
                + "• Purpose: " + existing.purpose + "\n"
                + "• Date / Time: " + CurrencyFormatter.formatDateTime(existing.createdAt) + "\n\n"
                + "Only use 'Record Separate Item (Override)' if the student is legitimately paying for an additional item (e.g., 2nd shirt or replacement ID).";

        new MaterialAlertDialogBuilder(this)
                .setTitle("Duplicate Payment Blocked")
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton("Cancel / Do Not Save", (d, w) -> {
                    repository.logDuplicatePaymentBlocked(sectionId, sectionName, existing);
                    btnSubmitPayment.setEnabled(true);
                    Snackbar.make(rootView, "Duplicate payment blocked. Existing receipt preserved.", Snackbar.LENGTH_SHORT).show();
                })
                .setNeutralButton("View Existing Receipt", (d, w) -> {
                    repository.logDuplicatePaymentBlocked(sectionId, sectionName, existing);
                    btnSubmitPayment.setEnabled(true);
                    Intent intent = new Intent(AddPaymentActivity.this, ReceiptDetailActivity.class);
                    intent.putExtra(ReceiptDetailActivity.EXTRA_RECEIPT_ID, existing.receiptId);
                    startActivity(intent);
                })
                .setPositiveButton("Record Separate Item (Override)", (d, w) -> showOverrideReasonDialog(
                        existing,
                        firstName,
                        lastName,
                        studentNumber,
                        purpose,
                        amount,
                        gcashRef,
                        proofPath
                ))
                .show();
    }

    private void showOverrideReasonDialog(
            PaymentEntity existing,
            String firstName,
            String lastName,
            String studentNumber,
            String purpose,
            double amount,
            String gcashRef,
            String proofPath
    ) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etReason = new EditText(this);
        etReason.setHint("Mandatory Override Reason (e.g., 2nd uniform shirt)");
        etReason.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        container.addView(etReason);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Authorize Duplicate Override")
                .setMessage("Enter a mandatory reason for recording an additional receipt for " + existing.getFullName()
                        + " (Existing Receipt: " + existing.receiptId + "). This override will be logged in the Security Audit Trail.")
                .setView(container)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> btnSubmitPayment.setEnabled(true))
                .setPositiveButton("Confirm & Save Receipt", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String reason = etReason.getText() != null ? etReason.getText().toString().trim() : "";
            if (reason.length() < 4) {
                Toast.makeText(AddPaymentActivity.this, "Please enter a clear override reason (at least 4 characters).", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            commitNewPaymentRecord(
                    firstName,
                    lastName,
                    studentNumber,
                    purpose,
                    amount,
                    gcashRef,
                    proofPath,
                    reason
            );
        }));

        dialog.show();
    }

    private void commitNewPaymentRecord(
            String firstName,
            String lastName,
            String fullStudentNumber,
            String purpose,
            double amount,
            String gcashRef,
            String proofPath,
            String duplicateOverrideReason
    ) {
        repository.recordPaymentWithDuplicateSupport(
                sectionId,
                firstName,
                lastName,
                fullStudentNumber,
                purpose,
                amount,
                selectedPaymentMethod,
                gcashRef,
                proofPath,
                duplicateOverrideReason,
                new PaymentRepository.ResultCallback<PaymentEntity>() {
                    @Override
                    public void onSuccess(PaymentEntity result) {
                        Intent intent = new Intent(AddPaymentActivity.this, ReceiptDetailActivity.class);
                        intent.putExtra(ReceiptDetailActivity.EXTRA_RECEIPT_ID, result.receiptId);
                        startActivity(intent);
                        finish();
                    }

                    @Override
                    public void onError(String message) {
                        btnSubmitPayment.setEnabled(true);
                        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                    }
                }
        );
    }
}
