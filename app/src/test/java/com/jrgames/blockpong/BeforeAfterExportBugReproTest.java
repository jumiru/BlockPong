package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

// Investigates a bug report: the "vor dem Zug" / "nach dem Zug" JSON in getLastMoveReport() came
// back byte-identical for a real shot on a board where rows 3-9 are a completely solid 11-wide
// wall -- physically, a 10-ball shot bouncing off that wall must reduce at least one block's
// value. Replays the exact reported board layout and shot to find out whether the live board
// really doesn't change (a physics question) or whether the "after" snapshot capture itself is
// broken (an export bug).
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BeforeAfterExportBugReproTest {

    @Test
    public void reportedShot_afterSnapshotReflectsActualHits() {
        float boardWidth = 1060f;
        float boardHeight = 1980f;
        float offsetX = 10f;
        float offsetY = 20f;

        GameBoard gb = new GameBoard(new TestGameCallbacks(), boardWidth, boardHeight, offsetX, offsetY);
        gb.clearBoardForTests();

        for (int x = 0; x <= 10; x++) gb.placeTriangleBlockForTests(x, 3, Block3.tTriangle.TL, 6);
        for (int x = 0; x <= 10; x++) gb.placeTriangleBlockForTests(x, 4, Block3.tTriangle.TR, 7);
        for (int x = 0; x <= 10; x++) gb.placeTriangleBlockForTests(x, 5, Block3.tTriangle.TL, 7);
        for (int x = 0; x <= 10; x++) gb.placeTriangleBlockForTests(x, 6, Block3.tTriangle.TR, 8);
        for (int x = 0; x <= 10; x++) gb.placeTriangleBlockForTests(x, 7, Block3.tTriangle.TL, 9);
        for (int x = 0; x <= 10; x++) gb.placeTriangleBlockForTests(x, 8, Block3.tTriangle.TR, 9);
        for (int x = 0; x <= 10; x++) {
            int value = 10;
            if (x == 5) value = 7;
            if (x == 7) value = 1;
            gb.placeTriangleBlockForTests(x, 9, Block3.tTriangle.TL, value);
        }
        gb.placeTriangleBlockForTests(0, 10, Block3.tTriangle.TR, 10);
        gb.placeTriangleBlockForTests(1, 10, Block3.tTriangle.TR, 10);
        gb.placeTriangleBlockForTests(2, 10, Block3.tTriangle.TR, 10);
        gb.placeTriangleBlockForTests(3, 10, Block3.tTriangle.TR, 2);
        gb.placeTriangleBlockForTests(8, 10, Block3.tTriangle.TR, 7);
        gb.placeTriangleBlockForTests(9, 10, Block3.tTriangle.TR, 10);
        gb.placeTriangleBlockForTests(10, 10, Block3.tTriangle.TR, 10);

        String beforeShotJson = gb.exportBlocksJson();
        System.out.println("Board before shot: " + beforeShotJson);

        float startX = 377.29657f;
        float firePosY = gb.getFirePosYForTests();
        float dx = 1.1402466f;
        float dy = -49.986996f;

        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + dx, firePosY + dy);

        int steps = 0;
        while (!gb.hasLastMoveRecording() && steps < 2000) {
            gb.update();
            steps++;
        }
        assertTrue("shot should have completed within 2000 update() ticks", gb.hasLastMoveRecording());

        String report = gb.getLastMoveReport();
        System.out.println(report);

        String afterShotJson = gb.exportBlocksJson();
        System.out.println("Board after shot (live gb.exportBlocksJson()): " + afterShotJson);

        int beforeIdx = report.indexOf("{\"blocks\"");
        int afterIdx = report.indexOf("{\"blocks\"", beforeIdx + 1);
        assertTrue("report should contain two JSON blocks", afterIdx > beforeIdx);
        String reportBefore = report.substring(beforeIdx, report.indexOf('\n', beforeIdx));
        String reportAfter = report.substring(afterIdx, report.indexOf('\n', afterIdx) >= 0 ? report.indexOf('\n', afterIdx) : report.length());

        System.out.println("reportBefore=" + reportBefore);
        System.out.println("reportAfter =" + reportAfter);
        System.out.println("live board after shot matches reportAfter: " + afterShotJson.equals(reportAfter));

        assertNotEquals("live board should differ from its pre-shot state (rows 3-9 are a solid wall; some block must take a hit)",
                beforeShotJson, afterShotJson);
        assertNotEquals("getLastMoveReport()'s 'nach dem Zug' JSON should differ from its 'vor dem Zug' JSON",
                reportBefore, reportAfter);
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
        @Override public int getLevel() { return 5; }
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
