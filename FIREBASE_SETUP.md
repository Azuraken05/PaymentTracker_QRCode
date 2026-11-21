# Firebase Setup Guide — Student QR Payment Tracker (Hardened Room Security & Multi-Cashier Duplicate Payment Prevention)

This application uses an **Offline-First Room SQLite (`v4`) + Firebase Auth + Real-Time Firestore & Storage Sync** architecture compatible with the **Firebase Free Spark Plan**.

* **Zero Data Loss Guarantee:** Non-destructive Room migrations (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`) preserve 100% of existing local rooms, folders, student receipts, expenses, and audit logs.
* **Cryptographic Room Security (`PBKDF2WithHmacSHA256`):** Room passwords are salted with a 16-byte `SecureRandom` salt (`room_salt`) and hashed with 65,536-iteration `PBKDF2WithHmacSHA256`, verified via constant-time `MessageDigest.isEqual`.
* **Brute-Force Lockout & Waiting Room Approval Gate:** 5 consecutive failed password attempts trigger a 15-minute cooldown lockout and log a `SECURITY_BRUTE_FORCE_ALERT` with device model telemetry (`Build.MANUFACTURER + " " + Build.MODEL`). New members enter `PENDING_APPROVAL` status until approved by the `OWNER`.
* **Multi-Cashier Duplicate Payment Prevention:** Pre-submission local Room + live Firestore `Source.SERVER` check and atomic Firestore transaction locks (`rooms/{roomId}/payment_locks/{lockKey}`) prevent two collectors from double-charging the same student.

---

## Step 1: Verify `google-services.json`
Your `app/google-services.json` file is already placed in:
```text
QR_PaymentScan_Cash_N_Online/
 ├── firestore.rules
 └── app/
      ├── build.gradle.kts
      └── google-services.json
```

---

## Step 2: Enable Firebase Authentication (Email/Password — Free Spark Plan)
1. Open the [Firebase Console](https://console.firebase.google.com/) and select your project (`trackerpayment-671be`).
2. Go to **Build $\rightarrow$ Authentication $\rightarrow$ Get started**.
3. Under the **Sign-in method** tab, click **Email/Password**, toggle **Enable**, and click **Save**.

---

## Step 3: Publish Hardened Production Firestore Security Rules (`firestore.rules`)
1. In the Firebase Console left sidebar, go to **Build $\rightarrow$ Firestore Database $\rightarrow$ Rules**.
2. Copy the contents of [`firestore.rules`](firestore.rules) in the project root and click **Publish**:
   * **Secret Isolation:** Isolates `roomPasswordHash`, `roomSalt`, and `roomSigningSecret` inside `rooms/{roomId}/private/security`, readable only by `ACTIVE` room members.
   * **Controlled Join & Waiting Room Gate:** Prevents self-promotion to `OWNER` and blocks `BANNED` members.
   * **Role-Enforced Writes:** `AUDITOR` is strictly read-only; `COLLECTOR` can record payments/expenses; only `OWNER` can void records, delete folders, release `payment_locks`, or manage members.
   * **Immutable Audit Trail:** `audit_logs` enforces `allow update, delete: if false;` so security alerts (`SECURITY_*`, `DUPLICATE_*`) can never be erased.

---

## Step 4: Enable Firebase Storage (For GCash & Expense Proof Photos)
1. Go to **Build $\rightarrow$ Storage $\rightarrow$ Rules** in the Firebase Console:
```javascript
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /payment_proofs/{allPaths=**} {
      allow read, write: if request.auth != null;
    }
    match /expense_proofs/{allPaths=**} {
      allow read, write: if request.auth != null;
    }
  }
}
```
