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

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
    private MaterialButton btnVoidPayment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_receipt_detail);

        rootView = findViewById(R.id.receiptDetailRoot);
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
        btnVoidPayment = findViewById(R.id.btnVoidPayment);

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

        btnBack.setOnClickListener(v -> finish());
        btnSaveReceiptToGallery.setOnClickListener(v -> saveReceiptImage(false));
        btnShareReceiptImage.setOnClickListener(v -> saveReceiptImage(true));
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
        tvReceiptMethod.setText(payment.isGcash() ? "ONLINE (GCASH)" : "CASH");
        tvReceiptDateTime.setText(CurrencyFormatter.formatDateTime(payment.createdAt));
        if (tvReceiptRecordedBy != null) {
            tvReceiptRecordedBy.setText(payment.getFormattedActorAttribution());
        }

        if (payment.isGcash() && payment.gcashReferenceNo != null) {
            rowReceiptGcashRef.setVisibility(View.VISIBLE);
            tvReceiptGcashRef.setText(payment.gcashReferenceNo);
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
            boolean canVoid = UserSessionManager.getInstance(this).canVoidOrDeleteOrManageMembers();
            btnVoidPayment.setVisibility(canVoid ? View.VISIBLE : View.GONE);
        }

        String shortSig = payment.qrSignature.length() > 16
                ? payment.qrSignature.substring(0, 16) + "..."
                : payment.qrSignature;
        tvReceiptSignatureHash.setText("HMAC-SHA256: " + shortSig);

        Bitmap qrBitmap = SecuritySignatureHelper.generateQrCodeBitmap(payment.qrPayload, 520);
        if (qrBitmap != null) {
            ivReceiptQrCode.setImageBitmap(qrBitmap);
        }

        if (payment.isGcash() && payment.proofImagePath != null && new File(payment.proofImagePath).exists()) {
            cardReceiptGcashProof.setVisibility(View.VISIBLE);
            File proofFile = new File(payment.proofImagePath);
            Glide.with(this)
                    .load(proofFile)
                    .centerCrop()
                    .into(ivReceiptProofThumbnail);
            cardReceiptGcashProof.setOnClickListener(v -> showFullProofDialog(proofFile));
        } else {
            cardReceiptGcashProof.setVisibility(View.GONE);
        }
    }

    private void showFullProofDialog(File proofFile) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_image_viewer, null);
        ImageView ivFull = dialogView.findViewById(R.id.ivFullProofDialog);
        Glide.with(this).load(proofFile).into(ivFull);

        new MaterialAlertDialogBuilder(this)
                .setTitle("GCash Proof of Payment")
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

                repository.voidPayment(currentPayment.receiptId, reason, new PaymentRepository.ResultCallback<PaymentEntity>() {
                    @Override
                    public void onSuccess(PaymentEntity result) {
                        dialog.dismiss();
                        Snackbar.make(rootView, "Payment marked as VOIDED and recorded in Audit Log.", Snackbar.LENGTH_LONG).show();
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
}
