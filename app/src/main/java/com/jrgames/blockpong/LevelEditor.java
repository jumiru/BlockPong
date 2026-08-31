package com.jrgames.blockpong;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

// In-app level editor (burger menu "Level-Editor"): create a new level or edit an existing one by
// tapping directly on a grid that mirrors the live game board's geometry. Tapping an empty cell
// places the current palette selection; tapping a block that differs from the selection replaces
// it; tapping a block that already matches the selection deletes it.
//
// A running app can't write into its own APK's assets/ (where levelN.json files normally live), so
// saving here persists to app-internal storage via EditorCallbacks -- Game.java resolves that
// override transparently in loadLevelJson(), so edited levels are immediately playable through
// completely normal gameplay. "Exportieren" copies the JSON to the clipboard so it can be pasted
// into tools/level_editor.py (its "paste debug json" feature) and committed permanently.
public class LevelEditor {

    public enum BlockShape {
        SQUARE, BL, TL, TR, BR;

        String jsonType() {
            return this == SQUARE ? "square" : name().toLowerCase();
        }
    }

    // EDIT_EXISTING/APPEND overwrite targetLevel directly on save; INSERT shifts every level from
    // targetLevel onward down by one first (see EditorCallbacks.insertLevelWithShift()), then -- the
    // shift only needs to happen once -- behaves like EDIT_EXISTING for any further save.
    public enum Mode { EDIT_EXISTING, APPEND, INSERT }

    public interface EditorCallbacks {
        java.util.List<Integer> listKnownLevels();
        String loadLevelJsonForEdit(int level);
        void saveLevelJson(int level, String json);
        void insertLevelWithShift(int atLevel, String json);
        void exportJsonToClipboard(String json);
        void showToast(String message);
        void closeEditor();
        // "Probespielen": play the current in-memory layout (json, saved or not) as normal
        // gameplay, targetLevel purely for cosmetic display (LEVEL stat box, debug exports) while
        // testing -- see Game.startTestPlay()/endTestPlay().
        void startTestPlay(String json, int targetLevel);
    }

    // Matches GameBoard's xDim (11 columns) and yDim-2 (18-2=16 editable rows) -- authored level
    // files never populate the bottom two rows, same convention GameBoard.loadBlocksFromJson()
    // documents. Not read from a live GameBoard instance since it exposes no public getter for
    // xDim/yDim; keep in sync by hand if those ever change.
    static final int GRID_COLS = 11;
    static final int GRID_ROWS = 16;

    private static final int MIN_VALUE = 1;
    private static final int MAX_VALUE = 99;

    private static final float PALETTE_TOP_MARGIN = 30f;
    private static final float ROW_HEIGHT = 110f;
    private static final float ROW_GAP = 15f;

    private final GameBoard geometry;
    private final EditorCallbacks callbacks;

    private final Block[][] blocks = new Block[GRID_COLS][GRID_ROWS];
    private BlockShape selectedShape = BlockShape.SQUARE;
    private int selectedValue = 5;

    // "Streifen" (drag-paint/-erase across several cells in one motion, see handleGridTap()/
    // handleGridDrag()/handleTouchMove()): whether the in-progress drag started on the grid at
    // all, which mode it's in (paint vs. erase, decided by the starting cell's own tap outcome),
    // and the last cell it touched (so re-entering the same cell on jittery finger movement isn't
    // reapplied every single ACTION_MOVE).
    private boolean gridDragActive;
    private boolean dragErase;
    private int dragLastX = -1;
    private int dragLastY = -1;
    private Mode mode = Mode.APPEND;
    private int targetLevel = 1;
    private boolean dirty;

    private final Paint titlePaint;
    private final Paint emptyCellPaint;
    private final Paint paletteLabelPaint;
    private final Paint selectedBorderPaint;
    private final Paint swatchStrokePaint;
    private final Paint actionButtonPaint;
    private final Paint actionButtonTextPaint;
    private final Paint stepperTextPaint;
    private final Paint stepperValuePaint;

    public LevelEditor(GameBoard geometry, EditorCallbacks callbacks) {
        this.geometry = geometry;
        this.callbacks = callbacks;

        titlePaint = new Paint();
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(48);
        titlePaint.setFakeBoldText(true);
        titlePaint.setAntiAlias(true);

        emptyCellPaint = new Paint();
        emptyCellPaint.setStyle(Paint.Style.STROKE);
        emptyCellPaint.setColor(Color.rgb(70, 70, 80));
        emptyCellPaint.setStrokeWidth(2);

        paletteLabelPaint = new Paint();
        paletteLabelPaint.setColor(Color.rgb(190, 180, 220));
        paletteLabelPaint.setTextSize(30);
        paletteLabelPaint.setTextAlign(Paint.Align.CENTER);
        paletteLabelPaint.setAntiAlias(true);

        selectedBorderPaint = new Paint();
        selectedBorderPaint.setStyle(Paint.Style.STROKE);
        selectedBorderPaint.setStrokeWidth(6);
        selectedBorderPaint.setColor(Color.WHITE);

        swatchStrokePaint = new Paint();
        swatchStrokePaint.setStyle(Paint.Style.STROKE);
        swatchStrokePaint.setColor(Color.WHITE);
        swatchStrokePaint.setStrokeWidth(3);
        swatchStrokePaint.setAntiAlias(true);

        actionButtonPaint = new Paint();
        actionButtonPaint.setColor(Color.rgb(45, 40, 80));

        actionButtonTextPaint = new Paint();
        actionButtonTextPaint.setColor(Color.WHITE);
        actionButtonTextPaint.setTextSize(34);
        actionButtonTextPaint.setTextAlign(Paint.Align.CENTER);
        actionButtonTextPaint.setAntiAlias(true);

        stepperTextPaint = new Paint();
        stepperTextPaint.setColor(Color.WHITE);
        stepperTextPaint.setTextSize(48);
        stepperTextPaint.setTextAlign(Paint.Align.CENTER);
        stepperTextPaint.setAntiAlias(true);

        stepperValuePaint = new Paint();
        stepperValuePaint.setColor(Color.WHITE);
        stepperValuePaint.setTextSize(44);
        stepperValuePaint.setTextAlign(Paint.Align.CENTER);
        stepperValuePaint.setAntiAlias(true);
    }

    // ---- opening ----

    public void openForEdit(int level) {
        mode = Mode.EDIT_EXISTING;
        targetLevel = level;
        clearGrid();
        String json = callbacks.loadLevelJsonForEdit(level);
        if (json != null && !loadFromJson(json)) {
            callbacks.showToast("Level " + level + " konnte nicht geladen werden.");
        }
        dirty = false;
    }

    public void openNew(int level, boolean insert) {
        mode = insert ? Mode.INSERT : Mode.APPEND;
        targetLevel = level;
        clearGrid();
        dirty = false;
    }

    private void clearGrid() {
        for (int x = 0; x < GRID_COLS; x++) {
            for (int y = 0; y < GRID_ROWS; y++) {
                blocks[x][y] = null;
            }
        }
    }

    // ---- JSON (mirrors GameBoard.exportBlocksJson()/loadBlocksFromJson()'s shape, kept as its own
    // small implementation rather than reused as-is: GameBoard silently falls back to a random
    // board on a parse error, which is right for live gameplay but wrong for an editor -- this
    // reports failure instead, see loadFromJson()'s boolean return) ----

    public String toJson() {
        try {
            JSONArray blockArray = new JSONArray();
            for (int y = 0; y < GRID_ROWS; y++) {
                for (int x = 0; x < GRID_COLS; x++) {
                    Block b = blocks[x][y];
                    if (b == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("x", x);
                    o.put("y", y);
                    o.put("value", b.getValue());
                    o.put("type", blockJsonType(b));
                    blockArray.put(o);
                }
            }
            JSONObject root = new JSONObject();
            root.put("blocks", blockArray);
            return root.toString();
        } catch (JSONException e) {
            return "{\"blocks\":[]}";
        }
    }

    public boolean loadFromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray blockArray = root.getJSONArray("blocks");
            Block[][] parsed = new Block[GRID_COLS][GRID_ROWS];
            for (int i = 0; i < blockArray.length(); i++) {
                JSONObject b = blockArray.getJSONObject(i);
                int x = b.getInt("x");
                int y = b.getInt("y");
                int value = b.getInt("value");
                String type = b.getString("type");
                if (x < 0 || x >= GRID_COLS || y < 0 || y >= GRID_ROWS) continue;
                parsed[x][y] = createBlock(x, y, type, value);
            }
            for (int x = 0; x < GRID_COLS; x++) {
                System.arraycopy(parsed[x], 0, blocks[x], 0, GRID_ROWS);
            }
            return true;
        } catch (JSONException | IllegalArgumentException e) {
            return false;
        }
    }

    private String blockJsonType(Block b) {
        return (b instanceof Block3) ? ((Block3) b).getType().name().toLowerCase() : "square";
    }

    private Block createBlock(int x, int y, String type, int value) {
        if ("square".equals(type)) {
            return new Block4(geometry, x, y, value);
        }
        Block3.tTriangle triangleType = Block3.tTriangle.valueOf(type.toUpperCase());
        return new Block3(geometry, x, y, triangleType, value);
    }

    private Block createSelectedBlock(int x, int y) {
        return createBlock(x, y, selectedShape.jsonType(), selectedValue);
    }

    // "existing block matches selection exactly" per the requested tap rules -- same shape AND
    // same value.
    private boolean matchesSelection(Block b) {
        if (b.getValue() != selectedValue) return false;
        if (b instanceof Block4) return selectedShape == BlockShape.SQUARE;
        if (b instanceof Block3) {
            return selectedShape.name().equals(((Block3) b).getType().name());
        }
        return false;
    }

    // ---- grid editing ----

    // Also decides how a drag starting on this cell continues (see handleGridDrag()): a tap that
    // places a block starts a "paint" stroke, a tap that deletes one starts an "erase" stroke --
    // whichever this first cell did, dragging over further cells keeps doing.
    void handleGridTap(int x, int y) {
        Block existing = blocks[x][y];
        if (existing == null || !matchesSelection(existing)) {
            blocks[x][y] = createSelectedBlock(x, y);
            dragErase = false;
        } else {
            blocks[x][y] = null;
            dragErase = true;
        }
        dragLastX = x;
        dragLastY = y;
        gridDragActive = true;
        dirty = true;
    }

    // Continues a drag/"streifen" stroke started by handleGridTap() onto a further cell. Applies
    // the stroke's already-decided mode instead of re-running the tap's toggle rule per cell --
    // toggling per cell mid-drag would flicker unpredictably (e.g. immediately erase a block the
    // drag just painted one tick earlier once it revisits that exact spot). A paint stroke skips
    // cells that already exactly match the selection, so dragging back over already-painted
    // ground leaves it alone rather than deleting it; an erase stroke clears whatever's in each
    // cell regardless of its type/value.
    private void handleGridDrag(int x, int y) {
        if (x == dragLastX && y == dragLastY) return;
        dragLastX = x;
        dragLastY = y;
        if (dragErase) {
            if (blocks[x][y] != null) {
                blocks[x][y] = null;
                dirty = true;
            }
        } else if (blocks[x][y] == null || !matchesSelection(blocks[x][y])) {
            blocks[x][y] = createSelectedBlock(x, y);
            dirty = true;
        }
    }

    // ---- touch dispatch ----

    // ACTION_DOWN. gridDragActive is cleared here (not just left stale) for any touch that starts
    // outside the grid -- e.g. pressing a palette button and then dragging onto the grid must not
    // resume painting from whatever stroke happened to run last.
    public void handleTouch(float x, float y) {
        int cx = cellXAt(x);
        int cy = cellYAt(y);
        if (cx >= 0 && cy >= 0) {
            handleGridTap(cx, cy);
            return;
        }
        gridDragActive = false;
        BlockShape[] shapes = BlockShape.values();
        for (int i = 0; i < shapes.length; i++) {
            if (shapeSwatchRect(i).contains(x, y)) {
                selectedShape = shapes[i];
                return;
            }
        }
        if (stepperMinusRect().contains(x, y)) {
            selectedValue = clampValue(selectedValue - 1);
            return;
        }
        if (stepperPlusRect().contains(x, y)) {
            selectedValue = clampValue(selectedValue + 1);
            return;
        }
        if (allValuesButtonRect(0).contains(x, y)) {
            adjustAllValues(-1);
            return;
        }
        if (allValuesButtonRect(1).contains(x, y)) {
            adjustAllValues(1);
            return;
        }
        if (shiftButtonRect(0).contains(x, y)) {
            shiftAll(-1, 0);
            return;
        }
        if (shiftButtonRect(1).contains(x, y)) {
            shiftAll(1, 0);
            return;
        }
        if (shiftButtonRect(2).contains(x, y)) {
            shiftAll(0, -1);
            return;
        }
        if (shiftButtonRect(3).contains(x, y)) {
            shiftAll(0, 1);
            return;
        }
        if (actionButtonRect(0).contains(x, y)) {
            onSave();
            return;
        }
        if (actionButtonRect(1).contains(x, y)) {
            onExport();
            return;
        }
        if (actionButtonRect(2).contains(x, y)) {
            onTestPlay();
            return;
        }
        if (actionButtonRect(3).contains(x, y)) {
            callbacks.closeEditor();
        }
    }

    // ACTION_MOVE: extends the drag/"streifen" stroke started by handleTouch()'s ACTION_DOWN (see
    // handleGridDrag()) so several cells can be painted or erased in one finger motion instead of
    // tapping each one individually. A no-op whenever the stroke didn't start on the grid (see
    // gridDragActive) -- unlike handleTouch(), this never checks the palette/action buttons, so a
    // drag that happens to cross e.g. "Schliessen" on its way can't accidentally trigger it.
    public void handleTouchMove(float x, float y) {
        if (!gridDragActive) return;
        int cx = cellXAt(x);
        int cy = cellYAt(y);
        if (cx >= 0 && cy >= 0) {
            handleGridDrag(cx, cy);
        }
    }

    private int clampValue(int v) {
        return Math.max(MIN_VALUE, Math.min(MAX_VALUE, v));
    }

    // "Alle Werte vergroessern/verkleinern": rescales every placed block's value by +-1 in one tap
    // (repeatable for bigger changes), independent of the palette's own selectedValue stepper --
    // that one only affects blocks placed *after* it's changed, this affects every block already on
    // the grid. Rebuilds each affected block via createBlock() rather than mutating Block.value
    // directly since Block exposes no public setter (only hit(), which only ever decrements by 1).
    private void adjustAllValues(int delta) {
        boolean changed = false;
        for (int x = 0; x < GRID_COLS; x++) {
            for (int y = 0; y < GRID_ROWS; y++) {
                Block b = blocks[x][y];
                if (b == null) continue;
                int newValue = clampValue(b.getValue() + delta);
                if (newValue != b.getValue()) {
                    blocks[x][y] = createBlock(x, y, blockJsonType(b), newValue);
                    changed = true;
                }
            }
        }
        if (changed) dirty = true;
    }

    // Shifts every placed block by one cell in the given direction. Checked in a first pass so a
    // shift that would push any block off the grid is rejected as a whole rather than silently
    // dropping the offending blocks. A uniform shift can never make two blocks collide, so unlike
    // adjustAllValues() there's no need to guard against overwriting an existing block.
    private void shiftAll(int dx, int dy) {
        for (int x = 0; x < GRID_COLS; x++) {
            for (int y = 0; y < GRID_ROWS; y++) {
                if (blocks[x][y] == null) continue;
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || nx >= GRID_COLS || ny < 0 || ny >= GRID_ROWS) {
                    callbacks.showToast("Verschieben nicht moeglich: Block wuerde das Feld verlassen.");
                    return;
                }
            }
        }
        Block[][] shifted = new Block[GRID_COLS][GRID_ROWS];
        for (int x = 0; x < GRID_COLS; x++) {
            for (int y = 0; y < GRID_ROWS; y++) {
                Block b = blocks[x][y];
                if (b == null) continue;
                shifted[x + dx][y + dy] = createBlock(x + dx, y + dy, blockJsonType(b), b.getValue());
            }
        }
        for (int x = 0; x < GRID_COLS; x++) {
            System.arraycopy(shifted[x], 0, blocks[x], 0, GRID_ROWS);
        }
        dirty = true;
    }

    private void onSave() {
        String json = toJson();
        if (mode == Mode.INSERT) {
            callbacks.insertLevelWithShift(targetLevel, json);
            mode = Mode.EDIT_EXISTING; // shift only ever needs to happen once
        } else {
            callbacks.saveLevelJson(targetLevel, json);
        }
        dirty = false;
        callbacks.showToast("Level " + targetLevel + " lokal gespeichert. \"Exportieren\" fuer dauerhafte Uebernahme.");
    }

    private void onExport() {
        // Wrapped in the same "Level N (JSON, kompatibel mit tools/level_editor.py):" label
        // Game.exportAllLevels() uses for each of its entries, so a single-level export round-trips
        // through the desktop tool's "Save All to Assets" the same way a bulk export does -- no
        // separate unlabeled-JSON path to keep in sync on the Python side.
        callbacks.exportJsonToClipboard(
                "Level " + targetLevel + " (JSON, kompatibel mit tools/level_editor.py):\n" + toJson().trim() + "\n");
        callbacks.showToast("Level-JSON in die Zwischenablage kopiert.");
    }

    // "Probespielen": hands the current grid content -- unsaved edits included -- off to Game for
    // real, normal gameplay, without needing to "Speichern"/"Exportieren" first. Doesn't touch
    // dirty/mode/blocks: Game.endTestPlay() (triggered via its own burger menu) brings the player
    // straight back to this same editor state once done.
    private void onTestPlay() {
        callbacks.startTestPlay(toJson(), targetLevel);
    }

    // ---- geometry ----

    // No inverse of GameBoard.getBlockX/Y() is exposed, so this just scans the (small, fixed)
    // column/row count -- cheap enough to do on every touch.
    private int cellXAt(float touchX) {
        for (int x = 0; x < GRID_COLS; x++) {
            if (touchX >= geometry.getBlockX(x) && touchX < geometry.getBlockX(x + 1)) {
                return x;
            }
        }
        return -1;
    }

    private int cellYAt(float touchY) {
        for (int y = 0; y < GRID_ROWS; y++) {
            if (touchY >= geometry.getBlockY(y) && touchY < geometry.getBlockY(y + 1)) {
                return y;
            }
        }
        return -1;
    }

    private float gridBottom() {
        return geometry.getBlockY(GRID_ROWS);
    }

    private float gridLeft() {
        return geometry.getBlockX(0);
    }

    private float gridRight() {
        return geometry.getBlockX(GRID_COLS);
    }

    RectF paletteRowRect() {
        float top = gridBottom() + PALETTE_TOP_MARGIN;
        return new RectF(gridLeft(), top, gridRight(), top + ROW_HEIGHT);
    }

    RectF shapeSwatchRect(int index) {
        RectF row = paletteRowRect();
        int n = BlockShape.values().length;
        float gap = ROW_GAP;
        float btnWidth = (row.width() - (n - 1) * gap) / n;
        float left = row.left + index * (btnWidth + gap);
        return new RectF(left, row.top, left + btnWidth, row.bottom);
    }

    RectF stepperRowRect() {
        RectF shapeRow = paletteRowRect();
        float top = shapeRow.bottom + ROW_GAP;
        return new RectF(shapeRow.left, top, shapeRow.right, top + ROW_HEIGHT);
    }

    RectF stepperMinusRect() {
        RectF row = stepperRowRect();
        float thirdWidth = row.width() / 3f;
        return new RectF(row.left, row.top, row.left + thirdWidth, row.bottom);
    }

    RectF stepperValueRect() {
        RectF row = stepperRowRect();
        float thirdWidth = row.width() / 3f;
        return new RectF(row.left + thirdWidth, row.top, row.left + 2 * thirdWidth, row.bottom);
    }

    RectF stepperPlusRect() {
        RectF row = stepperRowRect();
        float thirdWidth = row.width() / 3f;
        return new RectF(row.left + 2 * thirdWidth, row.top, row.right, row.bottom);
    }

    RectF allValuesRowRect() {
        RectF stepperRow = stepperRowRect();
        float top = stepperRow.bottom + ROW_GAP;
        return new RectF(stepperRow.left, top, stepperRow.right, top + ROW_HEIGHT);
    }

    RectF allValuesButtonRect(int index) {
        RectF row = allValuesRowRect();
        int n = 2;
        float gap = ROW_GAP;
        float btnWidth = (row.width() - (n - 1) * gap) / n;
        float left = row.left + index * (btnWidth + gap);
        return new RectF(left, row.top, left + btnWidth, row.bottom);
    }

    RectF shiftRowRect() {
        RectF valuesRow = allValuesRowRect();
        float top = valuesRow.bottom + ROW_GAP;
        return new RectF(valuesRow.left, top, valuesRow.right, top + ROW_HEIGHT);
    }

    RectF shiftButtonRect(int index) {
        RectF row = shiftRowRect();
        int n = 4;
        float gap = ROW_GAP;
        float btnWidth = (row.width() - (n - 1) * gap) / n;
        float left = row.left + index * (btnWidth + gap);
        return new RectF(left, row.top, left + btnWidth, row.bottom);
    }

    RectF actionRowRect() {
        RectF shiftRow = shiftRowRect();
        float top = shiftRow.bottom + ROW_GAP;
        return new RectF(shiftRow.left, top, shiftRow.right, top + ROW_HEIGHT);
    }

    RectF actionButtonRect(int index) {
        RectF row = actionRowRect();
        int n = 4;
        float gap = ROW_GAP;
        float btnWidth = (row.width() - (n - 1) * gap) / n;
        float left = row.left + index * (btnWidth + gap);
        return new RectF(left, row.top, left + btnWidth, row.bottom);
    }

    // ---- drawing ----

    public void draw(Canvas c) {
        c.drawColor(Color.BLACK);
        c.drawText(titleText(), 30f, 60f, titlePaint);
        drawGrid(c);
        drawShapePalette(c);
        drawValueStepper(c);
        drawAllValuesRow(c);
        drawShiftRow(c);
        drawActionRow(c);
    }

    private String titleText() {
        switch (mode) {
            case APPEND:
                return "Level-Editor - neues Level " + targetLevel + " (anhaengen)";
            case INSERT:
                return "Level-Editor - neues Level " + targetLevel + " (einfuegen)";
            default:
                return "Level-Editor - Level " + targetLevel + " bearbeiten";
        }
    }

    private void drawGrid(Canvas c) {
        for (int y = 0; y < GRID_ROWS; y++) {
            for (int x = 0; x < GRID_COLS; x++) {
                Block b = blocks[x][y];
                if (b != null) {
                    b.draw(c);
                } else {
                    float left = geometry.getBlockX(x);
                    float top = geometry.getBlockY(y);
                    c.drawRect(left, top, left + geometry.getBlockWidth(), top + geometry.getBlockHeight(), emptyCellPaint);
                }
            }
        }
    }

    private void drawShapePalette(Canvas c) {
        BlockShape[] shapes = BlockShape.values();
        int previewColor = previewColor(selectedValue);
        for (int i = 0; i < shapes.length; i++) {
            RectF box = shapeSwatchRect(i);
            drawShapeGlyph(c, shapes[i], box, previewColor);
            if (shapes[i] == selectedShape) {
                c.drawRect(box, selectedBorderPaint);
            }
        }
    }

    private void drawShapeGlyph(Canvas c, BlockShape shape, RectF box, int fillColor) {
        Paint fill = new Paint();
        fill.setColor(fillColor);
        fill.setStyle(Paint.Style.FILL);
        fill.setAntiAlias(true);
        if (shape == BlockShape.SQUARE) {
            c.drawRoundRect(box, 10, 10, fill);
            c.drawRoundRect(box, 10, 10, swatchStrokePaint);
            return;
        }
        android.graphics.Path path = new android.graphics.Path();
        float l = box.left, t = box.top, r = box.right, b = box.bottom;
        switch (shape) {
            case BL:
                path.moveTo(l, b);
                path.lineTo(l, t);
                path.lineTo(r, b);
                break;
            case TL:
                path.moveTo(l, t);
                path.lineTo(r, t);
                path.lineTo(l, b);
                break;
            case TR:
                path.moveTo(r, t);
                path.lineTo(r, b);
                path.lineTo(l, t);
                break;
            case BR:
                path.moveTo(r, b);
                path.lineTo(l, b);
                path.lineTo(r, t);
                break;
            default:
                break;
        }
        path.close();
        c.drawPath(path, fill);
        c.drawPath(path, swatchStrokePaint);
    }

    // Matches Block.getRectColorFromValue()'s hue formula so the palette preview shows the same
    // color the placed block will actually get.
    private int previewColor(int value) {
        float hue = (value * 24f) % 360f;
        return Color.HSVToColor(new float[]{hue, 0.65f, 0.90f});
    }

    private void drawValueStepper(Canvas c) {
        RectF minus = stepperMinusRect();
        RectF value = stepperValueRect();
        RectF plus = stepperPlusRect();
        c.drawRoundRect(minus, 16, 16, actionButtonPaint);
        c.drawRoundRect(plus, 16, 16, actionButtonPaint);
        c.drawText("-", minus.centerX(), minus.centerY() + 16, stepperTextPaint);
        c.drawText("+", plus.centerX(), plus.centerY() + 16, stepperTextPaint);
        c.drawText("Wert: " + selectedValue, value.centerX(), value.centerY() + 16, stepperValuePaint);
    }

    private void drawAllValuesRow(Canvas c) {
        String[] labels = {"Alle Werte -1", "Alle Werte +1"};
        for (int i = 0; i < labels.length; i++) {
            RectF box = allValuesButtonRect(i);
            c.drawRoundRect(box, 16, 16, actionButtonPaint);
            c.drawText(labels[i], box.centerX(), box.centerY() + 12, actionButtonTextPaint);
        }
    }

    private void drawShiftRow(Canvas c) {
        String[] labels = {"←", "→", "↑", "↓"};
        for (int i = 0; i < labels.length; i++) {
            RectF box = shiftButtonRect(i);
            c.drawRoundRect(box, 16, 16, actionButtonPaint);
            c.drawText(labels[i], box.centerX(), box.centerY() + 12, actionButtonTextPaint);
        }
    }

    private void drawActionRow(Canvas c) {
        String[] labels = {"Speichern", "Exportieren", "Probespielen", "Schliessen"};
        for (int i = 0; i < labels.length; i++) {
            RectF box = actionButtonRect(i);
            c.drawRoundRect(box, 16, 16, actionButtonPaint);
            c.drawText(labels[i], box.centerX(), box.centerY() + 12, actionButtonTextPaint);
        }
    }

    // ---- accessors (also used by tests) ----

    public boolean isDirty() {
        return dirty;
    }

    public Mode getMode() {
        return mode;
    }

    public int getTargetLevel() {
        return targetLevel;
    }

    public BlockShape getSelectedShape() {
        return selectedShape;
    }

    public int getSelectedValue() {
        return selectedValue;
    }

    Block getBlockForTests(int x, int y) {
        return blocks[x][y];
    }
}
