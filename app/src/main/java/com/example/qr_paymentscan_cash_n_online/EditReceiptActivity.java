package com.example.qr_paymentscan_cash_n_online;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.data.RoomEntity;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.ui.LoadingOverlayController;
import com.example.qr_paymentscan_cash_n_online.ui.ProofImagePreviewDialog;
import com.example.qr_paymentscan_cash_n_online.util.CloudImageStorageManager;
import com.example.qr_paymentscan_cash_n_online.util.LocalStorageManager;
import com.example.qr_paymentscan_cash_n_online.util.PaymentValidationHelper;
import com.example.qr_paymentscan_cash_n_online.util.RoomPolicyGuard;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;

public class EditReceiptActivity extends BaseThemedActivity {

    public static final String EXTRA_RECEIPT_ID = "extra_receipt_id";

    private String receiptId;
    private PaymentRepository repository;
    private PaymentEntity currentPayment;
    private LoadingOverlayController loadingOverlay;
    private View rootView;

    private TextView tvEditReceiptIdHeader;
    private TextView tvEditSectionName;
    private TextInputLayout tilEditFirstName;
    private TextInputEditText etEditFirstName;
    private TextInputLayout tilEditLastName;
    private TextInputEditText etEditLastName;
    private TextInputLayout tilEditStudentNumber;
    private TextInputEditText etEditStudentNumber;
    private TextInputLayout tilEditPurpose;
    private TextInputEditText etEditPurpose;
    private TextInputLayout tilEditAmount;
    private TextInputEditText etEditAmount;
    private ChipGroup chipGroupEditPaymentProviders;
    private TextInputLayout tilEditOtherProviderName;
    private TextInputEditText etEditOtherProviderName;
    private TextInputLayout tilEditGcashRef;
    private TextInputEditText etEditGcashRef;

    private MaterialCardView cardEditProofPreview;
    private ImageView ivEditProofThumbnail;
    private MaterialButton btnEditRemoveProof;
    private ChipGroup chipGroupEditReasons;
    private MaterialButton btnSaveEditReceipt;

    private String selectedPaymentMethod = PaymentEntity.METHOD_CASH;
    private String selectedProofPath = null;
    private boolean isProofRemoved = false;
    private RoomEntity currentRoom;
    private Uri pendingCameraCaptureUri = null;

    private final ActivityResultLauncher<String> pickGalleryProofLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    saveProofFromUri(uri);
                }
            });

    private final ActivityResultLauncher<Uri> captureCameraProofLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && pendingCameraCaptureUri != null) {
                    saveProofFromUri(pendingCameraCaptureUri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_edit_receipt);

        rootView = findViewById(R.id.editReceiptRoot);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        receiptId = getIntent().getStringExtra(EXTRA_RECEIPT_ID);
        if (receiptId == null || receiptId.trim().isEmpty()) {
            Toast.makeText(this, "Receipt not specified.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        repository = new PaymentRepository(this);
        loadingOverlay = new LoadingOverlayController(this);

        initViews();
        loadReceiptData();
    }

    private void initViews() {
        ImageButton btnBack = findViewById(R.id.btnBackEditReceipt);
        btnBack.setOnClickListener(v -> finish());

        tvEditReceiptIdHeader = findViewById(R.id.tvEditReceiptIdHeader);
        tvEditSectionName = findViewById(R.id.tvEditSectionName);
        tilEditFirstName = findViewById(R.id.tilEditFirstName);
        etEditFirstName = findViewById(R.id.etEditFirstName);
        tilEditLastName = findViewById(R.id.tilEditLastName);
        etEditLastName = findViewById(R.id.etEditLastName);
        tilEditStudentNumber = findViewById(R.id.tilEditStudentNumber);
        etEditStudentNumber = findViewById(R.id.etEditStudentNumber);
        tilEditPurpose = findViewById(R.id.tilEditPurpose);
        etEditPurpose = findViewById(R.id.etEditPurpose);
        tilEditAmount = findViewById(R.id.tilEditAmount);
        etEditAmount = findViewById(R.id.etEditAmount);

        chipGroupEditPaymentProviders = findViewById(R.id.chipGroupEditPaymentProviders);
        tilEditOtherProviderName = findViewById(R.id.tilEditOtherProviderName);
        etEditOtherProviderName = findViewById(R.id.etEditOtherProviderName);
        tilEditGcashRef = findViewById(R.id.tilEditGcashRef);
        etEditGcashRef = findViewById(R.id.etEditGcashRef);

        cardEditProofPreview = findViewById(R.id.cardEditProofPreview);
        ivEditProofThumbnail = findViewById(R.id.ivEditProofThumbnail);
        btnEditRemoveProof = findViewById(R.id.btnEditRemoveProof);
        chipGroupEditReasons = findViewById(R.id.chipGroupEditReasons);
        MaterialButton btnGallery = findViewById(R.id.btnEditPickGalleryProof);
        MaterialButton btnCamera = findViewById(R.id.btnEditCaptureCameraProof);
        btnSaveEditReceipt = findViewById(R.id.btnSaveEditReceipt);

        cardEditProofPreview.setOnClickListener(v -> {
            String remoteUrl = (currentPayment != null && !isProofRemoved)
                    ? (currentPayment.proofImageUrl != null ? currentPayment.proofImageUrl : currentPayment.remoteProofUrl)
                    : null;
            File localFile = (selectedProofPath != null && !isProofRemoved) ? new File(selectedProofPath) : null;
            ProofImagePreviewDialog.show(this, localFile, remoteUrl);
        });

        btnEditRemoveProof.setOnClickListener(v -> {
            isProofRemoved = true;
            selectedProofPath = null;
            cardEditProofPreview.setVisibility(View.GONE);
            btnEditRemoveProof.setVisibility(View.GONE);
            Snackbar.make(rootView, "Proof photo removed.", Snackbar.LENGTH_SHORT).show();
        });

        chipGroupEditPaymentProviders.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipEditCash) {
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_CASH;
                tilEditGcashRef.setVisibility(View.GONE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else if (id == R.id.chipEditGcash) {
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_GCASH;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else if (id == R.id.chipEditMaya) {
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_MAYA;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else if (id == R.id.chipEditGoTyme) {
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_GOTYME;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else if (id == R.id.chipEditBankTransfer) {
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_BANK_TRANSFER;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else if (id == R.id.chipEditOtherOnline) {
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_OTHER_ONLINE;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.VISIBLE);
            }
        });

        btnGallery.setOnClickListener(v -> pickGalleryProofLauncher.launch("image/*"));
        btnCamera.setOnClickListener(v -> launchCameraCapture());
        btnSaveEditReceipt.setOnClickListener(v -> validateAndSave());
    }

    private void loadReceiptData() {
        repository.observePaymentByReceiptId(receiptId).observe(this, payment -> {
            if (payment == null) {
                return;
            }
            currentPayment = payment;
            if (payment.roomId != null && !payment.roomId.isEmpty()) {
                repository.observeRoomById(payment.roomId).observe(this, room -> {
                    if (room != null) {
                        currentRoom = room;
                        if (room.isArchived) {
                            RoomPolicyGuard.showArchivedRoomNotice(this);
                            finish();
                        }
                    }
                });
            }
            tvEditReceiptIdHeader.setText(payment.receiptId);
            tvEditSectionName.setText("Folder: " + payment.sectionName);

            etEditFirstName.setText(payment.firstName);
            etEditLastName.setText(payment.lastName);
            if (payment.studentNumber != null) {
                etEditStudentNumber.setText(payment.studentNumber);
            }
            etEditPurpose.setText(payment.purpose);
            etEditAmount.setText(String.format(java.util.Locale.US, "%.2f", payment.amount));

            String method = payment.paymentMethod != null ? payment.paymentMethod.trim() : PaymentValidationHelper.PROVIDER_CASH;
            if (PaymentValidationHelper.isCash(method)) {
                chipGroupEditPaymentProviders.check(R.id.chipEditCash);
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_CASH;
                tilEditGcashRef.setVisibility(View.GONE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else if (PaymentValidationHelper.PROVIDER_GCASH.equalsIgnoreCase(method)) {
                chipGroupEditPaymentProviders.check(R.id.chipEditGcash);
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_GCASH;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else if (PaymentValidationHelper.PROVIDER_MAYA.equalsIgnoreCase(method)) {
                chipGroupEditPaymentProviders.check(R.id.chipEditMaya);
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_MAYA;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else if (PaymentValidationHelper.PROVIDER_GOTYME.equalsIgnoreCase(method)) {
                chipGroupEditPaymentProviders.check(R.id.chipEditGoTyme);
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_GOTYME;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else if (PaymentValidationHelper.PROVIDER_BANK_TRANSFER.equalsIgnoreCase(method)) {
                chipGroupEditPaymentProviders.check(R.id.chipEditBankTransfer);
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_BANK_TRANSFER;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.GONE);
            } else {
                chipGroupEditPaymentProviders.check(R.id.chipEditOtherOnline);
                selectedPaymentMethod = PaymentValidationHelper.PROVIDER_OTHER_ONLINE;
                tilEditGcashRef.setVisibility(View.VISIBLE);
                tilEditOtherProviderName.setVisibility(View.VISIBLE);
                etEditOtherProviderName.setText(method);
            }

            if (payment.gcashReferenceNo != null) {
                etEditGcashRef.setText(payment.gcashReferenceNo);
            }

            selectedProofPath = payment.proofImagePath;
            renderProofThumbnail(payment.proofImagePath, payment.proofImageUrl != null ? payment.proofImageUrl : payment.remoteProofUrl);
        });
    }

    private void renderProofThumbnail(String localPath, String remoteUrl) {
        if (isProofRemoved) {
            cardEditProofPreview.setVisibility(View.GONE);
            if (btnEditRemoveProof != null) {
                btnEditRemoveProof.setVisibility(View.GONE);
            }
            return;
        }
        if (localPath != null && new File(localPath).exists()) {
            cardEditProofPreview.setVisibility(View.VISIBLE);
            if (btnEditRemoveProof != null) {
                btnEditRemoveProof.setVisibility(View.VISIBLE);
            }
            Glide.with(this).load(new File(localPath)).centerCrop().into(ivEditProofThumbnail);
        } else if (remoteUrl != null && !remoteUrl.trim().isEmpty()) {
            cardEditProofPreview.setVisibility(View.VISIBLE);
            if (btnEditRemoveProof != null) {
                btnEditRemoveProof.setVisibility(View.VISIBLE);
            }
            Glide.with(this).load(remoteUrl).centerCrop().into(ivEditProofThumbnail);
        } else {
            cardEditProofPreview.setVisibility(View.GONE);
            if (btnEditRemoveProof != null) {
                btnEditRemoveProof.setVisibility(View.GONE);
            }
        }
    }

    private void launchCameraCapture() {
        try {
            File photoFile = File.createTempFile("PROOF_EDIT_", ".jpg", getCacheDir());
            pendingCameraCaptureUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    photoFile
            );
            captureCameraProofLauncher.launch(pendingCameraCaptureUri);
        } catch (Exception e) {
            Snackbar.make(rootView, "Failed to prepare camera: " + e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }

    private void saveProofFromUri(Uri uri) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                String savedPath = LocalStorageManager.copyProofImageToInternalStorage(this, uri);
                runOnUiThread(() -> {
                    isProofRemoved = false;
                    selectedProofPath = savedPath;
                    renderProofThumbnail(savedPath, null);
                    Snackbar.make(rootView, "Proof image attached.", Snackbar.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Snackbar.make(rootView, "Failed to attach photo: " + e.getMessage(), Snackbar.LENGTH_LONG).show());
            }
        });
    }

    private void validateAndSave() {
        tilEditFirstName.setError(null);
        tilEditLastName.setError(null);
        tilEditStudentNumber.setError(null);
        tilEditPurpose.setError(null);
        tilEditAmount.setError(null);
        tilEditGcashRef.setError(null);

        String first = etEditFirstName.getText() != null ? etEditFirstName.getText().toString().trim() : "";
        String last = etEditLastName.getText() != null ? etEditLastName.getText().toString().trim() : "";
        String studentNo = etEditStudentNumber.getText() != null ? etEditStudentNumber.getText().toString().trim() : "";
        String purpose = etEditPurpose.getText() != null ? etEditPurpose.getText().toString().trim() : "";
        String amountStr = etEditAmount.getText() != null ? etEditAmount.getText().toString().trim() : "";
        String gcashRef = etEditGcashRef.getText() != null ? etEditGcashRef.getText().toString().trim() : "";

        boolean hasError = false;
        if (first.isEmpty()) {
            tilEditFirstName.setError("First name is required");
            hasError = true;
        }
        if (last.isEmpty()) {
            tilEditLastName.setError("Last name is required");
            hasError = true;
        }
        if (purpose.isEmpty()) {
            tilEditPurpose.setError("Purpose is required");
            hasError = true;
        }

        double amount = 0.0;
        try {
            amount = Double.parseDouble(amountStr);
            if (amount <= 0.0) {
                tilEditAmount.setError("Amount must be greater than ₱0.00");
                hasError = true;
            }
        } catch (NumberFormatException e) {
            tilEditAmount.setError("Enter a valid numeric amount");
            hasError = true;
        }

        if (!studentNo.isEmpty()) {
            if (!studentNo.startsWith("02000") || studentNo.length() != 11) {
                tilEditStudentNumber.setError("Student number must be 11 digits starting with 02000");
                hasError = true;
            }
        }

        String effectivePaymentMethod = selectedPaymentMethod;
        if (PaymentValidationHelper.PROVIDER_OTHER_ONLINE.equals(selectedPaymentMethod)) {
            String customName = etEditOtherProviderName.getText() != null
                    ? etEditOtherProviderName.getText().toString().trim()
                    : "";
            if (!customName.isEmpty()) {
                effectivePaymentMethod = customName;
            }
        }

        if (PaymentValidationHelper.isOnline(effectivePaymentMethod)) {
            boolean hasLocalImage = !isProofRemoved && selectedProofPath != null && new File(selectedProofPath).exists();
            boolean hasCloudImage = !isProofRemoved && currentPayment != null && (
                    (currentPayment.remoteProofUrl != null && !currentPayment.remoteProofUrl.trim().isEmpty())
                    || (currentPayment.proofImageUrl != null && !currentPayment.proofImageUrl.trim().isEmpty())
            );
            boolean hasAnyImage = hasLocalImage || hasCloudImage;
            boolean requireProofStrict = currentRoom != null && currentRoom.requireProofPhotoForOnline;

            if (requireProofStrict && !hasAnyImage) {
                tilEditGcashRef.setError("Proof photo is strictly required by room policy");
                Snackbar.make(rootView, "Proof photo is mandatory for this room.", Snackbar.LENGTH_LONG).show();
                hasError = true;
            } else {
                boolean valid = PaymentValidationHelper.validateOnlinePaymentProof(
                        effectivePaymentMethod,
                        gcashRef,
                        hasLocalImage,
                        hasCloudImage
                );
                if (!valid) {
                    tilEditGcashRef.setError("Reference ID or Proof Image is required for online payments");
                    Snackbar.make(rootView, PaymentValidationHelper.ERROR_ONLINE_PROOF_OR_REF_REQUIRED, Snackbar.LENGTH_LONG).show();
                    hasError = true;
                }
            }
        }

        if (hasError) {
            return;
        }

        selectedPaymentMethod = effectivePaymentMethod;

        // Extract selected reason tag if any
        String reasonTag = null;
        if (chipGroupEditReasons != null) {
            int checkedReasonId = chipGroupEditReasons.getCheckedChipId();
            if (checkedReasonId != View.NO_ID) {
                com.google.android.material.chip.Chip checkedChip = findViewById(checkedReasonId);
                if (checkedChip != null) {
                    reasonTag = checkedChip.getText().toString().trim();
                }
            }
        }

        btnSaveEditReceipt.setEnabled(false);
        loadingOverlay.show("Updating Receipt...", "Updating across all connected devices");

        repository.updateReceipt(
                receiptId,
                first,
                last,
                studentNo,
                amount,
                selectedPaymentMethod,
                gcashRef,
                purpose,
                isProofRemoved ? null : selectedProofPath,
                isProofRemoved,
                reasonTag,
                new PaymentRepository.ResultCallback<PaymentEntity>() {
                    @Override
                    public void onSuccess(PaymentEntity result) {
                        loadingOverlay.hide();
                        if (result != null && result.proofImagePath != null && !result.proofImagePath.trim().isEmpty() && !isProofRemoved) {
                            CloudImageStorageManager.uploadProofImage(EditReceiptActivity.this, result);
                        }
                        Toast.makeText(EditReceiptActivity.this, "Receipt updated successfully.", Toast.LENGTH_SHORT).show();
                        finish();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        btnSaveEditReceipt.setEnabled(true);
                        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                    }
                }
        );
    }

    @Override
    protected void onDestroy() {
        if (loadingOverlay != null) {
            loadingOverlay.release();
        }
        super.onDestroy();
    }
}
