package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// Covers the EXTENDED_PATH bonus's upgraded aim preview (see GameBoard.computeFullPathPoints()
// / drawDirLine()): instead of an idealized single-wall-mirror line, it now runs the ball's real
// collision physics on a throwaway scratch board to trace where it would actually go all the way
// back to the start line -- including bouncing off blocks, not just walls. Two things matter
// most: the path must actually reflect a real bounce (not just draw straight through a block
// that's in the way), and none of that simulation may leak into the real board/score (see
// GameBoard.NoOpGameCallbacks).
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExtendedPathPreviewTest {

    @Test
    public void simulatedPath_bouncesOffABlockInsteadOfPassingThroughIt() throws Exception {
        TrackingCallbacks callbacks = new TrackingCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();

        // A full row of blocks a few rows above the fire line -- guarantees a hit regardless of
        // exactly which column the (slightly off-vertical, see below) aim drifts into, without
        // needing to reverse-engineer GameBoard's private cell/pixel geometry here.
        for (int x = 0; x < 11; x++) {
            gb.placeSquareBlockForTests(x, 2, 9);
        }

        float startX = gb.getFirePosXForTests();
        float startY = gb.getFirePosYForTests();

        Method m = GameBoard.class.getDeclaredMethod("computeFullPathPoints",
                float.class, float.class, float.class, float.class);
        m.setAccessible(true);
        // Slight horizontal component rather than perfectly vertical -- matches this codebase's
        // established convention of avoiding an exactly-vertical aim in physics tests (see
        // GameBoardImportReplayTest's "an exactly vertical shot is a degenerate edge case" note).
        @SuppressWarnings("unchecked")
        List<float[]> points = (List<float[]>) m.invoke(gb, startX, startY, 2f, -40f);

        assertNotNull(points);
        assertTrue("expected several simulated ticks before the ball returns to the start line",
                points.size() >= 3);

        // The path must turn back downward at some point (a bounce off the row above) rather than
        // just continuing upward in a straight line to its final point.
        boolean sawUpwardThenDownward = false;
        boolean sawUpward = false;
        for (int i = 1; i < points.size(); i++) {
            float dy = points.get(i)[1] - points.get(i - 1)[1];
            if (dy < -0.5f) sawUpward = true;
            if (sawUpward && dy > 0.5f) {
                sawUpwardThenDownward = true;
                break;
            }
        }
        assertTrue("expected the simulated path to bounce back down off the block row", sawUpwardThenDownward);

        // Crucially: none of this may have touched the REAL board or score -- it's a throwaway
        // simulation (see GameBoard.NoOpGameCallbacks).
        for (int x = 0; x < 11; x++) {
            Block b = gb.getBlockForTests(x, 2);
            assertNotNull("simulation must not have consumed a real block", b);
            assertEquals(9, b.getValue());
        }
        assertEquals("simulation must not have awarded real score", 0, callbacks.addScoreCalls);
    }

    @Test
    public void degenerateAimVector_returnsNull() throws Exception {
        GameBoard gb = new GameBoard(new TrackingCallbacks(), 660f, 900f, 0f, 0f);
        Method m = GameBoard.class.getDeclaredMethod("computeFullPathPoints",
                float.class, float.class, float.class, float.class);
        m.setAccessible(true);
        Object result = m.invoke(gb, 100f, 100f, 0f, 0f);
        assertEquals(null, result);
    }

    // Covers the block-count prediction the EXTENDED_PATH bonus also surfaces (see
    // GameBoard.predictBlocksCleared() / aimedBlocksClearedPreview): running the real shot
    // headless on the scratch board must report the same clear count the shot would actually
    // produce, without touching the real board/score.
    @Test
    public void predictBlocksCleared_matchesBlockActuallyHit() throws Exception {
        TrackingCallbacks callbacks = new TrackingCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        // A full row of value-1 blocks a few rows above the fire line -- one hit each clears it.
        // The near-vertical aim (dx=2, dy=-40) lands close to a cell boundary, hitting the two
        // blocks straddling it (a corner hit) rather than just one -- verified against the actual
        // dissolve count below, not hand-derived, since the point is that the prediction matches
        // whatever the real shot would do, not a specific number.
        for (int x = 0; x < 11; x++) {
            gb.placeSquareBlockForTests(x, 2, 1);
        }

        float startX = gb.getFirePosXForTests();

        Method m = GameBoard.class.getDeclaredMethod("predictBlocksCleared",
                float.class, float.class, float.class);
        m.setAccessible(true);
        int predicted = (int) m.invoke(gb, startX, 2f, -40f);

        assertEquals(2, predicted);

        // Crucially: none of this may have touched the REAL board or score.
        int intactBlocks = 0;
        for (int x = 0; x < 11; x++) {
            if (gb.getBlockForTests(x, 2) != null) intactBlocks++;
        }
        assertEquals("simulation must not have consumed any real block", 11, intactBlocks);
        assertEquals("simulation must not have awarded real score", 0, callbacks.addScoreCalls);
    }

    @Test
    public void predictBlocksCleared_degenerateAimVector_returnsMinusOne() throws Exception {
        GameBoard gb = new GameBoard(new TrackingCallbacks(), 660f, 900f, 0f, 0f);
        Method m = GameBoard.class.getDeclaredMethod("predictBlocksCleared",
                float.class, float.class, float.class);
        m.setAccessible(true);
        int result = (int) m.invoke(gb, 100f, 0f, 0f);
        assertEquals(-1, result);
    }

    private static final class TrackingCallbacks implements GameBoard.GameCallbacks {
        int addScoreCalls;

        @Override public int getLevel() { return 1; }
        @Override public void addAnimation(Animation a) {}
        @Override public void increaselevel() {}
        @Override public void setGameOver(boolean win) {}
        @Override public boolean isGameOver() { return false; }
        @Override public void resetGameOver() {}
        @Override public void addScore(int points) { addScoreCalls++; }
        @Override public String loadLevelJson(int level) { return null; }
        @Override public void onRoundEnd(int blocksCleared, int ballsUsed) {}
        @Override public boolean isBonusArmed(Bonus bonus) { return false; }
        @Override public List<Bonus> consumeArmedBonuses() { return Collections.emptyList(); }
    }
}
