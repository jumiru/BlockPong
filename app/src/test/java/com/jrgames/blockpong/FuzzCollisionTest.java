package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Random;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FuzzCollisionTest {

    private static final int NUM_SCENARIOS = 300;
    private static final int STEPS_PER_SCENARIO = 300;
    private static final float BOARD_WIDTH = 660f;
    private static final float BOARD_HEIGHT = 900f;

    // Run 300 random scenarios and report all unique failure types.
    @Test
    public void fuzz_randomScenarios_ballNeverLandsOnBlock() {
        String report = fuzzScenarios(false);
        if (report != null) {
            fail(report);
        }
    }

    // Same fuzz sweep against the "Mini-Blöcke" board config (see GameBoard.applyBoardConfig()):
    // a much finer grid and a smaller ball/normSpeed, scaled down together to keep the same
    // ballRadius-vs-blockWidth and normSpeed-vs-blockWidth safety margins as the normal board (see
    // GameBoard's NORMAL_*/MINI_* constants) -- this confirms that scaling actually holds up under
    // the same collision physics instead of just trusting the arithmetic.
    @Test
    public void fuzz_miniBlockScenarios_ballNeverLandsOnBlock() {
        String report = fuzzScenarios(true);
        if (report != null) {
            fail(report);
        }
    }

    // Requested default: a Mini-Blöcke board starts each shot with 20 balls instead of the normal
    // board's 10 (see GameBoard.MINI_NUM_INIT_BALLS/applyBoardConfig()).
    @Test
    public void miniBlockConfig_defaultsToTwentyInitBalls() {
        GameBoard normal = createTestBoard(false);
        assertNotNull(normal.getBallForTests(9));
        assertNull(normal.getBallForTests(10));

        GameBoard mini = createTestBoard(true);
        assertNotNull(mini.getBallForTests(19));
        assertNull(mini.getBallForTests(20));
    }

    private String fuzzScenarios(boolean miniBlocks) {
        StringBuilder report = new StringBuilder();
        int onBlockCount = 0;
        int jumpedCount  = 0;
        int speedCount   = 0;

        for (int scenario = 0; scenario < NUM_SCENARIOS; scenario++) {
            long seed = 1000L + scenario;
            String failure = runScenario(seed, miniBlocks);
            if (failure != null) {
                if (failure.startsWith("BALL ON BLOCK") && onBlockCount++ < 3) report.append(failure).append("\n\n");
                else if (failure.startsWith("BALL JUMPED")  && jumpedCount++ < 3) report.append(failure).append("\n\n");
                else if (failure.startsWith("SPEED CHANGED") && speedCount++ < 3) report.append(failure).append("\n\n");
            }
        }

        if (report.length() > 0) {
            report.insert(0, String.format(
                "onBlock=%d  jumped=%d  speed=%d\n\n", onBlockCount, jumpedCount, speedCount));
            return report.toString();
        }
        return null;
    }

    // Reproduces a specific scenario by seed -- point this at any seed reported by
    // fuzz_randomScenarios_ballNeverLandsOnBlock to get a focused, fast-failing repro.
    @Test
    public void replay_specificSeed() {
        long seed = 1000L;
        String result = runScenario(seed, false);
        if (result != null) {
            fail(result);
        }
    }

    // Regression test for a fixed edge case: two adjacent triangles with the same orientation
    // (e.g. a BL triangle directly next to another BL triangle) form a solid corridor whose
    // hypotenuse and straight-edge constraints overlap in a strip narrower than the ball's
    // diameter. enforceTriangleClearance/-SquareClearance in GameBoard used to resolve one
    // constraint by pushing the ball back into the other's trigger zone, with no single-step
    // fixed point -- both now pull the ball back to the last point along its own step path that's
    // clear of every block (pullBackToLastSafePoint), instead of pushing out along one block's
    // local normal, which bounds the correction to that step's own travel budget.
    @Test
    public void knownIssue_adjacentSameOrientationTriangles() {
        String result = runScenario(1136L, false);
        if (result != null) {
            fail(result);
        }
    }

    private String runScenario(long seed, boolean miniBlocks) {
        Random rand = new Random(seed);
        GameBoard gb = createTestBoard(miniBlocks);

        // Place random blocks (rows 0-10, avoid the fire zone at the bottom) -- bounded by the
        // board's own grid (11x18 normal, larger for Mini-Blöcke) so blocks never land out of range.
        int maxBx = Math.min(gb.getXDimForTests(), 11);
        int maxBy = Math.min(gb.getYDimForTests(), 11);
        int numBlocks = rand.nextInt(12) + 1;
        for (int i = 0; i < numBlocks; i++) {
            int bx = rand.nextInt(maxBx);
            int by = rand.nextInt(maxBy);
            int val = rand.nextInt(5) + 1;
            if (rand.nextBoolean()) {
                gb.placeSquareBlockForTests(bx, by, val);
            } else {
                Block3.tTriangle[] types = {
                    Block3.tTriangle.BL, Block3.tTriangle.BR,
                    Block3.tTriangle.TL, Block3.tTriangle.TR
                };
                gb.placeTriangleBlockForTests(bx, by, types[rand.nextInt(4)], val);
            }
        }

        float ballRadius = gb.getBallRadiusForTests();
        // Mini-Blöcke uses a smaller normSpeed to keep the same ballRadius/normSpeed-vs-blockWidth
        // safety margins as the normal board (see GameBoard's NORMAL_*/MINI_* constants) -- using
        // the fixed NORM_SPEED here for both configs would fire the ball far faster than the real
        // game ever does on a Mini-Blöcke board, and could manufacture tunneling that never
        // actually happens in production.
        float normSpeed = gb.getNormSpeedForTests();

        // Ball starts in the lower empty zone near the fire line.
        float startX = ballRadius + rand.nextFloat() * (BOARD_WIDTH - 2 * ballRadius);
        float startY = BOARD_HEIGHT - 3 * ballRadius - rand.nextFloat() * 2 * ballRadius;
        Ball ball = new Ball(ballRadius, startX, startY, 0);

        // Upward velocity, at least 15° from horizontal so the ball reaches the blocks.
        float maxHoriz = (float) Math.cos(Math.toRadians(15)) * normSpeed;
        float horizComp = (rand.nextFloat() * 2 - 1) * maxHoriz;
        float vertComp  = -(float) Math.sqrt(normSpeed * normSpeed - horizComp * horizComp);
        ball.setSpeed(horizComp, vertComp);

        // Skip scenarios where the ball starts inside a block.
        if (gb.ballOnBlock(ball)) return null;

        for (int step = 0; step < STEPS_PER_SCENARIO; step++) {
            float prevX  = ball.getX();
            float prevY  = ball.getY();
            float prevDx = ball.getDx();
            float prevDy = ball.getDy();

            gb.stepBallOnceForTests(ball);

            if (ball.isStill()) break;

            // Invariant 1: ball must not be inside (or on) a block.
            if (gb.ballOnBlock(ball)) {
                return String.format(
                    "BALL ON BLOCK  seed=%d  step=%d\n" +
                    "  prev pos  (%.2f, %.2f)  vel (%.2f, %.2f)\n" +
                    "  next pos  (%.2f, %.2f)\n" +
                    "Reproduce: set seed=%d in replay_specificSeed()",
                    seed, step,
                    prevX, prevY, prevDx, prevDy,
                    ball.getX(), ball.getY(),
                    seed);
            }

            // Invariant 2: speed magnitude must be preserved (same tolerance as debug mode: ±1 in speed²).
            float speedSq     = ball.getDx() * ball.getDx() + ball.getDy() * ball.getDy();
            float normSpeedSq = normSpeed * normSpeed;
            if (speedSq < normSpeedSq - 2f || speedSq > normSpeedSq + 2f) {
                return String.format(
                    "SPEED CHANGED  seed=%d  step=%d\n" +
                    "  prev pos  (%.2f, %.2f)  vel (%.2f, %.2f)\n" +
                    "  next pos  (%.2f, %.2f)  speed=%.3f (expected %.1f)\n" +
                    "Reproduce: set seed=%d in replay_specificSeed()",
                    seed, step,
                    prevX, prevY, prevDx, prevDy,
                    ball.getX(), ball.getY(),
                    (float) Math.sqrt(speedSq), normSpeed,
                    seed);
            }

            // Invariant 3: ball must not jump more than 110% of one step.
            float dist = (float) Math.sqrt(
                (ball.getX() - prevX) * (ball.getX() - prevX) +
                (ball.getY() - prevY) * (ball.getY() - prevY));
            if (dist > 1.1f * normSpeed) {
                return String.format(
                    "BALL JUMPED TOO FAR  seed=%d  step=%d  dist=%.2f\n" +
                    "  prev (%.2f, %.2f)  next (%.2f, %.2f)\n" +
                    "Reproduce: set seed=%d in replay_specificSeed()",
                    seed, step, dist,
                    prevX, prevY, ball.getX(), ball.getY(),
                    seed);
            }
        }
        return null;
    }

    private static GameBoard createTestBoard(boolean miniBlocks) {
        GameBoard gb = new GameBoard(new TestGameCallbacks(miniBlocks), BOARD_WIDTH, BOARD_HEIGHT, 0f, 0f);
        gb.clearBoardForTests();
        return gb;
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
        private int level = 1;
        private final boolean miniBlocks;
        TestGameCallbacks(boolean miniBlocks) { this.miniBlocks = miniBlocks; }
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
        @Override public boolean isMiniBlockLevel() { return miniBlocks; }
    }
}
