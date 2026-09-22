package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Reported bug: the LINE_DELETE bonus didn't always delete a row -- especially with blocks only
// right in front of the game-over line, since the bottom two rows were never candidates.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LineDeleteBonusTest {

    @Test
    public void deletesRowDirectlyAboveGameOverLine() {
        runDeleteWithOnlyBlockIn(16);
    }

    @Test
    public void deletesGameOverRow() {
        runDeleteWithOnlyBlockIn(17);
    }

    @Test
    public void rowPickIsWeightedByBlockCount() {
        GameBoard gb = new GameBoard(new CapturingCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        // Row 2: 1 block, row 5: 3 blocks -> r=0 hits row 2, r=1..3 hit row 5 (25% vs 75%).
        gb.placeSquareBlockForTests(4, 2, 7);
        gb.placeSquareBlockForTests(0, 5, 1);
        gb.placeSquareBlockForTests(1, 5, 1);
        gb.placeSquareBlockForTests(2, 5, 1);

        assertEquals(2, gb.pickLineDeleteRow(0));
        assertEquals(5, gb.pickLineDeleteRow(1));
        assertEquals(5, gb.pickLineDeleteRow(2));
        assertEquals(5, gb.pickLineDeleteRow(3));
        assertEquals(-1, gb.pickLineDeleteRow(4));
    }

    @Test
    public void emptyBoard_reportsNothingDeleted() {
        CapturingCallbacks callbacks = new CapturingCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        assertFalse("empty board: caller must not spend the charge", gb.triggerLineDeleteBonus());
        assertTrue(callbacks.animations.isEmpty());
    }

    private static void runDeleteWithOnlyBlockIn(int row) {
        CapturingCallbacks callbacks = new CapturingCallbacks();
        GameBoard gb = new GameBoard(callbacks, 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(3, row, 5);

        assertTrue("a row should be deleted", gb.triggerLineDeleteBonus());
        assertFalse(callbacks.animations.isEmpty());
        Animation a = callbacks.animations.get(0);
        callbacks.animations.clear();
        while (!a.update()) { /* run to completion */ }

        assertNull("block in row " + row + " should be gone", gb.getBlockForTests(3, row));
        assertNull("nothing should have slid into row " + (row - 1), gb.getBlockForTests(3, row - 1));
    }

    private static final class CapturingCallbacks implements GameBoard.GameCallbacks {
        final List<Animation> animations = new ArrayList<>();

        @Override public int getLevel() { return 1; }
        @Override public void addAnimation(Animation a) { animations.add(a); }
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
