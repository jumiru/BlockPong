package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

// Floating "+N" text shown briefly where a block was hit, rising and fading out.
public class ScorePopupAnimation extends Animation {

    private final String text;
    private final float centerX;
    private final float startY;
    private final float riseDistance;
    private final Paint paint;

    public ScorePopupAnimation(GameBoard gb, int dur, int x, int y, int points) {
        super(gb, dur);
        this.text = "+" + points;
        this.centerX = (gb.left(x) + gb.right(x)) / 2f;
        this.startY = (gb.top(y) + gb.bottom(y)) / 2f;
        this.riseDistance = gb.getBlockHeight();

        paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(gb.getBlockHeight() * 0.4f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAlpha(0);
    }

    @Override
    public void draw(Canvas c) {
        float progress = animationCycle / (float) animationDuration;
        c.drawText(text, centerX, startY - riseDistance * progress, paint);
    }

    @Override
    public boolean update() {
        float progress = animationCycle / (float) animationDuration;
        int alpha = progress < 0.2f
                ? (int) (255f * (progress / 0.2f))
                : (int) (255f * (1f - (progress - 0.2f) / 0.8f));
        paint.setAlpha(Math.max(0, Math.min(255, alpha)));

        animationCycle++;
        return animationCycle >= animationDuration;
    }
}
