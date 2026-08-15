package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.fail;

// Reported bug (still happening after the earlier touchDown() "!fire" fix): the start point
// and/or launch angle sometimes shift partway through firing a multi-ball shot -- balls launched
// later in the same shot don't match the ones launched first. A real shot dispatches one ball
// every 5 ticks (see GameBoard.update()'s "if (fire)" block), so a many-ball shot leaves a wide
// window during which stray touch events (menu-button fumbling, an accidental drag, etc.) could
// still interfere. This fires a large shot and fuzzes random touchDown/touchMove/touchRelease
// calls into that window, then asserts every dispatched ball got the exact same start x and
// launch vector as the first one (see GameBoard's dispatchedFirePosX/-FireSpeedX/-Y test hooks).
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ShotStartInvariantFuzzTest {

    private static final int NUM_SCENARIOS = 200;
    private static final float BOARD_WIDTH = 660f;
    private static final float BOARD_HEIGHT = 900f;

    @Test
    public void fuzz_strayTouchesDuringMultiBallLaunch_neverChangeStartPointOrAngle() {
        StringBuilder report = new StringBuilder();
        int failures = 0;

        for (int scenario = 0; scenario < NUM_SCENARIOS; scenario++) {
            long seed = 5000L + scenario;
            String failure = runScenario(seed);
            if (failure != null && failures++ < 5) {
                report.append("seed=").append(seed).append(": ").append(failure).append("\n\n");
            }
        }

        if (report.length() > 0) {
            report.insert(0, "failures=" + failures + "/" + NUM_SCENARIOS + "\n\n");
            fail(report.toString());
        }
    }

    // Reproduces a specific scenario by seed -- point this at any seed reported by the fuzz test
    // above for a focused, fast-failing repro.
    @Test
    public void replay_specificSeed() {
        long seed = 5000L;
        String result = runScenario(seed);
        if (result != null) {
            fail(result);
        }
    }

    private String runScenario(long seed) {
        Random rand = new Random(seed);
        TestGameCallbacks callbacks = new TestGameCallbacks();
        GameBoard gb = new GameBoard(callbacks, BOARD_WIDTH, BOARD_HEIGHT, 0f, 0f);
        gb.clearBoardForTests();

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();

        // Fire a real shot via the normal touch path, granting EXTRA_BALLS so there are enough
        // balls (staggered one dispatch every 5 ticks) to leave a wide fuzzing window.
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + 40f, firePosY - 300f);

        int steps = 0;
        int maxSteps = 3000;
        while (gb.getNextFireBallForTests() < 20 && steps < maxSteps) {
            gb.update();
            steps++;

            // Randomly fuzz in a stray touch sequence -- simulates a fumbled tap (near the menu
            // button, an accidental re-aim, etc.) landing during the multi-tick dispatch window.
            // Kept as a small jitter around one reference point (not an independent second
            // random point) so it doesn't accidentally trigger the unrelated "swipe down >=200px
            // while balls roll" ball-drop/abort gesture (touchRelease()'s last block), which
            // would derail this test into checking that gesture instead of the dispatch race.
            if (rand.nextInt(4) == 0) {
                float x1 = rand.nextFloat() * BOARD_WIDTH;
                float y1 = rand.nextFloat() * BOARD_HEIGHT;
                float x2 = x1 + (rand.nextFloat() - 0.5f) * 120f;
                float y2 = y1 + (rand.nextFloat() - 0.5f) * 120f;
                gb.touchDown(x1, y1);
                if (rand.nextBoolean()) {
                    gb.touchMove(x2, y2);
                }
                if (rand.nextInt(3) == 0) {
                    gb.cancelAim();
                } else {
                    gb.touchRelease(x2, y2);
                }
            }
        }

        if (steps >= maxSteps) {
            return "shot never finished dispatching within " + maxSteps + " ticks "
                    + "(nextFireBall=" + gb.getNextFireBallForTests() + ", ballRolling=" + gb.ballRolling() + ")";
        }

        int dispatched = gb.getNextFireBallForTests();
        float expectedX = gb.getDispatchedFirePosXForTests(0);
        float expectedDx = gb.getDispatchedFireSpeedXForTests(0);
        float expectedDy = gb.getDispatchedFireSpeedYForTests(0);

        for (int i = 1; i < dispatched; i++) {
            float x = gb.getDispatchedFirePosXForTests(i);
            float dx = gb.getDispatchedFireSpeedXForTests(i);
            float dy = gb.getDispatchedFireSpeedYForTests(i);
            if (x != expectedX || dx != expectedDx || dy != expectedDy) {
                return String.format(
                        "ball %d dispatched with (x=%.3f, dx=%.3f, dy=%.3f) but ball 0 got "
                                + "(x=%.3f, dx=%.3f, dy=%.3f)",
                        i, x, dx, dy, expectedX, expectedDx, expectedDy);
            }
        }
        return null;
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
        private boolean consumed;

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
            if (consumed) return Collections.emptyList();
            consumed = true;
            return Collections.singletonList(Bonus.EXTRA_BALLS);
        }
    }
}
