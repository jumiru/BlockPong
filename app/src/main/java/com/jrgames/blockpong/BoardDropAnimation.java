package com.jrgames.blockpong;

import android.graphics.Canvas;

public class BoardDropAnimation extends Animation {

    private final float dy;

    public BoardDropAnimation(GameBoard gb, int dur) {
        super(gb,dur);

        dy = gb.getBlockHeight()/dur;
    }
    @Override
    public void draw(Canvas c) {

    }

    @Override
    public boolean update() {
        gb.moveAllBlocks(dy);
        animationCycle++;
        if (animationCycle >= animationDuration) {
            gb.dropAllBlocksByOneCell();
            return true;
        }
        return false;
    }
}
