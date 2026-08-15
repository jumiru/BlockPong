package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Reported requirement: releasing at or below the start line (firePosY, where the resting ball
// sits) must not fire a shot -- the drag never lifted above it, so it isn't a committed aim, and
// the player should just be able to touch down again instead of an unintended shot going off.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TouchAimCancelTest {

    @Test
    public void releaseAtOrBelowStartLine_cancelsInsteadOfFiring() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();

        gb.touchDown(startX, firePosY);
        // Release without ever dragging above the start line -- a small sideways wobble only.
        gb.touchRelease(startX + 20f, firePosY);

        for (int i = 0; i < 10; i++) gb.update();
        assertFalse("release at the start line should not fire a shot", gb.ballRolling());

        // The player should be able to just touch down again and fire normally afterwards.
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + 5f, firePosY - 400f);
        for (int i = 0; i < 10; i++) gb.update();
        assertTrue("a normal drag above the start line should still fire", gb.ballRolling());
    }

    @Test
    public void releaseAboveStartLine_stillFiresNormally() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();

        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + 5f, firePosY - 1f);

        for (int i = 0; i < 10; i++) gb.update();
        assertTrue("a release just above the start line should still fire", gb.ballRolling());
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
        @Override public java.util.List<Bonus> consumeArmedBonuses() { return Collections.emptyList(); }
    }
}
