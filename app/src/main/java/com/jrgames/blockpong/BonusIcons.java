package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

// Shared vector-icon glyphs for each Bonus type, drawn centered at (cx, cy) with "radius" r.
// Used by both Game's bonus row (small, steady-state) and BonusAwardAnimation's pop-in
// celebration (large, animated) so both places draw the exact same glyph -- only the paints and
// size differ.
final class BonusIcons {

    private BonusIcons() {
    }

    // strokePaint/thinPaint: outlines (thinPaint is a lighter-weight stroke, used for
    // EXTENDED_PATH's taper). fillPaint: solid shapes (arrowheads, the lightning bolt, ball
    // cluster). highlightPaint: small "punched hole" accents on top of fillPaint shapes (a shine
    // dot on a ball) -- pass whatever color the icon is being drawn on top of.
    static void draw(Canvas canvas, Bonus bonus, float cx, float cy, float r,
                      Paint strokePaint, Paint thinPaint, Paint fillPaint, Paint highlightPaint) {
        switch (bonus) {
            case MOVE_STOPPER: {
                // A falling arrow halted just above a barrier bar -- the board drop stopped cold.
                float barY = cy + r * 0.55f;
                canvas.drawLine(cx - r, barY, cx + r, barY, strokePaint);
                canvas.drawLine(cx, cy - r, cx, barY - r * 0.35f, strokePaint);
                drawFilledTriangle(canvas,
                        cx, barY - r * 0.12f,
                        cx - r * 0.3f, barY - r * 0.45f,
                        cx + r * 0.3f, barY - r * 0.45f,
                        fillPaint);
                break;
            }
            case EXTENDED_PATH: {
                // Solid near reach, then a thinner line continuing up to a target ring -- the aim
                // line now sees (and stops at) a point further up than before.
                canvas.drawLine(cx, cy + r, cx, cy - r * 0.05f, strokePaint);
                canvas.drawLine(cx, cy - r * 0.05f, cx, cy - r * 0.75f, thinPaint);
                canvas.drawCircle(cx, cy - r * 0.95f, r * 0.28f, strokePaint);
                canvas.drawCircle(cx, cy - r * 0.95f, r * 0.09f, fillPaint);
                break;
            }
            case LINE_DELETE: {
                // A lightning bolt striking a row of blocks -- the whole row is zapped away.
                float rowY = cy + r * 0.72f;
                float blockSize = r * 0.42f;
                for (int i = -1; i <= 1; i++) {
                    float bx = cx + i * r * 0.75f;
                    canvas.drawRect(bx - blockSize / 2f, rowY - blockSize / 2f,
                            bx + blockSize / 2f, rowY + blockSize / 2f, strokePaint);
                }
                Path bolt = new Path();
                bolt.moveTo(cx + r * 0.12f, cy - r * 1.05f);
                bolt.lineTo(cx - r * 0.5f, cy + r * 0.1f);
                bolt.lineTo(cx - r * 0.08f, cy + r * 0.1f);
                bolt.lineTo(cx - r * 0.32f, cy + r * 0.55f);
                bolt.lineTo(cx + r * 0.5f, cy - r * 0.15f);
                bolt.lineTo(cx + r * 0.08f, cy - r * 0.15f);
                bolt.close();
                canvas.drawPath(bolt, fillPaint);
                break;
            }
            case EXTRA_BALLS: {
                // A tight triangular cluster of solid balls, each with a small shine highlight.
                float ballR = r * 0.36f;
                float[][] centers = {
                        {cx - r * 0.42f, cy + r * 0.35f},
                        {cx + r * 0.42f, cy + r * 0.35f},
                        {cx, cy - r * 0.45f},
                };
                for (float[] c : centers) {
                    canvas.drawCircle(c[0], c[1], ballR, fillPaint);
                    canvas.drawCircle(c[0] - ballR * 0.35f, c[1] - ballR * 0.35f, ballR * 0.22f, highlightPaint);
                }
                break;
            }
            case MOVE_START_POINT: {
                // A ball sitting on a slider rail, with arrows showing it can slide either way.
                canvas.drawLine(cx - r, cy, cx + r, cy, strokePaint);
                canvas.drawLine(cx - r, cy - r * 0.22f, cx - r, cy + r * 0.22f, strokePaint);
                canvas.drawLine(cx + r, cy - r * 0.22f, cx + r, cy + r * 0.22f, strokePaint);
                drawFilledTriangle(canvas,
                        cx - r * 0.62f, cy,
                        cx - r * 0.38f, cy - r * 0.22f,
                        cx - r * 0.38f, cy + r * 0.22f,
                        fillPaint);
                drawFilledTriangle(canvas,
                        cx + r * 0.62f, cy,
                        cx + r * 0.38f, cy - r * 0.22f,
                        cx + r * 0.38f, cy + r * 0.22f,
                        fillPaint);
                canvas.drawCircle(cx, cy, r * 0.3f, fillPaint);
                canvas.drawCircle(cx - r * 0.1f, cy - r * 0.1f, r * 0.09f, highlightPaint);
                break;
            }
            case DRAG_PADDLE: {
                // A thick paddle at the bottom that slides left/right (by dragging a finger),
                // with a ball bouncing straight back up off it -- distinct from MOVE_START_POINT's
                // thin rail-with-ball-on-it glyph above.
                float barY = cy + r * 0.65f;
                float barHalfWidth = r * 0.55f;
                float barHeight = r * 0.22f;
                RectF bar = new RectF(cx - barHalfWidth, barY - barHeight / 2f,
                        cx + barHalfWidth, barY + barHeight / 2f);
                canvas.drawRoundRect(bar, barHeight / 2f, barHeight / 2f, fillPaint);
                drawFilledTriangle(canvas,
                        cx - r * 0.95f, barY,
                        cx - r * 0.68f, barY - r * 0.18f,
                        cx - r * 0.68f, barY + r * 0.18f,
                        fillPaint);
                drawFilledTriangle(canvas,
                        cx + r * 0.95f, barY,
                        cx + r * 0.68f, barY - r * 0.18f,
                        cx + r * 0.68f, barY + r * 0.18f,
                        fillPaint);
                canvas.drawLine(cx, barY - barHeight / 2f - r * 0.05f, cx, cy - r * 0.55f, strokePaint);
                canvas.drawCircle(cx, cy - r * 0.75f, r * 0.22f, strokePaint);
                canvas.drawCircle(cx, cy - r * 0.75f, r * 0.08f, fillPaint);
                break;
            }
        }
    }

    private static void drawFilledTriangle(Canvas canvas, float x1, float y1, float x2, float y2,
                                            float x3, float y3, Paint paint) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        canvas.drawPath(path, paint);
    }
}
