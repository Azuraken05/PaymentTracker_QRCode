package com.example.qr_paymentscan_cash_n_online;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

import com.bumptech.glide.Glide;
import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.ui.LoadingOverlayController;
import com.example.qr_paymentscan_cash_n_online.util.CloudImageStorageManager;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.LocalStorageManager;
import com.example.qr_paymentscan_cash_n_online.util.SecuritySignatureHelper;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;

public class ReceiptDetailActivity extends BaseThemedActivity {

    public static final String EXTRA_RECEIPT_ID = "extra_receipt_id";

    private String receiptId;
    private PaymentRepository repository;
    private PaymentEntity currentPayment;

    private View rootView;
    private MaterialCardView receiptContainer;
    private TextView tvReceiptSectionName;
    private LinearLayout layoutReceiptStatusBanner;
    private ImageView ivReceiptStatusIcon;
    private TextView tvReceiptStatusText;
    private TextView tvReceiptAmountHero;

    private LinearLayout layoutReceiptVoidInfo;
    private TextView tvReceiptVoidReason;
    private TextView tvReceiptVoidTimestamp;

    private TextView tvReceiptIdValue;
    private TextView tvReceiptStudentName;
    private LinearLayout rowReceiptStudentNumber;
    private TextView tvReceiptStudentNumber;
    private TextView tvReceiptPurpose;
    private TextView tvReceiptMethod;
    private LinearLayout rowReceiptGcashRef;
    private TextView tvReceiptGcashRef;
    private TextView tvReceiptDateTime;
    private TextView tvReceiptRecordedBy;
    private ImageView ivReceiptQrCode;
    private TextView tvReceiptSignatureHash;

    private MaterialCardView cardReceiptGcashProof;
    private ImageView ivReceiptProofThumbnail;
    private TextView tvReceiptProofStatus;
    private MaterialButton btnRetryProofUpload;
    private MaterialButton btnEditReceipt;
    private MaterialButton btnVoidPayment;
    private TextView tvReceiptEditedBadge;
    private LoadingOverlayController loadingOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_receipt_detail);

        rootView = findViewById(R.id.receiptDetailRoot);
        loadingOverlay = new LoadingOverlayController(this);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        receiptId = getIntent().getStringExtra(EXTRA_RECEIPT_ID);
        if (receiptId == null || receiptId.isEmpty()) {
            finish();
            return;
        }

        repository = new PaymentRepository(this);

        ImageButton btnBack = findViewById(R.id.btnBackReceipt);
        MaterialButton btnShareReceiptImage = findViewById(R.id.btnShareReceiptImage);
        MaterialButton btnSaveReceiptToGallery = findViewById(R.id.btnSaveReceiptToGallery);
        btnEditReceipt = findViewById(R.id.btnEditReceipt);
        btnVoidPayment = findViewById(R.id.btnVoidPayment);
        tvReceiptEditedBadge = findViewById(R.id.tvReceiptEditedBadge);

        receiptContainer = findViewById(R.id.receipt_container);
        tvReceiptSectionName = findViewById(R.id.tvReceiptSectionName);
        layoutReceiptStatusBanner = findViewById(R.id.layoutReceiptStatusBanner);
        ivReceiptStatusIcon = findViewById(R.id.ivReceiptStatusIcon);
        tvReceiptStatusText = findViewById(R.id.tvReceiptStatusText);
        tvReceiptAmountHero = findViewById(R.id.tvReceiptAmountHero);

        layoutReceiptVoidInfo = findViewById(R.id.layoutReceiptVoidInfo);
        tvReceiptVoidReason = findViewById(R.id.tvReceiptVoidReason);
        tvReceiptVoidTimestamp = findViewById(R.id.tvReceiptVoidTimestamp);

        tvReceiptIdValue = findViewById(R.id.tvReceiptIdValue);
        tvReceiptStudentName = findViewById(R.id.tvReceiptStudentName);
        rowReceiptStudentNumber = findViewById(R.id.rowReceiptStudentNumber);
        tvReceiptStudentNumber = findViewById(R.id.tvReceiptStudentNumber);
        tvReceiptPurpose = findViewById(R.id.tvReceiptPurpose);
        tvReceiptMethod = findViewById(R.id.tvReceiptMethod);
        rowReceiptGcashRef = findViewById(R.id.rowReceiptGcashRef);
        tvReceiptGcashRef = findViewById(R.id.tvReceiptGcashRef);
        tvReceiptDateTime = findViewById(R.id.tvReceiptDateTime);
        tvReceiptRecordedBy = findViewById(R.id.tvReceiptRecordedBy);
        ivReceiptQrCode = findViewById(R.id.ivReceiptQrCode);
        tvReceiptSignatureHash = findViewById(R.id.tvReceiptSignatureHash);

        cardReceiptGcashProof = findViewById(R.id.cardReceiptGcashProof);
        ivReceiptProofThumbnail = findViewById(R.id.ivReceiptProofThumbnail);
        tvReceiptProofStatus = findViewById(R.id.tvReceiptProofStatus);
        btnRetryProofUpload = findViewById(R.id.btnRetryProofUpload);

        btnBack.setOnClickListener(v -> finish());
        btnSaveReceiptToGallery.setOnClickListener(v -> saveReceiptImage(false));
        btnShareReceiptImage.setOnClickListener(v -> saveReceiptImage(true));
        btnEditReceipt.setOnClickListener(v -> openEditReceiptScreen());
        btnVoidPayment.setOnClickListener(v -> showVoidPaymentDialog());

        repository.observePaymentByReceiptId(receiptId).observe(this, payment -> {
            if (payment != null) {
                currentPayment = payment;
                bindReceiptDetails(payment);
            }
        });
    }

    private void bindReceiptDetails(PaymentEntity payment) {
        tvReceiptSectionName.setText(payment.sectionName);
        tvReceiptAmountHero.setText(CurrencyFormatter.formatPhp(payment.amount));
        tvReceiptIdValue.setText(payment.receiptId);
        tvReceiptStudentName.setText(payment.getFullName());

        if (payment.hasStudentNumber()) {
            rowReceiptStudentNumber.setVisibility(View.VISIBLE);
            tvReceiptStudentNumber.setText(payment.studentNumber);
        } else {
            rowReceiptStudentNumber.setVisibility(View.GONE);
        }

        tvReceiptPurpose.setText(payment.purpose);
        String displayMethod = payment.paymentMethod != null ? payment.paymentMethod.toUpperCase(Locale.US) : "CASH";
        tvReceiptMethod.setText(displayMethod);
        tvReceiptDateTime.setText(CurrencyFormatter.formatDateTime(payment.createdAt));
        if (tvReceiptRecordedBy != null) {
            tvReceiptRecordedBy.setText(payment.getFormattedActorAttribution());
        }

        boolean hasProofImage = (payment.proofImagePath != null && !payment.proofImagePath.trim().isEmpty())
                || (payment.remoteProofUrl != null && !payment.remoteProofUrl.trim().isEmpty())
                || (payment.proofImageUrl != null && !payment.proofImageUrl.trim().isEmpty());
        String refDisplay = com.example.qr_paymentscan_cash_n_online.util.PaymentValidationHelper.formatReferenceDisplay(
                payment.paymentMethod,
                payment.gcashReferenceNo,
                hasProofImage
        );
        if (payment.isOnline() || (payment.gcashReferenceNo != null && !payment.gcashReferenceNo.trim().isEmpty())) {
            rowReceiptGcashRef.setVisibility(View.VISIBLE);
            tvReceiptGcashRef.setText(refDisplay);
        } else {
            rowReceiptGcashRef.setVisibility(View.GONE);
        }

        if (payment.isVoided()) {
            int voidBg = ContextCompat.getColor(this, R.color.status_void_bg);
            int voidColor = ContextCompat.getColor(this, R.color.status_void_red);
            layoutReceiptStatusBanner.setBackgroundColor(voidBg);
            ivReceiptStatusIcon.setImageResource(R.drawable.ic_block);
            ivReceiptStatusIcon.setColorFilter(voidColor);
            tvReceiptStatusText.setText("VOIDED • INVALID FOR PAYMENT");
            tvReceiptStatusText.setTextColor(voidColor);

            tvReceiptAmountHero.setTextColor(voidColor);
            tvReceiptAmountHero.setPaintFlags(tvReceiptAmountHero.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);

            layoutReceiptVoidInfo.setVisibility(View.VISIBLE);
            tvReceiptVoidReason.setText("VOID REASON: " + (payment.voidReason != null ? payment.voidReason : "Unspecified"));
            if (payment.voidedAt != null) {
                tvReceiptVoidTimestamp.setText("Voided On: " + CurrencyFormatter.formatDateTime(payment.voidedAt));
            } else {
                tvReceiptVoidTimestamp.setText("Status: VOIDED");
            }
            btnVoidPayment.setVisibility(View.GONE);
        } else {
            int paidBg = ContextCompat.getColor(this, R.color.status_paid_bg);
            int paidColor = ContextCompat.getColor(this, R.color.status_paid_green);
            layoutReceiptStatusBanner.setBackgroundColor(paidBg);
            ivReceiptStatusIcon.setImageResource(R.drawable.ic_verified);
            ivReceiptStatusIcon.setColorFilter(paidColor);
            tvReceiptStatusText.setText("PAID • AUTHENTIC RECORD");
            tvReceiptStatusText.setTextColor(paidColor);

            tvReceiptAmountHero.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            tvReceiptAmountHero.setPaintFlags(tvReceiptAmountHero.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));

            layoutReceiptVoidInfo.setVisibility(View.GONE);
            boolean canVoidOrEdit = UserSessionManager.getInstance(this).canVoidOrEditRecords();
            btnEditReceipt.setVisibility(canVoidOrEdit ? View.VISIBLE : View.GONE);
            btnVoidPayment.setVisibility(canVoidOrEdit ? View.VISIBLE : View.GONE);
        }

        if (payment.lastEditedAt > 0L) {
            tvReceiptEditedBadge.setVisibility(View.VISIBLE);
            String editor = payment.lastEditedByName != null ? payment.lastEditedByName : "Officer";
            tvReceiptEditedBadge.setText("Edited by " + editor + " on " + CurrencyFormatter.formatDateTime(payment.lastEditedAt));
        } else {
            tvReceiptEditedBadge.setVisibility(View.GONE);
        }

        String shortSig = payment.qrSignature.length() > 16
                ? payment.qrSignature.substring(0, 16) + "..."
                : payment.qrSignature;
        tvReceiptSignatureHash.setText("HMAC-SHA256: " + shortSig);

        Bitmap qrBitmap = SecuritySignatureHelper.generateQrCodeBitmap(payment.qrPayload, 520);
        if (qrBitmap != null) {
            ivReceiptQrCode.setImageBitmap(qrBitmap);
        }

        String displayProofPath = payment.proofImagePath;
        String displayProofUrl = payment.proofImageUrl != null ? payment.proofImageUrl : payment.remoteProofUrl;

        boolean hasCloudUrl = displayProofUrl != null && displayProofUrl.startsWith("http");
        boolean hasLocalFile = displayProofPath != null && new File(displayProofPath).exists();

        if (payment.isGcash() && (hasCloudUrl || hasLocalFile || (displayProofPath != null && !displayProofPath.trim().isEmpty()))) {
            cardReceiptGcashProof.setVisibility(View.VISIBLE);
            if (hasCloudUrl) {
                // Load cloud URL first for other users and cross-device sync
                Glide.with(this).load(displayProofUrl).centerCrop().into(ivReceiptProofThumbnail);
                cardReceiptGcashProof.setOnClickListener(v -> showFullProofDialog(null, displayProofUrl));
                if (tvReceiptProofStatus != null) {
                    tvReceiptProofStatus.setVisibility(View.GONE);
                }
                if (btnRetryProofUpload != null) {
                    btnRetryProofUpload.setVisibility(View.GONE);
                }
            } else if (hasLocalFile) {
                File proofFile = new File(displayProofPath);
                Glide.with(this).load(proofFile).centerCrop().into(ivReceiptProofThumbnail);
                cardReceiptGcashProof.setOnClickListener(v -> showFullProofDialog(proofFile, null));

                boolean canUpload = UserSessionManager.getInstance(this).canCreateOrEditRecords();
                if (tvReceiptProofStatus != null) {
                    tvReceiptProofStatus.setVisibility(View.VISIBLE);
                    tvReceiptProofStatus.setText("Pending Cloudinary upload");
                }
                if (btnRetryProofUpload != null) {
                    btnRetryProofUpload.setVisibility(canUpload ? View.VISIBLE : View.GONE);
                    btnRetryProofUpload.setOnClickListener(v -> {
                        btnRetryProofUpload.setEnabled(false);
                        Toast.makeText(this, "Uploading proof photo to Cloudinary...", Toast.LENGTH_SHORT).show();
                        CloudImageStorageManager.uploadProofImage(this, payment, new CloudImageStorageManager.UploadCallback() {
                            @Override
                            public void onSuccess(String secureUrl) {
                                runOnUiThread(() -> {
                                    btnRetryProofUpload.setEnabled(true);
                                    btnRetryProofUpload.setVisibility(View.GONE);
                                    if (tvReceiptProofStatus != null) {
                                        tvReceiptProofStatus.setVisibility(View.GONE);
                                    }
                                    Toast.makeText(ReceiptDetailActivity.this, "Proof photo uploaded successfully!", Toast.LENGTH_SHORT).show();
                                });
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                runOnUiThread(() -> {
                                    btnRetryProofUpload.setEnabled(true);
                                    Toast.makeText(ReceiptDetailActivity.this, "Upload failed: " + errorMessage, Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                    });
                }
            } else {
                // Proof attached on another device but not uploaded to cloud yet
                ivReceiptProofThumbnail.setImageResource(R.drawable.ic_image);
                cardReceiptGcashProof.setOnClickListener(null);
                if (tvReceiptProofStatus != null) {
                    tvReceiptProofStatus.setVisibility(View.VISIBLE);
                    tvReceiptProofStatus.setText("Waiting for collector's device to finish uploading proof photo.");
                }
                if (btnRetryProofUpload != null) {
                    btnRetryProofUpload.setVisibility(View.GONE);
                }
            }
        } else {
            cardReceiptGcashProof.setVisibility(View.GONE);
        }
    }

    private void openEditReceiptScreen() {
        if (!UserSessionManager.getInstance(this).canVoidOrEditRecords()) {
            Snackbar.make(rootView, "Auditors have read-only access.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, EditReceiptActivity.class);
        intent.putExtra(EditReceiptActivity.EXTRA_RECEIPT_ID, receiptId);
        startActivity(intent);
    }

    private void showFullProofDialog(File proofFile, String proofUrl) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_image_viewer, null);
        ImageView ivFull = dialogView.findViewById(R.id.ivFullProofDialog);
        if (proofFile != null && proofFile.exists()) {
            Glide.with(this).load(proofFile).into(ivFull);
        } else if (proofUrl != null) {
            Glide.with(this).load(proofUrl).into(ivFull);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Proof of Payment")
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show();
    }

    private void saveReceiptImage(boolean shareAfterSave) {
        if (currentPayment == null) {
            return;
        }
        Bitmap renderedReceipt = LocalStorageManager.captureViewBitmap(receiptContainer);
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                LocalStorageManager.SavedMediaResult saved = LocalStorageManager.saveReceiptBitmapToPublicPictures(
                        this,
                        renderedReceipt,
                        currentPayment.receiptId
                );
                runOnUiThread(() -> {
                    Snackbar.make(rootView, "Saved QR Receipt to " + saved.displayPath, Snackbar.LENGTH_LONG).show();
                    if (shareAfterSave) {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("image/png");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, saved.uri);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, "Share Student QR Receipt"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Snackbar.make(
                        rootView,
                        "Failed to save receipt image: " + e.getMessage(),
                        Snackbar.LENGTH_LONG
                ).show());
            }
        });
    }

    private void showVoidPaymentDialog() {
        if (currentPayment == null || currentPayment.isVoided()) {
            return;
        }
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_void_payment, null);
        TextInputLayout tilVoidReason = dialogView.findViewById(R.id.tilVoidReason);
        TextInputEditText etVoidReason = dialogView.findViewById(R.id.etVoidReason);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Void Payment Receipt?")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Confirm Void", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String reason = etVoidReason.getText() != null ? etVoidReason.getText().toString().trim() : "";
                if (reason.isEmpty()) {
                    tilVoidReason.setError("A mandatory reason is required to void this payment");
                    return;
                }
                tilVoidReason.setError(null);

                loadingOverlay.show("Voiding Record...", "Updating across all connected devices");
                repository.voidPayment(currentPayment.receiptId, reason, new PaymentRepository.ResultCallback<PaymentEntity>() {
                    @Override
                    public void onSuccess(PaymentEntity result) {
                        loadingOverlay.hide();
                        dialog.dismiss();
                        Snackbar.make(rootView, "Payment marked as VOIDED and recorded in Audit Log.", Snackbar.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        tilVoidReason.setError(message);
                    }
                });
            });
        });

        dialog.show();
    }

    @Override
    protected void onDestroy() {
        if (loadingOverlay != null) {
            loadingOverlay.release();
        }
        super.onDestroy();
    }
}
