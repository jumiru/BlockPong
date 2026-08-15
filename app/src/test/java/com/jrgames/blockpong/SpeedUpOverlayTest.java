package com.jrgames.blockpong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Covers the "Speed-up" overlay (see GameBoard.shouldOfferSpeedUp()/isSpeedUpButtonHit()/
// fastForwardToNextBlockHit() and the collisionsSinceLastBlockHit streak recordCollision()
// maintains): a button offering to fast-forward the balls once they've bounced
// SPEEDUP_TRIGGER_COLLISIONS times in a row without hitting a block.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpeedUpOverlayTest {

    private GameBoard newBoard() {
        return new GameBoard(new TestGameCallbacks(), 660f, 900f, 0f, 0f);
    }

    @Test
    public void offeredOnlyAtOrAboveTheThresholdWhileBallsAreRolling() {
        GameBoard gb = newBoard();
        gb.clearBoardForTests();
        // Straight up, no blocks anywhere -- keeps the balls rolling for a while without ever
        // resetting the streak via a real hit, so the seeded counter below is the only thing
        // driving shouldOfferSpeedUp().
        gb.importAndReplayForDebug("{\"blocks\":[]}", gb.getFirePosXForTests(), 0f, -50f, 1);
        // Dispatch happens inside update() itself, on its first tick -- right after import the
        // ball still reads as "still" (see computeExtendedPathPoints()'s identical do-while note).
        gb.update();

        gb.setCollisionsSinceLastBlockHitForTests(GameBoard.SPEEDUP_TRIGGER_COLLISIONS - 1);
        assertFalse("below the threshold: no offer yet", gb.shouldOfferSpeedUp());

        gb.setCollisionsSinceLastBlockHitForTests(GameBoard.SPEEDUP_TRIGGER_COLLISIONS);
        assertTrue("at the threshold while rolling: offer it", gb.shouldOfferSpeedUp());

        gb.setCollisionsSinceLastBlockHitForTests(GameBoard.SPEEDUP_TRIGGER_COLLISIONS + 3);
        assertTrue("above the threshold: still offer it", gb.shouldOfferSpeedUp());
    }

    @Test
    public void notOfferedOnceBallsHaveStoppedRolling() {
        GameBoard gb = newBoard();
        gb.clearBoardForTests();
        gb.setCollisionsSinceLastBlockHitForTests(GameBoard.SPEEDUP_TRIGGER_COLLISIONS);
        // Never fired -- balls are at rest on the start line, not rolling.
        assertFalse("nothing to fast-forward through if the balls aren't even moving", gb.shouldOfferSpeedUp());
    }

    @Test
    public void fastForwardStopsAsSoonAsABlockIsHit() {
        GameBoard gb = newBoard();
        gb.clearBoardForTests();
        // Wall the entire top row so a straight-up shot is guaranteed to hit something almost
        // immediately, regardless of which column it starts in.
        StringBuilder blocks = new StringBuilder("{\"blocks\":[");
        for (int x = 0; x < 11; x++) {
            if (x > 0) blocks.append(',');
            blocks.append("{\"x\":").append(x).append(",\"y\":0,\"value\":9,\"type\":\"square\"}");
        }
        blocks.append("]}");
        gb.importAndReplayForDebug(blocks.toString(), gb.getFirePosXForTests(), 0f, -50f, 1);
        gb.update(); // dispatch (see the other test's comment)

        // Pretend the balls had already been bouncing aimlessly for a while before this hit.
        gb.setCollisionsSinceLastBlockHitForTests(GameBoard.SPEEDUP_TRIGGER_COLLISIONS + 2);
        assertTrue(gb.shouldOfferSpeedUp());

        gb.fastForwardToNextBlockHit();

        assertEquals("the block hit must reset the streak to exactly 0",
                0, gb.getCollisionsSinceLastBlockHitForTests());
        assertFalse("no longer offered right after a hit resets the streak", gb.shouldOfferSpeedUp());
    }

    @Test
    public void fastForwardTerminatesEvenWithNothingLeftToHit() {
        GameBoard gb = newBoard();
        gb.clearBoardForTests();
        // Empty board: the ball can only ever bounce off borders, never reset the streak by
        // hitting a block. fastForwardToNextBlockHit() must still return (either the shot ends as
        // the ball comes back down, or the SPEEDUP_MAX_TICKS safety cap kicks in) rather than
        // looping forever.
        gb.importAndReplayForDebug("{\"blocks\":[]}", gb.getFirePosXForTests(), 20f, -46f, 1);
        gb.update(); // dispatch (see the other tests' comment) -- otherwise fastForwardToNextBlockHit()'s
                     // own ballRolling() check would exit before ever looping, testing nothing.
        gb.setCollisionsSinceLastBlockHitForTests(GameBoard.SPEEDUP_TRIGGER_COLLISIONS);

        gb.fastForwardToNextBlockHit();

        assertFalse("either the shot ended or the safety cap stopped it -- either way, no longer rolling-and-stuck",
                gb.shouldOfferSpeedUp());
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
