package com.jrgames.blockpong;


import android.app.AlertDialog;
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
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;


/**
 * Game manages all objects in the game and is responsible for updating all states
 * and renders all objects to the screen
 */
public class Game extends SurfaceView implements SurfaceHolder.Callback, GameBoard.GameCallbacks {

    private static final int LEFT_BORDER = 10;
    private static final int RIGHT_BORDER = 10;
    private static final int TOP_BORDER = 20;
    private static final int BOTTOM_BORDER = 400;
    // Gap between the board's bottom edge (where the ball rests on the fire line) and the bonus
    // button row drawn below it, in the reserved footer area.
    private static final int BONUS_ROW_TOP_MARGIN = 20;
    private static final int BONUS_ROW_HEIGHT = 110;
    // Gap between the bonus row and the LEVEL/SCORE/BEST boxes below it.
    private static final int BONUS_ROW_BOTTOM_MARGIN = 20;
    private static final int STATS_BOX_HEIGHT = 150;
    // A move (one shot until all balls are back at rest) earns a random bonus once it clears this
    // many blocks. Fixed and un-tiered for now -- revisit once the bonus effects themselves (see
    // Bonus.java) are wired up and their real-world impact is known. Also the first bonus-tier
    // boundary drawn on the shot-statistics histogram (see drawStatsScreen()).
    private static final int BLOCKS_CLEARED_BONUS_THRESHOLD = 20;
    // Bin width (in blocks cleared) for the shot-statistics histogram.
    private static final int SHOT_HISTOGRAM_BIN_SIZE = 5;
    private static final String PREFS_KEY_BEST_SCORE = "best_score";
    // Snapshot of an in-progress game, written on pause() and restored on the next cold start so
    // the app can pick up where it left off even if Android killed the process to reclaim memory
    // while it sat in the background unused.
    private static final String PREFS_KEY_SAVED_LEVEL = "saved_level";
    private static final String PREFS_KEY_SAVED_SCORE = "saved_score";
    private static final String PREFS_KEY_SAVED_BLOCKS = "saved_blocks";
    private static final String PREFS_KEY_SAVED_BONUS_COUNTS = "saved_bonus_counts";
    // Shot-statistics histograms (blocks cleared per shot, bucketed by how many balls the shot
    // was fired with), collected across all games -- see recordShotStatistics().
    private static final String PREFS_KEY_SHOT_HISTOGRAMS = "shot_histograms";
    private GameLoop gameLoop;
    private GameBoard gameBoard;
    private int canvasWidth;
    private int canvasHeight;

    private Rect blackRect;
    private Paint blackPaint;
    private Paint debugPaint;
    private Paint statBoxPaint;
    private Paint statLabelPaint;
    private Paint statValuePaint;
    private Paint bonusGrayPaint;
    private Paint[] bonusColorPaints;
    private Paint bonusArmedBorderPaint;
    private Paint bonusIconPaint;
    private Paint bonusBadgePaint;
    private Paint bonusBadgeTextPaint;
    private Paint statsTitlePaint;
    private Paint statsDropdownPaint;
    private Paint statsDropdownTextPaint;
    private Paint statsBarPaint;
    private Paint statsBarLabelPaint;
    private Paint statsBarCountPaint;
    private Paint statsThresholdPaint;
    private Paint statsThresholdTextPaint;
    private Paint statsEmptyTextPaint;
    private boolean debugMode;
    private int slowMotionFactor = 1;
    private int updateTick;
    // Fixed slow-motion factor applied whenever debug mode is toggled on via the burger menu
    // (replaces the old two-finger-tap cycling through 1x/2x/4x/8x).
    private static final int DEBUG_SLOW_MOTION_FACTOR = 4;
    private static final int REPLAY_SLOW_MOTION_FACTOR = 6;
    private static final float MENU_BUTTON_SIZE = 90f;
    private static final float MENU_BUTTON_MARGIN = 15f;

    private Paint menuButtonPaint;
    private Paint menuIconPaint;

    // "Replay in slow motion (last move)" playback state, driven by the recording in GameBoard.
    private boolean replaying;
    private int replayFrameIndex;
    private int replayTickCounter;

    // "Statistik" screen (burger menu): shows the shot-statistics histogram (see
    // recordShotStatistics()) for one ballsUsed bucket at a time, picked via a dropdown-style tap
    // target (see showStatsBallsPicker()). null once no shot has been recorded yet for any bucket.
    private boolean showingStats;
    private Integer statsSelectedBallsUsed;
    // ballsUsed -> (bin index = blocksCleared / SHOT_HISTOGRAM_BIN_SIZE) -> shot count. Collected
    // across all games, persisted in saveState()/surfaceCreated().
    private final TreeMap<Integer, TreeMap<Integer, Integer>> shotHistograms = new TreeMap<>();

    boolean gameOver;

    public int getLevel() {
        return level;
    }

    private int level;
    private boolean gameWon;
    private int score;
    private int bestScore;
    private final int[] bonusCounts = new int[Bonus.values().length];
    // The bonuses the player armed via the bonus row, to be spent together on the next shot (see
    // consumeArmedBonuses()) -- multiple can be stacked at once (e.g. EXTRA_BALLS + MOVE_STOPPER
    // for a critical situation). LINE_DELETE never appears here: it fires immediately on tap
    // instead of being armed (see toggleArmedBonus()).
    private final EnumSet<Bonus> armedBonuses = EnumSet.noneOf(Bonus.class);
    // Set by consumeArmedBonuses() when the shot currently in flight spent at least one bonus;
    // read (and reset) by onRoundEnd() so that shot can't also earn a new one.
    private boolean bonusUsedThisShot;
    private final Random bonusRandom = new Random();
    // Tracks a press-and-hold on a bonus button: a Runnable fires the explanation dialog as soon
    // as the long-press timeout elapses (while still held down), rather than waiting for
    // ACTION_UP. bonusLongPressTriggered then tells ACTION_UP to suppress the normal
    // arm/disarm tap it would otherwise perform.
    private Bonus bonusTouchDownBonus;
    private final Handler bonusLongPressHandler = new Handler(Looper.getMainLooper());
    private Runnable bonusLongPressRunnable;
    private boolean bonusLongPressTriggered;

    // Testing cheats via tap sequences on the LEVEL/SCORE/BEST stat boxes (see onStatBoxTapped()):
    // tapping BEST four times in a row resets the highscore; tapping LEVEL, SCORE, BEST, LEVEL in
    // that order grants one of every bonus. Once a cheat is used, the highscore no longer updates
    // for the rest of the session so cheated runs can't taint it.
    private enum StatBox { LEVEL, SCORE, BEST }
    private static final long CHEAT_TAP_WINDOW_MS = 4000;
    private final List<StatBox> statBoxTapSequence = new ArrayList<>();
    private long lastStatBoxTapTime;
    private boolean cheatsUsed;
    private final SharedPreferences prefs;

    private List<Animation> ongoingAnimations = new ArrayList<>(20);
    private List<Animation> newAnimations = new ArrayList<>(20);


    public Game(Context context, SharedPreferences prefs) {
        super(context);
        this.prefs = prefs;

        //getSurfaceHolder and add callback method
        SurfaceHolder surfaceHolder = getHolder();
        surfaceHolder.addCallback(this);



        setFocusable( true );
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d("Game()", "surfaceCreated()");

        if (gameBoard==null) {
            level = prefs.getInt(PREFS_KEY_SAVED_LEVEL, 1);
            score = prefs.getInt(PREFS_KEY_SAVED_SCORE, 0);
            bestScore = prefs.getInt(PREFS_KEY_BEST_SCORE, 0);
            Rect frame = holder.getSurfaceFrame();
            canvasWidth = frame.right - frame.left;
            canvasHeight = frame.bottom - frame.top;

            int boardWidth = canvasWidth - LEFT_BORDER - RIGHT_BORDER;
            int boardHeight = canvasHeight - TOP_BORDER - BOTTOM_BORDER;
            gameBoard = new GameBoard(this, (float)boardWidth, (float)boardHeight, LEFT_BORDER, TOP_BORDER);

            // initBoard() (called from the GameBoard constructor above) just generated a fresh
            // layout for `level`; overwrite it with the exact saved layout, if any, so partially
            // cleared blocks aren't lost.
            String savedBlocks = prefs.getString(PREFS_KEY_SAVED_BLOCKS, null);
            if (savedBlocks != null) {
                gameBoard.restoreBlocksFromJson(savedBlocks);
            }

            String savedBonusCounts = prefs.getString(PREFS_KEY_SAVED_BONUS_COUNTS, null);
            if (savedBonusCounts != null) {
                String[] parts = savedBonusCounts.split(",");
                for (int i = 0; i < bonusCounts.length && i < parts.length; i++) {
                    try {
                        bonusCounts[i] = Integer.parseInt(parts[i]);
                    } catch (NumberFormatException ignored) {
                        // leave that slot at 0
                    }
                }
            }

            loadShotHistograms(prefs.getString(PREFS_KEY_SHOT_HISTOGRAMS, null));

            blackPaint = new Paint();
            blackPaint.setColor(Color.BLACK);

            debugPaint = new Paint();
            debugPaint.setColor(Color.WHITE);
            debugPaint.setTextSize(50);

            statBoxPaint = new Paint();
            statBoxPaint.setColor(Color.rgb(45, 40, 80));

            statLabelPaint = new Paint();
            statLabelPaint.setColor(Color.rgb(190, 180, 220));
            statLabelPaint.setTextSize(32);
            statLabelPaint.setTextAlign(Paint.Align.CENTER);

            statValuePaint = new Paint();
            statValuePaint.setColor(Color.WHITE);
            statValuePaint.setTextSize(56);
            statValuePaint.setTextAlign(Paint.Align.CENTER);
            statValuePaint.setFakeBoldText(true);

            bonusGrayPaint = new Paint();
            bonusGrayPaint.setColor(Color.rgb(55, 55, 60));

            int[] bonusAccentColors = {
                    Color.rgb(255, 152, 0),  // MOVE_STOPPER - orange
                    Color.rgb(33, 150, 243), // EXTENDED_PATH - blue
                    Color.rgb(233, 30, 99),  // LINE_DELETE - pink
                    Color.rgb(76, 175, 80),  // EXTRA_BALLS - green
                    Color.rgb(156, 39, 176), // MOVE_START_POINT - purple
            };
            bonusColorPaints = new Paint[Bonus.values().length];
            for (int i = 0; i < bonusColorPaints.length; i++) {
                Paint p = new Paint();
                p.setColor(bonusAccentColors[i % bonusAccentColors.length]);
                bonusColorPaints[i] = p;
            }

            bonusArmedBorderPaint = new Paint();
            bonusArmedBorderPaint.setStyle(Paint.Style.STROKE);
            bonusArmedBorderPaint.setStrokeWidth(6);
            bonusArmedBorderPaint.setColor(Color.WHITE);

            bonusIconPaint = new Paint();
            bonusIconPaint.setColor(Color.WHITE);
            bonusIconPaint.setStyle(Paint.Style.STROKE);
            bonusIconPaint.setStrokeWidth(6);
            bonusIconPaint.setAntiAlias(true);

            bonusBadgePaint = new Paint();
            bonusBadgePaint.setColor(Color.rgb(220, 20, 20));
            bonusBadgePaint.setAntiAlias(true);

            bonusBadgeTextPaint = new Paint();
            bonusBadgeTextPaint.setColor(Color.WHITE);
            bonusBadgeTextPaint.setTextSize(28);
            bonusBadgeTextPaint.setTextAlign(Paint.Align.CENTER);
            bonusBadgeTextPaint.setFakeBoldText(true);
            bonusBadgeTextPaint.setAntiAlias(true);

            blackRect = new Rect(0,TOP_BORDER+boardHeight,canvasWidth,canvasHeight);

            menuButtonPaint = new Paint();
            menuButtonPaint.setColor(Color.rgb(45, 40, 80));
            menuButtonPaint.setAlpha(220);

            menuIconPaint = new Paint();
            menuIconPaint.setColor(Color.WHITE);
            menuIconPaint.setStrokeWidth(6);
            menuIconPaint.setAntiAlias(true);

            statsTitlePaint = new Paint();
            statsTitlePaint.setColor(Color.WHITE);
            statsTitlePaint.setTextSize(48);
            statsTitlePaint.setFakeBoldText(true);
            statsTitlePaint.setAntiAlias(true);

            statsDropdownPaint = new Paint();
            statsDropdownPaint.setColor(Color.rgb(45, 40, 80));

            statsDropdownTextPaint = new Paint();
            statsDropdownTextPaint.setColor(Color.WHITE);
            statsDropdownTextPaint.setTextSize(36);
            statsDropdownTextPaint.setTextAlign(Paint.Align.CENTER);
            statsDropdownTextPaint.setAntiAlias(true);

            statsBarPaint = new Paint();
            statsBarPaint.setColor(Color.rgb(33, 150, 243));

            statsBarLabelPaint = new Paint();
            statsBarLabelPaint.setColor(Color.rgb(190, 180, 220));
            statsBarLabelPaint.setTextSize(26);
            statsBarLabelPaint.setTextAlign(Paint.Align.CENTER);
            statsBarLabelPaint.setAntiAlias(true);

            statsBarCountPaint = new Paint();
            statsBarCountPaint.setColor(Color.WHITE);
            statsBarCountPaint.setTextSize(28);
            statsBarCountPaint.setTextAlign(Paint.Align.CENTER);
            statsBarCountPaint.setAntiAlias(true);

            statsThresholdPaint = new Paint();
            statsThresholdPaint.setColor(Color.rgb(233, 30, 99));
            statsThresholdPaint.setStrokeWidth(4);
            statsThresholdPaint.setAntiAlias(true);

            statsThresholdTextPaint = new Paint();
            statsThresholdTextPaint.setColor(Color.rgb(233, 30, 99));
            statsThresholdTextPaint.setTextSize(28);
            statsThresholdTextPaint.setFakeBoldText(true);
            statsThresholdTextPaint.setAntiAlias(true);

            statsEmptyTextPaint = new Paint();
            statsEmptyTextPaint.setColor(Color.rgb(190, 180, 220));
            statsEmptyTextPaint.setTextSize(40);
            statsEmptyTextPaint.setTextAlign(Paint.Align.CENTER);
            statsEmptyTextPaint.setAntiAlias(true);
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

        // While a replay is playing, the live board/animations are paused (see update()): the
        // burger menu button still works, and a tap anywhere else skips the replay early.
        if (replaying) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (isMenuButtonHit(event.getX(), event.getY())) {
                    showBurgerMenu();
                } else {
                    replaying = false;
                }
            }
            return true;
        }

        // Statistik screen: the menu button still opens the burger menu, tapping the ballsUsed
        // chip opens its picker (only if there's more than one bucket to switch between), and any
        // other tap closes the screen -- same "tap anywhere to leave" pattern as replaying above.
        if (showingStats) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (isMenuButtonHit(event.getX(), event.getY())) {
                    showBurgerMenu();
                } else if (shotHistograms.size() > 1 && getStatsDropdownRect().contains(event.getX(), event.getY())) {
                    showStatsBallsPicker();
                } else {
                    showingStats = false;
                }
            }
            return true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
                System.out.println("ACTION_UP at (" + event.getX() + ", " + event.getY() + ")");
                // Check if the burger menu button was hit.
                if (isMenuButtonHit(event.getX(), event.getY())) {
                    showBurgerMenu();
                    return true;
                }
                // Check if a bonus button was hit -- arms/disarms it for the next shot instead of
                // being forwarded to the board as an aim release. A press-and-hold on the same
                // button instead shows its explanation dialog (already triggered by the pending
                // Runnable below while the button was still held, so just suppress the tap here).
                cancelBonusLongPress();
                Bonus tappedBonus = getBonusButtonHit(event.getX(), event.getY());
                if (tappedBonus != null) {
                    if (!(tappedBonus == bonusTouchDownBonus && bonusLongPressTriggered)) {
                        toggleArmedBonus(tappedBonus);
                    }
                    bonusTouchDownBonus = null;
                    bonusLongPressTriggered = false;
                    return true;
                }
                bonusLongPressTriggered = false;
                // Check if a LEVEL/SCORE/BEST stat box was hit -- feeds the cheat tap-sequence
                // detector instead of being forwarded to the board.
                StatBox tappedStatBox = getStatBoxHit(event.getX(), event.getY());
                if (tappedStatBox != null) {
                    onStatBoxTapped(tappedStatBox);
                    return true;
                }
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
                if (isMenuButtonHit(event.getX(), event.getY())) {
                    return true;
                }
                cancelBonusLongPress();
                bonusTouchDownBonus = getBonusButtonHit(event.getX(), event.getY());
                bonusLongPressTriggered = false;
                if (bonusTouchDownBonus != null) {
                    final Bonus pressedBonus = bonusTouchDownBonus;
                    bonusLongPressRunnable = () -> {
                        bonusLongPressTriggered = true;
                        showBonusExplanationDialog(pressedBonus);
                    };
                    bonusLongPressHandler.postDelayed(bonusLongPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                if (gameOver) {
                      gameOver = false;
                      score = 0;
                      resetBonuses();
                      if (gameBoard != null) {
                          gameBoard.initBoard();
                      }
                } else {
                    // Don't call touchDown if we're clicking on the Share Report button during
                    // freeze, on a bonus button (handled as a tap/long-press on ACTION_UP), or on
                    // a LEVEL/SCORE/BEST stat box (handled as a cheat tap on ACTION_UP).
                    boolean skipTouchDown = bonusTouchDownBonus != null || getStatBoxHit(event.getX(), event.getY()) != null;
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

    // Burger menu action: exports the last completed shot (board layout before it, start x,
    // launch angle, applied bonuses) as text, for pasting into a debugging conversation (e.g.
    // with Claude) instead of having to describe a bug by hand. Unlike shareDebugReport() above,
    // this works after any normal shot, not just while frozen on a detected collision bug.
    private void exportLastMoveReport() {
        String report = gameBoard == null ? null : gameBoard.getLastMoveReport();
        if (report == null) {
            Toast.makeText(getContext(), "Kein letzter Zug zum Exportieren vorhanden.", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("BlockPong Letzter Zug", report));
        }
        Toast.makeText(getContext(), "Letzter Zug kopiert.", Toast.LENGTH_SHORT).show();

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "BlockPong - Letzter Zug");
        shareIntent.putExtra(Intent.EXTRA_TEXT, report);
        getContext().startActivity(Intent.createChooser(shareIntent, "Letzten Zug teilen"));
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

        if (replaying) {
            // Ghost replay of the last move: frozen block snapshot + recorded ball positions,
            // live gameplay/animations are paused (see update()).
            gameBoard.drawReplayFrame(canvas, replayFrameIndex);
            canvas.drawRect(blackRect, blackPaint);
            drawStatsFooter(canvas);
            canvas.drawText("REPLAY  SLOW x" + REPLAY_SLOW_MOTION_FACTOR, 50, 1700, debugPaint);
        } else if (showingStats) {
            drawStatsScreen(canvas);
        } else {
            // draw game board
            gameBoard.draw(canvas);
            canvas.drawRect(blackRect, blackPaint);

            drawBonusRow(canvas);
            drawStatsFooter(canvas);

            // animations
            synchronized (ongoingAnimations) {
                ongoingAnimations.forEach((a) -> {
                    a.draw(canvas);
                });
            }

            if (debugMode) {
                canvas.drawText("DEBUG ON  SLOW x" + slowMotionFactor, 50, 1700, debugPaint);
            }
        }

        drawMenuButton(canvas);
    }

    public void update() {

        updateTick++;

        if (replaying) {
            replayTickCounter++;
            if (replayTickCounter % REPLAY_SLOW_MOTION_FACTOR == 0) {
                replayFrameIndex++;
                if (gameBoard == null || replayFrameIndex >= gameBoard.getLastMoveFrameCount()) {
                    replaying = false;
                }
            }
            return;
        }

        // Live gameplay/animations are paused while the Statistik screen is up, same as replaying.
        if (showingStats) {
            return;
        }

        boolean runUpdate = (slowMotionFactor <= 1) || (updateTick % slowMotionFactor == 0);
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
        saveState();
        gameLoop.stopLoop();
    }

    // Persists level/score/board so the game can be reconstructed on next launch even after the
    // whole process was killed (e.g. Android reclaiming memory after the app sat unused for a
    // long time). Called from pause(), which the OS guarantees to run before that can happen.
    private void saveState() {
        if (gameBoard == null) return;
        StringBuilder bonusCountsCsv = new StringBuilder();
        for (int i = 0; i < bonusCounts.length; i++) {
            if (i > 0) bonusCountsCsv.append(',');
            bonusCountsCsv.append(bonusCounts[i]);
        }
        prefs.edit()
                .putInt(PREFS_KEY_SAVED_LEVEL, level)
                .putInt(PREFS_KEY_SAVED_SCORE, score)
                .putString(PREFS_KEY_SAVED_BLOCKS, gameBoard.exportBlocksJson())
                .putString(PREFS_KEY_SAVED_BONUS_COUNTS, bonusCountsCsv.toString())
                .putString(PREFS_KEY_SHOT_HISTOGRAMS, serializeShotHistograms())
                .apply();
    }

    // Format: "<ballsUsed>:<bin>=<count>,<bin>=<count>;<ballsUsed>:...". Both maps are TreeMaps
    // so this (and the dropdown listing) always comes out in a stable, sorted order.
    private String serializeShotHistograms() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, TreeMap<Integer, Integer>> bucket : shotHistograms.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(bucket.getKey()).append(':');
            boolean first = true;
            for (Map.Entry<Integer, Integer> bin : bucket.getValue().entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(bin.getKey()).append('=').append(bin.getValue());
            }
        }
        return sb.toString();
    }

    private void loadShotHistograms(String csv) {
        shotHistograms.clear();
        if (csv == null || csv.isEmpty()) return;
        for (String bucketPart : csv.split(";")) {
            String[] bucketSplit = bucketPart.split(":", 2);
            if (bucketSplit.length != 2) continue;
            try {
                int ballsUsed = Integer.parseInt(bucketSplit[0]);
                TreeMap<Integer, Integer> bins = new TreeMap<>();
                for (String binPart : bucketSplit[1].split(",")) {
                    String[] binSplit = binPart.split("=", 2);
                    if (binSplit.length != 2) continue;
                    bins.put(Integer.parseInt(binSplit[0]), Integer.parseInt(binSplit[1]));
                }
                if (!bins.isEmpty()) shotHistograms.put(ballsUsed, bins);
            } catch (NumberFormatException ignored) {
                // skip malformed entry
            }
        }
    }

    // Tallies one finished shot into the histogram for its ballsUsed bucket (see onRoundEnd()).
    private void recordShotStatistics(int ballsUsed, int blocksCleared) {
        int bin = blocksCleared / SHOT_HISTOGRAM_BIN_SIZE;
        shotHistograms.computeIfAbsent(ballsUsed, k -> new TreeMap<>()).merge(bin, 1, Integer::sum);
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

    public void addScore(int points) {
        score += points;
        if (!cheatsUsed && score > bestScore) {
            bestScore = score;
            prefs.edit().putInt(PREFS_KEY_BEST_SCORE, bestScore).apply();
        }
    }

    // Called by GameBoard once a move (all balls back at rest) ends. Tallies the shot into the
    // statistics histogram, then awards a random bonus if it cleared BLOCKS_CLEARED_BONUS_THRESHOLD
    // blocks -- unless this same shot already spent a bonus (see consumeArmedBonuses()), so bonus
    // shots can't chain into more bonuses.
    @Override
    public void onRoundEnd(int blocksCleared, int ballsUsed) {
        recordShotStatistics(ballsUsed, blocksCleared);
        boolean bonusSpentThisShot = bonusUsedThisShot;
        bonusUsedThisShot = false;
        if (!bonusSpentThisShot && blocksCleared >= BLOCKS_CLEARED_BONUS_THRESHOLD) {
            Bonus awarded = Bonus.values()[bonusRandom.nextInt(Bonus.values().length)];
            bonusCounts[awarded.ordinal()]++;
        }
    }

    // GameBoard reads this to apply EXTENDED_PATH/MOVE_START_POINT continuously while armed
    // (aim preview length, fire position), without spending them.
    @Override
    public boolean isBonusArmed(Bonus bonus) {
        return armedBonuses.contains(bonus);
    }

    // Called by GameBoard right as a shot launches. Spends every currently armed bonus (stacking
    // is allowed -- see armedBonuses) and returns them so GameBoard can apply each one's one-off
    // effect (see GameBoard.applyConsumedBonus()).
    @Override
    public List<Bonus> consumeArmedBonuses() {
        if (armedBonuses.isEmpty()) return Collections.emptyList();
        List<Bonus> consumed = new ArrayList<>(armedBonuses);
        for (Bonus bonus : consumed) {
            int idx = bonus.ordinal();
            if (bonusCounts[idx] > 0) bonusCounts[idx]--;
        }
        armedBonuses.clear();
        bonusUsedThisShot = true;
        return consumed;
    }

    // Tapping a bonus button toggles it in/out of armedBonuses -- several can be stacked onto the
    // same shot at once (e.g. EXTRA_BALLS + MOVE_STOPPER in a critical situation), except
    // LINE_DELETE which fires immediately instead of being armed.
    private void toggleArmedBonus(Bonus bonus) {
        if (bonus == Bonus.LINE_DELETE) {
            if (bonusCounts[bonus.ordinal()] <= 0) return;
            spendLineDeleteBonus();
            return;
        }
        if (armedBonuses.contains(bonus)) {
            armedBonuses.remove(bonus);
        } else if (bonusCounts[bonus.ordinal()] > 0) {
            armedBonuses.add(bonus);
        }
    }

    // LINE_DELETE bonus: unlike the others, it isn't armed for a later shot -- tapping its button
    // spends it and applies the effect immediately (see GameBoard.triggerLineDeleteBonus()), so it
    // only works between shots while the board is idle.
    private void spendLineDeleteBonus() {
        if (gameBoard == null || gameBoard.ballRolling() || gameBoard.isFrozen()) return;
        bonusCounts[Bonus.LINE_DELETE.ordinal()]--;
        gameBoard.triggerLineDeleteBonus();
    }

    private StatBox getStatBoxHit(float x, float y) {
        StatBox[] boxes = StatBox.values();
        for (int i = 0; i < boxes.length; i++) {
            if (getStatBoxRect(i).contains(x, y)) {
                return boxes[i];
            }
        }
        return null;
    }

    // Testing cheats -- see the statBoxTapSequence field comment for the two recognized
    // sequences.
    private void onStatBoxTapped(StatBox box) {
        long now = System.currentTimeMillis();
        if (now - lastStatBoxTapTime > CHEAT_TAP_WINDOW_MS) {
            statBoxTapSequence.clear();
        }
        lastStatBoxTapTime = now;

        statBoxTapSequence.add(box);
        while (statBoxTapSequence.size() > 4) {
            statBoxTapSequence.remove(0);
        }

        if (statBoxTapSequence.equals(Arrays.asList(StatBox.BEST, StatBox.BEST, StatBox.BEST, StatBox.BEST))) {
            cheatResetHighscore();
            statBoxTapSequence.clear();
        } else if (statBoxTapSequence.equals(Arrays.asList(StatBox.LEVEL, StatBox.SCORE, StatBox.BEST, StatBox.LEVEL))) {
            cheatGrantAllBonuses();
            statBoxTapSequence.clear();
        }
    }

    private void cheatResetHighscore() {
        bestScore = 0;
        prefs.edit().putInt(PREFS_KEY_BEST_SCORE, 0).apply();
        Toast.makeText(getContext(), "Cheat: Highscore zurückgesetzt", Toast.LENGTH_SHORT).show();
    }

    private void cheatGrantAllBonuses() {
        cheatsUsed = true;
        for (int i = 0; i < bonusCounts.length; i++) {
            bonusCounts[i]++;
        }
        Toast.makeText(getContext(), "Cheat: je 1x Bonus erhalten (Highscore pausiert)", Toast.LENGTH_SHORT).show();
    }

    // Overlay burger-menu button, top-right corner: replay last move, toggle debug mode, restart.
    private RectF getMenuButtonRect() {
        float right = canvasWidth - MENU_BUTTON_MARGIN;
        float left = right - MENU_BUTTON_SIZE;
        float top = MENU_BUTTON_MARGIN;
        float bottom = top + MENU_BUTTON_SIZE;
        return new RectF(left, top, right, bottom);
    }

    private boolean isMenuButtonHit(float x, float y) {
        return getMenuButtonRect().contains(x, y);
    }

    private void drawMenuButton(Canvas canvas) {
        RectF rect = getMenuButtonRect();
        canvas.drawRoundRect(rect, 16, 16, menuButtonPaint);

        float cx = rect.centerX();
        float barHalfWidth = rect.width() * 0.28f;
        float top = rect.top + rect.height() * 0.3f;
        float mid = rect.centerY();
        float bottom = rect.bottom - rect.height() * 0.3f;
        canvas.drawLine(cx - barHalfWidth, top, cx + barHalfWidth, top, menuIconPaint);
        canvas.drawLine(cx - barHalfWidth, mid, cx + barHalfWidth, mid, menuIconPaint);
        canvas.drawLine(cx - barHalfWidth, bottom, cx + barHalfWidth, bottom, menuIconPaint);
    }

    private void showBurgerMenu() {
        List<String> items = new ArrayList<>();
        items.add("Replay in Zeitlupe (letzter Zug)");
        items.add(debugMode ? "Debug-Modus deaktivieren" : "Debug-Modus aktivieren");
        items.add("Spiel neu starten");
        items.add("Statistik");
        items.add("Letzten Zug exportieren (Debug)");
        if (level > 1) {
            items.add("Zu einem früheren Level zurückkehren");
        }
        new AlertDialog.Builder(getContext())
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startReplay();
                            break;
                        case 1:
                            toggleDebugMode();
                            break;
                        case 2:
                            restartLevel();
                            break;
                        case 3:
                            showStatsScreen();
                            break;
                        case 4:
                            exportLastMoveReport();
                            break;
                        case 5:
                            showLevelPicker();
                            break;
                    }
                })
                .show();
    }

    // Opens the Statistik screen (burger menu), defaulting to the smallest ballsUsed bucket with
    // data if none is selected yet (or the previous selection no longer has any data).
    private void showStatsScreen() {
        if (statsSelectedBallsUsed == null || !shotHistograms.containsKey(statsSelectedBallsUsed)) {
            statsSelectedBallsUsed = shotHistograms.isEmpty() ? null : shotHistograms.firstKey();
        }
        showingStats = true;
    }

    // Dropdown-style tap target above the histogram showing the currently selected ballsUsed
    // bucket; tapping it opens showStatsBallsPicker() (see onTouchEvent()).
    private RectF getStatsDropdownRect() {
        float width = 320f;
        float left = (canvasWidth - width) / 2f;
        float top = 220f;
        return new RectF(left, top, left + width, top + 80f);
    }

    private void showStatsBallsPicker() {
        Integer[] keys = shotHistograms.keySet().toArray(new Integer[0]);
        String[] items = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            items[i] = keys[i] + " Bälle";
        }
        new AlertDialog.Builder(getContext())
                .setTitle("Bälle pro Schuss")
                .setItems(items, (dialog, which) -> statsSelectedBallsUsed = keys[which])
                .show();
    }

    // Histogram of blocks cleared per shot (see recordShotStatistics()), for the currently
    // selected ballsUsed bucket. X-axis: blocks cleared, in SHOT_HISTOGRAM_BIN_SIZE-wide bins.
    // Y-axis: how many shots landed in that bin. A vertical line marks
    // BLOCKS_CLEARED_BONUS_THRESHOLD, the point from which a shot earns a bonus.
    private void drawStatsScreen(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        canvas.drawText("Steine pro Schuss", 50, 150, statsTitlePaint);

        RectF dropdown = getStatsDropdownRect();
        canvas.drawRoundRect(dropdown, 16, 16, statsDropdownPaint);
        String dropdownText = statsSelectedBallsUsed == null
                ? "-"
                : statsSelectedBallsUsed + " Bälle" + (shotHistograms.size() > 1 ? " ▾" : "");
        canvas.drawText(dropdownText, dropdown.centerX(), dropdown.centerY() + 12, statsDropdownTextPaint);

        if (statsSelectedBallsUsed == null) {
            canvas.drawText("Noch keine Daten vorhanden.", canvasWidth / 2f, canvasHeight / 2f, statsEmptyTextPaint);
            return;
        }

        TreeMap<Integer, Integer> bins = shotHistograms.get(statsSelectedBallsUsed);
        int thresholdBin = BLOCKS_CLEARED_BONUS_THRESHOLD / SHOT_HISTOGRAM_BIN_SIZE;
        int maxBinIndex = Math.max(bins.isEmpty() ? 0 : bins.lastKey(), thresholdBin);
        int binCount = maxBinIndex + 2;
        int maxCount = 1;
        for (int count : bins.values()) {
            maxCount = Math.max(maxCount, count);
        }

        float chartLeft = 80f;
        float chartRight = canvasWidth - 80f;
        float chartTop = 380f;
        float chartBottom = canvasHeight - 200f;
        float chartHeight = chartBottom - chartTop;
        float barSlotWidth = (chartRight - chartLeft) / binCount;
        float barGap = barSlotWidth * 0.15f;

        for (int bin = 0; bin < binCount; bin++) {
            int count = bins.getOrDefault(bin, 0);
            float barLeft = chartLeft + bin * barSlotWidth;
            RectF barRect = new RectF(barLeft, chartBottom, barLeft + barSlotWidth - barGap, chartBottom);
            if (count > 0) {
                float barHeight = (count / (float) maxCount) * chartHeight;
                barRect.top = chartBottom - barHeight;
                canvas.drawRect(barRect, statsBarPaint);
                canvas.drawText(String.valueOf(count), barRect.centerX(), barRect.top - 12, statsBarCountPaint);
            }
            String label = (bin * SHOT_HISTOGRAM_BIN_SIZE) + "-" + (bin * SHOT_HISTOGRAM_BIN_SIZE + SHOT_HISTOGRAM_BIN_SIZE - 1);
            canvas.drawText(label, barLeft + (barSlotWidth - barGap) / 2f, chartBottom + 40, statsBarLabelPaint);
        }

        float thresholdX = chartLeft + thresholdBin * barSlotWidth;
        canvas.drawLine(thresholdX, chartTop, thresholdX, chartBottom, statsThresholdPaint);
        canvas.drawText("Bonus ab " + BLOCKS_CLEARED_BONUS_THRESHOLD, thresholdX + 10, chartTop - 10, statsThresholdTextPaint);
    }

    // Lists every level below the current one so the player can jump back to it. Levels are
    // predefined layouts under assets/levels/ (see loadLevelJson) plus a random fallback for any
    // level without a file, so every level number below the current one is always choosable.
    private void showLevelPicker() {
        String[] levelItems = new String[level - 1];
        for (int i = 0; i < levelItems.length; i++) {
            levelItems[i] = "Level " + (i + 1);
        }
        new AlertDialog.Builder(getContext())
                .setTitle("Zu welchem Level zurückkehren?")
                .setItems(levelItems, (dialog, which) -> goToLevel(which + 1))
                .show();
    }

    // Jumps straight to an earlier level's board. Counts as a cheat (like the LEVEL/SCORE/BEST tap
    // sequences below) so replaying an easier level can't inflate the highscore.
    private void goToLevel(int newLevel) {
        level = newLevel;
        gameOver = false;
        cheatsUsed = true;
        if (gameBoard != null) {
            gameBoard.initBoard();
        }
    }

    private void startReplay() {
        if (gameBoard == null || !gameBoard.hasLastMoveRecording()) {
            Toast.makeText(getContext(), "Kein letzter Zug zum Wiederholen vorhanden.", Toast.LENGTH_SHORT).show();
            return;
        }
        replaying = true;
        replayFrameIndex = 0;
        replayTickCounter = 0;
    }

    private void toggleDebugMode() {
        debugMode = !debugMode;
        slowMotionFactor = debugMode ? DEBUG_SLOW_MOTION_FACTOR : 1;
        if (gameBoard != null) {
            gameBoard.setDebugSupport(debugMode);
        }
    }

    // Rebuilds the current level's board, keeping score/level as they are (see the burger menu).
    private void restartLevel() {
        gameOver = false;
        resetBonuses();
        if (gameBoard != null) {
            gameBoard.initBoard();
        }
    }

    private void resetBonuses() {
        armedBonuses.clear();
        Arrays.fill(bonusCounts, 0);
    }

    private void cancelBonusLongPress() {
        if (bonusLongPressRunnable != null) {
            bonusLongPressHandler.removeCallbacks(bonusLongPressRunnable);
            bonusLongPressRunnable = null;
        }
    }

    // Long-pressing a bonus button shows what it does instead of arming/disarming it, so the
    // player can check a bonus's effect (even one they don't have yet) without spending it.
    private void showBonusExplanationDialog(Bonus bonus) {
        new AlertDialog.Builder(getContext())
                .setTitle(bonusTitle(bonus))
                .setMessage(bonusDescription(bonus))
                .setPositiveButton("OK", null)
                .show();
    }

    private String bonusTitle(Bonus bonus) {
        switch (bonus) {
            case MOVE_STOPPER: return "Move Stopper";
            case EXTENDED_PATH: return "Extended Path Indicator";
            case LINE_DELETE: return "Line Delete";
            case EXTRA_BALLS: return "Extra viele Bälle";
            case MOVE_START_POINT: return "Startpunkt verschieben";
        }
        return bonus.name();
    }

    private String bonusDescription(Bonus bonus) {
        switch (bonus) {
            case MOVE_STOPPER:
                return "Das automatische Runterschieben des Spielfelds setzt einmal aus.";
            case EXTENDED_PATH:
                return "Die Vorschau-Ziellinie wird länger, um weiter oben liegende Bereiche besser anpeilen zu können.";
            case LINE_DELETE:
                return "Wird sofort beim Antippen ausgeführt: eine der drei wertvollsten Reihen wird zufällig gelöscht, die darunterliegenden Reihen rutschen nach oben.";
            case EXTRA_BALLS:
                return "Der nächste Schuss wird mit deutlich mehr Bällen (20) abgefeuert.";
            case MOVE_START_POINT:
                return "Du kannst frei bestimmen, von wo aus der nächste Ball abgeschossen wird.";
        }
        return "";
    }

    // Predefined level layouts live in assets/levels/level<N>.json (see tools/level_editor.py).
    // Returns null -- meaning "no predefined layout, generate one randomly" -- if the level has
    // no file, keeping levels beyond the last authored one playable.
    public String loadLevelJson(int level) {
        String path = "levels/level" + level + ".json";
        try (InputStream is = getContext().getAssets().open(path)) {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    // 2048-style stat boxes (LEVEL / SCORE / BEST), drawn below the bonus row in the reserved
    // footer area -- the aim line always points up into the board (see
    // GameBoard.clampAimVector), so it never reaches down here regardless of drag distance.
    private void drawStatsFooter(Canvas canvas) {
        RectF levelBox = getStatBoxRect(0);
        RectF scoreBox = getStatBoxRect(1);
        RectF bestBox = getStatBoxRect(2);

        drawStatBox(canvas, levelBox, "LEVEL", String.valueOf(level));
        drawStatBox(canvas, scoreBox, "SCORE", String.valueOf(score));
        drawStatBox(canvas, bestBox, "BEST", String.valueOf(bestScore));
    }

    // index: 0=LEVEL, 1=SCORE, 2=BEST.
    private RectF getStatBoxRect(int index) {
        int boxTop = bonusRowBottom() + BONUS_ROW_BOTTOM_MARGIN;
        int boxBottom = boxTop + STATS_BOX_HEIGHT;
        int gap = 15;
        int boxWidth = (canvasWidth - LEFT_BORDER - RIGHT_BORDER - 2 * gap) / 3;
        int left = LEFT_BORDER + index * (boxWidth + gap);
        return new RectF(left, boxTop, left + boxWidth, boxBottom);
    }

    private void drawStatBox(Canvas canvas, RectF box, String label, String value) {
        canvas.drawRoundRect(box, 20, 20, statBoxPaint);

        float centerX = box.centerX();
        canvas.drawText(label, centerX, box.top + 45, statLabelPaint);
        canvas.drawText(value, centerX, box.bottom - 30, statValuePaint);
    }

    private int bonusRowTop() {
        int boardBottom = TOP_BORDER + (canvasHeight - TOP_BORDER - BOTTOM_BORDER);
        return boardBottom + BONUS_ROW_TOP_MARGIN;
    }

    private int bonusRowBottom() {
        return bonusRowTop() + BONUS_ROW_HEIGHT;
    }

    private RectF getBonusButtonRect(int index) {
        int n = Bonus.values().length;
        int gap = 15;
        int btnWidth = (canvasWidth - LEFT_BORDER - RIGHT_BORDER - (n - 1) * gap) / n;
        int left = LEFT_BORDER + index * (btnWidth + gap);
        return new RectF(left, bonusRowTop(), left + btnWidth, bonusRowBottom());
    }

    private Bonus getBonusButtonHit(float x, float y) {
        Bonus[] bonuses = Bonus.values();
        for (int i = 0; i < bonuses.length; i++) {
            if (getBonusButtonRect(i).contains(x, y)) {
                return bonuses[i];
            }
        }
        return null;
    }

    // Row of bonus buttons between the board's fire line and the LEVEL/SCORE/BEST boxes. Gray
    // when the player holds none of that bonus, colored once they have at least one; a red badge
    // with a white count sits on the top-right corner when count > 0. The currently armed bonus
    // (see toggleArmedBonus()) gets a highlighted border. Bonus effects themselves aren't wired
    // up yet -- this only tracks acquisition and the "armed for next shot" selection.
    private void drawBonusRow(Canvas canvas) {
        Bonus[] bonuses = Bonus.values();
        for (Bonus bonus : bonuses) {
            RectF box = getBonusButtonRect(bonus.ordinal());
            int count = bonusCounts[bonus.ordinal()];
            Paint background = count > 0 ? bonusColorPaints[bonus.ordinal()] : bonusGrayPaint;
            canvas.drawRoundRect(box, 20, 20, background);
            if (armedBonuses.contains(bonus)) {
                canvas.drawRoundRect(box, 20, 20, bonusArmedBorderPaint);
            }
            drawBonusIcon(canvas, bonus, box);
            if (count > 0) {
                float badgeRadius = 24f;
                float badgeCx = box.right - badgeRadius * 0.6f;
                float badgeCy = box.top + badgeRadius * 0.6f;
                canvas.drawCircle(badgeCx, badgeCy, badgeRadius, bonusBadgePaint);
                canvas.drawText(String.valueOf(count), badgeCx, badgeCy + 10, bonusBadgeTextPaint);
            }
        }
    }

    // Simple hand-drawn glyphs (matching this project's all-vector art style) so each bonus type
    // is visually distinguishable without needing icon assets.
    private void drawBonusIcon(Canvas canvas, Bonus bonus, RectF box) {
        float cx = box.centerX();
        float cy = box.centerY();
        float r = Math.min(box.width(), box.height()) * 0.28f;
        switch (bonus) {
            case MOVE_STOPPER:
                // pause icon: two vertical bars
                canvas.drawLine(cx - r * 0.5f, cy - r, cx - r * 0.5f, cy + r, bonusIconPaint);
                canvas.drawLine(cx + r * 0.5f, cy - r, cx + r * 0.5f, cy + r, bonusIconPaint);
                break;
            case EXTENDED_PATH:
                // upward arrow: longer aim line
                canvas.drawLine(cx, cy + r, cx, cy - r, bonusIconPaint);
                canvas.drawLine(cx, cy - r, cx - r * 0.5f, cy - r * 0.4f, bonusIconPaint);
                canvas.drawLine(cx, cy - r, cx + r * 0.5f, cy - r * 0.4f, bonusIconPaint);
                break;
            case LINE_DELETE:
                // horizontal bar struck through
                canvas.drawLine(cx - r, cy, cx + r, cy, bonusIconPaint);
                canvas.drawLine(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f, bonusIconPaint);
                canvas.drawLine(cx - r * 0.6f, cy + r * 0.6f, cx + r * 0.6f, cy - r * 0.6f, bonusIconPaint);
                break;
            case EXTRA_BALLS:
                // cluster of three small balls
                float ballR = r * 0.35f;
                canvas.drawCircle(cx - r * 0.5f, cy + ballR * 0.3f, ballR, bonusIconPaint);
                canvas.drawCircle(cx + r * 0.5f, cy + ballR * 0.3f, ballR, bonusIconPaint);
                canvas.drawCircle(cx, cy - r * 0.5f, ballR, bonusIconPaint);
                break;
            case MOVE_START_POINT:
                // horizontal double-headed arrow: fire position can be moved freely
                canvas.drawLine(cx - r, cy, cx + r, cy, bonusIconPaint);
                canvas.drawLine(cx - r, cy, cx - r * 0.5f, cy - r * 0.4f, bonusIconPaint);
                canvas.drawLine(cx - r, cy, cx - r * 0.5f, cy + r * 0.4f, bonusIconPaint);
                canvas.drawLine(cx + r, cy, cx + r * 0.5f, cy - r * 0.4f, bonusIconPaint);
                canvas.drawLine(cx + r, cy, cx + r * 0.5f, cy + r * 0.4f, bonusIconPaint);
                break;
        }
    }
}
