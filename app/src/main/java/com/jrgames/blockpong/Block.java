package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

public class Block {

    private final Paint strokePaint;
    private Paint fillPaint;

    private int x;
    private int y;

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    private RectF rect;

    public float getLeft() {return rect.left;}
    public float getTop() {return rect.top;}
    public float getRight() {return rect.right;}
    public float getBottom() {return rect.bottom; }

    private Paint textPaint;

    private int value;

    public Block( Block b) {
        strokePaint = b.strokePaint;
        fillPaint = b.fillPaint;
        x = b.x;
        y = b.y;
        rect = new RectF(b.rect);
        textPaint = b.textPaint;
        value = b.value;
    }

    public Block( GameBoard gb, int x, int y, int value) {
        this.x = x;
        this.y = y;
        this.value = value;


        // fill
        fillPaint = new Paint();
        fillPaint.setStyle(Paint.Style.FILL);


        // stroke
        strokePaint = new Paint();
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(5);

        rect = new RectF(gb.getBlockX(x),gb.getBlockY(y),
                gb.getBlockX(x)+gb.getBlockWidth(),gb.getBlockY(y)+gb.getBlockHeight());

        textPaint = new Paint();
        textPaint.setTextSize(rect.height()*.5f);

    }

    public void draw(Canvas c) {
        fillPaint.setColor(getRectColorFromValue());
        c.drawRoundRect(rect,10, 10, fillPaint);
        c.drawRoundRect(rect, 10,10, strokePaint);
        textPaint.setColor(getTextColorFromValue());
        drawCenter(c, textPaint, Integer.toString(value));
    }

    private int getTextColorFromValue() {
        return Color.WHITE;
    }

    private int getRectColorFromValue() {
        return Color.rgb(20+value/2,90-value,value*3);
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

    public void hit() {
        if (value>0)
            value--;
    }

    public int getValue() {
        return value;
    }

    public void moveBlock( float dY) {
        rect.top = rect.top + dY;
        rect.bottom = rect.bottom + dY;
    }

    public void setCoords(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setAlpha(int a) {
        fillPaint.setAlpha(a);
        strokePaint.setAlpha(a);
        textPaint.setAlpha(a);
    }
}
