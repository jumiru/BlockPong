package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GameBoardCollisionTest {

    private static final float EPS = 1e-3f;

    @Test
    public void leftBorderCollision_flipsOnlyDx() {
        GameBoard gameBoard = createTestBoard();
        Ball ball = new Ball(gameBoard.getBallRadiusForTests(), 30f, 200f, 0);
        ball.setSpeed(-20f, -7f);

        gameBoard.stepBallOnceForTests(ball);

        assertEquals(20f, ball.getDx(), EPS);
        assertEquals(-7f, ball.getDy(), EPS);
        assertTrue(ball.getX() > gameBoard.getBallRadiusForTests());
    }

    @Test
    public void squareFaceCollision_decrementsBlockValue() {
        GameBoard gameBoard = createTestBoard();
        gameBoard.placeSquareBlockForTests(4, 3, 2);

        Ball ball = new Ball(gameBoard.getBallRadiusForTests(), 270f, 280f, 1);
        ball.setSpeed(0f, -30f);

        gameBoard.stepBallOnceForTests(ball);

        assertEquals(1, gameBoard.getBlock(4, 3).getValue());
        assertTrue(ball.getDy() > 0f);
    }

    @Test
    public void triangleTopTipCollision_isDetectedAndHitCountDrops() {
        GameBoard gameBoard = createTestBoard();
        gameBoard.placeTriangleBlockForTests(5, 5, Block3.tTriangle.BL, 3);

        float cornerX = gameBoard.left(5);
        float cornerY = gameBoard.top(5);
        Block cornerHit = gameBoard.findCornerHitBlockForTests(cornerX - 40f, cornerY - 40f, cornerX + 40f, cornerY + 40f);
        assertNotNull(cornerHit);

        Ball ball = new Ball(gameBoard.getBallRadiusForTests(), cornerX - 40f, cornerY - 40f, 2);
        ball.setSpeed(40f, 40f);

        boolean collision = gameBoard.stepBallOnceForTests(ball);

        assertTrue(collision);
        assertEquals(2, gameBoard.getBlock(5, 5).getValue());
    }

    @Test
    public void topLeftBoardCornerCollision_reflectsOnBothAxes() {
        GameBoard gameBoard = createTestBoard();
        Ball ball = new Ball(gameBoard.getBallRadiusForTests(), 30f, 30f, 3);
        ball.setSpeed(-15f, -17f);

        gameBoard.stepBallOnceForTests(ball);

        assertTrue(ball.getDx() > 0f);
        assertTrue(ball.getDy() > 0f);
        assertTrue(ball.getX() >= gameBoard.getBallRadiusForTests() - EPS);
        assertTrue(ball.getY() >= gameBoard.getBallRadiusForTests() - EPS);
    }

    @Test
    public void emptyBoardStep_keepsVelocityWhenNoCollisionHappens() {
        GameBoard gameBoard = createTestBoard();
        Ball ball = new Ball(gameBoard.getBallRadiusForTests(), 330f, 420f, 4);
        ball.setSpeed(9f, -11f);

        boolean collision = gameBoard.stepBallOnceForTests(ball);

        assertTrue(!collision);
        assertEquals(9f, ball.getDx(), EPS);
        assertEquals(-11f, ball.getDy(), EPS);
    }

    @Test
    public void aimStraightUp_isUnaffectedByClamp() {
        GameBoard gameBoard = createTestBoard();
        float fx = gameBoard.getFirePosXForTests();
        float fy = gameBoard.getFirePosYForTests();

        gameBoard.touchDown(fx, fy - 300f);
        gameBoard.touchRelease(fx, fy - 300f);

        assertEquals(0f, gameBoard.getFireSpeedXForTests(), 0.5f);
        assertTrue(gameBoard.getFireSpeedYForTests() < 0f);
    }

    @Test
    public void aimBelowFireLine_getsClampedToUpwardMinimumAngle() {
        GameBoard gameBoard = createTestBoard();
        float fx = gameBoard.getFirePosXForTests();
        float fy = gameBoard.getFirePosYForTests();

        // Dragged down and to the right -- straight down toward/past the fire line, which must
        // not be allowed to aim the ball away from the board.
        gameBoard.touchDown(fx + 50f, fy + 50f);
        gameBoard.touchRelease(fx + 300f, fy + 300f);

        float dx = gameBoard.getFireSpeedXForTests();
        float dy = gameBoard.getFireSpeedYForTests();
        assertTrue("expected an upward shot, got dy=" + dy, dy < 0f);

        double angleFromHorizontalDeg = Math.toDegrees(Math.atan2(-dy, Math.abs(dx)));
        assertEquals(10.0, angleFromHorizontalDeg, 0.5);
        assertTrue("expected a rightward shot, got dx=" + dx, dx > 0f);
    }

    @Test
    public void aimBelowFireLineToTheLeft_getsClampedToUpwardMinimumAngle() {
        GameBoard gameBoard = createTestBoard();
        float fx = gameBoard.getFirePosXForTests();
        float fy = gameBoard.getFirePosYForTests();

        gameBoard.touchDown(fx - 50f, fy + 50f);
        gameBoard.touchRelease(fx - 300f, fy + 300f);

        float dx = gameBoard.getFireSpeedXForTests();
        float dy = gameBoard.getFireSpeedYForTests();
        assertTrue("expected an upward shot, got dy=" + dy, dy < 0f);

        double angleFromHorizontalDeg = Math.toDegrees(Math.atan2(-dy, Math.abs(dx)));
        assertEquals(10.0, angleFromHorizontalDeg, 0.5);
        assertTrue("expected a leftward shot, got dx=" + dx, dx < 0f);
    }

    @Test
    public void aimNearlyHorizontal_getsClampedToMinimumAngle() {
        GameBoard gameBoard = createTestBoard();
        float fx = gameBoard.getFirePosXForTests();
        float fy = gameBoard.getFirePosYForTests();

        // Just barely above the fire line -- an almost-flat shot that's still technically
        // "upward" but shallower than the 10 degree floor.
        gameBoard.touchDown(fx + 300f, fy - 5f);
        gameBoard.touchRelease(fx + 300f, fy - 5f);

        float dx = gameBoard.getFireSpeedXForTests();
        float dy = gameBoard.getFireSpeedYForTests();
        double angleFromHorizontalDeg = Math.toDegrees(Math.atan2(-dy, Math.abs(dx)));
        assertEquals(10.0, angleFromHorizontalDeg, 0.5);
    }

    @Test
    public void initBoard_loadsPredefinedLevelJsonWhenAvailable() {
        String json = "{\"blocks\":["
                + "{\"x\":2,\"y\":3,\"type\":\"square\",\"value\":7},"
                + "{\"x\":5,\"y\":1,\"type\":\"tl\",\"value\":4}"
                + "]}";
        GameBoard gameBoard = new GameBoard(new FixedLevelCallbacks(json), 660f, 900f, 0f, 0f);

        Block square = gameBoard.getBlock(2, 3);
        assertNotNull(square);
        assertEquals(7, square.getValue());

        Block triangle = gameBoard.getBlock(5, 1);
        assertNotNull(triangle);
        assertTrue(triangle instanceof Block3);
        assertEquals(Block3.tTriangle.TL, ((Block3) triangle).getType());
    }

    @Test
    public void initBoard_fallsBackWithoutCrashingOnMalformedLevelJson() {
        GameBoard gameBoard = new GameBoard(new FixedLevelCallbacks("not valid json"), 660f, 900f, 0f, 0f);
        assertNotNull(gameBoard);
    }

    @Test
    public void debugReport_containsTemplateAndNearbyBlockSection() {
        GameBoard gameBoard = createTestBoard();
        gameBoard.placeSquareBlockForTests(4, 4, 7);

        String report = gameBoard.getDebugReportForSharing();

        assertNotNull(report);
        assertTrue(report.contains("Title:"));
        assertTrue(report.contains("Freeze reason:"));
        assertTrue(report.contains("Blocks near hit (x,y,type,value):"));
        assertTrue(report.contains("Repro steps:"));
    }

    private static GameBoard createTestBoard() {
        GameBoard gameBoard = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gameBoard.clearBoardForTests();
        return gameBoard;
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {

        private int level = 1;

        @Override
        public int getLevel() {
            return level;
        }

        @Override
        public void addAnimation(Animation animation) {
            // No animations needed in unit tests.
        }

        @Override
        public void increaselevel() {
            level++;
        }

        @Override
        public void setGameOver(boolean win) {
            // Not needed for collision-only unit tests.
        }

        @Override
        public boolean isGameOver() {
            return false;
        }

        @Override
        public void resetGameOver() {
            // Not needed for collision-only unit tests.
        }

        @Override
        public void addScore(int points) {
            // Not needed for collision-only unit tests.
        }

        @Override
        public String loadLevelJson(int level) {
            return null;
        }

        @Override
        public void onRoundEnd() {
            // Not needed for collision-only unit tests.
        }

        @Override
        public Bonus getArmedBonus() {
            return null;
        }

        @Override
        public Bonus consumeArmedBonus() {
            return null;
        }
    }

    private static final class FixedLevelCallbacks implements GameBoard.GameCallbacks {
        private final String levelJson;

        FixedLevelCallbacks(String levelJson) {
            this.levelJson = levelJson;
        }

        @Override public int getLevel() { return 1; }
        @Override public void addAnimation(Animation animation) {}
        @Override public void increaselevel() {}
        @Override public void setGameOver(boolean win) {}
        @Override public boolean isGameOver() { return false; }
        @Override public void resetGameOver() {}
        @Override public void addScore(int points) {}
        @Override public String loadLevelJson(int level) { return levelJson; }
        @Override public void onRoundEnd() {}
        @Override public Bonus getArmedBonus() { return null; }
        @Override public Bonus consumeArmedBonus() { return null; }
    }
}








