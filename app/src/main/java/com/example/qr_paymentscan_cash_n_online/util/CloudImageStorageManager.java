package com.example.qr_paymentscan_cash_n_online.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.ExpenseEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Manages uploading payment and expense proof photos to Cloudinary so all connected
 * devices in a room can view proof photos instantly via HTTPS URLs.
 * Automatically syncs proofImageUrl to Firestore and SQLite.
 */
public final class CloudImageStorageManager {

    private static final String TAG = "CloudinaryUpload";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface UploadCallback {
        void onSuccess(String secureUrl);
        void onFailure(String errorMessage);
    }

    private CloudImageStorageManager() {
    }

    /**
     * Uploads proof photo for a payment asynchronously to Cloudinary and merges proofImageUrl into Firestore.
     */
    public static void uploadProofImage(Context context, PaymentEntity payment) {
        uploadProofImage(context, payment, null);
    }

    public static void uploadProofImage(Context context, PaymentEntity payment, UploadCallback callback) {
        if (payment == null || payment.proofImagePath == null) {
            if (callback != null) {
                callback.onFailure("No proof photo path attached.");
            }
            return;
        }

        File imageFile = new File(payment.proofImagePath);
        if (!imageFile.exists() || imageFile.length() == 0) {
            if (callback != null) {
                callback.onFailure("Proof photo file does not exist locally.");
            }
            return;
        }

        AppDatabase.DB_EXECUTOR.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(context);
            try {
                String secureUrl = uploadImageFileSyncWithReporting(context, imageFile, CloudImageConfig.CLOUDINARY_FOLDER);
                if (secureUrl != null && !secureUrl.trim().isEmpty()) {
                    long now = System.currentTimeMillis();
                    payment.proofImageUrl = secureUrl;
                    payment.imageSyncStatus = "SYNCED";
                    payment.updatedAt = now;
                    db.paymentDao().updateProofImageUrl(payment.id, secureUrl, "SYNCED", now);

                    // Patch into Firestore
                    patchPaymentProofToFirestore(context, payment.receiptId, secureUrl);

                    MAIN_HANDLER.post(() -> {
                        if (callback != null) {
                            callback.onSuccess(secureUrl);
                        }
                    });
                } else {
                    db.paymentDao().updateProofImageUrl(payment.id, null, "FAILED", System.currentTimeMillis());
                    MAIN_HANDLER.post(() -> {
                        if (callback != null) {
                            callback.onFailure("Upload failed: No secure URL returned.");
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "uploadProofImage failed: " + e.getMessage(), e);
                db.paymentDao().updateProofImageUrl(payment.id, null, "FAILED", System.currentTimeMillis());
                MAIN_HANDLER.post(() -> {
                    Toast.makeText(context, "Photo upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    if (callback != null) {
                        callback.onFailure(e.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Synchronously uploads a local image file to Cloudinary with error logging & reporting.
     */
    public static String uploadImageFileSyncWithReporting(Context context, File imageFile, String folder) throws IOException {
        if (imageFile == null || !imageFile.exists() || !CloudImageConfig.isConfigured()) {
            Log.w(TAG, "uploadImageFileSync: file missing or Cloudinary not configured.");
            return null;
        }

        String url = CloudImageConfig.getUploadEndpoint();

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
                try {
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        return obj.getString("secure_url");
                    } else if (obj.has("url")) {
                        return obj.getString("url");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing Cloudinary response: " + e.getMessage());
                }
            } else {
                String err = response.body() != null ? response.body().string() : "HTTP " + response.code();
                Log.e(TAG, "Cloudinary upload failed: code=" + response.code() + ", error=" + err);
                throw new IOException("Cloudinary error (" + response.code() + "): " + err);
            }
        }
        return null;
    }

    public static String uploadImageFileSync(File imageFile, String folder) {
        try {
            return uploadImageFileSyncWithReporting(null, imageFile, folder);
        } catch (Exception e) {
            Log.e(TAG, "uploadImageFileSync exception: " + e.getMessage(), e);
            return null;
        }
    }

    private static void patchPaymentProofToFirestore(Context context, String receiptId, String secureUrl) {
        if (receiptId == null || secureUrl == null || !FirebaseSyncManager.isFirebaseConfigured(context)) {
            return;
        }
        try {
            UserSessionManager session = UserSessionManager.getInstance(context);
            String roomId = session.getActiveRoomId();

            Map<String, Object> updates = new HashMap<>();
            updates.put("proofImageUrl", secureUrl);
            updates.put("imageSyncStatus", "SYNCED");
            updates.put("updatedAt", System.currentTimeMillis());

            FirebaseFirestore firestore = FirebaseFirestore.getInstance();
            if (roomId != null && !roomId.trim().isEmpty()) {
                firestore.collection("rooms").document(roomId)
                        .collection("payments").document(receiptId)
                        .set(updates, SetOptions.merge())
                        .addOnFailureListener(e -> Log.e(TAG, "Failed to patch room payment proofImageUrl: " + e.getMessage()));
            }

            // Also mirror to top-level payments collection
            firestore.collection("payments").document(receiptId)
                    .set(updates, SetOptions.merge())
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to patch mirror payment proofImageUrl: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "patchPaymentProofToFirestore error: " + e.getMessage());
        }
    }

    /**
     * Retries any pending or failed proof uploads when online.
     */
    public static void retryPendingUploads(Context context) {
        flushPendingUploads(context);
    }

    public static void flushPendingUploads(Context context) {
        if (!CloudImageConfig.isConfigured() || !FirebaseSyncManager.isNetworkConnected(context)) {
            return;
        }
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(context);
                for (PaymentEntity p : db.paymentDao().getAllPaymentsSync()) {
                    boolean needsUpload = (p.proofImageUrl == null || p.proofImageUrl.trim().isEmpty())
                            && p.proofImagePath != null;
                    if (needsUpload) {
                        File f = new File(p.proofImagePath);
                        if (f.exists() && f.length() > 0) {
                            String secureUrl = uploadImageFileSync(f, CloudImageConfig.CLOUDINARY_FOLDER);
                            if (secureUrl != null && !secureUrl.trim().isEmpty()) {
                                long now = System.currentTimeMillis();
                                p.proofImageUrl = secureUrl;
                                p.imageSyncStatus = "SYNCED";
                                p.updatedAt = now;
                                db.paymentDao().updateProofImageUrl(p.id, secureUrl, "SYNCED", now);
                                patchPaymentProofToFirestore(context, p.receiptId, secureUrl);
                            }
                        }
                    }
                }

                // Process pending expense proofs
                for (ExpenseEntity ex : db.expenseDao().getAllExpensesSync()) {
                    boolean needsUpload = (ex.proofImageUrl == null || ex.proofImageUrl.trim().isEmpty())
                            && ex.proofImagePath != null;
                    if (needsUpload) {
                        File f = new File(ex.proofImagePath);
                        if (f.exists() && f.length() > 0) {
                            String secureUrl = uploadImageFileSync(f, CloudImageConfig.CLOUDINARY_FOLDER);
                            if (secureUrl != null && !secureUrl.trim().isEmpty()) {
                                long now = System.currentTimeMillis();
                                ex.proofImageUrl = secureUrl;
                                ex.imageSyncStatus = "SYNCED";
                                ex.updatedAt = now;
                                db.expenseDao().updateProofImageUrl(ex.id, secureUrl, "SYNCED", now);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "flushPendingUploads error: " + e.getMessage());
            }
        });
    }
}
