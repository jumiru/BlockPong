package com.jrgames.blockpong;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Random;

import static org.junit.Assert.assertTrue;

// Covers the bonus-award weighting (see Game.onRoundEnd()/pickRandomBonus()): reported request
// was to check that every bonus type can actually come up, and to make unowned types come up more
// often than ones the player already has.
public class BonusPickerTest {

    private static final int UNOWNED_WEIGHT = 3;
    private static final int TRIALS = 60000;

    @Test
    public void everyBonusTypeCanBeAwarded_evenWeighting() {
        // All counts equal (a mid-game player who already has one of everything) -- every type
        // should still come up, roughly evenly.
        int[] counts = new int[Bonus.values().length];
        java.util.Arrays.fill(counts, 2);
        EnumMap<Bonus, Integer> tally = runTrials(counts, 12345L);

        for (Bonus bonus : Bonus.values()) {
            assertTrue(bonus + " should have been awarded at least once over " + TRIALS + " trials",
                    tally.getOrDefault(bonus, 0) > 0);
        }

        double expectedShare = 1.0 / Bonus.values().length;
        for (Bonus bonus : Bonus.values()) {
            double share = tally.get(bonus) / (double) TRIALS;
            assertTrue(bonus + " share " + share + " should be close to the even " + expectedShare,
                    Math.abs(share - expectedShare) < 0.02);
        }
    }

    @Test
    public void everyBonusTypeCanBeAwarded_freshPlayerHasNoneYet() {
        // A brand new player: all counts 0, so every type is equally (and maximally) preferred --
        // still just a uniform distribution, only the flat weight differs.
        int[] counts = new int[Bonus.values().length];
        EnumMap<Bonus, Integer> tally = runTrials(counts, 54321L);

        for (Bonus bonus : Bonus.values()) {
            assertTrue(bonus + " should have been awarded at least once",
                    tally.getOrDefault(bonus, 0) > 0);
        }
    }

    @Test
    public void unownedBonusesAreAwardedMoreOftenThanOwnedOnes() {
        int[] counts = new int[Bonus.values().length];
        // MOVE_STOPPER and EXTRA_BALLS are already owned; everything else isn't yet.
        counts[Bonus.MOVE_STOPPER.ordinal()] = 5;
        counts[Bonus.EXTRA_BALLS.ordinal()] = 1;

        EnumMap<Bonus, Integer> tally = runTrials(counts, 999L);

        int unownedCount = tally.get(Bonus.LINE_DELETE); // one representative unowned type
        int ownedCount = tally.get(Bonus.MOVE_STOPPER);

        // Expected ratio is exactly UNOWNED_WEIGHT (3x); allow generous slack for RNG noise.
        double ratio = unownedCount / (double) ownedCount;
        assertTrue("unowned bonus should be awarded roughly " + UNOWNED_WEIGHT
                        + "x as often as an owned one, got ratio " + ratio,
                ratio > UNOWNED_WEIGHT * 0.8 && ratio < UNOWNED_WEIGHT * 1.2);
    }

    @Test
    public void neverPicksAnOwnedBonusAsImpossible() {
        // "Tendenziell" (a soft nudge), not a hard exclusion: an already-owned bonus must still
        // be reachable, just less often.
        int[] counts = new int[Bonus.values().length];
        counts[Bonus.MOVE_STOPPER.ordinal()] = 10;

        EnumMap<Bonus, Integer> tally = runTrials(counts, 42L);
        assertTrue("an already-owned bonus should still be awardable sometimes",
                tally.getOrDefault(Bonus.MOVE_STOPPER, 0) > 0);
    }

    private EnumMap<Bonus, Integer> runTrials(int[] counts, long seed) {
        Random random = new Random(seed);
        EnumMap<Bonus, Integer> tally = new EnumMap<>(Bonus.class);
        for (int i = 0; i < TRIALS; i++) {
            Bonus picked = BonusPicker.pickWeighted(random, counts, UNOWNED_WEIGHT);
            tally.merge(picked, 1, Integer::sum);
        }
        return tally;
    }
}
