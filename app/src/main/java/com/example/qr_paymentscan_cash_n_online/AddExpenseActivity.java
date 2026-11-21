package com.example.qr_paymentscan_cash_n_online;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.example.qr_paymentscan_cash_n_online.data.AppDatabase;
import com.example.qr_paymentscan_cash_n_online.data.ExpenseEntity;
import com.example.qr_paymentscan_cash_n_online.data.PaymentRepository;
import com.example.qr_paymentscan_cash_n_online.ui.BaseThemedActivity;
import com.example.qr_paymentscan_cash_n_online.ui.LoadingOverlayController;
import com.example.qr_paymentscan_cash_n_online.util.LocalStorageManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;

public class AddExpenseActivity extends BaseThemedActivity {

    public static final String EXTRA_SECTION_ID = "extra_section_id";
    public static final String EXTRA_SECTION_NAME = "extra_section_name";

    private static final String[] EXPENSE_CATEGORIES = new String[]{
            "Supplies",
            "Printing",
            "Food & Drinks",
            "Transportation",
            "Venue/Equipment",
            "Other"
    };

    private long sectionId;
    private PaymentRepository repository;

    private View rootView;
    private TextInputLayout tilExpenseTitle;
    private TextInputEditText etExpenseTitle;
    private TextInputLayout tilExpenseAmount;
    private TextInputEditText etExpenseAmount;
    private MaterialAutoCompleteTextView actvExpenseCategory;
    private TextInputEditText etExpenseNotes;
    private MaterialCardView cardExpenseProofPreview;
    private ImageView ivExpenseProofPreview;
    private TextView tvExpenseProofStatus;
    private MaterialButton btnSubmitExpense;
    private LoadingOverlayController loadingOverlay;

    private String savedInternalProofPath = null;
    private Uri pendingCameraCaptureUri = null;

    private final ActivityResultLauncher<String> pickGalleryProofLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    persistExpenseProofUri(uri);
                }
            });

    private final ActivityResultLauncher<Uri> captureCameraProofLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && pendingCameraCaptureUri != null) {
                    persistExpenseProofUri(pendingCameraCaptureUri);
                }
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    launchCameraCapture();
                } else {
                    Snackbar.make(rootView, "Camera permission is required to capture a receipt photo.", Snackbar.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_add_expense);

        rootView = findViewById(R.id.addExpenseRoot);
        loadingOverlay = new LoadingOverlayController(this);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sectionId = getIntent().getLongExtra(EXTRA_SECTION_ID, -1L);
        String sectionName = getIntent().getStringExtra(EXTRA_SECTION_NAME);
        if (sectionName == null) {
            sectionName = "Section";
        }

        repository = new PaymentRepository(this);

        ImageButton btnBack = findViewById(R.id.btnBackAddExpense);
        TextView tvSectionBadge = findViewById(R.id.tvAddExpenseSectionBadge);
        tvSectionBadge.setText("Deducting from: " + sectionName);
        btnBack.setOnClickListener(v -> finish());

        tilExpenseTitle = findViewById(R.id.tilExpenseTitle);
        etExpenseTitle = findViewById(R.id.etExpenseTitle);
        tilExpenseAmount = findViewById(R.id.tilExpenseAmount);
        etExpenseAmount = findViewById(R.id.etExpenseAmount);
        actvExpenseCategory = findViewById(R.id.actvExpenseCategory);
        etExpenseNotes = findViewById(R.id.etExpenseNotes);
        MaterialButton btnPickGallery = findViewById(R.id.btnPickExpenseProofGallery);
        MaterialButton btnCaptureCamera = findViewById(R.id.btnCaptureExpenseProofCamera);
        cardExpenseProofPreview = findViewById(R.id.cardExpenseProofPreview);
        ivExpenseProofPreview = findViewById(R.id.ivExpenseProofPreview);
        tvExpenseProofStatus = findViewById(R.id.tvExpenseProofStatus);
        btnSubmitExpense = findViewById(R.id.btnSubmitExpense);

        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                EXPENSE_CATEGORIES
        );
        actvExpenseCategory.setAdapter(categoryAdapter);
        actvExpenseCategory.setText(EXPENSE_CATEGORIES[0], false);

        btnPickGallery.setOnClickListener(v -> pickGalleryProofLauncher.launch("image/*"));
        btnCaptureCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                launchCameraCapture();
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnSubmitExpense.setOnClickListener(v -> validateAndSubmitExpense());
    }

    private void launchCameraCapture() {
        try {
            pendingCameraCaptureUri = LocalStorageManager.createTempCameraCaptureUri(this);
            captureCameraProofLauncher.launch(pendingCameraCaptureUri);
        } catch (Exception e) {
            Snackbar.make(rootView, "Unable to launch camera: " + e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }

    private void persistExpenseProofUri(Uri sourceUri) {
        tvExpenseProofStatus.setText("Saving receipt image to local internal storage...");
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                String savedPath = LocalStorageManager.copyExpenseProofToInternalStorage(this, sourceUri);
                runOnUiThread(() -> {
                    savedInternalProofPath = savedPath;
                    cardExpenseProofPreview.setVisibility(View.VISIBLE);
                    Glide.with(this)
                            .load(new File(savedPath))
                            .centerCrop()
                            .into(ivExpenseProofPreview);
                    tvExpenseProofStatus.setText("Receipt photo saved offline: " + new File(savedPath).getName());
                    tvExpenseProofStatus.setTextColor(ContextCompat.getColor(this, R.color.status_paid_green));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvExpenseProofStatus.setText("Failed to attach receipt photo: " + e.getMessage());
                    tvExpenseProofStatus.setTextColor(ContextCompat.getColor(this, R.color.status_void_red));
                });
            }
        });
    }

    private void validateAndSubmitExpense() {
        tilExpenseTitle.setError(null);
        tilExpenseAmount.setError(null);

        String title = etExpenseTitle.getText() != null ? etExpenseTitle.getText().toString().trim() : "";
        String amountStr = etExpenseAmount.getText() != null ? etExpenseAmount.getText().toString().trim() : "";
        String category = actvExpenseCategory.getText() != null ? actvExpenseCategory.getText().toString().trim() : "Supplies";
        String notes = etExpenseNotes.getText() != null ? etExpenseNotes.getText().toString().trim() : "";

        boolean hasError = false;
        if (title.isEmpty()) {
            tilExpenseTitle.setError("Expense title / item used for is required");
            hasError = true;
        }

        double amount = 0.0;
        try {
            amount = Double.parseDouble(amountStr);
            if (amount <= 0.0) {
                tilExpenseAmount.setError("Amount must be greater than ₱0.00");
                hasError = true;
            }
        } catch (NumberFormatException e) {
            tilExpenseAmount.setError("Enter a valid numeric amount");
            hasError = true;
        }

        if (hasError) {
            return;
        }

        btnSubmitExpense.setEnabled(false);
        loadingOverlay.show("Recording Expense...", "Updating across all connected devices");
        repository.recordExpense(
                sectionId,
                title,
                amount,
                category,
                notes,
                savedInternalProofPath,
                new PaymentRepository.ResultCallback<ExpenseEntity>() {
                    @Override
                    public void onSuccess(ExpenseEntity result) {
                        loadingOverlay.hide();
                        finish();
                    }

                    @Override
                    public void onError(String message) {
                        loadingOverlay.hide();
                        btnSubmitExpense.setEnabled(true);
                        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
                    }
                }
        );
    }

    @Override
    protected void onDestroy() {
        if (loadingOverlay != null) {
            loadingOverlay.release();
        }
        super.onDestroy();
    }
}
