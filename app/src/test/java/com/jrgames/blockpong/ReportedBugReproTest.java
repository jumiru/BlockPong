package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.fail;

// Regression test for the bug report "ball 8 has been moved too far" (Update counter 58, using
// the exact board layout and ball state from that report -- rows 6-13 are a triangle field with
// adjacent same-orientation triangles at (6,10)/(7,10)). Verifies GameBoard's clearance safety
// nets no longer produce an oversized correction when the ball ends a step embedded there.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReportedBugReproTest {

    @Test
    public void reproduceBallMovedTooFar() {
        float boardWidth = 1060f;
        float boardHeight = 1980f;
        float offsetX = 10f;
        float offsetY = 20f;

        GameBoard gb = new GameBoard(new TestGameCallbacks(), boardWidth, boardHeight, offsetX, offsetY);
        gb.clearBoardForTests();

        placeRow(gb, 6, Block3.tTriangle.TL, 6);
        placeRow(gb, 7, Block3.tTriangle.TR, 7);
        placeRow(gb, 8, Block3.tTriangle.TL, 8);
        placeRow(gb, 9, Block3.tTriangle.TR, 9);

        int[] row10Values = {10,10,10,10,10,10,9,9,10,10,10};
        for (int x = 0; x < 11; x++) gb.placeTriangleBlockForTests(x, 10, Block3.tTriangle.TL, row10Values[x]);

        gb.placeTriangleBlockForTests(0, 11, Block3.tTriangle.TR, 11);
        gb.placeTriangleBlockForTests(1, 11, Block3.tTriangle.TR, 11);
        gb.placeTriangleBlockForTests(2, 11, Block3.tTriangle.TR, 11);
        gb.placeTriangleBlockForTests(3, 11, Block3.tTriangle.TR, 11);
        gb.placeTriangleBlockForTests(4, 11, Block3.tTriangle.TR, 11);
        gb.placeTriangleBlockForTests(5, 11, Block3.tTriangle.TR, 3);
        gb.placeTriangleBlockForTests(7, 11, Block3.tTriangle.TR, 6);
        gb.placeTriangleBlockForTests(8, 11, Block3.tTriangle.TR, 11);
        gb.placeTriangleBlockForTests(9, 11, Block3.tTriangle.TR, 11);
        gb.placeTriangleBlockForTests(10, 11, Block3.tTriangle.TR, 11);

        gb.placeTriangleBlockForTests(0, 12, Block3.tTriangle.TL, 2);
        gb.placeTriangleBlockForTests(2, 12, Block3.tTriangle.TL, 8);
        gb.placeTriangleBlockForTests(3, 12, Block3.tTriangle.TL, 12);
        gb.placeTriangleBlockForTests(4, 12, Block3.tTriangle.TL, 12);
        gb.placeTriangleBlockForTests(5, 12, Block3.tTriangle.TL, 12);
        gb.placeTriangleBlockForTests(7, 12, Block3.tTriangle.TL, 6);
        gb.placeTriangleBlockForTests(8, 12, Block3.tTriangle.TL, 12);
        gb.placeTriangleBlockForTests(9, 12, Block3.tTriangle.TL, 12);
        gb.placeTriangleBlockForTests(10, 12, Block3.tTriangle.TL, 12);

        gb.placeTriangleBlockForTests(2, 13, Block3.tTriangle.TR, 3);
        gb.placeTriangleBlockForTests(3, 13, Block3.tTriangle.TR, 1);
        gb.placeTriangleBlockForTests(7, 13, Block3.tTriangle.TR, 12);
        gb.placeTriangleBlockForTests(8, 13, Block3.tTriangle.TR, 13);
        gb.placeTriangleBlockForTests(9, 13, Block3.tTriangle.TR, 13);
        gb.placeTriangleBlockForTests(10, 13, Block3.tTriangle.TR, 13);

        float ballRadius = gb.getBallRadiusForTests();
        Ball ball = new Ball(ballRadius, 663.29456f, 1066.6466f, 0);
        ball.setSpeed(0.027318155f, -49.999992f);

        System.out.println("ball on block at start? " + gb.ballOnBlock(ball));

        float normSpeed = 50f;
        for (int step = 0; step < 60; step++) {
            float prevX = ball.getX();
            float prevY = ball.getY();

            gb.stepBallOnceForTests(ball);

            if (ball.isStill()) {
                System.out.println("ball stopped at step " + step);
                break;
            }

            float dist = (float) Math.sqrt(
                    (ball.getX() - prevX) * (ball.getX() - prevX) +
                    (ball.getY() - prevY) * (ball.getY() - prevY));
            System.out.printf("step=%d prev=(%.3f,%.3f) next=(%.3f,%.3f) vel=(%.3f,%.3f) dist=%.3f onBlock=%b%n",
                    step, prevX, prevY, ball.getX(), ball.getY(), ball.getDx(), ball.getDy(), dist, gb.ballOnBlock(ball));

            if (dist > 1.1f * normSpeed) {
                fail(String.format("BALL JUMPED TOO FAR step=%d dist=%.2f prev=(%.2f,%.2f) next=(%.2f,%.2f)",
                        step, dist, prevX, prevY, ball.getX(), ball.getY()));
            }
            if (gb.ballOnBlock(ball)) {
                fail(String.format("BALL ON BLOCK step=%d pos=(%.2f,%.2f)", step, ball.getX(), ball.getY()));
            }
            float speedSq = ball.getDx()*ball.getDx() + ball.getDy()*ball.getDy();
            if (Math.abs(speedSq - normSpeed*normSpeed) > 2f) {
                fail(String.format("SPEED CHANGED step=%d speed=%.3f", step, Math.sqrt(speedSq)));
            }
        }
    }

    private static void placeRow(GameBoard gb, int y, Block3.tTriangle type, int value) {
        for (int x = 0; x < 11; x++) {
            gb.placeTriangleBlockForTests(x, y, type, value);
        }
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
        private int level = 1;
        @Override public int getLevel() { return level; }
        @Override public void addAnimation(Animation a) {}
        @Override public void increaselevel() { level++; }
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
