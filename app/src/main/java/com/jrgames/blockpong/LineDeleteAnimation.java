package com.jrgames.blockpong;

import android.graphics.Canvas;

// LINE_DELETE bonus: mirrors BoardDropAnimation but slides rowToDelete and everything below it
// up by one cell instead of dropping the whole board down (see GameBoard.deleteLineAndShiftUp()).
public class LineDeleteAnimation extends Animation {

    private final float dy;
    private final int rowToDelete;

    public LineDeleteAnimation(GameBoard gb, int dur, int rowToDelete) {
        super(gb, dur);

        this.rowToDelete = rowToDelete;
        dy = -gb.getBlockHeight() / dur;
    }

    @Override
    public void draw(Canvas c) {

    }

    @Override
    public boolean update() {
        gb.moveBlocksFromRow(rowToDelete, dy);
        animationCycle++;
        if (animationCycle >= animationDuration) {
            gb.deleteLineAndShiftUp(rowToDelete);
            gb.checkLevelCompleteAfterLineDelete();
            return true;
        }
        return false;
    }
}
