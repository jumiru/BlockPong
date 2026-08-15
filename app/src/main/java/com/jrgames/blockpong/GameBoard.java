package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

public class GameBoard {

    interface GameCallbacks {
        int getLevel();
        void addAnimation(Animation animation);
        void increaselevel();
        void setGameOver(boolean win);
        boolean isGameOver();
        void resetGameOver();
        void addScore(int points);
        // Returns the predefined layout JSON for this level number, or null if none exists
        // (in which case GameBoard falls back to its random layout generator).
        String loadLevelJson(int level);
        // Called once when a move (all balls back at rest) ends, reporting how many blocks this
        // shot destroyed (see GameBoard.hit()) and how many balls it was fired with, so the
        // shot-statistics histogram can be updated and the bonus-award threshold checked. See
        // Game.onRoundEnd().
        void onRoundEnd(int blocksCleared, int ballsUsed);
        // Whether the given bonus is currently armed via the bonus row -- several can be armed at
        // once (see Game.armedBonuses). EXTENDED_PATH and MOVE_START_POINT apply continuously
        // while armed (aim preview / fire position), so GameBoard needs to read this without
        // spending them.
        boolean isBonusArmed(Bonus bonus);
        // Called right as a shot is launched; spends every bonus the player armed via the bonus
        // row (decrementing each one's count and clearing the armed state), returning them (empty
        // if none were armed) so GameBoard can apply each one's one-off effect.
        List<Bonus> consumeArmedBonuses();
    }

    private static final float EPSILON = 1e-6f;
    // Points awarded per hit = the block's starting value times this factor. Tougher blocks
    // (higher starting value) are worth more per hit, and every hit counts, not just the kill.
    private static final int POINTS_PER_VALUE = 1;
    // EXTENDED_PATH bonus: how much longer the aim preview line gets while armed.
    private static final float EXTENDED_PATH_LENGTH_MULTIPLIER = 1.6f;
    // EXTRA_BALLS bonus: ball count is bumped up to this (permanently, if not already there)
    // when spent.
    private static final int EXTRA_BALLS_TARGET_COUNT = 20;
    // MOVE_STOPPER bonus: set when spent, consumed (and cleared) by the next board-drop check in
    // actionAfterBallRolling() so that one drop is skipped -- and the game-over check for that
    // same shot is bypassed too, since it would otherwise fire before the bonus is even consulted.
    private boolean skipNextBoardDrop;

    // DRAG_PADDLE bonus: a paddle at the fire line, active for the rest of the shot it was spent
    // on (set in fire(), not applyConsumedBonus() -- unlike a one-off flag like skipNextBoardDrop,
    // its position/width keep evolving for as long as it's active, closer to EXTENDED_PATH/
    // MOVE_START_POINT's "continuously active" bonuses, except those poll isBonusArmed() during
    // aiming while this one has to keep going through the whole rolling phase, well after the
    // bonus is already consumed and isBonusArmed() would say false). Position follows a finger
    // drag directly (see touchDown()/touchMove()) -- an earlier accelerometer-tilt version felt
    // too sluggish to control precisely. Shrinks per reflection, not on a timer -- see the
    // collision check below -- and is also force-cleared once the move ends
    // (actionAfterBallRolling()), so it never lingers into the next shot's aim.
    private static final float PADDLE_INITIAL_WIDTH_FRACTION = 0.35f; // of board width
    private static final float PADDLE_HEIGHT = 18f;
    private static final int PADDLE_MAX_HITS = 10;
    private boolean paddleActive;
    private float paddlePosX;
    private float paddleWidth;
    private float paddleShrinkPerHit;
    private int paddleHitsRemaining;
    private Paint paddlePaint;

    private final float dirLineLength;
    private final Paint frozenBallPaint;
    private Ball ballAfterMoving;
    private Ball ballBeforeMoving;
    private String freezeReason = "";
    private boolean reusePreviousFireSpeed;
    private int updateCounter;
    private int updateCounterAtFreezeTime;
    private int freezeBall;
    private float startPosXTouch;
    private float startPosYTouch;
    private boolean ballDropRunning;
    private Ball frozenBall;
    // Tracks the cell hit earlier in the current step so the end-of-step safety nets
    // (enforceTriangleClearance, enforceSquareClearance) don't double-credit the same block
    // when they reposition the ball away from a block that already registered a hit this step.
    private int stepHitCellX = -1, stepHitCellY = -1;
    private float prevBallPosX;
    private float prevBallPosY;
    private float nextBallX;
    private float nextBallY;
    private boolean autoPlayMode;

    public Block getBlock(int x, int y) {
        return blocks[x][y];
    }


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
        NO_BLOCK, HIT_BLOCK4, HIT_BLOCK3_BL, HIT_BLOCK3_TL, HIT_BLOCK3_TR, HIT_BLOCK3_BR, HIT_BORDER } ;

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
            return (TL!=Content.NO_BLOCK  || T!=Content.NO_BLOCK || TR!=Content.NO_BLOCK || C!=Content.NO_BLOCK);
        }

        public boolean hitBlockOnBottom() {
            return (BL!=Content.NO_BLOCK || B!=Content.NO_BLOCK || BR!=Content.NO_BLOCK || C!=Content.NO_BLOCK);
        }

        public boolean hitBlockOnLeft() {
            return (TL!=Content.NO_BLOCK || L!=Content.NO_BLOCK || BL!=Content.NO_BLOCK || C!=Content.NO_BLOCK);
        }

        public boolean hitBlockOnRight() {
            return (TR!=Content.NO_BLOCK || R!=Content.NO_BLOCK || BR!=Content.NO_BLOCK || C!=Content.NO_BLOCK);
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

    // Screen-edge strip that touch input is clamped away from -- Android's back-gesture
    // (left/right, gesture nav) and notification-shade swipe (top) can steal a touch that starts
    // or passes through there, so gameplay never relies on those pixels. Set once by
    // Game.setTouchDeadZone() after the canvas size is known; unbounded (no-op) until then.
    private float touchMinX = Float.NEGATIVE_INFINITY;
    private float touchMaxX = Float.POSITIVE_INFINITY;
    private float touchMinY = Float.NEGATIVE_INFINITY;

    private final int maxNumBalls;
    private Ball balls[];
    private int numBalls;
    final GameCallbacks game;

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

    // Test/debug instrumentation only: records the (position, launch vector) each ball actually
    // got dispatched with (see update()'s "if (fire)" block), indexed by dispatch order -- so a
    // test can assert every ball in one shot launched with the exact same start point and angle,
    // catching the "angle/start point shifts partway through firing" bug if it ever regresses
    // (reported as still happening; the earlier fix only closed the specific window described in
    // touchDown()'s "!fire" comment, not necessarily every path that can mutate fireSpeedX/Y or
    // newFirePosX while a shot's balls are still being dispatched).
    private float[] dispatchedFirePosX;
    private float[] dispatchedFireSpeedX;
    private float[] dispatchedFireSpeedY;

    Paint ballPaint;
    Paint boundaryPaint;
    private float ballRadius;
    private boolean dirLineActive;
    private float dirLineX;
    private float dirLineY;
    // MOVE_START_POINT bonus: true while the current drag is still relocating the start ball
    // (see touchDown()/touchMove()); goes false once the touch crosses into the playing field.
    private boolean movingStartPoint;

    private Paint dirLinePaint;

    private Paint gameOverLinePaint;

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

    // Share Report button for freeze overlay
    private Paint shareButtonPaint;
    private Paint shareButtonTextPaint;
    private RectF shareButtonRect;
    private static final float SHARE_BUTTON_WIDTH = 250f;
    private static final float SHARE_BUTTON_HEIGHT = 80f;

    // "Speed-up" overlay button (see shouldOfferSpeedUp()/fastForwardToNextBlockHit()): appears
    // centered on the board once the balls have gone SPEEDUP_TRIGGER_COLLISIONS bounces without
    // hitting a block, offering to fast-forward past the boring stretch.
    private Paint speedUpButtonPaint;
    private Paint speedUpButtonTextPaint;
    private RectF speedUpButtonRect;
    private static final float SPEEDUP_BUTTON_WIDTH = 280f;
    private static final float SPEEDUP_BUTTON_HEIGHT = 90f;
    // Hard cap on how many ticks fastForwardToNextBlockHit() will simulate in one go -- guards
    // against looping (close to) forever in some pathological state instead of ever reaching a
    // block hit or the end of the shot (e.g. balls endlessly circling an otherwise-cleared area).
    private static final int SPEEDUP_MAX_TICKS = 3000;

    private boolean debugSupport;

    private BB bb;

    // Recording of the currently in-flight move, and the last completed move, for the "Replay in
    // slow motion" and "Letzten Zug exportieren" burger-menu actions. A move runs from fire() to
    // actionAfterBallRolling(); each tick records ball positions plus every block's remaining
    // value (-1 = destroyed) so the replay can show both balls flying and blocks disappearing
    // without re-simulating physics.
    private boolean recordingMove;
    private List<ReplayFrame> recordingFrames;
    private Block[][] recordingBlockSnapshot;
    private int recordingNumBalls;
    private float recordingFirePosX;
    private float recordingFireSpeedX;
    private float recordingFireSpeedY;
    private List<Bonus> recordingBonuses = Collections.emptyList();

    private List<ReplayFrame> lastMoveFrames;
    private Block[][] lastMoveBlockSnapshot;
    private Block[][] lastMoveBlockSnapshotAfter;
    private float lastMoveFirePosX;
    private float lastMoveFireSpeedX;
    private float lastMoveFireSpeedY;
    private List<Bonus> lastMoveBonuses = Collections.emptyList();
    private int completedMoveCount;

    // Count of blocks fully destroyed (see hit()) during the currently in-flight move, reset in
    // fire() and reported to Game.onRoundEnd() once the move ends (see actionAfterBallRolling()).
    private int blocksClearedThisMove;
    // Count of every block hit() this move, regardless of whether that hit actually cleared it
    // (i.e. >= blocksClearedThisMove) -- same lifecycle as blocksClearedThisMove, read via
    // getHitsThisMove() from Game.onRoundEnd() for the separate "Treffer pro Schuss" statistic.
    private int hitsThisMove;

    // Consecutive ball bounces (border or block face/corner/hypotenuse) since the last block hit
    // -- see recordCollision(). Drives the "Speed-up" overlay button (Game.showSpeedUpButton()):
    // once this reaches SPEEDUP_TRIGGER_COLLISIONS, the balls have been bouncing around without
    // making progress for a while, so offer to fast-forward (see fastForwardToNextBlockHit()).
    // Reset in fire() so every shot starts this streak fresh.
    private int collisionsSinceLastBlockHit;
    static final int SPEEDUP_TRIGGER_COLLISIONS = 5;

    private static class ReplayFrame {
        final float[] ballX;
        final float[] ballY;
        final int[] blockValues; // flattened index x*yDim+y; -1 = block absent at this tick

        ReplayFrame(float[] ballX, float[] ballY, int[] blockValues) {
            this.ballX = ballX;
            this.ballY = ballY;
            this.blockValues = blockValues;
        }
    }


    public GameBoard(Game game, float width, float height, float offsetX, float offsetY) {
        this((GameCallbacks) game, width, height, offsetX, offsetY);
    }

    GameBoard(GameCallbacks game, float width, float height, float offsetX, float offsetY) {
        this.game = game;
        this.width = width;
        this.height = height;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        maxNumBalls = 100;
        normSpeed = 50;
        numInitBalls = 10;
        xDim = 11; //11
        yDim = 18; // game-over row moved further down so blocks have more room before it triggers
        ballRadius = 22; // 20
        dirLineLength = 1.3f*width;
        // Keep production gameplay clean; debug overlays can be enabled explicitly.
        debugSupport = false;
        autoPlayMode = false;
        radiusSquare = ballRadius * ballRadius;

        blockWidth = width / (float)xDim;
        blockHeight = blockWidth;

        assert (ballRadius<blockWidth);
        assert (ballRadius<blockHeight);
        assert (normSpeed<blockWidth);
        assert (normSpeed<blockHeight);

        balls = new Ball[maxNumBalls];
        dispatchedFirePosX = new float[maxNumBalls];
        dispatchedFireSpeedX = new float[maxNumBalls];
        dispatchedFireSpeedY = new float[maxNumBalls];

        ballPaint = new Paint();
        ballPaint.setColor(Color.rgb(150,30,50));

        boundaryPaint = new Paint();
        boundaryPaint.setColor(Color.rgb(215,229,255));
        boundaryPaint.setStrokeWidth(8.0f);

        dirLinePaint = new Paint();
        dirLinePaint.setColor(Color.WHITE);

        gameOverLinePaint = new Paint();
        gameOverLinePaint.setColor(Color.rgb(80, 80, 80));
        gameOverLinePaint.setStrokeWidth(1.5f);

        paddlePaint = new Paint();
        paddlePaint.setColor(Color.rgb(0, 188, 212));
        paddlePaint.setAntiAlias(true);

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

        frozenBallPaint = new Paint();
        frozenBallPaint.setColor(Color.GREEN);

        // Initialize Share Report button
        shareButtonPaint = new Paint();
        shareButtonPaint.setColor(Color.BLUE);
        shareButtonPaint.setStyle(Paint.Style.FILL);

        shareButtonTextPaint = new Paint();
        shareButtonTextPaint.setColor(Color.WHITE);
        shareButtonTextPaint.setTextSize(40);
        shareButtonTextPaint.setTextAlign(Paint.Align.CENTER);

        shareButtonRect = new RectF(0, 0, SHARE_BUTTON_WIDTH, SHARE_BUTTON_HEIGHT);

        // Initialize Speed-up button
        speedUpButtonPaint = new Paint();
        speedUpButtonPaint.setColor(Color.parseColor("#FFA000"));
        speedUpButtonPaint.setStyle(Paint.Style.FILL);

        speedUpButtonTextPaint = new Paint();
        speedUpButtonTextPaint.setColor(Color.WHITE);
        speedUpButtonTextPaint.setTextSize(38);
        speedUpButtonTextPaint.setTextAlign(Paint.Align.CENTER);
        speedUpButtonTextPaint.setAntiAlias(true);

        speedUpButtonRect = new RectF(0, 0, SPEEDUP_BUTTON_WIDTH, SPEEDUP_BUTTON_HEIGHT);

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
                    blocks[x][y] = new Block4 ( this, x,y, 42-2*(x+y));
                }
            }
        } else {
            for ( int y = 0; y < yDim; y++) {
                for (int x = 0; x < xDim; x++) {
                    blocks[x][y] = null;
                }
            }
            String levelJson = game.loadLevelJson(game.getLevel());
            if (levelJson != null) {
                loadBlocksFromJson(levelJson);
            } else {
                randomBoard();
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
        freezeBall = 0;
    }

    // Number of rows nearest the paddle (highest y) that randomBoard() always leaves empty.
    private static final int RANDOM_BOARD_EMPTY_BOTTOM_ROWS = 3;

    // Fallback for any level with no predefined layout file (see loadBlocksFromJson): levels
    // beyond the last authored one, and every 10th level (10, 20, 30, ...), which is deliberately
    // left without a level*.json file so it's always a random "Zufalls-Level" -- see
    // Game.isRandomLevelSlot(). The level-vs-level scaling (maxValueAtTop/densityAtTop) still
    // scales continuously with the raw level number so an early random level like 10 isn't as
    // dense/tough as one appearing at level 100+, but *within* one generated board the row-by-row
    // shape is itself randomized rather than a fixed formula (see the random-walk below): the
    // bottom RANDOM_BOARD_EMPTY_BOTTOM_ROWS rows always stay empty, and going up from there both
    // density and block value tend to grow -- but via random per-row steps (occasionally even a
    // small dip) rather than a smooth deterministic curve, so no two generated boards look alike.
    private void randomBoard() {
        int rowCount = yDim - RANDOM_BOARD_EMPTY_BOTTOM_ROWS;
        if (rowCount <= 0) {
            return;
        }
        int lastRow = rowCount - 1; // bottom-most randomizable row (closest to the paddle)
        int level = game.getLevel();
        double maxValueAtTop = 3.0 + 0.22 * level;
        double densityAtTop = Math.min(0.7, 0.35 + 0.003 * level);

        // Random walk from the paddle-most randomizable row up to the top: each row nudges its
        // density/max-value up from the row below by a random amount, mostly positive (biased
        // step, see below) but occasionally slightly negative, so the upward tendency holds
        // without being a rigid gradient. Clamped to [0, atTop] / [1, atTop] throughout.
        double[] rowDensity = new double[rowCount];
        double[] rowMaxValue = new double[rowCount];
        rowDensity[lastRow] = rand.nextDouble() * densityAtTop * 0.15;
        rowMaxValue[lastRow] = 1.0 + rand.nextDouble() * maxValueAtTop * 0.15;
        double densityStepScale = lastRow > 0 ? (densityAtTop * 1.6 / lastRow) : 0;
        double valueStepScale = lastRow > 0 ? (maxValueAtTop * 1.6 / lastRow) : 0;
        for (int y = lastRow - 1; y >= 0; y--) {
            double densityDelta = (rand.nextDouble() - 0.2) * densityStepScale;
            rowDensity[y] = Math.max(0.0, Math.min(densityAtTop, rowDensity[y + 1] + densityDelta));
            double valueDelta = (rand.nextDouble() - 0.2) * valueStepScale;
            rowMaxValue[y] = Math.max(1.0, Math.min(maxValueAtTop, rowMaxValue[y + 1] + valueDelta));
        }

        for ( int y = 0; y < rowCount; y++) {
            int rowMaxValueInt = Math.max(1, (int) Math.round(rowMaxValue[y]));
            double rowDensityAtY = rowDensity[y];
            for ( int x = 0; x < xDim; x++ ) {
                if (rand.nextDouble() < rowDensityAtY)  {
                    int v = rand.nextInt(rowMaxValueInt)+1;
                    if (rand.nextBoolean()) {
                        blocks[x][y] = new Block4(this, x, y, v);
                    } else {
                        Block3.tTriangle type = Block3.tTriangle.BL;
                        switch (rand.nextInt(4)) {
                            case 0:
                                type = Block3.tTriangle.BL;
                                break;
                            case 1:
                                type = Block3.tTriangle.BR;
                                break;
                            case 2:
                                type = Block3.tTriangle.TL;
                                break;
                            case 3:
                                type = Block3.tTriangle.TR;
                                break;
                        }
                        blocks[x][y] = new Block3(this, x, y, type, v);
                    }
                }
            }
        }
    }

    // Loads a predefined level layout, as produced by tools/level_editor.py, or a full live-board
    // snapshot (see exportBlocksJson()) -- same JSON shape either way. Format:
    // {"blocks": [{"x":0,"y":0,"type":"square","value":5}, {"x":1,"y":2,"type":"tl","value":3,"color":"#3399FF"}, ...]}
    // "type" is "square" for a Block4, or one of "bl"/"tl"/"tr"/"br" for a Block3 triangle
    // (matching Block3.tTriangle, lowercased). "color" is optional -- an explicit "#RRGGBB" that
    // decouples the block's look from its point value (see Block.overrideColor); omitted means
    // the color is derived from "value" the way it always was. Authored level files never place blocks in the
    // bottom two rows (reserved, same as randomBoard()), but a live-board snapshot legitimately
    // can (blocks that dropped down towards game over) -- accepting the full range here lets
    // restoreBlocksFromJson() (saved-game restore, "Import & Replay (Debug)") bring those back
    // instead of silently losing them. Falls back to a random board if the JSON is malformed, so
    // a broken level file can't leave the board empty. Returns false in that case (true on a
    // clean parse) so callers that need to know the difference -- restoreBlocksFromJson(), and
    // through it importAndReplayForDebug() -- can tell a real import apart from this fallback
    // instead of silently reporting success on garbage input (reported bug: "Import & Replay"
    // looked like it worked -- dialog closed, no error -- but the board just showed an unrelated
    // random layout).
    private boolean loadBlocksFromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray blockArray = root.getJSONArray("blocks");
            for (int i = 0; i < blockArray.length(); i++) {
                JSONObject b = blockArray.getJSONObject(i);
                int x = b.getInt("x");
                int y = b.getInt("y");
                int value = b.getInt("value");
                String type = b.getString("type");
                Integer color = b.has("color") ? (Integer) Color.parseColor(b.getString("color")) : null;
                if (!inXRange(x) || y < 0 || y >= yDim) continue;

                if ("square".equals(type)) {
                    blocks[x][y] = new Block4(this, x, y, value, color);
                } else {
                    Block3.tTriangle triangleType = Block3.tTriangle.valueOf(type.toUpperCase());
                    blocks[x][y] = new Block3(this, x, y, triangleType, value, color);
                }
            }
            return true;
        } catch (JSONException | IllegalArgumentException e) {
            System.out.println("Malformed level JSON for level " + game.getLevel() + ": " + e.getMessage());
            randomBoard();
            return false;
        }
    }

    // Serializes the current board -- including blocks already partially worn down by hits --
    // into the same JSON shape loadBlocksFromJson() reads, so an in-progress game can be
    // restored verbatim after the process was killed (see Game.saveState()/restoreState()).
    public String exportBlocksJson() {
        return exportBlocksJson(blocks);
    }

    // grid variant of the above, so other block-grid snapshots (e.g. lastMoveBlockSnapshot, for
    // getLastMoveReport()) can be exported in the same reproducible, tools/level_editor.py
    // compatible format without duplicating the serialization logic. Covers the full board,
    // including the bottom two rows that authored levels never populate but a live board can
    // (blocks that dropped towards game over) -- omitting them here previously made the "vor"/
    // "nach dem Zug" export (and the persisted save-game) come back empty right when it mattered
    // most: the shot that actually triggers game over (reported bug).
    private String exportBlocksJson(Block[][] grid) {
        try {
            JSONArray blockArray = new JSONArray();
            for (int y = 0; y < yDim; y++) {
                for (int x = 0; x < xDim; x++) {
                    Block b = grid[x][y];
                    if (b == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("x", x);
                    o.put("y", y);
                    o.put("value", b.getValue());
                    o.put("type", (b instanceof Block3) ? ((Block3) b).getType().name().toLowerCase() : "square");
                    Integer overrideColor = b.getOverrideColor();
                    if (overrideColor != null) {
                        o.put("color", String.format("#%06X", overrideColor & 0xFFFFFF));
                    }
                    blockArray.put(o);
                }
            }
            JSONObject root = new JSONObject();
            root.put("blocks", blockArray);
            return root.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    // Counterpart to exportBlocksJson(): clears the board initBoard() just generated and
    // replaces it with the previously saved layout. Returns false if the JSON was malformed (the
    // board was still cleared and filled with a random layout as a fallback, but the caller gets
    // told this isn't the layout it asked for -- see loadBlocksFromJson()).
    public boolean restoreBlocksFromJson(String json) {
        for (int y = 0; y < yDim; y++) {
            for (int x = 0; x < xDim; x++) {
                blocks[x][y] = null;
            }
        }
        return loadBlocksFromJson(json);
    }

    // Burger menu debug helper ("Import & Replay"): loads a previously exported board layout and
    // immediately fires a shot with the exact recorded start position, launch vector, and ball
    // count, bypassing touch input entirely. Lets a reported bug's exact board+shot be reproduced
    // live (real physics, not just the frozen recorded "Replay in Zeitlupe") to check whether a
    // fix actually holds. Returns false -- without firing anything -- if blocksJson didn't parse,
    // so the dialog can report a real error instead of silently launching a shot at an unrelated
    // random board (reported bug: dialog closed looking successful, but nothing matching the
    // pasted report ever appeared).
    public boolean importAndReplayForDebug(String blocksJson, float startX, float dx, float dy, int ballCount) {
        freeze = false;
        dirLineActive = false;
        movingStartPoint = false;
        if (!restoreBlocksFromJson(blocksJson)) {
            return false;
        }

        firePosX = startX;
        newFirePosX = startX;
        int count = Math.max(1, Math.min(ballCount, maxNumBalls));
        numBalls = 0;
        for (int i = 0; i < count; i++) {
            addBall(i);
        }

        setFireSpeed(dx, dy);
        fire(Collections.emptyList());
        // touchRelease() resets this the same way right after its own fire() call -- without it,
        // endOfRollingPhase stays true (its steady-state value between shots, see initBoard()/
        // actionAfterBallRolling()), and update() would then never notice this shot has ended.
        endOfRollingPhase = false;
        return true;
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

        if ((dirLineActive && !movingStartPoint) || debugSupport) {
            drawDirLine(c);
        }


        // draw balls
        for ( int b=0;b<numBalls; b++) {
            balls[b].draw(c);
        }

        // DRAG_PADDLE bonus: paddle at the fire line, shrinking away one hit at a time.
        if (paddleActive) {
            float halfWidth = paddleWidth / 2f;
            RectF paddleRect = new RectF(paddlePosX - halfWidth, firePosY - PADDLE_HEIGHT / 2f,
                    paddlePosX + halfWidth, firePosY + PADDLE_HEIGHT / 2f);
            c.drawRoundRect(paddleRect, PADDLE_HEIGHT / 2f, PADDLE_HEIGHT / 2f, paddlePaint);
        }

        // Speed-up overlay: see shouldOfferSpeedUp()/fastForwardToNextBlockHit(). Drawn on top of
        // everything else on the board (balls included) so it's unmissable while it's up.
        if (shouldOfferSpeedUp()) {
            RectF btn = getSpeedUpButtonRect();
            c.drawRoundRect(btn, 20, 20, speedUpButtonPaint);
            c.drawText("⏩ Speed-up", btn.centerX(), btn.centerY() + 14f, speedUpButtonTextPaint);
        }

        // game-over line: marks the bottom of the row that ends the game if it holds a block
        drawDashedHorizontalLine(c, offsetX, offsetX + width, gameOverLineY(), gameOverLinePaint);

        // draw boundaries
        //left border
        c.drawLine(offsetX-boundaryPaint.getStrokeWidth()/2, offsetY+height, offsetX-boundaryPaint.getStrokeWidth()/2, offsetY, boundaryPaint);
        //top border
        c.drawLine(offsetX-boundaryPaint.getStrokeWidth()/2, offsetY,offsetX+width+boundaryPaint.getStrokeWidth()/2, offsetY,boundaryPaint);
        //right border
        c.drawLine(offsetX+width+boundaryPaint.getStrokeWidth()/2,offsetY,offsetX+width+boundaryPaint.getStrokeWidth()/2, offsetY+height, boundaryPaint);

        if (debugSupport && freeze) {
            c.drawText(freezeReason, 30f,300f, debugTextPaint);

            // Draw Share Report button
            float buttonX = 50f;
            float buttonY = 400f;
            shareButtonRect.set(buttonX, buttonY, buttonX + SHARE_BUTTON_WIDTH, buttonY + SHARE_BUTTON_HEIGHT);
            c.drawRect(shareButtonRect, shareButtonPaint);
            c.drawText("Share Report", buttonX + SHARE_BUTTON_WIDTH / 2f, buttonY + SHARE_BUTTON_HEIGHT / 2f + 15f, shareButtonTextPaint);
        }

        if (debugSupport) {
            for (int y = 0; y < yDim; y++) {
                c.drawLine(offsetX, bottom(y), width - offsetX, bottom(y), dirLinePaint);
            }
            for (int x = 0; x < xDim - 1; x++) {
                c.drawLine(right(x), offsetY, right(x), height + offsetY, dirLinePaint);
            }
            c.drawText("updateCycle: "+updateCounterAtFreezeTime, 50, 1200, debugTextPaint);
            c.drawText("freeze ball: "+freezeBall, 50, 1250, debugTextPaint);
            c.drawText("x1/y1 = " + roundedString(prevBallPosX, 1000) + " / " + roundedString(prevBallPosY, 1000), 50, 1300, debugTextPaint);
            c.drawText("x2/y2 = " + roundedString(nextBallX, 1000) + " / " + roundedString(nextBallY, 1000), 50, 1350, debugTextPaint);
            c.drawText("dx/dy = " + roundedString(balls[freezeBall].getDx(), 1000) + " / " + roundedString(balls[freezeBall].getDy(), 1000), 50, 1400, debugTextPaint);
            if (frozenBall!=null) {
                frozenBall.setPaint(frozenBallPaint);
                frozenBall.draw(c);
            }


            float hy = horizontalReflectionLine(balls[freezeBall]);
            float hx = verticalReflectionLine(balls[freezeBall]);

            c.drawLine(offsetX, hy, offsetX + width, hy, dirLinePaint);
            c.drawLine(hx, offsetY, hx, offsetY + height, dirLinePaint);

            debugRect.left = left(getMinXPos(balls[freezeBall]));
            debugRect.top = top(getMinYPos(balls[freezeBall]));
            debugRect.right = right(getMaxXPos(balls[freezeBall]));
            debugRect.bottom = bottom(getMaxYPos(balls[freezeBall]));
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
        if (lenOfSelection <= EPSILON) {
            return;
        }

        float vx = (x1-x0)/lenOfSelection;
        float vy = (y1-y0)/lenOfSelection;

        // EXTENDED_PATH bonus: while armed, show the ball's actual simulated trajectory (real
        // wall/block bounces) instead of the plain single-wall-mirror preview below -- see
        // computeExtendedPathPoints(). Falls through to the plain preview if the simulation
        // couldn't be set up for some reason (e.g. a degenerate aim vector).
        if (game.isBonusArmed(Bonus.EXTENDED_PATH)) {
            float targetLength = dirLineLength * EXTENDED_PATH_LENGTH_MULTIPLIER;
            List<float[]> points = computeExtendedPathPoints(x0, y0, vx, vy, targetLength);
            if (points != null && points.size() >= 2) {
                for (int i = 1; i < points.size(); i++) {
                    float[] a = points.get(i - 1);
                    float[] b = points.get(i);
                    c.drawLine(a[0], a[1], b[0], b[1], dirLinePaint);
                }
                return;
            }
        }

        float effectiveDirLineLength = dirLineLength;

        x1 = x0+vx*effectiveDirLineLength;
        y1 = y0+vy*effectiveDirLineLength;

        float leftMirrorLine = offsetX+ballRadius;
        float rightMirrorLine = offsetX+width-ballRadius;
        float topMirrorLine = top(0)+ballRadius;

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

        } else if (y1 < topMirrorLine) {
            // Ball-oriented reflection off the top wall (reported request), same "unfold"
            // technique as the left/right walls above -- mirror the overshoot point across the
            // border, then draw a straight line to it -- just with x/y swapped since this is a
            // horizontal wall instead of a vertical one. Only reached when the line doesn't
            // already exit through a side wall first (the two aren't chained/combined), which
            // covers this bonus's actual purpose: a long, mostly-vertical preview that now shows
            // where it bounces back down instead of just running off the top of the board.
            float x2 = x1;
            float yDistToBorder = y0-topMirrorLine;
            float entireY = y0-y1;
            float yDistBehindBorder = entireY-yDistToBorder;
            float xDistToIntersection = x0-vx/vy*yDistToBorder;
            y1 = topMirrorLine;
            x1 = xDistToIntersection;
            float y2 = topMirrorLine+yDistBehindBorder;

            c.drawLine(x1,y1,x2,y2,dirLinePaint);
        }

        c.drawLine(x0,y0,x1,y1,dirLinePaint);
    }

    // Scratch board used only to simulate the EXTENDED_PATH preview against the exact real
    // collision physics (see computeExtendedPathPoints()) -- built once and reused across calls
    // (GameBoard's constructor does real Paint allocation work, too expensive to redo every touch
    // move), wired to a no-op GameCallbacks so nothing it does (block hits, score, animations,
    // level-complete) leaks into the real game.
    private GameBoard pathSimulationBoard;

    private GameBoard pathSimulationBoard() {
        if (pathSimulationBoard == null) {
            pathSimulationBoard = new GameBoard(new NoOpGameCallbacks(), width, height, offsetX, offsetY);
        }
        return pathSimulationBoard;
    }

    // EXTENDED_PATH bonus: runs the real ball-collision physics (GameBoard.update()) on a
    // throwaway copy of the current board with a single ball fired along the given aim, recording
    // its position every tick until the preview's target length is covered (or the simulated shot
    // naturally ends). This is the same importAndReplayForDebug()/update() machinery the "Import &
    // Replay (Debug)" burger menu action already uses to replay an exact shot -- reusing it here
    // means the preview is guaranteed to match what the ball will actually do (multi-bounce off
    // walls *and* blocks), not just an idealized single-wall mirror. Returns null if the aim
    // vector is degenerate.
    private List<float[]> computeExtendedPathPoints(float startX, float startY, float dx, float dy, float targetLength) {
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= EPSILON) return null;

        GameBoard sim = pathSimulationBoard();
        sim.importAndReplayForDebug(exportBlocksJson(), startX, dx, dy, 1);

        List<float[]> points = new ArrayList<>();
        points.add(new float[]{startX, startY});
        float traveled = 0f;
        int ticks = 0;
        // Generous safety cap -- in practice the loop exits far sooner via targetLength, since a
        // preview only ever needs a short prefix of the shot, not the whole thing playing out.
        int maxTicks = 600;
        Ball ball = sim.getBallForTests(0);
        // A do-while, not a while: right after importAndReplayForDebug() the ball hasn't actually
        // been dispatched yet (that happens inside update() itself, once fireCounter's dispatch
        // condition is met -- immediately, on this very first tick) so it still reads as "still"
        // (ballRolling()==false) before the loop body ever runs. Checking ballRolling() as an
        // upfront guard exited before a single tick, producing an empty/near-empty path.
        do {
            float prevX = ball.getX();
            float prevY = ball.getY();
            sim.update();
            float x = ball.getX();
            float y = ball.getY();
            traveled += (float) Math.sqrt((x - prevX) * (x - prevX) + (y - prevY) * (y - prevY));
            points.add(new float[]{x, y});
            ticks++;
        } while (sim.ballRolling() && traveled < targetLength && ticks < maxTicks);

        // Trim the final segment back so the drawn path ends exactly at targetLength instead of
        // overshooting by up to one tick's worth of ball movement.
        if (traveled > targetLength && points.size() >= 2) {
            float[] last = points.get(points.size() - 1);
            float[] prev = points.get(points.size() - 2);
            float segLen = (float) Math.sqrt((last[0] - prev[0]) * (last[0] - prev[0]) + (last[1] - prev[1]) * (last[1] - prev[1]));
            if (segLen > EPSILON) {
                float t = Math.max(0f, (segLen - (traveled - targetLength)) / segLen);
                last[0] = prev[0] + (last[0] - prev[0]) * t;
                last[1] = prev[1] + (last[1] - prev[1]) * t;
            }
        }
        return points;
    }

    // No-op GameCallbacks for pathSimulationBoard() -- the simulation calls back into this exactly
    // like a real shot would (block hits scoring points, animations spawning, a level completing),
    // all of which must be swallowed since it's only a throwaway preview, never a real move.
    private static final class NoOpGameCallbacks implements GameCallbacks {
        @Override public int getLevel() { return 1; }
        @Override public void addAnimation(Animation animation) {}
        @Override public void increaselevel() {}
        @Override public void setGameOver(boolean win) {}
        @Override public boolean isGameOver() { return false; }
        @Override public void resetGameOver() {}
        @Override public void addScore(int points) {}
        @Override public String loadLevelJson(int level) { return null; }
        @Override public void onRoundEnd(int blocksCleared, int ballsUsed) {}
        @Override public boolean isBonusArmed(Bonus bonus) { return false; }
        @Override public List<Bonus> consumeArmedBonuses() { return Collections.emptyList(); }
    }

    void update() {
        if (freeze) return;
        if (ballDropRunning()) return;

        updateCounter++;

        crosses.clear();
        if (fire) {
            if (fireCounter==0) updateCounter = 0;
            if (fireCounter % 5 == 0) {
                balls[nextFireBall].setSpeed(fireSpeedX,fireSpeedY);
                // firePosX, not newFirePosX: newFirePosX also doubles as "where balls that have
                // already finished rolling should visually rest" (see the "ball back on start
                // line" code below), which gets updated the moment any earlier ball in this same
                // shot returns below the fire line -- e.g. a short shot whose first ball already
                // bounced back before the later balls of a big multi-ball volley have even been
                // dispatched. Using that mutable value here shifted the start x for whichever
                // balls launched afterwards (reported bug: start point/angle drifts mid-shot).
                // firePosX is fixed for the whole shot (set once in touchRelease() right before
                // fire()), so every ball in one shot launches from the exact same point.
                balls[nextFireBall].setPos(firePosX, firePosY);
                dispatchedFirePosX[nextFireBall] = firePosX;
                dispatchedFireSpeedX[nextFireBall] = fireSpeedX;
                dispatchedFireSpeedY[nextFireBall] = fireSpeedY;
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

                prevBallPosX = currBall.getX();
                prevBallPosY = currBall.getY();
                stepHitCellX = -1;
                stepHitCellY = -1;

                currBall.update();

                nextBallX = currBall.getX();
                nextBallY = currBall.getY();
                int ballNextXCell = getXPos(currBall);
                int ballNextYCell = getYPos(currBall);


                if ( updateCounter == updateCounterAtFreezeTime && freezeBall==ballIndex) {
                    System.out.println(updateCounter); // good location for debugger
                }


                boolean firstRound = true;
                boolean updateRequired = true;

                while (updateRequired) {
                    // first round checks collision with border (not corner) for main direction (i.e. biggest dx/dy increment)
                    // second round checks collision with border (not corner) for minor direction (i.e. smaller increment of dx/dy)
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
                            freeze("ball " + ballIndex + " has scheduled mirrorings", ballIndex);
                            break;
                        }
                    } else {
                        movingUp = currBall.movingUpwards() && !currBall.movingMainlyUpwards();
                        movingDown = currBall.movingDownwards() && !currBall.movingMainlyDownwards();
                        movingLeft = currBall.movingLeft() && !currBall.movingMainlyLeft();
                        movingRight = currBall.movingRight() && !currBall.movingMainlyRight();
                    }

                    if (movingUp)    ballCrossedHorizontalReflectionLineUpwards(prevBallPosX, prevBallPosY, horizontalReflectionLine, verticalReflectionLine, currBall);
                    if (movingDown)  ballCrossedHorizontalReflectionLineDownwards(prevBallPosX, prevBallPosY, horizontalReflectionLine, verticalReflectionLine, currBall);
                    if (movingLeft)  ballCrossedVerticalReflectionLineLeftwards(prevBallPosX, prevBallPosY, verticalReflectionLine, horizontalReflectionLine, currBall);
                    if (movingRight) ballCrossedVerticalReflectionLineRightwards(prevBallPosX, prevBallPosY, verticalReflectionLine, horizontalReflectionLine, currBall);

                    // last resort
                    if (firstRound) {
                        updateRequired = true;
                        firstRound = false;
                    } else if (currBall.scheduledMirroring()) {
                        // do the horizontal and vertical reflections now
                        currBall.performMirroring();
                        nextBallX = currBall.getX();
                        nextBallY = currBall.getY();

                        currBall.resetMirrorings();
                        updateRequired = true;
                        firstRound = true;
                    }
                }

                // Hypotenuse and corner hits are checked even when the ball center does not enter
                // a block cell (important for triangle hypotenuses and isolated tips).
                boolean hypHit = checkTriangleHypotenuses(prevBallPosX, prevBallPosY, currBall);
                if (hypHit) {
                    recordCollision(true);
                } else {
                    Block cornerHitBlock = getCornerHitBlock(prevBallPosX, prevBallPosY, nextBallX, nextBallY);
                    if (cornerHitBlock != null) {
                        int hitBlockX = cornerHitBlock.getX();
                        int hitBlockY = cornerHitBlock.getY();
                        if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                            hit(hitBlockX, hitBlockY);
                            recordCollision(true);
                            ballCollisionWithCorner(currBall, cornerHitBlock.getHitCornerX(), cornerHitBlock.getHitCornerY(), prevBallPosX, prevBallPosY);
                        }
                    }
                }
                enforceCollisionInvariants(currBall);

                // DRAG_PADDLE bonus: a ball reaching the fire line while the paddle is under it
                // (and wide enough to still cover this x) bounces back up instead of being caught
                // by the "ball back on start line" logic below -- checked first, before that logic
                // gets a chance to stop the ball here.
                if (paddleActive && currBall.getDy() > 0 && currBall.getY() + ballRadius >= firePosY
                        && Math.abs(currBall.getX() - paddlePosX) <= paddleWidth / 2f + ballRadius) {
                    currBall.setPos(currBall.getX(), firePosY - ballRadius);
                    currBall.setSpeed(currBall.getDx(), -currBall.getDy());
                    recordCollision(false);
                    // Shrinks per reflection (not on a timer): PADDLE_MAX_HITS hits and it's gone.
                    paddleHitsRemaining--;
                    paddleWidth -= paddleShrinkPerHit;
                    if (paddleHitsRemaining <= 0 || paddleWidth <= 0f) {
                        paddleActive = false;
                        paddleWidth = 0f;
                    }
                } else if (currBall.getY() > firePosY) {
                    // ball back on start line
                    if (!newFirePosSet) {
                        newFirePosX = currBall.getX();
                        newFirePosSet = true;
                    }
                    currBall.setPos(newFirePosX, firePosY);
                    currBall.setSpeed(0,0);
                }
                ballAfterMoving.copy(currBall);

                // perform some sanity checks
                if (debugSupport && !currBall.isStill()) {
                    float d = distance(ballBeforeMoving, ballAfterMoving);
                    if (d > 1.1 * normSpeed) {
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

                // Stop processing further balls this tick once one of them has frozen the game --
                // otherwise later balls in the loop keep moving physically and overwrite the
                // shared prevBallPosX/nextBallX diagnostic fields, corrupting the freeze report.
                if (freeze) break;
            }

            if (recordingMove && !freeze) {
                captureReplayFrame();
            }
        } else if (!endOfRollingPhase) {
            endOfRollingPhase = true;
            actionAfterBallRolling();
        }
    }

    private void ballCrossedHorizontalReflectionLineUpwards(float prevX, float prevY, float hLine, float vLine, Ball ball) {
        if (Math.abs(ball.getDy()) <= EPSILON) {
            return;
        }
        if (prevY>hLine && hLine>=ball.getY()) {
            float yDistanceToBlock = prevY - hLine;
            float xPosCrossSection = prevX + ball.getDx() * Math.abs(yDistanceToBlock / ball.getDy());
            int hitBlockX = getXBlock(xPosCrossSection);
            int hitBlockY = getYBlock(hLine-ballRadius-1f);

            Content ct = cellType(hitBlockX, hitBlockY);
            boolean hitTopBorder = hitBlockY < 0;
            boolean hitTopFace = (ct == Content.HIT_BLOCK4 || ct == Content.HIT_BLOCK3_BL || ct == Content.HIT_BLOCK3_BR);
            if (hitTopFace || hitTopBorder) {
                if (hitTopFace) {
                    hit(hitBlockX, hitBlockY);
                    if (ballOverlapsWithLeftNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                        hit(hitBlockX - 1, hitBlockY);
                    } else if (ballOverlapsWithRightNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                        hit(hitBlockX + 1, hitBlockY);
                    }
                }
                recordCollision(hitTopFace);
                ball.mirrorHorizontally(prevBallPosX, prevBallPosY, hLine, vLine);
            }
        }
    }
    private void ballCrossedHorizontalReflectionLineDownwards(float prevX, float prevY, float hLine, float vLine, Ball ball) {
        if (Math.abs(ball.getDy()) <= EPSILON) {
            return;
        }
        if ( prevY<hLine && hLine<=ball.getY() ) {
            float yDistanceToBlock = hLine - prevY;
            float xPosCrossSection = prevX + ball.getDx() * Math.abs(yDistanceToBlock / ball.getDy());
            int hitBlockX = getXBlock(xPosCrossSection);
            int hitBlockY = getYBlock(hLine+ballRadius+1f);

            Content ct = cellType(hitBlockX, hitBlockY);
            boolean hitTopFace = (ct == Content.HIT_BLOCK4 || ct == Content.HIT_BLOCK3_TL || ct == Content.HIT_BLOCK3_TR);
            if (hitTopFace) {
                if (ct == Content.HIT_BLOCK4 || ct == Content.HIT_BLOCK3_TL || ct == Content.HIT_BLOCK3_TR) {
                    hit(hitBlockX, hitBlockY);
                    if (ballOverlapsWithLeftNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                        hit(hitBlockX - 1, hitBlockY);
                    } else if (ballOverlapsWithRightNeighbour(hitBlockX, hitBlockY, xPosCrossSection)) {
                        hit(hitBlockX + 1, hitBlockY);
                    }
                }
                recordCollision(hitTopFace);
                ball.mirrorHorizontally(prevBallPosX, prevBallPosY, hLine, vLine);
            }
        }
    }
    private void ballCrossedVerticalReflectionLineLeftwards(float prevX, float prevY, float vLine, float hLine, Ball ball) {
        if (Math.abs(ball.getDx()) <= EPSILON) {
            return;
        }
        if ( prevX>vLine && vLine>=ball.getX() ) {
            float xDistanceToBlock = Math.abs(vLine - prevX);
            float yPosCrossSection = prevY + ball.getDy() * Math.abs(xDistanceToBlock / ball.getDx());
            int hitBlockY = getYBlock(yPosCrossSection);
            int hitBlockX = getXBlock(vLine-ballRadius-1f);

            Content ct = cellType(hitBlockX, hitBlockY);
            boolean hitSideBorder = !inXRange(hitBlockX);
            boolean hitRightFace = (ct == Content.HIT_BLOCK4 || ct == Content.HIT_BLOCK3_TR || ct == Content.HIT_BLOCK3_BR);
            if (hitRightFace || hitSideBorder) {
                if (hitRightFace) {
                    hit(hitBlockX, hitBlockY);
                    if (ballOverlapsWithUpperNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                        hit(hitBlockX, hitBlockY - 1);
                    } else if (ballOverlapsWithLowerNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                        hit(hitBlockX, hitBlockY + 1);
                    }
                }
                recordCollision(hitRightFace);
                ball.mirrorVertically(prevBallPosX, prevBallPosY, vLine, hLine);
            }
        }
    }
    private void ballCrossedVerticalReflectionLineRightwards(float prevX, float prevY, float vLine, float hLine, Ball ball) {
        if (Math.abs(ball.getDx()) <= EPSILON) {
            return;
        }
        if ( prevX<vLine && vLine<=ball.getX() ) {
            float xDistanceToBlock = Math.abs(vLine - prevX);
            float yPosCrossSection = prevY + ball.getDy() * Math.abs(xDistanceToBlock / ball.getDx());
            int hitBlockY = getYBlock(yPosCrossSection);
            int hitBlockX = getXBlock(vLine+ballRadius+1f);

            Content ct = cellType(hitBlockX, hitBlockY);
            boolean hitSideBorder = !inXRange(hitBlockX);
            boolean hitLeftFace = (ct == Content.HIT_BLOCK4 || ct == Content.HIT_BLOCK3_TL || ct == Content.HIT_BLOCK3_BL);
            if (hitLeftFace || hitSideBorder) {
                if (hitLeftFace) {
                    hit(hitBlockX, hitBlockY);
                    if (ballOverlapsWithUpperNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                        hit(hitBlockX, hitBlockY - 1);
                    } else if (ballOverlapsWithLowerNeighbour(hitBlockX, hitBlockY, yPosCrossSection)) {
                        hit(hitBlockX, hitBlockY + 1);
                    }
                }
                recordCollision(hitLeftFace);
                ball.mirrorVertically(prevBallPosX, prevBallPosY, vLine, hLine);
            }
        }
    }

    private boolean ballDropRunning() {
        if (ballDropRunning) {
            if (ballRolling()) return true;
            else {
                ballDropRunning = false;
                // Mark the round as already handled so the main update() loop below doesn't see
                // endOfRollingPhase still false and call actionAfterBallRolling() a second time
                // this same tick (that used to double the BoardDropAnimation, dropping the board
                // by two rows instead of one).
                endOfRollingPhase = true;
                actionAfterBallRolling();
            }
        }
        return false;
    }

    private Block getBorderHitBlock(float x1, float y1, float x2, float y2) {
        int cx1 = getXBlock(x1);
        int cy1 = getYBlock(y1);
        int cx2 = getXBlock(x2);
        int cy2 = getYBlock(y2);

        int xFrom = Math.min(cx1,cx2);
        int xTo   = Math.max(cx1, cx2);
        int yFrom = Math.min(cy1,cy2);
        int yTo   = Math.max(cy1,cy2);

        // widen the search area
        xFrom = Math.max(0,xFrom-1);
        xTo   = Math.min(xTo+1, xDim-1);
        yFrom = Math.max(0,yFrom-1);
        yTo   = Math.min(yTo+1, yDim-1);

        float min = Float.POSITIVE_INFINITY;
        Block result = null;

        for (int cx=xFrom; cx<=xTo; cx++ ) {
            for (int cy = yFrom; cy <= yTo; cy++) {
                if (cellOccupiedWithRealBlock(cx, cy)) {
                    // intersection with left side of the block
                    float ax = left(cx);
                    float ay = bottom(cy);
                    float bx = ax;
                    float by = top(cy);

                    float distToLeft = linesIntersect(x1,y1,x2,y2,ax,ay,bx,by);
                    if ( distToLeft < min) {
                        min = distToLeft;
                        result = blocks[cx][cy];
                        result.setHitBorder(Block.tEdge.left);
                    }
                    // intersection with top line
                    ax = left(cx);
                    ay = top(cy);
                    bx = right(cx);
                    by = ay;
                    float distToTop = linesIntersect(x1,y1,x2,y2,ax,ay,bx,by);
                    if ( distToTop < min) {
                        min = distToTop;
                        result = blocks[cx][cy];
                        result.setHitBorder(Block.tEdge.top);
                    }
                    // intersection with right line
                    ax = right(cx);
                    ay = top(cy);
                    bx = ax;
                    by = bottom(cy);
                    float distToRight = linesIntersect(x1,y1,x2,y2,ax,ay,bx,by);
                    if ( distToRight < min) {
                        min = distToRight;
                        result = blocks[cx][cy];
                        result.setHitBorder(Block.tEdge.right);
                    }
                    // intersection with bottom line
                    ax = left(cx);
                    ay = bottom(cy);
                    bx = right(cx);
                    by = ay;
                    float distToBottom = linesIntersect(x1,y1,x2,y2,ax,ay,bx,by);
                    if ( distToBottom < min) {
                        min = distToBottom;
                        result = blocks[cx][cy];
                        result.setHitBorder(Block.tEdge.bottom);
                    }
                }
            }
        }
        return result;
    }

    private float linesIntersect(float x1, float y1, float x2, float y2, float ax, float ay, float bx, float by ) {
        float r1x = x2-x1;
        float r1y = y2-y1;
        float r2x = bx-ax;
        float r2y = by-ay;

        //(x1/y1) + v1*r1 = (ax/ay) + v2*r2

        float denom = r1y*r2x-r2y*r1x;
        if (Math.abs(denom) <= EPSILON) {
            return Float.POSITIVE_INFINITY;
        }

        float v2 = (r2x*(ay-y1)+r2y*(x1-ax))/denom;
        float v1;
        if (Math.abs(r1x) > Math.abs(r1y) && Math.abs(r1x) > EPSILON) {
            v1 = (ax+v2*r2x-x1)/r1x;
        } else if (Math.abs(r1y) > EPSILON) {
            v1 = (ay+v2*r2y-y1)/r1y;
        } else {
            return Float.POSITIVE_INFINITY;
        }

        if ((0<=v1 && v1<=1) && (0<=v2 && v2<=1)) {
            return v1;
        }
        return Float.POSITIVE_INFINITY;
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

                    int nbc = numBlocksAtCorner(left(cx), top(cy));
                    boolean cit = cornerInTrajectory(left(cx), top(cy), prevBallPosX, prevBallPosY, nextBallX, nextBallY);
                    if (nbc==1 && cit) {
                        blocks[cx][cy].setHitCornerX(left(cx));
                        blocks[cx][cy].setHitCornerY(top(cy));
                        return blocks[cx][cy];
                    }

                    nbc = numBlocksAtCorner(right(cx), top(cy));
                    cit = cornerInTrajectory(right(cx), top(cy), prevBallPosX, prevBallPosY, nextBallX, nextBallY);
                    if (nbc==1 && cit) {
                        blocks[cx][cy].setHitCornerX(right(cx));
                        blocks[cx][cy].setHitCornerY(top(cy));
                        return blocks[cx][cy];
                    }

                    nbc = numBlocksAtCorner(left(cx), bottom(cy));
                    cit = cornerInTrajectory(left(cx), bottom(cy), prevBallPosX, prevBallPosY, nextBallX, nextBallY);
                    if (nbc==1 && cit) {
                        blocks[cx][cy].setHitCornerX(left(cx));
                        blocks[cx][cy].setHitCornerY(bottom(cy));
                        return blocks[cx][cy];
                    }

                    nbc = numBlocksAtCorner(right(cx), bottom(cy));
                     cit = cornerInTrajectory(right(cx), bottom(cy), prevBallPosX, prevBallPosY, nextBallX, nextBallY);
                    if (nbc==1 && cit) {
                        blocks[cx][cy].setHitCornerX(right(cx));
                        blocks[cx][cy].setHitCornerY(bottom(cy));
                        return blocks[cx][cy];
                    }
                }
            }
        }
        return null;
    }

    // Checks all Block3 triangles near the ball's path for hypotenuse collisions and reflects
    // the ball off the first hypotenuse hit.  Returns true if a hit was processed.
    // The hypotenuse of every Block3 is a 45-degree line, so the reflection simply swaps (or
    // negates+swaps) dx/dy.
    // Whether the ball's center cell (cx, cy) actually constitutes solid contact.
    // A triangle only fills half its cell, so being in the cell isn't enough by itself:
    // the ball must be on the solid side of the hypotenuse, or within radius of it.
    private boolean centerCellHasSolidOverlap(Ball ball, int cx, int cy, Content ct) {
        if (ct == Content.NO_BLOCK) return false;
        if (ct != Content.HIT_BLOCK3_BL && ct != Content.HIT_BLOCK3_TL
                && ct != Content.HIT_BLOCK3_TR && ct != Content.HIT_BLOCK3_BR) {
            return true; // HIT_BLOCK4 or HIT_BORDER
        }
        Block b = blocks[cx][cy];
        if (!(b instanceof Block3)) return true;
        return ballOverlapsTriangleSolid(ball, (Block3) b, cx, cy);
    }

    private boolean ballOverlapsTriangleSolid(Ball ball, Block3 b, int cx, int cy) {
        Block3.tTriangle type = b.getType();
        float lx = left(cx), rx = right(cx), ty = top(cy), by = bottom(cy);
        boolean isBLorTR = (type == Block3.tTriangle.BL || type == Block3.tTriangle.TR);

        float lineVal = isBLorTR
                ? ball.getX() - ball.getY() + (by - rx)
                : ball.getX() + ball.getY() - (rx + ty);

        boolean emptyIsNeg = (type == Block3.tTriangle.TR || type == Block3.tTriangle.BR);
        boolean onEmptySide = emptyIsNeg ? (lineVal < 0) : (lineVal > 0);
        if (!onEmptySide) return true; // on/over the solid side already

        // On the empty side: only a hit if the ball's edge reaches the hypotenuse segment.
        float dist = Math.abs(lineVal) / (float) Math.sqrt(2.0);
        if (dist > ballRadius) return false;

        float sx1, sy1, sx2, sy2;
        if (isBLorTR) { sx1 = lx; sy1 = ty; sx2 = rx; sy2 = by; }
        else          { sx1 = rx; sy1 = ty; sx2 = lx; sy2 = by; }
        float sdx = sx2 - sx1, sdy = sy2 - sy1;
        float t = ((ball.getX() - sx1) * sdx + (ball.getY() - sy1) * sdy) / (sdx * sdx + sdy * sdy);
        return t >= -EPSILON && t <= 1 + EPSILON;
    }

    // One hypotenuse (or shared-vertex tip) hit found by findNearestTriangleHit: the block at
    // (cx,cy), the time t (in [0, maxT] of the search segment) at which the ball's center first
    // reaches trigger distance, the contact point (hx,hy), and the post-bounce velocity.
    private static final class TriangleHit {
        final int cx, cy;
        final float t, hx, hy, newDx, newDy;
        TriangleHit(int cx, int cy, float t, float hx, float hy, float newDx, float newDy) {
            this.cx = cx; this.cy = cy; this.t = t;
            this.hx = hx; this.hy = hy; this.newDx = newDx; this.newDy = newDy;
        }
    }

    // Finds the nearest triangle-hypotenuse (or shared-vertex tip) hit along the segment from
    // (startX,startY) to (startX + maxT*dx, startY + maxT*dy), i.e. t restricted to [0, maxT]
    // instead of the fixed [0,1] of a full step. Used by checkTriangleHypotenuses to resolve
    // every bounce within a step, not just the first.
    private TriangleHit findNearestTriangleHit(float startX, float startY, float dx, float dy, float maxT) {
        int cx1 = getXBlock(startX), cy1 = getYBlock(startY);
        int cx2 = getXBlock(startX + maxT * dx), cy2 = getYBlock(startY + maxT * dy);
        int xFrom = Math.max(0,      Math.min(cx1, cx2) - 1);
        int xTo   = Math.min(xDim-1, Math.max(cx1, cx2) + 1);
        int yFrom = Math.max(0,      Math.min(cy1, cy2) - 1);
        int yTo   = Math.min(yDim-1, Math.max(cy1, cy2) + 1);

        // Trigger: ball center at distance ballRadius from the hypotenuse line.
        // For a 45-deg line the perpendicular distance is |lineVal| / sqrt(2),
        // so we hit when |lineVal| == ballRadius * sqrt(2).
        float triggerDist = ballRadius * (float) Math.sqrt(2.0);

        float minT  = Float.POSITIVE_INFINITY;
        int   minCx = -1, minCy = -1;
        float minNewDx = 0, minNewDy = 0, minHx = 0, minHy = 0;

        for (int cx = xFrom; cx <= xTo; cx++) {
            for (int cy = yFrom; cy <= yTo; cy++) {
                if (!cellOccupiedWithRealBlock(cx, cy)) continue;
                Block b = blocks[cx][cy];
                if (!(b instanceof Block3)) continue;
                Block3.tTriangle type = ((Block3) b).getType();

                float lx = left(cx), rx = right(cx), ty = top(cy), by = bottom(cy);
                boolean isBLorTR = (type == Block3.tTriangle.BL || type == Block3.tTriangle.TR);

                // Signed-distance value for the ball's starting position.
                // BL/TR line: x - y + (bottom - right) = 0
                // TL/BR line: x + y - (right  + top)   = 0
                float lineVal, lineDot;
                if (isBLorTR) {
                    lineVal = startX - startY + (by - rx);
                    lineDot = dx - dy;
                } else {
                    lineVal = startX + startY - (rx + ty);
                    lineDot = dx + dy;
                }

                if (Math.abs(lineDot) < EPSILON) continue;

                // Empty sides: TR and BR have lineVal < 0; BL and TL have lineVal > 0.
                boolean emptyIsNeg = (type == Block3.tTriangle.TR || type == Block3.tTriangle.BR);
                if (emptyIsNeg ? lineVal >= 0 : lineVal <= 0) continue; // ball on solid side

                // Must genuinely be approaching from outside the trigger radius. Without this,
                // a ball that already starts within the trigger zone but is moving AWAY from the
                // hypotenuse (e.g. right after a previous bounce) would still solve for a t in
                // [0,maxT] where |lineVal| grows back out to triggerDist, registering a bogus hit.
                if (emptyIsNeg ? (lineVal > -triggerDist) : (lineVal < triggerDist)) continue;

                // t at which |lineVal + t*lineDot| == triggerDist (first hit from empty side)
                float targetVal = emptyIsNeg ? -triggerDist : triggerDist;
                float t = (targetVal - lineVal) / lineDot;
                if (t < 0 || t > maxT) continue;

                // Centre position at the moment of contact
                float hx = startX + t * dx;
                float hy = startY + t * dy;

                // Verify the contact lies within the hypotenuse segment
                float sx1, sy1, sx2, sy2;
                if (isBLorTR) { sx1=lx; sy1=ty; sx2=rx; sy2=by; }
                else          { sx1=rx; sy1=ty; sx2=lx; sy2=by; }
                float sdx = sx2-sx1, sdy = sy2-sy1;
                float tSeg = ((hx-sx1)*sdx + (hy-sy1)*sdy) / (sdx*sdx + sdy*sdy);
                if (tSeg < -EPSILON || tSeg > 1+EPSILON) {
                    // The perpendicular contact point falls off the finite hypotenuse segment.
                    // The ball can still clip the nearer endpoint directly -- this is common where
                    // two triangles' hypotenuses share a vertex (e.g. a TL and a TR side by side),
                    // which getCornerHitBlock intentionally ignores because more than one block
                    // touches that point (it assumes that means a flat interior wall, not a tip).
                    float ex = tSeg < 0 ? sx1 : sx2;
                    float ey = tSeg < 0 ? sy1 : sy2;
                    float tc = timeToReachCorner(startX, startY, dx, dy, ex, ey);
                    if (tc <= maxT && tc < minT) {
                        minT = tc;
                        minCx = cx; minCy = cy;
                        float cHitX = startX + tc * dx;
                        float cHitY = startY + tc * dy;
                        float[] newVel = reflectVelocityOffCorner(dx, dy, cHitX, cHitY, ex, ey);
                        minNewDx = newVel[0];
                        minNewDy = newVel[1];
                        minHx = cHitX; minHy = cHitY;
                    }
                    continue;
                }

                if (t < minT) {
                    minT = t;
                    minCx = cx; minCy = cy;
                    // Reflection: BL/TR swaps components; TL/BR negates and swaps.
                    if (isBLorTR) { minNewDx = dy;  minNewDy = dx;  }
                    else          { minNewDx = -dy; minNewDy = -dx; }
                    minHx = hx; minHy = hy;
                }
            }
        }

        if (minCx < 0) return null;
        return new TriangleHit(minCx, minCy, minT, minHx, minHy, minNewDx, minNewDy);
    }

    // Number of same-step triangle bounces to resolve before giving up. Only matters for
    // geometrically-impossible cases like two adjacent same-orientation triangles forming a
    // corridor narrower than the ball (see enforceTriangleClearance/-SquareClearance): the ball
    // keeps bouncing between them and never escapes, so the cap stops the search rather than
    // looping until the step's whole time budget is exhausted mid-bounce.
    private static final int MAX_TRIANGLE_BOUNCES_PER_STEP = 8;

    private boolean checkTriangleHypotenuses(float prevX, float prevY, Ball ball) {
        float curX = prevX, curY = prevY;
        float remaining = 1f;
        boolean anyHit = false;
        // Cells already credited with a hit this call, so a ball bouncing back and forth between
        // two blocks within one step (the narrow-corridor case) doesn't double-hit either one.
        int[] hitCx = new int[MAX_TRIANGLE_BOUNCES_PER_STEP];
        int[] hitCy = new int[MAX_TRIANGLE_BOUNCES_PER_STEP];
        int hitCount = 0;

        // A single tick can involve more than one hypotenuse bounce -- e.g. two adjacent
        // same-orientation triangles forming a narrow zigzag corridor. Resolving only the first
        // bounce and extrapolating the rest of the step unchecked used to let the ball tunnel
        // into the next triangle's solid area, which enforceTriangleClearance/-SquareClearance
        // would then shove it back out of on the *next* tick -- producing a large net jump and
        // tripping the "moved too far" sanity check. Keep resolving bounces against whatever's
        // left of the step instead.
        for (int bounce = 0; bounce < MAX_TRIANGLE_BOUNCES_PER_STEP && remaining > EPSILON; bounce++) {
            TriangleHit hitResult = findNearestTriangleHit(curX, curY, ball.getDx(), ball.getDy(), remaining);
            if (hitResult == null) {
                // Nothing more to hit in what's left of the step -- cover it in a straight line,
                // unless that lands the ball inside another block's solid area. That happens when
                // two adjacent same-orientation triangles form a corridor narrower than the ball:
                // the block just bounced off correctly reflects the ball, but its hypotenuse line,
                // extended past its own finite segment, already reads as "solid" for the next
                // block's line well before the ball geometrically reaches it -- so
                // findNearestTriangleHit can't see that second collision coming (see
                // enforceTriangleClearance/-SquareClearance for the same corridor case handled
                // after the fact). Rather than tunnel into it, stay at the bounce point this tick;
                // the next tick starts the search fresh from a position that's actually clear.
                if (anyHit) {
                    float endX = curX + remaining * ball.getDx();
                    float endY = curY + remaining * ball.getDy();
                    ball.setPos(endX, endY);
                    if (ballOnBlock(ball)) {
                        ball.setPos(curX, curY);
                    }
                }
                return anyHit;
            }

            anyHit = true;
            remaining -= hitResult.t;
            ball.setSpeed(hitResult.newDx, hitResult.newDy);
            curX = hitResult.hx;
            curY = hitResult.hy;
            ball.setPos(curX, curY);

            boolean alreadyCredited = false;
            for (int i = 0; i < hitCount; i++) {
                if (hitCx[i] == hitResult.cx && hitCy[i] == hitResult.cy) { alreadyCredited = true; break; }
            }
            if (!alreadyCredited) {
                hit(hitResult.cx, hitResult.cy);
                hitCx[hitCount] = hitResult.cx;
                hitCy[hitCount] = hitResult.cy;
                hitCount++;
            }
        }

        // Either the step's remaining distance was fully consumed by bounces, or the bounce cap
        // was hit. Either way, leave the ball at its last bounce point rather than extrapolating
        // further into geometry that just kept bouncing it back.
        return anyHit;
    }

    // Time (as a fraction of the step, in [0,1]) at which a ball moving from (prevX,prevY) with
    // velocity (dx,dy) first comes within ballRadius of point (cx,cy). POSITIVE_INFINITY if it
    // never does within this step. Same quadratic as ballCollisionWithCorner, factored out so
    // checkTriangleHypotenuses can use it without going through getCornerHitBlock's nbc==1 gate.
    private float timeToReachCorner(float prevX, float prevY, float dx, float dy, float cx, float cy) {
        float t1 = prevX - cx;
        float t2 = prevY - cy;
        float a = dx * dx + dy * dy;
        float b = 2 * t1 * dx + 2 * t2 * dy;
        float c = t1 * t1 + t2 * t2 - radiusSquare;
        float disc = b * b - 4 * a * c;
        if (disc < 0) return Float.POSITIVE_INFINITY;
        float q = (float) Math.sqrt(disc);
        float v1 = (-b + q) / (2 * a);
        float v2 = (-b - q) / (2 * a);
        float t = Math.min(v1, v2);
        if (t < 0) t = Math.max(v1, v2);
        if (t < 0 || t > 1) return Float.POSITIVE_INFINITY;
        return t;
    }

    // Reflects velocity (dx,dy) off a point-corner at (cx,cy), given the ball center is at
    // (hitX,hitY) at the moment of contact. Same normal-reflection math as ballCollisionWithCorner.
    private float[] reflectVelocityOffCorner(float dx, float dy, float hitX, float hitY, float cx, float cy) {
        float nx = cx - hitX;
        float ny = cy - hitY;
        float px = cx - dx;
        float py = cy - dy;
        float v = (ny * py - ny * cy - nx * cx + nx * px) / (nx * nx + ny * ny);
        float sx = cx + v * nx;
        float sy = cy + v * ny;
        float wx = sx - px;
        float wy = sy - py;
        float ux = px + 2 * wx;
        float uy = py + 2 * wy;
        return new float[] { ux - cx, uy - cy };
    }

    // Returns true if the block in cell (cellX, cellY) geometrically covers corner (cx, cy).
    // Block4 (square) covers all four cell corners.
    // Block3 triangles each omit one corner — the one opposite the right angle.
    private boolean blockCoversCorner(int cellX, int cellY, float cx, float cy) {
        if (!cellOccupiedWithRealBlock(cellX, cellY)) return false;
        Block b = blocks[cellX][cellY];
        if (!(b instanceof Block3)) return true;
        switch (((Block3) b).getType()) {
            case BL: return !(Math.abs(cx - right(cellX)) < EPSILON && Math.abs(cy - top(cellY))    < EPSILON);
            case TL: return !(Math.abs(cx - right(cellX)) < EPSILON && Math.abs(cy - bottom(cellY)) < EPSILON);
            case TR: return !(Math.abs(cx - left(cellX))  < EPSILON && Math.abs(cy - bottom(cellY)) < EPSILON);
            case BR: return !(Math.abs(cx - left(cellX))  < EPSILON && Math.abs(cy - top(cellY))    < EPSILON);
        }
        return true;
    }

    // Used by ballOnBlock: a neighbour cell only counts as a real hit at (cornerX, cornerY) if
    // it's the world border (always solid) or an actual block whose own geometry covers that
    // corner. Without the blockCoversCorner check, being merely adjacent to a triangle that
    // points away from the corner would be wrongly treated as touching solid material there.
    private boolean cornerCoveredByNeighbour(int x, int y, float cornerX, float cornerY) {
        Content ct = cellType(x, y);
        if (ct == Content.NO_BLOCK) return false;
        if (ct == Content.HIT_BORDER) return true;
        return blockCoversCorner(x, y, cornerX, cornerY);
    }

    private int numBlocksAtCorner(float cx, float cy) {
        float d = blockWidth/10.0f;
        int x1 = getXBlock(cx-d), y1 = getYBlock(cy-d);
        int x2 = getXBlock(cx+d), y2 = getYBlock(cy-d);
        int x3 = getXBlock(cx+d), y3 = getYBlock(cy+d);
        int x4 = getXBlock(cx-d), y4 = getYBlock(cy+d);

        return (blockCoversCorner(x1,y1,cx,cy)?1:0) +
               (blockCoversCorner(x2,y2,cx,cy)?1:0) +
               (blockCoversCorner(x3,y3,cx,cy)?1:0) +
               (blockCoversCorner(x4,y4,cx,cy)?1:0);
    }


    private boolean cornerInTrajectory(float qx, float qy, float x1, float y1, float x2, float y2) {
        float rx = x2-x1;
        float ry = y2-y1;
        float lenSq = rx*rx + ry*ry;

        if (lenSq <= EPSILON) {
            return distanceSquare(qx, qy, x1, y1) <= radiusSquare;
        }

        float t = ((qx-x1)*rx + (qy-y1)*ry) / lenSq;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;

        float closestX = x1 + t * rx;
        float closestY = y1 + t * ry;
        return distanceSquare(qx, qy, closestX, closestY) <= radiusSquare;
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

        frozenBall = new Ball(balls[ball]);

        balls[ball].setPaint(dirLinePaint);
        balls[ball].resetMirrorings();
        for ( int b = 0; b < numBalls; b++) {
            balls[b].setSpeed(0,0);
        }
        endOfRollingPhase = true;
        updateCounterAtFreezeTime = updateCounter;
        reusePreviousFireSpeed = true;

        System.out.println("updateCounter @freeze time="+updateCounter);
        System.out.println("x1="+ prevBallPosX);
        System.out.println("y1="+ prevBallPosY);
        System.out.println("x2="+ nextBallX);
        System.out.println("y2="+ nextBallY);
        System.out.println("radius="+ ballRadius);
        System.out.println("hl="+ horizontalReflectionLine(ballBeforeMoving));
        System.out.println("vl="+ verticalReflectionLine(ballBeforeMoving));
        Block block = getBorderHitBlock(prevBallPosX, prevBallPosY, nextBallX, nextBallY);
        if (block == null) {
            block = getCornerHitBlock(prevBallPosX, prevBallPosY, nextBallX, nextBallY);
        }

        if (block != null) {
            int hitBlockX = block.getX();
            int hitBlockY = block.getY();
            System.out.println("left=" + left(hitBlockX));
            System.out.println("top=" + top(hitBlockY));
            System.out.println("right=" + right(hitBlockX));
            System.out.println("bottom=" + bottom(hitBlockY));
        }
        updateCounter = 0;
    }

    private float distance(Ball b1, Ball b2) {
        double v1 = b1.getX()-b2.getX();
        double v2 = b1.getY()-b2.getY();
        return (float)Math.sqrt(v1*v1+v2*v2);
    }

    // Runs all end-of-step corrective safety nets, repeating until a pass makes no further change
    // (or a small iteration cap is hit). A single pass can create a new overlap of its own -- e.g.
    // pushing out of one triangle can land the ball in a diagonally-adjacent block's solid area --
    // so it takes a few rounds for cascading same-step collisions to settle.
    private void enforceCollisionInvariants(Ball ball) {
        for (int i = 0; i < 4; i++) {
            float x = ball.getX(), y = ball.getY();
            enforceBoardBounds(ball);
            enforceTriangleClearance(ball);
            enforceSquareClearance(ball);
            if (ball.getX() == x && ball.getY() == y) break;
        }
    }

    // Safety net: a corner/hypotenuse bounce only checks the border/block collision for the
    // *pre-bounce* portion of the step, then moves the ball for the remaining fraction of the
    // step in the new direction without re-checking for a wall. That can push the ball past the
    // left/right/top playfield border within the same step. Reflect it back if that happened.
    // (Bottom is deliberately excluded: the ball is meant to fall past it into the fire zone.)
    private void enforceBoardBounds(Ball ball) {
        float minX = left(0) + ballRadius;
        float maxX = right(xDim - 1) - ballRadius;
        float minY = top(0) + ballRadius;

        if (ball.getX() < minX) {
            ball.setPos(2 * minX - ball.getX(), ball.getY());
            ball.setSpeed(-ball.getDx(), ball.getDy());
        } else if (ball.getX() > maxX) {
            ball.setPos(2 * maxX - ball.getX(), ball.getY());
            ball.setSpeed(-ball.getDx(), ball.getDy());
        }
        if (ball.getY() < minY) {
            ball.setPos(ball.getX(), 2 * minY - ball.getY());
            ball.setSpeed(ball.getDx(), -ball.getDy());
        }
    }

    // Safety net, same rationale as enforceBoardBounds: some collision paths (e.g. a generic
    // wall/face bounce that happens to coincide with a triangle's edge) can leave the ball just
    // barely overlapping a triangle's solid side without ever running the hypotenuse reflection.
    // Binary-searches the straight segment from (x0,y0) -- the ball's position at the start of
    // this step -- to the ball's current (embedded) position, for the point closest to the
    // current position at which the ball no longer overlaps any block, and moves it there.
    //
    // enforceTriangleClearance/-SquareClearance only run when a collision was *missed* by the
    // fast-path checks (reflection lines, checkTriangleHypotenuses) and the ball ended the step
    // embedded in solid material -- notably when two adjacent same-orientation triangles form a
    // corridor narrower than the ball (see the class comment on that known case). Pushing the
    // ball out along the single overlapped block's own local normal, by a fixed clearance
    // distance, doesn't know about that second block and can overshoot straight into it, and on
    // the next step's correction back the other way, again into the first -- a net step distance
    // well beyond one step's speed budget, tripping the "moved too far" invariant.
    //
    // Walking back along the step's own path instead can never move the ball further than this
    // step's own travel already did, however many blocks are involved, so the corrected position
    // is always within budget. It also generalizes: it doesn't need to know why the fast-path
    // checks missed the collision, just that undoing part of this step's motion resolves it.
    private void pullBackToLastSafePoint(Ball ball, float x0, float y0) {
        float x1 = ball.getX(), y1 = ball.getY();

        ball.setPos(x0, y0);
        if (ballOnBlock(ball)) {
            // The step's own start was already embedded (shouldn't normally happen) -- nothing
            // better to do than leave the ball there rather than searching a segment that's
            // embedded at both ends.
            return;
        }

        float loT = 0f, hiT = 1f; // lo: known clear, hi: known embedded
        for (int i = 0; i < 20; i++) {
            float midT = (loT + hiT) / 2f;
            ball.setPos(x0 + midT * (x1 - x0), y0 + midT * (y1 - y0));
            if (ballOnBlock(ball)) {
                hiT = midT;
            } else {
                loT = midT;
            }
        }
        ball.setPos(x0 + loT * (x1 - x0), y0 + loT * (y1 - y0));
    }

    // Safety net: a corner/hypotenuse bounce only checks the border/block collision for the
    // *pre-bounce* portion of the step, then moves the ball for the remaining fraction of the
    // step in the new direction without re-checking for a wall. That, or a missed collision
    // elsewhere, can leave the ball a step ending up overlapping a triangle's solid side without
    // ever running the hypotenuse reflection. If the ball ends a step there, pull it back to the
    // last point along this step's path that's clear (see pullBackToLastSafePoint) and reflect,
    // using the same convention as checkTriangleHypotenuses.
    private void enforceTriangleClearance(Ball ball) {
        int cx = getXBlock(ball.getX());
        int cy = getYBlock(ball.getY());
        if (!cellOccupiedWithRealBlock(cx, cy)) return;
        Block b = blocks[cx][cy];
        if (!(b instanceof Block3)) return;
        if (!ballOverlapsTriangleSolid(ball, (Block3) b, cx, cy)) return;

        Block3.tTriangle type = ((Block3) b).getType();
        boolean isBLorTR = (type == Block3.tTriangle.BL || type == Block3.tTriangle.TR);

        pullBackToLastSafePoint(ball, prevBallPosX, prevBallPosY);

        float dx = ball.getDx(), dy = ball.getDy();
        if (isBLorTR) ball.setSpeed(dy, dx);
        else          ball.setSpeed(-dy, -dx);
        // Don't double-credit a block that was already hit earlier this same step (e.g. a
        // corner bounce that leaves the ball marginally within this same triangle's trigger zone).
        if (cx != stepHitCellX || cy != stepHitCellY) hit(cx, cy);
    }

    // Safety net, same rationale as enforceTriangleClearance: a face bounce resolved for one
    // block can leave the ball's remaining same-step motion tunnel straight into a second,
    // diagonally-adjacent square (e.g. bouncing off one block's left face while already past the
    // top edge of a different block in the next column). Pull back to the last clear point along
    // this step's path (see pullBackToLastSafePoint) and reflect off whichever face is nearest.
    private void enforceSquareClearance(Ball ball) {
        int cx = getXBlock(ball.getX());
        int cy = getYBlock(ball.getY());
        if (!cellOccupiedWithRealBlock(cx, cy)) return;
        if (!(blocks[cx][cy] instanceof Block4)) return;

        float l = left(cx), r = right(cx), t = top(cy), bo = bottom(cy);
        float distLeft = ball.getX() - l;
        float distRight = r - ball.getX();
        float distTop = ball.getY() - t;
        float distBottom = bo - ball.getY();

        // Exclude directions that would push the ball past the world border -- pushing a block
        // in the last column out to its right edge, for instance, would land the ball off-board,
        // which enforceBoardBounds would then have to shove all the way back, overshooting badly.
        float boardMinX = left(0) + ballRadius, boardMaxX = right(xDim - 1) - ballRadius;
        float boardMinY = top(0) + ballRadius;
        if (l - ballRadius < boardMinX) distLeft = Float.POSITIVE_INFINITY;
        if (r + ballRadius > boardMaxX) distRight = Float.POSITIVE_INFINITY;
        if (t - ballRadius < boardMinY) distTop = Float.POSITIVE_INFINITY;

        float minDist = Math.min(Math.min(distLeft, distRight), Math.min(distTop, distBottom));

        pullBackToLastSafePoint(ball, prevBallPosX, prevBallPosY);

        if (minDist == distLeft) {
            ball.setSpeed(-Math.abs(ball.getDx()), ball.getDy());
        } else if (minDist == distRight) {
            ball.setSpeed(Math.abs(ball.getDx()), ball.getDy());
        } else if (minDist == distTop) {
            ball.setSpeed(ball.getDx(), -Math.abs(ball.getDy()));
        } else {
            ball.setSpeed(ball.getDx(), Math.abs(ball.getDy()));
        }
        // Don't double-credit a block that was already hit earlier this same step.
        if (cx != stepHitCellX || cy != stepHitCellY) hit(cx, cy);
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

        // determine the intersection of the end of the direction vector to the normal line
        float v = (ny*py - ny*cy -nx*cx + nx*px)/(nx*nx+ny*ny);
        float sx = cx+v*nx;
        float sy = cy+v*ny;
        float wx = sx-px;
        float wy = sy-py;

        // shifting the distance vector by 2 yields in the end point of the new direction
        float ux = px+2*wx;
        float uy = py+2*wy;


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
        int y = getYPos(currBall);
        if (currBall.movingUpwards()) {
            float line = top(y) + ballRadius;
            // Ball already above the trigger line (passed it without bouncing last step) —
            // advance one cell so the check can fire against the next row up.
            if (currBall.getY() < line) line = top(y - 1) + ballRadius;
            return line;
        } else {
            float line = bottom(y) - ballRadius;
            if (currBall.getY() > line) line = bottom(y + 1) - ballRadius;
            return line;
        }
    }

    private float verticalReflectionLine(Ball currBall) {
        int x = getXPos(currBall);
        if (currBall.movingRight()) {
            float line = right(x) - ballRadius;
            // Ball already past the trigger line — advance one cell to the right.
            if (currBall.getX() > line) line = right(x + 1) - ballRadius;
            return line;
        } else {
            float line = left(x) + ballRadius;
            // Ball already past the trigger line — advance one cell to the left.
            if (currBall.getX() < line) line = left(x - 1) + ballRadius;
            return line;
        }
    }

    private boolean inXRange(int x) {
        return (0<=x && x < xDim);
    }
    private boolean inYRange(int y) {
        return (0<=y && y < yDim);
    }

    private String roundedString(float v, int r) {
        float v1 = v*r;
        float v2 = Math.round(v1);
        float v3 = v2/(float)r;
        return Float.toString(Math.round(v*r)/(float)r);
    }

    private boolean ballOverlapsWithUpperNeighbour(int x, int y, float yPosCrossSection) {
        if ( hasATopNeighbour(x,y)) {
            if (Math.abs(top(y)-yPosCrossSection) <= section1) return true;
        }
        return false;
    }

    private boolean ballOverlapsWithLowerNeighbour(int x, int y, float yPosCrossSection) {
        if ( hasABottomNeighbour(x,y)) {
            if (Math.abs(yPosCrossSection-bottom(y))<= section1) return true;
        }
        return false;
    }



    private boolean ballOverlapsWithRightNeighbour(int x, int y, float xPosCrossSection) {
        if (hasARightNeighbour(x,y)) {
            if (Math.abs(right(x)-xPosCrossSection) <= section1 ) return true;
        }
        return false;
    }

    private boolean ballOverlapsWithLeftNeighbour(int x, int y, float xPosCrossSection) {
        if (hasALeftNeighbour(x,y)) {
            if (Math.abs(xPosCrossSection-left(x)) <= section1 ) return true;
        }
        return false;
    }

    private boolean hasALeftNeighbour(int x, int y) {
        if (!inYRange(y) && !inXRange(x)) {
            System.out.println("STOOOOOOOP!");
        }
        return x > 0 && blocks[x - 1][y] != null;
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
        return y > 0 && blocks[x][y-1] != null;
    }
    private boolean hasABottomNeighbour(int x, int y) {
        if (!inYRange(y) && !inXRange(x)) {
            System.out.println("STOOOOOOOP!");
        }
        return y < yDim-1 && blocks[x][y+1] != null;
    }

    public void setDebugSupport(boolean enabled) {
        this.debugSupport = enabled;
    }

    public boolean isDebugSupportEnabled() {
        return debugSupport;
    }

    public String getDebugSnapshot() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("upd=").append(updateCounter)
                .append(", freeze=").append(freeze)
                .append(", reason=").append(freezeReason)
                .append(", fire=").append(fire)
                .append(", balls=").append(numBalls)
                .append(", newFirePosX=").append(newFirePosX)
                .append(", firePosY=").append(firePosY);

        int dumpBalls = Math.min(numBalls, 3);
        for (int i = 0; i < dumpBalls; i++) {
            sb.append(" | b").append(i)
                    .append("=(")
                    .append(roundedString(balls[i].getX(), 100))
                    .append(",")
                    .append(roundedString(balls[i].getY(), 100))
                    .append(") d=(")
                    .append(roundedString(balls[i].getDx(), 100))
                    .append(",")
                    .append(roundedString(balls[i].getDy(), 100))
                    .append(")");
        }
        return sb.toString();
    }

    public String getDebugReportForSharing() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Title: ").append(freezeReason.isEmpty() ? "Collision bug" : freezeReason).append('\n');
        sb.append("Expected: Ball bounces physically and block counters update correctly.\n");
        sb.append("Actual: ").append(freezeReason.isEmpty() ? "Unexpected collision behavior" : freezeReason).append('\n');
        sb.append("Freeze reason: ").append(freezeReason).append('\n');
        sb.append("Update counter: ").append(updateCounterAtFreezeTime >= 0 ? updateCounterAtFreezeTime : updateCounter).append('\n');
        sb.append("Ball id: ").append(freezeBall).append('\n');
        sb.append("Touch start (x,y): ").append(startPosXTouch).append(", ").append(startPosYTouch).append('\n');
        sb.append("Fire pos (x,y): ").append(firePosX).append(", ").append(firePosY).append('\n');
        sb.append("Aiming line end (x,y): ").append(dirLineX).append(", ").append(dirLineY).append('\n');
        sb.append("Aim vector (dx,dy): ").append(dirLineX - firePosX).append(", ").append(dirLineY - firePosY).append('\n');
        sb.append("Aim vector length: ").append((float)Math.sqrt((dirLineX - firePosX) * (dirLineX - firePosX) + (dirLineY - firePosY) * (dirLineY - firePosY))).append('\n');
        sb.append("Launch speed (dx,dy): ").append(fireSpeedX).append(", ").append(fireSpeedY).append('\n');
        sb.append("Launch speed magnitude: ").append(normSpeed).append('\n');
        float aimAngleDeg = (float)Math.toDegrees(Math.atan2(fireSpeedY, fireSpeedX));
        sb.append("Launch angle (deg): ").append(aimAngleDeg).append('\n');
        sb.append("Prev pos (x,y): ").append(prevBallPosX).append(", ").append(prevBallPosY).append('\n');
        sb.append("Next pos (x,y): ").append(nextBallX).append(", ").append(nextBallY).append('\n');

        if (frozenBall != null) {
            // frozenBall is a snapshot taken before freeze() zeroes every ball's speed, so it
            // still holds the actual velocity at the moment of the freeze (unlike balls[freezeBall],
            // which would always read back (0,0) here).
            sb.append("Velocity (dx,dy): ")
                    .append(frozenBall.getDx())
                    .append(", ")
                    .append(frozenBall.getDy())
                    .append('\n');
        } else {
            sb.append("Velocity (dx,dy): n/a\n");
        }

        int centerX = getXBlock(nextBallX);
        int centerY = getYBlock(nextBallY);
        sb.append("Blocks near hit (x,y,type,value):\n");
        appendNearbyBlocks(sb, centerX, centerY, 1);
        sb.append("Board layout (rows top->bottom, cols left->right):\n");
        appendBoardLayout(sb);
        sb.append("Repro steps: Trigger debug mode, reproduce once, then share this report.\n\n");
        sb.append("Snapshot: ").append(getDebugSnapshot()).append('\n');

        return sb.toString();
    }

    private void appendNearbyBlocks(StringBuilder sb, int centerX, int centerY, int radius) {
        boolean found = false;
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                if (!inXRange(x) || !inYRange(y)) {
                    continue;
                }
                Block b = blocks[x][y];
                if (b != null) {
                    found = true;
                    sb.append("- ")
                            .append(x)
                            .append(",")
                            .append(y)
                            .append(",")
                            .append(blockTypeLabel(b))
                            .append(",")
                            .append(b.getValue())
                            .append('\n');
                }
            }
        }
        if (!found) {
            sb.append("- none\n");
        }
    }

    private void appendBoardLayout(StringBuilder sb) {
        sb.append("Dimensions: ").append(xDim).append("x").append(yDim).append('\n');
        for (int y = 0; y < yDim; y++) {
            sb.append("y=").append(y).append(": ");
            for (int x = 0; x < xDim; x++) {
                if (x > 0) {
                    sb.append(' ');
                }
                Block b = blocks[x][y];
                if (b == null) {
                    sb.append('.');
                } else {
                    sb.append(compactBlockCode(b));
                }
            }
            sb.append('\n');
        }
    }

    private String compactBlockCode(Block b) {
        if (b instanceof Block4) {
            return "S" + b.getValue();
        }
        if (b instanceof Block3) {
            return "T" + ((Block3) b).getType() + b.getValue();
        }
        return b.getClass().getSimpleName() + b.getValue();
    }

    private String blockTypeLabel(Block b) {
        if (b instanceof Block4) {
            return "Square";
        }
        if (b instanceof Block3) {
            return "Triangle-" + ((Block3) b).getType();
        }
        return b.getClass().getSimpleName();
    }

    // One physical bounce event just happened (border, block face, corner, or hypotenuse) -- see
    // collisionsSinceLastBlockHit. Called once per bounce, right where each reflection method
    // already knows whether that bounce was against a block or bare border/paddle, rather than
    // trying to infer it after the fact from hit() (which can fire more than once per bounce, for
    // an overlapping neighbour block).
    private void recordCollision(boolean hitBlock) {
        collisionsSinceLastBlockHit = hitBlock ? 0 : collisionsSinceLastBlockHit + 1;
    }

    private void hit(int x, int y) {
        if (freeze) return;
        stepHitCellX = x;
        stepHitCellY = y;
        if (inXRange(x) && inYRange(y)) {
            Block b = blocks[x][y];
            if (b==null) {
                freeze("ball hit null block", 0);
                return;
            }
            b.hit();
            hitsThisMove++;
            int points = b.getInitialValue() * POINTS_PER_VALUE;
            game.addScore(points);
            game.addAnimation(new ScorePopupAnimation(this, 25, b.getX(), b.getY(), points));
            if (b.getValue() == 0) {
                blocksClearedThisMove++;
                game.addAnimation(new DissolveBlockAnimation(this, 10, b.getX(), b.getY()));
                blocks[b.getX()][b.getY()] = null;
            } else {
                game.addAnimation(new HitBlockAnimation(this, 20, b.getX(), b.getY()));
            }
        }
    }

    private void drawCross(String s, float x, float y) {
        crosses.put(s,new Cross(x,y,s));
        //freeze = true;
    }

    private static final float GAME_OVER_LINE_DASH_LENGTH = 14f;
    private static final float GAME_OVER_LINE_GAP_LENGTH = 10f;

    // Manual dashes rather than Paint#setPathEffect(DashPathEffect): that silently renders solid
    // instead of dashed on a hardware-accelerated Canvas (same issue as the bonus icons' aim-line
    // taper -- see BonusIcons' EXTENDED_PATH comment), which is exactly what this View uses.
    private void drawDashedHorizontalLine(Canvas c, float xStart, float xEnd, float y, Paint paint) {
        float step = GAME_OVER_LINE_DASH_LENGTH + GAME_OVER_LINE_GAP_LENGTH;
        for (float x = xStart; x < xEnd; x += step) {
            c.drawLine(x, y, Math.min(x + GAME_OVER_LINE_DASH_LENGTH, xEnd), y, paint);
        }
    }

    private void actionAfterBallRolling() {

        //freeze("ballAtEnd",0);
        if (freeze) return;

        // DRAG_PADDLE bonus: gone once the move ends, even if it hasn't taken PADDLE_MAX_HITS
        // reflections yet -- it shouldn't linger on screen (or keep catching balls) once this
        // shot is over, and fire() already rearms/resets it fresh for whichever shot comes next.
        paddleActive = false;

        finishMoveRecording();

        // Report this move's shot statistics (and let Game check the bonus-award threshold),
        // regardless of whether the move also ended the level, ended the game, or just triggered
        // the normal board drop.
        game.onRoundEnd(blocksClearedThisMove, recordingNumBalls);

        // check for game win
        if (gameBoardEmpty()) {
            // Curtain-close/open transition (see LevelCompleteAnimation) -- it calls
            // game.increaselevel()/initBoard() itself once fully closed, so the swap happens
            // hidden behind the curtain instead of popping instantly.
            game.addAnimation(new LevelCompleteAnimation(this));
            if (autoPlayMode) {
                game.addAnimation(new TouchReleaseAnimation(this,100));
            }
        } else if (skipNextBoardDrop) {
            // MOVE_STOPPER bonus: skip exactly one automatic board drop, and also grants a
            // reprieve from the game-over check below for this one shot -- even if a block is
            // already sitting in the bottom row (e.g. from the drop before this bonus was armed),
            // so the player gets one more shot to clear it instead of losing despite the bonus.
            skipNextBoardDrop = false;
            if (autoPlayMode) {
                game.addAnimation(new TouchReleaseAnimation(this,50));
            }
        } else if ( hasBlocksInRow(yDim-1) ) {
            game.setGameOver(false);
            for (int i = 0; i < 5 ; i++) {
                game.addAnimation(new GameOverAnimation(this, 20*i, false));
                if (autoPlayMode) {
                    game.addAnimation(new TouchReleaseAnimation(this, 100));
                }
            }
        } else {
            // shift all blocks downwards
            game.addAnimation(new BoardDropAnimation(this, 30));
            if (autoPlayMode) {
                game.addAnimation(new TouchReleaseAnimation(this,50));
            }
        }
    }

    private void createBoardCopy() {
        blocksCopy = copyBlocksGrid();
    }

    private Block[][] copyBlocksGrid() {
        Block[][] copy = new Block[xDim][yDim];
        for (int x = 0; x < xDim; x++) {
            for (int y = 0; y < yDim; y++) {
                Block b = blocks[x][y];
                if (b instanceof Block3) {
                    copy[x][y] = new Block3((Block3) b);
                } else if (b instanceof Block4) {
                    copy[x][y] = new Block4((Block4) b);
                } else {
                    copy[x][y] = null;
                }
            }
        }
        return copy;
    }

    // Starts recording the shot that is about to launch (see the recordingMove field comment).
    private void startMoveRecording(List<Bonus> consumedBonuses) {
        recordingBlockSnapshot = copyBlocksGrid();
        recordingFrames = new ArrayList<>();
        recordingNumBalls = numBalls;
        recordingFirePosX = firePosX;
        recordingFireSpeedX = fireSpeedX;
        recordingFireSpeedY = fireSpeedY;
        recordingBonuses = consumedBonuses;
        recordingMove = true;
    }

    // Captures one tick of the in-flight move; called once per update() while balls are rolling.
    private void captureReplayFrame() {
        float[] bx = new float[recordingNumBalls];
        float[] by = new float[recordingNumBalls];
        for (int i = 0; i < recordingNumBalls; i++) {
            bx[i] = balls[i].getX();
            by[i] = balls[i].getY();
        }
        int[] blockValues = new int[xDim * yDim];
        for (int x = 0; x < xDim; x++) {
            for (int y = 0; y < yDim; y++) {
                Block b = blocks[x][y];
                blockValues[x * yDim + y] = (b != null) ? b.getValue() : -1;
            }
        }
        recordingFrames.add(new ReplayFrame(bx, by, blockValues));
    }

    // Finalizes the recording once the move ends, so it becomes available for replay/export.
    private void finishMoveRecording() {
        if (!recordingMove) return;
        lastMoveFrames = recordingFrames;
        lastMoveBlockSnapshot = recordingBlockSnapshot;
        // Captured here (blocks already reflect this move's hits, but not yet any board drop for
        // the next move) so the export can show both sides of the shot for analysis.
        lastMoveBlockSnapshotAfter = copyBlocksGrid();
        lastMoveFirePosX = recordingFirePosX;
        lastMoveFireSpeedX = recordingFireSpeedX;
        lastMoveFireSpeedY = recordingFireSpeedY;
        lastMoveBonuses = recordingBonuses;
        recordingMove = false;
        recordingFrames = null;
        recordingBlockSnapshot = null;
        completedMoveCount++;
    }

    public boolean hasLastMoveRecording() {
        return lastMoveFrames != null && !lastMoveFrames.isEmpty();
    }

    // Bumped once per finished move (see finishMoveRecording()). Lets a caller that just kicked
    // off a shot (e.g. Game.importAndReplayDebugText()'s "Abspielen in Zeitlupe") detect when
    // *that specific* shot -- not just any previously recorded one -- has finished, by comparing
    // against the value read before firing.
    public int getCompletedMoveCount() {
        return completedMoveCount;
    }

    // Total blocks hit (see hit()) during the move that just ended, regardless of whether that
    // hit actually cleared the block -- Game.onRoundEnd() reads this right after
    // actionAfterBallRolling() calls it, before the next fire() resets it, for the "Treffer pro
    // Schuss" statistic (separate from blocksClearedThisMove's "Steine entfernt pro Schuss").
    public int getHitsThisMove() {
        return hitsThisMove;
    }

    // DRAG_PADDLE bonus: moves the paddle to follow a finger drag (see touchDown()/touchMove()),
    // clamped so it can't be dragged off the board. Harmless to call while the bonus isn't active.
    private void movePaddleTo(float x) {
        float halfWidth = paddleWidth / 2f;
        paddlePosX = Math.max(offsetX + halfWidth, Math.min(offsetX + width - halfWidth, x));
    }

    // Text report of the last completed shot (board layout right before it, start x, launch
    // angle, bonuses spent on it) for the burger menu's "Letzten Zug exportieren" action -- meant
    // to be pasted into a debugging conversation. Null if no shot has completed yet.
    public String getLastMoveReport() {
        if (!hasLastMoveRecording()) return null;

        float dx = lastMoveFireSpeedX;
        float dy = lastMoveFireSpeedY;
        // Same convention as clampAimVector(): 0 = straight up, positive = tilted right.
        float angleFromUpDeg = (float) Math.toDegrees(Math.atan2(dx, -dy));

        StringBuilder sb = new StringBuilder(512);
        sb.append("BlockPong - Letzter Zug (Debug-Export)\n");
        sb.append("Level: ").append(game.getLevel()).append('\n');
        sb.append("Baelle: ").append(recordingNumBalls).append('\n');
        sb.append("Angewendete Boni: ")
                .append(lastMoveBonuses.isEmpty() ? "keine" : lastMoveBonuses.toString())
                .append('\n');
        sb.append("Startpunkt x: ").append(lastMoveFirePosX)
                .append(" (Feldbreite: ").append(width).append(", offsetX: ").append(offsetX).append(")\n");
        sb.append("Wurfrichtung (dx,dy): ").append(dx).append(", ").append(dy).append('\n');
        sb.append("Winkel (Grad, 0=gerade nach oben, + nach rechts): ").append(angleFromUpDeg).append('\n');
        sb.append("Spielfeld vor dem Zug (JSON, kompatibel mit tools/level_editor.py):\n");
        sb.append(exportBlocksJson(lastMoveBlockSnapshot)).append('\n');
        sb.append("Spielfeld nach dem Zug (JSON, kompatibel mit tools/level_editor.py):\n");
        sb.append(exportBlocksJson(lastMoveBlockSnapshotAfter));
        return sb.toString();
    }

    public int getLastMoveFrameCount() {
        return lastMoveFrames == null ? 0 : lastMoveFrames.size();
    }

    // Renders one recorded frame of the last move: the frozen block snapshot (skipping cells
    // already destroyed by this frame, with each remaining block's value updated to match) plus
    // the recorded ball positions -- used by the burger menu's "Replay in slow motion" action
    // instead of the live board while gameplay itself is paused (see Game.java).
    public void drawReplayFrame(Canvas c, int frameIndex) {
        if (lastMoveFrames == null || frameIndex < 0 || frameIndex >= lastMoveFrames.size()) return;
        ReplayFrame frame = lastMoveFrames.get(frameIndex);

        for (int x = 0; x < xDim; x++) {
            for (int y = 0; y < yDim; y++) {
                int val = frame.blockValues[x * yDim + y];
                Block b = lastMoveBlockSnapshot[x][y];
                if (val >= 0 && b != null) {
                    b.value = val;
                    b.draw(c);
                }
            }
        }

        for (int i = 0; i < frame.ballX.length; i++) {
            c.drawCircle(frame.ballX[i], frame.ballY[i], ballRadius, ballPaint);
        }

        drawDashedHorizontalLine(c, offsetX, offsetX + width, gameOverLineY(), gameOverLinePaint);

        c.drawLine(offsetX - boundaryPaint.getStrokeWidth() / 2, offsetY + height, offsetX - boundaryPaint.getStrokeWidth() / 2, offsetY, boundaryPaint);
        c.drawLine(offsetX - boundaryPaint.getStrokeWidth() / 2, offsetY, offsetX + width + boundaryPaint.getStrokeWidth() / 2, offsetY, boundaryPaint);
        c.drawLine(offsetX + width + boundaryPaint.getStrokeWidth() / 2, offsetY, offsetX + width + boundaryPaint.getStrokeWidth() / 2, offsetY + height, boundaryPaint);
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

    private void fire(List<Bonus> consumedBonuses) {
        fireCounter = 0;
        fire = true;
        nextFireBall = 0;
        newFirePosSet = false;
        blocksClearedThisMove = 0;
        hitsThisMove = 0;
        collisionsSinceLastBlockHit = 0;

        // Reset-then-rearm here (not in applyConsumedBonus(), called earlier for the other
        // bonuses in touchRelease()) so this runs for every shot, including ones that don't spend
        // DRAG_PADDLE (clearing any paddle left over from a previous shot) and ones fired directly
        // via importAndReplayForDebug(), which calls fire() without going through touchRelease()
        // or applyConsumedBonus() at all.
        paddleActive = consumedBonuses.contains(Bonus.DRAG_PADDLE);
        if (paddleActive) {
            paddleWidth = width * PADDLE_INITIAL_WIDTH_FRACTION;
            paddlePosX = firePosX;
            paddleHitsRemaining = PADDLE_MAX_HITS;
            paddleShrinkPerHit = paddleWidth / PADDLE_MAX_HITS;
        }

        // store game board for later debugging
        createBoardCopy();
        startMoveRecording(consumedBonuses);

    }


    // The aim direction must always point up into the board and stay at least this many degrees
    // away from the horizontal on either side (an all but flat shot would be unplayable, and one
    // pointing below horizontal would send the ball away from the board entirely).
    private static final float MIN_LAUNCH_ANGLE_DEG = 10f;
    // Same bound expressed as the max deviation from straight up, which is what the atan2 below
    // is measured against.
    private static final float MAX_AIM_ANGLE_FROM_UP_DEG = 90f - MIN_LAUNCH_ANGLE_DEG;

    // Clamps a raw (dx,dy) aim vector (screen coords, y grows downward) so its angle from
    // straight up never exceeds MAX_AIM_ANGLE_FROM_UP_DEG on either side. This is applied to
    // every touch point that feeds the aim line or the launch velocity, so a drag toward or past
    // the bottom of the screen can't select a downward or unplayably shallow shot -- it just
    // clamps at the steepest angle still allowed, same as dragging past a joystick's edge.
    private float[] clampAimVector(float dx, float dy) {
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= EPSILON) {
            return new float[]{0f, -1f};
        }
        double phiDeg = Math.toDegrees(Math.atan2(dx, -dy)); // 0 = straight up, +-90 = horizontal
        if (phiDeg > MAX_AIM_ANGLE_FROM_UP_DEG) phiDeg = MAX_AIM_ANGLE_FROM_UP_DEG;
        if (phiDeg < -MAX_AIM_ANGLE_FROM_UP_DEG) phiDeg = -MAX_AIM_ANGLE_FROM_UP_DEG;
        double rad = Math.toRadians(phiDeg);
        return new float[]{ len * (float) Math.sin(rad), -len * (float) Math.cos(rad) };
    }

    private void setFireSpeed(float dx, float dy) {
        float len = (float)Math.sqrt((dx*dx) + (dy*dy));
        if (len <= EPSILON) {
            fireSpeedX = 0f;
            fireSpeedY = -normSpeed;
            return;
        }
        fireSpeedX = normSpeed * dx/len;
        fireSpeedY = normSpeed * dy/len;
    }


    public boolean ballRolling() {
        boolean result = false;
        for ( int b = 0; b < numBalls; b++) {
            if (!balls[b].isStill()) return true;
        }
        return result;
    }

    // Applies the one-off effect of whichever bonus was just spent on this shot (or does nothing
    // if none was armed). EXTENDED_PATH and MOVE_START_POINT aren't handled here -- they act
    // continuously while armed (see drawDirLine() and touchDown()/touchMove()), so there's
    // nothing left to do once the shot fires. LINE_DELETE isn't handled here either -- it fires
    // immediately when armed instead of on the next shot (see Game.toggleArmedBonus() and
    // triggerLineDeleteBonus()). DRAG_PADDLE also isn't handled here -- unlike these one-off
    // flags/effects it needs to keep evolving through the whole rolling phase, so it's armed in
    // fire() instead (see the paddle fields' comment).
    private void applyConsumedBonus(Bonus bonus) {
        if (bonus == null) return;
        switch (bonus) {
            case MOVE_STOPPER:
                skipNextBoardDrop = true;
                break;
            case EXTRA_BALLS:
                while (numBalls < EXTRA_BALLS_TARGET_COUNT && numBalls < maxNumBalls) {
                    addBall(numBalls);
                }
                break;
            case EXTENDED_PATH:
            case MOVE_START_POINT:
            case LINE_DELETE:
            case DRAG_PADDLE:
                break;
        }
    }

    // Called once by Game after the canvas size is known (see surfaceCreated()) -- see the
    // touchMinX/touchMaxX/touchMinY fields for why this exists.
    public void setTouchDeadZone(float minX, float maxX, float minY) {
        touchMinX = minX;
        touchMaxX = maxX;
        touchMinY = minY;
    }

    private float clampTouchX(float x) {
        return Math.max(touchMinX, Math.min(touchMaxX, x));
    }

    private float clampTouchY(float y) {
        return Math.max(touchMinY, y);
    }

    public void touchDown(float x, float y) {
        x = clampTouchX(x);
        y = clampTouchY(y);

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

        // DRAG_PADDLE bonus: while active, any touch anywhere just drags the paddle -- takes
        // priority over both aiming and the "swipe down to abort" gesture below (whose own
        // startPosXTouch/Y wouldn't get set here, so touchRelease() also bails out early for it).
        if (paddleActive) {
            movePaddleTo(x);
            return;
        }

        if (!ballRolling() && !fire) {
            // ballRolling() alone isn't enough here: right after fire() starts a shot, balls are
            // launched one at a time every few ticks (see update()), so early on none of them
            // have moved yet and ballRolling() still reports false. Without the extra "!fire"
            // check, a touch landing in that gap could re-arm aiming and call fire() again,
            // resetting nextFireBall/fireSpeed mid-launch -- the previously reported "launch
            // angle changes for no reason partway through firing the balls" bug.
            //
            // MOVE_START_POINT bonus: while armed, dragging the start ball relocates the fire
            // point (see touchMove()) for as long as the touch stays below the game-over line,
            // i.e. in the strip where the ball rests. Touching down already inside the playing
            // field skips straight to aiming, same as without the bonus armed.
            movingStartPoint = game.isBonusArmed(Bonus.MOVE_START_POINT) && y > gameOverLineY();
            if (movingStartPoint) {
                moveStartPointTo(x);
            }
            dirLineActive = true;
            float[] v = clampAimVector(x - newFirePosX, y - firePosY);
            dirLineX = newFirePosX + v[0];
            dirLineY = firePosY + v[1];
        } else {
            startPosXTouch = x;
            startPosYTouch = y;
        }
    }

    public void touchMove(float x, float y) {
        x = clampTouchX(x);
        y = clampTouchY(y);
        if (paddleActive) {
            movePaddleTo(x);
            return;
        }
        if (dirLineActive) {
            if (movingStartPoint) {
                if (y > gameOverLineY()) {
                    moveStartPointTo(x);
                } else {
                    // Touch crossed into the playing field: lock the start position and switch
                    // to steering the aim, same as the rest of the drag from here on.
                    movingStartPoint = false;
                }
            }
            float[] v = clampAimVector(x - newFirePosX, y - firePosY);
            dirLineX = newFirePosX + v[0];
            dirLineY = firePosY + v[1];
        }
    }

    // MOVE_START_POINT bonus: moves the fire x-position (clamped to stay on the board) and the
    // still-resting balls' sprites to match, so the ball visibly follows the drag.
    private void moveStartPointTo(float x) {
        float minX = offsetX + ballRadius;
        float maxX = offsetX + width - ballRadius;
        newFirePosX = Math.max(minX, Math.min(maxX, x));
        for (int b = 0; b < numBalls; b++) {
            balls[b].setPos(newFirePosX, firePosY);
        }
    }

    // Called when the OS delivers ACTION_CANCEL instead of a matching touchRelease (a system
    // gesture stole the touch mid-aim). Drops the in-progress aim without firing, so a later,
    // unrelated tap can't be misread by touchRelease() as completing this abandoned gesture.
    public void cancelAim() {
        dirLineActive = false;
        movingStartPoint = false;
    }

    public void touchRelease(float x, float y) {
        x = clampTouchX(x);
        y = clampTouchY(y);
        if (paddleActive) {
            // Dragging the paddle doesn't fire or abort anything -- see touchDown().
            return;
        }
        if (dirLineActive && movingStartPoint) {
            // MOVE_START_POINT bonus: this whole gesture stayed below the game-over line, so it
            // only repositioned the start ball -- don't fire, just wait for the next gesture to
            // aim (see touchDown()/touchMove()).
            dirLineActive = false;
            return;
        }
        if (dirLineActive && y >= firePosY) {
            // Release is still at or below the start line (firePosY, where the resting ball
            // sits) -- the drag never lifted above it, so this isn't a committed aim, just a
            // finger coming back down near where it started. Cancel instead of firing whatever
            // clampAimVector() would default to (reported requirement), so the player can simply
            // touch down again.
            dirLineActive = false;
            return;
        }
        if (dirLineActive) {
            if ( !reusePreviousFireSpeed) {
                firePosX = newFirePosX;

                float[] v = clampAimVector(x - firePosX, y - firePosY);
                setFireSpeed(v[0], v[1]);
            }
            // Applied before fire() so EXTRA_BALLS' extra balls are already in place for this
            // shot's launch sequence. Several bonuses can be armed and spent together.
            List<Bonus> consumedBonuses = game.consumeArmedBonuses();
            for (Bonus bonus : consumedBonuses) {
                applyConsumedBonus(bonus);
            }
            fire(consumedBonuses);
            dirLineActive = false;
            endOfRollingPhase = false;
        }

        if (ballRolling() && !ballDropRunning) {
            if ((startPosXTouch-100 <= x && x <= startPosXTouch+100) &&
                    ( y>= 200 + startPosYTouch )) {
                ballDropRunning = true;
                fire = false;
                for (int i = 0; i <numBalls; i++) {
                    game.addAnimation(new BallDropAnimation(this, 30, balls[i], newFirePosX, firePosY));
                }
            }
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

    // Y of the game-over line: the boundary between the playing field (blocks above) and the
    // strip below it where the start ball rests between shots. Also used by the MOVE_START_POINT
    // bonus to decide whether a drag is still relocating the start ball or already aiming.
    private float gameOverLineY() {
        return bottom(yDim - 1);
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

    // LINE_DELETE bonus: moves only the deleted row and everything below it (rows above stay put
    // since they aren't affected by the shift -- see deleteLineAndShiftUp()).
    public void moveBlocksFromRow(int fromRow, float dy) {
        for ( int y = fromRow; y < yDim; y++) {
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
        createBoardCopy();
    }

    // LINE_DELETE bonus: fires immediately when armed (see Game.toggleArmedBonus()), not on the
    // next shot. Picks one of the (up to) 3 rows with the highest total block value at random and
    // removes it; rows above are untouched, rows below slide up to fill the gap. No-op if the
    // board is empty.
    public void triggerLineDeleteBonus() {
        int lastPlayableRow = yDim - 3;
        List<Integer> candidates = new ArrayList<>();
        for (int y = 0; y <= lastPlayableRow; y++) {
            if (hasBlocksInRow(y)) candidates.add(y);
        }
        if (candidates.isEmpty()) return;
        candidates.sort((a, b) -> Integer.compare(rowValueSum(b), rowValueSum(a)));
        List<Integer> topRows = candidates.subList(0, Math.min(3, candidates.size()));
        int rowToDelete = topRows.get(rand.nextInt(topRows.size()));
        game.addAnimation(new LineDeleteAnimation(this, 30, rowToDelete));
    }

    private int rowValueSum(int y) {
        int sum = 0;
        for (int x = 0; x < xDim; x++) {
            Block b = blocks[x][y];
            if (b != null) sum += b.getValue();
        }
        return sum;
    }

    // Removes rowToDelete and shifts every row below it up by one cell (the inverse of
    // dropAllBlocksByOneCell()), clearing the bottom row that's now vacated. Must cover the same
    // row range as moveBlocksFromRow() (all the way to yDim-1) even though rowToDelete itself is
    // never chosen from the bottom two rows (see triggerLineDeleteBonus()) -- otherwise blocks
    // sitting in those rows get their pixel position slid up by the animation without their
    // model row index following, leaving a stale row that still counts for collisions/game-over.
    public void deleteLineAndShiftUp(int rowToDelete) {
        for (int y = rowToDelete; y < yDim - 1; y++) {
            for (int x = 0; x < xDim; x++) {
                blocks[x][y] = blocks[x][y + 1];
                if (blocks[x][y] != null) {
                    blocks[x][y].setCoords(x, y);
                }
            }
        }
        for (int x = 0; x < xDim; x++) {
            blocks[x][yDim - 1] = null;
        }
        createBoardCopy();
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
        return (int)Math.floor((b.getX()-offsetX)/blockWidth);
    }

    public int getYPos(Ball b) {
        return (int)Math.floor((b.getY()-offsetY)/blockHeight);
    }
    public int getMinXPos(Ball b) {
        float minX = b.getX()-ballRadius;
        return (int)Math.floor((minX-offsetX)/blockWidth);
    }

    public int getMaxXPos(Ball b) {
        float maxX = b.getX()+ballRadius;
        int x = (int)Math.floor((maxX-offsetX)/blockWidth);
        return x;
    }

    public int getMinYPos(Ball b) {
        float minY = b.getY()-ballRadius;
        return (int)Math.floor((minY-offsetY)/blockHeight);
    }

    public int getMaxYPos(Ball b) {
        float maxY = b.getY()+ballRadius;
        int y = (int)Math.floor((maxY-offsetY)/blockHeight);

        return y;
    }



    public boolean ballOnBlock(Ball ball) {

        float x = ball.getX()-offsetX;
        float y = ball.getY()-offsetY;
        int cx = (int)Math.floor(x/blockWidth);
        int cy = (int)Math.floor(y/blockHeight);

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
        boolean neighboursHit = false;

        if ((bb.C==Content.HIT_BLOCK4
                || bb.C==Content.HIT_BLOCK3_TL
                || bb.C==Content.HIT_BLOCK3_BL
                || bb.C==Content.HIT_BLOCK3_TR)
                && distanceSquare(ball, left, top) <= radiusSquare) {
            neighboursHit |= cornerCoveredByNeighbour(cx - 1, cy,     left, top)
                    || cornerCoveredByNeighbour(cx - 1, cy - 1, left, top)
                    || cornerCoveredByNeighbour(cx,     cy - 1, left, top);
        }

        // top-right corner
        if ((bb.C==Content.HIT_BLOCK4
                || bb.C==Content.HIT_BLOCK3_TL
                || bb.C==Content.HIT_BLOCK3_BL
                || bb.C==Content.HIT_BLOCK3_BR)
                && distanceSquare(ball, right, top) <= radiusSquare) {
            neighboursHit |= cornerCoveredByNeighbour(cx,     cy - 1, right, top)
                    || cornerCoveredByNeighbour(cx + 1, cy - 1, right, top)
                    || cornerCoveredByNeighbour(cx + 1, cy,     right, top);
        }

        // bottom-right
        if ((bb.C==Content.HIT_BLOCK4
                || bb.C==Content.HIT_BLOCK3_BR
                || bb.C==Content.HIT_BLOCK3_BL
                || bb.C==Content.HIT_BLOCK3_TR)
                && distanceSquare(ball, right, bot) <= radiusSquare) {
            neighboursHit |= cornerCoveredByNeighbour(cx + 1, cy,     right, bot)
                    || cornerCoveredByNeighbour(cx + 1, cy + 1, right, bot)
                    || cornerCoveredByNeighbour(cx,     cy + 1, right, bot);
        }

        //bot-left
        if ((bb.C==Content.HIT_BLOCK4
                || bb.C==Content.HIT_BLOCK3_TL
                || bb.C==Content.HIT_BLOCK3_BR
                || bb.C==Content.HIT_BLOCK3_TR)
                && distanceSquare(ball, left, bot) <= radiusSquare) {
            neighboursHit |= cornerCoveredByNeighbour(cx,     cy + 1, left, bot)
                    || cornerCoveredByNeighbour(cx - 1, cy + 1, left, bot)
                    || cornerCoveredByNeighbour(cx - 1, cy,     left, bot);
        }

        // check borders
        // left
        if ((bb.C==Content.HIT_BLOCK4
                || bb.C==Content.HIT_BLOCK3_TL
                || bb.C==Content.HIT_BLOCK3_BL) && (ball.getX() - ballRadius <= left))
            neighboursHit |= cellType(cx - 1, cy) != Content.NO_BLOCK;
        if ((bb.C==Content.HIT_BLOCK4
                || bb.C==Content.HIT_BLOCK3_TR
                || bb.C==Content.HIT_BLOCK3_BR) && (ball.getX() + ballRadius >= right))
            neighboursHit |= cellType(cx + 1, cy) != Content.NO_BLOCK;
        if ((bb.C==Content.HIT_BLOCK4
                || bb.C==Content.HIT_BLOCK3_TL
                || bb.C==Content.HIT_BLOCK3_TR) && (ball.getY() - ballRadius <= top))
            neighboursHit |= cellType(cx, cy - 1) != Content.NO_BLOCK;
        if ((bb.C==Content.HIT_BLOCK4
                || bb.C==Content.HIT_BLOCK3_BR
                || bb.C==Content.HIT_BLOCK3_BL) && (ball.getY() + ballRadius >= bot))
            neighboursHit |= cellType(cx, cy + 1) != Content.NO_BLOCK;

        // check diagonals
        if ( bb.C==Content.HIT_BLOCK3_BL || bb.C==Content.HIT_BLOCK3_TR ) {
            float x1 = right;
            float x2 = left;
            float y1 = bot;
            float y2 = top;
            float qx = ball.getX();
            float qy = ball.getY();
            float rx = x2-x1;
            float ry = y2-y1;
            float nx = ry;
            float ny = -rx;
            float w = -(rx * (qy - y1) - ry * (qx - x1)) / (nx * ry - ny * rx);
            float v = (qx - x1 - w * nx) / rx;

            // v>=0 means right/top of diagonal
            // v<=0 means left/bottom of diagonal


            float dSquare = (w * nx) * (w * nx) + (w * ny) * (w * ny);
            if ( dSquare <= radiusSquare) {
                // ball on diagonal
            }
        }



        boolean centerHit = centerCellHasSolidOverlap(ball, cx, cy, bb.C);

        if (centerHit || neighboursHit) {
            return true;
        }

        return false;

    }

    private Content cellType(int x, int y) {
        if (cellOccupiedWithRealBlock(x,y)) {
            return blocks[x][y].blockHitType();
        }
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
        return (int)Math.floor((x-offsetX)/blockWidth);
    }
    private int getYBlock(float y) {
        return (int)Math.floor((y-offsetY)/blockHeight);
    }

    // Test hooks for deterministic collision tests in JVM unit tests.
    void clearBoardForTests() {
        for (int x = 0; x < xDim; x++) {
            for (int y = 0; y < yDim; y++) {
                blocks[x][y] = null;
            }
        }
    }

    // Real shots default to numInitBalls (10) balls, not 1 -- tests that need to track a single
    // ball deterministically (e.g. counting exact paddle reflections) should call this first so
    // the other 9 pre-existing balls don't also launch and contribute untracked events.
    void setNumBallsForTests(int n) {
        numBalls = n;
    }

    void placeSquareBlockForTests(int x, int y, int value) {
        blocks[x][y] = new Block4(this, x, y, value);
    }

    void placeTriangleBlockForTests(int x, int y, Block3.tTriangle type, int value) {
        blocks[x][y] = new Block3(this, x, y, type, value);
    }

    Block getBlockForTests(int x, int y) {
        return blocks[x][y];
    }

    Ball getBallForTests(int index) {
        return balls[index];
    }

    int getCollisionsSinceLastBlockHitForTests() {
        return collisionsSinceLastBlockHit;
    }

    void setCollisionsSinceLastBlockHitForTests(int n) {
        collisionsSinceLastBlockHit = n;
    }

    // The (start x, launch vector) ball at dispatchIndex was actually dispatched with -- see
    // update()'s "if (fire)" block. Lets a test assert every ball in one shot shares the same
    // values, i.e. the shot's start point/angle stayed constant across the whole (staggered)
    // launch sequence.
    float getDispatchedFirePosXForTests(int dispatchIndex) {
        return dispatchedFirePosX[dispatchIndex];
    }

    float getDispatchedFireSpeedXForTests(int dispatchIndex) {
        return dispatchedFireSpeedX[dispatchIndex];
    }

    float getDispatchedFireSpeedYForTests(int dispatchIndex) {
        return dispatchedFireSpeedY[dispatchIndex];
    }

    int getNextFireBallForTests() {
        return nextFireBall;
    }

    boolean isPaddleActiveForTests() {
        return paddleActive;
    }

    float getPaddlePosXForTests() {
        return paddlePosX;
    }

    float getPaddleWidthForTests() {
        return paddleWidth;
    }

    float getBallRadiusForTests() {
        return ballRadius;
    }

    float getFirePosXForTests() {
        return firePosX;
    }

    float getFirePosYForTests() {
        return firePosY;
    }

    float getFireSpeedXForTests() {
        return fireSpeedX;
    }

    float getFireSpeedYForTests() {
        return fireSpeedY;
    }

    boolean stepBallOnceForTests(Ball ball) {
        float prevX = ball.getX();
        float prevY = ball.getY();
        prevBallPosX = prevX;
        prevBallPosY = prevY;
        stepHitCellX = -1;
        stepHitCellY = -1;
        float vLine = verticalReflectionLine(ball);
        float hLine = horizontalReflectionLine(ball);

        ball.update();
        nextBallX = ball.getX();
        nextBallY = ball.getY();

        boolean collisionResolved = false;
        boolean firstRound = true;
        boolean updateRequired = true;

        while (updateRequired) {
            updateRequired = false;
            boolean movingUp;
            boolean movingDown;
            boolean movingLeft;
            boolean movingRight;

            if (firstRound) {
                movingUp = ball.movingMainlyUpwards();
                movingDown = ball.movingMainlyDownwards();
                movingLeft = ball.movingMainlyLeft();
                movingRight = ball.movingMainlyRight();
            } else {
                movingUp = ball.movingUpwards() && !ball.movingMainlyUpwards();
                movingDown = ball.movingDownwards() && !ball.movingMainlyDownwards();
                movingLeft = ball.movingLeft() && !ball.movingMainlyLeft();
                movingRight = ball.movingRight() && !ball.movingMainlyRight();
            }

            if (movingUp) {
                ballCrossedHorizontalReflectionLineUpwards(prevX, prevY, hLine, vLine, ball);
            }
            if (movingDown) {
                ballCrossedHorizontalReflectionLineDownwards(prevX, prevY, hLine, vLine, ball);
            }
            if (movingLeft) {
                ballCrossedVerticalReflectionLineLeftwards(prevX, prevY, vLine, hLine, ball);
            }
            if (movingRight) {
                ballCrossedVerticalReflectionLineRightwards(prevX, prevY, vLine, hLine, ball);
            }

            if (firstRound) {
                updateRequired = true;
                firstRound = false;
            } else if (ball.scheduledMirroring()) {
                ball.performMirroring();
                ball.resetMirrorings();
                nextBallX = ball.getX();
                nextBallY = ball.getY();
                collisionResolved = true;
                updateRequired = true;
                firstRound = true;
            }
        }

        boolean hypHit = checkTriangleHypotenuses(prevX, prevY, ball);
        if (hypHit) {
            collisionResolved = true;
        } else {
            Block cornerHitBlock = getCornerHitBlock(prevX, prevY, nextBallX, nextBallY);
            if (cornerHitBlock != null) {
                int hitBlockX = cornerHitBlock.getX();
                int hitBlockY = cornerHitBlock.getY();
                if (cellOccupiedWithRealBlock(hitBlockX, hitBlockY)) {
                    hit(hitBlockX, hitBlockY);
                    ballCollisionWithCorner(ball, cornerHitBlock.getHitCornerX(), cornerHitBlock.getHitCornerY(), prevX, prevY);
                    collisionResolved = true;
                }
            }
        }
        enforceCollisionInvariants(ball);

        return collisionResolved;
    }

    Block findCornerHitBlockForTests(float prevX, float prevY, float nextX, float nextY) {
        return getCornerHitBlock(prevX, prevY, nextX, nextY);
    }

    /**
     * Checks if a touch point hits the Share Report button in the freeze overlay.
     * Only checks coordinates, not freeze state (freeze state checked elsewhere).
     * @param touchX The x-coordinate of the touch
     * @param touchY The y-coordinate of the touch
     * @return true if the touch is within the button bounds, false otherwise
     */
    public boolean isShareReportButtonHit(float touchX, float touchY) {
        // Recalculate button rect (same as in draw method)
        float buttonX = 50f;
        float buttonY = 400f;
        shareButtonRect.set(buttonX, buttonY, buttonX + SHARE_BUTTON_WIDTH, buttonY + SHARE_BUTTON_HEIGHT);
        boolean hit = shareButtonRect.contains(touchX, touchY);
        System.out.println("isShareReportButtonHit called: touch=(" + touchX + "," + touchY + 
            "), buttonRect=(" + buttonX + "," + buttonY + "," + (buttonX + SHARE_BUTTON_WIDTH) + "," + (buttonY + SHARE_BUTTON_HEIGHT) + 
            "), hit=" + hit);
        return hit;
    }
    
    /**
     * Returns whether the game board is currently frozen (waiting for user interaction after a bug).
     */
    public boolean isFrozen() {
        return freeze;
    }

    // True once the balls have bounced SPEEDUP_TRIGGER_COLLISIONS times in a row (see
    // recordCollision()) without hitting a block -- draw()/isSpeedUpButtonHit() show and hit-test
    // the overlay button only while this holds. Also requires balls to actually still be rolling
    // (nothing to fast-forward through otherwise) and the game not already frozen on a debug
    // anomaly (that overlay takes priority).
    public boolean shouldOfferSpeedUp() {
        return !freeze && ballRolling() && collisionsSinceLastBlockHit >= SPEEDUP_TRIGGER_COLLISIONS;
    }

    // Centered on the board -- computed fresh each call (cheap) so draw() and the touch hit-test
    // below always agree, instead of the Share Report button's pattern of recomputing the same
    // literal coordinates in two places.
    private RectF getSpeedUpButtonRect() {
        float buttonX = offsetX + width / 2f - SPEEDUP_BUTTON_WIDTH / 2f;
        float buttonY = offsetY + height / 2f - SPEEDUP_BUTTON_HEIGHT / 2f;
        speedUpButtonRect.set(buttonX, buttonY, buttonX + SPEEDUP_BUTTON_WIDTH, buttonY + SPEEDUP_BUTTON_HEIGHT);
        return speedUpButtonRect;
    }

    public boolean isSpeedUpButtonHit(float touchX, float touchY) {
        return shouldOfferSpeedUp() && getSpeedUpButtonRect().contains(touchX, touchY);
    }

    // Keeps simulating physics ticks with no rendering in between (see the class-level "Fallback
    // for..." comment style used elsewhere -- this is the "eine Idee wäre, das Spiel ohne
    // Screen-Updates weiter zu simulieren" approach) until the next ball-block collision resets
    // collisionsSinceLastBlockHit, the shot ends (ballRolling() goes false), the game freezes on a
    // debug anomaly, or SPEEDUP_MAX_TICKS is reached, whichever comes first. Runs synchronously on
    // the calling (touch-handling) thread -- purely numeric, no drawing, so even the max tick count
    // completes in well under a frame's worth of wall-clock time.
    public void fastForwardToNextBlockHit() {
        int ticks = 0;
        while (!freeze && ballRolling() && collisionsSinceLastBlockHit >= SPEEDUP_TRIGGER_COLLISIONS
                && ticks < SPEEDUP_MAX_TICKS) {
            update();
            ticks++;
        }
    }

}
