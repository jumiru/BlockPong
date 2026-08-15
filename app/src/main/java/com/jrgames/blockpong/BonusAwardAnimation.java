package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

// Celebratory pop-in shown when a bonus is awarded (see Game.onRoundEnd()): the bonus's icon
// zooms out from the center of the board -- growing with a slight bounce/overshoot, holding, then
// shrinking back away -- to a badge covering roughly 30% of the board's shorter side, similar to
// the tile-award animations in games like 2048NG. Purely decorative; doesn't touch game state.
public class BonusAwardAnimation extends Animation {

    // Fractions of animationDuration spent in each phase; the remainder shrinks/fades out.
    private static final float GROW_FRACTION = 0.35f;
    private static final float HOLD_FRACTION = 0.35f;
    // Badge diameter as a fraction of the board's shorter side ("filling ~30% of the board").
    private static final float TARGET_DIAMETER_FRACTION = 0.3f;

    private final Bonus bonus;
    private final String label;
    // Ticks to wait, fully undrawn, before this badge's own grow/hold/shrink cycle (of length
    // `visibleDuration`) begins -- lets several bonuses earned by the same shot cascade in one
    // after another instead of animating as perfectly coincident circles (see Game.onRoundEnd()).
    private final int startDelay;
    private final int visibleDuration;

    private final Paint backgroundPaint;
    private final Paint borderPaint;
    private final Paint iconStrokePaint;
    private final Paint iconThinPaint;
    private final Paint iconFillPaint;
    private final Paint iconHighlightPaint;
    private final Paint labelPaint;

    public BonusAwardAnimation(GameBoard gb, int dur, Bonus bonus, int accentColor, String label) {
        this(gb, dur, bonus, accentColor, label, 0);
    }

    public BonusAwardAnimation(GameBoard gb, int dur, Bonus bonus, int accentColor, String label, int startDelay) {
        super(gb, dur + startDelay);
        this.visibleDuration = dur;
        this.startDelay = startDelay;
        this.bonus = bonus;
        this.label = label;

        backgroundPaint = new Paint();
        backgroundPaint.setColor(accentColor);
        backgroundPaint.setAntiAlias(true);

        borderPaint = new Paint();
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(6);
        borderPaint.setAntiAlias(true);

        iconStrokePaint = new Paint();
        iconStrokePaint.setColor(Color.WHITE);
        iconStrokePaint.setStyle(Paint.Style.STROKE);
        iconStrokePaint.setStrokeWidth(14);
        iconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        iconStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        iconStrokePaint.setAntiAlias(true);

        iconThinPaint = new Paint(iconStrokePaint);
        iconThinPaint.setStrokeWidth(iconStrokePaint.getStrokeWidth() * 0.4f);

        iconFillPaint = new Paint();
        iconFillPaint.setColor(Color.WHITE);
        iconFillPaint.setStyle(Paint.Style.FILL);
        iconFillPaint.setAntiAlias(true);

        // Same trick as the bonus row's icons: "punch" a highlight dot using the badge's own
        // background color, so it reads as a cutout/shine rather than a mismatched extra shape.
        iconHighlightPaint = new Paint();
        iconHighlightPaint.setColor(accentColor);
        iconHighlightPaint.setAntiAlias(true);

        labelPaint = new Paint();
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(38);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        labelPaint.setAntiAlias(true);
    }

    @Override
    public void draw(Canvas c) {
        int cycle = animationCycle - startDelay;
        if (cycle < 0) return; // still waiting for an earlier badge in the cascade to clear

        float t = visibleDuration <= 0 ? 1f : Math.min(1f, (float) cycle / visibleDuration);

        float scale;
        int alpha;
        if (t < GROW_FRACTION) {
            scale = overshoot(t / GROW_FRACTION);
            alpha = 255;
        } else if (t < GROW_FRACTION + HOLD_FRACTION) {
            scale = 1f;
            alpha = 255;
        } else {
            float p = (t - GROW_FRACTION - HOLD_FRACTION) / (1f - GROW_FRACTION - HOLD_FRACTION);
            scale = 1f - p * 0.35f;
            alpha = (int) (255 * (1f - p));
        }

        float maxRadius = TARGET_DIAMETER_FRACTION * 0.5f * Math.min(gb.getWidth(), gb.getHeight());
        float radius = maxRadius * Math.max(0f, scale);
        if (radius <= 1f || alpha <= 0) return;

        float cx = gb.getXOffset() + gb.getWidth() / 2f;
        float cy = gb.getYOffset() + gb.getHeight() / 2f;

        backgroundPaint.setAlpha(alpha);
        borderPaint.setAlpha(alpha);
        iconStrokePaint.setAlpha(alpha);
        iconThinPaint.setAlpha(alpha);
        iconFillPaint.setAlpha(alpha);
        iconHighlightPaint.setAlpha(alpha);
        labelPaint.setAlpha(alpha);

        c.drawCircle(cx, cy, radius, backgroundPaint);
        c.drawCircle(cx, cy, radius, borderPaint);

        BonusIcons.draw(c, bonus, cx, cy - radius * 0.15f, radius * 0.45f,
                iconStrokePaint, iconThinPaint, iconFillPaint, iconHighlightPaint);

        c.drawText(label, cx, cy + radius * 0.72f, labelPaint);
    }

    @Override
    public boolean update() {
        animationCycle++;
        return animationCycle >= animationDuration;
    }

    // Ease-out-back: overshoots slightly past 1.0 before settling back, for a bouncy pop-in
    // (standard easing formula, e.g. https://easings.net/#easeOutBack).
    private static float overshoot(float p) {
        float c1 = 1.4f;
        float c3 = c1 + 1f;
        float x = p - 1f;
        return 1f + c3 * x * x * x + c1 * x * x;
    }
}
