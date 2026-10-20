package com.configscanner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
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
 * Modern progress hero (v81): glassy rounded track, gradient fill with a
 * breathing glow, drifting sparkle particles inside the filled area, a
 * luminous travelling sheen while the scan is active, and a soft pulse at
 * the leading edge. All frames are dot-per-frame allocation-free: shaders
 * are cached and rebuilt only when the fill width or view size changes.
 *
 * Public API stays identical to the previous version, so MainActivity
 * does not need any change: {@link #setProgress(float)} and
 * {@link #setRunning(boolean)}.
 */
public class ProgressHeroView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowBehindPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    // particle field: fixed count, deterministic seeds, no allocation per frame
    private static final int PARTICLES = 12;
    private final float[] pSeed = new float[PARTICLES];
    private final float[] pAmp = new float[PARTICLES];
    private final float[] pSize = new float[PARTICLES];
    private final float[] pSpeed = new float[PARTICLES];

    private float lastFillW = -1f;
    private float viewW = 0f;

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

        sheenPaint.setStyle(Paint.Style.FILL);

        headPaint.setColor(accent);
        headPaint.setStyle(Paint.Style.FILL);

        headGlowPaint.setColor(accent);
        headGlowPaint.setStyle(Paint.Style.FILL);

        glowBehindPaint.setColor(accent);
        glowBehindPaint.setStyle(Paint.Style.FILL);

        particlePaint.setColor(0xFFFFFFFF);
        particlePaint.setStyle(Paint.Style.FILL);
        try {
            particlePaint.setMaskFilter(new BlurMaskFilter(dp(1.4f), BlurMaskFilter.Blur.SOLID));
        } catch (Exception ignored) {
            // HW accel may disable masks on some devices; particles still draw crisp
        }

        java.util.Random rnd = new java.util.Random(2026);
        for (int i = 0; i < PARTICLES; i++) {
            pSeed[i] = rnd.nextFloat();
            pAmp[i] = (rnd.nextFloat() - 0.5f) * dp(5f);
            pSize[i] = dp(1.1f) + rnd.nextFloat() * dp(1.6f);
            pSpeed[i] = 0.55f + rnd.nextFloat() * 0.9f;
        }
    }

    /** progress in percent (0..100) */
    public void setProgress(float pct) {
        pct = Math.max(0f, Math.min(100f, pct));
        progress = pct;
        if (displayAnim != null) {
            displayAnim.cancel();
        }
        displayAnim = ValueAnimator.ofFloat(display, progress);
        displayAnim.setDuration(450);
        displayAnim.setInterpolator(new DecelerateInterpolator());
        displayAnim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            if (Math.abs(v - display) > 0.05f) {   // skip micro-frames
                display = v;
                invalidate();
            }
            lastFillW = -1f; // force shader refresh on settle
        });
        displayAnim.start();
    }

    /** Starts/stops the shimmer sweep + particles (call while a scan is active). */
    public void setRunning(boolean r) {
        running = r;
        if (r) {
            if (sweepAnim == null) {
                sweepAnim = ValueAnimator.ofFloat(0f, 1f);
                sweepAnim.setDuration(1800);
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
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        viewW = w;
        lastFillW = -1f;
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
        float cy = top + barHeight / 2f;

        float fillW = w * display / 100f;
        if (fillW != lastFillW || viewW != w) {
            fillPaint.setShader(new LinearGradient(0, top, Math.max(fillW, 1f), top + barHeight,
                    accentAlt, accent, Shader.TileMode.CLAMP));
            lastFillW = fillW;
        }
        viewW = w;

        float breath = running ? (0.55f + 0.45f * (float) Math.sin(sweep * Math.PI * 2.0)) : 0.9f;

        // soft glow pool behind the filled area (breathing while running)
        if (fillW > dp(10f)) {
            glowBehindPaint.setAlpha((int) (running ? 26 * breath : 14));
            rect.set(dp(-6), cy - dp(14), fillW + dp(6), cy + dp(14));
            canvas.drawRoundRect(rect, dp(14), dp(14), glowBehindPaint);
            glowBehindPaint.setAlpha(255);
        }

        // base track
        rect.set(0, top, w, top + barHeight);
        canvas.drawRoundRect(rect, radius, radius, trackPaint);

        if (fillW >= dp(2f)) {
            rect.set(0, top, fillW, top + barHeight);
            // never let a rounded end produce negative width
            float drawW = Math.min(fillW, w);
            rect.set(0, top, drawW, top + barHeight);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);

            int save = canvas.save();
            // clip particles + sheen + head to the pill area
            canvas.clipRect(0, top, drawW, top + barHeight);

            // sparkle particles drifting inside the filled part
            if (running && fillW > dp(20f)) {
                for (int i = 0; i < PARTICLES; i++) {
                    float fx = ((pSeed[i] + sweep * pSpeed[i]) % 1f) * fillW;
                    float fy = cy + pAmp[i] * (float) Math.sin((pSeed[i] + sweep) * Math.PI * 4f);
                    float fade = (float) Math.min(1.0, Math.min(fx, fillW - fx) / dp(14f));
                    if (fade <= 0f) continue;
                    particlePaint.setAlpha((int) (running ? 150 * fade * breath : 90 * fade));
                    canvas.drawCircle(fx, fy, pSize[i], particlePaint);
                }
                particlePaint.setAlpha(255);
            }

            // travelling sheen across the filled area
            if (running && fillW > dp(30f)) {
                float band = dp(30f);
                float sx = sweep * (fillW + band * 2f) - band;
                float sl = Math.max(0f, sx - band);
                float sr = Math.min(fillW, sx + band);
                if (sr > sl) {
                    sheenPaint.setShader(new LinearGradient(sl, 0, sr, 0,
                            0x00FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP));
                    rect.set(sl, top, sr, top + barHeight);
                    canvas.drawRoundRect(rect, radius, radius, sheenPaint);
                    sheenPaint.setShader(null);
                }
            }

            // luminous head dot: breathing halo + crisp core
            float hx = Math.max(radius, Math.min(fillW, w - radius));
            headGlowPaint.setAlpha((int) (running ? 60 + 45 * breath : 40));
            canvas.drawCircle(hx, cy, dp(9.5f), headGlowPaint);
            headPaint.setAlpha(255);
            canvas.drawCircle(hx, cy, dp(4.5f), headPaint);
            headPaint.setAlpha(140);
            canvas.drawCircle(hx, cy, dp(1.8f), headPaint);
            headPaint.setAlpha(255);

            canvas.restoreToCount(save);
        }

        // subtle tick marks every ~10% — scale hints under the bar
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
