package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Paint;

public class Ball {

    private float x;
    private float y;

    private float radius;

    private float dx;
    private float dy;
    private Paint p;


    public Ball() {

    }

    public Ball( float r, float x, float y) {
        this.radius = r;
        this.x = x;
        this.y = y;

        dx = 0;
        dy = 0;

    }

    public void copy(Ball b) {
        x = b.x;
        y = b.y;
        radius = b.radius;
        dx = b.dx;
        dy = b.dy;
        p = b.p;
    }

    public void setPaint(Paint p) {
        this.p = p;
    }

    public Paint getPaint() {
        return p;
    }

    public void draw(Canvas c) {
        c.drawCircle(x,y, radius, p);

    }

    public void update() {
        x = x + dx;
        y = y + dy;
    }

    public void setPos( float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void setSpeed( float dx, float dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public float getX() {return x;}
    public float getY() {return y;}
    public float getDx() {return dx;}
    public float getDy() {return dy;}


    public boolean isStill() {
        return (dx==0 && dy==0);
    }
    public boolean movingUpwards() {
        return (dy<0);
    }
    public boolean movingDownwards() {
        return (dy>0);
    }
    public boolean movingLeft() {
        return (dx<0);
    }
    public boolean movingRight() {return (dx>0);}


    public void mirrorHorizontally( float mirrorLine) {
        float distToMirror = y - mirrorLine;
        dy = -dy;
        x = x + dx;
        float distToMove = dy-2f*distToMirror;
        y = y + (distToMove);
    }

    public void mirrorVertically( float mirrorline ) {
        float distToMirror = x - mirrorline;
        dx = -dx;
        y = y + dy;
        float distToMove = dx-2f*distToMirror;
        x = x + (distToMove);
    }
}
