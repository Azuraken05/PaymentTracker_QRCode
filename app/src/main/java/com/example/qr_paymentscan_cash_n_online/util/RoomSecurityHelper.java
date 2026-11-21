package com.example.qr_paymentscan_cash_n_online.util;

import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Cryptographic Password Hardening (PBKDF2WithHmacSHA256), Constant-Time Verification
 * (supporting both rooms/{roomId}/private/security and legacy parent room credentials),
 * and Password Strength Validation for Private Rooms.
 *
 * Note: Join-attempt cooldown/lockout has been completely removed so officers can retry
 * entering Room Key and Password immediately without waiting.
 */
public final class RoomSecurityHelper {

    public static final int PBKDF2_ITERATIONS = 65536;
    public static final int PBKDF2_KEY_LENGTH_BITS = 256;
    public static final int SALT_BYTES_LENGTH = 16;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RoomSecurityHelper() {
    }

    /**
     * Generates a unique 16-byte cryptographic salt encoded in Base64.
     */
    public static String generateSaltBase64() {
        byte[] salt = new byte[SALT_BYTES_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * Hashes a room password using PBKDF2WithHmacSHA256 (65,536 iterations, 256-bit key length)
     * with the room's unique 16-byte salt. Falls back to SHA-256 hex if salt is null or empty.
     */
    public static String hashPasswordPbkdf2(String rawPassword, String saltBase64) {
        String safePassword = rawPassword != null ? rawPassword : "";
        if (saltBase64 == null || saltBase64.trim().isEmpty()) {
            return SecuritySignatureHelper.sha256Hex(safePassword);
        }
        try {
            byte[] saltBytes = Base64.getDecoder().decode(saltBase64.trim());
            KeySpec spec = new PBEKeySpec(
                    safePassword.toCharArray(),
                    saltBytes,
                    PBKDF2_ITERATIONS,
                    PBKDF2_KEY_LENGTH_BITS
            );
            SecretKeyFactory factory;
            try {
                factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            } catch (Exception fallbackEx) {
                factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            }
            byte[] hashBytes = factory.generateSecret(spec).getEncoded();
            return bytesToHex(hashBytes);
        } catch (Exception e) {
            return SecuritySignatureHelper.sha256Hex(safePassword);
        }
    }

    /**
     * Verifies a candidate room password against the stored password hash and salt using
     * constant-time byte array comparison (MessageDigest.isEqual).
     * Backward-compatible with legacy rooms where storedSalt is null or empty ("").
     */
    public static boolean verifyRoomPassword(String inputPassword, String storedHash, String storedSalt) {
        if (inputPassword == null || storedHash == null || storedHash.trim().isEmpty()) {
            return false;
        }
        byte[] expectedBytes = storedHash.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);

        if (storedSalt != null && !storedSalt.trim().isEmpty()) {
            String pbkdf2Candidate = hashPasswordPbkdf2(inputPassword, storedSalt.trim());
            byte[] pbkdf2Bytes = pbkdf2Candidate.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(pbkdf2Bytes, expectedBytes)) {
                return true;
            }
        }

        // Fallback check against legacy unsalted SHA-256 hash (when roomSalt is null or "")
        String sha256Candidate = SecuritySignatureHelper.sha256Hex(inputPassword);
        byte[] sha256Bytes = sha256Candidate.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(sha256Bytes, expectedBytes);
    }

    /**
     * Verifies a candidate room password by checking rooms/{roomId}/private/security credentials first;
     * if that sub-document does not exist, has empty fields, or fails to match (e.g. legacy auto-migrated rooms),
     * falls back to verifying against roomPasswordHash and roomSalt on the parent rooms/{roomId} document.
     */
    public static boolean verifyRoomPasswordWithFallback(
            String inputPassword,
            String privatePasswordHash,
            String privateSalt,
            String parentPasswordHash,
            String parentSalt
    ) {
        if (inputPassword == null) {
            return false;
        }
        if (privatePasswordHash != null && !privatePasswordHash.trim().isEmpty()) {
            if (verifyRoomPassword(inputPassword, privatePasswordHash, privateSalt)) {
                return true;
            }
        }
        if (parentPasswordHash != null && !parentPasswordHash.trim().isEmpty()) {
            return verifyRoomPassword(inputPassword, parentPasswordHash, parentSalt);
        }
        return false;
    }

    /**
     * Enforces minimum strong room password policy:
     * - Minimum 8 characters
     * - At least 1 uppercase letter
     * - At least 1 lowercase letter
     * - At least 1 number
     * Returns null if valid, or a descriptive validation message if invalid.
     */
    public static String validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            return "Password must be at least 8 characters long.";
        }
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasUpper) {
            return "Password must include at least 1 uppercase letter (A-Z).";
        }
        if (!hasLower) {
            return "Password must include at least 1 lowercase letter (a-z).";
        }
        if (!hasDigit) {
            return "Password must include at least 1 number (0-9).";
        }
        return null;
    }

    public static String getPasswordStrengthLabel(String password) {
        if (password == null || password.length() < 8) {
            return "WEAK";
        }
        int score = 0;
        if (password.length() >= 8) score++;
        if (password.length() >= 12) score++;
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSymbol = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSymbol = true;
        }
        if (hasUpper && hasLower) score++;
        if (hasDigit) score++;
        if (hasSymbol) score++;
        if (score >= 4) {
            return "STRONG";
        } else if (score >= 3) {
            return "MEDIUM";
        }
        return "WEAK";
    }

    public static String getDeviceInfo() {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim() : "Android";
        String model = Build.MODEL != null ? Build.MODEL.trim() : "Device";
        return manufacturer + " " + model;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }
}
