package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

// Lightweight in-app confirmation banner (e.g. "Level in die Zwischenablage kopiert"), drawn near
// the top of the board instead of shown as a system Toast. Reason: Android ignores
// Toast#setGravity() for apps targeting API 30+ (this app targets 35), so a normal Toast always
// lands at its fixed system-controlled position near the bottom of the screen -- which is exactly
// where Android's own "Copied to clipboard" popup (with its Copy/Share actions) also appears after
// ClipboardManager#setPrimaryClip(), so the Toast covers it (reported bug, confirmed unfixable via
// Toast positioning). Drawing our own banner sidesteps the platform restriction entirely.
public class MessageBannerAnimation extends Animation {

    private static final int FADE_FRAMES = 15;
    // Clears the menu button (top-right, see Game.getMenuButtonRect()) which sits in roughly the
    // same y-range right at the top of the screen.
    private static final float TOP_MARGIN = 100f;

    private final String text;
    private final Paint bgPaint;
    private final Paint textPaint;

    public MessageBannerAnimation(GameBoard gb, int dur, String text) {
        super(gb, dur);
        this.text = text;

        bgPaint = new Paint();
        bgPaint.setColor(Color.rgb(30, 30, 40));
        bgPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(38);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
    }

    @Override
    public boolean update() {
        animationCycle++;
        return animationCycle >= animationDuration;
    }

    @Override
    public void draw(Canvas c) {
        int alpha = alphaForCycle();
        if (alpha <= 0) return;

        float centerX = gb.getXOffset() + gb.getWidth() / 2f;
        float top = gb.getYOffset() + TOP_MARGIN;

        float textWidth = textPaint.measureText(text);
        float paddingH = 40f;
        float paddingV = 24f;
        RectF box = new RectF(centerX - textWidth / 2f - paddingH, top,
                centerX + textWidth / 2f + paddingH, top + textPaint.getTextSize() + 2 * paddingV);

        bgPaint.setAlpha(Math.min(220, alpha));
        textPaint.setAlpha(alpha);
        c.drawRoundRect(box, 20, 20, bgPaint);
        c.drawText(text, centerX, box.top + paddingV + textPaint.getTextSize() * 0.75f, textPaint);
    }

    private int alphaForCycle() {
        if (animationCycle < FADE_FRAMES) {
            return (int) (255f * animationCycle / FADE_FRAMES);
        }
        int fadeOutStart = animationDuration - FADE_FRAMES;
        if (animationCycle >= fadeOutStart) {
            return (int) (255f * (animationDuration - animationCycle) / FADE_FRAMES);
        }
        return 255;
    }
}
