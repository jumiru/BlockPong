package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

public class Block3 extends Block {

    // the type defines where the 90deg angle lies
    public enum tTriangle { BL, TL, TR, BR };

    private tTriangle type;

    public tTriangle getType() {
        return type;
    }
    private final Path path;

    private float offset;

    private float textX;
    private float textY;

    public GameBoard.Content blockHitType() {
        switch (type) {
            case BL:
                return GameBoard.Content.HIT_BLOCK3_BL;
            case TL:
                return  GameBoard.Content.HIT_BLOCK3_TL;
            case BR:
                return  GameBoard.Content.HIT_BLOCK3_BR;
            case TR:
                return  GameBoard.Content.HIT_BLOCK3_TR;
        }
        return GameBoard.Content.HIT_BLOCK3_BL;
    }
    public Block3(Block3 b) {
        super(b);
        this.type = b.type;
        this.path = new Path(b.path);
        this.offset = b.offset;
    }

    public Block3( GameBoard gb, int x, int y, tTriangle type, int value) {
        super(gb,x,y,value);

        this.type = type;

        float left   = gb.getBlockX(x);
        float top    = gb.getBlockY(y);
        float right  = left+gb.getBlockWidth();
        float bottom = top+gb.getBlockHeight();

        fillPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        textPaint.setTextSize(gb.getBlockHeight()*.45f);
        float ts = textPaint.getTextSize();

        path = new Path();
        if (type == tTriangle.BL) {
            path.moveTo(left, bottom);
            path.lineTo(left, top);
            path.lineTo(right, bottom);
            path.lineTo(left, bottom);
            textX = left+ts/6f;
            textY = bottom-ts/6f;
        } else if (type == tTriangle.TL) {
            path.moveTo(left, top);
            path.lineTo(right, top);
            path.lineTo(left, bottom);
            path.lineTo(left, top);
            textX = left+ts/6f;
            textY = top+ts/20f+ts;
        } else if (type == tTriangle.TR) {
            path.moveTo(right, top);
            path.lineTo(right, bottom);
            path.lineTo(left, top);
            path.lineTo(right, top);
            textX = right-ts*1.2f;
            textY = top+ts/20f+ts;
        } else if (type == tTriangle.BR) {
            path.moveTo(right, bottom);
            path.lineTo(left, bottom);
            path.lineTo(right, top);
            path.lineTo(right, bottom);
            textX = right-ts*1.2f;
            textY = bottom-ts/6f;
        }
        path.close();


    }

    public void draw(Canvas c) {
        fillPaint.setColor(getRectColorFromValue());
        c.drawPath(path, fillPaint);
        c.drawPath(path, strokePaint);
        textPaint.setColor(getTextColorFromValue());
        c.drawText(Integer.toString(value), textX, textY+offset, textPaint);
    }


    public void moveBlock( float dY) {
        offset+=dY;
        path.offset(0, dY);
    }
}
