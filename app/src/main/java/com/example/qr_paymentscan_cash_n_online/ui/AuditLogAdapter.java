package com.example.qr_paymentscan_cash_n_online.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.qr_paymentscan_cash_n_online.R;
import com.example.qr_paymentscan_cash_n_online.data.AuditLogEntity;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;

import java.util.ArrayList;
import java.util.List;

public class AuditLogAdapter extends RecyclerView.Adapter<AuditLogAdapter.AuditViewHolder> {

    private final List<AuditLogEntity> items = new ArrayList<>();

    public void submitList(List<AuditLogEntity> newList) {
        items.clear();
        if (newList != null) {
            items.addAll(newList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AuditViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audit_log, parent, false);
        return new AuditViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AuditViewHolder holder, int position) {
        AuditLogEntity item = items.get(position);
        Context context = holder.itemView.getContext();

        holder.tvActionBadge.setText(item.actionType);
        holder.tvTimestamp.setText(CurrencyFormatter.formatDateTime(item.timestamp));
        holder.tvDetails.setText(item.details);
        holder.tvActorBadge.setText("By " + item.getFormattedActorAttribution());

        StringBuilder meta = new StringBuilder();
        if (item.sectionName != null && !item.sectionName.isEmpty()) {
            meta.append("Folder/Room: ").append(item.sectionName);
        }
        if (item.receiptId != null && !item.receiptId.isEmpty()) {
            if (meta.length() > 0) {
                meta.append(" • ");
            }
            meta.append("Ref: ").append(item.receiptId);
        }
        if (meta.length() == 0) {
            meta.append("Workspace Event");
        }
        holder.tvMeta.setText(meta.toString());

        switch (item.actionType) {
            case AuditLogEntity.ACTION_PAYMENT_RECORDED:
                holder.ivIcon.setImageResource(R.drawable.ic_check_circle);
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_paid_green));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.status_paid_bg));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_paid_bg));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.status_paid_green));
                break;

            case AuditLogEntity.ACTION_EXPENSE_RECORDED:
                holder.ivIcon.setImageResource(R.drawable.ic_trending_down);
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_warning_amber));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.status_warning_bg));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_warning_bg));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.status_warning_amber));
                break;

            case AuditLogEntity.ACTION_PAYMENT_VOIDED:
            case AuditLogEntity.ACTION_EXPENSE_VOIDED:
            case AuditLogEntity.ACTION_FOLDER_DELETED:
            case AuditLogEntity.ACTION_MEMBER_REMOVED:
                holder.ivIcon.setImageResource(
                        AuditLogEntity.ACTION_FOLDER_DELETED.equals(item.actionType)
                                ? R.drawable.ic_delete_outline
                                : R.drawable.ic_block
                );
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_void_red));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.status_void_bg));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_void_bg));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.status_void_red));
                break;

            case AuditLogEntity.ACTION_QR_VERIFICATION_FAILED:
            case AuditLogEntity.ACTION_SECURITY_BRUTE_FORCE_ALERT:
            case AuditLogEntity.ACTION_SECURITY_JOIN_FAILED:
            case AuditLogEntity.ACTION_SECURITY_JOIN_DENIED:
            case AuditLogEntity.ACTION_SECURITY_MEMBER_BANNED:
            case AuditLogEntity.ACTION_SECURITY_ACCESS_REVOKED:
            case AuditLogEntity.ACTION_DUPLICATE_PAYMENT_BLOCKED:
            case AuditLogEntity.ACTION_DUPLICATE_SYNC_CONFLICT:
                holder.ivIcon.setImageResource(R.drawable.ic_gpp_bad);
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_void_red));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.status_void_bg));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_void_bg));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.status_void_red));
                break;

            case AuditLogEntity.ACTION_DUPLICATE_PAYMENT_OVERRIDE:
            case AuditLogEntity.ACTION_SECURITY_ROOM_LOCKED:
            case AuditLogEntity.ACTION_SECURITY_KEY_ROTATED:
            case AuditLogEntity.ACTION_SECURITY_SECRET_ROTATED:
            case AuditLogEntity.ACTION_SECURITY_CREDENTIALS_ROTATED:
                holder.ivIcon.setImageResource(R.drawable.ic_shield);
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_warning_amber));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.status_warning_bg));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_warning_bg));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.status_warning_amber));
                break;

            case AuditLogEntity.ACTION_SECURITY_MEMBER_APPROVED:
            case AuditLogEntity.ACTION_SECURITY_ROOM_UNLOCKED:
                holder.ivIcon.setImageResource(R.drawable.ic_shield);
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_paid_green));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.status_paid_bg));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_paid_bg));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.status_paid_green));
                break;

            case AuditLogEntity.ACTION_FOLDER_RENAMED:
                holder.ivIcon.setImageResource(R.drawable.ic_edit);
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.on_primary_container));
                break;

            case AuditLogEntity.ACTION_ROOM_CREATED:
            case AuditLogEntity.ACTION_MEMBER_JOINED:
            case AuditLogEntity.ACTION_MEMBER_ROLE_CHANGED:
                holder.ivIcon.setImageResource(R.drawable.ic_group);
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.on_primary_container));
                break;

            case AuditLogEntity.ACTION_EXCEL_EXPORTED:
                holder.ivIcon.setImageResource(R.drawable.ic_table_view);
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.on_primary_container));
                break;

            default:
                holder.ivIcon.setImageResource(R.drawable.ic_folder);
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary));
                holder.ivIcon.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container));
                holder.tvActionBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container));
                holder.tvActionBadge.setTextColor(ContextCompat.getColor(context, R.color.on_primary_container));
                break;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class AuditViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvActionBadge;
        final TextView tvTimestamp;
        final TextView tvDetails;
        final TextView tvActorBadge;
        final TextView tvMeta;

        AuditViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivAuditActionIcon);
            tvActionBadge = itemView.findViewById(R.id.tvAuditActionBadge);
            tvTimestamp = itemView.findViewById(R.id.tvAuditTimestamp);
            tvDetails = itemView.findViewById(R.id.tvAuditDetails);
            tvActorBadge = itemView.findViewById(R.id.tvAuditActorBadge);
            tvMeta = itemView.findViewById(R.id.tvAuditMeta);
        }
    }
}
