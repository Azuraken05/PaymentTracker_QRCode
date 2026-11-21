package com.example.qr_paymentscan_cash_n_online.util;

public final class PaymentValidationHelper {

    public static final String PROVIDER_CASH = "Cash";
    public static final String PROVIDER_GCASH = "GCash";
    public static final String PROVIDER_MAYA = "Maya";
    public static final String PROVIDER_GOTYME = "GoTyme";
    public static final String PROVIDER_BANK_TRANSFER = "Bank Transfer";
    public static final String PROVIDER_OTHER_ONLINE = "Other Online";

    public static final String ERROR_ONLINE_PROOF_OR_REF_REQUIRED =
            "For online payments, please enter a Reference/Transaction ID or attach a Proof Image.";

    private PaymentValidationHelper() {
    }

    public static boolean isCash(String paymentMode) {
        return paymentMode == null || PROVIDER_CASH.equalsIgnoreCase(paymentMode.trim());
    }

    public static boolean isOnline(String paymentMode) {
        return !isCash(paymentMode);
    }

    /**
     * Enforces the "Reference ID OR Proof Image" rule for digital/online payments.
     * Cash is always valid. Online payments pass if either a reference string
     * or a proof image (local or cloud) is present.
     */
    public static boolean validateOnlinePaymentProof(
            String paymentMode,
            String referenceString,
            boolean hasLocalImage,
            boolean hasCloudImage
    ) {
        if (isCash(paymentMode)) {
            return true;
        }
        boolean hasRef = referenceString != null && !referenceString.trim().isEmpty();
        boolean hasProof = hasLocalImage || hasCloudImage;
        return hasRef || hasProof;
    }

    public static String getOnlinePaymentValidationError(
            String paymentMode,
            String referenceString,
            boolean hasLocalImage,
            boolean hasCloudImage
    ) {
        if (validateOnlinePaymentProof(paymentMode, referenceString, hasLocalImage, hasCloudImage)) {
            return null;
        }
        return ERROR_ONLINE_PROOF_OR_REF_REQUIRED;
    }

    /**
     * When an online payment was saved with a proof photo but no reference ID,
     * returns "Verified via Proof Photo (No Ref #)" instead of blank or "N/A".
     */
    public static String formatReferenceDisplay(
            String paymentMode,
            String referenceString,
            boolean hasProofImage
    ) {
        if (referenceString != null && !referenceString.trim().isEmpty()) {
            return referenceString.trim();
        }
        if (isOnline(paymentMode) && hasProofImage) {
            return "Verified via Proof Photo (No Ref #)";
        }
        return "N/A";
    }

    public static String cleanReference(String reference) {
        return reference != null ? reference.trim() : "";
    }
}
