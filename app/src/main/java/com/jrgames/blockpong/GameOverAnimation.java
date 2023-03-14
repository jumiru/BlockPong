package com.jrgames.blockpong;


import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.Random;

public class GameOverAnimation extends Animation {

    private boolean curtainPhase;
    private RectF curtain;
    private Paint curtainPaint;
    private float dY;
    private float bottom;

    private int scrollPos;
    private Paint textPaint;
    private float alpha;
    private float alphaSineIncrement;
    private float alphaStart;
    private float alphaCounter;
    private float alphaAmplitude;

    Random rand;
    private float scrollY;
    private String scrollText;
    boolean gameWon;

    private int scrollSpeed;


    public GameOverAnimation(GameBoard gb, int dur, boolean gameWon ) {
        super(gb,dur);
        curtainPhase = true;
        curtainPaint = new Paint();
        curtainPaint.setColor(Color.rgb(30,30,30));

        alphaCounter = 0;
        alpha = 0;
        alphaSineIncrement = 0.05f;
        alphaStart = 100;
        alphaAmplitude = 70;
        curtainPaint.setAlpha( (int)alphaStart );
        textPaint = new Paint();
        textPaint.setTextSize(300);
        textPaint.setFakeBoldText(true);
        rand = new Random();
        if (gameWon) {
            textPaint.setColor(Color.rgb(22, 255, 88));
            scrollText = "WON!!!!!!!";
        } else {
            textPaint.setColor(Color.rgb(222, 44, 22));
            scrollText = "Game Over !!!";
        }

        dY = (float)gb.getHeight() / dur;
        bottom = (float)gb.getYOffset();

        scrollSpeed = rand.nextInt(10)+3;
        scrollPos = rand.nextInt(1000)-2000;
        scrollY = rand.nextInt((int) gb.getHeight()-300)+300 + (int) gb.getYOffset();

        curtain = new RectF( gb.getXOffset(), gb.getYOffset(),
                gb.getXOffset()+gb.getWidth(), gb.getYOffset());

        this.gameWon = gameWon;


    }

    @Override
    public void draw(Canvas c) {
        c.drawRect(curtain, curtainPaint);
        if ( !curtainPhase ) {
            c.drawText(scrollText, scrollPos, scrollY , textPaint);
            curtainPaint.setAlpha((int)alpha);
        }
    };


    @Override
    public boolean update() {
        if (curtainPhase) {
            bottom += dY;
            curtain.bottom = (int) bottom;
            if (bottom >= gb.getHeight() + gb.getYOffset()) {
                curtainPhase = false;
                bottom = gb.getHeight() + gb.getHeight();
            }
        } else {
            scrollPos += scrollSpeed;
            alphaCounter += alphaSineIncrement;
            alpha = alphaStart + alphaAmplitude * (float) Math.sin((double) alphaCounter);
            if (scrollPos >= gb.getWidth() + gb.getXOffset() * 2) {
                scrollPos = rand.nextInt(1000)-2000;
                scrollY = rand.nextInt((int) gb.getHeight()-300) + 300 + (int) gb.getYOffset();
                scrollSpeed = rand.nextInt(10)+3;
            }
        }

        animationCycle++;
        if (!gb.game.isGameOver()) return true;
        return false;
    }
};