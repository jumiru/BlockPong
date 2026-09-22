package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Burger-menu "Level-Übersicht": a scrollable grid of every known level's thumbnail, mirroring
// tools/level_editor.py's LevelOverviewWindow -- but read-only/navigation-only on-device: dragging
// scrolls, tapping a thumbnail jumps straight to that level (see Game.goToLevel()), "Schliessen" or
// the still-active burger-menu button leaves without picking one. Random-level slots
// (Game.isRandomLevelSlot()) have no fixed layout to preview -- they render as a dice placeholder
// instead of parsed blocks, but are still tappable since they're perfectly playable.
public class LevelOverview {

    // Matches LevelEditor's NORMAL_GRID_COLS/GRID_ROWS (== GameBoard's normal authored-level grid),
    // used both as the aspect ratio EVERY thumbnail card is laid out against (uniform regardless of
    // the level's own grid, so the masonry layout stays regular) and as the coordinate space a
    // normal level's blocks are parsed/drawn against. A Mini-Blöcke level (see
    // OverviewCallbacks.isMiniBlockLevel()) uses MINI_GRID_COLS_THUMB/ROWS_THUMB instead for the
    // latter (matching LevelEditor's own MINI_GRID_COLS/ROWS) so its blocks are parsed/drawn at
    // their correct relative positions instead of being clipped to the normal grid's smaller
    // bounds -- they still land inside the SAME uniformly-shaped card, just packed in smaller.
    private static final int GRID_COLS_THUMB = 11;
    private static final int GRID_ROWS_THUMB = 16;
    private static final int MINI_GRID_COLS_THUMB = 27;
    private static final int MINI_GRID_ROWS_THUMB = 42;
    private static final int COLUMNS = 3;
    private static final float CELL_MARGIN = 18f;
    private static final float LABEL_HEIGHT = 50f;
    private static final float HEADER_HEIGHT = 110f;
    // ACTION_MOVE must move the finger at least this far before a touch counts as a scroll drag
    // rather than a tap -- otherwise a finger's natural jitter while tapping a thumbnail would
    // register as a (tiny, invisible) scroll and suppress the tap in handleTouchUp().
    private static final float DRAG_SLOP_PX = 12f;

    public interface OverviewCallbacks {
        List<Integer> listKnownLevels();
        String loadLevelJsonForThumbnail(int level);
        boolean isRandomLevel(int level);
        // "Mini-Blöcke" slot (see Game.isMiniBlockLevelSlot()): a perfectly normal, authored/
        // editable level like any other, just laid out on GameBoard's finer grid -- its thumbnail
        // parses/draws blocks against MINI_GRID_COLS_THUMB/ROWS_THUMB instead of the normal grid,
        // and its card label gets a "(Mini)" suffix (see open()/drawCard()).
        boolean isMiniBlockLevel(int level);
        int getCurrentLevel();
        void goToLevelFromOverview(int level);
        void closeOverview();
    }

    private final OverviewCallbacks callbacks;

    private final Paint headerBgPaint;
    private final Paint titlePaint;
    private final Paint closeButtonPaint;
    private final Paint closeButtonTextPaint;
    private final Paint cellBgPaint;
    private final Paint currentCellBgPaint;
    private final Paint cellBorderPaint;
    private final Paint currentBorderPaint;
    private final Paint labelPaint;
    private final Paint currentLabelPaint;
    private final Paint randomLabelPaint;
    private final Paint blockFillPaint;
    private final Path blockPath = new Path();

    private List<Integer> levels = new ArrayList<>();
    // Parallel to levels: parsed blocks as {x, y, shapeCode, value} rows, or null for a
    // random-level slot (Game.isRandomLevelSlot(), no fixed layout to preview -- renders as a dice
    // placeholder instead, see drawRandomPlaceholder()).
    private List<List<int[]>> blocksByLevel = new ArrayList<>();
    // Parallel to levels: true for a Mini-Blöcke level (see OverviewCallbacks.isMiniBlockLevel()) --
    // it's still parsed/drawn as real blocks like any other authored level, just against
    // MINI_GRID_COLS_THUMB/ROWS_THUMB instead of the normal grid (see parseBlocks()/drawBlocks()),
    // and gets a "(Mini)" suffix on its card label (see drawCard()).
    private List<Boolean> miniByLevel = new ArrayList<>();
    private int currentLevelHighlight;

    private int canvasWidth;
    private int canvasHeight;
    private float scrollY;

    private boolean dragging;
    private boolean dragMoved;
    private float dragStartY;
    private float dragStartScrollY;

    public LevelOverview(OverviewCallbacks callbacks) {
        this.callbacks = callbacks;

        headerBgPaint = new Paint();
        headerBgPaint.setColor(Color.rgb(45, 40, 80));

        titlePaint = new Paint();
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(42);
        titlePaint.setFakeBoldText(true);
        titlePaint.setTextAlign(Paint.Align.RIGHT);
        titlePaint.setAntiAlias(true);

        closeButtonPaint = new Paint();
        closeButtonPaint.setColor(Color.rgb(70, 60, 110));

        closeButtonTextPaint = new Paint();
        closeButtonTextPaint.setColor(Color.WHITE);
        closeButtonTextPaint.setTextSize(32);
        closeButtonTextPaint.setTextAlign(Paint.Align.CENTER);
        closeButtonTextPaint.setAntiAlias(true);

        cellBgPaint = new Paint();
        cellBgPaint.setColor(Color.rgb(20, 20, 26));

        currentCellBgPaint = new Paint();
        currentCellBgPaint.setColor(Color.rgb(35, 32, 55));

        cellBorderPaint = new Paint();
        cellBorderPaint.setStyle(Paint.Style.STROKE);
        cellBorderPaint.setStrokeWidth(2);
        cellBorderPaint.setColor(Color.rgb(70, 70, 80));

        currentBorderPaint = new Paint();
        currentBorderPaint.setStyle(Paint.Style.STROKE);
        currentBorderPaint.setStrokeWidth(6);
        currentBorderPaint.setColor(Color.rgb(255, 210, 60));

        labelPaint = new Paint();
        labelPaint.setColor(Color.rgb(210, 210, 220));
        labelPaint.setTextSize(32);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setAntiAlias(true);

        currentLabelPaint = new Paint();
        currentLabelPaint.setColor(Color.rgb(255, 210, 60));
        currentLabelPaint.setTextSize(32);
        currentLabelPaint.setFakeBoldText(true);
        currentLabelPaint.setTextAlign(Paint.Align.CENTER);
        currentLabelPaint.setAntiAlias(true);

        randomLabelPaint = new Paint();
        randomLabelPaint.setColor(Color.rgb(150, 150, 160));
        randomLabelPaint.setTextSize(30);
        randomLabelPaint.setTextAlign(Paint.Align.CENTER);
        randomLabelPaint.setAntiAlias(true);

        blockFillPaint = new Paint();
        blockFillPaint.setAntiAlias(true);
    }

    // ---- opening ----

    public void open(int canvasWidth, int canvasHeight, int currentLevel) {
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.currentLevelHighlight = currentLevel;

        levels = callbacks.listKnownLevels();
        blocksByLevel = new ArrayList<>(levels.size());
        miniByLevel = new ArrayList<>(levels.size());
        for (int lvl : levels) {
            boolean mini = callbacks.isMiniBlockLevel(lvl);
            miniByLevel.add(mini);
            if (callbacks.isRandomLevel(lvl)) {
                blocksByLevel.add(null);
            } else {
                String json = callbacks.loadLevelJsonForThumbnail(lvl);
                int cols = mini ? MINI_GRID_COLS_THUMB : GRID_COLS_THUMB;
                int rows = mini ? MINI_GRID_ROWS_THUMB : GRID_ROWS_THUMB;
                blocksByLevel.add(json != null ? parseBlocks(json, cols, rows) : new ArrayList<>());
            }
        }
        scrollToLevel(currentLevel);
    }

    private List<int[]> parseBlocks(String json, int cols, int rows) {
        List<int[]> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.getJSONArray("blocks");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.getJSONObject(i);
                int x = b.getInt("x");
                int y = b.getInt("y");
                if (x < 0 || x >= cols || y < 0 || y >= rows) continue;
                int value = b.getInt("value");
                int shape = shapeCode(b.getString("type"));
                result.add(new int[]{x, y, shape, value});
            }
        } catch (JSONException e) {
            // Malformed level JSON just yields a partial/blank thumbnail -- not worth failing the
            // whole overview over one bad level file.
        }
        return result;
    }

    private int shapeCode(String type) {
        switch (type) {
            case "bl": return 1;
            case "tl": return 2;
            case "tr": return 3;
            case "br": return 4;
            default: return 0; // square
        }
    }

    // Matches LevelEditor.previewColor()/Block.getRectColorFromValue()'s hue formula so a
    // thumbnail's colors match what the block actually looks like in-game.
    private int previewColor(int value) {
        float hue = (value * 24f) % 360f;
        return Color.HSVToColor(new float[]{hue, 0.65f, 0.90f});
    }

    // ---- layout ----

    private float colWidth() {
        return (canvasWidth - (COLUMNS + 1) * CELL_MARGIN) / (float) COLUMNS;
    }

    private float cardHeight() {
        float thumbHeight = colWidth() * GRID_ROWS_THUMB / (float) GRID_COLS_THUMB;
        return thumbHeight + LABEL_HEIGHT;
    }

    private RectF cardRect(int index) {
        int col = index % COLUMNS;
        int row = index / COLUMNS;
        float colWidth = colWidth();
        float rowStride = cardHeight() + CELL_MARGIN;
        float left = CELL_MARGIN + col * (colWidth + CELL_MARGIN);
        float top = HEADER_HEIGHT + CELL_MARGIN + row * rowStride - scrollY;
        return new RectF(left, top, left + colWidth, top + cardHeight());
    }

    private float contentHeight() {
        int rows = (levels.size() + COLUMNS - 1) / COLUMNS;
        return HEADER_HEIGHT + CELL_MARGIN + rows * (cardHeight() + CELL_MARGIN);
    }

    private RectF closeButtonRect() {
        return new RectF(30, 20, 280, HEADER_HEIGHT - 20);
    }

    private float clampScroll(float value) {
        float maxScroll = Math.max(0, contentHeight() - canvasHeight);
        return Math.max(0, Math.min(maxScroll, value));
    }

    // Scrolls so the given level's row lands just below the header -- called on open() so jumping
    // into the overview shows the player's current spot right away instead of always starting at
    // Level 1.
    private void scrollToLevel(int level) {
        int idx = levels.indexOf(level);
        if (idx < 0) {
            scrollY = 0;
            return;
        }
        int row = idx / COLUMNS;
        float rowTop = HEADER_HEIGHT + CELL_MARGIN + row * (cardHeight() + CELL_MARGIN);
        scrollY = clampScroll(rowTop - HEADER_HEIGHT - CELL_MARGIN);
    }

    private int cellIndexAt(float x, float y) {
        if (y < HEADER_HEIGHT) return -1;
        for (int i = 0; i < levels.size(); i++) {
            if (cardRect(i).contains(x, y)) return i;
        }
        return -1;
    }

    // ---- touch ----

    public void handleTouchDown(float x, float y) {
        dragging = true;
        dragMoved = false;
        dragStartY = y;
        dragStartScrollY = scrollY;
    }

    public void handleTouchMove(float x, float y) {
        if (!dragging) return;
        float dy = y - dragStartY;
        if (Math.abs(dy) > DRAG_SLOP_PX) dragMoved = true;
        scrollY = clampScroll(dragStartScrollY - dy);
    }

    public void handleTouchUp(float x, float y) {
        dragging = false;
        if (dragMoved) return; // was a scroll drag, not a tap
        if (closeButtonRect().contains(x, y)) {
            callbacks.closeOverview();
            return;
        }
        int idx = cellIndexAt(x, y);
        if (idx >= 0) {
            callbacks.goToLevelFromOverview(levels.get(idx));
        }
    }

    // ---- drawing ----

    public void draw(Canvas c) {
        c.drawColor(Color.BLACK);
        drawCards(c);
        drawHeader(c);
    }

    private void drawHeader(Canvas c) {
        c.drawRect(0, 0, canvasWidth, HEADER_HEIGHT, headerBgPaint);
        RectF close = closeButtonRect();
        c.drawRoundRect(close, 16, 16, closeButtonPaint);
        c.drawText("Schliessen", close.centerX(), close.centerY() + 12, closeButtonTextPaint);
        // Right margin clears the burger-menu button, which stays drawn on top of this header
        // (see Game.draw()) and would otherwise overlap a title anchored closer to the edge.
        c.drawText("Level-Übersicht", canvasWidth - 130, HEADER_HEIGHT / 2f + 14, titlePaint);
    }

    // Only draws cards whose row could plausibly be visible -- with 100+ levels, drawing every
    // thumbnail's blocks every frame regardless of scroll position would be wasted work.
    private void drawCards(Canvas c) {
        float rowStride = cardHeight() + CELL_MARGIN;
        int firstRow = Math.max(0, (int) ((scrollY - HEADER_HEIGHT) / rowStride) - 1);
        int lastRow = (int) ((scrollY + canvasHeight) / rowStride) + 1;
        int firstIdx = firstRow * COLUMNS;
        int lastIdx = Math.min(levels.size() - 1, (lastRow + 1) * COLUMNS - 1);
        for (int i = firstIdx; i <= lastIdx; i++) {
            drawCard(c, i);
        }
    }

    private void drawCard(Canvas c, int idx) {
        RectF card = cardRect(idx);
        if (card.bottom < 0 || card.top > canvasHeight) return;
        int lvl = levels.get(idx);
        boolean isCurrent = (lvl == currentLevelHighlight);
        RectF thumb = new RectF(card.left, card.top, card.right, card.bottom - LABEL_HEIGHT);

        boolean mini = miniByLevel.get(idx);
        c.drawRect(thumb, isCurrent ? currentCellBgPaint : cellBgPaint);
        List<int[]> blocks = blocksByLevel.get(idx);
        if (blocks == null) {
            drawRandomPlaceholder(c, thumb);
        } else {
            drawBlocks(c, thumb, blocks, mini ? MINI_GRID_COLS_THUMB : GRID_COLS_THUMB,
                    mini ? MINI_GRID_ROWS_THUMB : GRID_ROWS_THUMB);
        }
        c.drawRect(thumb, isCurrent ? currentBorderPaint : cellBorderPaint);

        String text = "Level " + lvl + (mini ? " (Mini)" : "");
        c.drawText(text, card.centerX(), card.bottom - LABEL_HEIGHT / 2f + 12,
                isCurrent ? currentLabelPaint : labelPaint);
    }

    private void drawRandomPlaceholder(Canvas c, RectF thumb) {
        c.drawText("Zufalls-", thumb.centerX(), thumb.centerY() - 10, randomLabelPaint);
        c.drawText("Level", thumb.centerX(), thumb.centerY() + 40, randomLabelPaint);
    }

    private void drawBlocks(Canvas c, RectF thumb, List<int[]> blocks, int cols, int rows) {
        float cellW = thumb.width() / cols;
        float cellH = thumb.height() / rows;
        for (int[] b : blocks) {
            float left = thumb.left + b[0] * cellW;
            float top = thumb.top + b[1] * cellH;
            float right = left + cellW;
            float bottom = top + cellH;
            blockFillPaint.setColor(previewColor(b[3]));
            int shape = b[2];
            if (shape == 0) {
                c.drawRect(left, top, right, bottom, blockFillPaint);
                continue;
            }
            blockPath.reset();
            switch (shape) {
                case 1: // BL
                    blockPath.moveTo(left, bottom);
                    blockPath.lineTo(left, top);
                    blockPath.lineTo(right, bottom);
                    break;
                case 2: // TL
                    blockPath.moveTo(left, top);
                    blockPath.lineTo(right, top);
                    blockPath.lineTo(left, bottom);
                    break;
                case 3: // TR
                    blockPath.moveTo(right, top);
                    blockPath.lineTo(right, bottom);
                    blockPath.lineTo(left, top);
                    break;
                default: // BR
                    blockPath.moveTo(right, bottom);
                    blockPath.lineTo(left, bottom);
                    blockPath.lineTo(right, top);
                    break;
            }
            blockPath.close();
            c.drawPath(blockPath, blockFillPaint);
        }
    }
}
