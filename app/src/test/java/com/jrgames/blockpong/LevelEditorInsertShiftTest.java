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

    @Test
    public void insertAtOccupiedPosition_shiftsLaterLevelsDownByOne() {
        Game game = newGame();
        String json3 = level("three");
        String json4 = level("four");
        String json5 = level("five");
        game.saveLevelJson(3, json3);
        game.saveLevelJson(4, json4);
        game.saveLevelJson(5, json5);

        String newJson = level("inserted");
        game.insertLevelWithShift(4, newJson);

        assertEquals("level below the insertion point is untouched", json3, loaded(game, 3));
        assertEquals("new content lands exactly at the insertion point", newJson, loaded(game, 4));
        assertEquals("old level 4 moved to 5", json4, loaded(game, 5));
        assertEquals("old level 5 moved to 6", json5, loaded(game, 6));
    }

    @Test
    public void insertAtOccupiedPosition_updatesKnownLevelList() {
        Game game = newGame();
        game.saveLevelJson(3, level("three"));
        game.saveLevelJson(4, level("four"));

        game.insertLevelWithShift(4, level("inserted"));

        List<Integer> known = game.listKnownLevels();
        assertTrue("level 5 (shifted from 4) must now be known", known.contains(5));
        assertTrue("level 4 (the new content) must be known", known.contains(4));
        assertTrue("level 3 (untouched) must still be known", known.contains(3));
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
        String json5 = level("five");
        game.saveLevelJson(2, json2);
        game.saveLevelJson(5, json5);

        Method exportAll = Game.class.getDeclaredMethod("exportAllLevels", List.class);
        exportAll.setAccessible(true);
        exportAll.invoke(game, Arrays.asList(2, 5));

        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        String clipped = clipboard.getPrimaryClip().getItemAt(0).getText().toString();

        assertTrue("clip must label level 2's section", clipped.contains("Level 2 ("));
        assertTrue("clip must include level 2's content", clipped.contains(json2));
        assertTrue("clip must label level 5's section", clipped.contains("Level 5 ("));
        assertTrue("clip must include level 5's content", clipped.contains(json5));
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
