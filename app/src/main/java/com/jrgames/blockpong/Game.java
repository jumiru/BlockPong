package com.jrgames.blockpong;


import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;


/**
 * Game manages all objects in the game and is responsible for updating all states
 * and renders all objects to the screen
 */
public class Game extends SurfaceView implements SurfaceHolder.Callback, GameBoard.GameCallbacks {

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
    private boolean debugMode;
    private int slowMotionFactor = 1;
    private int updateTick;
    private boolean singleStepRequested;

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

        // Two-finger tap: toggle debug + cycle slow-motion (1x -> 2x -> 4x -> 8x -> 1x)
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            System.out.println("TWO-FINGER TAP");
            debugMode = true;
            if (slowMotionFactor == 1) slowMotionFactor = 2;
            else if (slowMotionFactor == 2) slowMotionFactor = 4;
            else if (slowMotionFactor == 4) slowMotionFactor = 8;
            else slowMotionFactor = 1;
            if (gameBoard != null) {
                gameBoard.setDebugSupport(debugMode);
            }
            Log.i("BlockPongDebug", "SlowMotionFactor=" + slowMotionFactor);
            return true;
        }

        // Three-finger tap: one simulation step while in slow-motion for reproducible bug traces.
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 3) {
            System.out.println("THREE-FINGER TAP");
            debugMode = true;
            if (gameBoard != null) {
                gameBoard.setDebugSupport(true);
                Log.i("BlockPongDebug", "SNAPSHOT " + gameBoard.getDebugSnapshot());
            }
            singleStepRequested = true;
            return true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
                System.out.println("ACTION_UP at (" + event.getX() + ", " + event.getY() + ")");
                // Check if Share Report button was hit (and game is frozen)
                if (gameBoard != null && gameBoard.isFrozen() && gameBoard.isShareReportButtonHit(event.getX(), event.getY())) {
                    System.out.println("Button hit! Calling shareDebugReport()");
                    shareDebugReport();
                    return true;
                }
                // Normal touch release
                if (gameBoard != null) {
                    gameBoard.touchRelease(event.getX(), event.getY());
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                System.out.println("ACTION_MOVE at (" + event.getX() + ", " + event.getY() + ")");
                // Don't call touchMove if we're moving over the Share Report button during freeze
                boolean skipTouchMove = false;
                if (gameBoard != null && gameBoard.isFrozen() && gameBoard.isShareReportButtonHit(event.getX(), event.getY())) {
                    skipTouchMove = true;
                }
                if (!skipTouchMove && gameBoard != null) {
                    gameBoard.touchMove(event.getX(), event.getY());
                }
                return true;
            case MotionEvent.ACTION_DOWN:
                System.out.println("ACTION_DOWN at (" + event.getX() + ", " + event.getY() + ")");
                if (gameOver) {
                      gameOver = false;
                      if (gameBoard != null) {
                          gameBoard.initBoard();
                      }
                } else {
                    // Don't call touchDown if we're clicking on the Share Report button during freeze
                    boolean skipTouchDown = false;
                    if (gameBoard != null && gameBoard.isFrozen() && gameBoard.isShareReportButtonHit(event.getX(), event.getY())) {
                        System.out.println("Ignoring touchDown because Share Report button is hit while frozen");
                        skipTouchDown = true;
                    }
                    if (!skipTouchDown && gameBoard != null) {
                        gameBoard.touchDown(event.getX(), event.getY());
                    }
                }
                return true;
        }

        return super.onTouchEvent(event);
    }

    private void shareDebugReport() {
        System.out.println("shareDebugReport() called");
        if (gameBoard == null) {
            System.out.println("ERROR: gameBoard is null in shareDebugReport()");
            return;
        }

        String report = gameBoard.getDebugReportForSharing();
        System.out.println("Generated report length: " + report.length());
        logDebugReportToLogcat(report);

        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("BlockPong Debug Report", report));
            System.out.println("Report copied to clipboard");
        }

        Toast.makeText(getContext(), "Debug-Report kopiert. E-Mail-Entwurf wird geoeffnet.", Toast.LENGTH_SHORT).show();

        Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
        emailIntent.setData(Uri.parse("mailto:"));
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "BlockPong Bug Report");
        emailIntent.putExtra(Intent.EXTRA_TEXT, report);

        if (emailIntent.resolveActivity(getContext().getPackageManager()) != null) {
            System.out.println("Starting email chooser");
            getContext().startActivity(Intent.createChooser(emailIntent, "Debug-Report per E-Mail teilen"));
            return;
        }

        System.out.println("Email app not available, using generic share");
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "BlockPong Bug Report");
        shareIntent.putExtra(Intent.EXTRA_TEXT, report);
        getContext().startActivity(Intent.createChooser(shareIntent, "Debug-Report teilen"));
    }

    private void logDebugReportToLogcat(String report) {
        final String tag = "BlockPongReport";
        Log.i(tag, "===== BEGIN DEBUG REPORT =====");

        String[] lines = report.split("\\r?\\n", -1);
        for (String line : lines) {
            if (line == null) {
                Log.i(tag, "null");
                continue;
            }

            if (line.isEmpty()) {
                Log.i(tag, "");
                continue;
            }

            int start = 0;
            while (start < line.length()) {
                int end = Math.min(start + 3000, line.length());
                String chunk = line.substring(start, end);
                Log.i(tag, chunk);
                start = end;
            }
        }

        Log.i(tag, "===== END DEBUG REPORT =====");
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
        if (debugMode) {
            canvas.drawText("DEBUG ON  SLOW x" + slowMotionFactor, 50, 1700, debugPaint);
        }

        // buttons


    }

    public void update() {

        updateTick++;
        boolean runUpdate = (slowMotionFactor <= 1) || (updateTick % slowMotionFactor == 0);
        if (singleStepRequested) {
            runUpdate = true;
            singleStepRequested = false;
        }
        if (!runUpdate) {
            return;
        }

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

    public void resetGameOver() {
        gameOver = false;
    }

    public void increaselevel() {
        level++;
    }

    public boolean isGameOver() {
        return gameOver;
    }
}
