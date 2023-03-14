package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

public class HitBlockAnimation extends Animation {

    private final float da;
    private int x;
    private int y;

    private RectF rect;
    private Paint p;

    public HitBlockAnimation(GameBoard gb, int dur, int x, int y) {
        super(gb, dur);
        this.x = x;
        this.y = y;
        rect = new RectF(gb.left(x),gb.top(y),gb.right(x),gb.bottom(y));
        p = new Paint();
        p.setColor(Color.WHITE);
        p.setAlpha(0);
        da = 512f / animationDuration;
    }

    @Override
    public void draw(Canvas c) {
        c.drawRect(rect, p);
    }

    @Override
    public boolean update() {
        if ( animationCycle < animationDuration/2)
            p.setAlpha( (int)((float)p.getAlpha()+da));
        else
            p.setAlpha( (int)((float)p.getAlpha()-da));

        animationCycle++;
        if (animationCycle>=animationDuration) return true;
        return false;
    }
}
