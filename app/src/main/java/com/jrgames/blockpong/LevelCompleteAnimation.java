package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

// Level-complete transition: two curtain panels sweep in from the left/right edges of the board
// and meet in the middle, hiding the board while the next level is set up underneath, then sweep
// back out to reveal it. Three phases, run back to back:
//  1. CLOSING  -- panels swing shut using a damped-spring easing curve, so they overshoot slightly
//                 past center before settling (see springEase()) -- "Ueberschwingen beim Zuziehen".
//  2. SETTLING -- panels hold fully closed (this is when the actual level swap happens, hidden
//                 behind the curtain) while the fabric ripple along their leading edge keeps
//                 decaying -- "Nachschwingen bevor er wieder aufgezogen wird": the curtain has
//                 stopped moving but the cloth is still gently rippling itself calm.
//  3. OPENING  -- panels retract back out with a plain ease-out (no overshoot), revealing the new
//                 board.
public class LevelCompleteAnimation extends Animation {

    private enum Phase { CLOSING, SETTLING, OPENING }

    private static final int CLOSE_DURATION = 80;
    private static final int SETTLE_DURATION = 50;
    private static final int OPEN_DURATION = 70;

    // Damped-spring constants for the closing swing (see springEase()) -- tuned by feel for a
    // visible but not cartoonish overshoot (roughly one big bounce past center, a couple of small
    // ones, then rest).
    private static final float SPRING_DAMPING = 5.5f;
    private static final float SPRING_FREQUENCY = 11f;

    // Cloth-ripple wave along each panel's leading edge (see wavyEdgeX()). Amplitude decays over
    // the closing swing and again, independently, over the whole settle hold -- that second decay
    // is the "after-swing" the fabric does once the panels themselves have stopped moving.
    private static final float WAVE_AMPLITUDE = 26f;
    private static final float WAVE_FREQUENCY = 0.018f;
    private static final float WAVE_SPEED = 0.22f;
    private static final int WAVE_SAMPLES = 18;

    private final Paint fillPaint;
    private final Paint foldPaintLight;
    private final Paint foldPaintDark;

    private Phase phase = Phase.CLOSING;
    private int phaseCycle;
    private boolean boardSwapped;

    // Snapshot of the wave's decay envelope at the moment CLOSING ends, so SETTLING continues
    // decaying from wherever the swing actually left it instead of jumping.
    private float settleStartEnvelope;

    public LevelCompleteAnimation(GameBoard gb) {
        super(gb, CLOSE_DURATION + SETTLE_DURATION + OPEN_DURATION);

        fillPaint = new Paint();
        fillPaint.setColor(Color.rgb(140, 20, 26));
        fillPaint.setAntiAlias(true);

        foldPaintLight = new Paint();
        foldPaintLight.setColor(Color.rgb(190, 40, 46));
        foldPaintLight.setAntiAlias(true);

        foldPaintDark = new Paint();
        foldPaintDark.setColor(Color.rgb(95, 10, 14));
        foldPaintDark.setAntiAlias(true);
    }

    @Override
    public boolean update() {
        animationCycle++;
        phaseCycle++;

        switch (phase) {
            case CLOSING:
                if (phaseCycle >= CLOSE_DURATION) {
                    settleStartEnvelope = (float) Math.exp(-SPRING_DAMPING * 1f);
                    if (!boardSwapped) {
                        // Fully closed -- swap to the new level while it's completely hidden.
                        gb.game.increaselevel();
                        gb.initBoard();
                        boardSwapped = true;
                    }
                    phase = Phase.SETTLING;
                    phaseCycle = 0;
                }
                break;
            case SETTLING:
                if (phaseCycle >= SETTLE_DURATION) {
                    phase = Phase.OPENING;
                    phaseCycle = 0;
                }
                break;
            case OPENING:
                if (phaseCycle >= OPEN_DURATION) {
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public void draw(Canvas c) {
        float left = gb.getXOffset();
        float top = gb.getYOffset();
        float width = gb.getWidth();
        float height = gb.getHeight();
        float centerX = left + width / 2f;

        float closeProgress = closeEase();
        float waveEnvelope = waveEnvelope();

        // Distance each panel's leading edge has swung past the outer wall, capped so an overshoot
        // spike can't push it past the far wall entirely.
        float reach = Math.min(width, (width / 2f) * closeProgress);
        float leftEdgeBase = left + reach;
        float rightEdgeBase = left + width - reach;

        drawPanel(c, left, top, leftEdgeBase, height, true, waveEnvelope);
        drawPanel(c, left + width, top, rightEdgeBase, height, false, waveEnvelope);
    }

    // Combined progress (0..1) of how closed the panels are: 1 for the whole SETTLING phase and
    // onward into OPENING's start, springing shut during CLOSING, easing back open during OPENING.
    private float closeEase() {
        switch (phase) {
            case CLOSING:
                return springEase((float) phaseCycle / CLOSE_DURATION);
            case SETTLING:
                return 1f;
            case OPENING:
            default:
                float t = (float) phaseCycle / OPEN_DURATION;
                return 1f - easeOutCubic(t);
        }
    }

    // Underdamped step response: 0 at t=0, overshoots past 1 partway through, decays into 1.
    private float springEase(float t) {
        return 1f - (float) (Math.exp(-SPRING_DAMPING * t) * Math.cos(SPRING_FREQUENCY * t));
    }

    private float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    // How strongly the leading edge ripples right now -- decays across the closing swing (fabric
    // whipping around while the panel itself is still moving fast), then keeps decaying
    // independently across the whole settle hold (the "after-swing"), and is essentially still
    // during OPENING so the reveal itself reads as calm.
    private float waveEnvelope() {
        switch (phase) {
            case CLOSING:
                return (float) Math.exp(-SPRING_DAMPING * (float) phaseCycle / CLOSE_DURATION);
            case SETTLING:
                return settleStartEnvelope * (1f - (float) phaseCycle / SETTLE_DURATION);
            case OPENING:
            default:
                return 0f;
        }
    }

    private void drawPanel(Canvas c, float outerX, float top, float edgeBaseX, float height,
                            boolean isLeftPanel, float waveEnvelope) {
        if (Math.abs(edgeBaseX - outerX) < 1f) return; // fully retracted -- nothing to draw

        Path path = new Path();
        path.moveTo(outerX, top);
        path.lineTo(outerX, top + height);
        for (int i = WAVE_SAMPLES; i >= 0; i--) {
            float y = top + height * i / (float) WAVE_SAMPLES;
            path.lineTo(wavyEdgeX(edgeBaseX, y, waveEnvelope, isLeftPanel), y);
        }
        path.close();

        c.drawPath(path, fillPaint);
        drawFolds(c, path, outerX, edgeBaseX, top, height, isLeftPanel);
    }

    // A traveling ripple superimposed on the panel's leading edge, biggest while waveEnvelope is
    // high (right after a fast swing) and fading to a flat line as it decays -- makes the edge look
    // like loose fabric rather than a rigid slab.
    private float wavyEdgeX(float edgeBaseX, float y, float waveEnvelope, boolean isLeftPanel) {
        float wave = WAVE_AMPLITUDE * waveEnvelope
                * (float) Math.sin(y * WAVE_FREQUENCY + animationCycle * WAVE_SPEED);
        // Ripple pushes the edge further into the curtain's own fabric (i.e. toward its outer
        // wall) rather than out into open board space, so it never looks like it exposes a gap.
        return isLeftPanel ? edgeBaseX - Math.abs(wave) : edgeBaseX + Math.abs(wave);
    }

    // Vertical pleat shading across the panel's fill so it reads as cloth rather than a flat color
    // block -- alternating light/dark bands clipped to the panel's already-wavy silhouette.
    private void drawFolds(Canvas c, Path panelPath, float outerX, float edgeBaseX, float top, float height,
                            boolean isLeftPanel) {
        int saveCount = c.save();
        c.clipPath(panelPath);

        float panelWidth = Math.abs(edgeBaseX - outerX);
        int foldCount = Math.max(1, Math.round(panelWidth / 55f));
        float foldWidth = panelWidth / foldCount;
        for (int i = 0; i < foldCount; i++) {
            float bandLeft = isLeftPanel ? outerX + i * foldWidth : outerX - (i + 1) * foldWidth;
            Paint p = (i % 2 == 0) ? foldPaintDark : foldPaintLight;
            c.drawRect(bandLeft, top, bandLeft + foldWidth, top + height, p);
        }
        c.restoreToCount(saveCount);
    }
}
