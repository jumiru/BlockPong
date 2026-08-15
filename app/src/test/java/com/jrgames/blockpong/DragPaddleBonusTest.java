package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Covers the DRAG_PADDLE bonus: a paddle appears at the fire line for the rest of the shot it's
// spent on, bounces balls back upward instead of letting them settle, follows a finger drag
// directly (an earlier accelerometer-tilt version felt too sluggish to control), and shrinks by
// one step per reflection -- gone after 10 hits (or at the end of the move, whichever comes
// first) -- so it can never trap a ball bouncing forever. All tests pin numBalls down to 1
// (setNumBallsForTests()): a real shot defaults to 10 balls, and with the default board+release
// used here every one of them would eventually reach the paddle too, contributing untracked hits.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DragPaddleBonusTest {

    @Test
    public void withoutDragPaddle_ballSettlesWithinAFewTicks() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(null), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);

        for (int i = 0; i < 100; i++) gb.update();

        assertTrue("baseline: with no paddle, a straight up-and-back shot on an empty board "
                + "should have settled well within 100 ticks",
                gb.getBallForTests(0).isStill());
    }

    @Test
    public void withDragPaddle_ballKeepsBouncingRightAfterFiring() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.DRAG_PADDLE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        // Straight up: the ball's x never drifts, so it's guaranteed to land back inside the
        // paddle's x-range (centered on the same start x) once it falls back down.
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);

        assertTrue("DRAG_PADDLE should have armed the paddle", gb.isPaddleActiveForTests());

        // Just enough ticks for one round trip and a bounce off the paddle -- not the whole
        // 10-hit budget -- so this checks "the paddle caught it" rather than "it's already gone".
        for (int i = 0; i < 20; i++) gb.update();

        assertTrue("paddle should still be up after just one bounce", gb.isPaddleActiveForTests());
        assertFalse("ball caught by the paddle should still be bouncing, not settled",
                gb.getBallForTests(0).isStill());
    }

    @Test
    public void paddleTakesExactlyTenHitsBeforeDisappearing() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.DRAG_PADDLE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);

        int hits = 0;
        for (int i = 0; i < 5000 && gb.isPaddleActiveForTests(); i++) {
            float dyBefore = gb.getBallForTests(0).getDy();
            gb.update();
            float dyAfter = gb.getBallForTests(0).getDy();
            // A paddle reflection flips a falling ball (dy>0) to a rising one (dy<0); the
            // opposite transition (rising -> falling) is just the top-wall bounce, not this.
            if (dyBefore > 0 && dyAfter < 0) hits++;
        }

        assertEquals("paddle should disappear after exactly its 10-hit budget", 10, hits);
        assertFalse(gb.isPaddleActiveForTests());
    }

    @Test
    public void paddleGoneAfterTenHits_ballSettlesNormallyAfterwards() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.DRAG_PADDLE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);

        for (int i = 0; i < 5000; i++) gb.update();

        assertFalse("paddle should have used up its hits by now", gb.isPaddleActiveForTests());
        assertTrue("once the paddle is gone the ball must eventually settle at the fire line "
                + "like normal -- it can never be trapped bouncing forever",
                gb.getBallForTests(0).isStill());
    }

    @Test
    public void paddleIsClearedAtTheEndOfTheMoveEvenIfNotFullyUsed() {
        // A shot aimed sideways past the board's blocks never returns to the paddle's x-range, so
        // the paddle should still have its full 10 hits left once the ball finally settles -- but
        // must be gone anyway, since the move that armed it has ended.
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.DRAG_PADDLE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + 5f, firePosY - 400f);

        int steps = 0;
        while (!gb.hasLastMoveRecording() && steps < 5000) {
            gb.update();
            steps++;
        }
        assertTrue("shot should have completed", gb.hasLastMoveRecording());
        assertFalse("paddle must not linger past the end of the move it was spent on",
                gb.isPaddleActiveForTests());
    }

    @Test
    public void dragMovesThePaddleToFollowTheFingerAndClampsAtTheEdges() {
        float boardWidth = 660f;
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.DRAG_PADDLE), boardWidth, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);
        assertTrue(gb.isPaddleActiveForTests());

        float halfWidth = gb.getPaddleWidthForTests() / 2f;

        // A fresh touchDown mid-shot (balls are rolling) should move the paddle immediately to
        // the touch's x -- not start aiming or the unrelated swipe-down-to-abort gesture.
        gb.touchDown(300f, 500f);
        assertEquals(300f, gb.getPaddlePosXForTests(), 0.01f);

        // touchMove keeps following.
        gb.touchMove(200f, 600f);
        assertEquals(200f, gb.getPaddlePosXForTests(), 0.01f);

        // Dragging past either edge clamps instead of letting the paddle leave the board.
        gb.touchMove(-50f, 600f);
        assertEquals(halfWidth, gb.getPaddlePosXForTests(), 0.01f);
        gb.touchMove(boardWidth + 50f, 600f);
        assertEquals(boardWidth - halfWidth, gb.getPaddlePosXForTests(), 0.01f);
    }

    @Test
    public void draggingThePaddleDoesNotTriggerAimingOrFiring() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.DRAG_PADDLE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);
        assertTrue(gb.isPaddleActiveForTests());

        float speedXBefore = gb.getFireSpeedXForTests();
        float speedYBefore = gb.getFireSpeedYForTests();
        int dispatchedBefore = gb.getNextFireBallForTests();

        gb.touchDown(300f, 500f);
        gb.touchMove(250f, 400f);
        gb.touchRelease(250f, 400f);

        assertEquals("dragging the paddle must not change the in-flight shot's launch vector",
                speedXBefore, gb.getFireSpeedXForTests(), 0.01f);
        assertEquals(speedYBefore, gb.getFireSpeedYForTests(), 0.01f);
        assertEquals("dragging the paddle must not dispatch any (additional) balls",
                dispatchedBefore, gb.getNextFireBallForTests());
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
        private final Bonus bonusToConsume;
        private boolean consumed;

        TestGameCallbacks(Bonus bonusToConsume) {
            this.bonusToConsume = bonusToConsume;
        }

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

        @Override
        public List<Bonus> consumeArmedBonuses() {
            if (consumed || bonusToConsume == null) return Collections.emptyList();
            consumed = true;
            return Collections.singletonList(bonusToConsume);
        }
    }
}
