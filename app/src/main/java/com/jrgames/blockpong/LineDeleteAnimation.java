package com.jrgames.blockpong;

import android.graphics.Canvas;

// LINE_DELETE bonus: mirrors BoardDropAnimation but slides the board up by one cell instead of
// down (see GameBoard.deleteLineAndShiftUp()).
public class LineDeleteAnimation extends Animation {

    private final float dy;

    public LineDeleteAnimation(GameBoard gb, int dur) {
        super(gb, dur);

        dy = -gb.getBlockHeight() / dur;
    }

    @Override
    public void draw(Canvas c) {

    }

    @Override
    public boolean update() {
        gb.moveAllBlocks(dy);
        animationCycle++;
        if (animationCycle >= animationDuration) {
            gb.deleteLineAndShiftUp();
            return true;
        }
        return false;
    }
}
