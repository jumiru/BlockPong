package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Random;

public class GameBoard {

    private final float dirLineLength;
    private Ball ballAfterMoving;
    private Ball ballBeforeMoving;
    private String freezeReason = "";
    private boolean reusePreviousFireSpeed;
    private int updateCounter;
    private int updateCounterAtFreezeTime;
    private int freezeBall;


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

    enum Content {
        NO_BLOCK, HIT_BLOCK, HIT_BORDER } ;

    private class BB {

        //
        //
        //     TL |  T  | TR
        //     ---------------
        //      L |  C  | R
        //     ---------------
        //      BL|  B  | BR

        public BB() {
            clear();
        }

        public Content TL;
        public Content T;
        public Content TR;
        public Content L;
        public Content C;
        public Content R;
        public Content BL;
        public Content B;
        public Content BR;

        public int x;
        public int y;


        public void clear() {
            TL = Content.NO_BLOCK;
            T = Content.NO_BLOCK;
            TR = Content.NO_BLOCK;
            L = Content.NO_BLOCK;
            C = Content.NO_BLOCK;
            R = Content.NO_BLOCK;
            BL = Content.NO_BLOCK;
            B = Content.NO_BLOCK;
            BR = Content.NO_BLOCK;
        }

        public void setCenter(int cx, int cy) {
            x = cx;
            y = cy;
        }

        public boolean anyHit() {
            return (TL!=Content.NO_BLOCK || T!=Content.NO_BLOCK || TR!=Content.NO_BLOCK
            || L!=Content.NO_BLOCK || C!=Content.NO_BLOCK || R!=Content.NO_BLOCK
            || BL!=Content.NO_BLOCK || B!=Content.NO_BLOCK || BR!=Content.NO_BLOCK);
        }

        public void shiftDown() {
            BL = L;
            B = C;
            BR = R;
            L = TL;
            C = T;
            R = TR;
            TR = T = TL = Content.NO_BLOCK;
            y--;
        }

        public void shiftUp() {
            TL = L;
            T = C;
            TR = R;
            L = BL;
            C = B;
            R = BR;
            BL = B = BR = Content.NO_BLOCK;
            y++;
        }

        public void shiftRight() {
            TR = T;
            R = C;
            BR = B;
            T=TL;
            C=L;
            B=BL;
            TL = L = BL = Content.NO_BLOCK;
            x--;
        }

        public void shiftLeft() {
            TL = T;
            L = C;
            BL = B;
            T = TR;
            C = R;
            B = BR;
            TR = R = BR = Content.NO_BLOCK;
            x++;
        }

        public boolean hitBlockOnTop() {
            return (TL!=Content.NO_BLOCK  || T!=Content.NO_BLOCK || TR!=Content.NO_BLOCK);
        }

        public boolean hitBlockOnBottom() {
            return (BL!=Content.NO_BLOCK || B!=Content.NO_BLOCK || BR!=Content.NO_BLOCK);
        }

        public boolean hitBlockOnLeft() {
            return (TL!=Content.NO_BLOCK || L!=Content.NO_BLOCK || BL!=Content.NO_BLOCK);
        }

        public boolean hitBlockOnRight() {
            return (TR!=Content.NO_BLOCK || R!=Content.NO_BLOCK || BR!=Content.NO_BLOCK);
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

    private boolean debugSupport;

    private BB bb;


    public GameBoard(Game game, float width, float height, float offsetX, float offsetY) {
        this.game = game;
        this.width = width;
        this.height = height;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        maxNumBalls = 100;
        normSpeed = 25;
        numInitBalls = 20;
        xDim = 11; //11
        yDim = 11; //11
        ballRadius = 23; // 20
        dirLineLength = 1.3f*width;
        debugSupport = true;

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

        nextBall = new Ball(ballRadius,0,0,0);

        crosses = new HashMap<String, Cross>();

        ballBeforeMoving = new Ball();
        ballAfterMoving = new Ball();

        bb = new BB();
        updateCounterAtFreezeTime = -1;

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
            for ( int y = 0; y < yDim-2; y++) {
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
            addBall(i);
        };
        endOfRollingPhase = true;
        newFirePosSet = false;
    }



    private void addBall(int index) {
        assert( numBalls < maxNumBalls);
        balls[numBalls] = new Ball(ballRadius, firePosX, firePosY, index);
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

        if (dirLineActive || debugSupport) {
            drawDirLine(c);
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

        if (debugSupport) {
            for (int y = 0; y < yDim; y++) {
                c.drawLine(offsetX, bottom(y), width - offsetX, bottom(y), dirLinePaint);
            }
            for (int x = 0; x < xDim - 1; x++) {
                c.drawLine(right(x), offsetY, right(x), height + offsetY, dirLinePaint);
            }

            c.drawText("x/y = " + roundedString(balls[0].getX(), 1000) + " / " + roundedString(balls[0].getY(), 1000), 50, 1200, debugTextPaint);
            c.drawText("dx/dy = " + roundedString(balls[0].getDx(), 1000) + " / " + roundedString(balls[0].getDy(), 1000), 50, 1300, debugTextPaint);



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

    private void drawDirLine(Canvas c) {
        float x0 = newFirePosX;
        float y0 = firePosY;
        float x1 = dirLineX;
        float y1 = dirLineY;

        float lenOfSelection = (float)Math.sqrt((x1-x0)*(x1-x0) + (y1-y0)*(y1-y0));

        float vx = (x1-x0)/lenOfSelection;
        float vy = (y1-y0)/lenOfSelection;

        x1 = x0+vx*dirLineLength;
        y1 = y0+vy*dirLineLength;

        float leftMirrorLine = offsetX+ballRadius;
        float rightMirrorLine = offsetX+width-ballRadius;

        if (x1 < leftMirrorLine) {
            float y2 = y1;
            float xDistToBorder = x0-leftMirrorLine;
            float entireX = x0-x1;
            float xDistBehindBorder = entireX-xDistToBorder;
            float yDistToIntersection = y0+Math.signum(vx)*vy/vx*xDistToBorder;
            x1 = leftMirrorLine;
            y1 = yDistToIntersection;
            float x2 = leftMirrorLine+xDistBehindBorder;

            c.drawLine(x1,y1,x2,y2,dirLinePaint);

        } else if (x1 > rightMirrorLine) {
            float y2 = y1;
            float xDistToBorder = rightMirrorLine-x0;
            float entireX = x1-x0;
            float xDistBehindBorder = entireX-xDistToBorder;
            float yDistToIntersection = y0+Math.signum(vx)*vy/vx*xDistToBorder;
            x1 = rightMirrorLine;
            y1 = yDistToIntersection;
            float x2 = rightMirrorLine-xDistBehindBorder;

            c.drawLine(x1,y1,x2,y2,dirLinePaint);
        }

        c.drawLine(x0,y0,x1,y1,dirLinePaint);
    }

    void update() {
        if (freeze) return;
        updateCounter++;

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

                float prevBallPosX = currBall.getX();
                float prevBallPosY = currBall.getY();

                currBall.update();

                float nextBallX = currBall.getX();
                float nextBallY = currBall.getY();
                int ballNextXCell = getXPos(currBall);
                int ballNextYCell = getYPos(currBall);


                if ( updateCounter == updateCounterAtFreezeTime && freezeBall==ballIndex) {
                    System.out.println(updateCounter); // good location for debugger
                }

                boolean firstRound = true;

                boolean updateRequired = true;
                boolean collisionPerformed = false;


                while (updateRequired) {
                    updateRequired = false;

                    boolean movingUp;
                    boolean movingDown;
                    boolean movingLeft;
                    boolean movingRight;

                    if (firstRound) {
                        movingUp = currBall.movingMainlyUpwards();
                        movingDown = currBall.movingMainlyDownwards();
                        movingLeft = currBall.movingMainlyLeft();
                        movingRight = currBall.movingMainlyRight();
                        if (currBall.scheduledMirroring()) {
                            freeze("ball 0 has scheduled mirrorings", ballIndex);
                            break;
                        }
                    } else {
                        movingUp = currBall.movingUpwards() && !currBall.movingMainlyUpwards();
                        movingDown = currBall.movingDownwards() && !currBall.movingMainlyDownwards();
                        movingLeft = currBall.movingLeft() && !currBall.movingMainlyLeft();
                        movingRight = currBall.movingRight() && !currBall.movingMainlyRight();
                    }


                    if (ballOnBlock(currBall)) {
                        if (firstRound) {
                            boolean hitCornerOnBottomWhileMovingHorizontally = (ballNextXCell != ballPrevXCell) && (ballNextYCell == ballPrevYCell) && (prevBallPosY > horizontalReflectionLine) && (nextBallY > horizontalReflectionLine);
                            boolean hitCornerOnTopWhileMovingHorizontally = (ballNextXCell != ballPrevXCell) && (ballNextYCell == ballPrevYCell) && (prevBallPosY < horizontalReflectionLine) && (nextBallY < horizontalReflectionLine);
                            boolean hitCornerOnLeftWhileMovingVertically = (ballNextXCell == ballPrevXCell) && (ballNextYCell != ballPrevYCell) && (prevBallPosX < verticalReflectionLine) && (nextBallX < verticalReflectionLine);
                            boolean hitCornerOnRightWhileMovingVertically = (ballNextXCell == ballPrevXCell) && (ballNextYCell != ballPrevYCell) && (prevBallPosX > verticalReflectionLine) && (nextBallX > verticalReflectionLine);

                            if (hitCornerOnBottomWhileMovingHorizontally) {
                                float cx = (ballPrevXCell < ballNextXCell) ? left(ballNextXCell) : right(ballNextXCell);
                                float cy = bottom(ballNextYCell);
                                hit(ballNextXCell, ballNextYCell + 1);
                                //determine the position of the ball when hitting the cross point cx/cy
                                ballCollisionWithCorner(currBall, cx, cy, prevBallPosX, prevBallPosY);
                                collisionPerformed = true;
                                continue;
                            } else if (hitCornerOnTopWhileMovingHorizontally) {
                                float cx = (ballPrevXCell < ballNextXCell) ? left(ballNextXCell) : right(ballNextXCell);
                                float cy = top(ballNextYCell);
                                hit(ballNextXCell, ballNextYCell - 1);
                                //determine the position of the ball when hitting the cross point cx/cy
                                ballCollisionWithCorner(currBall, cx, cy, prevBallPosX, prevBallPosY);
                                collisionPerformed = true;
                                continue;
                            } else if (hitCornerOnLeftWhileMovingVertically) {
                                float cx = left(ballNextXCell);
                                float cy = (ballPrevYCell < ballNextYCell) ? top(ballNextYCell) : bottom(ballNextYCell);
                                hit(ballNextXCell - 1, ballNextYCell);
                                //determine the position of the ball when hitting the cross point cx/cy
                                ballCollisionWithCorner(currBall, cx, cy, prevBallPosX, prevBallPosY);
                                collisionPerformed = true;
                                continue;
                            } else if (hitCornerOnRightWhileMovingVertically) {
                                float cx = right(ballNextXCell);
                                float cy = (ballPrevYCell < ballNextYCell) ? top(ballNextYCell) : bottom(ballNextYCell);
                                hit(ballNextXCell + 1, ballNextYCell);
                                //determine the position of the ball when hitting the cross point cx/cy
                                ballCollisionWithCorner(currBall, cx, cy, prevBallPosX, prevBallPosY);
                                collisionPerformed = true;
                                continue;
                            }
                        }

                        if (movingUp && bb.hitBlockOnTop()) {
                            boolean currBallOnBlock = ballOnBlock(currBall);
                            boolean ballCenterStillBeforeBlockBorder = (bb.C==Content.NO_BLOCK)
                                    && (ballNextYCell == ballPrevYCell)
                                    && (horizontalReflectionLine > nextBallY && nextBallY > top(ballNextYCell));

                            if (ballCenterStillBeforeBlockBorder) {
                                bb.shiftDown();
                            }


                            float yDistanceToBlock = prevBallPosY - horizontalReflectionLine;
                            float xPosCrossSection = prevBallPosX + currBall.getDx() * Math.abs(yDistanceToBlock / currBall.getDy());
                            int hitBlockXOfCrossSection = getXBlock(xPosCrossSection);
                            int hitBlockY = bb.y;
                            int hitBlockX = hitBlockXOfCrossSection;

                            if (cellType(hitBlockX, hitBlockY)!=Content.NO_BLOCK) {
                                //hitting a block side, i.e. simple reflection is applied
                                // case I: block on top of xPosCrossSection (xPCS)
                                // block:     |XXXXXXXXXX|XXXXXXXXXX|      (the right block is optional)
                                //            |XXXXXXXXXX|XXXXXXXXXX|
                                //
                                // HRL    -------------+-------------------------
                                //                       xPCS
                                if (bb.C != Content.HIT_BORDER) {
                                    hit(hitBlockX, hitBlockY);
                                    if (ballOverlapsWithLeftNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                                        hit(hitBlockX - 1, hitBlockY);
                                    } else if (ballOverlapsWithRightNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                                        hit(hitBlockX + 1, hitBlockY);
                                    }
                                }
                                currBall.mirrorHorizontally(prevBallPosX, prevBallPosY, horizontalReflectionLine, verticalReflectionLine);
                            } else if (!currBall.scheduledMirroring()) {
                                // hitting a block corner (intersection point of ball and horicontalReflectionLine lies next to a solid block
                                // case I: block on top of xPosCrossSection (xPCS)
                                // block:     XXXXXXXXXXXX
                                //            XXXXXXXXXXXX
                                //
                                // HRL    -------------------+------------
                                //                       xPCS
                                //
                                Block blk = getCornerHitBlock(prevBallPosX, prevBallPosY, nextBallX, nextBallY);
                                if (blk != null ) {
                                    hitBlockX = blk.getX();
                                    hitBlockY = blk.getY();

                                    if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                                        hit(hitBlockX, hitBlockY);
                                        //determine the position of the ball when hitting the cross point cx/cy
                                        ballCollisionWithCorner(currBall, blk.getHitCornerX(), blk.getHitCornerY(), prevBallPosX, prevBallPosY);
                                        collisionPerformed = true;
                                    }
                                }
                            }
                        }





                        if (movingDown && bb.hitBlockOnBottom()) {

                            // it must be a corner hit if previous yPosition was already > horizontalReflectionLine, otherwise it would have been reflected in previous step already
                            //
                            //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  hRL
                            //                   prev     next
                            //                         |XXXXXXXXXXX
                            //                         |

                            boolean ballCenterStillBeforeBlockBorder = (bb.C==Content.NO_BLOCK) && (ballNextYCell == ballPrevYCell)
                                    && (horizontalReflectionLine < nextBallY && nextBallY < bottom(ballNextYCell));

                            if (ballCenterStillBeforeBlockBorder) {
                                bb.shiftUp();
                            }

                            float yDistanceToBlock = horizontalReflectionLine - prevBallPosY;
                            float xPosCrossSection = prevBallPosX + currBall.getDx() * Math.abs(yDistanceToBlock / currBall.getDy());
                            int hitBlockXOfCrossSection = getXBlock(xPosCrossSection);
                            int hitBlockY = bb.y;
                            int hitBlockX = hitBlockXOfCrossSection;

                            if (cellType(hitBlockX, hitBlockY)!=Content.NO_BLOCK) {
                                if (bb.C != Content.HIT_BORDER) {
                                    hit(hitBlockX, hitBlockY);
                                    if (ballOverlapsWithLeftNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                                        hit(hitBlockX - 1, hitBlockY);
                                    } else if (ballOverlapsWithRightNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                                        hit(hitBlockX + 1, hitBlockY);
                                    }
                                }
                                currBall.mirrorHorizontally(prevBallPosX, prevBallPosY, horizontalReflectionLine, verticalReflectionLine);
                            } else if (!currBall.scheduledMirroring()) {
                                Block blk = getCornerHitBlock(prevBallPosX, prevBallPosY, nextBallX, nextBallY);
                                if (blk != null ) {
                                    hitBlockX = blk.getX();
                                    hitBlockY = blk.getY();
                                    //                                float cx = right(hitBlockX);
                                    //                                float cy = top(hitBlockY);
                                    //                                if (ballTouchesBlockOnLeft(currBall) ||  currBall.movingLeft() && bottomRightCornerInRadius(currBall)) {
                                    //                                    cx = left(hitBlockX);
                                    //                                    hitBlockX--;
                                    //                                } else {
                                    //                                    hitBlockX++;
                                    //                                }
                                    if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                                        hit(hitBlockX, hitBlockY);
                                        //determine the position of the ball when hitting the cross point cx/cy
                                        ballCollisionWithCorner(currBall, blk.getHitCornerX(), blk.getHitCornerY(), prevBallPosX, prevBallPosY);
                                        collisionPerformed = true;
                                    }
                                }
                            }
                        }




                        if (movingLeft && bb.hitBlockOnLeft()) {

                            boolean ballCenterStillBeforeBlockBorder = (bb.C==Content.NO_BLOCK) && (ballNextXCell == ballPrevXCell)
                                    && (verticalReflectionLine > nextBallX && nextBallX > left(ballNextXCell));

                            if (ballCenterStillBeforeBlockBorder) {
                                bb.shiftRight();
                            }


                            float xDistanceToBlock = Math.abs(verticalReflectionLine - prevBallPosX);
                            float yPosCrossSection = prevBallPosY + currBall.getDy() * Math.abs(xDistanceToBlock / currBall.getDx());
                            int hitBlockYOfCrossSection = getYBlock(yPosCrossSection);
                            int hitBlockY = hitBlockYOfCrossSection;
                            int hitBlockX = bb.x;

                            if (cellType(hitBlockX, hitBlockY)!=Content.NO_BLOCK) {
                                if (bb.C != Content.HIT_BORDER) {
                                    hit(hitBlockX, hitBlockY);
                                    if (ballOverlapsWithUpperNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                                        hit(hitBlockX, hitBlockY-1);
                                    } else if (ballOverlapsWithLowerNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                                        hit(hitBlockX, hitBlockY+1);
                                    }
                                }
                                currBall.mirrorVertically(prevBallPosX, prevBallPosY, verticalReflectionLine, horizontalReflectionLine);
                            } else if (!currBall.scheduledMirroring()) {
                                Block blk = getCornerHitBlock(prevBallPosX, prevBallPosY, nextBallX, nextBallY);
                                if (blk != null ) {
                                    hitBlockX = blk.getX();
                                    hitBlockY = blk.getY();
                                    //                                float cx = right(hitBlockX);
                                    //                                float cy = bottom(hitBlockY);
                                    //                                if (ballTouchesBlockOnTop(currBall) || currBall.movingUpwards() && topRightCornerInRadius(currBall)) {
                                    //                                    cy = top(hitBlockY);
                                    //                                    hitBlockY--;
                                    //                                } else {
                                    //                                    hitBlockY++;
                                    //                                }
                                    if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                                        hit(hitBlockX, hitBlockY);
                                        //determine the position of the ball when hitting the cross point cx/cy
                                        ballCollisionWithCorner(currBall, blk.getHitCornerX(), blk.getHitCornerY(), prevBallPosX, prevBallPosY);
                                        collisionPerformed = true;
                                    }
                                }
                            }
                        }




                        if (movingRight && bb.hitBlockOnRight()) {

                            boolean ballCenterStillBeforeBlockBorder = (bb.C==Content.NO_BLOCK) && (ballNextXCell == ballPrevXCell)
                                    && (verticalReflectionLine < nextBallX && nextBallX < right(ballNextXCell));

                            if (ballCenterStillBeforeBlockBorder) {
                                bb.shiftLeft();
                            }

                            float xDistanceToBlock = Math.abs(verticalReflectionLine - prevBallPosX);
                            float yPosCrossSection = prevBallPosY + currBall.getDy() * Math.abs(xDistanceToBlock / currBall.getDx());
                            int hitBlockYOfCrossSection = getYBlock(yPosCrossSection);
                            int hitBlockY = hitBlockYOfCrossSection;
                            int hitBlockX = bb.x;

                            if (cellType(hitBlockX, hitBlockY)!=Content.NO_BLOCK) {
                                if (bb.C != Content.HIT_BORDER) {
                                    hit(hitBlockX, hitBlockY);
                                    if (ballOverlapsWithUpperNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                                        hit(hitBlockX, hitBlockY-1);
                                    } else if (ballOverlapsWithLowerNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                                        hit(hitBlockX, hitBlockY+1);
                                    }
                                }
                                currBall.mirrorVertically(prevBallPosX, prevBallPosY,verticalReflectionLine, horizontalReflectionLine);
                            } else if (!currBall.scheduledMirroring()) {
                                Block blk = getCornerHitBlock(prevBallPosX, prevBallPosY, nextBallX, nextBallY);
                                if (blk != null ) {
                                    hitBlockX = blk.getX();
                                    hitBlockY = blk.getY();
                                    //                                float cx = left(hitBlockX);
                                    //                                float cy = bottom(hitBlockY);
                                    //                                if (ballTouchesBlockOnTop(currBall) || currBall.movingUpwards()&&bottomRightCornerInRadius(currBall) ){ //topRightCornerInRadius(currBall)) {
                                    //                                    cy = top(hitBlockY);
                                    //                                    hitBlockY--;
                                    //                                } else if (ballTouchesBlockOnBottom(currBall)) {
                                    //                                    hitBlockY++;
                                    //                                }
                                    if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                                        hit(hitBlockX, hitBlockY);
                                        //determine the position of the ball when hitting the cross point cx/cy
                                        ballCollisionWithCorner(currBall, blk.getHitCornerX(), blk.getHitCornerY(), prevBallPosX, prevBallPosY);
                                        collisionPerformed = true;
                                    }
                                }
                            }
                        }

                        // last resort
                        if (!collisionPerformed && firstRound) {
                            updateRequired = true;
                        }
                    }

                    if (currBall.scheduledMirroring()) {
                        // do the horizontal and vertical reflections now
                        currBall.performMirroring();
                        collisionPerformed = true;
                    }
                    verticalReflectionLine = verticalReflectionLine(currBall);
                    horizontalReflectionLine = horizontalReflectionLine(currBall);
                    nextBallX = currBall.getX();
                    nextBallY = currBall.getY();
                    firstRound = false;
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

                if (debugSupport && !currBall.isStill()) {
                    float d = distance(ballBeforeMoving, ballAfterMoving);
                    if (d > 2 * normSpeed) {
                        freeze("ball "+ballIndex+" has been moved too far", ballIndex);
                    }
                    else if (ballOnBlock(currBall)) {
                        freeze("ball "+ballIndex+" on block", ballIndex);
                    } else {
                        float normSpeedSquare = normSpeed * normSpeed;
                        float currSpeedSquare = currBall.getDx() * currBall.getDx() + currBall.getDy() * currBall.getDy();
                        if (currSpeedSquare < normSpeedSquare - 1) {
                            freeze("ball " + ballIndex + " is too slow expected=" + roundedString(normSpeedSquare, 100) +
                                    " actual=" + roundedString((float) Math.sqrt(currSpeedSquare), 100), ballIndex);
                        }
                        else if (currSpeedSquare > normSpeedSquare + 1) {
                            freeze("ball " + ballIndex + " is too fast expected=" + roundedString(normSpeedSquare, 100) +
                                    " actual=" + roundedString((float) Math.sqrt(currSpeedSquare), 100), ballIndex);
                        }
                    }
                }
            }
        } else if (!endOfRollingPhase) {
            endOfRollingPhase = true;
            actionAfterBallRolling();
        }
    }

    private Block getCornerHitBlock(float prevBallPosX, float prevBallPosY, float nextBallX, float nextBallY) {
        int cx1 = getXBlock(prevBallPosX);
        int cy1 = getYBlock(prevBallPosY);
        int cx2 = getXBlock(nextBallX);
        int cy2 = getYBlock(nextBallY);

        int xFrom = Math.min(cx1,cx2);
        int xTo   = Math.max(cx1, cx2);
        int yFrom = Math.min(cy1,cy2);
        int yTo   = Math.max(cy1,cy2);

        //getMinXPos(ball1);
        //getMaxXPos(ball1);
        //getMinYPos(ball2);
        //getMaxYPos(ball2);

        // widen the search area
        xFrom = Math.max(0,xFrom-1);
        xTo   = Math.min(xTo+1, xDim-1);
        yFrom = Math.max(0,yFrom-1);
        yTo   = Math.min(yTo+1, yDim-1);


        for (int cx=xFrom; cx<=xTo; cx++ ) {
            for ( int cy = yFrom; cy<=yTo; cy++) {
                if (cellOccupiedWithRealBlock(cx,cy)) {
                    if ( cornerInTrajectory(left(cx) ,top(cy)   , prevBallPosX, prevBallPosY, nextBallX, nextBallY)) {
                        blocks[cx][cy].setHitCornerX(left(cx));
                        blocks[cx][cy].setHitCornerY(top(cy));
                        return blocks[cx][cy];
                    }
                    if ( cornerInTrajectory(right(cx),top(cy)   , prevBallPosX, prevBallPosY, nextBallX, nextBallY)) {
                        blocks[cx][cy].setHitCornerX(right(cx));
                        blocks[cx][cy].setHitCornerY(top(cy));
                        return blocks[cx][cy];
                    }
                    if ( cornerInTrajectory(left(cx) ,bottom(cy), prevBallPosX, prevBallPosY, nextBallX, nextBallY)) {
                        blocks[cx][cy].setHitCornerX(left(cx));
                        blocks[cx][cy].setHitCornerY(bottom(cy));
                        return blocks[cx][cy];
                    }
                    if ( cornerInTrajectory(right(cx),bottom(cy), prevBallPosX, prevBallPosY, nextBallX, nextBallY)) {
                        blocks[cx][cy].setHitCornerX(right(cx));
                        blocks[cx][cy].setHitCornerY(bottom(cy));
                        return blocks[cx][cy];
                    }
                }
            }
        }
        return null;
    }

    private boolean cornerInTrajectory(float qx, float qy, float x1, float y1, float x2, float y2) {
        if ( distanceSquare(qx,qy,x1,y1) <= radiusSquare) return true;
        if ( distanceSquare(qx,qy,x2,y2) <= radiusSquare) return true;
        float rx = x2-x1;
        float ry = y2 - y1;
        float nx = ry;
        float ny = -rx;
        float w = (rx*(qy-y1) - ry*(qx-x1))/(nx*ry-ny*rx);

        //alternative computation
        //        float a1 = x2-x1;
        //        float a2 = y2-y1;
        //        float b1 = y2-y1;
        //        float b2 = x1-x2;
        //        float c1 = x1-qx;
        //        float c2 = y1-qy;

        //float w2 = (c1*a2-c2*a1)/(b1*a2-b2*a1);

        return distanceSquare(w*ny, w*ny, qx, qy) <= radiusSquare;
    }

    private boolean bottomLeftCornerInRadius(Ball currBall) {
        int cx = getXPos(currBall);
        int cy = getYPos(currBall);
        float cornerX = left(cx);
        float cornerY = bottom(cy);
        return (distanceSquare(currBall,cornerX, cornerY) <= radiusSquare);    }

    private boolean topLeftCornerInRadius(Ball currBall) {
        int cx = getXPos(currBall);
        int cy = getYPos(currBall);
        float cornerX = left(cx);
        float cornerY = top(cy);
        return (distanceSquare(currBall,cornerX, cornerY) <= radiusSquare);
    }

    private boolean topRightCornerInRadius(Ball currBall) {
        int cx = getXPos(currBall);
        int cy = getYPos(currBall);
        float cornerX = right(cx);
        float cornerY = top(cy);
        return (distanceSquare(currBall,cornerX, cornerY) <= radiusSquare);
    }

    private boolean bottomRightCornerInRadius(Ball currBall) {
        int cx = getXPos(currBall);
        int cy = getYPos(currBall);
        float cornerX = right(cx);
        float cornerY = bottom(cy);
        return (distanceSquare(currBall,cornerX, cornerY) <= radiusSquare);
    }

    private boolean hitBottomCorner(Ball b1, Ball b2) {
        int c1X = getXPos(b1);
        int c1Y = getYPos(b1);
        float x1 = b1.getX();
        float y1 = b1.getY();

        float x2 = b2.getX();
        float y2 = b2.getY();
        int c2X = getXPos(b2);
        int c2Y = getYPos(b2);


        if (cellOccupiedWithRealBlock(c1X, c1Y)) return false;
        if (cellOccupiedWithRealBlock(c2X, c2Y)) return false;
        if (c1X == c2X || c1Y == c2Y) return false;

//        assert (   c1X == c2X + 1 && c2Y == c2Y + 1
//                || c1X == c2X - 1 && c2Y == c2Y + 1
//                || c1X == c2X + 1 && c2Y == c2Y - 1
//                || c1X == c2X - 1 && c2Y == c2Y - 1);

        // line

return false;

    }

    private void freeze(String s, int ball) {
        freeze = true;
        freezeReason = s;
        freezeBall = ball;
        balls[ball].setPaint(dirLinePaint);
        for ( int b = 0; b < numBalls; b++) {
            balls[b].setSpeed(0,0);
        }
        endOfRollingPhase = true;
        updateCounterAtFreezeTime = updateCounter;
        reusePreviousFireSpeed = true;

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
                || (currBall.movingMainlyUpwards()   && y2>y1)
                || (currBall.movingMainlyDownwards() && y2<y1)
                || (currBall.movingMainlyLeft()      && x2>x1)
                || (currBall.movingMainlyRight()     && x2<x1)){
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

        //drawCross("endOfNewSpeed:",ux, uy);


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
        int y = getYPos(currBall);

        if (currBall.movingUpwards()) {
            horizontalReflectionLine = top(y) + ballRadius;
        } else {
            horizontalReflectionLine = bottom(y) - ballRadius;
        }
        return horizontalReflectionLine;
    }

    private float verticalReflectionLine(Ball currBall) {
        float verticalReflectionLine = 0;
        int x = getXPos(currBall);

        if (currBall.movingRight()) {
            verticalReflectionLine = right(x) - ballRadius;
        } else {
            verticalReflectionLine = left(x) + ballRadius;
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
        if (!inYRange(y) && !inXRange(x)) {
            System.out.println("STOOOOOOOP!");
        }
        return x > 1 && blocks[x - 1][y] != null;
    }
    private boolean hasARightNeighbour(int x, int y) {
        if (!inYRange(y) && !inXRange(x)) {
            System.out.println("STOOOOOOOP!");
        }
        return x < xDim-1 && blocks[x + 1][y] != null;
    }
    private boolean hasATopNeighbour(int x, int y) {
        if (!inYRange(y) && !inXRange(x)) {
            System.out.println("STOOOOOOOP!");
        }
        return y > 1 && blocks[x][y-1] != null;
    }
    private boolean hasABottomNeighbour(int x, int y) {
        if (!inYRange(y) && !inXRange(x)) {
            System.out.println("STOOOOOOOP!");
        }
        return y < xDim-1 && blocks[x][y+1] != null;
    }

    private void hit(int x, int y) {
        if (freeze) return;
        if (inXRange(x) && inYRange(y)) {
            Block b = blocks[x][y];
            if (b==null) {
                freeze("ball hit null block", 0);
                return;
            }
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

    private void actionAfterBallRolling() {

        //freeze("ballAtEnd",0);
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
                firePosX = newFirePosX;

                float dx = x - firePosX;
                float dy = y - firePosY;

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
        int cx = (int)(x/blockWidth);
        int cy = (int)(y/blockHeight);

        bb.clear();
        bb.setCenter(cx,cy);

        // check center
        bb.C = cellType(cx,cy);

        //check corners
        float top = top(cy);
        float bot = bottom(cy);
        float left = left(cx);
        float right = right(cx);

        // check corners
        // top-left
        if (distanceSquare(ball,left,top)<=radiusSquare ) {
            bb.L  = cellType(cx-1, cy);
            bb.TL = cellType(cx - 1, cy-1);
            bb.T  = cellType(cx, cy-1);
        }

        // top-right corner
        if (distanceSquare(ball,right,top)<=radiusSquare ) {
            bb.T  = cellType(cx, cy-1);
            bb.TR = cellType(cx+1, cy-1);
            bb.R  = cellType(cx + 1, cy);
        }

        // bottom-right
        if (distanceSquare(ball,right,bot)<=radiusSquare ) {
            bb.R = cellType(cx + 1, cy);
            bb.BR = cellType(cx + 1, cy+1);
            bb.B = cellType(cx, cy+1);
        }

        if (distanceSquare(ball,left,bot)<=radiusSquare ) {
            bb.B = cellType(cx, cy+1);
            bb.BL = cellType(cx - 1, cy+1);
            bb.L = cellType(cx - 1, cy);
        }

        // check borders
        if (ball.getX()-ballRadius <= left)  bb.L = cellType(cx-1,cy);
        if (ball.getX()+ballRadius >= right) bb.R = cellType(cx+1, cy);
        if (ball.getY()-ballRadius <= top)   bb.T = cellType(cx,cy-1);
        if (ball.getY()+ballRadius >= bot)   bb.B = cellType(cx,cy+1);

        if (bb.anyHit()) {
            return true;
        }
        return false;

    }

    private Content cellType(int x, int y) {
        if (cellOccupiedWithRealBlock(x,y)) return Content.HIT_BLOCK;
        else if (!inXRange(x) || y<0) return Content.HIT_BORDER;
        else return Content.NO_BLOCK;
    }

    private boolean cellOccupied(int x, int y) {
        if(!inXRange(x)) return true; // left and right borders
        if(y<0) return true; // top border
        if(y>=yDim) return false; // lower part of game board
        return blocks[x][y]!=null; //cell in game board
    }

    private boolean cellOccupiedWithRealBlock(int x, int y) {
        if(!inXRange(x) || !inYRange(y)) return false; // left and right borders
        return blocks[x][y]!=null; //cell in game board
    }



    private float distanceSquare(Ball ball, float x, float y) {
        float dx = ball.getX()-x;
        float dy = ball.getY()-y;
        return dx*dx+dy*dy;
    }

    private float distanceSquare(float x1, float y1, float x2, float y2) {
        float dx = x2-x1;
        float dy = y2-y1;
        return dx*dx+dy*dy;
    }

    private int getXBlock(float x) {
        return (int)((x-offsetX)/blockWidth);
    }
    private int getYBlock(float y) {
        return (int)((y-offsetY)/blockHeight);
    }


}
