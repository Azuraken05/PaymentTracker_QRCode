package com.example.qr_paymentscan_cash_n_online.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.qr_paymentscan_cash_n_online.R;
import com.example.qr_paymentscan_cash_n_online.data.SectionSummary;
import com.example.qr_paymentscan_cash_n_online.util.CurrencyFormatter;
import com.example.qr_paymentscan_cash_n_online.util.UserSessionManager;

import java.util.ArrayList;
import java.util.List;

public class SectionAdapter extends RecyclerView.Adapter<SectionAdapter.SectionViewHolder> {

    public interface OnSectionClickListener {
        void onSectionClicked(SectionSummary section);
    }

    public interface OnSectionRenameClickListener {
        void onSectionRenameClicked(SectionSummary section);
    }

    public interface OnSectionDeleteClickListener {
        void onSectionDeleteClicked(SectionSummary section);
    }

    private final List<SectionSummary> items = new ArrayList<>();
    private final OnSectionClickListener clickListener;
    private final OnSectionRenameClickListener renameListener;
    private final OnSectionDeleteClickListener deleteListener;

    public SectionAdapter(
            OnSectionClickListener clickListener,
            OnSectionDeleteClickListener deleteListener
    ) {
        this(clickListener, null, deleteListener);
    }

    public SectionAdapter(
            OnSectionClickListener clickListener,
            OnSectionRenameClickListener renameListener,
            OnSectionDeleteClickListener deleteListener
    ) {
        this.clickListener = clickListener;
        this.renameListener = renameListener;
        this.deleteListener = deleteListener;
    }

    public void submitList(List<SectionSummary> newList) {
        items.clear();
        if (newList != null) {
            items.addAll(newList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_section_folder, parent, false);
        return new SectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionViewHolder holder, int position) {
        SectionSummary item = items.get(position);
        Context context = holder.itemView.getContext();
        UserSessionManager session = UserSessionManager.getInstance(context);

        holder.tvName.setText(item.sectionName);
        if (item.description != null && !item.description.trim().isEmpty()) {
            holder.tvDesc.setText(item.description);
        } else {
            holder.tvDesc.setText(item.parentFolderId == null
                    ? "Main Section Folder"
                    : "Sub-Folder Collection");
        }

        holder.tvSubfolderCount.setText(item.subfolderCount
                + (item.subfolderCount == 1 ? " Sub-folder" : " Sub-folders"));
        int totalReceipts = item.validCount + item.voidedCount;
        holder.tvValidCount.setText(totalReceipts + (totalReceipts == 1 ? " Receipt" : " Receipts"));
        holder.tvCreatedDate.setText(CurrencyFormatter.formatShortDate(item.createdAt));

        if (item.parentFolderId != null) {
            holder.tvItemMergeBadge.setVisibility(View.VISIBLE);
            if (item.mergeGrossToParent) {
                holder.tvItemMergeBadge.setText("Merged to Parent");
                holder.tvItemMergeBadge.setTextColor(ContextCompat.getColor(context, R.color.status_paid_green));
                holder.tvItemMergeBadge.setBackgroundTintList(
                        ContextCompat.getColorStateList(context, R.color.status_paid_bg)
                );
                TextViewCompat.setCompoundDrawableTintList(
                        holder.tvItemMergeBadge,
                        ContextCompat.getColorStateList(context, R.color.status_paid_green)
                );
            } else {
                holder.tvItemMergeBadge.setText("Separate");
                holder.tvItemMergeBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
                holder.tvItemMergeBadge.setBackgroundTintList(
                        ContextCompat.getColorStateList(context, R.color.surface_variant)
                );
                TextViewCompat.setCompoundDrawableTintList(
                        holder.tvItemMergeBadge,
                        ContextCompat.getColorStateList(context, R.color.text_secondary)
                );
            }
        } else {
            holder.tvItemMergeBadge.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onSectionClicked(item);
            }
        });

        boolean canRename = session.canCreateOrEditRecords() && renameListener != null;
        holder.btnRename.setVisibility(canRename ? View.VISIBLE : View.GONE);
        holder.btnRename.setOnClickListener(v -> {
            if (renameListener != null) {
                renameListener.onSectionRenameClicked(item);
            }
        });

        boolean canDelete = session.canVoidOrDeleteOrManageMembers() && deleteListener != null;
        holder.btnDelete.setVisibility(canDelete ? View.VISIBLE : View.GONE);
        holder.btnDelete.setOnClickListener(v -> {
            if (deleteListener != null) {
                deleteListener.onSectionDeleteClicked(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class SectionViewHolder extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvDesc;
        final TextView tvSubfolderCount;
        final TextView tvValidCount;
        final TextView tvItemMergeBadge;
        final TextView tvCreatedDate;
        final ImageButton btnRename;
        final ImageButton btnDelete;

        SectionViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvItemSectionName);
            tvDesc = itemView.findViewById(R.id.tvItemSectionDesc);
            tvSubfolderCount = itemView.findViewById(R.id.tvItemSubfolderCount);
            tvValidCount = itemView.findViewById(R.id.tvItemValidCount);
            tvItemMergeBadge = itemView.findViewById(R.id.tvItemMergeBadge);
            tvCreatedDate = itemView.findViewById(R.id.tvItemCreatedDate);
            btnRename = itemView.findViewById(R.id.btnRenameFolderItem);
            btnDelete = itemView.findViewById(R.id.btnDeleteFolderItem);
        }
    }
}
