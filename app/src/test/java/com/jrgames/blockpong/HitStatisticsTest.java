package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// Covers the "Treffer pro Schuss" statistic (see Game.recordShotStatistics()): a shot that dents
// a block's value without actually clearing it should still count as a hit, distinct from
// GameBoard's existing blocksClearedThisMove (reported via onRoundEnd()), which only counts
// blocks that reached zero. The two used to be conflated -- a shot's real hit count was never
// tracked at all.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HitStatisticsTest {

    @Test
    public void hitThatDoesNotClearTheBlock_stillCountsAsAHit() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();

        // Value high enough that a single shot's hit(s) can't possibly clear it, so
        // blocksClearedThisMove stays 0 while getHitsThisMove() must still register the hit(s).
        gb.placeSquareBlockForTests(5, 2, 1000);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + 5f, firePosY - 400f);

        int steps = 0;
        while (!gb.hasLastMoveRecording() && steps < 2000) {
            gb.update();
            steps++;
        }
        assertTrue("shot should have completed within 2000 update() ticks", gb.hasLastMoveRecording());

        assertTrue("a shot aimed at a block should register at least one hit", gb.getHitsThisMove() >= 1);
        Block block = gb.getBlockForTests(5, 2);
        assertEquals("block value should have dropped by exactly the number of hits recorded",
                1000 - gb.getHitsThisMove(), block.getValue());
    }

    @Test
    public void hitsThisMove_resetsOnTheNextShot() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(5, 2, 1000);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + 5f, firePosY - 400f);
        int steps = 0;
        while (!gb.hasLastMoveRecording() && steps < 2000) {
            gb.update();
            steps++;
        }
        assertTrue(gb.getHitsThisMove() >= 1);

        // A second shot aimed away from any block should reset the count to 0, not carry over.
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX - 300f, firePosY - 20f);
        steps = 0;
        while (!gb.ballRolling() && steps < 200) {
            gb.update();
            steps++;
        }
        assertEquals("hitsThisMove should reset at the start of the next shot", 0, gb.getHitsThisMove());
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
        @Override public List<Bonus> consumeArmedBonuses() { return Collections.emptyList(); }
    }
}
