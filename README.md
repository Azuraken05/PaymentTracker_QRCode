# VeriPay — Student Payment Tracking & QR Receipt Verification System

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Language-Java-ED8B00?logo=openjdk&logoColor=white)
![UI](https://img.shields.io/badge/UI-Material%20Design%203-6200EE?logo=materialdesign&logoColor=white)
![Backend](https://img.shields.io/badge/Backend-Firebase%20Firestore-FFCA28?logo=firebase&logoColor=black)
![Media](https://img.shields.io/badge/Image%20Cloud-Cloudinary-3448C5?logo=cloudinary&logoColor=white)

An offline-first, multi-user Android application built for class treasurers, student councils, and campus organizations to manage fee collections, issue tamper-proof QR digital receipts, and audit multi-officer transactions in real time.

---

## Key Features

### Multi-User Workspaces & Folder Hierarchy
* **Private Collaborative Rooms:** Create or join isolated collection workspaces using a unique Room Key (`KEY-XXXXXX`) or a scannable Room Invite QR code.
* **Automatic Multi-Room Discovery:** Automatically discovers, hydrates, and syncs all owned and joined rooms across devices upon sign-in (`RoomDiscoveryManager`), plus one-tap migration of local offline records into a cloud room.
* **Three-Tier Role-Based Access Control:**
  * **Owner (`OWNER`):** Full administrative control over room security settings, member approvals, role assignments, folder structures, and email-verified permanent room deletion.
  * **Collector (`COLLECTOR`):** Can create/manage folders, record and edit payments or expenses, void invalid entries with required reasons, and export reports.
  * **Auditor (`AUDITOR`):** Strictly read-only access to inspect balances, digital receipts, proof images, and immutable audit logs.
* **Two-Level Section Hierarchy & Gross Roll-Up:** Organize collections cleanly into **Main Section Folders** and single-level **Subfolders**, with an Owner/Collector toggle to merge a subfolder's Gross Collected amount directly into its parent folder's total.

### Room Security & Anti-Hacking Console
* **Salted Room Credentials:** Room passwords use PBKDF2 hashing stored in restricted security sub-documents, paired with password reveal toggles and key rotation tools.
* **Access Governance & Locks:** Enforce an **Owner Approval Gate** (`PENDING_APPROVAL`), trigger an **Emergency Room Lock**, or set invite key expiration timers.
* **Cloud vs. Local Room Policies:** Room owners can require signed-in Firebase accounts (`requireCloudAccount`) or permit offline local accounts per workspace.
* **Session & Device Privacy Controls:**
  * Strict session teardown (`SessionManager`) enforcing re-login after sign-out.
  * User-controlled **Offline Room Access** privacy switch (`allowOfflineRoomAccess`) that clears cached cloud rooms from the device upon logout when disabled.
  * Optional **Biometric / Device PIN App Lock** (`androidx.biometric`) and `FLAG_SECURE` recent-apps screen privacy shield.

### Cryptographic QR Receipts & Flexible Payment Validation
* **HMAC-SHA256 QR Verification:** Every generated digital receipt encodes an HMAC-SHA256 cryptographic signature (`qrSignature`) verified against the room's signing secret to detect forged or altered receipts.
* **Multi-Provider Payment Support:** Supports **Cash**, **GCash**, **Maya**, **GoTyme**, **Bank Transfer**, and custom online wallets.
* **Flexible Digital Proof Validation:** Online payments accept any alphanumeric Reference/Transaction ID **or** an attached proof photo (or both), preventing bottlenecks when a payer only provides a screenshot or a reference code.
* **Receipt Editing, Duplicate Guard & Audit Trail:** Authorized officers can edit receipt details with automatic balance recalculation, QR signature re-signing, duplicate lock updates (`DuplicatePaymentGuard`), before/after audit diffing, and string-safe Excel report exports.

### Offline-First Architecture & FinTech Dashboard
* **Bidirectional Real-Time Sync:** Local **Room SQLite** storage ensures uninterrupted receipt issuance without internet access, while **Cloud Firestore** snapshot listeners synchronize folders, subfolders, payments, expenses, rosters, and deletions across all connected devices in real time.
* **Cloud Image Storage & Caching:** Proof photos are automatically compressed (`1280px` max edge at `80%` JPEG quality), uploaded to **Cloudinary** via OkHttp with offline retry queuing, and cached locally via **Glide**.
* **Modern Material Design 3 Interface:** Features a FinTech command dashboard with a live 12h/24h digital clock header, jitter-free tabular numerals, and hardware-accelerated sync overlays (`LoadingOverlayController`).

---

## Tech Stack

| Layer | Technology / Library |
| :--- | :--- |
| **Language & Platform** | Java, Android SDK (`com.example.qr_paymentscan_cash_n_online`) |
| **UI & Animations** | Material Design 3, `NestedScrollView`, `DiffUtil`, `TextClock`, `LoadingOverlayController` |
| **Local Persistence** | Android Room SQLite (non-destructive migrations), `AppExecutors` background thread pools |
| **Cloud Database & Auth** | Firebase Authentication (Email/Password + Email Verification), Cloud Firestore (`collectionGroup`, `WriteBatch`) |
| **Image Storage & Loading** | Cloudinary REST API (Unsigned Upload), OkHttp `4.12.0`, Glide `4.16.0` |
| **Security & Biometrics** | PBKDF2 Password Hashing, HMAC-SHA256 QR Signatures, AndroidX Biometric `1.1.0`, `FLAG_SECURE` |

---

## Project Architecture

```text
app/src/main/java/com/example/qr_paymentscan_cash_n_online/
├── auth/ & session/
│   ├── AuthActivity.java                 # Sign-in, registration, password reset & local mode entry
│   ├── EmailVerificationActivity.java    # Mandatory email verification gate
│   ├── ProfileActivity.java              # Account status badge, profile editor & privacy switches
│   └── SessionManager.java               # Session lifecycle, cache cleanup & forced re-login guard
├── rooms/ & security/
│   ├── RoomLobbyActivity.java            # Multi-room discovery, QR room scanner & room creation
│   ├── RoomMembersActivity.java            # Room Security Console, member approval & access controls
│   ├── RoomDiscoveryManager.java         # Cloud owned/joined workspace hydration
│   ├── RoomDeletionManager.java          # Email-verified cascading room deletion
│   ├── LocalToCloudMigrationManager.java # One-tap local workspace upload to Firestore
│   ├── RoomSecurityHelper.java           # PBKDF2 hashing & HMAC-SHA256 QR signing
│   ├── RoomPolicyGuard.java              # Cloud-only vs. local room permission enforcement
│   └── AppLockManager.java               # Biometric / device credential lock
├── ledger/ & receipts/
│   ├── MainActivity.java                 # Dashboard command clock & main section folder list
│   ├── SectionDetailActivity.java        # Fee ledger, single-level subfolders, roster & roll-up totals
│   ├── SectionFinancialCalculator.java   # Recursive gross collected & net balance calculator
│   ├── AddPaymentActivity.java           # Multi-wallet payment entry & proof capture
│   ├── EditReceiptActivity.java          # Receipt modification with QR re-signing & audit diff
│   ├── ReceiptDetailActivity.java        # Digital receipt display, QR verification & void workflow
│   ├── PaymentValidationHelper.java      # Alphanumeric reference OR proof image validation
│   └── DuplicatePaymentGuard.java        # Cross-device duplicate payment lock manager
└── sync/ & storage/
    ├── AppDatabase.java                  # Room SQLite schema & non-destructive migrations
    ├── AppExecutors.java                 # Dedicated diskIO, networkIO & mainThread executors
    ├── FirestoreRealtimeSyncManager.java # Bidirectional snapshot listeners & deletion pruning
    ├── CloudImageStorageManager.java     # Image compression, Cloudinary upload & offline queue
    ├── CloudImageConfig.java             # Cloudinary environment constants
    └── LoadingOverlayController.java     # Hardware-accelerated Material 3 sync overlay
```

---

## Setup & Installation

### 1. Prerequisites
* Android Studio (Ladybug or newer recommended)
* JDK 17+
* A free [Firebase](https://console.firebase.google.com/) project (Spark Plan compatible)
* A free [Cloudinary](https://cloudinary.com/) account (for cloud receipt proof hosting)

### 2. Clone the Repository
```bash
git clone [https://github.com/Azuraken05/VeriPay-Android.git](https://github.com/Azuraken05/VeriPay-Android.git)
cd VeriPay-Android
```

### 3. Configure Firebase
1. In the **Firebase Console**, create a new project and register an Android app with package name:
   ```text
   com.example.qr_paymentscan_cash_n_online
   ```
2. Download **`google-services.json`** and place it inside the `app/` directory.
3. Enable **Email/Password** sign-in under **Authentication > Sign-in method**.
4. Create a **Cloud Firestore** database, open the **Rules** tab, paste the contents of the root `firestore.rules` file, and click **Publish**.

### 4. Configure Cloudinary Image Storage
1. Log in to your **Cloudinary Dashboard** and copy your **Cloud Name**.
2. Go to **Settings > Upload > Upload presets**, create an **Unsigned** upload preset (e.g., `trackerpayment_unsigned`), and set the target folder to `tracker_payment`.
3. Open `CloudImageConfig.java` and insert your credentials:
   ```java
   public static final String CLOUDINARY_CLOUD_NAME = "your_cloud_name";
   public static final String CLOUDINARY_UPLOAD_PRESET = "your_unsigned_preset";
   ```

### 5. Build & Run
Open the project in Android Studio, allow Gradle to sync dependencies, and run the app on a physical Android device or emulator (`minSdk 24+`).

---

## Author

This was made by **Azuraken05**
