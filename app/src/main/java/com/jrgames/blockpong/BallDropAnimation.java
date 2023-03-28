package com.jrgames.blockpong;

import android.graphics.Canvas;

public class BallDropAnimation extends Animation {

    private final float targetX;
    private final float targetY;
    private final float dx;
    private final float dy;
    Ball ball;
    public BallDropAnimation(GameBoard gameBoard, int dur, Ball ball, float targetX, float targetY) {
        super(gameBoard, dur);
        this.ball = ball;
        this.targetX = targetX;
        this.targetY = targetY;

        dx = (targetX-ball.getX())/dur;
        dy = (targetY-ball.getY())/dur;
    }

    @Override
    public void draw(Canvas c) {
        ball.draw(c);
    }

    @Override
    public boolean update() {
        ball.setPos(ball.getX()+dx, ball.getY()+dy);
        animationCycle++;
        if (animationCycle >= animationDuration) {
            ball.setSpeed(0,0);
            return true;
        }
        return false;
    }
}
