package com.example.qr_paymentscan_cash_n_online.util;

import android.content.Context;
import android.content.SharedPreferences;
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
 * Cryptographic Password Hardening (PBKDF2WithHmacSHA256), Constant-Time Verification,
 * Password Strength Validation, and Brute-Force Rate-Limiting Lockout Guard for Private Rooms.
 */
public final class RoomSecurityHelper {

    public static final int PBKDF2_ITERATIONS = 65536;
    public static final int PBKDF2_KEY_LENGTH_BITS = 256;
    public static final int SALT_BYTES_LENGTH = 16;

    public static final int MAX_FAILED_JOIN_ATTEMPTS = 5;
    public static final long LOCKOUT_DURATION_MS = 15L * 60L * 1000L; // 15 minutes

    private static final String PREFS_BRUTE_FORCE = "room_brute_force_guard_prefs";
    private static final String PREFIX_FAIL_COUNT = "fail_count_";
    private static final String PREFIX_LOCKOUT_UNTIL = "lockout_until_";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RoomSecurityHelper() {
    }

    /**
     * Generates a unique 16-byte cryptographic salt encoded in URL/line-safe Base64.
     */
    public static String generateSaltBase64() {
        byte[] salt = new byte[SALT_BYTES_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * Hashes a room password using PBKDF2WithHmacSHA256 (65,536 iterations, 256-bit key length)
     * with the room's unique 16-byte salt.
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
            throw new IllegalStateException("PBKDF2 password hashing failed", e);
        }
    }

    /**
     * Verifies a candidate room password against the stored password hash and salt using
     * constant-time byte array comparison (MessageDigest.isEqual).
     * Backward-compatible with legacy v3 rooms where storedSalt is null or empty.
     */
    public static boolean verifyRoomPassword(String inputPassword, String storedHash, String storedSalt) {
        if (inputPassword == null || storedHash == null || storedHash.trim().isEmpty()) {
            return false;
        }
        String candidateHash;
        if (storedSalt != null && !storedSalt.trim().isEmpty()) {
            candidateHash = hashPasswordPbkdf2(inputPassword, storedSalt.trim());
        } else {
            candidateHash = SecuritySignatureHelper.sha256Hex(inputPassword);
        }
        byte[] a = candidateHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] b = storedHash.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
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

    /**
     * Checks if a roomKey is currently under brute-force cooldown lockout.
     * Combines local device SharedPreferences lockout and remote room lockout_until timestamp.
     */
    public static long getRemainingLockoutMillis(Context context, String roomKey, long remoteLockoutUntil) {
        long now = System.currentTimeMillis();
        long localLockoutUntil = 0L;
        if (context != null && roomKey != null) {
            String normalizedKey = roomKey.trim().toUpperCase(Locale.US);
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_BRUTE_FORCE, Context.MODE_PRIVATE);
            localLockoutUntil = prefs.getLong(PREFIX_LOCKOUT_UNTIL + normalizedKey, 0L);
        }
        long effectiveLockoutUntil = Math.max(localLockoutUntil, remoteLockoutUntil);
        return Math.max(0L, effectiveLockoutUntil - now);
    }

    public static int getFailedJoinAttempts(Context context, String roomKey) {
        if (context == null || roomKey == null) {
            return 0;
        }
        String normalizedKey = roomKey.trim().toUpperCase(Locale.US);
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_BRUTE_FORCE, Context.MODE_PRIVATE);
        return prefs.getInt(PREFIX_FAIL_COUNT + normalizedKey, 0);
    }

    /**
     * Records a failed join attempt for the given roomKey.
     * If failed attempts reach MAX_FAILED_JOIN_ATTEMPTS (5), starts a 15-minute cooldown lockout.
     * Returns the updated consecutive failed count.
     */
    public static int recordFailedJoinAttempt(Context context, String roomKey) {
        if (context == null || roomKey == null) {
            return 1;
        }
        String normalizedKey = roomKey.trim().toUpperCase(Locale.US);
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_BRUTE_FORCE, Context.MODE_PRIVATE);
        int current = prefs.getInt(PREFIX_FAIL_COUNT + normalizedKey, 0) + 1;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(PREFIX_FAIL_COUNT + normalizedKey, current);
        if (current >= MAX_FAILED_JOIN_ATTEMPTS) {
            long lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS;
            editor.putLong(PREFIX_LOCKOUT_UNTIL + normalizedKey, lockoutUntil);
        }
        editor.apply();
        return current;
    }

    public static void clearFailedJoinAttempts(Context context, String roomKey) {
        if (context == null || roomKey == null) {
            return;
        }
        String normalizedKey = roomKey.trim().toUpperCase(Locale.US);
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_BRUTE_FORCE, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(PREFIX_FAIL_COUNT + normalizedKey)
                .remove(PREFIX_LOCKOUT_UNTIL + normalizedKey)
                .apply();
    }

    public static String formatLockoutCountdown(long remainingMillis) {
        long totalSeconds = Math.max(1L, (remainingMillis + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "Too many failed attempts. Try again in %dm %02ds", minutes, seconds);
    }

    public static String getDeviceInfo() {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim() : "Android";
        String model = Build.MODEL != null ? Build.MODEL.trim() : "Device";
        if (model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            return model;
        }
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
