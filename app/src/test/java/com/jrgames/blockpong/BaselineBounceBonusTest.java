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

// Covers the BASELINE_BOUNCE bonus: for the rest of the shot it's spent on, any ball reaching the
// start line bounces back upward instead of settling there, budgeted at one reflection per ball in
// the shot (numBalls), shared across however many balls actually fire -- see GameBoard.fire()'s
// baselineBounceReflectionsRemaining field comment. Replaces an earlier "drag a shrinking paddle
// with your finger" version that testers found too fiddly to aim in time. All tests pin numBalls
// down via setNumBallsForTests() so the reflection budget (and the exact hit count) stays
// predictable regardless of the real default of 10.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BaselineBounceBonusTest {

    @Test
    public void withoutBaselineBounce_ballSettlesWithinAFewTicks() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(null), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);

        for (int i = 0; i < 100; i++) gb.update();

        assertTrue("baseline: with the bonus unarmed, a straight up-and-back shot on an empty "
                + "board should have settled well within 100 ticks",
                gb.getBallForTests(0).isStill());
    }

    @Test
    public void withBaselineBounce_ballKeepsBouncingRightAfterFiring() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.BASELINE_BOUNCE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);

        assertEquals("BASELINE_BOUNCE should have armed a budget of one reflection per ball (1)",
                1, gb.getBaselineBounceReflectionsRemainingForTests());

        // Just enough ticks for one round trip and a bounce off the start line -- not the whole
        // budget -- so this checks "the bounce caught it" rather than "it's already gone".
        for (int i = 0; i < 20; i++) gb.update();

        assertFalse("ball caught by the baseline bounce should still be flying, not settled",
                gb.getBallForTests(0).isStill());
    }

    @Test
    public void reflectionBudgetMatchesNumBalls_andIsSpentExactlyOnce() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.BASELINE_BOUNCE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(3);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);

        assertEquals("budget should equal the number of balls fired this shot",
                3, gb.getBaselineBounceReflectionsRemainingForTests());

        // The 3 balls of this shot launch staggered (see fire()'s dispatch cadence), so each one
        // reaches the start line at a different time -- track every ball's dy transitions, not
        // just one, or a hit "used up" by ball 1 or 2 would go uncounted.
        int hits = 0;
        for (int i = 0; i < 5000 && gb.getBaselineBounceReflectionsRemainingForTests() > 0; i++) {
            float[] dyBefore = {gb.getBallForTests(0).getDy(), gb.getBallForTests(1).getDy(), gb.getBallForTests(2).getDy()};
            gb.update();
            for (int b = 0; b < 3; b++) {
                float dyAfter = gb.getBallForTests(b).getDy();
                // A baseline reflection flips a falling ball (dy>0) to a rising one (dy<0); the
                // opposite transition (rising -> falling) is just the top-wall bounce, not this.
                if (dyBefore[b] > 0 && dyAfter < 0) hits++;
            }
        }

        assertEquals("budget should be spent by exactly as many reflections as it started with",
                3, hits);
        assertEquals(0, gb.getBaselineBounceReflectionsRemainingForTests());
    }

    @Test
    public void budgetExhausted_ballSettlesNormallyAfterwards() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.BASELINE_BOUNCE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);

        for (int i = 0; i < 5000; i++) gb.update();

        assertEquals("budget should have been used up by now",
                0, gb.getBaselineBounceReflectionsRemainingForTests());
        assertTrue("once the budget is spent the ball must eventually settle at the fire line "
                + "like normal -- it can never be trapped bouncing forever",
                gb.getBallForTests(0).isStill());
    }

    @Test
    public void budgetIsClearedAtTheEndOfTheMoveEvenIfNotFullyUsed() {
        // A shot aimed sideways past the board's blocks never returns to the fire line at all
        // within a reasonable number of ticks in this test's setup, so the budget should still be
        // untouched once the move ends -- but must be cleared anyway, since the move that armed it
        // has ended.
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.BASELINE_BOUNCE), 660f, 900f, 0f, 0f);
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
        assertEquals("budget must not linger past the end of the move it was spent on",
                0, gb.getBaselineBounceReflectionsRemainingForTests());
    }

    // GameBoard.armBaselineBounceMidShot(): lets the player spend BASELINE_BOUNCE once the current
    // shot's balls are already rolling instead of only before firing (see
    // Game.toggleArmedBonus()'s BASELINE_BOUNCE branch) -- same idea as armMoveStopperMidShot().

    @Test
    public void armBaselineBounceMidShot_grantsFreshBudgetMatchingNumBalls() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(null), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(4);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f); // fires without BASELINE_BOUNCE armed

        assertEquals("no bonus armed pre-shot -- budget starts at 0",
                0, gb.getBaselineBounceReflectionsRemainingForTests());

        assertTrue("arming mid-shot should succeed the first time", gb.armBaselineBounceMidShot());
        assertEquals("mid-shot budget should equal the number of balls actually fired this shot",
                4, gb.getBaselineBounceReflectionsRemainingForTests());
    }

    @Test
    public void armBaselineBounceMidShot_stacksOnTopOfPreFireBudget() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.BASELINE_BOUNCE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(3);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f); // arms + fires with BASELINE_BOUNCE already

        assertEquals(3, gb.getBaselineBounceReflectionsRemainingForTests());
        assertTrue(gb.armBaselineBounceMidShot());
        assertEquals("each mid-shot tap adds another numBalls reflections",
                6, gb.getBaselineBounceReflectionsRemainingForTests());
        assertTrue(gb.armBaselineBounceMidShot());
        assertEquals(9, gb.getBaselineBounceReflectionsRemainingForTests());
    }

    @Test
    public void armBaselineBounceMidShot_rechargesAfterBudgetRunsOut() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Bonus.BASELINE_BOUNCE), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);

        for (int i = 0; i < 5000 && gb.getBaselineBounceReflectionsRemainingForTests() > 0; i++) gb.update();
        assertEquals("budget should have been exhausted by now",
                0, gb.getBaselineBounceReflectionsRemainingForTests());

        assertTrue(gb.armBaselineBounceMidShot());
        assertEquals(1, gb.getBaselineBounceReflectionsRemainingForTests());
    }

    // MOVE_STOPPER still pending when the level gets cleared: nothing left to skip, so it must not
    // carry over into the next level -- the charge goes back to the player instead.
    @Test
    public void pendingMoveStopper_isRefundedWhenLevelIsCleared() {
        TestGameCallbacks cb = new TestGameCallbacks(Bonus.MOVE_STOPPER);
        GameBoard gb = new GameBoard(cb, 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.setNumBallsForTests(1);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX, firePosY - 400f);
        assertTrue(gb.isMoveStopperPending());

        for (int i = 0; i < 5000 && !gb.hasLastMoveRecording(); i++) gb.update();
        assertTrue("shot should have completed", gb.hasLastMoveRecording());
        assertFalse("must not carry over into the next level", gb.isMoveStopperPending());
        assertEquals(Collections.singletonList(Bonus.MOVE_STOPPER), cb.refunded);
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
        final List<Bonus> refunded = new java.util.ArrayList<>();
        @Override public void refundBonus(Bonus bonus) { refunded.add(bonus); }

        @Override
        public List<Bonus> consumeArmedBonuses() {
            if (consumed || bonusToConsume == null) return Collections.emptyList();
            consumed = true;
            return Collections.singletonList(bonusToConsume);
        }
    }
}
