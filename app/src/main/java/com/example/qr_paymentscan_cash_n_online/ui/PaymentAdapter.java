package com.example.qr_paymentscan_cash_n_online.ui;

import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.qr_paymentscan_cash_n_online.R;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;

import java.util.ArrayList;
import java.util.List;

public class PaymentAdapter extends RecyclerView.Adapter<PaymentAdapter.PaymentViewHolder> {

    public interface OnPaymentClickListener {
        void onPaymentClicked(PaymentEntity payment);
    }

    private final List<PaymentEntity> items = new ArrayList<>();
    private final OnPaymentClickListener listener;

    public PaymentAdapter(OnPaymentClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<PaymentEntity> newList) {
        items.clear();
        if (newList != null) {
            items.addAll(newList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PaymentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_payment_record, parent, false);
        return new PaymentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PaymentViewHolder holder, int position) {
        PaymentEntity item = items.get(position);
        Context context = holder.itemView.getContext();

        String studentTitle = item.hasStudentNumber()
                ? item.getFullName() + " (" + item.studentNumber + ")"
                : item.getFullName();
        holder.tvStudentName.setText(studentTitle);
        holder.tvPurpose.setText(item.purpose);
        holder.tvAmount.setText(CurrencyFormatter.formatPhp(item.amount));
        holder.tvReceiptId.setText(item.receiptId);
        holder.tvDate.setText(CurrencyFormatter.formatDateTime(item.createdAt));

        if (item.isGcash()) {
            String refSuffix = (item.gcashReferenceNo != null && !item.gcashReferenceNo.isEmpty())
                    ? " • Ref: " + item.gcashReferenceNo
                    : "";
            holder.tvMethodBadge.setText("GCASH" + refSuffix);
            holder.tvMethodBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.gcash_blue_container));
            holder.tvMethodBadge.setTextColor(ContextCompat.getColor(context, R.color.gcash_blue_dark));
            holder.ivIcon.setImageResource(R.drawable.ic_qr_code_2);
        } else {
            holder.tvMethodBadge.setText("CASH");
            holder.tvMethodBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container));
            holder.tvMethodBadge.setTextColor(ContextCompat.getColor(context, R.color.on_primary_container));
            holder.ivIcon.setImageResource(R.drawable.ic_payments);
        }

        if (item.isVoided()) {
            holder.tvStatusBadge.setText("VOIDED");
            holder.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_void_bg));
            holder.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_void_red));
            holder.tvAmount.setTextColor(ContextCompat.getColor(context, R.color.status_void_red));
            holder.tvAmount.setPaintFlags(holder.tvAmount.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);

            holder.tvVoidReasonRow.setVisibility(View.VISIBLE);
            holder.tvVoidReasonRow.setText("VOID REASON: " + (item.voidReason != null ? item.voidReason : "Unspecified"));
        } else {
            holder.tvStatusBadge.setText("PAID");
            holder.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_paid_bg));
            holder.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_paid_green));
            holder.tvAmount.setTextColor(ContextCompat.getColor(context, R.color.primary));
            holder.tvAmount.setPaintFlags(holder.tvAmount.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            holder.tvVoidReasonRow.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPaymentClicked(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class PaymentViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvStudentName;
        final TextView tvPurpose;
        final TextView tvAmount;
        final TextView tvStatusBadge;
        final TextView tvMethodBadge;
        final TextView tvReceiptId;
        final TextView tvDate;
        final TextView tvVoidReasonRow;

        PaymentViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivPaymentIcon);
            tvStudentName = itemView.findViewById(R.id.tvPaymentStudentName);
            tvPurpose = itemView.findViewById(R.id.tvPaymentPurpose);
            tvAmount = itemView.findViewById(R.id.tvPaymentAmount);
            tvStatusBadge = itemView.findViewById(R.id.tvPaymentStatusBadge);
            tvMethodBadge = itemView.findViewById(R.id.tvPaymentMethodBadge);
            tvReceiptId = itemView.findViewById(R.id.tvPaymentReceiptId);
            tvDate = itemView.findViewById(R.id.tvPaymentDate);
            tvVoidReasonRow = itemView.findViewById(R.id.tvPaymentVoidReasonRow);
        }
    }
}
