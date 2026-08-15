package com.jrgames.blockpong;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Covers the burger menu's "Import & Replay (Debug)" text pipeline (Game.importAndReplayDebugText()
// and friends) -- the parsing/dialog layer above GameBoard.importAndReplayForDebug() (already
// covered by GameBoardImportReplayTest).
//
// Investigates a reported bug: pasting a debug report and tapping "Abspielen" closed the dialog
// looking successful, but the board showed an unrelated layout and sometimes no shot ever fired.
// Root cause: GameBoard.loadBlocksFromJson() silently falls back to randomBoard() on any JSON
// parse failure (so a broken predefined level file can't leave the board empty -- see its
// comment), but that same fallback fired silently for the debug-import path too, with no way for
// the caller to tell a real import from the fallback. Fixed by having
// loadBlocksFromJson()/restoreBlocksFromJson()/importAndReplayForDebug() report success/failure,
// and Game.importAndReplayDebugText() surface a Toast error instead of pretending it worked.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ImportReplayDebugTextTest {

    private Game newGameWithBoard(GameBoard[] out) throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE);
        Game game = new Game(context, prefs);

        GameBoard gb = new GameBoard(game, 660f, 900f, 0f, 0f);
        Field gbField = Game.class.getDeclaredField("gameBoard");
        gbField.setAccessible(true);
        gbField.set(game, gb);
        out[0] = gb;
        return game;
    }

    private String invokeImport(Game game, String text) throws Exception {
        Method importMethod = Game.class.getDeclaredMethod("importAndReplayDebugText", String.class);
        importMethod.setAccessible(true);
        return (String) importMethod.invoke(game, text);
    }

    @Test
    public void realExportedReport_roundTripsThroughGameImportPipeline() throws Exception {
        GameBoard[] gbHolder = new GameBoard[1];
        Game game = newGameWithBoard(gbHolder);
        GameBoard gb = gbHolder[0];

        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(3, 2, 4);

        float firePosY = gb.getFirePosYForTests();
        float startX = gb.getFirePosXForTests();
        gb.touchDown(startX, firePosY);
        gb.touchRelease(startX + 80f, firePosY - 400f);

        for (int i = 0; i < 200 && !gb.hasLastMoveRecording(); i++) {
            gb.update();
        }
        assertTrue(gb.hasLastMoveRecording());

        String report = gb.getLastMoveReport();
        assertNotNull(report);

        String error = invokeImport(game, report);
        assertNull("import of a real just-produced report should not error", error);

        for (int i = 0; i < 10; i++) gb.update();
        assertTrue("importing should have started a shot that's now rolling", gb.ballRolling());
    }

    @Test
    public void mangledBoardJson_reportsErrorInsteadOfSilentlySubstitutingARandomBoard() throws Exception {
        GameBoard[] gbHolder = new GameBoard[1];
        Game game = newGameWithBoard(gbHolder);
        GameBoard gb = gbHolder[0];
        gb.clearBoardForTests();
        gb.placeSquareBlockForTests(1, 1, 7);

        // Simulates text mangled by round-tripping through an email client (e.g. smart-quoted
        // apostrophes/quotes replacing the plain ASCII '"' JSON needs) -- extractBoardJson() still
        // finds a {"blocks": ... } looking span, but it no longer parses as JSON.
        String mangled = "BlockPong - Letzter Zug (Debug-Export)\n"
                + "Baelle: 3\n"
                + "Startpunkt x: 100.0\n"
                + "Wurfrichtung (dx,dy): 10.0, -40.0\n"
                + "Spielfeld vor dem Zug (JSON, kompatibel mit tools/level_editor.py):\n"
                + "{“blocks”:[{“x”:1,“y”:1,“value”:7,“type”:“square”}]}\n";

        String error = invokeImport(game, mangled);
        assertNotNull("malformed board JSON must be reported, not silently swapped for a random board", error);

        // The block placed before the (failed) import must not have been replaced by fire()
        // continuing on with an unrelated random board.
        assertFalse("no shot should have started when the import failed", gb.ballRolling());
    }
}
