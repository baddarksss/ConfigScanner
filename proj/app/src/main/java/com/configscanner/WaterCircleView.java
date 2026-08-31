package com.configscanner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * A circle that fills with animated "water" as the progress rises.
 * Three wave layers drift at different speeds over a vertical gradient,
 * bubbles rise through the water, the ring glows while a run is active,
 * and the percentage counts up smoothly. Reaching 100% fires a one-shot
 * expanding glow pulse.
 */
public class WaterCircleView extends View {

    private static final int WAVE_STEPS = 48;
    private static final int BUBBLES = 12;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterFront = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterMid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final Path circle = new Path();
    private final RectF bounds = new RectF();

    private float progress = 0f;         // target 0..100
    private float display = 0f;          // animated 0..100
    private float phase = 0f;            // wave phase
    private float pulse = -1f;           // completion pulse 0..1, -1 = off
    private boolean running = false;
    private int accent = 0xFF5B9BFF;

    private ValueAnimator anim;
    private ValueAnimator displayAnim;
    private ValueAnimator pulseAnim;

    private final float[] bubbleX = new float[BUBBLES];
    private final float[] bubbleR = new float[BUBBLES];
    private final float[] bubbleSpeed = new float[BUBBLES];
    private final float[] bubbleOff = new float[BUBBLES];

    public WaterCircleView(Context c) {
        super(c);
        init(c);
    }

    public WaterCircleView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    public WaterCircleView(Context c, AttributeSet a, int d) {
        super(c, a, d);
        init(c);
    }

    private void init(Context c) {
        int cardBg = androidx.core.content.ContextCompat.getColor(c, R.color.card_bg);
        int textCol = c.getColor(R.color.text_primary);
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true);
        if (tv.resourceId != 0) accent = c.getResources().getColor(tv.resourceId, c.getTheme());

        bgPaint.setColor(cardBg);
        bgPaint.setAlpha(160);
        waterMid.setColor(0xFF38BDF8);
        waterMid.setAlpha(120);
        waterDeep.setColor(0xFF0EA5E9);
        waterDeep.setAlpha(70);
        ringPaint.setColor(accent);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2.2f));
        glowPaint.setColor(accent);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(7f));
        pulsePaint.setColor(accent);
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(dp(2.5f));
        bubblePaint.setColor(0xFFFFFFFF);
        bubblePaint.setAlpha(80);
        textPaint.setColor(textCol);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        // deterministic pseudo-random bubbles (golden-ratio distribution)
        for (int i = 0; i < BUBBLES; i++) {
            bubbleX[i] = fract(i * 0.6180339887f);
            bubbleR[i] = dp(1.1f) + (i % 3) * dp(0.7f);
            bubbleSpeed[i] = 0.10f + 0.05f * (i % 4);
            bubbleOff[i] = fract(i * 0.3819660113f);
        }
    }

    private static float fract(float v) {
        return v - (int) v;
    }

    /** progress in percent (0..100) */
    public void setProgress(float pct) {
        pct = Math.max(0f, Math.min(100f, pct));
        boolean completing = pct >= 100f && progress < 100f;
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
        if (completing) firePulse();
    }

    /** Starts/stops the wave animation (call while a test run is active). */
    public void setRunning(boolean r) {
        running = r;
        if (r) {
            if (anim == null) {
                anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
                anim.setDuration(1600);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new LinearInterpolator());
                anim.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
            }
            if (!anim.isStarted()) anim.start();
        } else if (anim != null) {
            anim.cancel();
        }
        invalidate();
    }

    private void firePulse() {
        if (pulseAnim != null) pulseAnim.cancel();
        pulse = 0f;
        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(700);
        pulseAnim.setInterpolator(new DecelerateInterpolator());
        pulseAnim.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnim.start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(3f);
        float size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2f + pad;
        float top = (getHeight() - size) / 2f + pad;
        bounds.set(left, top, left + size - pad * 2, top + size - pad * 2);
        circle.reset();
        circle.addOval(bounds, Path.Direction.CW);

        // base disc
        canvas.drawOval(bounds, bgPaint);

        canvas.save();
        canvas.clipPath(circle);

        float level = bounds.bottom - (bounds.height() * display / 100f);
        float amp1 = size * 0.028f;
        float amp2 = size * 0.020f;
        float f1 = 2.1f * (float) Math.PI / size;
        float f2 = 2.9f * (float) Math.PI / size;
        float slosh = running ? (float) Math.sin(phase * 0.9f) * amp1 * 0.5f : 0f;

        // back to front: deep, mid, gradient front
        drawWave(canvas, level + amp1 * 1.4f, amp1 * 1.5f, f1 * 0.75f,
                phase * 0.55f + 0.6f, waterDeep);
        drawWave(canvas, level + amp1 * 0.7f, amp2, f2,
                -phase * 1.35f + 1.3f, waterMid);

        waterFront.setShader(new LinearGradient(bounds.left, level, bounds.left, bounds.bottom,
                0xFF7DD3FC, 0xFF0284C7, Shader.TileMode.CLAMP));
        drawWave(canvas, level + slosh, amp1, f1, phase, waterFront);
        waterFront.setShader(null);

        // rising bubbles
        if (display > 4f) {
            float t = (System.nanoTime() % 60_000_000_000L) / 1e9f;
            float depth = bounds.bottom - (level + amp1) - dp(4f);
            if (depth > 0) {
                for (int i = 0; i < BUBBLES; i++) {
                    float frac = 1f - fract(t * bubbleSpeed[i] + bubbleOff[i]);
                    float bx = bounds.left + bounds.width()
                            * (bubbleX[i] + (float) Math.sin(t * 0.8f + i * 1.7f) * 0.012f);
                    float by = level + amp1 + frac * depth;
                    canvas.drawCircle(bx, by, bubbleR[i], bubblePaint);
                }
            }
        }
        canvas.restore();

        // glowing ring (pulses while running, faint when idle)
        int glowAlpha = running
                ? (int) (38 + 26 * Math.sin(phase * 1.7f))
                : 22;
        glowPaint.setAlpha(Math.max(0, glowAlpha));
        canvas.drawOval(bounds, glowPaint);
        canvas.drawOval(bounds, ringPaint);

        // one-shot completion pulse
        if (pulse >= 0f && pulse < 1f) {
            float pr = size / 2f + pulse * size * 0.22f;
            pulsePaint.setAlpha((int) (150 * (1f - pulse)));
            canvas.drawCircle(left + size / 2f, top + size / 2f, pr, pulsePaint);
        } else {
            pulse = -1f;
        }

        // percent text
        textPaint.setTextSize(size * 0.20f);
        String txt = String.valueOf(Math.round(display)) + "%";
        float ty = top + size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(txt, left + size / 2f, ty, textPaint);
    }

    private void drawWave(Canvas canvas, float level, float amp, float freq,
                          float ph, Paint paint) {
        wave.reset();
        float w = bounds.width();
        float startX = bounds.left;
        wave.moveTo(startX, level);
        for (int i = 1; i <= WAVE_STEPS; i++) {
            float x = startX + w * i / WAVE_STEPS;
            float y = level + (float) Math.sin(x * freq + ph) * amp;
            wave.lineTo(x, y);
        }
        wave.lineTo(bounds.right, bounds.bottom + 10);
        wave.lineTo(bounds.left, bounds.bottom + 10);
        wave.close();
        canvas.drawPath(wave, paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setRunning(false);
        anim = null;
        if (displayAnim != null) displayAnim.cancel();
        displayAnim = null;
        if (pulseAnim != null) pulseAnim.cancel();
        pulseAnim = null;
    }
}
