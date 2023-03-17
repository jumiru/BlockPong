package com.jrgames.blockpong;


import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;


/**
 * Game manages all objects in the game and is responsible for updating all states
 * and renders all objects to the screen
 */
public class Game extends SurfaceView implements SurfaceHolder.Callback  {

    private static final int LEFT_BORDER = 10;
    private static final int RIGHT_BORDER = 10;
    private static final int TOP_BORDER = 20;
    private static final int BOTTOM_BORDER = 400;
    private GameLoop gameLoop;
    private GameBoard gameBoard;
    private int canvasWidth;
    private int canvasHeight;

    private Rect blackRect;
    private Paint blackPaint;
    private Paint debugPaint;

    boolean gameOver;

    public int getLevel() {
        return level;
    }

    private int level;
    private boolean gameWon;

    private List<Animation> ongoingAnimations = new ArrayList<>(20);
    private List<Animation> newAnimations = new ArrayList<>(20);


    public Game(Context context, SharedPreferences prefs) {
        super(context);

        //getSurfaceHolder and add callback method
        SurfaceHolder surfaceHolder = getHolder();
        surfaceHolder.addCallback(this);



        setFocusable( true );
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d("Game()", "surfaceCreated()");

        if (gameBoard==null) {
            level = 1;
            Rect frame = holder.getSurfaceFrame();
            canvasWidth = frame.right - frame.left;
            canvasHeight = frame.bottom - frame.top;

            int boardWidth = canvasWidth - LEFT_BORDER - RIGHT_BORDER;
            int boardHeight = canvasHeight - TOP_BORDER - BOTTOM_BORDER;
            gameBoard = new GameBoard(this, (float)boardWidth, (float)boardHeight, LEFT_BORDER, TOP_BORDER);

            blackPaint = new Paint();
            blackPaint.setColor(Color.BLACK);

            debugPaint = new Paint();
            debugPaint.setColor(Color.WHITE);
            debugPaint.setTextSize(50);

            blackRect = new Rect(0,TOP_BORDER+boardHeight,canvasWidth,canvasHeight);
        }


        gameLoop = new GameLoop(this, holder);
        gameLoop.startLoop();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d("Game()", "surfaceChanged()");
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d("Game()", "surfaceDestroyed()");
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
                gameBoard.touchRelease(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                gameBoard.touchMove(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_DOWN:
                if (gameOver) {
                      gameOver = false;
                      gameBoard.initBoard();
                } else {
                    gameBoard.touchDown(event.getX(), event.getY());
                }
                return true;
        }

        return super.onTouchEvent(event);
    }



    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        // draw game board
        gameBoard.draw(canvas);
        canvas.drawRect(blackRect, blackPaint);

        // animations
        synchronized (ongoingAnimations) {
            ongoingAnimations.forEach((a) -> {
                a.draw(canvas);
            });
        }

        canvas.drawText("AVG UPS "+gameLoop.getAverageUPS(), 50, 1800, debugPaint);
        canvas.drawText("AVG FPS "+gameLoop.getAverageFPS(), 50, 1850, debugPaint);

        // buttons


    }

    public void update() {

        // animation updates
        if ( !newAnimations.isEmpty()) {
            int idx = 0;
            while ( idx < newAnimations.size() ) {
                Animation a = newAnimations.get(idx);
                synchronized (ongoingAnimations) {
                    ongoingAnimations.add(a);
                }
                newAnimations.remove(idx);
            }
        }

        synchronized (ongoingAnimations) {
            ongoingAnimations.removeIf(a -> a.update());
        }


        // game updates
        gameBoard.update();
    }

    public void addAnimation(Animation a) {
        synchronized (newAnimations) {
            newAnimations.add(a);
        }
    }

    public void pause() {
        gameLoop.stopLoop();
    }

    public void setGameOver(boolean win) {
        gameOver = true;
        gameWon = win;
    }

    public void increaselevel() {
        level++;
    }

    public boolean isGameOver() {
        return gameOver;
    }
}
