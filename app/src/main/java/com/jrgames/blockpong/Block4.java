package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

public class Block4 extends Block {


    private RectF rect;

    public float getLeft() {return rect.left;}
    public float getTop() {return rect.top;}
    public float getRight() {return rect.right;}
    public float getBottom() {return rect.bottom; }

    public float getBorder( tEdge b ) {
        if (b==tEdge.left) return getLeft();
        if (b==tEdge.right) return getRight();
        if (b==tEdge.top) return getTop();
        if (b==tEdge.bottom) return getBottom();
        return Float.POSITIVE_INFINITY;
    }




    public Block4( Block4 b) {
        super(b);

        rect = new RectF(b.rect);
    }

    public Block4( GameBoard gb, int x, int y, int value) {
        super(gb, x, y, value);

        rect = new RectF(gb.getBlockX(x),gb.getBlockY(y),
                gb.getBlockX(x)+gb.getBlockWidth(),gb.getBlockY(y)+gb.getBlockHeight());

        textPaint = new Paint();
        textPaint.setTextSize(rect.height()*.5f);

    }




    private void drawCenter(Canvas canvas, Paint paint, String text ) {
        RectF bounds = new RectF(rect);
        // measure text width
        bounds.right = textPaint.measureText(text, 0, text.length());
        // measure text height
        bounds.bottom = paint.descent() - paint.ascent();

        bounds.left += (rect.width() - bounds.right) / 2.0f;
        bounds.top += (rect.height() - bounds.bottom) / 2.0f;

        canvas.drawText(text, bounds.left, bounds.top - paint.ascent(), paint);
    }



    public void draw(Canvas c) {
        fillPaint.setColor(getRectColorFromValue());
        c.drawRoundRect(rect,10, 10, fillPaint);
        c.drawRoundRect(rect, 10,10, strokePaint);
        textPaint.setColor(getTextColorFromValue());
        drawCenter(c, textPaint, Integer.toString(value));
    }

    public void moveBlock( float dY) {
        rect.top = rect.top + dY;
        rect.bottom = rect.bottom + dY;
    }

}
