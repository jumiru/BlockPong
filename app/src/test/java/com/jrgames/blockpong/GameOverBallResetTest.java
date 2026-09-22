package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Reported bugs: after game over with 20 balls (EXTRA_BALLS) the new game's launch HUD showed
// "x20" (or sometimes a negative "x-4") instead of "x10", and balls brought back via the
// swipe-down recall were never counted as returned. See GameBoard.ballsRemainingToFire().
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GameOverBallResetTest {

    @Test
    public void newGameAfterGameOverWithExtraBalls_startsWithInitialBallCount() throws Exception {
        TestGameCallbacks callbacks = new TestGameCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(0, 17, 9);
        gb.placeSquareBlockForTests(5, 2, 4);

        fireExtraBallsShot(gb, callbacks);

        int steps = 0;
        while (!callbacks.gameOverCalled && steps < 5000) {
            gb.update();
            steps++;
        }
        assertTrue(callbacks.gameOverCalled);

        // What Game.onTouchEvent() does on the game-over tap.
        callbacks.gameOverCalled = false;
        gb.initBoard();

        assertEquals("numBalls after new game", 10, intField(gb, "numBalls"));
        assertEquals("launch HUD after new game", 10, hud(gb));
    }

    @Test
    public void swipeDownRecall_countsRecalledBallsAsBackAtLaunchPoint() throws Exception {
        TestGameCallbacks callbacks = new TestGameCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(5, 2, 4);

        fireExtraBallsShot(gb, callbacks);
        // Let every ball get dispatched and fly for a bit, but not return yet.
        for (int i = 0; i < 120; i++) gb.update();
        assertTrue(gb.ballRolling());
        assertTrue("some balls should be in flight", hud(gb) < 20);

        // Swipe-down recall gesture (see GameBoard.touchRelease()).
        float x = gb.getFirePosXForTests();
        gb.touchDown(x, 100f);
        gb.touchRelease(x, 400f);
        assertFalse("recall should have started drop animations", callbacks.animations.isEmpty());
        for (Animation a : callbacks.animations) {
            while (!a.update()) { /* run to completion */ }
        }

        assertEquals("all recalled balls should count as back", 20, hud(gb));
    }

    private static void fireExtraBallsShot(GameBoard gb, TestGameCallbacks callbacks) throws Exception {
        callbacks.armExtraBalls = true;
        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + 5f, firePosY - 400f);
        assertEquals(20, intField(gb, "numBalls"));
    }

    private static int hud(GameBoard gb) throws Exception {
        Method m = GameBoard.class.getDeclaredMethod("ballsRemainingToFire");
        m.setAccessible(true);
        return (Integer) m.invoke(gb);
    }

    private static int intField(Object o, String name) throws Exception {
        Field f = GameBoard.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(o);
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
        boolean gameOverCalled;
        boolean armExtraBalls;
        final List<Animation> animations = new ArrayList<>();

        @Override public int getLevel() { return 1; }
        @Override public void addAnimation(Animation a) { animations.add(a); }
        @Override public void increaselevel() {}
        @Override public void setGameOver(boolean win) { gameOverCalled = true; }
        @Override public boolean isGameOver() { return gameOverCalled; }
        @Override public void resetGameOver() { gameOverCalled = false; }
        @Override public void addScore(int points) {}
        @Override public String loadLevelJson(int level) { return null; }
        @Override public void onRoundEnd(int blocksCleared, int ballsUsed) {}
        @Override public boolean isBonusArmed(Bonus bonus) { return false; }
        @Override public List<Bonus> consumeArmedBonuses() {
            if (!armExtraBalls) return Collections.emptyList();
            armExtraBalls = false;
            List<Bonus> l = new ArrayList<>();
            l.add(Bonus.EXTRA_BALLS);
            return l;
        }
    }
}
