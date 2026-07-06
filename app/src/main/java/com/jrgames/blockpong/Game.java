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
import java.util.List;
import java.util.Random;


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
    // A move (one shot until all balls are back at rest) earns a random bonus once its score
    // clears this threshold. Fixed and un-tiered for now -- revisit once the bonus effects
    // themselves (see Bonus.java) are wired up and their real-world impact is known.
    // Playtesting showed even ordinary rounds (many balls, several block hits each) routinely
    // scoring 400-600, so the threshold needs to sit well above that for a bonus to feel special.
    private static final int ROUND_SCORE_BONUS_THRESHOLD = 3000;
    private static final String PREFS_KEY_BEST_SCORE = "best_score";
    // Snapshot of an in-progress game, written on pause() and restored on the next cold start so
    // the app can pick up where it left off even if Android killed the process to reclaim memory
    // while it sat in the background unused.
    private static final String PREFS_KEY_SAVED_LEVEL = "saved_level";
    private static final String PREFS_KEY_SAVED_SCORE = "saved_score";
    private static final String PREFS_KEY_SAVED_BLOCKS = "saved_blocks";
    private static final String PREFS_KEY_SAVED_BONUS_COUNTS = "saved_bonus_counts";
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
    private int score;
    private int bestScore;
    // Score accumulated during the move currently in progress; compared against
    // ROUND_SCORE_BONUS_THRESHOLD and reset in onRoundEnd().
    private int roundScore;
    private final int[] bonusCounts = new int[Bonus.values().length];
    // The bonus the player armed via the bonus row, to be spent on the next shot (see
    // consumeArmedBonus()). Null if none armed.
    private Bonus armedBonus;
    private final Random bonusRandom = new Random();
    // Tracks a press-and-hold on a bonus button so ACTION_UP can tell a long press (show the
    // explanation dialog) apart from a normal tap (arm/disarm the bonus).
    private Bonus bonusTouchDownBonus;
    private long bonusTouchDownTime;

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
                // Check if a bonus button was hit -- arms/disarms it for the next shot instead of
                // being forwarded to the board as an aim release. A press-and-hold on the same
                // button instead shows its explanation dialog.
                Bonus tappedBonus = getBonusButtonHit(event.getX(), event.getY());
                if (tappedBonus != null) {
                    long heldMs = System.currentTimeMillis() - bonusTouchDownTime;
                    if (tappedBonus == bonusTouchDownBonus && heldMs >= ViewConfiguration.getLongPressTimeout()) {
                        showBonusExplanationDialog(tappedBonus);
                    } else {
                        toggleArmedBonus(tappedBonus);
                    }
                    bonusTouchDownBonus = null;
                    return true;
                }
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
                bonusTouchDownBonus = getBonusButtonHit(event.getX(), event.getY());
                bonusTouchDownTime = System.currentTimeMillis();
                if (gameOver) {
                      gameOver = false;
                      score = 0;
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
                .apply();
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
        roundScore += points;
        if (!cheatsUsed && score > bestScore) {
            bestScore = score;
            prefs.edit().putInt(PREFS_KEY_BEST_SCORE, bestScore).apply();
        }
    }

    // Called by GameBoard once a move (all balls back at rest) ends. Awards a random bonus if
    // this move's score cleared ROUND_SCORE_BONUS_THRESHOLD, then resets the round counter.
    @Override
    public void onRoundEnd() {
        if (roundScore >= ROUND_SCORE_BONUS_THRESHOLD) {
            Bonus awarded = Bonus.values()[bonusRandom.nextInt(Bonus.values().length)];
            bonusCounts[awarded.ordinal()]++;
        }
        roundScore = 0;
    }

    // GameBoard reads this to apply EXTENDED_PATH/MOVE_START_POINT continuously while armed
    // (aim preview length, fire position), without spending them.
    @Override
    public Bonus getArmedBonus() {
        return armedBonus;
    }

    // Called by GameBoard right as a shot launches. Spends the armed bonus, if any, and returns
    // it so GameBoard can apply its one-off effect (see GameBoard.applyConsumedBonus()).
    @Override
    public Bonus consumeArmedBonus() {
        Bonus consumed = armedBonus;
        if (consumed != null) {
            int idx = consumed.ordinal();
            if (bonusCounts[idx] > 0) bonusCounts[idx]--;
            armedBonus = null;
        }
        return consumed;
    }

    private void toggleArmedBonus(Bonus bonus) {
        if (bonusCounts[bonus.ordinal()] <= 0) return;
        armedBonus = (armedBonus == bonus) ? null : bonus;
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
                return "Eine Reihe wird gelöscht, die darunterliegenden Reihen rutschen nach oben.";
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
            if (armedBonus == bonus) {
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
