package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

public class DissolveBlockAnimation extends Animation {
    private final float da;

    private Block block;
    private float alpha;

    public DissolveBlockAnimation(GameBoard gb, int dur, int x, int y) {
        super(gb, dur);
        block = gb.getBlock(x,y);
        System.out.println("dissolve block: "+x+"/"+y);
        if ( block instanceof Block3 ) {
            block = new Block3((Block3)block);
        } else if (block instanceof Block4) {
            block = new Block4((Block4) block);
        } else {
            System.out.println("STOP: "+x+"/"+y);
        }


        alpha = 255f;
        da = alpha / animationDuration;
    }

    @Override
    public void draw(Canvas c) {
        block.draw(c);
    }

    @Override
    public boolean update() {
        block.setAlpha( (int)(alpha));
        alpha-=da;
        animationCycle++;
        if (animationCycle>=animationDuration) return true;
        return false;
    }
}
