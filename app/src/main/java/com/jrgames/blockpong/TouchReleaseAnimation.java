package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.Random;

public class TouchReleaseAnimation extends Animation {

    private Random rand;
    private Paint textPaint;

    public TouchReleaseAnimation(GameBoard gb, int dur) {
        super(gb, dur);
        rand = new Random();
        textPaint = new Paint();
        textPaint.setTextSize(100);
        textPaint.setColor(Color.GREEN);
    }

    public void draw(Canvas c) {
        c.drawText(Integer.toString((int)(animationCycle/10)), gb.getWidth()/2, gb.getHeight()/2, textPaint);
    }

    @Override
    public boolean update() {

        animationCycle++;
        if (animationCycle>=animationDuration) {
            if ( gb.game.isGameOver()) {
                gb.game.resetGameOver();
                gb.initBoard();
            }

            float x = rand.nextFloat() * gb.getWidth() + gb.getXOffset();
            float y = rand.nextFloat() * gb.getHeight() + gb.getYOffset();
            gb.touchDown(x, y);
            gb.touchRelease(x, y);
            return true;
        }
        return false;
    }

}
