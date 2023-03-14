package com.jrgames.blockpong;


import android.graphics.Canvas;

public abstract class Animation {
    protected int animationDuration;
    protected int animationCycle;
    protected GameBoard gb;


    public Animation(GameBoard gb, int dur) {
        this.gb = gb;
        animationDuration = dur;
        animationCycle = 0;
    }

    public abstract void draw(Canvas c);
    public abstract boolean update();


}
