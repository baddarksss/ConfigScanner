package com.configscanner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * Minimal modern progress hero: a big rounded track that fills with an
 * animated accent gradient while a sweep-light glides over the leading
 * edge. Percent is NOT drawn here — the layout layers a large number on
 * top, keeping the view pure and crisp.
 */
public class ProgressHeroView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float progress = 0f;   // target 0..100
    private float display = 0f;    // animated 0..100
    private float sweep = 0f;      // running shimmer phase 0..1
    private boolean running = false;

    private int accent = 0xFF2E6BFF;
    private int accentAlt = 0xFF38BDF8;
    private int trackColor = 0x142C4A80;

    private ValueAnimator displayAnim;
    private ValueAnimator sweepAnim;

    public ProgressHeroView(Context c) {
        super(c);
        init(c);
    }

    public ProgressHeroView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    public ProgressHeroView(Context c, AttributeSet a, int d) {
        super(c, a, d);
        init(c);
    }

    private void init(Context c) {
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true);
        if (tv.resourceId != 0) {
            accent = c.getResources().getColor(tv.resourceId, c.getTheme());
        }
        trackColor = c.getColor(R.color.hero_track);
        accentAlt = c.getColor(R.color.hero_fill_end);

        trackPaint.setColor(trackColor);
        trackPaint.setStyle(Paint.Style.FILL);

        fillPaint.setStyle(Paint.Style.FILL);

        headPaint.setColor(accent);
        headPaint.setStyle(Paint.Style.FILL);

        headGlowPaint.setColor(accent);
        headGlowPaint.setStyle(Paint.Style.FILL);

        tickPaint.setColor(c.getColor(R.color.hero_tick));
        tickPaint.setStyle(Paint.Style.FILL);
    }

    /** progress in percent (0..100) */
    public void setProgress(float pct) {
        pct = Math.max(0f, Math.min(100f, pct));
        progress = pct;
        if (displayAnim != null) displayAnim.cancel();
        displayAnim = ValueAnimator.ofFloat(display, progress);
        displayAnim.setDuration(450);
        displayAnim.setInterpolator(new DecelerateInterpolator());
        displayAnim.addUpdateListener(a -> {
            display = (float) a.getAnimatedValue();
            invalidate();
        });
        displayAnim.start();
    }

    /** Starts/stops the shimmer sweep (call while a test run is active). */
    public void setRunning(boolean r) {
        running = r;
        if (r) {
            if (sweepAnim == null) {
                sweepAnim = ValueAnimator.ofFloat(0f, 1f);
                sweepAnim.setDuration(1400);
                sweepAnim.setRepeatCount(ValueAnimator.INFINITE);
                sweepAnim.setInterpolator(new LinearInterpolator());
                sweepAnim.addUpdateListener(a -> {
                    sweep = (float) a.getAnimatedValue();
                    invalidate();
                });
            }
            if (!sweepAnim.isStarted()) sweepAnim.start();
        } else if (sweepAnim != null) {
            sweepAnim.cancel();
            sweep = 0f;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float barHeight = Math.min(h, dp(26f));
        float top = (h - barHeight) / 2f;
        float radius = barHeight / 2f;
        rect.set(0, top, w, top + barHeight);

        // base track
        canvas.drawRoundRect(rect, radius, radius, trackPaint);

        float fillW = w * display / 100f;
        if (fillW > 0f) {
            // gradient fill; keep the shader fresh as the head moves
            fillPaint.setShader(new LinearGradient(0, 0, Math.max(fillW, 1f), 0,
                    accentAlt, accent, Shader.TileMode.CLAMP));
            rect.set(0, top, fillW, top + barHeight);
            // never let a tiny sliver produce a negative width
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            fillPaint.setShader(null);

            // glowing head dot at the leading edge
            float cx = Math.max(radius, Math.min(fillW, w - radius));
            float cy = top + barHeight / 2f;
            headGlowPaint.setAlpha(running ? 70 : 40);
            canvas.drawCircle(cx, cy, dp(9f), headGlowPaint);
            headPaint.setAlpha(255);
            canvas.drawCircle(cx, cy, dp(4.5f), headPaint);

            // shimmer sweep across the FILLED part only
            if (running && fillW > dp(30f)) {
                float band = dp(26f);
                float sx = sweep * (fillW + band * 2f) - band;
                int save = canvas.saveLayerAlpha(0, top, w, top + barHeight, 66, Canvas.ALL_SAVE_FLAG);
                fillPaint.setColor(0xFFFFFFFF);
                float sl = Math.max(0f, sx - band);
                float sr = Math.min(fillW, sx + band);
                if (sr > sl) {
                    rect.set(sl, top, sr, top + barHeight);
                    fillPaint.setShader(new LinearGradient(sl, 0, sr, 0,
                            0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(rect, radius, radius, fillPaint);
                    fillPaint.setShader(null);
                }
                canvas.restoreToCount(save);
            }
        }

        // subtle tick marks every 10% — gives the bar a sense of scale
        int ticks = 9;
        for (int i = 1; i <= ticks; i++) {
            float x = w * i / (ticks + 1f);
            float th = (i % 5 == 0) ? dp(6f) : dp(3.5f);
            rect.set(x - dp(0.75f), top + barHeight + dp(5f),
                    x + dp(0.75f), top + barHeight + dp(5f) + th);
            canvas.drawRoundRect(rect, dp(0.75f), dp(0.75f), tickPaint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setRunning(false);
        sweepAnim = null;
        if (displayAnim != null) displayAnim.cancel();
        displayAnim = null;
    }
}
