package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// Covers the burger menu's "Import & Replay (Debug)" action: GameBoard.importAndReplayForDebug()
// should load the given board layout, place the ball(s) at the given start x, and immediately
// fire with the exact given launch vector -- so a reported bug's exact board+shot can be
// reproduced live (real physics) to verify a fix.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GameBoardImportReplayTest {

    @Test
    public void loadsBoardStartPositionAndFiresExactVector() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(5, 5, 9);

        // (30, -40) has magnitude exactly 50 -- GameBoard's normSpeed -- so setFireSpeed() (which
        // normalizes any input vector onto that magnitude) passes it through unchanged, letting
        // this assert an exact match instead of an approximation.
        String json = "{\"blocks\":[{\"x\":2,\"y\":3,\"value\":6,\"type\":\"tl\"}]}";
        gb.importAndReplayForDebug(json, 123.5f, 30f, -40f, 7);

        assertEquals(123.5f, gb.getFirePosXForTests(), 0.001f);
        assertEquals(30f, gb.getFireSpeedXForTests(), 0.001f);
        assertEquals(-40f, gb.getFireSpeedYForTests(), 0.001f);

        // the old block at (5,5) should be gone, replaced entirely by the imported layout
        String exported = gb.exportBlocksJson();
        assertTrue(exported.contains("\"x\":2"));
        assertTrue(exported.contains("\"y\":3"));
        assertTrue(exported.contains("\"value\":6"));
        assertTrue("old board should be fully replaced", !exported.contains("\"value\":9"));

        // Confirms fire() actually kicked off a real shot (ball count, staggered launch, etc. --
        // full completion of an arbitrary synthetic trajectory isn't the point of this test; that
        // machinery is already covered by LastMoveExportTest and friends).
        for (int i = 0; i < 10; i++) gb.update();
        assertTrue("importing should have started a shot that's now rolling", gb.ballRolling());
    }

    @Test
    public void strayTouchRightAfterImportDoesNotAlterTheImportedLaunchVector() {
        // Reported requirement: for an exact repro, the replayed shot must keep the imported
        // angle no matter where the user then taps to trigger/watch it (e.g. the "Abspielen"
        // button's on-screen position, or a tap that lands on the board once the dialog closes)
        // -- the angle must come from the debug export, never from touch coordinates.
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();

        gb.importAndReplayForDebug("{\"blocks\":[]}", 200f, 30f, -40f, 3);
        float expectedSpeedX = gb.getFireSpeedXForTests();
        float expectedSpeedY = gb.getFireSpeedYForTests();

        // Touch down/up at an arbitrary, unrelated point on the board -- simulates a stray event
        // landing on the SurfaceView right after the shot was triggered.
        gb.touchDown(500f, 800f);
        gb.touchRelease(50f, 100f);

        assertEquals("a stray touchDown/touchRelease must not change the imported launch vector",
                expectedSpeedX, gb.getFireSpeedXForTests(), 0.001f);
        assertEquals("a stray touchDown/touchRelease must not change the imported launch vector",
                expectedSpeedY, gb.getFireSpeedYForTests(), 0.001f);

        // The balls actually launched must also carry the original vector once dispatched.
        for (int i = 0; i < 10; i++) gb.update();
        assertEquals(expectedSpeedX, gb.getBallForTests(0).getDx(), 0.001f);
        assertEquals(expectedSpeedY, gb.getBallForTests(0).getDy(), 0.001f);
    }

    @Test
    public void completedMoveCountAdvancesOnceTheImportedShotFinishes() {
        // Covers Game.java's "Abspielen in Zeitlupe" auto-replay trigger, which detects "my shot
        // finished" by comparing this counter's value before/after firing rather than just
        // hasLastMoveRecording() (which could already be true from an earlier, unrelated move).
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();

        int before = gb.getCompletedMoveCount();
        // Slight horizontal component (matches every real debug export seen so far, none of
        // which had an exactly-zero dx) -- an exactly vertical shot is a degenerate edge case
        // that isn't this test's concern.
        gb.importAndReplayForDebug("{\"blocks\":[]}", gb.getFirePosXForTests(), 10f, -48.9898f, 1);

        int steps = 0;
        while (gb.getCompletedMoveCount() == before && steps < 2000) {
            gb.update();
            steps++;
        }
        assertEquals(before + 1, gb.getCompletedMoveCount());
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
        @Override public int getLevel() { return 1; }
        @Override public void addAnimation(Animation a) {}
        @Override public void increaselevel() {}
        @Override public void setGameOver(boolean win) {}
        @Override public boolean isGameOver() { return false; }
        @Override public void resetGameOver() {}
        @Override public void addScore(int points) {}
        @Override public String loadLevelJson(int level) { return null; }
        @Override public void onRoundEnd(int blocksCleared, int ballsUsed) {}
        @Override public boolean isBonusArmed(Bonus bonus) { return false; }
        @Override public java.util.List<Bonus> consumeArmedBonuses() { return java.util.Collections.emptyList(); }
    }
}
