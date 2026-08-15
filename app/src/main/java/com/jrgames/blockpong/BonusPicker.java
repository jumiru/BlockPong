package com.jrgames.blockpong;

import java.util.Random;

// Weighted random selection for which bonus to award (see Game.onRoundEnd()). Extracted out of
// Game so it's testable without instantiating the whole View.
final class BonusPicker {

    private BonusPicker() {
    }

    // Picks one of Bonus.values(), weighted so a type the player currently holds none of
    // (countsByOrdinal[i] == 0) is unownedWeightMultiplier times more likely than one they
    // already have -- a soft nudge towards variety, not a hard rule (an already-owned bonus can
    // still come up, just less often). countsByOrdinal must have one entry per Bonus constant,
    // indexed by Bonus.ordinal() (i.e. Game's bonusCounts array, as-is).
    static Bonus pickWeighted(Random random, int[] countsByOrdinal, int unownedWeightMultiplier) {
        Bonus[] bonuses = Bonus.values();
        int[] weights = new int[bonuses.length];
        int totalWeight = 0;
        for (int i = 0; i < bonuses.length; i++) {
            weights[i] = countsByOrdinal[i] == 0 ? unownedWeightMultiplier : 1;
            totalWeight += weights[i];
        }
        int r = random.nextInt(totalWeight);
        for (int i = 0; i < bonuses.length; i++) {
            r -= weights[i];
            if (r < 0) return bonuses[i];
        }
        return bonuses[bonuses.length - 1]; // unreachable: weights always sum to totalWeight
    }
}
