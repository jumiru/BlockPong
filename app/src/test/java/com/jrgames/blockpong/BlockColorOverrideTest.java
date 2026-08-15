package com.jrgames.blockpong;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Covers the optional per-block "color" field (see GameBoard.loadBlocksFromJson()/
// exportBlocksJson() and Block.overrideColor): a level file can decouple a block's look from its
// point value by giving it an explicit "#RRGGBB", independent of tools/level_editor.py's mirrored
// support for the same field.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BlockColorOverrideTest {

    private GameBoard newBoard() {
        return new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
    }

    @Test
    public void explicitColorWinsOverTheValueDerivedOne() {
        GameBoard gb = newBoard();
        gb.clearBoardForTests();
        String json = "{\"blocks\":[{\"x\":2,\"y\":3,\"value\":6,\"type\":\"square\",\"color\":\"#3399FF\"}]}";
        gb.importAndReplayForDebug(json, 100f, 30f, -40f, 1);

        Block b = gb.getBlockForTests(2, 3);
        assertEquals(Integer.valueOf(Color.parseColor("#3399FF")), b.getOverrideColor());
        assertEquals(Color.parseColor("#3399FF"), invokeGetRectColor(b));
    }

    @Test
    public void withoutColorFieldItStillDerivesFromValueAsBefore() {
        GameBoard gb = newBoard();
        gb.clearBoardForTests();
        String json = "{\"blocks\":[{\"x\":2,\"y\":3,\"value\":6,\"type\":\"square\"}]}";
        gb.importAndReplayForDebug(json, 100f, 30f, -40f, 1);

        Block b = gb.getBlockForTests(2, 3);
        assertNull("no color field means no override", b.getOverrideColor());
    }

    @Test
    public void exportRoundTripsTheColorField() {
        GameBoard gb = newBoard();
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(4, 4, 9);
        // placeSquareBlockForTests() has no color parameter, so this block's export must NOT
        // carry a "color" key -- only blocks with an explicit override should.
        String noColorExport = gb.exportBlocksJson();
        assertTrue("plain block export must omit \"color\"", !noColorExport.contains("\"color\""));

        String json = "{\"blocks\":[{\"x\":1,\"y\":1,\"value\":2,\"type\":\"tl\",\"color\":\"#FF00FF\"}]}";
        gb.importAndReplayForDebug(json, 100f, 30f, -40f, 1);
        String exported = gb.exportBlocksJson();
        assertTrue("re-exported JSON must keep the color override", exported.contains("\"color\":\"#FF00FF\""));
    }

    // getRectColorFromValue() is protected -- reflection keeps this test in the same package
    // without widening the production API just for a test assertion.
    private static int invokeGetRectColor(Block b) {
        try {
            java.lang.reflect.Method m = Block.class.getDeclaredMethod("getRectColorFromValue");
            m.setAccessible(true);
            return (int) m.invoke(b);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
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
