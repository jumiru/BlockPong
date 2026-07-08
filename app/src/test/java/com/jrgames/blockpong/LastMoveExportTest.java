package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Covers the burger menu's "Letzten Zug exportieren" action: GameBoard.getLastMoveReport()
// should reflect the board layout, start point, launch angle and applied bonuses of the shot
// that was actually fired, once it completes.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LastMoveExportTest {

    @Test
    public void noMoveYet_reportIsNull() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(Collections.emptyList()), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();

        assertFalse(gb.hasLastMoveRecording());
        assertNull(gb.getLastMoveReport());
    }

    @Test
    public void completedShot_reportIncludesBoardStartPointAngleAndBonuses() {
        TestGameCallbacks callbacks = new TestGameCallbacks(Arrays.asList(Bonus.MOVE_STOPPER, Bonus.EXTRA_BALLS));
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(3, 2, 4);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + 80f, firePosY - 400f);

        for (int i = 0; i < 200 && !gb.hasLastMoveRecording(); i++) {
            gb.update();
        }
        assertTrue("shot should have completed within 200 update() ticks", gb.hasLastMoveRecording());

        String report = gb.getLastMoveReport();
        assertNotNull(report);
        assertTrue(report.contains("MOVE_STOPPER"));
        assertTrue(report.contains("EXTRA_BALLS"));
        assertTrue(report.contains("\"x\":3"));
        assertTrue(report.contains("\"y\":2"));
        assertTrue(report.contains("\"value\":4"));
        assertTrue(report.contains("Startpunkt x:"));
        assertTrue(report.contains("Winkel"));
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
        private final List<Bonus> bonusesToConsume;
        private boolean consumed;

        TestGameCallbacks(List<Bonus> bonusesToConsume) {
            this.bonusesToConsume = bonusesToConsume;
        }

        @Override public int getLevel() { return 3; }
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
            if (consumed) return Collections.emptyList();
            consumed = true;
            return bonusesToConsume;
        }
    }
}
