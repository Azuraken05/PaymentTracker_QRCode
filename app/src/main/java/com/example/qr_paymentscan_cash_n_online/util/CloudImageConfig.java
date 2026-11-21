package com.example.qr_paymentscan_cash_n_online.util;

/**
 * Configuration constants for Cloudinary image uploads.
 * Fill in your Cloudinary cloud name and unsigned upload preset.
 * When left as default placeholders or unconfigured, proof images continue
 * to be saved safely to local private app storage and Firebase Storage.
 */
public final class CloudImageConfig {

    public static final String CLOUDINARY_CLOUD_NAME = "etzpyx15";
    public static final String CLOUDINARY_UPLOAD_PRESET = "trackerpayment_unsigned";

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
}
