package com.example.qr_paymentscan_cash_n_online.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CurrencyFormatter {

    private CurrencyFormatter() {
    }

    public static String formatPhp(double amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        DecimalFormat df = new DecimalFormat("#,##0.00", symbols);
        return "₱" + df.format(amount);
    }

    public static String formatDateTime(long epochMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US);
        return sdf.format(new Date(epochMillis));
    }

    public static String formatShortDate(long epochMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.US);
        return sdf.format(new Date(epochMillis));
    }

    public static String formatFileTimestamp(long epochMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US);
        return sdf.format(new Date(epochMillis));
    }
}
