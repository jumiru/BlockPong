package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

public abstract class Block {

    public enum tEdge { left, right, top, bottom , none };

    protected Paint strokePaint;
    protected Paint fillPaint;
    protected Paint textPaint;

    protected int x;
    protected int y;

    protected int value;

    private tEdge hitBorder;
    private float hitCornerX;
    private float hitCornerY;

    public float getHitCornerX() {
        return hitCornerX;
    }

    public float getHitCornerY() {
        return hitCornerY;
    }

    public void setHitCornerX(float hitCornerX) {
        this.hitCornerX = hitCornerX;
    }

    public void setHitCornerY(float hitCornerY) {
        this.hitCornerY = hitCornerY;
    }


    public void setHitBorder( tEdge b ) {
        hitBorder = b;
    }

    public tEdge getHitBorder() {
        return hitBorder;
    }



    public int getX() {
        return x;
    }

    public int getY() {
        return y;
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


        textPaint = new Paint();
        textPaint.setTextSize(gb.getBlockHeight()*.5f);

    }

    public Block( Block b) {
        strokePaint = b.strokePaint;
        fillPaint = b.fillPaint;
        textPaint = b.textPaint;
        x = b.x;
        y = b.y;
        value = b.value;
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

    public void hit() {
        if (value>0)
            value--;
    }


    protected int getTextColorFromValue() {
        return Color.WHITE;
    }

    protected int getRectColorFromValue() {
        return Color.rgb(20+value/2,90-value,value*3);
    }
    public int getValue() {
        return value;
    }

    abstract public void draw(Canvas c);
    abstract public void moveBlock( float dY);



}
