package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Random;

public class GameBoard {

    private Ball ballAfterMoving;
    private Ball ballBeforeMoving;
    private String freezeReason;
    private boolean reusePreviousFireSpeed;
    private int updateCounter;
    private int updateCounterAtFreezeTime;

    private class Cross {
        private float x;
        private float y;
        private String text;
        private Paint p;
        Paint pt;

        public Cross( float x, float y, String text) {
            this.x = x;
            this.y = y;
            this.text = text;
            p = new Paint();
            p.setColor(Color.YELLOW);
            p.setStrokeWidth(3f);
            pt = new Paint();
            pt.setColor(Color.YELLOW);
            pt.setTextSize(30);
        }

        public void draw(Canvas c) {
            c.drawLine(x - 50, y, x + 50, y, p);
            c.drawLine(x, y - 50, x, y + 50, p);
            c.drawText(text, x+10, y-50, pt);
        }
    }

    private final int numInitBalls;
    private final RectF debugRect;
    private final Paint debugRectPaint;
    private final float section1;
    private final float section2;
    private final float section3;
    private final float section4;
    private final float radiusSquare;
    private float width;
    private float height;
    private float offsetX;
    private float offsetY;

    private final int maxNumBalls;
    private Ball balls[];
    private int numBalls;
    public final Game game;

    private boolean fire;
    private int fireCounter;
    private int nextFireBall;
    private float firePosX;
    private float newFirePosX;
    private float firePosY;
    private boolean newFirePosSet;

    private float normSpeed;
    private float fireSpeedX;
    private float fireSpeedY;

    Paint ballPaint;
    Paint boundaryPaint;
    private float ballRadius;
    private boolean dirLineActive;
    private float dirLineX;
    private float dirLineY;

    private Paint dirLinePaint;

    private Paint debugTextPaint;

    private Block[][] blocks;
    private Block[][] blocksCopy;
    private int xDim;
    private int yDim;
    private float blockWidth;
    private float blockHeight;

    private Random rand;
    private boolean freeze;

    private HashMap<String, Cross> crosses;
    private float increment1;
    private float increment2;
    private float increment3;

    private Ball nextBall;
    private boolean endOfRollingPhase;

    private boolean drawDebugInfo;


    public GameBoard(Game game, float width, float height, float offsetX, float offsetY) {
        this.game = game;
        this.width = width;
        this.height = height;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        maxNumBalls = 100;
        normSpeed = 25;
        numInitBalls = 10;
        xDim = 11; //11
        yDim = 11; //11
        ballRadius = 20; // 20
        drawDebugInfo = true;

        radiusSquare = ballRadius * ballRadius;

        blockWidth = width / (float)xDim;
        blockHeight = blockWidth;

        assert (ballRadius<blockWidth);
        assert (ballRadius<blockHeight);
        assert (normSpeed<blockWidth);
        assert (normSpeed<blockHeight);

        balls = new Ball[maxNumBalls];

        ballPaint = new Paint();
        ballPaint.setColor(Color.rgb(150,30,50));

        boundaryPaint = new Paint();
        boundaryPaint.setColor(Color.rgb(215,229,255));
        boundaryPaint.setStrokeWidth(8.0f);

        dirLinePaint = new Paint();
        dirLinePaint.setColor(Color.WHITE);

        debugTextPaint = new Paint();
        debugTextPaint.setColor(Color.WHITE);
        debugTextPaint.setTextSize(50);


        firePosX=width/2.0f+offsetX;

        firePosY=height-2*ballRadius;
        newFirePosX = firePosX;

        double rad = Math.toRadians(12.125);

        section1 = (float)(Math.sin(1*rad)*ballRadius);
        section2 = (float)(Math.sin(3*rad)*ballRadius);
        section3 = (float)(Math.sin(5*rad)*ballRadius);
        section4 = ballRadius;

        increment1 = (float)(Math.sin(2*rad)*ballRadius);
        increment2 = (float)(Math.sin(4*rad)*ballRadius);
        increment3 = (float)(Math.sin(6*rad)*ballRadius);

        rand = new Random();
        blocks = new Block[xDim][yDim];
        blocksCopy = new Block[xDim][yDim];


        initBoard();

        debugRect = new RectF(left(getMinXPos(balls[0])), top(getMinYPos(balls[0])), right(getMaxXPos(balls[0])), bottom(getMaxYPos(balls[0])));
        debugRectPaint = new Paint();
        debugRectPaint.setColor(Color.YELLOW);
        debugRectPaint.setStrokeWidth(5);
        debugRectPaint.setAlpha(100);

        nextBall = new Ball(ballRadius,0,0);

        crosses = new HashMap<String, Cross>();

        ballBeforeMoving = new Ball();
        ballAfterMoving = new Ball();

    }

    public void initBoard() {
        if (game.getLevel() == -1 ) {
            for ( int y = 4; y < yDim; y++) {
                for ( int x = 4; x < xDim-3; x++ ) {
                    blocks[x][y] = new Block ( this, x,y, 42-2*(x+y));
                }
            }
        } else {
            for ( int y = 0; y < yDim; y++) {
                for (int x = 0; x < xDim; x++) {
                    blocks[x][y] = null;
                }
            }
            for ( int y = 0; y < yDim-1; y++) {
                for ( int x = 0; x < xDim; x++ ) {
                    if (rand.nextInt(10) <= game.getLevel() )  {
                        int v = rand.nextInt(5*game.getLevel())+1;
                        blocks[x][y] = new Block ( this, x,y, v);
                    }
                }
            }
        }

        //TODO: remove iteration
//        for ( int y = 0; y < yDim-1; y++) {
//            for (int x = 0; x < xDim; x++) {
//                blocks[x][y] = null;
//            }
//        }
//        blocks[2][0] = new Block ( this, 2,0, 1);
//        blocks[3][0] = new Block ( this, 3,0, 5);
//
//        blocks[0][2] = new Block ( this, 0,2, 7);
//        blocks[1][3] = new Block ( this, 1,3, 1);
//        blocks[2][3] = new Block ( this, 2,3, 5);
//        blocks[0][4] = new Block ( this, 0,4, 2);
//        blocks[1][4] = new Block ( this, 1,4, 8);
//        blocks[4][4] = new Block ( this, 4,4, 3);

        firePosX=width/2.0f+offsetX;

        firePosY=height-2*ballRadius;
        newFirePosX = firePosX;

        numBalls = 0;
        for (int i = 0; i < numInitBalls; i++) {
            addBall();
        };
        endOfRollingPhase = true;
        newFirePosSet = false;
    }



    private void addBall() {
        assert( numBalls < maxNumBalls);
        balls[numBalls] = new Ball(ballRadius, firePosX, firePosY);
        balls[numBalls].setPaint(ballPaint);
        numBalls++;
    }

    void draw(Canvas c) {

        // draw blocks
        for (int y=0; y<yDim; y++) {
            for ( int x=0; x<xDim; x++) {
                if (blocks[x][y]!=null) {
                    blocks[x][y].draw(c);
                }
            }
        }

        if (dirLineActive) {
            c.drawLine(newFirePosX,firePosY,dirLineX,dirLineY,dirLinePaint);
        }


        // draw balls
        for ( int b=0;b<numBalls; b++) {
            balls[b].draw(c);
        }


        // draw boundaries
        //left border
        c.drawLine(offsetX-boundaryPaint.getStrokeWidth()/2, offsetY+height, offsetX-boundaryPaint.getStrokeWidth()/2, offsetY, boundaryPaint);
        //top border
        c.drawLine(offsetX-boundaryPaint.getStrokeWidth()/2, offsetY,offsetX+width+boundaryPaint.getStrokeWidth()/2, offsetY,boundaryPaint);
        //right border
        c.drawLine(offsetX+width+boundaryPaint.getStrokeWidth()/2,offsetY,offsetX+width+boundaryPaint.getStrokeWidth()/2, offsetY+height, boundaryPaint);

        if (freeze) {
            c.drawText(freezeReason, 30f,300f, debugTextPaint);
        }

        if (drawDebugInfo) {
            for (int y = 0; y < yDim; y++) {
                c.drawLine(offsetX, bottom(y), width - offsetX, bottom(y), dirLinePaint);
            }
            for (int x = 0; x < xDim - 1; x++) {
                c.drawLine(right(x), offsetY, right(x), height + offsetY, dirLinePaint);
            }

            c.drawText("x = " + roundedString(ballBeforeMoving.getX(), 1000) + " -> " + roundedString(ballAfterMoving.getX(), 1000), 50, 1200, debugTextPaint);
            c.drawText("y = " + roundedString(ballBeforeMoving.getY(), 1000) + " -> " + roundedString(ballAfterMoving.getY(), 1000), 50, 1250, debugTextPaint);
            c.drawText("dx = " + roundedString(ballBeforeMoving.getDx(), 1000) + " -> " + roundedString(ballAfterMoving.getDx(), 1000), 50, 1300, debugTextPaint);
            c.drawText("dy = " + roundedString(ballBeforeMoving.getDy(), 1000) + " -> " + roundedString(ballAfterMoving.getDy(), 1000), 50, 1350, debugTextPaint);
            c.drawText("fireSpeed = " + roundedString(fireSpeedX, 1000) + " / " + roundedString(fireSpeedY, 1000), 50, 1400, debugTextPaint);
            c.drawText("firePus = " + roundedString(firePosX, 1000) + " / " + roundedString(firePosY, 1000), 50, 1450, debugTextPaint);


            float hy = horizontalReflectionLine(balls[0]);
            float hx = verticalReflectionLine(balls[0]);

            c.drawLine(offsetX, hy, offsetX + width, hy, dirLinePaint);
            c.drawLine(hx, offsetY, hx, offsetY + height, dirLinePaint);

            debugRect.left = left(getMinXPos(balls[0]));
            debugRect.top = top(getMinYPos(balls[0]));
            debugRect.right = right(getMaxXPos(balls[0]));
            debugRect.bottom = bottom(getMaxYPos(balls[0]));
            c.drawRect(debugRect, debugRectPaint);


            for (Cross cross : crosses.values()) {
                cross.draw(c);
            }
        }
    }

    void update() {
        if (freeze) return;
        updateCounter++;
        if ( updateCounter == updateCounterAtFreezeTime) {
            System.out.println(updateCounter); // good location for debugger
        }
        crosses.clear();
        if (fire) {
            if (fireCounter==0) updateCounter = 0;
            if (fireCounter % 5 == 0) {
                balls[nextFireBall].setSpeed(fireSpeedX,fireSpeedY);
                balls[nextFireBall].setPos(newFirePosX, firePosY);
                nextFireBall++;
                if (nextFireBall==numBalls) {
                    fire = false;
                }
            }
            fireCounter++;
        }


        if (ballRolling()) {
            //freeze = true;
            // move balls and handle collisions
            // check if possible collision within the next delta step
            for (int ballIndex = 0; ballIndex < numBalls; ballIndex++) {

                Ball currBall = balls[ballIndex];
                ballBeforeMoving.copy(currBall);

                float verticalReflectionLine = verticalReflectionLine(currBall);
                float horizontalReflectionLine = horizontalReflectionLine(currBall);

                int ballPrevXCell = getXPos(currBall);
                int ballPrevYCell = getYPos(currBall);
                int prevBallMinYPos = getMinYPos(currBall);
                int prevBallMaxYPos = getMaxYPos(currBall);
                int prevBallMaxXPos = getMaxXPos(currBall);
                int prevBallMinXPos = getMinXPos(currBall);

                float prevBallPosX = currBall.getX();
                float prevBallPosY = currBall.getY();

                currBall.update();

                float nextBallX = currBall.getX();
                float nextBallY = currBall.getY();
                int ballNextXCell = getXPos(currBall);
                int ballNextYCell = getYPos(currBall);

                boolean updateRequired = true;
                while (updateRequired) {
                    updateRequired = false;

                    if (currBall.movingUpwards()) {
                        if (ballTouchesBlockOnTop(currBall) ) {

                            float yDistanceToBlock = 0;
                            float xPosCrossSection = 0;
                            int hitBlockX = 0;
                            int hitBlockY = ballPrevYCell-1;
                            if (nextBallY<horizontalReflectionLine) {
                                yDistanceToBlock = prevBallPosY - horizontalReflectionLine;
                                xPosCrossSection = prevBallPosX + currBall.getDx() * Math.abs(yDistanceToBlock / currBall.getDy());
                                hitBlockX = getXBlock(xPosCrossSection);
                            } else {
                                hitBlockX = ballNextXCell;
                            }

                            drawCross("hitTop", xPosCrossSection, horizontalReflectionLine-ballRadius);


                            if ( inXRange(hitBlockX) && inYRange(hitBlockY)) {
                                if (cellOccupied(hitBlockX, hitBlockY)) {
                                    hit(hitBlockX, hitBlockY);

                                    if (ballOverlapsWithLeftNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                                        hit(hitBlockX - 1, hitBlockY);
                                    } else if (ballOverlapsWithRightNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                                        hit(hitBlockX + 1, hitBlockY);
                                    }

                                    currBall.mirrorHorizontally(horizontalReflectionLine);
                                } else  {
                                    float cx = right(hitBlockX);
                                    float cy = bottom(hitBlockY);
                                    if (ballTouchesBlockOnLeft(currBall)) {
                                        cx = left(hitBlockX);
                                        hitBlockX--;
                                    } else {
                                        hitBlockX++;
                                    }
                                    if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                                        hit(hitBlockX, hitBlockY);
                                        //determine the position of the ball when hitting the cross point cx/cy
                                        ballCollisionWithCorner(currBall, cx, cy, prevBallPosX, prevBallPosY);
                                    }


                                }
                            } else {
                                currBall.mirrorHorizontally(horizontalReflectionLine);
                            }
                            horizontalReflectionLine = horizontalReflectionLine(currBall);
                            nextBallX = currBall.getX();
                            nextBallY = currBall.getY();
                            updateRequired = true;
                        }
                    }


                    if (currBall.movingDownwards()
                            && ballTouchesBlockOnBottom(currBall)) {

                        float yDistanceToBlock = 0;
                        float xPosCrossSection = 0;
                        int hitBlockX = 0;
                        int hitBlockY = ballPrevYCell + 1;
                        if ( nextBallY>horizontalReflectionLine ) {
                            yDistanceToBlock = horizontalReflectionLine - prevBallPosY;
                            xPosCrossSection = prevBallPosX + currBall.getDx()*Math.abs(yDistanceToBlock/currBall.getDy());
                            hitBlockX = getXBlock(xPosCrossSection);
                        } else {
                            hitBlockX = ballNextXCell;
                        }
                        drawCross("hitBottom", xPosCrossSection, horizontalReflectionLine+ballRadius);
                        if ( inXRange(hitBlockX) && inYRange(hitBlockY)) {
                            if (cellOccupied(hitBlockX, hitBlockY)) {
                                hit(hitBlockX, hitBlockY);
                                if (ballOverlapsWithLeftNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                                    hit(hitBlockX-1, hitBlockY);
                                } else if (ballOverlapsWithRightNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                                    hit(hitBlockX + 1, hitBlockY);
                                }
                                currBall.mirrorHorizontally(horizontalReflectionLine);
                            } else {
                                float cx = right(hitBlockX);
                                float cy = top(hitBlockY);
                                if (ballTouchesBlockOnLeft(currBall)) {
                                    cx = left(hitBlockX);
                                    hitBlockX--;
                                } else {
                                    hitBlockX++;
                                }
                                if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                                    hit(hitBlockX, hitBlockY);
                                    //determine the position of the ball when hitting the cross point cx/cy
                                    ballCollisionWithCorner(currBall, cx, cy, prevBallPosX, prevBallPosY);
                                }

                            }
                        } else {
                            currBall.mirrorHorizontally(horizontalReflectionLine);
                        }

                        horizontalReflectionLine = horizontalReflectionLine(currBall);
                        nextBallX = currBall.getX();
                        nextBallY = currBall.getY();
                        updateRequired = true;
                    }


                    if (currBall.movingLeft()
                            && ballTouchesBlockOnLeft(currBall)) {

                        float xDistanceToBlock = 0;
                        float yPosCrossSection = 0;
                        int hitBlockX = ballPrevXCell-1;
                        int hitBlockY = 0;

                        if (nextBallX<verticalReflectionLine) {
                            xDistanceToBlock = prevBallPosX - verticalReflectionLine;
                            yPosCrossSection = prevBallPosY + currBall.getDy() * Math.abs(xDistanceToBlock / currBall.getDx());
                            hitBlockY = getYBlock(yPosCrossSection);
                        } else {
                            hitBlockY = ballNextYCell;
                        }
                        drawCross("hitLeft", verticalReflectionLine-ballRadius, yPosCrossSection);

                        if ( inXRange(hitBlockX) && inYRange(hitBlockY)) {
                            if (cellOccupied(hitBlockX, hitBlockY)) {
                                hit(hitBlockX, hitBlockY);
                                if (ballOverlapsWithUpperNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                                    hit(hitBlockX, hitBlockY - 1);
                                } else if (ballOverlapsWithLowerNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                                    hit(hitBlockX, hitBlockY + 1);
                                }
                                currBall.mirrorVertically(verticalReflectionLine);
                            } else {
                                float cx = right(hitBlockX);
                                float cy = bottom(hitBlockY);
                                if (ballTouchesBlockOnTop(currBall)) {
                                    cy = top(hitBlockX);
                                    hitBlockY--;
                                } else {
                                    hitBlockY++;
                                }
                                if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                                    hit(hitBlockX, hitBlockY);
                                    //determine the position of the ball when hitting the cross point cx/cy
                                    ballCollisionWithCorner(currBall, cx, cy, prevBallPosX, prevBallPosY);
                                }

                            }
                        } else {
                            currBall.mirrorVertically(verticalReflectionLine);
                        }

                        verticalReflectionLine = verticalReflectionLine(currBall);
                        nextBallX = currBall.getX();
                        nextBallY = currBall.getY();
                        updateRequired = true;
                    }


                    if (currBall.movingRight()
                            && ballTouchesBlockOnRight(currBall)) {

                        float xDistanceToBlock = 0;
                        float yPosCrossSection = 0;
                        int hitBlockX = ballPrevXCell+1;
                        int hitBlockY = 0;

                        if (nextBallX>verticalReflectionLine) {
                            xDistanceToBlock = verticalReflectionLine - prevBallPosX;
                            yPosCrossSection = prevBallPosY + currBall.getDy() * Math.abs(xDistanceToBlock / currBall.getDx());
                            hitBlockY = getYBlock(yPosCrossSection);
                        } else {
                            hitBlockY = ballNextYCell;
                        }
                        drawCross("hitRight", verticalReflectionLine+ballRadius, yPosCrossSection);

                        if ( inXRange(hitBlockX) && inYRange(hitBlockY)) {
                            if (cellOccupied(hitBlockX, hitBlockY)) {
                                hit(hitBlockX, hitBlockY);

                                if (ballOverlapsWithUpperNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                                    hit(hitBlockX, hitBlockY - 1);
                                } else if (ballOverlapsWithLowerNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                                    hit(hitBlockX, hitBlockY + 1);
                                }
                                currBall.mirrorVertically(verticalReflectionLine);
                            } else {
                                float cx = left(hitBlockX);
                                float cy = bottom(hitBlockY);
                                if (ballTouchesBlockOnTop(currBall)) {
                                    cy = top(hitBlockX);
                                    hitBlockY--;
                                } else {
                                    hitBlockY++;
                                }
                                if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                                    hit(hitBlockX, hitBlockY);
                                    //determine the position of the ball when hitting the cross point cx/cy
                                    ballCollisionWithCorner(currBall, cx, cy, prevBallPosX, prevBallPosY);
                                }
                            }
                        } else {
                            currBall.mirrorVertically(verticalReflectionLine);
                        }

                        verticalReflectionLine = verticalReflectionLine(currBall);
                        nextBallX = currBall.getX();
                        nextBallY = currBall.getY();
                        updateRequired = true;
                    }

                }
                if (currBall.getY() > firePosY) {
                    if (!newFirePosSet) {
                        newFirePosX = currBall.getX();
                        newFirePosSet = true;
                    }
                    currBall.setPos(newFirePosX, firePosY);
                    currBall.setSpeed(0,0);
                }
                ballAfterMoving.copy(currBall);

                if (!currBall.isStill()) {
                    float d = distance(ballBeforeMoving, ballAfterMoving);
                    if (d > 2 * normSpeed) {
                        currBall.setPaint(dirLinePaint);
                        freeze("ball "+ballIndex+" has been moved too far");
                    }
                    if (ballOnBlock(currBall)) {
                        currBall.setPaint(dirLinePaint);
                        freeze("ball "+ballIndex+" on block");
                    }
                }
            }
        } else if (!endOfRollingPhase) {
            endOfRollingPhase = true;
            actionAfterBAllRolling();
        }
    }

    private void freeze(String s) {
        freeze = true;
        freezeReason = s;
        for ( int b = 0; b < numBalls; b++) {
            balls[b].setSpeed(0,0);
        }
        endOfRollingPhase = true;
        updateCounterAtFreezeTime = updateCounter;
        System.out.println("updateCounter @freeze time="+updateCounter);
        updateCounter = 0;
    }

    private float distance(Ball b1, Ball b2) {
        double v1 = b1.getX()-b2.getX();
        double v2 = b1.getY()-b2.getY();
        return (float)Math.sqrt(v1*v1+v2*v2);
    }

    private boolean ballCollisionWithCorner(Ball currBall, float cx, float cy, float prevBallPosX, float prevBallPosY) {
        // determine the x1/y1 position of the ball where it hits the corner cx/cy
        // (or x2/y2, its quadratic and has 2 solutions)
        float dx = currBall.getDx();
        float dy = currBall.getDy();
        float x  = prevBallPosX;
        float y  = prevBallPosY;
        float r = ballRadius;
        float t1 = x - cx;
        float t2 = y - cy;
        float a = (dx*dx+dy*dy);
        float b = 2*t1*dx + 2*t2*dy;
        float c = t1*t1+t2*t2-r*r;
        float q = (float)Math.sqrt(b*b-4*a*c);
        float v1 = (-b + q )/ (2*a);
        float v2 = (-b - q )/(2*a);

        //
        // freeze = true;
        //the two solutions are:
        float x1 = x+v1*dx;
        float y1 = y+v1*dy;
        float x2 = x+v2*dx;
        float y2 = y+v2*dy;

        // if there is no valid solution, we assume the ball passes the corner without hitting it
        if (Float.isNaN(v1) && Float.isNaN(v2)) return false;

        //drawCross("collisionPoint2:",x2, y2);
        //select right solution (the first on the ball trajectory is the right one
        if ( Float.isNaN(v1)
                || (currBall.movingUpwards()   && y2>y1)
                || (currBall.movingDownwards() && y2<y1)
                || (currBall.movingLeft()      && x2>x1)
                || (currBall.movingRight()     && y2<x1)){
            x1 = x2;
            y1 = y2;
        }

        assert(!Float.isNaN(x1));

        // x1/y2 is position of ball when hitting the corner of the block
        //drawCross("collisionPoint1:", x1, y1);

        // determine the new direction ofter collision with the block
        // the normal of the reflection line (tangent of ball) has dir nx/ny
        float nx = cx-x1;
        float ny = cy-y1;

        // the direction (speed) vector moved to the cross point
        float px = cx-dx;
        float py = cy-dy;
        drawCross("startOfspeed:",px, py);


        // determine the intersection of the end of the direction vector to the normal line
        float v = (ny*py - ny*cy -nx*cx + nx*px)/(nx*nx+ny*ny);
        float sx = cx+v*nx;
        float sy = cy+v*ny;
        float wx = sx-px;
        float wy = sy-py;

        // shifting the distance vector by 2 yields in the end point of the new direction
        float ux = px+2*wx;
        float uy = py+2*wy;

        drawCross("endOfNewSpeed:",ux, uy);


        float newdx = ux-cx;
        float newdy = uy-cy;
        currBall.setSpeed(newdx, newdy);
        float fractOfLenToMoveInNewDirection = 1f-(float)Math.sqrt(((x1-x)*(x1-x)+(y1-y)*(y1-y))/(dx*dx+dy*dy));
        float newPosX = x1+fractOfLenToMoveInNewDirection*newdx;
        float newPosY = y1+fractOfLenToMoveInNewDirection*newdy;
        currBall.setPos(newPosX,newPosY);
        return true;
    }


    private float horizontalReflectionLine(Ball currBall) {
        float horizontalReflectionLine = 0;
        int minY = getMinYPos(currBall);
        int maxY = getMaxYPos(currBall);

        if (currBall.movingUpwards()) {
            if (minY > 0)
                horizontalReflectionLine = top(minY) + ballRadius;
            else
                horizontalReflectionLine = offsetY + ballRadius;
        }
        if (currBall.movingDownwards()) {
            if (maxY < yDim-1)
                horizontalReflectionLine = bottom(maxY) - ballRadius;
        }
        return horizontalReflectionLine;
    }

    private float verticalReflectionLine(Ball currBall) {
        float verticalReflectionLine = 0;
        int minX = getMinXPos(currBall);
        int maxX = getMaxXPos(currBall);


        if (currBall.movingRight()) {
            if (minX < xDim-1 )
                verticalReflectionLine = right(maxX) - ballRadius;
            else
                verticalReflectionLine = offsetX + width - ballRadius;
        }
        if (currBall.movingLeft()) {
            if (minX > 0 )
                verticalReflectionLine = left(minX) + ballRadius;
            else
                verticalReflectionLine = offsetX + ballRadius;
        }
    return verticalReflectionLine;
    }

    private boolean inXRange(int x) {
        return (0<=x && x < xDim);
    }
    private boolean inYRange(int y) {
        return (0<=y && y < yDim);
    }

    private boolean ballOverlapsWithUpperNeighbour(int x, int y, float yPosCrossSection) {
        if ( hasATopNeighbour(x,y)) {
            if (top(y)-yPosCrossSection <= section1) return true;
        }
        return false;
    }

    private boolean ballOverlapsWithLowerNeighbour(int x, int y, float yPosCrossSection) {
        if ( hasABottomNeighbour(x,y)) {
            if (yPosCrossSection-bottom(y) <= section1) return true;
        }
        return false;
    }

    private String roundedString(float v, int r) {
        float v1 = v*r;
        float v2 = Math.round(v1);
        float v3 = v2/(float)r;
        return Float.toString(Math.round(v*r)/(float)r);
    }

    private boolean ballOverlapsWithRightNeighbour(int x, int y, float xPosCrossSection) {
        if (hasARightNeighbour(x,y)) {
            if (right(x)-xPosCrossSection <= section1 ) return true;
        }
        return false;
    }

    private boolean ballOverlapsWithLeftNeighbour(int x, int y, float xPosCrossSection) {
        if (hasALeftNeighbour(x,y)) {
            if (xPosCrossSection-left(x) <= section1 ) return true;
        }
        return false;
    }

    private boolean hasALeftNeighbour(int x, int y) {
        return x > 1 && blocks[x - 1][y] != null;
    }
    private boolean hasARightNeighbour(int x, int y) {
        return x < xDim-1 && blocks[x + 1][y] != null;
    }
    private boolean hasATopNeighbour(int x, int y) {
        return y > 1 && blocks[x][y-1] != null;
    }
    private boolean hasABottomNeighbour(int x, int y) {
        return y < xDim-1 && blocks[x][y+1] != null;
    }

    private void hit(int x, int y) {
        if (freeze) return;
        if (inXRange(x) && inYRange(y)) {
            Block b = blocks[x][y];
            b.hit();
            if (b.getValue() == 0) {
                blocks[b.getX()][b.getY()] = null;
                game.addAnimation(new DissolveBlockAnimation(this, 10, b.getX(), b.getY()));
            } else {
                game.addAnimation(new HitBlockAnimation(this, 20, b.getX(), b.getY()));
            }
        }
    }

    private void drawCross(String s, float x, float y) {
        crosses.put(s,new Cross(x,y,s));
        //freeze = true;
    }

    private void actionAfterBAllRolling() {

        if (freeze) return;

        // check for game win
        if (gameBoardEmpty()) {
            game.increaselevel();
            initBoard();
        } else {
            if ( hasBlocksInRow(yDim-1) ) {
                game.setGameOver(false);
                for (int i = 0; i < 5 ; i++) {
                    game.addAnimation(new GameOverAnimation(this, 20*i, false));
                }
            } else {
                // shift all blocks downwards
                game.addAnimation(new BoardDropAnimation(this, 60));
                //float x = rand.nextFloat()*width+offsetX;
                //float y = rand.nextFloat()*height+offsetY;
                //touchDown(x,y);
                //touchRelease(x,y);
            }
        }
    }

    private boolean gameBoardEmpty() {
        for (int x=0; x<xDim; x++) {
            if (hasBlocksInColumn(x)) return false;
        }
        return true;
    }

    private boolean hasBlocksInRow(int y) {
        for (int x=0; x<xDim; x++) {
            if (blocks[x][y] != null ) return true;
        }
        return false;
    }

    private boolean hasBlocksInColumn(int x) {
        for (int y=0; y<yDim; y++) {
            if (blocks[x][y] != null ) return true;
        }
        return false;
    }

    private void fire() {
        fireCounter = 0;
        fire = true;
        nextFireBall = 0;
        firePosX = newFirePosX;
        newFirePosSet = false;

        // store game board for later debugging
        for (int x=0; x < xDim; x++) {
            for ( int y=0; y <yDim; y++ ) {
                if ( blocks[x][y]!=null)
                    blocksCopy[x][y] = new Block(blocks[x][y]);
                else
                    blocksCopy[x][y] = null;
            }
        }
    }


    private void setFireSpeed(float dx, float dy) {
        float len = (float)Math.sqrt((dx*dx) + (dy*dy));
        fireSpeedX = normSpeed * dx/len;
        fireSpeedY = normSpeed * dy/len;
    }


    private boolean ballRolling() {
        boolean result = false;
        for ( int b = 0; b < numBalls; b++) {
            if (!balls[b].isStill()) return true;
        }
        return result;
    }

    public void touchDown(float x, float y) {

        if (freeze) {
            freeze = false;
            //reset game board
            for (int xi=0; xi < xDim; xi++) {
                for ( int yi=0; yi <yDim; yi++ ) {
                    blocks[xi][yi] = blocksCopy[xi][yi];
                }
            }
            // reset balls
            newFirePosX = firePosX;
            reusePreviousFireSpeed = true;
            for (int b = 0; b < numBalls; b++) {
                balls[b].setPos(firePosX, firePosY);
                balls[b].setPaint(ballPaint);
            }
        }

        if (!ballRolling()) {
            if (y>0.9f*height+offsetY) {
                y = 0.9f*height+offsetY;
            }
            dirLineActive = true;
            dirLineX = x;
            dirLineY = y;
        }
    }

    public void touchMove(float x, float y) {
        if (dirLineActive) {
            if (y>0.9f*height+offsetY) {
                y = 0.9f*height+offsetY;
            }
            dirLineX = x;
            dirLineY = y;
        }
    }

    public void touchRelease(float x, float y) {
        if (dirLineActive) {
            if (y>0.9f*height+offsetY) {
                y = 0.9f*height+offsetY;
            }

            if ( !reusePreviousFireSpeed) {
                float dx = x - firePosX;
                float dy = y - firePosY;

                //TODO: remove
                //dx = 5.422f;
                //dy = -8.402f;
                //newFirePosX = 410.416f;


                setFireSpeed(dx, dy);
            }
            fire();
            dirLineActive = false;
            endOfRollingPhase = false;
        }
    }

    public float getBlockX(int x) {
        return offsetX+x*blockWidth;
    }

    public float getBlockY(int y) {
        return offsetY+y*blockHeight;
    }

    public float getBlockWidth() {
        return blockWidth;
    }

    public float getBlockHeight() {
        return blockHeight;
    }


    public float bottom(int y) {
        return getBlockY(y+1);
    }

    public float left(int x) {
        return getBlockX(x);
    }

    public float right(int x) {
        return getBlockX(x+1);
    }
    public float top(int y) {
        return getBlockY(y);
    }

    public void moveAllBlocks(float dy) {
        for ( int y = 0; y < yDim; y++) {
            for (int x = 0; x < xDim; x++) {
                if (blocks[x][y] != null) blocks[x][y].moveBlock(dy);
            }
        }
    }

    public void dropAllBlocksByOneCell() {
        for ( int y = yDim-2; y >= 0; y--) {
            for (int x = 0; x < xDim; x++) {
                blocks[x][y + 1] = blocks[x][y];
                if (blocks[x][y + 1] != null) {
                    blocks[x][y + 1].setCoords(x, y + 1);
                }
            }
        }
        for (int x = 0; x < xDim; x++) {
            blocks[x][0] = null;
        }
    }

    public float getHeight() {
        return height;
    }
    public float getWidth() {
        return width;
    }

    public float getYOffset() {
        return offsetY;
    }

    public float getXOffset() {
        return offsetX;
    }


    public int getXPos(Ball b) {
        return (int)((b.getX()-offsetX)/blockWidth);
    }

    public int getYPos(Ball b) {
        return (int)((b.getY()-offsetY)/blockHeight);
    }
    public int getMinXPos(Ball b) {
        float minX = b.getX()-ballRadius;
        int x = 0;
        if (minX < offsetX ) x = -1;
        else x = (int)((minX-offsetX)/blockWidth);
        return x;
    }

    public int getMaxXPos(Ball b) {
        float maxX = b.getX()+ballRadius;
        int x = (int)((maxX-offsetX)/blockWidth);
        return x;
    }

    public int getMinYPos(Ball b) {
        float minY = b.getY()-ballRadius;
        int y = 0;
        if (minY < offsetY) y = -1;
        else y = (int)((minY-offsetY)/blockHeight);
        return y;
    }

    public int getMaxYPos(Ball b) {
        float maxY = b.getY()+ballRadius;
        int y = (int)((maxY-offsetY)/blockHeight);

        return y;
    }

    private boolean hasBlock(int x, int y) {
        if (x<0 || y<0 || x>=xDim) return true;
        return (inXRange(x) && inXRange(y) && blocks[x][y]!=null);
    }

    public boolean ballTouchesBlockOnTop(Ball ball) {
        float x = ball.getX()-offsetX;
        float y = ball.getY()-offsetY;
        int centerX = (int)(x/blockWidth);
        int centerY = (int)(y/blockHeight);

        if ( cellOccupied(centerX,centerY-1) && (ball.getY()-ballRadius<top(centerY)))    return true;
        if ( cellOccupied(centerX+1, centerY-1) && distanceSquare(ball,right(centerX),top(centerY)) <= radiusSquare) return true;
        if ( cellOccupied(centerX-1, centerY-1) && distanceSquare(ball,left(centerX), top(centerY)) <= radiusSquare) return true;

        return false;
    }


    public boolean ballTouchesBlockOnBottom(Ball ball) {
        float x = ball.getX()-offsetX;
        float y = ball.getY()-offsetY;
        int centerX = (int)(x/blockWidth);
        int centerY = (int)(y/blockHeight);

        if ( cellOccupied(centerX,centerY+1) && (ball.getY()+ballRadius>bottom(centerY))) return true;
        if ( cellOccupied(centerX+1, centerY+1) && distanceSquare(ball,right(centerX),bottom(centerY)) <= radiusSquare) return true;
        if ( cellOccupied(centerX-1, centerY+1) && distanceSquare(ball,left(centerX), bottom(centerY)) <= radiusSquare) return true;

        return false;
    }

    public boolean ballTouchesBlockOnLeft(Ball ball) {
        float x = ball.getX()-offsetX;
        float y = ball.getY()-offsetY;
        int centerX = (int)(x/blockWidth);
        int centerY = (int)(y/blockHeight);

        if ( cellOccupied(centerX-1, centerY) && (ball.getX()-ballRadius)<left(centerX))   return true;
        if ( cellOccupied(centerX-1, centerY+1) && distanceSquare(ball,left(centerX), bottom(centerY)) <= radiusSquare) return true;
        if ( cellOccupied(centerX-1, centerY-1) && distanceSquare(ball,left(centerX), top(centerY)) <= radiusSquare) return true;

        return false;
    }

    public boolean ballTouchesBlockOnRight(Ball ball) {
        float x = ball.getX()-offsetX;
        float y = ball.getY()-offsetY;
        int centerX = (int)(x/blockWidth);
        int centerY = (int)(y/blockHeight);

        if ( cellOccupied(centerX+1, centerY) && (ball.getX()+ballRadius)>right(centerX))  return true;
        if ( cellOccupied(centerX+1, centerY-1) && distanceSquare(ball,right(centerX),top(centerY)) <= radiusSquare) return true;
        if ( cellOccupied(centerX+1, centerY+1) && distanceSquare(ball,right(centerX),bottom(centerY)) <= radiusSquare) return true;

        return false;
    }

    public boolean ballOnBlock(Ball ball) {
        float x = ball.getX()-offsetX;
        float y = ball.getY()-offsetY;
        int centerX = (int)(x/blockWidth);
        int centerY = (int)(y/blockHeight);
        return cellOccupied(centerX,centerY);
    }

    private boolean cellOccupied(int x, int y) {
        if(!inXRange(x)) return true; // left and right borders
        if(y<0) return true; // top border
        if(y>=yDim) return false; // lower part of game board
        return blocks[x][y]!=null; //cell in game board
    }

    private boolean cellOccupiedWithRealBlock(int x, int y) {
        if(!inXRange(x)) return false; // left and right borders
        return blocks[x][y]!=null; //cell in game board
    }



    private float distanceSquare(Ball ball, float x, float y) {
        float dx = ball.getX()-x;
        float dy = ball.getY()-y;
        return dx*dx+dy*dy;
    }

    private int getXBlock(float x) {
        return (int)((x-offsetX)/blockWidth);
    }
    private int getYBlock(float y) {
        return (int)((y-offsetY)/blockHeight);
    }


}
