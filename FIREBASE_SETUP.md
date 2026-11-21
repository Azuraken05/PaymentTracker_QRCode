# Firebase Setup Guide — Student QR Payment Tracker (Private Room Join Approval Workflow & Multi-Cashier Duplicate Payment Prevention)

This application uses an **Offline-First Room SQLite (`v4`) + Firebase Auth + Real-Time Firestore & Storage Sync** architecture compatible with the **Firebase Free Spark Plan**.

* **Zero Data Loss Guarantee:** Non-destructive Room migrations (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`) preserve 100% of existing local rooms, folders, student receipts, expenses, and audit logs.
* **Cryptographic Room Security (`PBKDF2WithHmacSHA256` + Legacy Fallback):** Room passwords are salted with a 16-byte `SecureRandom` salt (`room_salt`) and hashed with 65,536-iteration `PBKDF2WithHmacSHA256`, verified via constant-time `MessageDigest.isEqual` across both `rooms/{roomId}/private/security` and parent `rooms/{roomId}` documents.
* **Zero Join Cooldown + End-to-End Owner Approval Gate:** Officers can immediately retry entering a Room Key and Password without any cooldown lockout. When `requireApproval == true`, join requests are written directly to `rooms/{roomId}/members/{userId}` with `status: "PENDING_APPROVAL"`. The Room Owner receives the request in real time in **Room Members & Access** (Approve as `COLLECTOR` or `AUDITOR`, Reject, or Ban), and the joiner's device unlocks automatically the moment the Owner approves.
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

## Step 3: Publish Updated Production Firestore Security Rules (`firestore.rules`)
1. In the Firebase Console left sidebar, go to **Build $\rightarrow$ Firestore Database $\rightarrow$ Rules**.
2. Copy the contents of [`firestore.rules`](firestore.rules) in the project root and click **Publish**:
   * **Room Lookup & Credential Verification:** Signed-in users (`request.auth != null`) can query `rooms` by `roomKey` and read `rooms/{roomId}/private/security` to verify the room password before joining.
   * **Join Request Creation & Updates:** Signed-in users can `create` or `update` their own member document at `rooms/{roomId}/members/{userId}` with `role == 'COLLECTOR'` and `status in ['PENDING_APPROVAL', 'ACTIVE']` (unless `BANNED`).
   * **Owner Approval & Role Management:** The Room Owner (`rooms/{roomId}.ownerUserId == request.auth.uid` or `role == 'OWNER'` and `status == 'ACTIVE'` in `members/{uid}`) can read, approve, change roles, ban, or delete any document in `rooms/{roomId}/members/{memberId}`.
   * **Role-Enforced Writes & Immutable Audit Trail:** `AUDITOR` is strictly read-only; `COLLECTOR` can record payments/expenses; only `OWNER` can void records, delete folders, or release `payment_locks`; `audit_logs` is append-only (`allow update, delete: if false;`).

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
