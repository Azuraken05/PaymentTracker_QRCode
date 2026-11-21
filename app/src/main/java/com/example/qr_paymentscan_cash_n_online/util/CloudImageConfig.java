package com.example.qr_paymentscan_cash_n_online.util;

/**
 * Configuration constants for Cloudinary image uploads.
 * Every photo taken with Camera or selected from Gallery uploads to Cloudinary
 * and syncs proofImageUrl to Firestore for all room members.
 */
public final class CloudImageConfig {

    public static final String CLOUDINARY_CLOUD_NAME = "etzpyx15";
    public static final String CLOUDINARY_UPLOAD_PRESET = "trackerpayment_unsigned";
    public static final String CLOUDINARY_FOLDER = "tracker_payment";

    private CloudImageConfig() {
    }

    public static boolean isConfigured() {
        return CLOUDINARY_CLOUD_NAME != null
                && !CLOUDINARY_CLOUD_NAME.trim().isEmpty()
                && !"YOUR_CLOUD_NAME".equalsIgnoreCase(CLOUDINARY_CLOUD_NAME.trim())
                && CLOUDINARY_UPLOAD_PRESET != null
                && !CLOUDINARY_UPLOAD_PRESET.trim().isEmpty()
                && !"YOUR_UPLOAD_PRESET".equalsIgnoreCase(CLOUDINARY_UPLOAD_PRESET.trim());
    }

    public static String getUploadEndpoint() {
        return "https://api.cloudinary.com/v1_1/" + CLOUDINARY_CLOUD_NAME.trim() + "/image/upload";
    }
}
