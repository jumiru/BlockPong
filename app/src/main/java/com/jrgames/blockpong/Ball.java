package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Paint;

public class Ball {

    private float x;
    private float y;

    private float predictiveDX;
    private float predictiveX;
    private float predictiveDY;
    private float predictiveY;
    private boolean predictionValid;

    private float radius;

    private float dx;
    private float dy;
    private Paint p;

    private int ballIndex;


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
        predictionValid = false;
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


    public void mirrorHorizontally( float prevX, float prevY, float horizontalReflectionLine, float xCross, float verticalReflectionLine) {
        float distToMirror = Math.abs(prevY - horizontalReflectionLine);
        float distAfterMirror = Math.abs(dy)-distToMirror;
        if (predictionValid) {
            dy = predictiveDY;
            y = predictiveY;
        } else if (Float.isNaN(xCross) || Float.isNaN(verticalReflectionLine)) {
            predictionValid = true;
            predictiveDY = -dy;
            predictiveY = horizontalReflectionLine + Math.signum(predictiveDY)*distAfterMirror;
        } else {
            dy = -dy;
            y = horizontalReflectionLine + Math.signum(dy)*distAfterMirror;

            if ((dx < 0 && x < verticalReflectionLine) || (dx > 0) && x > verticalReflectionLine) {
                mirrorHorizontally(xCross, horizontalReflectionLine, verticalReflectionLine, Float.NaN, Float.NaN);
            }
        }
    }

    public void mirrorVertically( float prevX, float prevY, float verticalReflectionLine, float yCross, float horizontalReflectionLine) {
        float distToMirror = Math.abs(verticalReflectionLine-prevX);
        float distAfterMirror = Math.abs(dx)-distToMirror;
        if (predictionValid) {
            dx = predictiveDX;
            x = predictiveX;
        } else if (Float.isNaN(yCross) || Float.isNaN(verticalReflectionLine)) {
            predictionValid = true;
            predictiveDX = -dx;
            predictiveX = verticalReflectionLine + Math.signum(dx)*distAfterMirror;
        } else {
            dx = -dx;
            x = verticalReflectionLine + Math.signum(dx)*distAfterMirror;
            if (Float.isNaN(yCross) || Float.isNaN(horizontalReflectionLine)) return;
            if ((dy < 0 && y < horizontalReflectionLine) || (dy > 0) && y > horizontalReflectionLine) {
                mirrorHorizontally(verticalReflectionLine, yCross, horizontalReflectionLine, Float.NaN, Float.NaN);
            }
        }
    }
}
