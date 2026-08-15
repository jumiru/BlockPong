package com.jrgames.blockpong;

// Bonus types the player can earn by scoring particularly well in a single move (see
// Game.onRoundEnd()). This only models acquisition and the "armed for next shot" selection
// state -- the actual gameplay effects are wired up in a follow-up step.
public enum Bonus {
    MOVE_STOPPER,       // skips the next automatic board-drop-by-one-row
    EXTENDED_PATH,      // lengthens the aim preview line
    LINE_DELETE,        // fires immediately on tap: removes one of the top-3 rows by value, rows below slide up
    EXTRA_BALLS,        // fires with many more balls (20) for one shot
    MOVE_START_POINT,   // lets the player freely choose the fire position
    DRAG_PADDLE         // a shrinking paddle appears at the fire line after firing, dragged with
                         // a finger, and bounces balls back upward until it's gone
}
