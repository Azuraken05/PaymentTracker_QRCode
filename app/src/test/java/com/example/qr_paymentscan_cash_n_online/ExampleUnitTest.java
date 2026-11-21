package com.example.qr_paymentscan_cash_n_online;

import com.example.qr_paymentscan_cash_n_online.util.DuplicatePaymentGuard;
import com.example.qr_paymentscan_cash_n_online.util.RoomSecurityHelper;
import com.example.qr_paymentscan_cash_n_online.util.SecuritySignatureHelper;

import org.junit.Test;

import static org.junit.Assert.*;

public class ExampleUnitTest {

    @Test
    public void pbkdf2SaltedPasswordHashing_andConstantTimeVerification_isCorrect() {
        String salt1 = RoomSecurityHelper.generateSaltBase64();
        String salt2 = RoomSecurityHelper.generateSaltBase64();
        assertNotNull(salt1);
        assertNotEquals(salt1, salt2);

        String hash1 = RoomSecurityHelper.hashPasswordPbkdf2("Treasurer2026", salt1);
        String hash2 = RoomSecurityHelper.hashPasswordPbkdf2("Treasurer2026", salt2);
        assertNotEquals("Different salts must yield different PBKDF2 hashes", hash1, hash2);

        assertTrue(RoomSecurityHelper.verifyRoomPassword("Treasurer2026", hash1, salt1));
        assertFalse(RoomSecurityHelper.verifyRoomPassword("WrongPass123", hash1, salt1));

        // Backward compatibility with legacy v3 unsalted SHA-256 room password hash
        String legacyHash = SecuritySignatureHelper.sha256Hex("123456");
        assertTrue(RoomSecurityHelper.verifyRoomPassword("123456", legacyHash, null));
        assertFalse(RoomSecurityHelper.verifyRoomPassword("654321", legacyHash, ""));
    }

    @Test
    public void passwordStrengthValidation_enforcesPolicy() {
        assertNotNull(RoomSecurityHelper.validatePasswordStrength("short1A"));
        assertNotNull(RoomSecurityHelper.validatePasswordStrength("alllowercase123"));
        assertNotNull(RoomSecurityHelper.validatePasswordStrength("ALLUPPERCASE123"));
        assertNotNull(RoomSecurityHelper.validatePasswordStrength("NoNumbersHere"));
        assertNull(RoomSecurityHelper.validatePasswordStrength("StrongPass2026"));
    }

    @Test
    public void duplicatePaymentGuard_normalizesStudentIdentityAndLockKey() {
        String keyA = DuplicatePaymentGuard.buildStudentIdentityKey(
                10L,
                "  Juan   Dela  ",
                "  CRUZ ",
                "Dept Shirt",
                250.0 // fixed-fee folder
        );
        String keyB = DuplicatePaymentGuard.buildStudentIdentityKey(
                10L,
                "juan dela",
                "cruz",
                "Department Shirt",
                250.0
        );
        assertEquals("Fixed-fee folders normalize student full name regardless of casing/spaces", keyA, keyB);

        String lockA = DuplicatePaymentGuard.buildLockDocumentId(10L, "  Juan  Dela ", "Cruz", "Dept Shirt", 250.0);
        String lockB = DuplicatePaymentGuard.buildLockDocumentId(10L, "juan dela", "CRUZ", "Other Purpose", 250.0);
        assertEquals("Deterministic lock IDs match for the same student in a fixed-fee folder", lockA, lockB);
    }
}
