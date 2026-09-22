package com.jrgames.blockpong;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Covers the burger menu's "Level-Editor" grid/palette interaction rules (see LevelEditor.java):
// tap an empty cell to place the current selection, tap a differently-typed/valued block to
// replace it, tap a block that already matches the selection to delete it.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LevelEditorTest {

    private GameBoard geometry;
    private TestEditorCallbacks callbacks;
    private LevelEditor editor;

    @Before
    public void setUp() {
        geometry = new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
        callbacks = new TestEditorCallbacks();
        editor = new LevelEditor(geometry, callbacks);
        editor.openNew(1, false);
    }

    @Test
    public void tappingEmptyCell_placesSelectedShapeAndValue() {
        editor.handleTouch(midpointX(3), midpointY(2));

        Block b = editor.getBlockForTests(3, 2);
        assertNotNull(b);
        assertTrue(b instanceof Block4);
        assertEquals(editor.getSelectedValue(), b.getValue());
        assertTrue(editor.isDirty());
    }

    @Test
    public void tappingBlockWithDifferentSelection_replacesIt() {
        editor.handleTouch(midpointX(3), midpointY(2)); // places a square, value 5
        selectShape(LevelEditor.BlockShape.TL);
        editor.handleTouch(midpointX(3), midpointY(2)); // different shape -> replace

        Block b = editor.getBlockForTests(3, 2);
        assertNotNull(b);
        assertTrue(b instanceof Block3);
        assertEquals(Block3.tTriangle.TL, ((Block3) b).getType());
    }

    @Test
    public void tappingBlockMatchingSelectionExactly_deletesIt() {
        editor.handleTouch(midpointX(3), midpointY(2)); // places square, value 5 (default selection)
        assertNotNull(editor.getBlockForTests(3, 2));

        editor.handleTouch(midpointX(3), midpointY(2)); // same selection again -> delete
        assertNull(editor.getBlockForTests(3, 2));
    }

    @Test
    public void tappingBlockWithDifferentValueButSameShape_replacesNotDeletes() {
        editor.handleTouch(midpointX(3), midpointY(2)); // value 5 square
        incrementValue();
        editor.handleTouch(midpointX(3), midpointY(2)); // value 6 square -> replace, not delete

        Block b = editor.getBlockForTests(3, 2);
        assertNotNull(b);
        assertEquals(6, b.getValue());
    }

    @Test
    public void dragAcrossEmptyCells_paintsEachOneWithTheSelection() {
        editor.handleTouch(midpointX(2), midpointY(4)); // ACTION_DOWN starts a paint stroke
        editor.handleTouchMove(midpointX(3), midpointY(4));
        editor.handleTouchMove(midpointX(4), midpointY(4));

        for (int x = 2; x <= 4; x++) {
            Block b = editor.getBlockForTests(x, 4);
            assertNotNull("cell " + x + " should have been painted", b);
            assertTrue(b instanceof Block4);
            assertEquals(editor.getSelectedValue(), b.getValue());
        }
    }

    @Test
    public void dragStartingOnMatchingBlock_erasesEveryCellItCrosses_regardlessOfType() {
        editor.handleTouch(midpointX(2), midpointY(4)); // square, value 5
        selectShape(LevelEditor.BlockShape.TL);
        editor.handleTouch(midpointX(3), midpointY(4)); // different shape -> placed, not erased
        assertNotNull(editor.getBlockForTests(2, 4));
        assertNotNull(editor.getBlockForTests(3, 4));

        // Re-select the square/5 combo so tapping cell (2,4) again matches it exactly -> starts
        // an erase stroke -- which must then also clear (3,4)'s differently-typed block.
        selectShape(LevelEditor.BlockShape.SQUARE);
        editor.handleTouch(midpointX(2), midpointY(4));
        editor.handleTouchMove(midpointX(3), midpointY(4));

        assertNull(editor.getBlockForTests(2, 4));
        assertNull(editor.getBlockForTests(3, 4));
    }

    @Test
    public void dragBackOverAlreadyPaintedCell_leavesItInPlace() {
        editor.handleTouch(midpointX(0), midpointY(0));
        editor.handleTouchMove(midpointX(1), midpointY(0));
        editor.handleTouchMove(midpointX(0), midpointY(0)); // back onto the first cell

        // A paint stroke must not toggle/delete ground it already painted.
        assertNotNull(editor.getBlockForTests(0, 0));
    }

    @Test
    public void dragThatStartsOnAButton_doesNotPaintOnceItReachesTheGrid() {
        clickTestPlay(); // ACTION_DOWN on a button, not the grid -- must not arm a paint stroke
        editor.handleTouchMove(midpointX(0), midpointY(0));

        assertNull(editor.getBlockForTests(0, 0));
    }

    @Test
    public void tappingShapeSwatch_changesSelection() {
        selectShape(LevelEditor.BlockShape.BR);
        assertEquals(LevelEditor.BlockShape.BR, editor.getSelectedShape());
    }

    // Mini-Blöcke levels (Game.isMiniBlockLevelSlot()) stay square-only -- easier to reason about
    // collision without triangle geometry at that scale (see GameBoard's Mini-Blöcke fuzz coverage).
    @Test
    public void miniBlockLevel_shapePaletteStaysSquareOnly() {
        callbacks.miniBlockLevels.add(5);
        editor.openNew(5, false);
        assertEquals(LevelEditor.BlockShape.SQUARE, editor.getSelectedShape());

        selectShape(LevelEditor.BlockShape.TL); // tap on a disabled swatch -- must be ignored
        assertEquals(LevelEditor.BlockShape.SQUARE, editor.getSelectedShape());
    }

    // Mini-Blöcke levels use GameBoard's finer Mini-Blöcke grid (27 columns) instead of the normal
    // 11 -- placing a block at column 20 (out of range on the normal grid) must land there.
    @Test
    public void miniBlockLevel_usesFinerGrid() {
        callbacks.miniBlockLevels.add(5);
        editor.openNew(5, false);

        float miniCellSize = 660f / 27f; // square cells, matches GameBoard.MINI_X_DIM
        float x = miniCellSize * 20 + miniCellSize / 2f;
        float y = miniCellSize * 5 + miniCellSize / 2f;
        editor.handleTouch(x, y);

        Block b = editor.getBlockForTests(20, 5);
        assertNotNull(b);
        assertTrue(b instanceof Block4);
    }

    // Mini-Blöcke levels keep block values single-digit (see GameBoard.MINI_MAX_VALUE) so the
    // number painted on a block stays legible at this finer grid's smaller cell size.
    @Test
    public void miniBlockLevel_valueStepperCapsAtNine() {
        callbacks.miniBlockLevels.add(5);
        editor.openNew(5, false);

        for (int i = 0; i < 20; i++) incrementValue();
        assertEquals(9, editor.getSelectedValue());
    }

    @Test
    public void valueStepper_incrementsAndDecrementsAndClamps() {
        int initial = editor.getSelectedValue();
        incrementValue();
        assertEquals(initial + 1, editor.getSelectedValue());
        decrementValue();
        decrementValue();
        assertEquals(initial - 1, editor.getSelectedValue());

        // Clamp at the lower bound.
        for (int i = 0; i < 20; i++) decrementValue();
        assertTrue(editor.getSelectedValue() >= 1);
    }

    @Test
    public void allValuesButton_rescalesEveryPlacedBlockButNotTheSelection() {
        editor.handleTouch(midpointX(0), midpointY(0)); // square, value 5
        selectShape(LevelEditor.BlockShape.TR);
        editor.handleTouch(midpointX(1), midpointY(1)); // triangle, value 5
        int selectedBefore = editor.getSelectedValue();

        clickAllValuesPlus();

        assertEquals(6, editor.getBlockForTests(0, 0).getValue());
        assertEquals(6, editor.getBlockForTests(1, 1).getValue());
        assertEquals("the palette's own selectedValue is untouched by the bulk action",
                selectedBefore, editor.getSelectedValue());

        clickAllValuesMinus();
        clickAllValuesMinus();

        assertEquals(4, editor.getBlockForTests(0, 0).getValue());
        assertEquals(4, editor.getBlockForTests(1, 1).getValue());
    }

    @Test
    public void allValuesButton_clampsAtBounds() {
        editor.handleTouch(midpointX(0), midpointY(0)); // value 5
        for (int i = 0; i < 20; i++) clickAllValuesMinus();
        assertTrue(editor.getBlockForTests(0, 0).getValue() >= 1);

        for (int i = 0; i < 200; i++) clickAllValuesPlus();
        assertTrue(editor.getBlockForTests(0, 0).getValue() <= 99);
    }

    @Test
    public void toJsonAndLoadFromJson_roundTrip() {
        editor.handleTouch(midpointX(0), midpointY(0)); // square, value 5
        selectShape(LevelEditor.BlockShape.BR);
        editor.handleTouch(midpointX(4), midpointY(7));

        String json = editor.toJson();

        editor.openNew(2, false); // reset to a blank grid
        assertNull(editor.getBlockForTests(0, 0));

        assertTrue(editor.loadFromJson(json));
        assertNotNull(editor.getBlockForTests(0, 0));
        assertTrue(editor.getBlockForTests(0, 0) instanceof Block4);
        Block b = editor.getBlockForTests(4, 7);
        assertNotNull(b);
        assertTrue(b instanceof Block3);
        assertEquals(Block3.tTriangle.BR, ((Block3) b).getType());
    }

    @Test
    public void loadFromJson_malformed_returnsFalseAndLeavesToastFree() {
        assertFalse(editor.loadFromJson("not json"));
    }

    @Test
    public void save_appendMode_writesDirectlyToTargetLevel() {
        editor.openNew(7, false);
        editor.handleTouch(midpointX(0), midpointY(0));

        clickSave();

        assertEquals(1, callbacks.savedLevels.size());
        assertEquals(Integer.valueOf(7), callbacks.savedLevels.get(0));
        assertTrue(callbacks.insertCalls.isEmpty());
        assertFalse(editor.isDirty());
    }

    @Test
    public void save_insertMode_callsInsertOnceThenBehavesLikeEdit() {
        editor.openNew(4, true);
        editor.handleTouch(midpointX(0), midpointY(0));

        clickSave();
        assertEquals(1, callbacks.insertCalls.size());
        assertEquals(Integer.valueOf(4), callbacks.insertCalls.get(0));
        assertEquals(LevelEditor.Mode.EDIT_EXISTING, editor.getMode());

        // Saving again must not shift a second time.
        editor.handleTouch(midpointX(1), midpointY(1));
        clickSave();
        assertEquals(1, callbacks.insertCalls.size());
        assertEquals(1, callbacks.savedLevels.size());
    }

    @Test
    public void export_copiesCurrentJsonToClipboard() {
        editor.handleTouch(midpointX(0), midpointY(0));
        clickExport();

        assertEquals(1, callbacks.exportedJson.size());
        assertTrue(callbacks.exportedJson.get(0).contains("\"x\":0"));
    }

    @Test
    public void testPlayButton_startsTestPlayWithCurrentJsonAndTargetLevel_evenWhileUnsaved() {
        editor.openNew(9, false);
        editor.handleTouch(midpointX(0), midpointY(0));

        clickTestPlay();

        assertEquals(1, callbacks.testPlayJson.size());
        assertTrue(callbacks.testPlayJson.get(0).contains("\"x\":0"));
        assertEquals(Integer.valueOf(9), callbacks.testPlayLevels.get(0));
        // Unlike Speichern, Probespielen doesn't clear the dirty flag or persist anything -- it's
        // just handing the live in-memory layout off for a trial run.
        assertTrue(editor.isDirty());
        assertTrue(callbacks.savedLevels.isEmpty());
    }

    @Test
    public void closeButton_invokesCloseCallback() {
        clickClose();
        assertTrue(callbacks.closed);
    }

    // ---- helpers ----

    private void selectShape(LevelEditor.BlockShape shape) {
        LevelEditor.BlockShape[] shapes = LevelEditor.BlockShape.values();
        int index = -1;
        for (int i = 0; i < shapes.length; i++) {
            if (shapes[i] == shape) index = i;
        }
        android.graphics.RectF r = editor.shapeSwatchRect(index);
        editor.handleTouch(r.centerX(), r.centerY());
    }

    private void incrementValue() {
        android.graphics.RectF r = editor.stepperPlusRect();
        editor.handleTouch(r.centerX(), r.centerY());
    }

    private void decrementValue() {
        android.graphics.RectF r = editor.stepperMinusRect();
        editor.handleTouch(r.centerX(), r.centerY());
    }

    private void clickAllValuesPlus() {
        android.graphics.RectF r = editor.allValuesButtonRect(1);
        editor.handleTouch(r.centerX(), r.centerY());
    }

    private void clickAllValuesMinus() {
        android.graphics.RectF r = editor.allValuesButtonRect(0);
        editor.handleTouch(r.centerX(), r.centerY());
    }

    private void clickSave() {
        android.graphics.RectF r = editor.actionButtonRect(0);
        editor.handleTouch(r.centerX(), r.centerY());
    }

    private void clickExport() {
        android.graphics.RectF r = editor.actionButtonRect(1);
        editor.handleTouch(r.centerX(), r.centerY());
    }

    private void clickTestPlay() {
        android.graphics.RectF r = editor.actionButtonRect(2);
        editor.handleTouch(r.centerX(), r.centerY());
    }

    private void clickClose() {
        android.graphics.RectF r = editor.actionButtonRect(3);
        editor.handleTouch(r.centerX(), r.centerY());
    }

    // Real cell-center pixel coordinates from the same GameBoard geometry the editor was built
    // with (see GameBoard.getBlockX/Y()/getBlockWidth/Height()) -- LevelEditor.cellXAt/cellYAt()
    // do the inverse scan of these exact values, so this must not assume a fixed cell size.
    private float midpointX(int cellX) {
        return geometry.getBlockX(cellX) + geometry.getBlockWidth() / 2f;
    }

    private float midpointY(int cellY) {
        return geometry.getBlockY(cellY) + geometry.getBlockHeight() / 2f;
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
        @Override public List<Bonus> consumeArmedBonuses() { return Collections.emptyList(); }
    }

    private static final class TestEditorCallbacks implements LevelEditor.EditorCallbacks {
        final List<Integer> savedLevels = new ArrayList<>();
        final List<Integer> insertCalls = new ArrayList<>();
        final List<String> exportedJson = new ArrayList<>();
        final List<String> testPlayJson = new ArrayList<>();
        final List<Integer> testPlayLevels = new ArrayList<>();
        final java.util.Set<Integer> miniBlockLevels = new java.util.HashSet<>();
        boolean closed;

        @Override public List<Integer> listKnownLevels() { return Collections.emptyList(); }
        @Override public String loadLevelJsonForEdit(int level) { return null; }
        @Override public void saveLevelJson(int level, String json) { savedLevels.add(level); }
        @Override public void insertLevelWithShift(int atLevel, String json) { insertCalls.add(atLevel); }
        @Override public void exportJsonToClipboard(String json) { exportedJson.add(json); }
        @Override public void showToast(String message) {}
        @Override public void closeEditor() { closed = true; }
        @Override public void startTestPlay(String json, int targetLevel) {
            testPlayJson.add(json);
            testPlayLevels.add(targetLevel);
        }
        @Override public boolean isMiniBlockLevel(int level) { return miniBlockLevels.contains(level); }
    }
}
