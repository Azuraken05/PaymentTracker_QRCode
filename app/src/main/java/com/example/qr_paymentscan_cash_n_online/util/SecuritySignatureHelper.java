package com.example.qr_paymentscan_cash_n_online.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SecuritySignatureHelper {

    private static final String PREFS_NAME = "qr_receipt_security_prefs";
    private static final String KEY_HMAC_SECRET = "hmac_sha256_secret_hex";
    private static final String ROOM_KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SecuritySignatureHelper() {
    }

    public static String generateUniqueReceiptId(long timestampMillis) {
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
        String datePart = dateFmt.format(new Date(timestampMillis));
        byte[] randomBytes = new byte[3];
        SECURE_RANDOM.nextBytes(randomBytes);
        String hexPart = bytesToHex(randomBytes).toUpperCase(Locale.US);
        return "RCP-" + datePart + "-" + hexPart;
    }

    /**
     * Generates a human-friendly Room Key in the format KEY-XXXXXX (excluding ambiguous 0/O/1/I).
     */
    public static String generateRoomKey() {
        StringBuilder sb = new StringBuilder("KEY-");
        for (int i = 0; i < 6; i++) {
            int idx = SECURE_RANDOM.nextInt(ROOM_KEY_ALPHABET.length());
            sb.append(ROOM_KEY_ALPHABET.charAt(idx));
        }
        return sb.toString();
    }

    /**
     * Generates a 256-bit (64 hex character) cryptographic room signing secret.
     */
    public static String generateRoomSigningSecretHex() {
        byte[] keyBytes = new byte[32];
        SECURE_RANDOM.nextBytes(keyBytes);
        return bytesToHex(keyBytes);
    }

    /**
     * Computes a SHA-256 hex digest for Room Password hashing.
     */
    public static String sha256Hex(String input) {
        String safe = input != null ? input : "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(safe.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 hashing failed", e);
        }
    }

    public static synchronized String getOrCreateSecretKey(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY_HMAC_SECRET, null);
        if (existing != null && existing.length() >= 32) {
            return existing;
        }
        byte[] keyBytes = new byte[32];
        SECURE_RANDOM.nextBytes(keyBytes);
        String secretHex = bytesToHex(keyBytes);
        prefs.edit().putString(KEY_HMAC_SECRET, secretHex).apply();
        return secretHex;
    }

    public static synchronized void restoreSecretKeyIfPresent(Context context, String secretHex) {
        if (secretHex != null && secretHex.length() >= 32) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_HMAC_SECRET, secretHex).apply();
        }
    }

    public static String computeReceiptSignature(
            Context context,
            String receiptId,
            long sectionId,
            String firstName,
            String lastName,
            double amount,
            long createdAt
    ) {
        String activeRoomSecret = UserSessionManager.getInstance(context).getActiveRoomSigningSecret();
        String secretToUse = (activeRoomSecret != null && activeRoomSecret.length() >= 32)
                ? activeRoomSecret
                : getOrCreateSecretKey(context);
        return computeReceiptSignatureWithSecret(
                secretToUse,
                receiptId,
                sectionId,
                firstName,
                lastName,
                amount,
                createdAt
        );
    }

    public static String computeReceiptSignatureWithSecret(
            String secretHex,
            String receiptId,
            long sectionId,
            String firstName,
            String lastName,
            double amount,
            long createdAt
    ) {
        String amountFormatted = String.format(Locale.US, "%.2f", amount);
        String canonicalData = receiptId + "|"
                + sectionId + "|"
                + (firstName != null ? firstName.trim() : "") + "|"
                + (lastName != null ? lastName.trim() : "") + "|"
                + amountFormatted + "|"
                + createdAt;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretHex.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(canonicalData.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(rawHmac).toUpperCase(Locale.US);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 signature failed", e);
        }
    }

    public static String buildQrPayload(String receiptId, String signature) {
        return receiptId + "|" + signature;
    }

    public static boolean verifyPaymentRecordAuthenticity(Context context, PaymentEntity entity, String scannedSignature) {
        return verifyPaymentRecordAuthenticity(context, entity, scannedSignature, Collections.emptyList());
    }

    /**
     * Multi-device & backward-compatible authenticity check:
     * Verifies against the active room signing secret, any provided room signing secrets,
     * and the legacy device-local HMAC secret so both newly issued multi-user receipts
     * and previously issued v1/v2 student QR codes verify as AUTHENTIC & VALID.
     */
    public static boolean verifyPaymentRecordAuthenticity(
            Context context,
            PaymentEntity entity,
            String scannedSignature,
            List<String> additionalRoomSecrets
    ) {
        if (entity == null || scannedSignature == null || scannedSignature.isEmpty()) {
            return false;
        }
        if (!constantTimeEquals(entity.qrSignature, scannedSignature)) {
            return false;
        }

        List<String> candidateSecrets = new ArrayList<>();
        String activeSecret = UserSessionManager.getInstance(context).getActiveRoomSigningSecret();
        if (activeSecret != null && activeSecret.length() >= 32) {
            candidateSecrets.add(activeSecret);
        }
        if (additionalRoomSecrets != null) {
            for (String s : additionalRoomSecrets) {
                if (s != null && s.length() >= 32 && !candidateSecrets.contains(s)) {
                    candidateSecrets.add(s);
                }
            }
        }
        String legacyDeviceSecret = getOrCreateSecretKey(context);
        if (legacyDeviceSecret != null && !candidateSecrets.contains(legacyDeviceSecret)) {
            candidateSecrets.add(legacyDeviceSecret);
        }

        for (String secret : candidateSecrets) {
            String recomputed = computeReceiptSignatureWithSecret(
                    secret,
                    entity.receiptId,
                    entity.sectionId,
                    entity.firstName,
                    entity.lastName,
                    entity.amount,
                    entity.createdAt
            );
            if (constantTimeEquals(recomputed, scannedSignature)) {
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    public static Bitmap generateQrCodeBitmap(String payload, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    public static String decodeQrFromImageUri(Context context, Uri imageUri) {
        try (InputStream is = context.getContentResolver().openInputStream(imageUri)) {
            if (is == null) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
            if (bitmap == null) {
                return null;
            }
            String directDecode = decodeBitmapWithZxing(bitmap);
            if (directDecode != null) {
                return directDecode;
            }
            if (bitmap.getWidth() > 1200 || bitmap.getHeight() > 1200) {
                int targetW = bitmap.getWidth() / 2;
                int targetH = bitmap.getHeight() / 2;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
                return decodeBitmapWithZxing(scaled);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String decodeBitmapWithZxing(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
            MultiFormatReader reader = new MultiFormatReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            Result result = reader.decode(binaryBitmap, hints);
            return result != null ? result.getText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }
}
