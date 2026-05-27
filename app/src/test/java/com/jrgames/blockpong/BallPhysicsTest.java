package com.jrgames.blockpong;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BallPhysicsTest {

    private static final float EPS = 1e-4f;

    @Test
    public void mirrorVerticallyIntern_flipsDxAndKeepsDy() {
        Ball ball = new Ball(10f, 100f, 50f, 1);
        ball.setSpeed(10f, 6f);

        boolean mirrored = ball.mirrorVerticallyIntern(100f, 50f, 110f, Float.POSITIVE_INFINITY);

        assertTrue(mirrored);
        assertEquals(110f, ball.getX(), EPS);
        assertEquals(50f, ball.getY(), EPS);
        assertEquals(-10f, ball.getDx(), EPS);
        assertEquals(6f, ball.getDy(), EPS);
        assertEquals(speedMagnitude(10f, 6f), speedMagnitude(ball.getDx(), ball.getDy()), EPS);
    }

    @Test
    public void mirrorHorizontallyIntern_flipsDyAndKeepsDx() {
        Ball ball = new Ball(10f, 100f, 100f, 2);
        ball.setSpeed(6f, -10f);

        boolean mirrored = ball.mirrorHorizontallyIntern(100f, 100f, 90f, Float.POSITIVE_INFINITY);

        assertTrue(mirrored);
        assertEquals(100f, ball.getX(), EPS);
        assertEquals(90f, ball.getY(), EPS);
        assertEquals(6f, ball.getDx(), EPS);
        assertEquals(10f, ball.getDy(), EPS);
        assertEquals(speedMagnitude(6f, -10f), speedMagnitude(ball.getDx(), ball.getDy()), EPS);
    }

    @Test
    public void mirrorVerticallyIntern_defersWhenHorizontalHitIsCloser() {
        Ball ball = new Ball(10f, 100f, 100f, 3);
        ball.setSpeed(10f, 20f);

        boolean mirrored = ball.mirrorVerticallyIntern(100f, 100f, 130f, 110f);

        assertFalse(mirrored);
        assertEquals(100f, ball.getX(), EPS);
        assertEquals(100f, ball.getY(), EPS);
        assertEquals(10f, ball.getDx(), EPS);
        assertEquals(20f, ball.getDy(), EPS);
    }

    @Test
    public void mirrorHorizontallyIntern_defersWhenVerticalHitIsCloser() {
        Ball ball = new Ball(10f, 100f, 100f, 4);
        ball.setSpeed(20f, -10f);

        boolean mirrored = ball.mirrorHorizontallyIntern(100f, 100f, 90f, 110f);

        assertFalse(mirrored);
        assertEquals(100f, ball.getX(), EPS);
        assertEquals(100f, ball.getY(), EPS);
        assertEquals(20f, ball.getDx(), EPS);
        assertEquals(-10f, ball.getDy(), EPS);
    }

    @Test
    public void resetMirrorings_clearsScheduledFlags() {
        Ball ball = new Ball(10f, 0f, 0f, 5);

        assertFalse(ball.scheduledMirroring());
        ball.mirrorVertically(10f, 20f, 30f, Float.POSITIVE_INFINITY);
        ball.mirrorHorizontally(10f, 20f, 30f, Float.POSITIVE_INFINITY);
        assertTrue(ball.scheduledMirroring());

        ball.resetMirrorings();
        assertFalse(ball.scheduledMirroring());
    }

    private static float speedMagnitude(float dx, float dy) {
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}

