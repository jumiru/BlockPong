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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Game manages all objects in the game and is responsible for updating all states
 * and renders all objects to the screen
 */
public class Game extends SurfaceView implements SurfaceHolder.Callback, GameBoard.GameCallbacks, LevelEditor.EditorCallbacks {

    private static final int LEFT_BORDER = 10;
    private static final int RIGHT_BORDER = 10;
    private static final int TOP_BORDER = 20;
    private static final int BOTTOM_BORDER = 400;
    // Width of the input-inert strip kept clear along the screen edges (see
    // GameBoard.setTouchDeadZone()) and, on API 29+, the width of the rects handed to
    // setSystemGestureExclusionRects() below -- both address the same problem (Android's
    // back-gesture on the sides and the notification-shade swipe on top stealing an in-progress
    // aim) from two different angles, so they share one constant.
    private static final int EDGE_DEAD_ZONE_DP = 24;
    // Gap between the board's bottom edge (where the ball rests on the fire line) and the bonus
    // button row drawn below it, in the reserved footer area.
    private static final int BONUS_ROW_TOP_MARGIN = 20;
    private static final int BONUS_ROW_HEIGHT = 110;
    // Gap between the bonus row and the LEVEL/SCORE/BEST boxes below it.
    private static final int BONUS_ROW_BOTTOM_MARGIN = 20;
    private static final int STATS_BOX_HEIGHT = 150;
    // A move (one shot until all balls are back at rest) earns random bonuses once it clears
    // enough blocks -- more bonuses at higher tiers (see bonusesForBlocksCleared()), not additive
    // (40 blocks awards 3, not 1+2+3). Also the bonus-tier boundaries drawn on the shot-statistics
    // histogram (see drawStatsScreen()).
    private static final int BLOCKS_CLEARED_BONUS_THRESHOLD = 20;
    private static final int BLOCKS_CLEARED_BONUS_THRESHOLD_2 = 30;
    private static final int BLOCKS_CLEARED_BONUS_THRESHOLD_3 = 40;
    // Multiple bonuses earned by the same shot pop in one after another rather than all at once
    // (see onRoundEnd()) -- simultaneous BonusAwardAnimations would otherwise be perfectly
    // coincident opaque circles, hiding all but the topmost for their whole duration.
    private static final int BONUS_AWARD_STAGGER_TICKS = 45;
    // pickRandomBonus(): a bonus type the player currently holds none of is this many times more
    // likely to be picked than one they already have -- a soft nudge towards variety, not a hard
    // rule (an already-owned bonus can still come up, just less often).
    private static final int UNOWNED_BONUS_WEIGHT_MULTIPLIER = 3;
    // Bin width (in blocks cleared) for the shot-statistics histogram.
    private static final int SHOT_HISTOGRAM_BIN_SIZE = 5;
    // Bin width (in blocks hit, cleared or not) for the hit-statistics histogram. Wider than
    // SHOT_HISTOGRAM_BIN_SIZE: a shot can hit far more blocks than it clears, so this histogram's
    // range runs much higher -- coarser bins keep the label count (and the label-thinning in
    // drawHistogramChart()) more readable.
    private static final int HIT_HISTOGRAM_BIN_SIZE = 10;
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
    // Companion histogram: blocks actually hit per shot (cleared or not), same bucketing --
    // see recordHitStatistics(). Kept separate from PREFS_KEY_SHOT_HISTOGRAMS since a shot can
    // hit far more blocks than it clears (chipping a block's value without destroying it).
    private static final String PREFS_KEY_HIT_HISTOGRAMS = "hit_histograms";
    // Manual save slots (long-press SCORE): independent of the auto-save above -- the player
    // explicitly picks when to save/load, e.g. as a checkpoint before a risky shot. Each slot's
    // prefs keys are "slot_<index>_<field>" (see slotKey()); a missing "level" key means empty.
    private static final int SAVE_SLOT_COUNT = 3;
    private static final String PREFS_KEY_SLOT_PREFIX = "slot_";
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
    // Filled counterpart to bonusIconPaint's stroke -- used for solid icon details (arrowheads,
    // the lightning bolt, ball cluster dots) that read better filled than outlined at this size.
    private Paint bonusIconFillPaint;
    // Thinner counterpart to bonusIconPaint's stroke -- used for EXTENDED_PATH's taper.
    private Paint bonusIconThinPaint;
    private Paint bonusBadgePaint;
    private Paint bonusBadgeTextPaint;
    private Paint statsTitlePaint;
    private Paint statsDropdownPaint;
    private Paint statsDropdownTextPaint;
    private Paint statsBarPaint;
    private Paint statsBarPaint2;
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
    // Duration (in update() ticks, ~60/s -- see GameLoop) of BonusAwardAnimation's pop-in/hold/
    // fade-out celebration.
    private static final int BONUS_AWARD_ANIMATION_DURATION = 100;
    private static final float MENU_BUTTON_SIZE = 90f;
    private static final float MENU_BUTTON_MARGIN = 15f;

    private Paint menuButtonPaint;
    private Paint menuIconPaint;

    // "Replay in slow motion (last move)" playback state, driven by the recording in GameBoard.
    private boolean replaying;
    private int replayFrameIndex;
    private int replayTickCounter;

    // "Import & Replay (Debug)" dialog state: the pasted text survives closing/reopening the
    // dialog (so the same board+shot can be tried again -- e.g. normal speed, then slow motion,
    // then again after a rebuild -- without retyping it each time), and pendingAutoReplay* lets
    // the "Abspielen in Zeitlupe" button detect when *its* shot (not some earlier one) finishes,
    // so it can auto-start the slow-motion replay once it does.
    private String importReplayDraftText = "";
    private boolean pendingAutoReplay;
    private int pendingAutoReplaySeq;

    // "Level-Editor" screen (burger menu): full-screen takeover like showingStats/replaying below,
    // see draw()/update()/handleTouchEvent(). null until first opened.
    private boolean levelEditorActive;
    private LevelEditor levelEditor;

    // "Probespielen" (test play a level being edited, see LevelEditor's action row): while active,
    // the editor screen is hidden and gameplay runs completely normally against the editor's
    // in-memory (possibly unsaved) layout instead of any real level file -- see loadLevelJson()'s
    // short-circuit and startTestPlay()/endTestPlay(). The player's real progress (level, score,
    // board, bonuses, armed bonuses) is snapshotted into these preTestPlay* fields on entry and
    // restored verbatim on exit, so a test run can never leak into or corrupt it -- including via
    // saveState() (see its own testPlayActive guard), which would otherwise persist the abandoned
    // test board over the real save if the app were backgrounded mid-test.
    private boolean testPlayActive;
    private String testPlayJson;
    private int preTestPlayLevel;
    private int preTestPlayScore;
    private String preTestPlayBlocksJson;
    private boolean preTestPlayGameOver;
    private boolean preTestPlayGameWon;
    private int[] preTestPlayBonusCounts;
    private EnumSet<Bonus> preTestPlayArmedBonuses;

    // "Statistik" screen (burger menu): shows the shot-statistics histogram (see
    // recordShotStatistics()) for one ballsUsed bucket at a time, picked via a dropdown-style tap
    // target (see showStatsBallsPicker()). null once no shot has been recorded yet for any bucket.
    private boolean showingStats;
    private Integer statsSelectedBallsUsed;
    // ballsUsed -> (bin index = blocksCleared / SHOT_HISTOGRAM_BIN_SIZE) -> shot count. Collected
    // across all games, persisted in saveState()/surfaceCreated().
    private final TreeMap<Integer, TreeMap<Integer, Integer>> shotHistograms = new TreeMap<>();
    // Same shape as shotHistograms, but binned by blocks hit (cleared or not) instead of blocks
    // cleared -- see recordHitStatistics(). Always recorded together with shotHistograms (both
    // updated from the same onRoundEnd() call), so it always has the same set of ballsUsed keys;
    // the ballsUsed dropdown/picker only needs to consult shotHistograms.
    private final TreeMap<Integer, TreeMap<Integer, Integer>> hitHistograms = new TreeMap<>();

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

    // Same press-and-hold pattern as the bonus buttons above, but for the SCORE stat box: holding
    // it opens the manual save-slot menu (showSaveSlotMenu()) instead of waiting for ACTION_UP;
    // scoreLongPressTriggered then tells ACTION_UP to suppress the normal cheat-sequence tap.
    private final Handler statBoxLongPressHandler = new Handler(Looper.getMainLooper());
    private Runnable statBoxLongPressRunnable;
    private boolean scoreLongPressTriggered;

    // Testing cheats via tap sequences on the LEVEL/SCORE/BEST stat boxes (see onStatBoxTapped()):
    // tapping BEST four times in a row resets the highscore; tapping LEVEL, SCORE, BEST, LEVEL in
    // that order grants one of every bonus. Once a cheat is used, the highscore no longer updates
    // for the rest of the session so cheated runs can't taint it.
    // Declaration order matches the on-screen left-to-right order (SCORE, LEVEL, BEST) --
    // getStatBoxHit() maps screen position to constant via StatBox.values()[index].
    private enum StatBox { SCORE, LEVEL, BEST }
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

            float density = getResources().getDisplayMetrics().density;
            float deadZonePx = EDGE_DEAD_ZONE_DP * density;
            gameBoard.setTouchDeadZone(deadZonePx, canvasWidth - deadZonePx, deadZonePx);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Tell the system not to steal the edge back-gesture along the sides of the play
                // area -- aiming can legitimately start close to the screen edge, which otherwise
                // overlaps the back-gesture zone on gesture-nav devices. No equivalent API exists
                // for the top edge (notification-shade swipe); that's mitigated separately via
                // immersive sticky mode in MainActivity.
                int exclusionWidthPx = Math.round(deadZonePx);
                List<Rect> exclusionRects = new ArrayList<>();
                exclusionRects.add(new Rect(0, 0, exclusionWidthPx, canvasHeight));
                exclusionRects.add(new Rect(canvasWidth - exclusionWidthPx, 0, canvasWidth, canvasHeight));
                setSystemGestureExclusionRects(exclusionRects);
            }

            // initBoard() (called from the GameBoard constructor above) just generated a fresh
            // layout for `level`; overwrite it with the exact saved layout, if any, so partially
            // cleared blocks aren't lost.
            String savedBlocks = prefs.getString(PREFS_KEY_SAVED_BLOCKS, null);
            if (savedBlocks != null) {
                gameBoard.restoreBlocksFromJson(savedBlocks);
            }

            loadBonusCounts(prefs.getString(PREFS_KEY_SAVED_BONUS_COUNTS, null));

            loadHistogram(prefs.getString(PREFS_KEY_SHOT_HISTOGRAMS, null), shotHistograms);
            loadHistogram(prefs.getString(PREFS_KEY_HIT_HISTOGRAMS, null), hitHistograms);

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
                    Color.rgb(0, 188, 212),  // DRAG_PADDLE - cyan
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
            bonusIconPaint.setStrokeCap(Paint.Cap.ROUND);
            bonusIconPaint.setStrokeJoin(Paint.Join.ROUND);
            bonusIconPaint.setAntiAlias(true);

            bonusIconFillPaint = new Paint();
            bonusIconFillPaint.setColor(Color.WHITE);
            bonusIconFillPaint.setStyle(Paint.Style.FILL);
            bonusIconFillPaint.setAntiAlias(true);

            bonusIconThinPaint = new Paint(bonusIconPaint);
            bonusIconThinPaint.setStrokeWidth(bonusIconPaint.getStrokeWidth() * 0.4f);

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

            statsBarPaint2 = new Paint();
            statsBarPaint2.setColor(Color.rgb(76, 175, 80));

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


        // surfaceDestroyed() below stops any previous loop before this runs again, but guard here
        // too in case some code path ever calls surfaceCreated() twice without an intervening
        // destroy -- starting a second GameLoop on top of a live one would have both threads
        // calling update()/draw() every frame (guarded by the same lock, so not corrupting state,
        // but silently doubling update speed and duplicating all game-over/round-end side effects).
        if (gameLoop != null) {
            gameLoop.stopLoop();
        }
        gameLoop = new GameLoop(this, holder);
        gameLoop.startLoop();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d("Game()", "surfaceChanged()");
    }

    // Stops the render/update thread before the Surface backing it goes away. Previously a no-op
    // -- the GameLoop thread kept running and calling surfaceHolder.lockCanvas() against a Surface
    // that could be mid-teardown, however that manifests on a given device/API level (a null
    // canvas making Game.draw() throw and silently killing the thread, or just wasted work).
    // Whenever the surface is later recreated (screen rotation, the app coming back to the
    // foreground, or -- as reported -- the soft keyboard opening for the Import & Replay dialog's
    // EditText triggering a window resize), that dead/stale thread left nothing driving
    // GameBoard.update() anymore: the board visibly froze and stayed frozen even though touch
    // input kept being delivered and mutating state that nothing ever rendered or advanced again.
    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d("Game()", "surfaceDestroyed()");
        if (gameLoop != null) {
            gameLoop.stopLoop();
            gameLoop = null;
        }
    }

    // GameLoop runs update()/draw() on its own thread, guarded by synchronized(surfaceHolder)
    // (see GameLoop.run()). onTouchEvent() runs on the UI thread and mutates the exact same
    // GameBoard state (fireSpeedX/Y, nextFireBall, fire, dirLineActive, ball positions) with no
    // lock at all -- so a touch completing a new aim could interleave with an in-progress
    // multi-ball launch mid-sequence, overwriting fireSpeedX/Y partway through: balls already
    // dispatched keep the old direction, the rest pick up the new one (reported bug: "launch angle
    // changes partway through firing the balls"). Synchronizing on the same lock GameLoop uses
    // (getHolder() is the same SurfaceHolder instance passed to it) makes touch handling and
    // update()/draw() mutually exclusive, closing the race.
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        synchronized (getHolder()) {
            return handleTouchEvent(event);
        }
    }

    private boolean handleTouchEvent(MotionEvent event) {

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

        // Level-Editor screen: the menu button is disabled here (see draw()) -- its hit area sits
        // on top of the grid's top-right cell, which made that cell untappable (reported bug).
        // "Schliessen" in the editor's own action row is the way out instead.
        if (levelEditorActive) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                levelEditor.handleTouch(event.getX(), event.getY());
            } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                // "Streifen": lets a finger dragged across several cells paint/erase all of them
                // in one motion instead of tapping each individually (see LevelEditor.handleTouchMove()).
                levelEditor.handleTouchMove(event.getX(), event.getY());
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
                // Speed-up overlay (see GameBoard.shouldOfferSpeedUp()): fast-forward past the
                // current boring stretch instead of forwarding the tap as an aim release.
                if (gameBoard != null && gameBoard.isSpeedUpButtonHit(event.getX(), event.getY())) {
                    gameBoard.fastForwardToNextBlockHit();
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
                // detector instead of being forwarded to the board. A press-and-hold on SCORE
                // instead opens the save-slot menu (already triggered by the pending Runnable
                // below while it was still held, so just suppress the tap here).
                cancelStatBoxLongPress();
                StatBox tappedStatBox = getStatBoxHit(event.getX(), event.getY());
                if (tappedStatBox != null) {
                    if (!(tappedStatBox == StatBox.SCORE && scoreLongPressTriggered)) {
                        onStatBoxTapped(tappedStatBox);
                    }
                    scoreLongPressTriggered = false;
                    return true;
                }
                scoreLongPressTriggered = false;
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
                if (gameBoard != null && gameBoard.isSpeedUpButtonHit(event.getX(), event.getY())) {
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
                cancelStatBoxLongPress();
                scoreLongPressTriggered = false;
                if (getStatBoxHit(event.getX(), event.getY()) == StatBox.SCORE) {
                    statBoxLongPressRunnable = () -> {
                        scoreLongPressTriggered = true;
                        showSaveSlotMenu();
                    };
                    statBoxLongPressHandler.postDelayed(statBoxLongPressRunnable, ViewConfiguration.getLongPressTimeout());
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
            case MotionEvent.ACTION_CANCEL:
                // The OS sends this instead of ACTION_UP when a system gesture steals the touch
                // mid-aim (e.g. pulling down the notification shade to start a screen recording).
                // Without this, dirLineActive stayed stuck true, and the next unrelated tap (often
                // meant for the burger button) was interpreted by touchRelease() as completing that
                // abandoned aim -- firing a shot instead of opening the menu.
                cancelBonusLongPress();
                bonusTouchDownBonus = null;
                bonusLongPressTriggered = false;
                cancelStatBoxLongPress();
                scoreLongPressTriggered = false;
                if (gameBoard != null) {
                    gameBoard.cancelAim();
                }
                return true;
        }

        return super.onTouchEvent(event);
    }

    // Android 12+ shows its own "Copied to clipboard" system overlay (with Copy/Share/etc. actions)
    // right after ClipboardManager.setPrimaryClip() -- a default-gravity Toast lands in that same
    // bottom-center area and covers those actions (reported bug). Toast#setGravity() can't fix
    // this: apps targeting API 30+ (this app targets 35) have their Toast gravity silently ignored
    // by the platform -- confirmed still overlapping after trying that. Drawing our own banner
    // near the top of the board (see MessageBannerAnimation) sidesteps the restriction entirely.
    // Used for every clipboard-copy confirmation in this file instead of a system Toast.
    private static final int CLIPBOARD_TOAST_SHORT_TICKS = 120; // ~2s at 60 updates/s
    private static final int CLIPBOARD_TOAST_LONG_TICKS = 210;  // ~3.5s

    private void showClipboardToast(String message, int duration) {
        if (gameBoard == null) return;
        int ticks = (duration == Toast.LENGTH_LONG) ? CLIPBOARD_TOAST_LONG_TICKS : CLIPBOARD_TOAST_SHORT_TICKS;
        addAnimation(new MessageBannerAnimation(gameBoard, ticks, message));
    }

    private void shareDebugReport() {
        System.out.println("shareDebugReport() called");
        if (gameBoard == null) {
            System.out.println("ERROR: gameBoard is null in shareDebugReport()");
            return;
        }

        String report;
        synchronized (getHolder()) {
            report = gameBoard.getDebugReportForSharing();
        }
        System.out.println("Generated report length: " + report.length());
        logDebugReportToLogcat(report);

        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("BlockPong Debug Report", report));
            System.out.println("Report copied to clipboard");
        }

        showClipboardToast("Debug-Report kopiert. E-Mail-Entwurf wird geoeffnet.", Toast.LENGTH_SHORT);

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
        // Runs from the burger AlertDialog's item-click callback -- a separate UI-thread event,
        // entirely outside onTouchEvent()'s synchronized(getHolder()) block (see there for why
        // that's needed). GameLoop's thread can be mid-way through finishMoveRecording() (which
        // updates lastMoveBlockSnapshot/lastMoveBlockSnapshotAfter) at the exact moment this reads
        // them, so without this same lock the "vor"/"nach" JSON here could come from two different
        // moves, or otherwise not reflect a consistent snapshot pair (reported bug: exported
        // "vor"/"nach dem Zug" boards came back identical for a shot that must have changed the
        // board).
        String report;
        synchronized (getHolder()) {
            report = gameBoard == null ? null : gameBoard.getLastMoveReport();
        }
        if (report == null) {
            Toast.makeText(getContext(), "Kein letzter Zug zum Exportieren vorhanden.", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("BlockPong Letzter Zug", report));
        }
        showClipboardToast("Letzter Zug kopiert.", Toast.LENGTH_SHORT);

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
        } else if (levelEditorActive) {
            levelEditor.draw(canvas);
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
            if (testPlayActive) {
                canvas.drawText("TESTSPIEL - Menue > \"Testspiel beenden\"", 30, 60, debugPaint);
            }
        }

        // Hidden in the Level-Editor -- its hit area overlapped the grid's top-right cell, making
        // that cell untappable (reported bug); "Schliessen" in the editor's own action row is the
        // way out instead (see handleTouchEvent()).
        if (!levelEditorActive) {
            drawMenuButton(canvas);
        }
    }

    public void update() {

        updateTick++;

        if (replaying) {
            replayTickCounter++;
            if (replayTickCounter % REPLAY_SLOW_MOTION_FACTOR == 0) {
                if (gameBoard == null) {
                    replaying = false;
                } else if (replayFrameIndex < gameBoard.getLastMoveFrameCount() - 1) {
                    replayFrameIndex++;
                }
                // else: last frame reached -- hold there instead of auto-clearing "replaying".
                // It must only end via the explicit tap onTouchEvent() already handles (same as
                // skipping it early), otherwise a tap meant to dismiss the finished replay can
                // land just after it auto-clears and get misread as a live aim/fire on the actual
                // board underneath -- launching an unwanted ball (reported bug).
            }
            return;
        }

        // Live gameplay/animations are paused while the Statistik screen is up, same as replaying.
        if (showingStats) {
            return;
        }

        // ...and while the Level-Editor is up.
        if (levelEditorActive) {
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

        // "Abspielen in Zeitlupe" in the Import & Replay dialog: once the shot fired from there
        // (identified by the move-completion count moving past what it was right before firing --
        // not just hasLastMoveRecording(), which could already be true from an earlier, unrelated
        // move) has finished, automatically switch into the existing slow-motion replay viewer.
        if (pendingAutoReplay && gameBoard.getCompletedMoveCount() != pendingAutoReplaySeq) {
            pendingAutoReplay = false;
            startReplay();
        }
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

    // Counterpart to pause() -- MainActivity previously had no onResume() at all, so once
    // onPause() stopped the loop, nothing ever restarted it unless surfaceCreated() happened to
    // fire again on the way back (which it reliably does after the process was killed and
    // relaunched, but NOT after a lighter-weight transition like the system share chooser opened
    // by "Letzten Zug exportieren (Debug)" -- that activity doesn't tear down this one's Surface,
    // so surfaceCreated() never re-fires and the board was left permanently frozen, looking
    // exactly like a hung "Import & Replay", even for actions unrelated to that dialog).
    // gameBoard==null means surfaceCreated() hasn't run even once yet -- nothing to resume; it
    // will start the loop itself once the surface is ready.
    public void resume() {
        if (gameBoard == null) return;
        if (gameLoop != null) {
            gameLoop.stopLoop();
        }
        gameLoop = new GameLoop(this, getHolder());
        gameLoop.startLoop();
    }

    // Persists level/score/board so the game can be reconstructed on next launch even after the
    // whole process was killed (e.g. Android reclaiming memory after the app sat unused for a
    // long time). Called from pause(), which the OS guarantees to run before that can happen.
    private void saveState() {
        if (gameBoard == null) return;
        // Probespielen: never persist an abandoned test board/level/score over the real save --
        // e.g. if the app gets backgrounded (and possibly killed) while test-playing. The real
        // state sitting in the preTestPlay* fields is only ever written back via endTestPlay(),
        // not through here.
        if (testPlayActive) return;
        prefs.edit()
                .putInt(PREFS_KEY_SAVED_LEVEL, level)
                .putInt(PREFS_KEY_SAVED_SCORE, score)
                .putString(PREFS_KEY_SAVED_BLOCKS, gameBoard.exportBlocksJson())
                .putString(PREFS_KEY_SAVED_BONUS_COUNTS, serializeBonusCounts())
                .putString(PREFS_KEY_SHOT_HISTOGRAMS, serializeHistogram(shotHistograms))
                .putString(PREFS_KEY_HIT_HISTOGRAMS, serializeHistogram(hitHistograms))
                .apply();
    }

    // Format: "<ballsUsed>:<bin>=<count>,<bin>=<count>;<ballsUsed>:...". Both maps are TreeMaps
    // so this (and the dropdown listing) always comes out in a stable, sorted order. Shared by
    // both shotHistograms and hitHistograms, which have the same shape.
    private String serializeHistogram(TreeMap<Integer, TreeMap<Integer, Integer>> histogram) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, TreeMap<Integer, Integer>> bucket : histogram.entrySet()) {
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

    private void loadHistogram(String csv, TreeMap<Integer, TreeMap<Integer, Integer>> target) {
        target.clear();
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
                if (!bins.isEmpty()) target.put(ballsUsed, bins);
            } catch (NumberFormatException ignored) {
                // skip malformed entry
            }
        }
    }

    // Tallies one finished shot into the histograms for its ballsUsed bucket (see onRoundEnd()).
    private void recordShotStatistics(int ballsUsed, int blocksCleared, int blocksHit) {
        int clearedBin = blocksCleared / SHOT_HISTOGRAM_BIN_SIZE;
        shotHistograms.computeIfAbsent(ballsUsed, k -> new TreeMap<>()).merge(clearedBin, 1, Integer::sum);
        int hitBin = blocksHit / HIT_HISTOGRAM_BIN_SIZE;
        hitHistograms.computeIfAbsent(ballsUsed, k -> new TreeMap<>()).merge(hitBin, 1, Integer::sum);
    }

    public void setGameOver(boolean win) {
        gameOver = true;
        gameWon = win;
    }

    public void resetGameOver() {
        gameOver = false;
    }

    public void increaselevel() {
        // Probespielen: clearing the whole test board must not advance the player's real level --
        // loadLevelJson() keeps returning the same testPlayJson regardless, so the LevelComplete-
        // Animation's subsequent initBoard() call just reloads the fresh, unplayed test layout,
        // letting the player try it again immediately.
        if (testPlayActive) return;
        level++;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public void addScore(int points) {
        score += points;
        // Probespielen: a good test run must not leak into the real highscore, same as cheatsUsed.
        if (!cheatsUsed && !testPlayActive && score > bestScore) {
            bestScore = score;
            prefs.edit().putInt(PREFS_KEY_BEST_SCORE, bestScore).apply();
        }
    }

    // Called by GameBoard once a move (all balls back at rest) ends. Tallies the shot into the
    // statistics histogram, then awards random bonuses per bonusesForBlocksCleared() -- unless this
    // same shot already spent a bonus (see consumeArmedBonuses()), so bonus shots can't chain into
    // more bonuses.
    @Override
    public void onRoundEnd(int blocksCleared, int ballsUsed) {
        int blocksHit = gameBoard == null ? blocksCleared : gameBoard.getHitsThisMove();
        // Probespielen: test shots aren't representative real play, so they're kept out of the
        // persisted shot/hit histograms (see showStatsScreen()).
        if (!testPlayActive) {
            recordShotStatistics(ballsUsed, blocksCleared, blocksHit);
        }
        boolean bonusSpentThisShot = bonusUsedThisShot;
        bonusUsedThisShot = false;
        if (!bonusSpentThisShot) {
            int bonusesToAward = bonusesForBlocksCleared(blocksCleared);
            for (int i = 0; i < bonusesToAward; i++) {
                Bonus awarded = pickRandomBonus();
                bonusCounts[awarded.ordinal()]++;
                if (gameBoard != null) {
                    addAnimation(new BonusAwardAnimation(gameBoard, BONUS_AWARD_ANIMATION_DURATION,
                            awarded, bonusColorPaints[awarded.ordinal()].getColor(), bonusTitle(awarded),
                            i * BONUS_AWARD_STAGGER_TICKS));
                }
            }
        }
    }

    // Tier lookup for onRoundEnd() -- highest tier the shot reached wins (not additive), see
    // BLOCKS_CLEARED_BONUS_THRESHOLD*'s comment. Also used by drawStatsScreen() to know which
    // boundaries to mark on the shot-statistics histogram.
    private static int bonusesForBlocksCleared(int blocksCleared) {
        if (blocksCleared >= BLOCKS_CLEARED_BONUS_THRESHOLD_3) return 3;
        if (blocksCleared >= BLOCKS_CLEARED_BONUS_THRESHOLD_2) return 2;
        if (blocksCleared >= BLOCKS_CLEARED_BONUS_THRESHOLD) return 1;
        return 0;
    }

    // Picks the bonus type to award (see onRoundEnd()) -- see BonusPicker for the actual weighting
    // (favors whichever types bonusCounts says the player doesn't have yet).
    private Bonus pickRandomBonus() {
        return BonusPicker.pickWeighted(bonusRandom, bonusCounts, UNOWNED_BONUS_WEIGHT_MULTIPLIER);
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

    // The menu button must always win over aiming/firing, even for a touch that lands just
    // outside its drawn bounds (reported requirement: releasing near the button should never
    // launch a ball) -- so hit-testing uses this padded rect rather than the tight drawn one.
    private static final float MENU_BUTTON_HIT_PADDING = 60f;

    private RectF getMenuButtonHitRect() {
        RectF r = getMenuButtonRect();
        r.inset(-MENU_BUTTON_HIT_PADDING, -MENU_BUTTON_HIT_PADDING);
        return r;
    }

    private boolean isMenuButtonHit(float x, float y) {
        return getMenuButtonHitRect().contains(x, y);
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

        // Every item's index is captured explicitly (rather than relied on via hardcoded
        // `case N:` labels) since several items are only conditionally added -- "Testspiel
        // beenden" only while test-playing, "Zu einem frueheren Level"/"Level-Editor" only while
        // NOT test-playing (jumping levels or re-entering the editor mid-test would conflict with
        // the paused real game the test is sitting on top of, see startTestPlay()/endTestPlay()) --
        // so a fixed case number would silently point at the wrong action whenever a conditional
        // item's presence didn't match what the number assumed.
        final int testPlayEndIdx = testPlayActive ? items.size() : -1;
        if (testPlayActive) {
            items.add("Testspiel beenden (zurueck zum Editor)");
        }
        final int replayIdx = items.size();
        items.add("Replay in Zeitlupe (letzter Zug)");
        final int debugIdx = items.size();
        items.add(debugMode ? "Debug-Modus deaktivieren" : "Debug-Modus aktivieren");
        final int restartIdx = items.size();
        items.add("Spiel neu starten");
        final int statsIdx = items.size();
        items.add("Statistik");
        final int exportMoveIdx = items.size();
        items.add("Letzten Zug exportieren (Debug)");
        final int importReplayIdx = items.size();
        items.add("Import & Replay (Debug)...");
        final int levelPickerIdx = (!testPlayActive && level > 1) ? items.size() : -1;
        if (levelPickerIdx != -1) {
            items.add("Zu einem früheren Level zurückkehren");
        }
        final int levelEditorIdx = testPlayActive ? -1 : items.size();
        if (!testPlayActive) {
            items.add("Level-Editor");
        }
        new AlertDialog.Builder(getContext())
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    if (which == testPlayEndIdx) {
                        endTestPlay();
                    } else if (which == replayIdx) {
                        startReplay();
                    } else if (which == debugIdx) {
                        toggleDebugMode();
                    } else if (which == restartIdx) {
                        restartLevel();
                    } else if (which == statsIdx) {
                        showStatsScreen();
                    } else if (which == exportMoveIdx) {
                        exportLastMoveReport();
                    } else if (which == importReplayIdx) {
                        showImportReplayDialog();
                    } else if (which == levelPickerIdx) {
                        showLevelPicker();
                    } else if (which == levelEditorIdx) {
                        showLevelEditorEntry();
                    }
                })
                .show();
    }

    // Burger menu action: pick an existing level to edit, or create a new one (appended after the
    // last known level, or inserted at a chosen position -- see LevelEditor.Mode and
    // insertLevelWithShift()).
    private void showLevelEditorEntry() {
        List<Integer> known = listKnownLevels();
        int maxKnown = known.isEmpty() ? 0 : known.get(known.size() - 1);

        // Rows cover every level 1..maxKnown so the reserved random-level slots (10, 20, 30, ...)
        // show up too, greyed out and unselectable, instead of just silently not being listed --
        // see isRandomLevelSlot().
        List<Integer> rowLevel = new ArrayList<>(); // -1 for the trailing "action" rows
        List<String> labels = new ArrayList<>();
        List<Boolean> rowEnabled = new ArrayList<>();
        for (int lvl = 1; lvl <= maxKnown; lvl++) {
            if (isRandomLevelSlot(lvl)) {
                labels.add("Level " + lvl + " (Zufalls-Level)");
                rowEnabled.add(false);
                rowLevel.add(lvl);
            } else if (known.contains(lvl)) {
                labels.add("Level " + lvl + " bearbeiten");
                rowEnabled.add(true);
                rowLevel.add(lvl);
            }
        }
        final int newLevelIdx = labels.size();
        labels.add("Neues Level erstellen...");
        rowEnabled.add(true);
        rowLevel.add(-1);
        final int exportAllIdx = labels.size();
        labels.add("Alle Level exportieren");
        rowEnabled.add(true);
        rowLevel.add(-1);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(), android.R.layout.simple_list_item_1, labels) {
            @Override
            public boolean isEnabled(int position) {
                return rowEnabled.get(position);
            }

            @NonNull
            @Override
            public android.view.View getView(int position, android.view.View convertView, @NonNull ViewGroup parent) {
                android.view.View v = super.getView(position, convertView, parent);
                TextView tv = (TextView) v;
                tv.setTextColor(rowEnabled.get(position) ? Color.WHITE : Color.GRAY);
                tv.setEnabled(rowEnabled.get(position));
                return v;
            }
        };

        new AlertDialog.Builder(getContext())
                .setTitle("Level-Editor")
                .setAdapter(adapter, (dialog, which) -> {
                    if (!rowEnabled.get(which)) {
                        return; // random-level rows are not clickable, but guard defensively
                    }
                    if (which == newLevelIdx) {
                        showNewLevelDialog(known);
                    } else if (which == exportAllIdx) {
                        exportAllLevels(known);
                    } else {
                        openLevelEditorFor(rowLevel.get(which));
                    }
                })
                .show();
    }

    // "Alle Level exportieren": bundles every known level (assets + Level-Editor overrides, see
    // listKnownLevels()) into one clipboard export -- each section uses the same
    // "LabelN (JSON, kompatibel mit tools/level_editor.py):\n{...}" shape getLastMoveReport()
    // already uses for its "vor"/"nach dem Zug" boards, which tools/level_editor.py's paste-JSON
    // import already knows how to pull multiple {"blocks":...} objects out of in one paste --
    // sparing a level-by-level "Exportieren" click for every override an insert-with-shift may
    // have touched.
    private void exportAllLevels(List<Integer> known) {
        if (known.isEmpty()) {
            Toast.makeText(getContext(), "Keine Level zum Exportieren vorhanden.", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("BlockPong - Alle Level (Export)\n");
        for (int lvl : known) {
            String json = loadLevelJson(lvl);
            if (json == null) continue;
            sb.append("Level ").append(lvl).append(" (JSON, kompatibel mit tools/level_editor.py):\n");
            sb.append(json.trim()).append("\n\n");
        }
        exportJsonToClipboard(sb.toString());
        showClipboardToast(known.size() + " Level in die Zwischenablage kopiert.", Toast.LENGTH_LONG);
    }

    private void showNewLevelDialog(List<Integer> known) {
        int nextAppend = known.isEmpty() ? 1 : nextEditableSlot(known.get(known.size() - 1) + 1);
        String[] options = {"Anhängen (wird Level " + nextAppend + ")", "Einfügen an Position..."};
        new AlertDialog.Builder(getContext())
                .setTitle("Neues Level erstellen")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openLevelEditorNew(nextAppend, false);
                    } else {
                        showInsertPositionDialog(nextAppend);
                    }
                })
                .show();
    }

    // Numeric entry for "insert at position N" -- a plain AlertDialog only offers up to 3 button
    // slots and none of them are text-input-friendly, so this reuses the hand-built
    // EditText+ScrollView+button-bar template already established for showImportReplayDialog().
    private void showInsertPositionDialog(int defaultLevel) {
        EditText input = new EditText(getContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(defaultLevel));

        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle("An welcher Position einfügen?")
                .setView(container)
                .setPositiveButton("Einfügen", null)
                .setNegativeButton("Abbrechen", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int position = Integer.parseInt(input.getText().toString().trim());
                if (position < 1) throw new NumberFormatException();
                if (isRandomLevelSlot(position)) {
                    Toast.makeText(getContext(), "Level " + position + " ist ein Zufalls-Level und kann nicht belegt werden.", Toast.LENGTH_LONG).show();
                    return;
                }
                openLevelEditorNew(position, true);
                dialog.dismiss();
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "Bitte eine gültige Levelnummer (>=1) eingeben.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openLevelEditorFor(int level) {
        if (levelEditor == null) {
            levelEditor = new LevelEditor(gameBoard, this);
        }
        levelEditor.openForEdit(level);
        levelEditorActive = true;
    }

    private void openLevelEditorNew(int level, boolean insert) {
        if (levelEditor == null) {
            levelEditor = new LevelEditor(gameBoard, this);
        }
        levelEditor.openNew(level, insert);
        levelEditorActive = true;
    }

    // "Probespielen" (LevelEditor.EditorCallbacks): leaves the editor screen and plays the editor's
    // current in-memory layout -- saved or not -- as completely normal gameplay, so a level can be
    // tried out before committing to "Speichern"/"Exportieren". The real game state underneath
    // (which was simply paused, untouched, while the editor screen was up on top of it) is
    // snapshotted here and restored by endTestPlay(), the only way back out (burger menu ->
    // "Testspiel beenden").
    @Override
    public void startTestPlay(String json, int targetLevel) {
        if (gameBoard == null) return;
        preTestPlayLevel = level;
        preTestPlayScore = score;
        preTestPlayBlocksJson = gameBoard.exportBlocksJson();
        preTestPlayGameOver = gameOver;
        preTestPlayGameWon = gameWon;
        preTestPlayBonusCounts = bonusCounts.clone();
        preTestPlayArmedBonuses = EnumSet.copyOf(armedBonuses);

        testPlayActive = true;
        testPlayJson = json;
        level = targetLevel;
        gameOver = false;
        armedBonuses.clear();
        levelEditorActive = false;
        gameBoard.initBoard();
        showToast("Testspiel gestartet - Menue > \"Testspiel beenden\" fuehrt zurueck zum Editor.");
    }

    private void endTestPlay() {
        if (!testPlayActive) return;
        testPlayActive = false;
        testPlayJson = null;
        // Discard any animation left over from the test run (e.g. a level-complete curtain or
        // game-over flourish still playing) rather than let it keep running over the restored,
        // unrelated real board.
        synchronized (ongoingAnimations) {
            ongoingAnimations.clear();
        }
        synchronized (newAnimations) {
            newAnimations.clear();
        }

        level = preTestPlayLevel;
        score = preTestPlayScore;
        gameOver = preTestPlayGameOver;
        gameWon = preTestPlayGameWon;
        System.arraycopy(preTestPlayBonusCounts, 0, bonusCounts, 0, bonusCounts.length);
        armedBonuses.clear();
        armedBonuses.addAll(preTestPlayArmedBonuses);

        if (gameBoard != null) {
            // Same two-step pattern as surfaceCreated()'s startup restore: initBoard() first to
            // reset ball/fire-position state cleanly for the restored level (loadLevelJson() now
            // resolves normally again, testPlayActive being false), then overlay the exact
            // block layout the player had paused on.
            gameBoard.initBoard();
            gameBoard.restoreBlocksFromJson(preTestPlayBlocksJson);
        }
        levelEditorActive = true;
    }

    // Burger menu action: pastes a "Letzten Zug exportieren (Debug)" report (or just its board
    // JSON + start x + launch vector) and immediately fires that exact shot live on the real
    // board, bypassing touch input entirely -- so a reported bug's exact repro can be replayed
    // with real physics to check whether a fix actually holds, instead of only watching the
    // frozen recorded "Replay in Zeitlupe" (which can't exercise a code change at all).
    private void showImportReplayDialog() {
        // Defaults to the last completed shot's own report -- replaying the move that was just
        // played is the common case (e.g. right after it looked wrong), so this saves having to
        // export it and paste it back in by hand. Only kicks in with no draft yet (an edited/
        // pasted-from-elsewhere draft from a previous open of this dialog still wins -- see
        // importReplayDraftText's persistence across opens).
        if (importReplayDraftText.isEmpty() && gameBoard != null) {
            String lastMoveReport = gameBoard.getLastMoveReport();
            if (lastMoveReport != null) {
                importReplayDraftText = lastMoveReport;
            }
        }

        EditText input = new EditText(getContext());
        input.setMinLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHint("Debug-Export hier einfuegen...");
        input.setText(importReplayDraftText);

        // A pasted debug report can run to dozens of lines (full board JSON, replay data); a bare
        // EditText grows to fit all of it, which can push the dialog's buttons off the bottom of
        // the screen with no way to reach them (reported bug). Capping the EditText inside a
        // ScrollView keeps the dialog's own height (and its buttons) fixed regardless of paste
        // length -- the text scrolls internally instead.
        float density = getResources().getDisplayMetrics().density;
        int maxHeightPx = Math.round(300 * density);
        int paddingPx = Math.round(8 * density);
        ScrollView scrollContainer = new ScrollView(getContext()) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST));
            }
        };
        scrollContainer.setPadding(paddingPx, 0, paddingPx, 0);
        scrollContainer.addView(input, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // AlertDialog.Builder only offers three built-in button slots (positive/neutral/negative),
        // one short of the four wanted here in a specific order -- so the buttons are laid out by
        // hand below the input instead of via setPositiveButton()/setNeutralButton()/
        // setNegativeButton(), giving full control over both the order and the extra "Loeschen".
        LinearLayout buttonBar = new LinearLayout(getContext());
        buttonBar.setOrientation(LinearLayout.VERTICAL);
        Button playButton = new Button(getContext());
        playButton.setText("Abspielen");
        Button slowMoButton = new Button(getContext());
        slowMoButton.setText("Abspielen in Zeitlupe");
        Button clearButton = new Button(getContext());
        clearButton.setText("Loeschen");
        Button closeButton = new Button(getContext());
        closeButton.setText("Schliessen");
        buttonBar.addView(playButton);
        buttonBar.addView(slowMoButton);
        buttonBar.addView(clearButton);
        buttonBar.addView(closeButton);

        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(scrollContainer);
        container.addView(buttonBar);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle("Import & Replay (Debug)")
                .setView(container)
                .create();
        dialog.show();

        // On success the dialog closes right away so the live board (with the shot now firing) is
        // actually visible instead of staying hidden behind the dialog (reported bug -- the dialog
        // used to never auto-close, so the shot played out unseen); on an invalid paste it stays
        // open so the text can be fixed without retyping it.
        playButton.setOnClickListener(v -> {
            importReplayDraftText = input.getText().toString();
            if (runImportReplay(importReplayDraftText, false)) {
                dialog.dismiss();
            }
        });
        slowMoButton.setOnClickListener(v -> {
            importReplayDraftText = input.getText().toString();
            if (runImportReplay(importReplayDraftText, true)) {
                dialog.dismiss();
            }
        });
        clearButton.setOnClickListener(v -> {
            input.setText("");
            importReplayDraftText = "";
        });
        closeButton.setOnClickListener(v -> dialog.dismiss());
    }

    // Returns true if the shot was actually fired (caller dismisses the dialog on true).
    private boolean runImportReplay(String text, boolean thenShowSlowMotionReplay) {
        int seqBeforeFire = gameBoard == null ? 0 : gameBoard.getCompletedMoveCount();
        String error = importAndReplayDebugText(text);
        if (error != null) {
            Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
            return false;
        }
        if (thenShowSlowMotionReplay) {
            pendingAutoReplay = true;
            pendingAutoReplaySeq = seqBeforeFire;
            Toast.makeText(getContext(), "Zug wird abgespielt, danach Zeitlupen-Replay...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Zug wird abgespielt...", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // Returns null on success, or a user-facing error message (shown as a Toast) on failure.
    private String importAndReplayDebugText(String text) {
        if (gameBoard == null) {
            return "Kein Spielfeld vorhanden.";
        }
        String blocksJson = extractBoardJson(text);
        if (blocksJson == null) {
            return "Kein Spielfeld-JSON im eingefuegten Text gefunden.";
        }
        Float startX = extractFirstNumberAfter(text, "Startpunkt x:");
        float[] launch = extractLaunchVector(text);
        if (startX == null || launch == null) {
            return "Startpunkt x oder Wurfrichtung nicht gefunden -- bitte den kompletten Debug-Export einfuegen.";
        }
        Integer balls = extractFirstIntAfter(text, "Baelle:");
        int ballCount = balls != null ? balls : 10;

        // Same lock GameLoop's thread uses for update()/draw() (see onTouchEvent()) -- this
        // mutates GameBoard state from a menu-dialog callback, exactly like the export actions
        // that turned out to need it too.
        boolean ok;
        synchronized (getHolder()) {
            ok = gameBoard.importAndReplayForDebug(blocksJson, startX, launch[0], launch[1], ballCount);
        }
        if (!ok) {
            // The extracted "blocks" JSON didn't parse (e.g. text mangled by copy/paste through an
            // email client) -- previously this silently fired a shot at an unrelated random board
            // instead, with no indication anything had gone wrong (reported bug).
            return "Spielfeld-JSON ist ungueltig -- bitte den kompletten, unveraenderten Debug-Export einfuegen.";
        }
        gameOver = false;
        return null;
    }

    // Finds the first {"blocks": [...]} JSON object in free-form text (preferring the one after
    // a "vor dem Zug" label, if present, since a full report may contain a "nach dem Zug" one
    // too) via brace counting -- simple but sufficient since this format never nests braces
    // inside string values.
    private String extractBoardJson(String text) {
        int labelIdx = text.indexOf("vor dem Zug");
        int searchFrom = labelIdx >= 0 ? labelIdx : 0;
        int start = text.indexOf('{', searchFrom);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    private Float extractFirstNumberAfter(String text, String label) {
        int idx = text.indexOf(label);
        if (idx < 0) return null;
        Matcher m = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)").matcher(text.substring(idx + label.length()));
        if (!m.find()) return null;
        try {
            return Float.parseFloat(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer extractFirstIntAfter(String text, String label) {
        Float value = extractFirstNumberAfter(text, label);
        return value == null ? null : Math.round(value);
    }

    private float[] extractLaunchVector(String text) {
        int idx = text.indexOf("Wurfrichtung");
        if (idx < 0) return null;
        Matcher m = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)\\s*,\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(text.substring(idx));
        if (!m.find()) return null;
        try {
            return new float[]{Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
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

    // Two histograms for the currently selected ballsUsed bucket, stacked in one screen: blocks
    // actually cleared per shot (see recordShotStatistics(), same data/threshold as before) on
    // top, and blocks merely hit (cleared or not) per shot below it -- the two can differ a lot
    // (a shot can chip many blocks' values without clearing any of them), so seeing both at once
    // is the point.
    private void drawStatsScreen(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        canvas.drawText("Statistik", 50, 150, statsTitlePaint);

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

        float slotsTop = 330f;
        float slotsBottom = canvasHeight - 40f;
        float slotGap = 30f;
        float slotHeight = (slotsBottom - slotsTop - slotGap) / 2f;

        drawHistogramChart(canvas, "Steine entfernt pro Schuss",
                shotHistograms.get(statsSelectedBallsUsed), SHOT_HISTOGRAM_BIN_SIZE,
                slotsTop, slotsTop + slotHeight, statsBarPaint,
                new int[]{BLOCKS_CLEARED_BONUS_THRESHOLD, BLOCKS_CLEARED_BONUS_THRESHOLD_2, BLOCKS_CLEARED_BONUS_THRESHOLD_3},
                new String[]{"1 Bonus ab " + BLOCKS_CLEARED_BONUS_THRESHOLD,
                        "2 Boni ab " + BLOCKS_CLEARED_BONUS_THRESHOLD_2,
                        "3 Boni ab " + BLOCKS_CLEARED_BONUS_THRESHOLD_3});

        drawHistogramChart(canvas, "Treffer pro Schuss",
                hitHistograms.get(statsSelectedBallsUsed), HIT_HISTOGRAM_BIN_SIZE,
                slotsTop + slotHeight + slotGap, slotsBottom, statsBarPaint2,
                null, null);
    }

    // Draws one bar-chart histogram (title, bars, bin labels, optional threshold markers) inside
    // the vertical band [slotTop, slotBottom] -- shared by both charts in drawStatsScreen().
    // X-axis: bin start value, in binSize-wide bins. Y-axis: how many shots landed in that bin.
    // thresholdValues/thresholdLabels are parallel arrays (both null, or both the same length).
    private void drawHistogramChart(Canvas canvas, String title, TreeMap<Integer, Integer> bins,
                                     int binSize, float slotTop, float slotBottom, Paint barPaint,
                                     int[] thresholdValues, String[] thresholdLabels) {
        if (bins == null) bins = new TreeMap<>();
        canvas.drawText(title, 50, slotTop + 30, statsTitlePaint);

        int maxBinIndex = bins.isEmpty() ? 0 : bins.lastKey();
        if (thresholdValues != null) {
            for (int thresholdValue : thresholdValues) {
                maxBinIndex = Math.max(maxBinIndex, thresholdValue / binSize);
            }
        }
        int binCount = maxBinIndex + 2;
        int maxCount = 1;
        for (int count : bins.values()) {
            maxCount = Math.max(maxCount, count);
        }

        float chartLeft = 80f;
        float chartRight = canvasWidth - 80f;
        float chartTop = slotTop + 70f;
        float chartBottom = slotBottom - 60f;
        float chartHeight = chartBottom - chartTop;
        float barSlotWidth = (chartRight - chartLeft) / binCount;
        float barGap = barSlotWidth * 0.15f;

        // A wide-ranging histogram (the hits chart especially: one shot can hit far more blocks
        // than it clears, e.g. bin "35-39") packs many narrow bins into the same chart width --
        // drawing every bin's label then makes neighboring labels overlap. Thin them out to only
        // as many as actually fit, evenly spaced, based on how wide the widest label actually is.
        String widestLabel = ((binCount - 1) * binSize) + "-" + ((binCount - 1) * binSize + binSize - 1);
        float labelWidth = statsBarLabelPaint.measureText(widestLabel);
        int labelStride = Math.max(1, (int) Math.ceil((labelWidth + 16f) / barSlotWidth));

        for (int bin = 0; bin < binCount; bin++) {
            int count = bins.getOrDefault(bin, 0);
            float barLeft = chartLeft + bin * barSlotWidth;
            RectF barRect = new RectF(barLeft, chartBottom, barLeft + barSlotWidth - barGap, chartBottom);
            if (count > 0) {
                float barHeight = (count / (float) maxCount) * chartHeight;
                barRect.top = chartBottom - barHeight;
                canvas.drawRect(barRect, barPaint);
                canvas.drawText(String.valueOf(count), barRect.centerX(), barRect.top - 12, statsBarCountPaint);
            }
            if (bin % labelStride == 0) {
                String label = (bin * binSize) + "-" + (bin * binSize + binSize - 1);
                canvas.drawText(label, barLeft + (barSlotWidth - barGap) / 2f, chartBottom + 40, statsBarLabelPaint);
            }
        }

        if (thresholdValues != null) {
            for (int i = 0; i < thresholdValues.length; i++) {
                int thresholdBin = thresholdValues[i] / binSize;
                float thresholdX = chartLeft + thresholdBin * barSlotWidth;
                canvas.drawLine(thresholdX, chartTop, thresholdX, chartBottom, statsThresholdPaint);
                // Stack labels for adjacent tier lines that would otherwise overlap at chartTop.
                canvas.drawText(thresholdLabels[i], thresholdX + 10, chartTop - 10 - i * 28, statsThresholdTextPaint);
            }
        }
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

    // Rebuilds the current level's board (see the burger menu). Resets score back to 0, but keeps
    // the current level -- bestScore is untouched since it's only ever raised on a new high (see
    // addScore()).
    private void restartLevel() {
        gameOver = false;
        score = 0;
        resetBonuses();
        if (gameBoard != null) {
            gameBoard.initBoard();
        }
    }

    private void resetBonuses() {
        armedBonuses.clear();
        Arrays.fill(bonusCounts, 0);
    }

    // Shared by the auto-save (saveState()/surfaceCreated()) and the manual save slots
    // (saveToSlot()/loadFromSlot()) -- both persist bonusCounts in the same simple CSV format.
    private String serializeBonusCounts() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bonusCounts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(bonusCounts[i]);
        }
        return sb.toString();
    }

    private void loadBonusCounts(String csv) {
        Arrays.fill(bonusCounts, 0);
        if (csv == null) return;
        String[] parts = csv.split(",");
        for (int i = 0; i < bonusCounts.length && i < parts.length; i++) {
            try {
                bonusCounts[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                // leave that slot at 0
            }
        }
    }

    // Manual save slots (long-press SCORE -- see handleTouchEvent()): independent of the
    // auto-save, so the player can explicitly checkpoint a game (e.g. before a risky shot) and
    // come back to it later. Doesn't cover shot/hit histograms or bestScore -- those are
    // lifetime/global stats, not tied to any one saved game.
    private void showSaveSlotMenu() {
        // Mid-shot, balls reference the live board -- swapping it out from under them would be
        // visually broken at best. Same restriction LINE_DELETE's bonus effect already uses.
        if (gameBoard == null || gameBoard.ballRolling()) return;
        String[] items = new String[SAVE_SLOT_COUNT];
        for (int i = 0; i < SAVE_SLOT_COUNT; i++) {
            items[i] = describeSlot(i);
        }
        new AlertDialog.Builder(getContext())
                .setTitle("Speicherstände")
                .setItems(items, (dialog, which) -> onSlotTapped(which))
                .show();
    }

    private boolean slotIsOccupied(int slot) {
        return prefs.contains(slotKey(slot, "level"));
    }

    private String describeSlot(int slot) {
        if (!slotIsOccupied(slot)) {
            return "Slot " + (slot + 1) + ": leer";
        }
        int slotLevel = prefs.getInt(slotKey(slot, "level"), 1);
        int slotScore = prefs.getInt(slotKey(slot, "score"), 0);
        return "Slot " + (slot + 1) + ": Level " + slotLevel + ", Score " + slotScore;
    }

    // Tapping an empty slot saves the current game there right away; tapping an occupied one
    // opens a follow-up dialog (load / overwrite / delete) instead of silently clobbering it.
    private void onSlotTapped(int slot) {
        if (!slotIsOccupied(slot)) {
            saveToSlot(slot);
            Toast.makeText(getContext(), "In Slot " + (slot + 1) + " gespeichert.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(describeSlot(slot))
                .setItems(new String[]{"Laden", "Überschreiben", "Löschen"}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            loadFromSlot(slot);
                            break;
                        case 1:
                            saveToSlot(slot);
                            Toast.makeText(getContext(), "Slot " + (slot + 1) + " überschrieben.", Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            deleteSlot(slot);
                            Toast.makeText(getContext(), "Slot " + (slot + 1) + " gelöscht.", Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .show();
    }

    private void saveToSlot(int slot) {
        if (gameBoard == null) return;
        prefs.edit()
                .putInt(slotKey(slot, "level"), level)
                .putInt(slotKey(slot, "score"), score)
                .putString(slotKey(slot, "blocks"), gameBoard.exportBlocksJson())
                .putString(slotKey(slot, "bonus_counts"), serializeBonusCounts())
                .apply();
    }

    private void loadFromSlot(int slot) {
        if (gameBoard == null || !slotIsOccupied(slot)) return;
        level = prefs.getInt(slotKey(slot, "level"), 1);
        score = prefs.getInt(slotKey(slot, "score"), 0);
        gameOver = false;
        armedBonuses.clear();
        loadBonusCounts(prefs.getString(slotKey(slot, "bonus_counts"), null));
        String blocksJson = prefs.getString(slotKey(slot, "blocks"), null);
        if (blocksJson != null) {
            gameBoard.restoreBlocksFromJson(blocksJson);
        } else {
            gameBoard.initBoard();
        }
        Toast.makeText(getContext(), "Slot " + (slot + 1) + " geladen.", Toast.LENGTH_SHORT).show();
    }

    private void deleteSlot(int slot) {
        prefs.edit()
                .remove(slotKey(slot, "level"))
                .remove(slotKey(slot, "score"))
                .remove(slotKey(slot, "blocks"))
                .remove(slotKey(slot, "bonus_counts"))
                .apply();
    }

    private String slotKey(int slot, String field) {
        return PREFS_KEY_SLOT_PREFIX + slot + "_" + field;
    }

    private void cancelBonusLongPress() {
        if (bonusLongPressRunnable != null) {
            bonusLongPressHandler.removeCallbacks(bonusLongPressRunnable);
            bonusLongPressRunnable = null;
        }
    }

    private void cancelStatBoxLongPress() {
        if (statBoxLongPressRunnable != null) {
            statBoxLongPressHandler.removeCallbacks(statBoxLongPressRunnable);
            statBoxLongPressRunnable = null;
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
            case DRAG_PADDLE: return "Zieh-Paddle";
        }
        return bonus.name();
    }

    private String bonusDescription(Bonus bonus) {
        switch (bonus) {
            case MOVE_STOPPER:
                return "Das automatische Runterschieben des Spielfelds setzt einmal aus. Verhindert auch ein Game Over für diesen Zug, falls bereits ein Block die unterste Reihe erreicht hat.";
            case EXTENDED_PATH:
                return "Die Vorschau-Ziellinie wird länger, um weiter oben liegende Bereiche besser anpeilen zu können.";
            case LINE_DELETE:
                return "Wird sofort beim Antippen ausgeführt: eine der drei wertvollsten Reihen wird zufällig gelöscht, die darunterliegenden Reihen rutschen nach oben.";
            case EXTRA_BALLS:
                return "Der nächste Schuss wird mit deutlich mehr Bällen (20) abgefeuert.";
            case MOVE_START_POINT:
                return "Du kannst frei bestimmen, von wo aus der nächste Ball abgeschossen wird.";
            case DRAG_PADDLE:
                return "Nach dem Abschuss erscheint auf Höhe der Startlinie ein Balken, der Bälle nach oben zurückwirft. Ziehe mit dem Finger nach links oder rechts, um ihn zu verschieben. Mit jedem Treffer wird er kleiner, bis er nach 10 Treffern (oder am Ende des Zugs) verschwindet.";
        }
        return "";
    }

    // Predefined level layouts live in assets/levels/level<N>.json (see tools/level_editor.py).
    // Returns null -- meaning "no predefined layout, generate one randomly" -- if the level has
    // no file, keeping levels beyond the last authored one playable.
    //
    // Checks a Level-Editor override first: a running app can't write back into its own assets/,
    // so edits/new levels made on-device via the "Level-Editor" burger menu action are persisted
    // to app-internal storage instead (see levelOverrideFile()) -- this is the single resolution
    // point GameBoard.initBoard() already goes through, so an edited level is immediately playable
    // via completely normal gameplay, not just inside the editor.
    public String loadLevelJson(int level) {
        // Probespielen (test play, see startTestPlay()): unconditionally wins over any real level
        // file or override, regardless of which level number GameBoard.initBoard() asks for --
        // that's what makes a still-unsaved editor layout playable at all.
        if (testPlayActive) {
            return testPlayJson;
        }
        // Every 10th level is deliberately random (see isRandomLevelSlot()) -- short-circuit to
        // null unconditionally so it stays random even if a stray override somehow exists.
        if (isRandomLevelSlot(level)) {
            return null;
        }
        String override = readLevelOverride(level);
        if (override != null) {
            return override;
        }
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

    // Every 10th level (10, 20, 30, ...) is deliberately left without a level*.json file (and
    // never gets a Level-Editor override, see loadLevelJson()/insertLevelWithShift() below) so
    // it's always randomly generated by GameBoard.randomBoard() -- a recognizable "Zufalls-Level"
    // breather between authored levels. Greyed out / unselectable in the Level-Editor UI.
    static boolean isRandomLevelSlot(int level) {
        return level % 10 == 0;
    }

    // Smallest level number >= level that isn't a random-level slot -- used for the default
    // "append" target and while shifting levels during "insert at position" so nothing ever lands
    // on a reserved slot.
    private static int nextEditableSlot(int level) {
        while (isRandomLevelSlot(level)) level++;
        return level;
    }

    // ---- Level-Editor persistence (LevelEditor.EditorCallbacks) ----

    private static final Pattern LEVEL_FILE_PATTERN = Pattern.compile("level(\\d+)\\.json");

    private File levelOverrideFile(int level) {
        return new File(new File(getContext().getFilesDir(), "levels"), "level" + level + ".json");
    }

    private String readLevelOverride(int level) {
        File f = levelOverrideFile(level);
        if (!f.isFile()) return null;
        try (InputStream is = new java.io.FileInputStream(f)) {
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

    private void writeLevelOverride(int level, String json) {
        File f = levelOverrideFile(level);
        f.getParentFile().mkdirs();
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e("Game()", "Failed to save level override " + level, e);
        }
    }

    // Union of level numbers found as assets/levels/level<N>.json and as Level-Editor overrides
    // under internal storage -- always derived fresh (no separately tracked "level count" that
    // could go stale).
    @Override
    public java.util.List<Integer> listKnownLevels() {
        java.util.TreeSet<Integer> levels = new java.util.TreeSet<>();
        try {
            String[] assetFiles = getContext().getAssets().list("levels");
            if (assetFiles != null) {
                for (String name : assetFiles) {
                    Matcher m = LEVEL_FILE_PATTERN.matcher(name);
                    if (m.matches()) levels.add(Integer.parseInt(m.group(1)));
                }
            }
        } catch (IOException e) {
            Log.e("Game()", "Failed to list asset levels", e);
        }
        File overrideDir = new File(getContext().getFilesDir(), "levels");
        File[] overrideFiles = overrideDir.listFiles();
        if (overrideFiles != null) {
            for (File f : overrideFiles) {
                Matcher m = LEVEL_FILE_PATTERN.matcher(f.getName());
                if (m.matches()) levels.add(Integer.parseInt(m.group(1)));
            }
        }
        return new ArrayList<>(levels);
    }

    @Override
    public String loadLevelJsonForEdit(int level) {
        return loadLevelJson(level);
    }

    @Override
    public void saveLevelJson(int level, String json) {
        writeLevelOverride(level, json);
    }

    // "Insert at N": every currently-known level from the highest down to N is shifted up to the
    // next slot (read its *current resolved* content, write it as the override at
    // nextEditableSlot(levelNum + 1) -- skipping over any reserved random-level slot crossed in
    // the process, so 10/20/30/... never end up holding real content), then the new content is
    // written at N -- see LevelEditor's Mode.INSERT. Only ever touches the override layer since
    // asset files can't be modified; already-shifted slots simply become override-backed instead
    // of asset-backed, which resolves identically either way.
    @Override
    public void insertLevelWithShift(int atLevel, String json) {
        if (isRandomLevelSlot(atLevel)) {
            return; // guarded by the UI (showInsertPositionDialog) -- defensive no-op here too
        }
        java.util.List<Integer> known = listKnownLevels();
        int max = known.isEmpty() ? atLevel - 1 : known.get(known.size() - 1);
        for (int levelNum = max; levelNum >= atLevel; levelNum--) {
            String content = loadLevelJson(levelNum);
            if (content != null) {
                writeLevelOverride(nextEditableSlot(levelNum + 1), content);
            }
        }
        writeLevelOverride(atLevel, json);
    }

    @Override
    public void exportJsonToClipboard(String json) {
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("BlockPong Level JSON", json));
        }
    }

    @Override
    public void showToast(String message) {
        // Covers both LevelEditor's "gespeichert" and "kopiert" confirmations -- it doesn't
        // distinguish which, so both get the clipboard-safe raised position; harmless for the
        // save confirmation, necessary for the export one.
        showClipboardToast(message, Toast.LENGTH_LONG);
    }

    @Override
    public void closeEditor() {
        levelEditorActive = false;
    }

    // 2048-style stat boxes (LEVEL / SCORE / BEST), drawn below the bonus row in the reserved
    // footer area -- the aim line always points up into the board (see
    // GameBoard.clampAimVector), so it never reaches down here regardless of drag distance.
    private void drawStatsFooter(Canvas canvas) {
        RectF scoreBox = getStatBoxRect(0);
        RectF levelBox = getStatBoxRect(1);
        RectF bestBox = getStatBoxRect(2);

        drawStatBox(canvas, scoreBox, "SCORE", String.valueOf(score));
        drawStatBox(canvas, levelBox, "LEVEL", String.valueOf(level));
        drawStatBox(canvas, bestBox, "BEST", String.valueOf(bestScore));
    }

    // index: 0=SCORE, 1=LEVEL, 2=BEST.
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

    // Hand-drawn glyphs (matching this project's all-vector art style, no icon assets) so each
    // bonus type is visually distinguishable and reads as what it actually does at a glance.
    // Actual shapes live in BonusIcons, shared with BonusAwardAnimation's pop-in celebration.
    private void drawBonusIcon(Canvas canvas, Bonus bonus, RectF box) {
        float cx = box.centerX();
        float cy = box.centerY();
        float r = Math.min(box.width(), box.height()) * 0.28f;
        BonusIcons.draw(canvas, bonus, cx, cy, r,
                bonusIconPaint, bonusIconThinPaint, bonusIconFillPaint, bonusGrayPaint);
    }
}
