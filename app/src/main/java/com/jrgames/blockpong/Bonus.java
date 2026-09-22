package com.jrgames.blockpong;

// Bonus types the player can earn by scoring particularly well in a single move (see
// Game.onRoundEnd()). This only models acquisition and the "armed for next shot" selection
// state -- the actual gameplay effects are wired up in a follow-up step.
public enum Bonus {
    MOVE_STOPPER,       // skips the next automatic board-drop-by-one-row
    EXTENDED_PATH,      // lengthens the aim preview line
    LINE_DELETE,        // fires immediately on tap: removes a random row weighted by its block count, rows below slide up
    EXTRA_BALLS,        // fires with many more balls (20) for one shot
    MOVE_START_POINT,   // lets the player freely choose the fire position
    BASELINE_BOUNCE     // for the rest of the shot, balls reaching the start line bounce back
                         // upward instead of settling -- budgeted at one reflection per ball fired
}
