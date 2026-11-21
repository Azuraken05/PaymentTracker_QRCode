package com.example.qr_paymentscan_cash_n_online.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;

import androidx.core.content.FileProvider;

import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.AuditLogEntity;
import com.example.qr_paymentscan_cash_n_online.data.ExpenseEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentEntity;
import com.example.qr_paymentscan_cash_n_online.data.SectionEntity;
import com.example.qr_paymentscan_cash_n_online.sync.FirebaseSyncManager;

import org.dhatim.fastexcel.BorderStyle;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class LocalStorageManager {

    public static class SavedMediaResult {
        public final Uri uri;
        public final String displayPath;
        public final String fileName;

        public SavedMediaResult(Uri uri, String displayPath, String fileName) {
            this.uri = uri;
            this.displayPath = displayPath;
            this.fileName = fileName;
        }
    }

    private LocalStorageManager() {
    }

    /**
     * Copies and compresses an uploaded or camera-captured GCash proof image into internal storage
     * (context.getFilesDir()/payment_proofs/) so the image never breaks even if deleted from gallery.
     */
    public static String copyProofImageToInternalStorage(Context context, Uri sourceUri) throws IOException {
        return copyImageToInternalFolder(context, sourceUri, "payment_proofs", "GCASH_PROOF_");
    }

    /**
     * Copies and compresses an optional Expense receipt / proof image into internal storage
     * (context.getFilesDir()/expense_proofs/).
     */
    public static String copyExpenseProofToInternalStorage(Context context, Uri sourceUri) throws IOException {
        return copyImageToInternalFolder(context, sourceUri, "expense_proofs", "EXPENSE_PROOF_");
    }

    private static String copyImageToInternalFolder(
            Context context,
            Uri sourceUri,
            String folderName,
            String filePrefix
    ) throws IOException {
        File proofDir = new File(context.getFilesDir(), folderName);
        if (!proofDir.exists() && !proofDir.mkdirs()) {
            throw new IOException("Unable to create local " + folderName + " folder.");
        }

        Bitmap decoded;
        try (InputStream is = context.getContentResolver().openInputStream(sourceUri)) {
            if (is == null) {
                throw new IOException("Unable to open selected proof image.");
            }
            decoded = BitmapFactory.decodeStream(is);
        }
        if (decoded == null) {
            throw new IOException("Selected file is not a valid image.");
        }

        int maxDimension = 1600;
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        Bitmap outputBitmap = decoded;
        if (width > maxDimension || height > maxDimension) {
            float ratio = Math.min((float) maxDimension / width, (float) maxDimension / height);
            int targetW = Math.max(1, Math.round(width * ratio));
            int targetH = Math.max(1, Math.round(height * ratio));
            outputBitmap = Bitmap.createScaledBitmap(decoded, targetW, targetH, true);
        }

        String fileName = filePrefix + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 6) + ".jpg";
        File destFile = new File(proofDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(destFile)) {
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            fos.flush();
        }
        return destFile.getAbsolutePath();
    }

    /**
     * Creates a temporary camera capture file in cache/temp_captures/ and returns its FileProvider Uri.
     */
    public static Uri createTempCameraCaptureUri(Context context) throws IOException {
        File captureDir = new File(context.getCacheDir(), "temp_captures");
        if (!captureDir.exists() && !captureDir.mkdirs()) {
            throw new IOException("Could not create camera temp folder.");
        }
        File tempFile = new File(captureDir, "CAPTURED_PROOF_" + System.currentTimeMillis() + ".jpg");
        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                tempFile
        );
    }

    /**
     * Renders a View hierarchy (receipt_container) on the UI thread into a high-resolution Bitmap.
     */
    public static Bitmap captureViewBitmap(View receiptView) {
        int width = receiptView.getWidth();
        int height = receiptView.getHeight();
        if (width <= 0 || height <= 0) {
            int measureWidth = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
            int measureHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            receiptView.measure(measureWidth, measureHeight);
            width = receiptView.getMeasuredWidth();
            height = receiptView.getMeasuredHeight();
            receiptView.layout(0, 0, width, height);
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        receiptView.draw(canvas);
        return bitmap;
    }

    /**
     * Saves a rendered receipt Bitmap to Pictures/PaymentReceipts/ via MediaStore on a background thread.
     */
    public static SavedMediaResult saveReceiptBitmapToPublicPictures(Context context, Bitmap bitmap, String receiptId) throws IOException {
        String fileName = receiptId + "_" + CurrencyFormatter.formatFileTimestamp(System.currentTimeMillis()) + ".png";
        String relativePath = Environment.DIRECTORY_PICTURES + "/PaymentReceipts";

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (itemUri == null) {
            throw new IOException("Unable to create MediaStore entry in Pictures/PaymentReceipts.");
        }

        try (OutputStream os = resolver.openOutputStream(itemUri)) {
            if (os == null) {
                throw new IOException("Failed to open image output stream.");
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues doneValues = new ContentValues();
            doneValues.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(itemUri, doneValues, null, null);
        }

        return new SavedMediaResult(itemUri, "Pictures/PaymentReceipts/" + fileName, fileName);
    }

    /**
     * Exports a Section Folder's payment records (with optional Student Number 02000XXXXXX),
     * section expenses/deductions, and section audit logs to a real Microsoft Excel (.xlsx)
     * file in Downloads/PaymentTracker/ using org.dhatim:fastexcel.
     */
    public static SavedMediaResult exportSectionToExcel(
            Context context,
            SectionEntity section,
            List<PaymentEntity> payments,
            List<ExpenseEntity> expenses,
            List<AuditLogEntity> auditLogs
    ) throws IOException {
        String safeSection = section.sectionName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String timestamp = CurrencyFormatter.formatFileTimestamp(System.currentTimeMillis());
        String fileName = safeSection + "_Ledger_" + timestamp + ".xlsx";

        ByteArrayOutputStream excelBytes = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(excelBytes, "StudentPaymentTracker", "2.0")) {
            // =========================================================================
            // Worksheet 1: Section Payments (with Student Number & Financial Summary)
            // =========================================================================
            Worksheet ws = wb.newWorksheet("Section Payments");
            ws.width(0, 6);
            ws.width(1, 24);
            ws.width(2, 16);
            ws.width(3, 18);
            ws.width(4, 18);
            ws.width(5, 24);
            ws.width(6, 16);
            ws.width(7, 22);
            ws.width(8, 16);
            ws.width(9, 14);
            ws.width(10, 28);
            ws.width(11, 24);
            ws.width(12, 24);
            ws.width(13, 28);

            ws.value(0, 0, "SECTION PAYMENT LEDGER — " + section.sectionName);
            ws.style(0, 0).bold().fontSize(14).set();

            String desc = (section.description != null && !section.description.isEmpty())
                    ? section.description
                    : "General Section Collection";
            ws.value(1, 0, "Collection Description: " + desc);
            ws.style(1, 0).italic().set();

            ws.value(2, 0, "Exported On: " + CurrencyFormatter.formatDateTime(System.currentTimeMillis()));

            int headerRow = 4;
            String[] headers = new String[]{
                    "No.",
                    "Receipt ID",
                    "Student Number",
                    "First Name",
                    "Last Name",
                    "Payment Purpose",
                    "Payment Method",
                    "Reference / Transaction ID",
                    "Amount (₱)",
                    "Status",
                    "Void Reason",
                    "Date Recorded",
                    "Date Voided",
                    "Recorded By"
            };

            for (int col = 0; col < headers.length; col++) {
                ws.value(headerRow, col, headers[col]);
                ws.style(headerRow, col)
                        .bold()
                        .fillColor("D3E3FD")
                        .borderStyle(BorderStyle.THIN)
                        .set();
            }

            double totalValidAmount = 0.0;
            double totalVoidedAmount = 0.0;
            int validCount = 0;
            int voidedCount = 0;

            int rowIdx = headerRow + 1;
            int counter = 1;
            if (payments != null) {
                for (PaymentEntity p : payments) {
                    ws.value(rowIdx, 0, counter++);
                    ws.value(rowIdx, 1, p.receiptId);
                    ws.value(rowIdx, 2, p.hasStudentNumber() ? p.studentNumber : "N/A");
                    ws.value(rowIdx, 3, p.firstName);
                    ws.value(rowIdx, 4, p.lastName);
                    ws.value(rowIdx, 5, p.purpose);
                    boolean hasProof = (p.proofImagePath != null && !p.proofImagePath.isEmpty())
                            || (p.remoteProofUrl != null && !p.remoteProofUrl.isEmpty())
                            || (p.proofImageUrl != null && !p.proofImageUrl.isEmpty());
                    String refValue = PaymentValidationHelper.formatReferenceDisplay(p.paymentMethod, p.gcashReferenceNo, hasProof);
                    ws.value(rowIdx, 6, p.paymentMethod);
                    ws.value(rowIdx, 7, refValue);
                    ws.value(rowIdx, 8, p.amount);
                    ws.style(rowIdx, 8).format("₱#,##0.00").set();
                    ws.value(rowIdx, 9, p.status);
                    ws.value(rowIdx, 10, p.voidReason != null ? p.voidReason : "-");
                    ws.value(rowIdx, 11, CurrencyFormatter.formatDateTime(p.createdAt));
                    ws.value(rowIdx, 12, p.voidedAt != null ? CurrencyFormatter.formatDateTime(p.voidedAt) : "-");
                    ws.value(rowIdx, 13, p.getFormattedActorAttribution());

                    String statusColor = p.isVoided() ? "FCE8E6" : "E6F4EA";
                    ws.style(rowIdx, 9).bold().fillColor(statusColor).set();

                    if (p.isVoided()) {
                        totalVoidedAmount += p.amount;
                        voidedCount++;
                    } else {
                        totalValidAmount += p.amount;
                        validCount++;
                    }
                    rowIdx++;
                }
            }

            double totalActiveExpenses = 0.0;
            int activeExpenseCount = 0;
            if (expenses != null) {
                for (ExpenseEntity ex : expenses) {
                    if (!ex.isVoided()) {
                        totalActiveExpenses += ex.amount;
                        activeExpenseCount++;
                    }
                }
            }
            double netRemainingBalance = totalValidAmount - totalActiveExpenses;

            int summaryStart = rowIdx + 1;
            ws.value(summaryStart, 0, "SECTION FINANCIAL SUMMARY");
            ws.style(summaryStart, 0).bold().set();

            ws.value(summaryStart + 1, 0, "Gross Valid Collected (₱):");
            ws.style(summaryStart + 1, 0).bold().set();
            ws.value(summaryStart + 1, 1, CurrencyFormatter.formatPhp(totalValidAmount));
            ws.style(summaryStart + 1, 1).bold().fillColor("E6F4EA").set();

            ws.value(summaryStart + 2, 0, "Total Expenses Deducted (- ₱):");
            ws.style(summaryStart + 2, 0).bold().set();
            ws.value(summaryStart + 2, 1, "-" + CurrencyFormatter.formatPhp(totalActiveExpenses));
            ws.style(summaryStart + 2, 1).bold().fillColor("FEF7E0").set();

            ws.value(summaryStart + 3, 0, "Net Remaining Balance (₱):");
            ws.style(summaryStart + 3, 0).bold().set();
            ws.value(summaryStart + 3, 1, CurrencyFormatter.formatPhp(netRemainingBalance));
            ws.style(summaryStart + 3, 1).bold().fillColor("D3E3FD").set();

            ws.value(summaryStart + 4, 0, "Total Voided Payments (₱):");
            ws.value(summaryStart + 4, 1, CurrencyFormatter.formatPhp(totalVoidedAmount));

            ws.value(summaryStart + 5, 0, "Valid Receipts / Voided Receipts / Active Expenses:");
            ws.value(summaryStart + 5, 1, validCount + " Valid / " + voidedCount + " Voided / " + activeExpenseCount + " Expenses");

            ws.finish();

            // =========================================================================
            // Worksheet 2: Section Expenses (Deductions)
            // =========================================================================
            Worksheet expSheet = wb.newWorksheet("Section Expenses");
            expSheet.width(0, 6);
            expSheet.width(1, 28);
            expSheet.width(2, 18);
            expSheet.width(3, 16);
            expSheet.width(4, 32);
            expSheet.width(5, 14);
            expSheet.width(6, 28);
            expSheet.width(7, 24);
            expSheet.width(8, 24);
            expSheet.width(9, 28);

            expSheet.value(0, 0, "SECTION EXPENSES & DEDUCTIONS — " + section.sectionName);
            expSheet.style(0, 0).bold().fontSize(13).set();

            String[] expHeaders = new String[]{
                    "No.",
                    "Expense Title / Item Used For",
                    "Category",
                    "Amount Used (₱)",
                    "Notes / Remarks",
                    "Status",
                    "Void Reason",
                    "Date Recorded",
                    "Date Voided",
                    "Recorded By"
            };
            for (int c = 0; c < expHeaders.length; c++) {
                expSheet.value(2, c, expHeaders[c]);
                expSheet.style(2, c).bold().fillColor("FEF7E0").borderStyle(BorderStyle.THIN).set();
            }

            int eRow = 3;
            int eCounter = 1;
            if (expenses != null) {
                for (ExpenseEntity ex : expenses) {
                    expSheet.value(eRow, 0, eCounter++);
                    expSheet.value(eRow, 1, ex.title);
                    expSheet.value(eRow, 2, ex.category);
                    expSheet.value(eRow, 3, ex.amount);
                    expSheet.style(eRow, 3).format("₱#,##0.00").set();
                    expSheet.value(eRow, 4, ex.notes != null ? ex.notes : "-");
                    expSheet.value(eRow, 5, ex.status);
                    expSheet.value(eRow, 6, ex.voidReason != null ? ex.voidReason : "-");
                    expSheet.value(eRow, 7, CurrencyFormatter.formatDateTime(ex.createdAt));
                    expSheet.value(eRow, 8, ex.voidedAt != null ? CurrencyFormatter.formatDateTime(ex.voidedAt) : "-");
                    expSheet.value(eRow, 9, ex.getFormattedActorAttribution());

                    String stColor = ex.isVoided() ? "FCE8E6" : "E6F4EA";
                    expSheet.style(eRow, 5).bold().fillColor(stColor).set();
                    eRow++;
                }
            }
            expSheet.finish();

            // =========================================================================
            // Worksheet 3: Section Audit Log
            // =========================================================================
            Worksheet auditSheet = wb.newWorksheet("Section Audit Log");
            auditSheet.width(0, 24);
            auditSheet.width(1, 24);
            auditSheet.width(2, 28);
            auditSheet.width(3, 24);
            auditSheet.width(4, 68);

            auditSheet.value(0, 0, "AUDIT TRAIL — " + section.sectionName);
            auditSheet.style(0, 0).bold().fontSize(13).set();

            String[] auditHeaders = new String[]{"Timestamp", "Action Type", "Actor (Role)", "Receipt / Ref ID", "Details"};
            for (int c = 0; c < auditHeaders.length; c++) {
                auditSheet.value(2, c, auditHeaders[c]);
                auditSheet.style(2, c).bold().fillColor("D3E3FD").borderStyle(BorderStyle.THIN).set();
            }

            int aRow = 3;
            if (auditLogs != null) {
                for (AuditLogEntity log : auditLogs) {
                    auditSheet.value(aRow, 0, CurrencyFormatter.formatDateTime(log.timestamp));
                    auditSheet.value(aRow, 1, log.actionType);
                    auditSheet.value(aRow, 2, log.getFormattedActorAttribution());
                    auditSheet.value(aRow, 3, log.receiptId != null ? log.receiptId : "-");
                    auditSheet.value(aRow, 4, log.details);
                    aRow++;
                }
            }
            auditSheet.finish();
        }

        byte[] rawBytes = excelBytes.toByteArray();
        return saveExcelBytesToDownloads(context, fileName, rawBytes);
    }

    private static SavedMediaResult saveExcelBytesToDownloads(Context context, String fileName, byte[] bytes) throws IOException {
        String mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/PaymentTracker";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Unable to create Excel file in Downloads/PaymentTracker.");
            }
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) {
                    throw new IOException("Failed to open Excel output stream.");
                }
                os.write(bytes);
                os.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return new SavedMediaResult(uri, "Downloads/PaymentTracker/" + fileName, fileName);
        } else {
            File exportDir = new File(context.getCacheDir(), "exports");
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                throw new IOException("Could not create export folder.");
            }
            File outFile = new File(exportDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(bytes);
                fos.flush();
            }
            Uri providerUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    outFile
            );
            return new SavedMediaResult(providerUri, outFile.getAbsolutePath(), fileName);
        }
    }

    /**
     * Exports all Sections (including parentFolderId), Payments (including studentNumber),
     * Expenses, Audit Logs, and the HMAC signing key to a single JSON backup file.
     */
    public static int exportDatabaseToJsonUri(Context context, Uri targetUri) throws Exception {
        AppDatabase db = AppDatabase.getInstance(context);
        List<SectionEntity> sections = db.sectionDao().getAllSectionsSync();
        List<PaymentEntity> payments = db.paymentDao().getAllPaymentsSync();
        List<ExpenseEntity> expenses = db.expenseDao().getAllExpensesSync();
        List<AuditLogEntity> logs = db.auditLogDao().getAllLogsSync();

        JSONObject root = new JSONObject();
        root.put("backupVersion", 2);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("hmacSecretKey", SecuritySignatureHelper.getOrCreateSecretKey(context));

        JSONArray sectionsArr = new JSONArray();
        for (SectionEntity s : sections) {
            JSONObject obj = new JSONObject();
            obj.put("id", s.id);
            obj.put("sectionName", s.sectionName);
            obj.put("description", s.description != null ? s.description : "");
            obj.put("createdAt", s.createdAt);
            obj.put("parentFolderId", s.parentFolderId != null ? s.parentFolderId : JSONObject.NULL);
            obj.put("targetFeeAmount", s.targetFeeAmount);
            sectionsArr.put(obj);
        }
        root.put("sections", sectionsArr);

        JSONArray paymentsArr = new JSONArray();
        for (PaymentEntity p : payments) {
            JSONObject obj = new JSONObject();
            obj.put("id", p.id);
            obj.put("receiptId", p.receiptId);
            obj.put("sectionId", p.sectionId);
            obj.put("sectionName", p.sectionName);
            obj.put("firstName", p.firstName);
            obj.put("lastName", p.lastName);
            obj.put("studentNumber", p.studentNumber != null ? p.studentNumber : JSONObject.NULL);
            obj.put("purpose", p.purpose);
            obj.put("amount", p.amount);
            obj.put("paymentMethod", p.paymentMethod);
            obj.put("gcashReferenceNo", p.gcashReferenceNo != null ? p.gcashReferenceNo : JSONObject.NULL);
            obj.put("proofImagePath", p.proofImagePath != null ? p.proofImagePath : JSONObject.NULL);
            obj.put("remoteProofUrl", p.remoteProofUrl != null ? p.remoteProofUrl : JSONObject.NULL);
            obj.put("status", p.status);
            obj.put("paymentStatus", p.paymentStatus);
            obj.put("remainingBalance", p.remainingBalance);
            obj.put("voidReason", p.voidReason != null ? p.voidReason : JSONObject.NULL);
            obj.put("createdAt", p.createdAt);
            obj.put("voidedAt", p.voidedAt != null ? p.voidedAt : JSONObject.NULL);
            obj.put("qrSignature", p.qrSignature);
            obj.put("qrPayload", p.qrPayload);
            paymentsArr.put(obj);
        }
        root.put("payments", paymentsArr);

        JSONArray expensesArr = new JSONArray();
        for (ExpenseEntity ex : expenses) {
            JSONObject obj = new JSONObject();
            obj.put("id", ex.id);
            obj.put("sectionId", ex.sectionId);
            obj.put("sectionName", ex.sectionName);
            obj.put("title", ex.title);
            obj.put("amount", ex.amount);
            obj.put("category", ex.category);
            obj.put("notes", ex.notes != null ? ex.notes : JSONObject.NULL);
            obj.put("proofImagePath", ex.proofImagePath != null ? ex.proofImagePath : JSONObject.NULL);
            obj.put("remoteProofUrl", ex.remoteProofUrl != null ? ex.remoteProofUrl : JSONObject.NULL);
            obj.put("status", ex.status);
            obj.put("voidReason", ex.voidReason != null ? ex.voidReason : JSONObject.NULL);
            obj.put("createdAt", ex.createdAt);
            obj.put("voidedAt", ex.voidedAt != null ? ex.voidedAt : JSONObject.NULL);
            expensesArr.put(obj);
        }
        root.put("expenses", expensesArr);

        JSONArray logsArr = new JSONArray();
        for (AuditLogEntity l : logs) {
            JSONObject obj = new JSONObject();
            obj.put("id", l.id);
            obj.put("actionType", l.actionType);
            obj.put("sectionId", l.sectionId != null ? l.sectionId : JSONObject.NULL);
            obj.put("sectionName", l.sectionName != null ? l.sectionName : JSONObject.NULL);
            obj.put("receiptId", l.receiptId != null ? l.receiptId : JSONObject.NULL);
            obj.put("details", l.details);
            obj.put("timestamp", l.timestamp);
            logsArr.put(obj);
        }
        root.put("auditLogs", logsArr);

        byte[] jsonBytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = context.getContentResolver().openOutputStream(targetUri)) {
            if (os == null) {
                throw new IOException("Cannot write to selected backup file.");
            }
            os.write(jsonBytes);
            os.flush();
        }

        db.auditLogDao().insertAuditLog(new AuditLogEntity(
                AuditLogEntity.ACTION_DATABASE_BACKUP,
                null,
                null,
                null,
                "Exported local JSON backup (" + sections.size() + " folders, "
                        + payments.size() + " receipts, " + expenses.size() + " expenses).",
                System.currentTimeMillis()
        ));

        return payments.size();
    }

    /**
     * Restores Sections, Payments, Expenses, Audit Logs, and the HMAC signing key from both v1 and v2 JSON backup files.
     */
    public static int restoreDatabaseFromJsonUri(Context context, Uri sourceUri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(sourceUri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }

        JSONObject root = new JSONObject(sb.toString());
        String secretKey = root.optString("hmacSecretKey", null);
        SecuritySignatureHelper.restoreSecretKeyIfPresent(context, secretKey);

        AppDatabase db = AppDatabase.getInstance(context);
        JSONArray sectionsArr = root.optJSONArray("sections");
        if (sectionsArr != null) {
            for (int i = 0; i < sectionsArr.length(); i++) {
                JSONObject sObj = sectionsArr.getJSONObject(i);
                Long parentFolderId = (!sObj.has("parentFolderId") || sObj.isNull("parentFolderId"))
                        ? null
                        : sObj.getLong("parentFolderId");
                double targetFee = sObj.optDouble("targetFeeAmount", 0.0);
                long createdAt = sObj.getLong("createdAt");
                SectionEntity section = new SectionEntity(
                        sObj.getString("sectionName"),
                        sObj.optString("description", ""),
                        createdAt,
                        parentFolderId,
                        targetFee,
                        SectionEntity.SYNC_PENDING,
                        createdAt
                );
                section.id = sObj.getLong("id");
                db.sectionDao().insertOrReplaceSection(section);
            }
        }

        int restoredPayments = 0;
        JSONArray paymentsArr = root.optJSONArray("payments");
        if (paymentsArr != null) {
            for (int i = 0; i < paymentsArr.length(); i++) {
                JSONObject pObj = paymentsArr.getJSONObject(i);
                String status = pObj.getString("status");
                long createdAt = pObj.getLong("createdAt");
                PaymentEntity payment = new PaymentEntity(
                        pObj.getString("receiptId"),
                        pObj.getLong("sectionId"),
                        pObj.getString("sectionName"),
                        pObj.getString("firstName"),
                        pObj.getString("lastName"),
                        (!pObj.has("studentNumber") || pObj.isNull("studentNumber"))
                                ? null
                                : pObj.optString("studentNumber", null),
                        pObj.getString("purpose"),
                        pObj.getDouble("amount"),
                        pObj.getString("paymentMethod"),
                        pObj.isNull("gcashReferenceNo") ? null : pObj.optString("gcashReferenceNo", null),
                        pObj.isNull("proofImagePath") ? null : pObj.optString("proofImagePath", null),
                        (!pObj.has("remoteProofUrl") || pObj.isNull("remoteProofUrl"))
                                ? null
                                : pObj.optString("remoteProofUrl", null),
                        status,
                        pObj.optString("paymentStatus", status),
                        pObj.optDouble("remainingBalance", 0.0),
                        pObj.isNull("voidReason") ? null : pObj.optString("voidReason", null),
                        createdAt,
                        pObj.isNull("voidedAt") ? null : pObj.getLong("voidedAt"),
                        pObj.getString("qrSignature"),
                        pObj.getString("qrPayload"),
                        PaymentEntity.SYNC_PENDING,
                        createdAt
                );
                payment.id = pObj.getLong("id");
                db.paymentDao().insertOrReplacePayment(payment);
                restoredPayments++;
            }
        }

        JSONArray expensesArr = root.optJSONArray("expenses");
        if (expensesArr != null) {
            for (int i = 0; i < expensesArr.length(); i++) {
                JSONObject eObj = expensesArr.getJSONObject(i);
                long createdAt = eObj.getLong("createdAt");
                ExpenseEntity expense = new ExpenseEntity(
                        eObj.getLong("sectionId"),
                        eObj.getString("sectionName"),
                        eObj.getString("title"),
                        eObj.getDouble("amount"),
                        eObj.optString("category", "Other"),
                        eObj.isNull("notes") ? null : eObj.optString("notes", null),
                        eObj.isNull("proofImagePath") ? null : eObj.optString("proofImagePath", null),
                        (!eObj.has("remoteProofUrl") || eObj.isNull("remoteProofUrl"))
                                ? null
                                : eObj.optString("remoteProofUrl", null),
                        eObj.optString("status", ExpenseEntity.STATUS_ACTIVE),
                        eObj.isNull("voidReason") ? null : eObj.optString("voidReason", null),
                        createdAt,
                        eObj.isNull("voidedAt") ? null : eObj.getLong("voidedAt"),
                        ExpenseEntity.SYNC_PENDING,
                        createdAt
                );
                expense.id = eObj.getLong("id");
                db.expenseDao().insertOrReplaceExpense(expense);
            }
        }

        JSONArray logsArr = root.optJSONArray("auditLogs");
        if (logsArr != null) {
            for (int i = 0; i < logsArr.length(); i++) {
                JSONObject lObj = logsArr.getJSONObject(i);
                long ts = lObj.getLong("timestamp");
                AuditLogEntity log = new AuditLogEntity(
                        lObj.getString("actionType"),
                        lObj.isNull("sectionId") ? null : lObj.getLong("sectionId"),
                        lObj.isNull("sectionName") ? null : lObj.optString("sectionName", null),
                        lObj.isNull("receiptId") ? null : lObj.optString("receiptId", null),
                        lObj.getString("details"),
                        ts,
                        AuditLogEntity.SYNC_PENDING,
                        ts
                );
                log.id = lObj.getLong("id");
                db.auditLogDao().insertOrReplaceAuditLog(log);
            }
        }

        db.auditLogDao().insertAuditLog(new AuditLogEntity(
                AuditLogEntity.ACTION_DATABASE_BACKUP,
                null,
                null,
                null,
                "Restored local JSON backup (" + restoredPayments + " payment receipt(s) imported).",
                System.currentTimeMillis()
        ));

        FirebaseSyncManager.enqueueImmediateSyncIfAvailable(context);
        return restoredPayments;
    }
}
