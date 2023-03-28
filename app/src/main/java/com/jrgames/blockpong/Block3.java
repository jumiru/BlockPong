package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.RectF;

public class Block3 extends Block {

    public Block3( Block3 b) {
        super(b);
    }

    public Block3( GameBoard gb, int x, int y, int value) {
        super(gb,x,y,value);
    }

    public void draw(Canvas c) {
//        fillPaint.setColor(getRectColorFromValue());
//        c.drawRoundRect(rect,10, 10, fillPaint);
//        c.drawRoundRect(rect, 10,10, strokePaint);
//        textPaint.setColor(getTextColorFromValue());
//        drawCenter(c, textPaint, Integer.toString(value));
    }

    public void moveBlock( float dY) {


    }
}
