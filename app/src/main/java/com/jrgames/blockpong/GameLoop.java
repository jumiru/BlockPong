package com.jrgames.blockpong;


import android.graphics.Canvas;
import android.util.Log;
import android.view.SurfaceHolder;

public class GameLoop extends Thread {
    private static final double MAX_UPS = 60.0;
    private static final double UPS_PERIOD = 1E3/MAX_UPS;
    private final Game game;
    private boolean isRunning;
    private SurfaceHolder surfaceHolder;
    private double averageUPS;
    private double averageFPS;


    public GameLoop(Game game, SurfaceHolder surfaceHolder) {
        this.surfaceHolder = surfaceHolder;
        this.game = game;
    }

    public double getAverageUPS() {
        return averageUPS;
    }

    public double getAverageFPS() {
        return averageFPS;
    }

    public void startLoop() {
        Log.d("GameLoop()", "startLoop()");
        isRunning = true;
        start();
        Log.d("GameLoop()", "startLoop(): "+ getState());
    }

    @Override
    public void run() {
        Log.d("GameLoop()", "run()");
        super.run();

        //Declare time and cycle counter variables
        int updateCount = 0;
        int frameCount = 0;

        long startTime = 0;
        long elapsedTime = 0;
        long sleepTime = 0;

        //Game loop
        Canvas canvas = null;

        startTime = System.currentTimeMillis();
        while (isRunning) {

            // try to update and render game
            try {
                canvas = surfaceHolder.lockCanvas();
                synchronized (surfaceHolder) {
                    game.update();
                    updateCount++;

                    game.draw(canvas);
                }
            } catch ( IllegalArgumentException e) {
                e.printStackTrace();
            } finally {
                if ( canvas != null ) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas);
                        frameCount++;
                    } catch(Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            // pause game loop to not exceed target UPS
            elapsedTime = System.currentTimeMillis() - startTime;
            sleepTime = (long) (updateCount*UPS_PERIOD - elapsedTime );

            if ( sleepTime>0 ) {
                try {
                    sleep(sleepTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // skip frames to keep up with target UPS
            while (sleepTime<0 && updateCount < MAX_UPS-1) {
                game.update();
                updateCount++;
                elapsedTime = System.currentTimeMillis() - startTime;
                sleepTime = (long) (updateCount*UPS_PERIOD - elapsedTime );
            }

            // Calculate average FPS and UPS

            elapsedTime = System.currentTimeMillis() - startTime;
            if ( elapsedTime > 1000 ) {
                averageUPS = updateCount / (1E-3 * elapsedTime);
                averageFPS = frameCount / (1E-3 * elapsedTime);
                updateCount = 0;
                frameCount = 0;
                startTime = System.currentTimeMillis();
            }
        }
    }

    public void stopLoop() {
        Log.d("GameLoop()", "stopLoop()");
        isRunning = false;
        // wait for thread to join
        try {
            join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Log.d("GameLoop()", "stopLoop(): "+ getState());
    }
}
