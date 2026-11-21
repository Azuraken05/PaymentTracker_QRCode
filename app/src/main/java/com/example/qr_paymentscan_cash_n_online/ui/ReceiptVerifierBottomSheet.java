package com.example.qr_paymentscan_cash_n_online.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.example.qr_paymentscan_cash_n_online.R;
import com.example.qr_paymentscan_cash_n_online.ReceiptDetailActivity;
import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.SecuritySignatureHelper;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.List;

public class ReceiptVerifierBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_QR_PAYLOAD = "arg_qr_payload";

    public static ReceiptVerifierBottomSheet newInstance(String rawQrPayload) {
        ReceiptVerifierBottomSheet sheet = new ReceiptVerifierBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_QR_PAYLOAD, rawQrPayload);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_receipt_verifier, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MaterialCardView cardVerdictHeader = view.findViewById(R.id.cardVerdictHeader);
        ImageView ivVerdictIcon = view.findViewById(R.id.ivVerdictIcon);
        TextView tvVerdictTitle = view.findViewById(R.id.tvVerdictTitle);
        TextView tvVerdictSubtitle = view.findViewById(R.id.tvVerdictSubtitle);

        LinearLayout layoutVoidAlert = view.findViewById(R.id.layoutVerifierVoidAlert);
        TextView tvVoidReason = view.findViewById(R.id.tvVerifierVoidReason);
        TextView tvVoidDate = view.findViewById(R.id.tvVerifierVoidDate);

        LinearLayout layoutDetails = view.findViewById(R.id.layoutVerifiedRecordDetails);
        TextView tvAmount = view.findViewById(R.id.tvVerifierAmount);
        TextView tvDetailsSummary = view.findViewById(R.id.tvVerifierDetailsSummary);
        MaterialButton btnViewProof = view.findViewById(R.id.btnVerifierViewProof);
        MaterialButton btnOpenFullReceipt = view.findViewById(R.id.btnVerifierOpenFullReceipt);
        MaterialButton btnDismiss = view.findViewById(R.id.btnVerifierDismiss);

        btnDismiss.setOnClickListener(v -> dismiss());

        String rawPayload = getArguments() != null ? getArguments().getString(ARG_QR_PAYLOAD, "") : "";
        PaymentRepository repository = new PaymentRepository(requireContext());

        if (rawPayload == null || !rawPayload.contains("|")) {
            renderCounterfeitState(
                    cardVerdictHeader,
                    ivVerdictIcon,
                    tvVerdictTitle,
                    tvVerdictSubtitle,
                    layoutVoidAlert,
                    layoutDetails,
                    "UNVERIFIED / INVALID QR FORMAT",
                    "This QR code does not contain a valid Student Payment Tracker cryptographic payload."
            );
            repository.logSecurityQrFailure(rawPayload, "Invalid QR format (missing signature delimiter)");
            return;
        }

        String[] parts = rawPayload.split("\\|", 2);
        String scannedReceiptId = parts[0].trim();
        String scannedSignature = parts[1].trim();

        AppDatabase.DB_EXECUTOR.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            PaymentEntity record = db.paymentDao().findByReceiptIdSync(scannedReceiptId);
            List<String> allRoomSecrets = db.roomDao().getAllRoomSigningSecretsSync();

            if (!isAdded()) {
                return;
            }

            if (record == null) {
                requireActivity().runOnUiThread(() -> renderCounterfeitState(
                        cardVerdictHeader,
                        ivVerdictIcon,
                        tvVerdictTitle,
                        tvVerdictSubtitle,
                        layoutVoidAlert,
                        layoutDetails,
                        "UNVERIFIED / RECEIPT NOT FOUND",
                        "Receipt ID \"" + scannedReceiptId + "\" does not exist in this workspace's database."
                ));
                repository.logSecurityQrFailure(rawPayload, "Receipt ID not found in local database: " + scannedReceiptId);
                return;
            }

            boolean signatureValid = SecuritySignatureHelper.verifyPaymentRecordAuthenticity(
                    requireContext(),
                    record,
                    scannedSignature,
                    allRoomSecrets
            );

            if (!signatureValid) {
                requireActivity().runOnUiThread(() -> renderCounterfeitState(
                        cardVerdictHeader,
                        ivVerdictIcon,
                        tvVerdictTitle,
                        tvVerdictSubtitle,
                        layoutVoidAlert,
                        layoutDetails,
                        "TAMPERED / FORGED QR DETECTED",
                        "HMAC-SHA256 cryptographic signature mismatch! This QR receipt may have been altered or forged."
                ));
                repository.logSecurityQrFailure(rawPayload, "HMAC-SHA256 signature mismatch for receipt " + scannedReceiptId);
                return;
            }

            requireActivity().runOnUiThread(() -> {
                layoutDetails.setVisibility(View.VISIBLE);
                tvAmount.setText(CurrencyFormatter.formatPhp(record.amount));

                StringBuilder summary = new StringBuilder();
                summary.append("Receipt ID: ").append(record.receiptId).append("\n");
                summary.append("Student Name: ").append(record.getFullName()).append("\n");
                if (record.hasStudentNumber()) {
                    summary.append("Student No.: ").append(record.studentNumber).append("\n");
                }
                summary.append("Section Folder: ").append(record.sectionName).append("\n");
                summary.append("Payment Purpose: ").append(record.purpose).append("\n");
                summary.append("Payment Method: ").append(record.paymentMethod);
                if (record.isGcash() && record.gcashReferenceNo != null) {
                    summary.append(" (Ref: ").append(record.gcashReferenceNo).append(")");
                }
                summary.append("\nRecorded By: ").append(record.getFormattedActorAttribution());
                summary.append("\nDate Recorded: ").append(CurrencyFormatter.formatDateTime(record.createdAt));
                tvDetailsSummary.setText(summary.toString());

                if (record.isVoided()) {
                    int warnBg = ContextCompat.getColor(requireContext(), R.color.status_warning_bg);
                    int warnColor = ContextCompat.getColor(requireContext(), R.color.status_void_red);
                    cardVerdictHeader.setCardBackgroundColor(warnBg);
                    cardVerdictHeader.setStrokeColor(warnColor);
                    ivVerdictIcon.setImageResource(R.drawable.ic_warning);
                    ivVerdictIcon.setColorFilter(warnColor);
                    tvVerdictTitle.setText("AUTHENTIC — BUT VOIDED");
                    tvVerdictTitle.setTextColor(warnColor);
                    tvVerdictSubtitle.setText("This receipt was genuinely issued by this system, but it has been VOIDED and is no longer valid for payment proof.");

                    layoutVoidAlert.setVisibility(View.VISIBLE);
                    tvVoidReason.setText("VOID REASON: " + (record.voidReason != null ? record.voidReason : "Unspecified"));
                    if (record.voidedAt != null) {
                        tvVoidDate.setText("Voided On: " + CurrencyFormatter.formatDateTime(record.voidedAt));
                    } else {
                        tvVoidDate.setText("Status: VOIDED");
                    }
                } else {
                    int paidBg = ContextCompat.getColor(requireContext(), R.color.status_paid_bg);
                    int paidColor = ContextCompat.getColor(requireContext(), R.color.status_paid_green);
                    cardVerdictHeader.setCardBackgroundColor(paidBg);
                    cardVerdictHeader.setStrokeColor(paidColor);
                    ivVerdictIcon.setImageResource(R.drawable.ic_verified);
                    ivVerdictIcon.setColorFilter(paidColor);
                    tvVerdictTitle.setText("AUTHENTIC & VALID RECEIPT");
                    tvVerdictTitle.setTextColor(paidColor);
                    tvVerdictSubtitle.setText("HMAC-SHA256 cryptographic signature verified against room signing key. Status: PAID.");
                    layoutVoidAlert.setVisibility(View.GONE);
                }

                if (record.isGcash() && record.proofImagePath != null && new File(record.proofImagePath).exists()) {
                    btnViewProof.setVisibility(View.VISIBLE);
                    btnViewProof.setOnClickListener(v -> showFullProofDialog(record.proofImagePath));
                } else {
                    btnViewProof.setVisibility(View.GONE);
                }

                btnOpenFullReceipt.setOnClickListener(v -> {
                    dismiss();
                    Intent intent = new Intent(requireContext(), ReceiptDetailActivity.class);
                    intent.putExtra(ReceiptDetailActivity.EXTRA_RECEIPT_ID, record.receiptId);
                    startActivity(intent);
                });
            });
        });
    }

    private void renderCounterfeitState(
            MaterialCardView cardVerdictHeader,
            ImageView ivVerdictIcon,
            TextView tvVerdictTitle,
            TextView tvVerdictSubtitle,
            LinearLayout layoutVoidAlert,
            LinearLayout layoutDetails,
            String title,
            String subtitle
    ) {
        int badBg = ContextCompat.getColor(requireContext(), R.color.status_void_bg);
        int badColor = ContextCompat.getColor(requireContext(), R.color.status_void_red);
        cardVerdictHeader.setCardBackgroundColor(badBg);
        cardVerdictHeader.setStrokeColor(badColor);
        ivVerdictIcon.setImageResource(R.drawable.ic_gpp_bad);
        ivVerdictIcon.setColorFilter(badColor);
        tvVerdictTitle.setText(title);
        tvVerdictTitle.setTextColor(badColor);
        tvVerdictSubtitle.setText(subtitle);
        layoutVoidAlert.setVisibility(View.GONE);
        layoutDetails.setVisibility(View.GONE);
    }

    private void showFullProofDialog(String proofPath) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_image_viewer, null);
        ImageView ivFull = dialogView.findViewById(R.id.ivFullProofDialog);
        Glide.with(this)
                .load(new File(proofPath))
                .into(ivFull);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Verified GCash Proof of Payment")
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show();
    }
}
