package com.example.qr_paymentscan_cash_n_online.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.qr_paymentscan_cash_n_online.R;
import com.example.qr_paymentscan_cash_n_online.data.AuditLogEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

public class ReceiptEditHistoryBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_RECEIPT_ID = "arg_receipt_id";

    public static ReceiptEditHistoryBottomSheet newInstance(String receiptId) {
        ReceiptEditHistoryBottomSheet sheet = new ReceiptEditHistoryBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_RECEIPT_ID, receiptId);
        sheet.setArguments(args);
        return sheet;
    }

    private String receiptId;
    private PaymentRepository repository;
    private EditHistoryAdapter adapter;
    private TextView tvEmpty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_receipt_edit_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getArguments() != null) {
            receiptId = getArguments().getString(ARG_RECEIPT_ID);
        }

        TextView tvSubtitle = view.findViewById(R.id.tvHistoryReceiptSubtitle);
        if (tvSubtitle != null && receiptId != null) {
            tvSubtitle.setText("Receipt " + receiptId + " • Immutable Audit Trail");
        }

        tvEmpty = view.findViewById(R.id.tvEmptyEditHistory);
        RecyclerView rvHistory = view.findViewById(R.id.rvReceiptEditHistory);
        rvHistory.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new EditHistoryAdapter();
        rvHistory.setAdapter(adapter);

        Context ctx = getContext();
        if (ctx != null && receiptId != null) {
            repository = new PaymentRepository(ctx);
            repository.observeAuditLogsByReceiptId(receiptId).observe(getViewLifecycleOwner(), logs -> {
                if (logs != null && !logs.isEmpty()) {
                    adapter.submitList(logs);
                    rvHistory.setVisibility(View.VISIBLE);
                    if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
                } else {
                    adapter.submitList(null);
                    rvHistory.setVisibility(View.GONE);
                    if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private static class EditHistoryAdapter extends RecyclerView.Adapter<EditHistoryViewHolder> {
        private final List<AuditLogEntity> items = new ArrayList<>();

        void submitList(List<AuditLogEntity> newItems) {
            items.clear();
            if (newItems != null) {
                items.addAll(newItems);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public EditHistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_receipt_edit_log, parent, false);
            return new EditHistoryViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull EditHistoryViewHolder holder, int position) {
            AuditLogEntity item = items.get(position);
            String actor = item.actorName != null ? item.actorName : "Officer";
            if (item.actorRole != null && !item.actorRole.trim().isEmpty()) {
                actor += " (" + item.actorRole.trim() + ")";
            }
            holder.tvActor.setText(actor);
            holder.tvTimestamp.setText(CurrencyFormatter.formatDateTime(item.timestamp));
            holder.tvDetails.setText(item.details != null ? item.details : item.actionType);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class EditHistoryViewHolder extends RecyclerView.ViewHolder {
        final TextView tvActor;
        final TextView tvTimestamp;
        final TextView tvDetails;

        EditHistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            tvActor = itemView.findViewById(R.id.tvLogActorName);
            tvTimestamp = itemView.findViewById(R.id.tvLogTimestamp);
            tvDetails = itemView.findViewById(R.id.tvLogDetails);
        }
    }
}
