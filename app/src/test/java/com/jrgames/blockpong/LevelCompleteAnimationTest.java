package com.jrgames.blockpong;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Covers the level-complete curtain transition (see LevelCompleteAnimation): the board swap
// (game.increaselevel()/gb.initBoard()) must happen exactly once, exactly when the curtain panels
// are fully closed -- not before (would reveal the swap) and not late (would leave the old, now
// score-stale board visible for extra frames).
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LevelCompleteAnimationTest {

    private static final int CLOSE_DURATION = 80;
    private static final int SETTLE_DURATION = 50;
    private static final int OPEN_DURATION = 70;

    @Test
    public void boardSwap_happensExactlyOnceRightWhenClosingFinishes() {
        TrackingCallbacks callbacks = new TrackingCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        callbacks.loadLevelJsonCalls = 0; // GameBoard's own constructor already calls initBoard() once
        LevelCompleteAnimation anim = new LevelCompleteAnimation(gb);

        for (int i = 0; i < CLOSE_DURATION - 1; i++) {
            anim.update();
            assertEquals("must not swap before the curtain is fully closed", 0, callbacks.increaseLevelCalls);
        }

        anim.update(); // the CLOSE_DURATION-th tick -- curtain just finished closing
        assertEquals("must swap exactly when closing finishes", 1, callbacks.increaseLevelCalls);
        // gb.initBoard() (which the animation calls alongside increaselevel()) isn't itself part
        // of GameCallbacks -- loadLevelJson() being called back into is proof initBoard() ran,
        // since that's the only place GameBoard calls it (see GameBoard.initBoard()).
        assertEquals(1, callbacks.loadLevelJsonCalls);

        // Must not swap again during SETTLING/OPENING.
        for (int i = 0; i < SETTLE_DURATION + OPEN_DURATION; i++) {
            anim.update();
        }
        assertEquals(1, callbacks.increaseLevelCalls);
        assertEquals(1, callbacks.loadLevelJsonCalls);
    }

    @Test
    public void update_returnsTrueOnlyAfterFullDuration() {
        TrackingCallbacks callbacks = new TrackingCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        LevelCompleteAnimation anim = new LevelCompleteAnimation(gb);

        int total = CLOSE_DURATION + SETTLE_DURATION + OPEN_DURATION;
        for (int i = 0; i < total - 1; i++) {
            assertFalse("animation must not report finished before its full duration", anim.update());
        }
        assertTrue("animation must report finished on its final tick", anim.update());
    }

    @Test
    public void draw_doesNotThrowAcrossAllThreePhases() {
        TrackingCallbacks callbacks = new TrackingCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        LevelCompleteAnimation anim = new LevelCompleteAnimation(gb);
        Canvas canvas = new Canvas(Bitmap.createBitmap(660, 900, Bitmap.Config.ARGB_8888));

        int total = CLOSE_DURATION + SETTLE_DURATION + OPEN_DURATION;
        for (int i = 0; i < total; i++) {
            anim.draw(canvas); // must not throw in any phase
            anim.update();
        }
        anim.draw(canvas);
    }

    private static final class TrackingCallbacks implements GameBoard.GameCallbacks {
        int increaseLevelCalls;
        int loadLevelJsonCalls;

        @Override public int getLevel() { return 1; }
        @Override public void addAnimation(Animation a) {}
        @Override public void increaselevel() { increaseLevelCalls++; }
        @Override public void setGameOver(boolean win) {}
        @Override public boolean isGameOver() { return false; }
        @Override public void resetGameOver() {}
        @Override public void addScore(int points) {}
        @Override public String loadLevelJson(int level) { loadLevelJsonCalls++; return null; }
        @Override public void onRoundEnd(int blocksCleared, int ballsUsed) {}
        @Override public boolean isBonusArmed(Bonus bonus) { return false; }
        @Override public List<Bonus> consumeArmedBonuses() { return Collections.emptyList(); }
    }
}
