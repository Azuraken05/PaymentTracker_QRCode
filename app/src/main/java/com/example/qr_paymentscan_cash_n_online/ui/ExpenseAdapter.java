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
import com.example.qr_paymentscan_cash_n_online.data.ExpenseEntity;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ExpenseAdapter extends RecyclerView.Adapter<ExpenseAdapter.ExpenseViewHolder> {

    public interface OnExpenseActionListener {
        void onViewProofClicked(ExpenseEntity expense);
        void onVoidExpenseClicked(ExpenseEntity expense);
    }

    private final List<ExpenseEntity> items = new ArrayList<>();
    private final OnExpenseActionListener listener;

    public ExpenseAdapter(OnExpenseActionListener listener) {
        this.listener = listener;
    }

    public void submitList(List<ExpenseEntity> newList) {
        items.clear();
        if (newList != null) {
            items.addAll(newList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ExpenseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_expense_record, parent, false);
        return new ExpenseViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ExpenseViewHolder holder, int position) {
        ExpenseEntity item = items.get(position);
        Context context = holder.itemView.getContext();
        UserSessionManager session = UserSessionManager.getInstance(context);

        holder.tvTitle.setText(item.title);
        String catAndNotes = item.category;
        if (item.notes != null && !item.notes.trim().isEmpty()) {
            catAndNotes += " • " + item.notes.trim();
        }
        holder.tvCategoryAndNotes.setText(catAndNotes);
        holder.tvRecordedBy.setText("Recorded by: " + item.getFormattedActorAttribution());
        holder.tvAmount.setText("- " + CurrencyFormatter.formatPhp(item.amount));
        holder.tvDate.setText(CurrencyFormatter.formatDateTime(item.createdAt));

        boolean hasProof = item.proofImagePath != null && new File(item.proofImagePath).exists();
        holder.btnViewProof.setVisibility(hasProof ? View.VISIBLE : View.GONE);
        holder.btnViewProof.setOnClickListener(v -> {
            if (listener != null) {
                listener.onViewProofClicked(item);
            }
        });

        if (item.isVoided()) {
            holder.tvStatusBadge.setText("VOIDED (RESTORED)");
            holder.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_void_bg));
            holder.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_void_red));
            holder.tvAmount.setPaintFlags(holder.tvAmount.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            holder.btnVoidExpense.setVisibility(View.GONE);
            holder.tvVoidReasonRow.setVisibility(View.VISIBLE);
            holder.tvVoidReasonRow.setText("VOID REASON: " + (item.voidReason != null ? item.voidReason : "Unspecified"));
        } else {
            holder.tvStatusBadge.setText("DEDUCTED");
            holder.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_warning_bg));
            holder.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_warning_amber));
            holder.tvAmount.setPaintFlags(holder.tvAmount.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            boolean canVoid = session.canVoidOrDeleteOrManageMembers();
            holder.btnVoidExpense.setVisibility(canVoid ? View.VISIBLE : View.GONE);
            holder.tvVoidReasonRow.setVisibility(View.GONE);
            holder.btnVoidExpense.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onVoidExpenseClicked(item);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ExpenseViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvTitle;
        final TextView tvCategoryAndNotes;
        final TextView tvRecordedBy;
        final TextView tvAmount;
        final TextView tvStatusBadge;
        final TextView tvDate;
        final MaterialButton btnViewProof;
        final MaterialButton btnVoidExpense;
        final TextView tvVoidReasonRow;

        ExpenseViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivExpenseIcon);
            tvTitle = itemView.findViewById(R.id.tvExpenseTitle);
            tvCategoryAndNotes = itemView.findViewById(R.id.tvExpenseCategoryAndNotes);
            tvRecordedBy = itemView.findViewById(R.id.tvExpenseRecordedBy);
            tvAmount = itemView.findViewById(R.id.tvExpenseAmount);
            tvStatusBadge = itemView.findViewById(R.id.tvExpenseStatusBadge);
            tvDate = itemView.findViewById(R.id.tvExpenseDate);
            btnViewProof = itemView.findViewById(R.id.btnExpenseViewProof);
            btnVoidExpense = itemView.findViewById(R.id.btnVoidExpenseItem);
            tvVoidReasonRow = itemView.findViewById(R.id.tvExpenseVoidReasonRow);
        }
    }
}
