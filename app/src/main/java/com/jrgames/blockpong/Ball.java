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
    private boolean postpone2ndMirroring;
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

    public void mirrorHorizontallyIntern( float prevX, float prevY, float horizontalReflectionLine, float verticalReflectionLine) {

        float yDistToHMirror = Math.abs(prevY - horizontalReflectionLine);
        float xDistToVMirror = Math.abs(prevX - verticalReflectionLine);
        float xDistToHMirror = Math.abs(yDistToHMirror*dx/dy);
        float yDistToVMirror = Math.abs(xDistToVMirror*dy/dx);
        if ( verticalReflectionLine!=Float.POSITIVE_INFINITY && xDistToVMirror < xDistToHMirror) {
            postpone2ndMirroring = true;
            return;
        }
        postpone2ndMirroring = false;

        float distAfterHMirror = Math.abs(dy)-yDistToHMirror;

        // intersection point with vertical mirror line
        float nextX = prevX+Math.signum(dx)*xDistToHMirror;
        float nextY = prevY+Math.signum(dy)*yDistToHMirror;

        dy = -dy;
        y = horizontalReflectionLine + Math.signum(dy)*distAfterHMirror;

        if (postpone2ndMirroring) {
            mirrorVerticallyIntern(nextX, nextY, verticalReflectionLine, horizontalReflectionLine);
        }
    }

    public void mirrorVertically( float prevX, float prevY, float verticalReflectionLine, float horizontalReflectionLine) {
        mvDataStored = true;
        mvPrevX = prevX;
        mvPrevY = prevY;
        mvVerticalReflectionLine = verticalReflectionLine;
        mvHorizontalReflectionLine = horizontalReflectionLine;
    }


    public void mirrorVerticallyIntern( float prevX, float prevY, float verticalReflectionLine, float horizontalReflectionLine) {
        float xDistToVMirror = Math.abs(verticalReflectionLine-prevX);
        float yDistToHMirror = Math.abs(prevY - horizontalReflectionLine);
        float yDistToVMirror = Math.abs(xDistToVMirror*dy/dx);
        float xDistToHMirror = Math.abs(yDistToHMirror*dx/dy);

        if ( horizontalReflectionLine!=Float.POSITIVE_INFINITY && xDistToVMirror < xDistToHMirror) {
            postpone2ndMirroring = true;
            return;
        }
        postpone2ndMirroring = false;

        float distAfterVMirror = Math.abs(dx)-xDistToVMirror;

        // intersection point with vertical mirror line
        float nextX = prevX+Math.signum(dx)*xDistToVMirror;
        float nextY = prevY+Math.signum(dy)*yDistToVMirror;

        dx = -dx;
        x = verticalReflectionLine + Math.signum(dx)*distAfterVMirror;
        if (postpone2ndMirroring) {
            mirrorHorizontallyIntern(nextX, nextY, horizontalReflectionLine, verticalReflectionLine);
        }
    }

    public void performMirroring() {
        if ( mvDataStored ) {
            if (!mhDataStored) {
                mvHorizontalReflectionLine = Float.POSITIVE_INFINITY;
            }
            mirrorVerticallyIntern( mvPrevX, mvPrevY, mvVerticalReflectionLine, mvHorizontalReflectionLine);
        }
        if ( mhDataStored ) {
            if (!mvDataStored) {
                mhVerticalReflectionLine = Float.POSITIVE_INFINITY;
            }
            mirrorHorizontallyIntern( mhPrevX, mhPrevY, mhHorizontalReflectionLine, mhVerticalReflectionLine);
        }
        postpone2ndMirroring = false;
        mvDataStored = false;
        mhDataStored = false;
    }

    public boolean scheduledMirroring() {
        return mvDataStored || mhDataStored;
    }
}
