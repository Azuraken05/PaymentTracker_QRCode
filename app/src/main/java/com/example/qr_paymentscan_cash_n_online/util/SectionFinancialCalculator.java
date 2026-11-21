package com.example.qr_paymentscan_cash_n_online.util;

import androidx.annotation.NonNull;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.ExpenseEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.SectionEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SectionFinancialCalculator {

    public static class FinancialSummary {
        public final double directGrossCollected;
        public final double mergedSubfolderGross;
        public final double totalGrossCollected;
        public final double totalExpenses;
        public final double netBalance;
        public final int mergedSubfolderCount;

        public FinancialSummary(
                double directGrossCollected,
                double mergedSubfolderGross,
                double totalGrossCollected,
                double totalExpenses,
                double netBalance,
                int mergedSubfolderCount
        ) {
            this.directGrossCollected = directGrossCollected;
            this.mergedSubfolderGross = mergedSubfolderGross;
            this.totalGrossCollected = totalGrossCollected;
            this.totalExpenses = totalExpenses;
            this.netBalance = netBalance;
            this.mergedSubfolderCount = mergedSubfolderCount;
        }
    }

    private static class ChildGrossResult {
        final double rollUpGross;
        final int mergedFolderCount;

        ChildGrossResult(double rollUpGross, int mergedFolderCount) {
            this.rollUpGross = rollUpGross;
            this.mergedFolderCount = mergedFolderCount;
        }
    }

    /**
     * Calculates the financial summary for section S synchronously (to be invoked on a background executor).
     *
     * @param db AppDatabase instance
     * @param targetSectionId ID of the current section/subfolder S
     * @return FinancialSummary containing direct gross, merged gross, total gross, expenses, net balance, and count
     */
    @NonNull
    public static FinancialSummary calculateSummarySync(@NonNull AppDatabase db, long targetSectionId) {
        // 1. Direct gross collected in S (active payments)
        double directGross = 0.0;
        List<PaymentEntity> activePayments = db.paymentDao().getActivePaymentsBySectionSync(targetSectionId);
        if (activePayments != null) {
            for (PaymentEntity p : activePayments) {
                if (p != null && !p.isVoided()) {
                    directGross += p.amount;
                }
            }
        }

        // 2. Direct expenses in S (active expenses)
        double totalExpenses = 0.0;
        List<ExpenseEntity> expenses = db.expenseDao().getExpensesBySectionSync(targetSectionId);
        if (expenses != null) {
            for (ExpenseEntity e : expenses) {
                if (e != null && !ExpenseEntity.STATUS_VOIDED.equalsIgnoreCase(e.status)) {
                    totalExpenses += e.amount;
                }
            }
        }

        // 3. Merged subfolder gross roll-up
        double mergedSubfolderGross = 0.0;
        int mergedCount = 0;
        Set<Long> visited = new HashSet<>();
        visited.add(targetSectionId);

        List<SectionEntity> directChildren = db.sectionDao().getSubfoldersSync(targetSectionId);
        if (directChildren != null) {
            for (SectionEntity child : directChildren) {
                if (child != null && child.isMergeGrossToParent() && !visited.contains(child.id)) {
                    ChildGrossResult childResult = calculateEffectiveChildGross(db, child.id, visited);
                    mergedSubfolderGross += childResult.rollUpGross;
                    mergedCount += 1 + childResult.mergedFolderCount;
                }
            }
        }

        double totalGross = directGross + mergedSubfolderGross;
        double net = totalGross - totalExpenses;

        return new FinancialSummary(
                directGross,
                mergedSubfolderGross,
                totalGross,
                totalExpenses,
                net,
                mergedCount
        );
    }

    /**
     * Recursively computes the effective gross collected of child folder C:
     * C.directGross + (any of C's children that have mergeGrossToParent == true).
     */
    private static ChildGrossResult calculateEffectiveChildGross(
            @NonNull AppDatabase db,
            long folderId,
            @NonNull Set<Long> visited
    ) {
        visited.add(folderId);

        double childDirectGross = 0.0;
        List<PaymentEntity> payments = db.paymentDao().getActivePaymentsBySectionSync(folderId);
        if (payments != null) {
            for (PaymentEntity p : payments) {
                if (p != null && !p.isVoided()) {
                    childDirectGross += p.amount;
                }
            }
        }

        double nestedMergedGross = 0.0;
        int nestedMergedCount = 0;
        List<SectionEntity> subChildren = db.sectionDao().getSubfoldersSync(folderId);
        if (subChildren != null) {
            for (SectionEntity subChild : subChildren) {
                if (subChild != null && subChild.isMergeGrossToParent() && !visited.contains(subChild.id)) {
                    ChildGrossResult nested = calculateEffectiveChildGross(db, subChild.id, visited);
                    nestedMergedGross += nested.rollUpGross;
                    nestedMergedCount += 1 + nested.mergedFolderCount;
                }
            }
        }

        return new ChildGrossResult(childDirectGross + nestedMergedGross, nestedMergedCount);
    }

    /**
     * Recursively collects targetSectionId and all descendant section IDs (children, grandchildren, etc.).
     */
    @NonNull
    public static List<Long> getAllDescendantSectionIdsSync(@NonNull AppDatabase db, long rootSectionId) {
        List<Long> allIds = new ArrayList<>();
        allIds.add(rootSectionId);
        Set<Long> visited = new HashSet<>();
        visited.add(rootSectionId);
        collectDescendantsRecursive(db, rootSectionId, allIds, visited);
        return allIds;
    }

    private static void collectDescendantsRecursive(
            @NonNull AppDatabase db,
            long parentId,
            @NonNull List<Long> collector,
            @NonNull Set<Long> visited
    ) {
        List<SectionEntity> children = db.sectionDao().getSubfoldersSync(parentId);
        if (children != null) {
            for (SectionEntity child : children) {
                if (child != null && !visited.contains(child.id)) {
                    visited.add(child.id);
                    collector.add(child.id);
                    collectDescendantsRecursive(db, child.id, collector, visited);
                }
            }
        }
    }
}
