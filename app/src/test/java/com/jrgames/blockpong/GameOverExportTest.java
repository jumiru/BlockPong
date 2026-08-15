package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// Investigates a bug report: "Letzten Zug exportieren" should still work for the shot that
// triggers game over (a block already sitting in the bottom row when the shot ends), not just
// for ordinary shots. Root cause found: exportBlocksJson(grid) only serialized rows 0..yDim-3,
// silently dropping the bottom two rows -- exactly where blocks sit once they trigger game over,
// so the exported "vor"/"nach dem Zug" JSON came back looking empty even though the live board
// wasn't. loadBlocksFromJson() had the matching import-side bug, so restoring a saved game (or
// "Import & Replay (Debug)") would also silently lose those blocks.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GameOverExportTest {

    @Test
    public void shotThatTriggersGameOver_stillProducesLastMoveReport() {
        TestGameCallbacks callbacks = new TestGameCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();

        // A block already resting in the bottom playable row (yDim-1, yDim=18 -- see GameBoard's
        // "game-over row moved further down" comment) -- this shot won't touch it, so once the
        // shot ends, hasBlocksInRow(yDim-1) is true and game-over should trigger.
        gb.placeSquareBlockForTests(0, 17, 9);
        // A block the shot actually can hit, elsewhere on the board, so before/after differ.
        gb.placeSquareBlockForTests(5, 2, 4);

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
        assertTrue("this shot should have triggered game over", callbacks.gameOverCalled);

        String report = gb.getLastMoveReport();
        assertNotNull("getLastMoveReport() should still work for the game-over shot", report);
        assertTrue(report.contains("Spielfeld vor dem Zug"));
        assertTrue(report.contains("Spielfeld nach dem Zug"));

        // The game-over-causing block at (0,17) must actually show up in both snapshots -- this
        // is what came back empty before the fix.
        int beforeIdx = report.indexOf("Spielfeld vor dem Zug");
        int afterIdx = report.indexOf("Spielfeld nach dem Zug");
        String beforeJson = report.substring(beforeIdx, afterIdx);
        String afterJson = report.substring(afterIdx);
        assertTrue("'vor dem Zug' should include the block sitting in the game-over row",
                beforeJson.contains("\"x\":0") && beforeJson.contains("\"y\":17"));
        assertTrue("'nach dem Zug' should include the block sitting in the game-over row",
                afterJson.contains("\"x\":0") && afterJson.contains("\"y\":17"));
    }

    @Test
    public void liveBoardExport_includesBottomTwoRows() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(2, 16, 5);
        gb.placeSquareBlockForTests(3, 17, 6);

        String exported = gb.exportBlocksJson();
        assertTrue("exportBlocksJson() should include row 16 (was clipped to yDim-2)",
                exported.contains("\"y\":16"));
        assertTrue("exportBlocksJson() should include row 17 (was clipped to yDim-2)",
                exported.contains("\"y\":17"));
    }

    @Test
    public void restoreBlocksFromJson_roundTripsBottomTwoRows() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(2, 16, 5);
        gb.placeSquareBlockForTests(3, 17, 6);
        String exported = gb.exportBlocksJson();

        gb.restoreBlocksFromJson(exported);

        assertNotNull("restoring a saved game should bring back a block sitting in row 16",
                gb.getBlockForTests(2, 16));
        assertNotNull("restoring a saved game should bring back a block sitting in row 17 "
                + "(the game-over row) -- previously silently dropped on import",
                gb.getBlockForTests(3, 17));
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
        boolean gameOverCalled;

        @Override public int getLevel() { return 1; }
        @Override public void addAnimation(Animation a) {}
        @Override public void increaselevel() {}
        @Override public void setGameOver(boolean win) { gameOverCalled = true; }
        @Override public boolean isGameOver() { return gameOverCalled; }
        @Override public void resetGameOver() { gameOverCalled = false; }
        @Override public void addScore(int points) {}
        @Override public String loadLevelJson(int level) { return null; }
        @Override public void onRoundEnd(int blocksCleared, int ballsUsed) {}
        @Override public boolean isBonusArmed(Bonus bonus) { return false; }
        @Override public java.util.List<Bonus> consumeArmedBonuses() { return Collections.emptyList(); }
    }
}
