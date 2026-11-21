package com.example.qr_paymentscan_cash_n_online.ui;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.qr_paymentscan_cash_n_online.R;

import java.io.File;

public final class ProofImagePreviewDialog {

    private ProofImagePreviewDialog() {
    }

    public static void show(@NonNull Context context, @Nullable File localFile, @Nullable String remoteUrl) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_proof_image_preview, null);
        dialog.setContentView(dialogView);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        ImageView ivZoomable = dialogView.findViewById(R.id.ivZoomableProof);
        ImageButton btnClose = dialogView.findViewById(R.id.btnCloseZoomDialog);
        ImageButton btnReset = dialogView.findViewById(R.id.btnResetZoom);

        btnClose.setOnClickListener(v -> dialog.dismiss());

        setupTouchImageView(ivZoomable, btnReset);

        if (localFile != null && localFile.exists()) {
            Glide.with(context)
                    .load(localFile)
                    .listener(createFitCenterListener(ivZoomable))
                    .into(ivZoomable);
        } else if (remoteUrl != null && !remoteUrl.trim().isEmpty()) {
            Glide.with(context)
                    .load(remoteUrl)
                    .listener(createFitCenterListener(ivZoomable))
                    .into(ivZoomable);
        }

        dialog.show();
    }

    private static RequestListener<Drawable> createFitCenterListener(ImageView imageView) {
        return new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                imageView.post(() -> fitImageToView(imageView, resource));
                return false;
            }
        };
    }

    private static void fitImageToView(ImageView imageView, Drawable drawable) {
        if (drawable == null) return;
        int dWidth = drawable.getIntrinsicWidth();
        int dHeight = drawable.getIntrinsicHeight();
        int vWidth = imageView.getWidth();
        int vHeight = imageView.getHeight();

        if (dWidth <= 0 || dHeight <= 0 || vWidth <= 0 || vHeight <= 0) return;

        float scale = Math.min((float) vWidth / dWidth, (float) vHeight / dHeight);
        float dx = (vWidth - dWidth * scale) / 2f;
        float dy = (vHeight - dHeight * scale) / 2f;

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        imageView.setImageMatrix(matrix);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void setupTouchImageView(ImageView imageView, ImageButton btnReset) {
        final Matrix matrix = new Matrix();
        final Matrix savedMatrix = new Matrix();

        final int NONE = 0;
        final int DRAG = 1;
        final int ZOOM = 2;
        final int[] mode = {NONE};

        final PointF start = new PointF();
        final PointF mid = new PointF();
        final float[] oldDist = {1f};

        btnReset.setOnClickListener(v -> {
            Drawable d = imageView.getDrawable();
            if (d != null) {
                fitImageToView(imageView, d);
            }
        });

        imageView.setOnTouchListener((v, event) -> {
            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(imageView.getImageMatrix());
                    start.set(event.getX(), event.getY());
                    mode[0] = DRAG;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    oldDist[0] = spacing(event);
                    if (oldDist[0] > 10f) {
                        savedMatrix.set(imageView.getImageMatrix());
                        midPoint(mid, event);
                        mode[0] = ZOOM;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    mode[0] = NONE;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode[0] == DRAG) {
                        matrix.set(savedMatrix);
                        matrix.postTranslate(event.getX() - start.x, event.getY() - start.y);
                        imageView.setImageMatrix(matrix);
                    } else if (mode[0] == ZOOM) {
                        float newDist = spacing(event);
                        if (newDist > 10f) {
                            matrix.set(savedMatrix);
                            float scale = newDist / oldDist[0];
                            matrix.postScale(scale, scale, mid.x, mid.y);
                            imageView.setImageMatrix(matrix);
                        }
                    }
                    break;
            }
            return true;
        });
    }

    private static float spacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private static void midPoint(PointF point, MotionEvent event) {
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2f, y / 2f);
    }
}
