package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

public abstract class Block {

    public abstract GameBoard.Content blockHitType();

    public enum tEdge { left, right, top, bottom , none };

    protected Paint strokePaint;
    protected Paint fillPaint;
    protected Paint textPaint;

    protected int x;
    protected int y;

    protected int value;
    // The value the block was created with. Kept separate from `value` (which counts down as the
    // block is hit) so the block's color stays stable for its whole lifetime instead of drifting
    // as it takes damage.
    protected final int initialValue;
    // Explicit color from the level file's optional "color" field (see GameBoard.loadBlocksFromJson()),
    // decoupled from the block's point value -- e.g. for drawing a picture/pattern in a level
    // without that shape dictating difficulty. Null means "no override", the common case: color is
    // derived from initialValue the way it always was (see getRectColorFromValue()).
    protected final Integer overrideColor;

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
        this(gb, x, y, value, null);
    }

    public Block( GameBoard gb, int x, int y, int value, Integer overrideColor) {
        this.x = x;
        this.y = y;
        this.value = value;
        this.initialValue = value;
        this.overrideColor = overrideColor;


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
        initialValue = b.initialValue;
        overrideColor = b.overrideColor;
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
        // Pick black or white text, whichever contrasts better against this block's fill color.
        return perceivedLuminance(getRectColorFromValue()) > 0.6f ? Color.BLACK : Color.WHITE;
    }

    private static float perceivedLuminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    // An explicit overrideColor (see field above) always wins. Otherwise colored by the block's
    // starting value (not its current, decreasing value) so a block's color stays put for its
    // whole lifetime instead of drifting hit by hit. The hue cycles through the color wheel as the
    // starting value grows, giving tougher blocks visibly different (and, past 15, repeating)
    // colors rather than everything reading as "greenish".
    protected int getRectColorFromValue() {
        if (overrideColor != null) {
            return overrideColor;
        }
        float hue = (initialValue * 24f) % 360f;
        return Color.HSVToColor(new float[]{ hue, 0.65f, 0.90f });
    }
    public int getValue() {
        return value;
    }

    public int getInitialValue() {
        return initialValue;
    }

    // Null if this block's color is derived from its value the usual way -- see overrideColor.
    public Integer getOverrideColor() {
        return overrideColor;
    }

    abstract public void draw(Canvas c);
    abstract public void moveBlock( float dY);



}
