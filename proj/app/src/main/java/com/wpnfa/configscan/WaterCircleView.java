package com.wpnfa.configscan;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A circle that fills with animated "water" as the progress rises.
 * Two overlapping sine waves drift horizontally; the water level tracks
 * progress (0..100). A percentage label sits in the center.
 */
public class WaterCircleView extends View {

    private static final int WAVE_STEPS = 48;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterPaint1 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final RectF bounds = new RectF();

    private float progress = 0f;        // 0..100
    private float phase = 0f;
    private boolean running = false;

    private ValueAnimator anim;

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
        // Colors resolve through the context's current (light/dark) config.
        int cardBg = androidx.core.content.ContextCompat.getColor(c, R.color.card_bg);
        int textCol = c.getColor(R.color.text_primary);
        int accent;
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true);
        accent = tv.resourceId != 0
                ? c.getResources().getColor(tv.resourceId, c.getTheme())
                : 0xFF5B9BFF;

        bgPaint.setColor(cardBg);
        bgPaint.setAlpha(160);
        waterPaint1.setColor(0xFF38BDF8);
        waterPaint1.setAlpha(235);
        waterPaint2.setColor(0xFF0EA5E9);
        waterPaint2.setAlpha(140);
        ringPaint.setColor(accent);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2.2f));
        textPaint.setColor(textCol);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** progress in percent (0..100) */
    public void setProgress(float pct) {
        progress = Math.max(0f, Math.min(100f, pct));
        invalidate();
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

        // base disc
        canvas.drawOval(bounds, bgPaint);

        canvas.save();
        canvas.clipPath(circlePath());

        // water level: 100% => wave at the very top
        float level = bounds.bottom - (bounds.height() * progress / 100f);
        float amp1 = size * 0.028f;
        float amp2 = size * 0.020f;
        float f1 = 2.1f * (float) Math.PI / size; // wavelength ~ half the diameter
        float f2 = 2.9f * (float) Math.PI / size;

        drawWave(canvas, level, amp1, f1, phase, waterPaint1);
        drawWave(canvas, level + amp1 * 0.9f, amp2, f2, -phase * 1.6f + 1.3f, waterPaint2);
        canvas.restore();

        // ring
        canvas.drawOval(bounds, ringPaint);

        // percent text
        textPaint.setTextSize(size * 0.20f);
        String txt = String.valueOf(Math.round(progress)) + "%";
        float ty = top + size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(txt, left + size / 2f, ty, textPaint);
    }

    private Path circlePath() {
        Path p = new Path();
        p.addOval(bounds, Path.Direction.CW);
        return p;
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
    }
}
