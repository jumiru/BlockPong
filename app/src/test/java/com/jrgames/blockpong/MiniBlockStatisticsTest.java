package com.jrgames.blockpong;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

// Covers Game.recordShotStatistics()'s Mini-Blöcke vs. normal pool split: a shot's ballsUsed/
// blocksCleared/blocksHit tally must land in miniShotHistograms/miniHitHistograms when it was
// played on a Mini-Blöcke level (see Game.isMiniBlockLevel()/isMiniBlockLevelSlot()), and in the
// normal shotHistograms/hitHistograms pool otherwise -- never both, never mixed. Exercised via
// Game.onRoundEnd() directly (gameBoard stays null; onRoundEnd() falls back to using the passed-in
// blocksCleared as the hit count in that case, which is fine here since only routing is under
// test).
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MiniBlockStatisticsTest {

    private Game newGame() {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("test_prefs_mini_block_stats", Context.MODE_PRIVATE);
        return new Game(context, prefs);
    }

    @Test
    public void normalLevelShot_recordedInNormalPoolOnly() {
        Game game = newGame();
        game.setLevelForTests(3); // not a multiple of 5 -- normal level

        game.onRoundEnd(12, 10);

        assertEquals("normal-level shot must be tallied in the normal pool",
                1, game.getShotCountForTests(false, 10));
        assertEquals("normal-level shot must NOT also land in the Mini-Blöcke pool",
                0, game.getShotCountForTests(true, 10));
    }

    @Test
    public void miniBlockLevelShot_recordedInMiniPoolOnly() {
        Game game = newGame();
        game.setLevelForTests(15); // %5==0, %10!=0 -- a Mini-Blöcke slot (see Game.isMiniBlockLevelSlot())

        game.onRoundEnd(7, 20);

        assertEquals("Mini-Blöcke shot must NOT land in the normal pool",
                0, game.getShotCountForTests(false, 20));
        assertEquals("Mini-Blöcke shot must be tallied in the Mini-Blöcke pool",
                1, game.getShotCountForTests(true, 20));
    }

    @Test
    public void randomLevelSlotShot_stillCountsAsNormalNotMini() {
        // Every 10th level is a Zufalls-Level (see Game.isRandomLevelSlot()) -- also %5==0, so this
        // guards against isMiniBlockLevelSlot() accidentally matching it too (it explicitly
        // excludes level % 10 == 0).
        Game game = newGame();
        game.setLevelForTests(10);

        game.onRoundEnd(5, 10);

        assertEquals("a random-level shot (level % 10 == 0) is not a Mini-Blöcke level",
                1, game.getShotCountForTests(false, 10));
        assertEquals(0, game.getShotCountForTests(true, 10));
    }

    @Test
    public void multipleShotsInSameBucket_accumulateRatherThanOverwrite() {
        Game game = newGame();
        game.setLevelForTests(25);

        game.onRoundEnd(3, 20);
        game.onRoundEnd(8, 20);
        game.onRoundEnd(3, 20);

        assertEquals("three shots with the same ballsUsed should all be tallied, not overwrite each other",
                3, game.getShotCountForTests(true, 20));
    }
}
