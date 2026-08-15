package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

// Regression test: Block3(Block3) used to forget to copy textX/textY (the cached position of the
// value label, computed once in the main constructor from the triangle's corner). Every snapshot
// taken via GameBoard.copyBlocksGrid() -- the replay recording, the "vor"/"nach dem Zug" debug
// export's board state, and the freeze/debug board copy -- goes through this copy constructor, so
// the bug made every triangle's value text draw at (0, offset) instead of inside the triangle,
// while square blocks (Block4, which computes its text position live from its copied rect) were
// unaffected. Reported as "the replay doesn't show block values".
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class Block3CopyConstructorTest {

    @Test
    public void copyPreservesTextPosition() {
        GameBoard gb = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        gb.clearBoardForTests();
        gb.placeTriangleBlockForTests(3, 2, Block3.tTriangle.TR, 7);
        Block3 original = (Block3) gb.getBlockForTests(3, 2);

        Block3 copy = new Block3(original);

        assertEquals(original.getTextXForTests(), copy.getTextXForTests(), 0.001f);
        assertEquals(original.getTextYForTests(), copy.getTextYForTests(), 0.001f);
    }

    private static final class TestGameCallbacks implements GameBoard.GameCallbacks {
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
        @Override public java.util.List<Bonus> consumeArmedBonuses() { return java.util.Collections.emptyList(); }
    }
}
