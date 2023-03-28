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

    private int ballIndex;
    private boolean mhDataStored;
    private float mhPrevX;
    private float mhPrevY;
    private float mhHorizontalReflectionLine;
    private float mhVerticalReflectionLine;
    private boolean mvDataStored;
    private float mvPrevX;
    private float mvPrevY;
    private float mvVerticalReflectionLine;
    private float mvHorizontalReflectionLine;


    public Ball() {

    }

    public Ball(Ball b) {
        this.x = b.x;
        this.y = b.y;
        this.radius = b.radius;
        this.dx = b.dx;
        this.dy = b.dy;
        this.p = b.p;
        this.ballIndex = b.ballIndex;
        this.mhDataStored = b.mhDataStored;
        this.mhPrevX = b.mhPrevX;
        this.mhPrevY = b.mhPrevY;
        this.mhHorizontalReflectionLine = b.mhHorizontalReflectionLine;
        this.mhVerticalReflectionLine = b.mhVerticalReflectionLine;
        this.mvDataStored = b.mvDataStored;
        this.mvPrevX = b.mvPrevX;
        this.mvPrevY = b.mvPrevY;
        this.mvVerticalReflectionLine = b.mvVerticalReflectionLine;
        this.mvHorizontalReflectionLine = b.mvHorizontalReflectionLine;
    }

    public Ball( float r, float x, float y, int ballIndex) {
        this.radius = r;
        this.x = x;
        this.y = y;
        this.ballIndex = ballIndex;

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
        ballIndex = b.ballIndex;
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
    public boolean movingMainlyUpwards() {
        return (dy<0 && Math.abs(dy) > Math.abs(dx));
    }
    public boolean movingMainlyDownwards() {
        return (dy>0 && Math.abs(dy) > Math.abs(dx));
    }
    public boolean movingMainlyLeft() {
        return (dx<0 && Math.abs(dx) > Math.abs(dy));
    }
    public boolean movingMainlyRight() {return (dx>0 && Math.abs(dx) > Math.abs(dy));}
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


    public void mirrorHorizontally( float prevX, float prevY, float horizontalReflectionLine, float verticalReflectionLine) {
        mhDataStored = true;
        mhPrevX = prevX;
        mhPrevY = prevY;
        mhHorizontalReflectionLine = horizontalReflectionLine;
        mhVerticalReflectionLine = verticalReflectionLine;
    }

    public boolean mirrorHorizontallyIntern( float prevX, float prevY, float horizontalReflectionLine, float verticalReflectionLine) {

        float yDistToHMirror = horizontalReflectionLine - prevY;
        float xDistToVMirror = verticalReflectionLine - prevX;
        float xDistToHMirror = yDistToHMirror*dx/dy;
        float yDistToVMirror = xDistToVMirror*dy/dx;
        if ( verticalReflectionLine!=Float.POSITIVE_INFINITY && Math.abs(xDistToVMirror) < Math.abs(xDistToHMirror)) {
            return false;
        }

        float distAfterHMirror = dy-yDistToHMirror;

        // intersection point with vertical mirror line
        float nextX = prevX+xDistToHMirror;
        float nextY = prevY+yDistToHMirror;

        dy = -dy;
        y = horizontalReflectionLine - distAfterHMirror;
        return true;
    }

    public void mirrorVertically( float prevX, float prevY, float verticalReflectionLine, float horizontalReflectionLine) {
        mvDataStored = true;
        mvPrevX = prevX;
        mvPrevY = prevY;
        mvVerticalReflectionLine = verticalReflectionLine;
        mvHorizontalReflectionLine = horizontalReflectionLine;
    }


    public boolean mirrorVerticallyIntern( float prevX, float prevY, float verticalReflectionLine, float horizontalReflectionLine) {
        float xDistToVMirror = verticalReflectionLine-prevX;
        float yDistToHMirror = horizontalReflectionLine-prevY;
        float yDistToVMirror = xDistToVMirror*dy/dx;
        float xDistToHMirror = yDistToHMirror*dx/dy;

        if ( horizontalReflectionLine!=Float.POSITIVE_INFINITY && Math.abs(xDistToHMirror) < Math.abs(xDistToVMirror)) {
            return false;
        }

        float distAfterVMirror = dx-xDistToVMirror;

        // intersection point with vertical mirror line
        float nextX = prevX+xDistToHMirror;
        float nextY = prevY+yDistToHMirror;

        dx = -dx;
        x = verticalReflectionLine - distAfterVMirror;
        return true;
    }

    public void performMirroring() {
        boolean vmp = false;
        boolean hmp = false;

        if ( mvDataStored ) {
            if (!mhDataStored) {
                mvHorizontalReflectionLine = Float.POSITIVE_INFINITY;
            }
            vmp = mirrorVerticallyIntern( mvPrevX, mvPrevY, mvVerticalReflectionLine, mvHorizontalReflectionLine);
        }
        if ( mhDataStored ) {
            if (!mvDataStored) {
                mhVerticalReflectionLine = Float.POSITIVE_INFINITY;
            }
            hmp = mirrorHorizontallyIntern( mhPrevX, mhPrevY, mhHorizontalReflectionLine, mhVerticalReflectionLine);
        }
        if (vmp) mvDataStored = false;
        if (hmp) mhDataStored = false;
    }

    public boolean scheduledMirroring() {
        return mvDataStored || mhDataStored;
    }

    public void resetMirrorings() {
        mhDataStored = mvDataStored = false;
    }
}
