package com.example.qr_paymentscan_cash_n_online.util;

import android.content.Context;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.ExpenseEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Manages uploading payment & expense proof photos to Cloudinary so all connected
 * devices in a room can view proof photos instantly via HTTPS URLs.
 * Operates offline-first: if unconfigured or offline, proofs remain safely saved
 * to internal storage, and uploads are queued.
 */
public final class CloudImageStorageManager {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private CloudImageStorageManager() {
    }

    /**
     * Synchronously uploads a local image file to Cloudinary using an unsigned upload preset.
     * Returns the HTTPS secure_url on success, or null on failure/unconfigured.
     */
    public static String uploadImageFileSync(File imageFile, String folder) {
        if (imageFile == null || !imageFile.exists() || !CloudImageConfig.isConfigured()) {
            return null;
        }

        try {
            String url = "https://api.cloudinary.com/v1_1/"
                    + CloudImageConfig.CLOUDINARY_CLOUD_NAME.trim()
                    + "/image/upload";

            String mimeType = "image/jpeg";
            String lower = imageFile.getName().toLowerCase();
            if (lower.endsWith(".png")) {
                mimeType = "image/png";
            } else if (lower.endsWith(".webp")) {
                mimeType = "image/webp";
            }

            MultipartBody.Builder builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("upload_preset", CloudImageConfig.CLOUDINARY_UPLOAD_PRESET.trim())
                    .addFormDataPart(
                            "file",
                            imageFile.getName(),
                            RequestBody.create(imageFile, MediaType.parse(mimeType))
                    );

            if (folder != null && !folder.trim().isEmpty()) {
                builder.addFormDataPart("folder", folder.trim());
            }

            Request request = new Request.Builder()
                    .url(url)
                    .post(builder.build())
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        return obj.getString("secure_url");
                    } else if (obj.has("url")) {
                        return obj.getString("url");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Uploads any payment or expense proof photos that are still pending upload.
     */
    public static void flushPendingUploads(Context context) {
        if (!CloudImageConfig.isConfigured()) {
            return;
        }
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(context);
                // 1. Process pending payment proofs
                for (PaymentEntity p : db.paymentDao().getAllPaymentsSync()) {
                    if (PaymentEntity.IMAGE_STATUS_PENDING_UPLOAD.equals(p.imageSyncStatus)
                            && p.proofImagePath != null) {
                        File f = new File(p.proofImagePath);
                        if (f.exists()) {
                            String cloudUrl = uploadImageFileSync(f, "payment_proofs");
                            if (cloudUrl != null) {
                                db.paymentDao().updateProofImageUrl(
                                        p.id,
                                        cloudUrl,
                                        PaymentEntity.IMAGE_STATUS_UPLOADED,
                                        System.currentTimeMillis()
                                );
                            }
                        }
                    }
                }

                // 2. Process pending expense proofs
                for (ExpenseEntity ex : db.expenseDao().getAllExpensesSync()) {
                    if (ExpenseEntity.IMAGE_STATUS_PENDING_UPLOAD.equals(ex.imageSyncStatus)
                            && ex.proofImagePath != null) {
                        File f = new File(ex.proofImagePath);
                        if (f.exists()) {
                            String cloudUrl = uploadImageFileSync(f, "expense_proofs");
                            if (cloudUrl != null) {
                                db.expenseDao().updateProofImageUrl(
                                        ex.id,
                                        cloudUrl,
                                        ExpenseEntity.IMAGE_STATUS_UPLOADED,
                                        System.currentTimeMillis()
                                );
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }
}
