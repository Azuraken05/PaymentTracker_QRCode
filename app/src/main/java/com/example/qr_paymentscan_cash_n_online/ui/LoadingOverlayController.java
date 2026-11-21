package com.example.qr_paymentscan_cash_n_online.ui;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.animation.ValueAnimator;
import android.widget.TextView;

import com.example.qr_paymentscan_cash_n_online.R;

/**
 * Full-screen animated "syncing" overlay: a dimmed scrim that blocks taps, a centered Material 3 card with a
 * pulsing halo, a rotating sync icon inside an indeterminate circular progress ring, and a dynamic title /
 * subtitle. Fades + scales in over 180ms, fades out over 150ms, and always dismisses itself after a 12 second
 * safety timeout so the UI can never stay locked.
 */
public final class LoadingOverlayController {

    private static final long ENTER_DURATION_MS = 180L;
    private static final long EXIT_DURATION_MS = 150L;
    private static final long SAFETY_TIMEOUT_MS = 12_000L;
    private static final float ENTER_START_SCALE = 0.92f;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable safetyTimeoutRunnable = this::hide;

    private View root;
    private View card;
    private View halo;
    private View icon;
    private TextView titleView;
    private TextView subtitleView;

    private ObjectAnimator rotationAnimator;
    private ObjectAnimator pulseAnimator;
    private boolean showing = false;

    public LoadingOverlayController(Activity activity) {
        this.activity = activity;
    }

    public boolean isShowing() {
        return showing;
    }

    public void show(String title, String subtitle) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        ensureAttached();
        if (root == null) {
            return;
        }
        titleView.setText(title != null ? title : activity.getString(R.string.loading_default_title));
        subtitleView.setText(subtitle != null ? subtitle : activity.getString(R.string.loading_default_subtitle));

        handler.removeCallbacks(safetyTimeoutRunnable);
        handler.postDelayed(safetyTimeoutRunnable, SAFETY_TIMEOUT_MS);

        if (showing) {
            return;
        }
        showing = true;
        root.animate().cancel();
        card.animate().cancel();
        root.setAlpha(0f);
        card.setScaleX(ENTER_START_SCALE);
        card.setScaleY(ENTER_START_SCALE);
        root.setVisibility(View.VISIBLE);
        root.bringToFront();

        DecelerateInterpolator decelerate = new DecelerateInterpolator();
        root.animate().alpha(1f).setDuration(ENTER_DURATION_MS).setInterpolator(decelerate).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(ENTER_DURATION_MS).setInterpolator(decelerate).start();
        startLoopingAnimators();
    }

    public void show(String title) {
        show(title, activity != null ? activity.getString(R.string.loading_default_subtitle) : null);
    }

    public void hide() {
        handler.removeCallbacks(safetyTimeoutRunnable);
        if (!showing || root == null) {
            return;
        }
        showing = false;
        root.animate().cancel();
        root.animate()
                .alpha(0f)
                .setDuration(EXIT_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (!showing && root != null) {
                        root.setVisibility(View.GONE);
                        stopLoopingAnimators();
                    }
                })
                .start();
    }

    /** Call from Activity.onDestroy to cancel timers and animators. */
    public void release() {
        handler.removeCallbacksAndMessages(null);
        showing = false;
        if (root != null) {
            root.animate().cancel();
            root.setVisibility(View.GONE);
        }
        stopLoopingAnimators();
    }

    private void ensureAttached() {
        if (root != null) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        LayoutInflater.from(activity).inflate(R.layout.layout_loading_overlay, content, true);
        root = content.findViewById(R.id.loadingOverlayRoot);
        card = content.findViewById(R.id.loadingCard);
        halo = content.findViewById(R.id.loadingHalo);
        icon = content.findViewById(R.id.loadingIcon);
        titleView = content.findViewById(R.id.loadingTitle);
        subtitleView = content.findViewById(R.id.loadingSubtitle);
    }

    private void startLoopingAnimators() {
        stopLoopingAnimators();
        rotationAnimator = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f);
        rotationAnimator.setDuration(1100L);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.start();

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                halo,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.88f, 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.88f, 1.12f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.95f, 0.35f)
        );
        pulseAnimator.setDuration(900L);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.start();
    }

    private void stopLoopingAnimators() {
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
            rotationAnimator = null;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }
}
