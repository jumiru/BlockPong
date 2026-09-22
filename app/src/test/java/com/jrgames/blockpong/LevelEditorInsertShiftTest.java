package com.jrgames.blockpong;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// Covers Game's LevelEditor.EditorCallbacks implementation for "insert at position N, shifting
// every later level down by one" (see Game.insertLevelWithShift(), triggered by LevelEditor's
// Mode.INSERT on save). Persistence itself is app-internal storage (Game.levelOverrideFile()) since
// a running app can't write into its own assets/ -- these tests exercise that storage layer
// directly through Game's public loadLevelJson()/saveLevelJson()/listKnownLevels(), which is also
// exactly what GameBoard.initBoard() goes through for live gameplay.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LevelEditorInsertShiftTest {

    private Game newGame() {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("test_prefs_insert_shift", Context.MODE_PRIVATE);
        return new Game(context, prefs);
    }

    // Levels chosen (6/7/8/9) deliberately avoid any multiple of 5 -- those are reserved
    // Zufalls-/Mini-Blöcke slots (see Game.isReservedLevelSlot()) that insertLevelWithShift() must
    // never shift real content onto (covered separately by insertShift_skipsOverRandomLevelSlot()).
    @Test
    public void insertAtOccupiedPosition_shiftsLaterLevelsDownByOne() {
        Game game = newGame();
        String json6 = level("six");
        String json7 = level("seven");
        String json8 = level("eight");
        game.saveLevelJson(6, json6);
        game.saveLevelJson(7, json7);
        game.saveLevelJson(8, json8);

        String newJson = level("inserted");
        game.insertLevelWithShift(7, newJson);

        assertEquals("level below the insertion point is untouched", json6, loaded(game, 6));
        assertEquals("new content lands exactly at the insertion point", newJson, loaded(game, 7));
        assertEquals("old level 7 moved to 8", json7, loaded(game, 8));
        assertEquals("old level 8 moved to 9", json8, loaded(game, 9));
    }

    @Test
    public void insertAtOccupiedPosition_updatesKnownLevelList() {
        Game game = newGame();
        game.saveLevelJson(6, level("six"));
        game.saveLevelJson(7, level("seven"));

        game.insertLevelWithShift(7, level("inserted"));

        List<Integer> known = game.listKnownLevels();
        assertTrue("level 8 (shifted from 7) must now be known", known.contains(8));
        assertTrue("level 7 (the new content) must be known", known.contains(7));
        assertTrue("level 6 (untouched) must still be known", known.contains(6));
    }

    @Test
    public void insertBeyondKnownLevels_isJustAWriteNoShift() {
        Game game = newGame();
        game.saveLevelJson(1, level("one"));

        String newJson = level("far-future");
        game.insertLevelWithShift(11, newJson);

        assertEquals(newJson, loaded(game, 11));
        assertEquals("unrelated existing level is untouched", level("one"), loaded(game, 1));
    }

    // Every 10th level is reserved for GameBoard's random generator (see Game.isRandomLevelSlot())
    // and must never end up holding real content -- neither as the insertion target itself...
    @Test
    public void insertAtRandomLevelSlot_isRejected() {
        Game game = newGame();
        game.saveLevelJson(9, level("nine"));

        game.insertLevelWithShift(10, level("should-not-land"));

        assertEquals(null, loaded(game, 10));
        assertEquals("untouched, since the insert was rejected", level("nine"), loaded(game, 9));
    }

    // ...nor as a slot a shift passes through: inserting at 9 pushes the existing 9 and 11 up by
    // one each, but must skip straight over 10 rather than parking one of them there.
    @Test
    public void insertShift_skipsOverRandomLevelSlot() {
        Game game = newGame();
        game.saveLevelJson(9, level("nine"));
        game.saveLevelJson(11, level("eleven"));

        game.insertLevelWithShift(9, level("inserted"));

        assertEquals(level("inserted"), loaded(game, 9));
        assertEquals(null, loaded(game, 10));
        assertEquals("old level 9 skipped the reserved slot 10 and landed on 11", level("nine"), loaded(game, 11));
        assertEquals("old level 11 moved to 12", level("eleven"), loaded(game, 12));
    }

    // Unlike a Zufalls-Level slot, a Mini-Blöcke slot (5, 15, 25, ... -- see
    // Game.isMiniBlockLevelSlot()) is a perfectly normal, editable level: GameBoard just applies
    // its finer grid/smaller ball there (see GameCallbacks.isMiniBlockLevel()). It must be a valid
    // insertion target, and a shift must land ON it rather than skip over it like a reserved slot.
    @Test
    public void insertAtMiniBlockLevelSlot_isAccepted() {
        Game game = newGame();
        game.saveLevelJson(14, level("fourteen"));

        String newJson = level("mini-content");
        game.insertLevelWithShift(15, newJson);

        assertEquals(newJson, loaded(game, 15));
        assertEquals("untouched, insertion point is below it", level("fourteen"), loaded(game, 14));
    }

    @Test
    public void insertShift_doesNotSkipMiniBlockLevelSlot() {
        Game game = newGame();
        game.saveLevelJson(14, level("fourteen"));
        game.saveLevelJson(15, level("fifteen"));

        game.insertLevelWithShift(14, level("inserted"));

        assertEquals(level("inserted"), loaded(game, 14));
        assertEquals("old level 14 shifted straight onto the Mini-Blöcke slot 15, not skipped",
                level("fourteen"), loaded(game, 15));
        assertEquals("old level 15 moved to 16", level("fifteen"), loaded(game, 16));
    }

    @Test
    public void saveLevelJson_isImmediatelyPlayableViaLoadLevelJson() {
        Game game = newGame();
        String json = level("edited");
        game.saveLevelJson(9, json);

        assertEquals(json, loaded(game, 9));
    }

    @Test
    public void exportAllLevels_copiesEveryKnownLevelToClipboard() throws Exception {
        Game game = newGame();
        String json2 = level("two");
        String json6 = level("six");
        game.saveLevelJson(2, json2);
        game.saveLevelJson(6, json6);

        Method exportAll = Game.class.getDeclaredMethod("exportAllLevels", List.class);
        exportAll.setAccessible(true);
        exportAll.invoke(game, Arrays.asList(2, 6));

        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        String clipped = clipboard.getPrimaryClip().getItemAt(0).getText().toString();

        assertTrue("clip must label level 2's section", clipped.contains("Level 2 ("));
        assertTrue("clip must include level 2's content", clipped.contains(json2));
        assertTrue("clip must label level 6's section", clipped.contains("Level 6 ("));
        assertTrue("clip must include level 6's content", clipped.contains(json6));
    }

    private String level(String tag) {
        return "{\"blocks\":[{\"x\":0,\"y\":0,\"value\":1,\"type\":\"square\",\"tag\":\"" + tag + "\"}]}";
    }

    // loadLevelJson() (both the override and the plain assets path) reads the file line-by-line and
    // appends '\n' after every line (see Game.readLevelOverride()/loadLevelJson()), so a written
    // single-line JSON comes back with a trailing newline -- harmless for org.json's parser, which
    // ignores trailing whitespace, but this needs normalizing for exact-string comparisons here.
    private String loaded(Game game, int level) {
        String s = game.loadLevelJson(level);
        return s == null ? null : s.trim();
    }
}
