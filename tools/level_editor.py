#!/usr/bin/env python3
"""GUI editor for BlockPong level JSON files, with image tracing built in.

Two ways to use it:

1. GUI (default): draw a level by hand on an 11-wide, 20-row grid (only the
   top 16 rows are actually loaded in-game -- the canvas marks that boundary),
   or import a GIF/PNG and trace it into blocks, then keep hand-editing the
   result before saving. Click a cell to select it, then use the arrow keys
   to move the selection, 1-9 to set its value, Shift+Left/Right to cycle its
   block type, and Backspace to delete it.
       python tools/level_editor.py                  # blank canvas
       python tools/level_editor.py level3.json       # open an existing level
       python tools/level_editor.py logo.png          # trace an image, then edit it

2. Headless batch conversion (no window), same as the old gif_to_level.py:
       python tools/level_editor.py input.gif app/src/main/assets/levels/level3.json
       python tools/level_editor.py input.gif --level 3
       python tools/level_editor.py logo.png out.json --method max --rows 8 --value-max 15

The image-tracing logic (downsample onto the board's grid, merge each cell's
pixels into one color, map brightness to a block value) only ever produces
square blocks -- detecting triangular regions in arbitrary raster art reliably
is a much harder problem. Use the GUI to add/replace triangle blocks by hand
after tracing.
"""

import argparse
import colorsys
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import tkinter as tk
from pathlib import Path
from tkinter import colorchooser, filedialog, messagebox, simpledialog, ttk

from PIL import Image, ImageGrab

BOARD_COLS = 11
MAX_PLAYABLE_ROWS = 16  # GameBoard reserves the bottom 2 rows of its 18-row board.
EDITOR_ROWS = 20  # Editor's drawable grid height. Larger than MAX_PLAYABLE_ROWS on purpose --
                   # GameBoard still only loads the top MAX_PLAYABLE_ROWS rows, so blocks placed
                   # below that line won't appear in-game; the canvas marks that boundary.
CELL_SIZE = 44

# "Mini-Bloecke" levels (see is_mini_block_level_slot()) use GameBoard's finer grid -- see
# GameBoard.MINI_X_DIM/MINI_Y_DIM in the app. MINI_MAX_PLAYABLE_ROWS mirrors MAX_PLAYABLE_ROWS'
# "-2" convention (authored levels never populate the bottom two rows); MINI_EDITOR_ROWS keeps the
# same +4 rows of below-the-boundary drawing slack as EDITOR_ROWS does over MAX_PLAYABLE_ROWS.
MINI_BOARD_COLS = 27
MINI_MAX_PLAYABLE_ROWS = 42
MINI_EDITOR_ROWS = 46
# Mini-Bloecke block values are capped at a single digit (see GameBoard.MINI_MAX_VALUE) so the
# number painted on a block stays legible at this finer grid's smaller cell size.
MINI_MAX_VALUE = 9

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS_LEVELS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "levels"

TRIANGLE_TYPES = ("tl", "tr", "bl", "br")
BLOCK_TYPES = ("square",) + TRIANGLE_TYPES

LEVEL_FILE_RE = re.compile(r"level(\d+)\.json$", re.IGNORECASE)

# Matches Game.writeExportFile()'s target on the device (app-specific external storage, readable
# via `adb pull` without root as long as USB debugging is on) -- see
# LevelOverviewWindow._import_from_phone() below.
ANDROID_PACKAGE = "com.jrgames.blockpong"
REMOTE_EXPORT_PATH = f"/sdcard/Android/data/{ANDROID_PACKAGE}/files/level_export.txt"


def _level_sort_key(path):
    m = LEVEL_FILE_RE.match(path.name)
    return (0, int(m.group(1))) if m else (1, path.name.lower())


# ---------------------------------------------------------------------------
# adb helpers for "Import from Phone" -- pulls Game.writeExportFile()'s mirror of whatever was
# last copied via the app's "Exportieren"/"Alle Level exportieren", so a level built on the phone
# can be pulled over USB without any manual copy/paste or clipboard-sync app.

def _find_adb():
    exe = shutil.which("adb")
    if exe:
        return exe
    for var in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = os.environ.get(var)
        if not root:
            continue
        candidate = Path(root) / "platform-tools" / ("adb.exe" if sys.platform == "win32" else "adb")
        if candidate.exists():
            return str(candidate)
    return None


def _adb_list_devices(adb_exe):
    result = subprocess.run([adb_exe, "devices"], capture_output=True, text=True, timeout=10)
    devices = []
    for line in result.stdout.splitlines()[1:]:
        line = line.strip()
        if not line or "\t" not in line:
            continue
        serial, state = line.split("\t", 1)
        if state.strip() == "device":
            devices.append(serial)
    return devices


def _choose_adb_device(parent, devices):
    dialog = tk.Toplevel(parent)
    dialog.title("Geraet waehlen")
    dialog.transient(parent)
    dialog.grab_set()
    ttk.Label(dialog, text="Mehrere Geraete gefunden -- welches?").pack(anchor="w", padx=10, pady=(10, 4))
    choice = {"serial": None}

    def pick(serial):
        choice["serial"] = serial
        dialog.destroy()

    for serial in devices:
        ttk.Button(dialog, text=serial, command=lambda s=serial: pick(s)).pack(fill="x", padx=10, pady=2)
    ttk.Button(dialog, text="Abbrechen", command=dialog.destroy).pack(fill="x", padx=10, pady=(4, 10))
    dialog.wait_window()
    return choice["serial"]


def _adb_pull_export_text(parent):
    """Runs `adb pull` for REMOTE_EXPORT_PATH and returns its text, or None (with an error dialog
    already shown) if adb/the device/the file aren't available."""
    adb_exe = _find_adb()
    if not adb_exe:
        messagebox.showerror(
            "Import from Phone",
            "adb wurde nicht gefunden. Android SDK Platform-Tools installieren und in PATH, "
            "ANDROID_SDK_ROOT oder ANDROID_HOME verfuegbar machen.",
            parent=parent)
        return None

    try:
        devices = _adb_list_devices(adb_exe)
    except (OSError, subprocess.SubprocessError) as e:
        messagebox.showerror("Import from Phone", f"adb devices fehlgeschlagen: {e}", parent=parent)
        return None
    if not devices:
        messagebox.showerror(
            "Import from Phone",
            "Kein Geraet gefunden. Handy per USB anschliessen, USB-Debugging aktivieren und den "
            "Verbindungsdialog auf dem Handy bestaetigen.",
            parent=parent)
        return None

    serial = devices[0]
    if len(devices) > 1:
        serial = _choose_adb_device(parent, devices)
        if serial is None:
            return None

    with tempfile.TemporaryDirectory() as tmp:
        local_path = Path(tmp) / "level_export.txt"
        try:
            result = subprocess.run(
                [adb_exe, "-s", serial, "pull", REMOTE_EXPORT_PATH, str(local_path)],
                capture_output=True, text=True, timeout=20)
        except (OSError, subprocess.SubprocessError) as e:
            messagebox.showerror("Import from Phone", f"adb pull fehlgeschlagen: {e}", parent=parent)
            return None
        if result.returncode != 0 or not local_path.exists():
            messagebox.showerror(
                "Import from Phone",
                "Konnte die Export-Datei nicht vom Handy holen. In der App zuerst im "
                "Level-Editor \"Exportieren\" oder \"Alle Level exportieren\" antippen, dann hier "
                "erneut versuchen.\n\n" + (result.stderr.strip() or result.stdout.strip()),
                parent=parent)
            return None
        return local_path.read_text(encoding="utf-8")


# Matches the "Spielfeld vor/nach dem Zug" labels GameBoard.getLastMoveReport() prints right
# before each embedded JSON block, so pasted debug-export text can be labeled meaningfully.
SNAPSHOT_LABEL_RE = re.compile(r"Spielfeld (vor|nach) dem Zug[^\n]*|Level \d+ \(JSON[^\n]*")

# Matches the "Level N" prefix of the per-level label the app's "Alle Level exportieren" export
# uses (see exportAllLevels() in Game.java), to recover which level number a pasted block belongs
# to -- as opposed to a "Spielfeld vor/nach dem Zug" move-debug label, which isn't a whole level.
LEVEL_LABEL_RE = re.compile(r"^Level (\d+)\b")


def extract_json_blocks_objects(text):
    """Finds every {"blocks": [...]} JSON object embedded in free-form text -- e.g. a whole
    "Letzten Zug exportieren (Debug)" report pasted verbatim, which may contain one or two such
    objects (before/after the move) plus surrounding prose. Returns a list of (label, blocks)
    tuples in the order they appear."""
    results = []
    decoder = json.JSONDecoder()
    idx = 0
    while True:
        start = text.find("{", idx)
        if start == -1:
            break
        try:
            obj, end = decoder.raw_decode(text, start)
        except json.JSONDecodeError:
            idx = start + 1
            continue
        if isinstance(obj, dict) and isinstance(obj.get("blocks"), list):
            preceding_labels = list(SNAPSHOT_LABEL_RE.finditer(text[:start]))
            label = preceding_labels[-1].group().rstrip(":") if preceding_labels else f"JSON object #{len(results) + 1}"
            results.append((label, obj["blocks"]))
        idx = max(end, start + 1)
    return results


# Each entry: (display label, regex over the report text, capture group index). Purely
# informational for the paste-and-compare viewer -- missing fields are just omitted, so this
# also degrades gracefully if only raw JSON (no report text) was pasted.
REPORT_METADATA_FIELDS = [
    ("Level", re.compile(r"Level:\s*(\S+)"), 1),
    ("Baelle", re.compile(r"Baelle:\s*(\S+)"), 1),
    ("Boni", re.compile(r"Angewendete Boni:\s*(.+)"), 1),
    ("Startpunkt x", re.compile(r"Startpunkt x:\s*([^\n(]+)"), 1),
    ("Winkel", re.compile(r"Winkel[^:]*:\s*(\S+)"), 1),
]


def extract_report_metadata(text):
    """Pulls the handful of informational fields (level, ball count, bonuses, start x, angle)
    out of a pasted "Letzten Zug exportieren (Debug)" report, for display alongside the board
    comparison. Returns an ordered dict-like list of (label, value) pairs; fields not found in
    the text are simply left out."""
    found = []
    for label, pattern, group in REPORT_METADATA_FIELDS:
        m = pattern.search(text)
        if m:
            found.append((label, m.group(group).strip()))
    return found


# ---------------------------------------------------------------------------
# Image -> blocks conversion (unchanged from the original gif_to_level.py).
# Shared by the headless CLI mode and the GUI's "Import Image..." dialog.
# ---------------------------------------------------------------------------

def luminance(rgb):
    r, g, b = rgb
    return 0.299 * r + 0.587 * g + 0.114 * b


def load_frame(path, frame_index):
    img = Image.open(path)
    try:
        img.seek(frame_index)
    except EOFError:
        raise SystemExit(f"Frame {frame_index} doesn't exist in {path} (it has {img.n_frames} frame(s))")
    return img.convert("RGBA")


def detect_background_color(img):
    """No alpha channel to key off of -- sample the four corners and use the most common color."""
    w, h = img.size
    corners = [img.getpixel((0, 0)), img.getpixel((w - 1, 0)), img.getpixel((0, h - 1)), img.getpixel((w - 1, h - 1))]
    return max(set(corners), key=corners.count)


def is_background(rgba, has_alpha, bg_color, bg_tolerance):
    r, g, b, a = rgba
    if has_alpha and a < 128:
        return True
    if bg_color is not None:
        dr, dg, db = r - bg_color[0], g - bg_color[1], b - bg_color[2]
        return (dr * dr + dg * dg + db * db) ** 0.5 <= bg_tolerance
    return False


def cell_pixel_range(grid_index, grid_size, image_size):
    lo = grid_index * image_size // grid_size
    hi = (grid_index + 1) * image_size // grid_size
    return lo, max(hi, lo + 1)


def merge_cell_color(pixels, method):
    if method == "avg":
        n = len(pixels)
        r = sum(p[0] for p in pixels) / n
        g = sum(p[1] for p in pixels) / n
        b = sum(p[2] for p in pixels) / n
        return (r, g, b)
    # "max": the single brightest constituent pixel, so a bold highlight in an
    # otherwise dim region still comes through instead of being averaged away.
    return max(((p[0], p[1], p[2]) for p in pixels), key=luminance)


def convert(image_path, cols, rows, method, value_min, value_max, frame_index, bg_color_hex, bg_tolerance,
            max_playable_rows=MAX_PLAYABLE_ROWS):
    img = load_frame(image_path, frame_index)
    has_alpha = "A" in Image.open(image_path).convert("RGBA").getbands() and img.getextrema()[3][0] < 255

    if bg_color_hex:
        bg_color = tuple(int(bg_color_hex[i:i + 2], 16) for i in (0, 2, 4))
    elif not has_alpha:
        bg_color = detect_background_color(img)
        print(f"No transparency found; treating corner color {bg_color} as background.", file=sys.stderr)
    else:
        bg_color = None

    w, h = img.size
    if rows is None:
        rows = max(1, round(cols * h / w))
    if rows > max_playable_rows:
        print(f"Warning: {rows} rows requested, but only the top {max_playable_rows} are playable "
              f"(GameBoard reserves the bottom 2 rows) -- clipping to {max_playable_rows}.", file=sys.stderr)
        rows = max_playable_rows

    pixels = img.load()
    blocks = []
    grid = []
    for gy in range(rows):
        y0, y1 = cell_pixel_range(gy, rows, h)
        grid_row = []
        for gx in range(cols):
            x0, x1 = cell_pixel_range(gx, cols, w)
            cell_pixels = [pixels[x, y] for y in range(y0, y1) for x in range(x0, x1)]

            avg_rgba = tuple(sum(c[i] for c in cell_pixels) / len(cell_pixels) for i in range(4))
            if is_background(avg_rgba, has_alpha, bg_color, bg_tolerance):
                grid_row.append(" ")
                continue

            color = merge_cell_color(cell_pixels, method)
            brightness = luminance(color) / 255.0
            value = round(value_min + brightness * (value_max - value_min))
            value = max(value_min, min(value_max, value))

            blocks.append({"x": gx, "y": gy, "type": "square", "value": value})
            grid_row.append(str(value % 10))
        grid.append("".join(grid_row))

    return blocks, grid, rows


def is_random_level_slot(n):
    """Every 10th level is deliberately left without a level file so GameBoard.randomBoard()
    generates it -- see Game.isRandomLevelSlot() in the app. Never editable/generatable here."""
    return n % 10 == 0


def is_mini_block_level_slot(n):
    """Every 5th-not-10th level uses GameBoard's finer "Mini-Bloecke" grid/smaller ball (see
    Game.isMiniBlockLevelSlot() in the app) -- unlike is_random_level_slot(), this is a perfectly
    normal, authorable level, just always edited/rendered on the bigger grid regardless of what's
    currently stored there."""
    return n % 5 == 0 and n % 10 != 0


def resolve_output_path(args):
    if args.level is not None:
        if is_random_level_slot(args.level):
            raise SystemExit(f"Level {args.level} is a random-level slot (every 10th level) and can't be authored.")
        return ASSETS_LEVELS_DIR / f"level{args.level}.json"
    if args.output is None:
        raise SystemExit("Specify either an output path or --level N")
    return Path(args.output)


# ---------------------------------------------------------------------------
# Color helpers shared with the GUI -- mirrors Block.java's HSV formula
# (Block.getRectColorFromValue / getTextColorFromValue) so the editor's
# preview colors match what the block will actually look like in-game.
# ---------------------------------------------------------------------------

def value_to_hex_color(value):
    hue = (value * 24) % 360
    r, g, b = colorsys.hsv_to_rgb(hue / 360.0, 0.65, 0.90)
    return "#%02x%02x%02x" % (round(r * 255), round(g * 255), round(b * 255))


# Quick-pick palette shown as clickable swatches next to the custom color picker (see
# LevelEditorApp._build_layout()'s color section) -- a fixed, recognizable set rather than
# anything value-derived, for deliberately overriding a block's look independent of its value.
STANDARD_COLORS = [
    "#e74c3c", "#e67e22", "#f1c40f", "#2ecc71", "#1abc9c", "#3498db",
    "#9b59b6", "#e84393", "#ffffff", "#95a5a6", "#34495e", "#000000",
]


def effective_block_color(value, color=None):
    """A block's displayed color: an explicit override (see GameBoard.loadBlocksFromJson()'s
    optional "color" field / Block.overrideColor on the app side) if set, else the usual
    value-derived color. Value and color are otherwise fully independent -- a block's point value
    no longer dictates its look."""
    return color if color else value_to_hex_color(value)


def contrasting_text_color(hex_color):
    r = int(hex_color[1:3], 16)
    g = int(hex_color[3:5], 16)
    b = int(hex_color[5:7], 16)
    brightness = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return "black" if brightness > 0.6 else "white"


# Vertex order matches Block3.java's Path construction exactly (right angle at
# the named corner; the opposite corner is the one omitted).
def _tri_bl(l, t, r, b):
    return [l, b, l, t, r, b]


def _tri_tl(l, t, r, b):
    return [l, t, r, t, l, b]


def _tri_tr(l, t, r, b):
    return [r, t, r, b, l, t]


def _tri_br(l, t, r, b):
    return [r, b, l, b, r, t]


TRIANGLE_POINTS = {"bl": _tri_bl, "tl": _tri_tl, "tr": _tri_tr, "br": _tri_br}

COMPARE_CELL_SIZE = 28  # Smaller than the main editor's CELL_SIZE so two boards fit side by side.


def draw_blocks_static(canvas, blocks, cell_size=COMPARE_CELL_SIZE, rows=MAX_PLAYABLE_ROWS, cols=BOARD_COLS):
    """Renders a blocks list onto a plain Canvas, read-only -- no selection, no bindings. Used by
    the paste-and-compare viewer, which is for looking at a reported board state, not editing it."""
    canvas.delete("all")
    for gy in range(rows):
        for gx in range(cols):
            left, top = gx * cell_size, gy * cell_size
            canvas.create_rectangle(left, top, left + cell_size, top + cell_size, outline="#444444")

    for b in blocks:
        gx, gy, block_type, value = b["x"], b["y"], b["type"], b["value"]
        if not (0 <= gx < cols) or not (0 <= gy < rows):
            continue
        left, top = gx * cell_size, gy * cell_size
        right, bottom = left + cell_size, top + cell_size
        color = effective_block_color(value, b.get("color"))
        if block_type == "square":
            canvas.create_rectangle(left, top, right, bottom, fill=color, outline="white")
            cx, cy = (left + right) / 2, (top + bottom) / 2
        elif block_type in TRIANGLE_POINTS:
            pts = TRIANGLE_POINTS[block_type](left, top, right, bottom)
            canvas.create_polygon(pts, fill=color, outline="white")
            cx = (pts[0] + pts[2] + pts[4]) / 3
            cy = (pts[1] + pts[3] + pts[5]) / 3
        else:
            continue
        canvas.create_text(cx, cy, text=str(value), fill=contrasting_text_color(color), font=("TkDefaultFont", 7))


# ---------------------------------------------------------------------------
# GUI
# ---------------------------------------------------------------------------

class ImageImportWindow(tk.Toplevel):
    """Interactive image-to-blocks import: load an image (file or clipboard paste), then pan/zoom/
    rotate it against a live preview of the block grid and pick a background/transparent color by
    right-clicking the preview, before exporting the currently-aligned view as blocks via
    self.result -- same (blocks, rows) contract the old plain-settings dialog used, so callers
    don't need to change. The headless CLI path (convert(), used by main()/_import_image_path())
    is untouched -- this is purely an interactive alternative for the "Import Image..." menu action.

    Coordinate model: self.rotated_image is self.source_image rotated by self.rotation_deg (PIL
    rotate(expand=True, fillcolor=transparent), so corners the rotation introduces are transparent
    rather than showing rotated-away content). self.base_ppc_x/self.base_ppc_y ("pixels per grid
    column/row" -- fitting the *whole* rotated image into the grid with one uniform scale so its
    aspect ratio is kept, centered by _reset_view(); recomputed whenever the
    source image, its rotation, or the column/row count changes) divided by self.zoom_x/self.zoom_y
    (independently adjustable, see _adjust_zoom()) give the actual source-image-pixel size of one
    grid cell's sampling window on each axis; self.offset_x/self.offset_y (in rotated-image pixel
    space) is that window's top-left origin for grid cell (0, 0), shifted by dragging. Any sample
    window landing outside the rotated image's bounds is simply background/transparent -- that's
    what lets panning/zooming "move the image around" within the fixed-size grid instead of always
    covering it edge-to-edge."""

    PREVIEW_CELL_PX = 28
    ZOOM_MIN, ZOOM_MAX = 0.1, 8.0
    ZOOM_STEP = 1.15  # multiplicative, per wheel notch / +/- button click
    # Fixed default grid -- pan/zoom/rotate now let the user fit any image to it manually, so
    # there's no need to auto-size rows/cols to each image's aspect ratio anymore.
    DEFAULT_COLS, DEFAULT_ROWS = 11, 17

    def __init__(self, parent, max_cols=BOARD_COLS, max_rows=EDITOR_ROWS,
                 default_cols=None, default_rows=None, max_value=99):
        # max_cols/max_rows/default_cols/default_rows/max_value let LevelEditorApp.import_image()
        # widen the Cols/Rows spinboxes (and cap the Value spinboxes) to GameBoard's Mini-Bloecke
        # grid/single-digit values when the level currently open is a Mini-Bloecke slot --
        # otherwise an import into one could never use more than the normal board's 11 columns, or
        # would import values ("17") too wide for that grid's smaller cells to show legibly.
        super().__init__(parent)
        self.title("Import Image")
        self.transient(parent)
        self.grab_set()
        self.result = None
        self.max_cols = max_cols
        self.max_rows = max_rows
        self.max_value = max_value

        self.source_image = None   # original, unrotated RGBA PIL Image, or None until loaded
        self.rotated_image = None  # source_image rotated by rotation_deg, RGBA, expand=True
        self.base_ppc_x = 1.0
        self.base_ppc_y = 1.0
        self.zoom_x = 1.0
        self.zoom_y = 1.0
        self.rotation_deg = 0.0
        self.offset_x = 0.0
        self.offset_y = 0.0
        self._pan_last = (0, 0)

        self.cols_var = tk.IntVar(value=default_cols if default_cols is not None else self.DEFAULT_COLS)
        self.rows_var = tk.IntVar(value=default_rows if default_rows is not None else self.DEFAULT_ROWS)
        self.method_var = tk.StringVar(value="avg")
        self.value_min_var = tk.IntVar(value=1)
        self.value_max_var = tk.IntVar(value=min(20, max_value))
        self.bg_color = None  # (r, g, b) or None -- see _pick_bg_color()/_auto_bg_color()
        self.bg_tolerance_var = tk.DoubleVar(value=24.0)
        self.rotation_var = tk.DoubleVar(value=0.0)
        self.zoom_x_var = tk.StringVar(value="100%")
        self.zoom_y_var = tk.StringVar(value="100%")
        self.bg_status_var = tk.StringVar(value="Background: none picked yet")
        self.source_label_var = tk.StringVar(value="No image loaded.")

        self._build_layout()
        self._redraw_preview()

        self.cols_var.trace_add("write", lambda *a: self._on_grid_size_changed())
        self.rows_var.trace_add("write", lambda *a: self._redraw_preview())

    # -- layout ----------------------------------------------------------

    def _build_layout(self):
        main = ttk.Frame(self, padding=10)
        main.pack(fill="both", expand=True)

        top = ttk.Frame(main)
        top.pack(fill="x")
        ttk.Button(top, text="Open Image...", command=self._open_file).pack(side="left")
        ttk.Button(top, text="Paste from Clipboard", command=self._paste_clipboard).pack(side="left", padx=(6, 0))
        ttk.Label(top, textvariable=self.source_label_var, foreground="#888888").pack(side="left", padx=(10, 0))

        body = ttk.Frame(main)
        body.pack(fill="both", expand=True, pady=(8, 0))

        self.canvas = tk.Canvas(
            body, width=self.cols_var.get() * self.PREVIEW_CELL_PX,
            height=self.rows_var.get() * self.PREVIEW_CELL_PX,
            background="#1e1e1e", highlightthickness=1, highlightbackground="#666666")
        self.canvas.grid(row=0, column=0)
        self.canvas.bind("<Button-1>", self._on_pan_start)
        self.canvas.bind("<B1-Motion>", self._on_pan_drag)
        self.canvas.bind("<Button-3>", self._pick_bg_color)
        self.canvas.bind("<MouseWheel>", lambda e: self._on_wheel_zoom(e, "y"))
        self.canvas.bind("<Button-4>", lambda e: self._on_wheel_zoom(e, "y"))
        self.canvas.bind("<Button-5>", lambda e: self._on_wheel_zoom(e, "y"))
        self.canvas.bind("<Shift-MouseWheel>", lambda e: self._on_wheel_zoom(e, "x"))
        self.canvas.bind("<Shift-Button-4>", lambda e: self._on_wheel_zoom(e, "x"))
        self.canvas.bind("<Shift-Button-5>", lambda e: self._on_wheel_zoom(e, "x"))

        side = ttk.Frame(body, padding=(10, 0))
        side.grid(row=0, column=1, sticky="n")

        ttk.Label(side, text="Grid size", font=("TkDefaultFont", 9, "bold")).pack(anchor="w")
        grid_row = ttk.Frame(side)
        grid_row.pack(anchor="w", pady=(0, 6))
        ttk.Label(grid_row, text="Cols:").pack(side="left")
        ttk.Spinbox(grid_row, from_=1, to=self.max_cols, textvariable=self.cols_var, width=5).pack(side="left", padx=(2, 8))
        ttk.Label(grid_row, text="Rows:").pack(side="left")
        ttk.Spinbox(grid_row, from_=1, to=self.max_rows, textvariable=self.rows_var, width=5).pack(side="left", padx=(2, 0))

        ttk.Label(side, text="Zoom", font=("TkDefaultFont", 9, "bold")).pack(anchor="w", pady=(6, 0))
        zoom_x_row = ttk.Frame(side)
        zoom_x_row.pack(anchor="w")
        ttk.Label(zoom_x_row, text="X:", width=2).pack(side="left")
        ttk.Button(zoom_x_row, text="-", width=3, command=lambda: self._adjust_zoom("x", 1 / self.ZOOM_STEP)).pack(side="left")
        ttk.Label(zoom_x_row, textvariable=self.zoom_x_var, width=6, anchor="center").pack(side="left")
        ttk.Button(zoom_x_row, text="+", width=3, command=lambda: self._adjust_zoom("x", self.ZOOM_STEP)).pack(side="left")
        zoom_y_row = ttk.Frame(side)
        zoom_y_row.pack(anchor="w")
        ttk.Label(zoom_y_row, text="Y:", width=2).pack(side="left")
        ttk.Button(zoom_y_row, text="-", width=3, command=lambda: self._adjust_zoom("y", 1 / self.ZOOM_STEP)).pack(side="left")
        ttk.Label(zoom_y_row, textvariable=self.zoom_y_var, width=6, anchor="center").pack(side="left")
        ttk.Button(zoom_y_row, text="+", width=3, command=lambda: self._adjust_zoom("y", self.ZOOM_STEP)).pack(side="left")
        ttk.Button(side, text="Reset view", command=self._reset_view).pack(anchor="w", pady=(2, 0))

        ttk.Label(side, text="Rotate", font=("TkDefaultFont", 9, "bold")).pack(anchor="w", pady=(6, 0))
        rotate_row = ttk.Frame(side)
        rotate_row.pack(anchor="w")
        ttk.Button(rotate_row, text="⟲ 90°", width=6, command=lambda: self._nudge_rotation(-90)).pack(side="left")
        ttk.Button(rotate_row, text="⟳ 90°", width=6, command=lambda: self._nudge_rotation(90)).pack(side="left", padx=(2, 0))
        ttk.Scale(side, from_=-180, to=180, variable=self.rotation_var, orient="horizontal",
                  command=self._on_rotation_changed).pack(fill="x", pady=(4, 0))

        ttk.Label(side, text="Background color", font=("TkDefaultFont", 9, "bold")).pack(anchor="w", pady=(6, 0))
        ttk.Label(side, textvariable=self.bg_status_var, foreground="#888888", wraplength=170,
                  justify="left").pack(anchor="w")
        ttk.Button(side, text="Auto-detect (corners)", command=self._auto_bg_color).pack(fill="x", pady=(2, 0))
        ttk.Label(side, text="Tolerance:").pack(anchor="w", pady=(4, 0))
        ttk.Spinbox(side, from_=0, to=255, textvariable=self.bg_tolerance_var, width=6,
                    command=self._redraw_preview).pack(anchor="w")

        ttk.Label(side, text="Value range", font=("TkDefaultFont", 9, "bold")).pack(anchor="w", pady=(6, 0))
        value_row = ttk.Frame(side)
        value_row.pack(anchor="w")
        ttk.Spinbox(value_row, from_=0, to=self.max_value, textvariable=self.value_min_var, width=5,
                    command=self._redraw_preview).pack(side="left")
        ttk.Label(value_row, text="to").pack(side="left", padx=4)
        ttk.Spinbox(value_row, from_=0, to=self.max_value, textvariable=self.value_max_var, width=5,
                    command=self._redraw_preview).pack(side="left")

        ttk.Label(side, text="Merge method:").pack(anchor="w", pady=(6, 0))
        ttk.Combobox(side, textvariable=self.method_var, values=["avg", "max"], width=6,
                     state="readonly").pack(anchor="w")
        self.method_var.trace_add("write", lambda *a: self._redraw_preview())
        self.bg_tolerance_var.trace_add("write", lambda *a: self._redraw_preview())
        self.value_min_var.trace_add("write", lambda *a: self._redraw_preview())
        self.value_max_var.trace_add("write", lambda *a: self._redraw_preview())

        ttk.Label(side, text=(
            "Left-drag: move image\nRight-click: pick background color\n"
            "Wheel / Y +/-: zoom Y\nShift+Wheel / X +/-: zoom X"
        ), justify="left", foreground="#888888").pack(anchor="w", pady=(12, 0))

        btns = ttk.Frame(main)
        btns.pack(fill="x", pady=(10, 0))
        ttk.Button(btns, text="Export as Level", command=self._do_export).pack(side="left")
        ttk.Button(btns, text="Cancel", command=self.destroy).pack(side="left", padx=(6, 0))

    # -- image source ------------------------------------------------------

    def _open_file(self):
        path = filedialog.askopenfilename(
            parent=self,
            filetypes=[("Images", "*.gif *.png *.jpg *.jpeg *.bmp"), ("All files", "*.*")],
        )
        if not path:
            return
        try:
            img = Image.open(path).convert("RGBA")
        except Exception as e:
            messagebox.showerror("Import Image", f"Could not open image:\n{e}", parent=self)
            return
        self._set_source_image(img, Path(path).name)

    def _paste_clipboard(self):
        try:
            data = ImageGrab.grabclipboard()
        except Exception as e:
            messagebox.showerror("Import Image", f"Could not read the clipboard:\n{e}", parent=self)
            return
        if data is None:
            messagebox.showerror("Import Image", "Clipboard has no image.", parent=self)
            return
        if isinstance(data, list):
            # Windows Explorer "copy" of one or more files, rather than actual image bytes.
            if not data:
                messagebox.showerror("Import Image", "Clipboard has no image.", parent=self)
                return
            try:
                img = Image.open(data[0]).convert("RGBA")
            except Exception as e:
                messagebox.showerror("Import Image", f"Could not open clipboard file:\n{e}", parent=self)
                return
            self._set_source_image(img, Path(data[0]).name)
        else:
            self._set_source_image(data.convert("RGBA"), "(clipboard)")

    def _set_source_image(self, img, label):
        self.source_image = img
        self.source_label_var.set(f"{label} ({img.width}x{img.height})")
        self.bg_color = None
        self.bg_status_var.set("Background: none picked yet")
        # Grid size (cols/rows) is no longer auto-computed from the image's aspect ratio -- with
        # pan/zoom/rotate available, whatever grid the user currently has stays put across loads;
        # only construction sets DEFAULT_COLS/DEFAULT_ROWS.
        self._reset_view()

    # -- view transform: rotate / zoom / pan --------------------------------

    def _reset_view(self):
        self.zoom_x = 1.0
        self.zoom_y = 1.0
        self.zoom_x_var.set("100%")
        self.zoom_y_var.set("100%")
        self.rotation_deg = 0.0
        self.rotation_var.set(0.0)
        self.offset_x = 0.0
        self.offset_y = 0.0
        self._rebuild_rotated_image()
        self._center_image()

    def _center_image(self):
        # Center the fitted image in the grid -- with a uniform scale (see _rebuild_rotated_image())
        # one axis generally has slack, which would otherwise all end up on the right/bottom.
        if self.rotated_image is None:
            return
        self.offset_x = (self.rotated_image.width - self.cols_var.get() * self._current_ppc_x()) / 2
        self.offset_y = (self.rotated_image.height - self.rows_var.get() * self._current_ppc_y()) / 2
        self._redraw_preview()

    def _rebuild_rotated_image(self):
        if self.source_image is None:
            self.rotated_image = None
            self._redraw_preview()
            return
        if self.rotation_deg % 360 == 0:
            self.rotated_image = self.source_image
        else:
            self.rotated_image = self.source_image.rotate(
                self.rotation_deg, expand=True, resample=Image.BICUBIC, fillcolor=(0, 0, 0, 0))
        # Uniform scale on both axes ("contain" fit): board cells are square in-game (GameBoard's
        # blockHeight = blockWidth), so this keeps the image's original aspect ratio -- up to
        # whole-cell rounding -- instead of stretching it to the grid. zoom_x/zoom_y can still
        # distort it deliberately.
        ppc = max(1.0, self.rotated_image.width / self.cols_var.get(),
                  self.rotated_image.height / self.rows_var.get())
        self.base_ppc_x = ppc
        self.base_ppc_y = ppc
        self._redraw_preview()

    def _on_grid_size_changed(self):
        # Column/row count redefines what base_ppc_x/base_ppc_y ("pixels per column/row") mean --
        # rebuild against them.
        self._rebuild_rotated_image()

    def _nudge_rotation(self, delta):
        self.rotation_var.set((self.rotation_var.get() + delta + 180) % 360 - 180)
        self._on_rotation_changed()

    def _on_rotation_changed(self, *_args):
        self.rotation_deg = self.rotation_var.get()
        self._rebuild_rotated_image()

    def _adjust_zoom(self, axis, factor):
        if axis == "x":
            self.zoom_x = max(self.ZOOM_MIN, min(self.ZOOM_MAX, self.zoom_x * factor))
            self.zoom_x_var.set(f"{round(self.zoom_x * 100)}%")
        else:
            self.zoom_y = max(self.ZOOM_MIN, min(self.ZOOM_MAX, self.zoom_y * factor))
            self.zoom_y_var.set(f"{round(self.zoom_y * 100)}%")
        self._redraw_preview()

    def _on_wheel_zoom(self, event, axis):
        num = getattr(event, "num", None)
        if num == 4:
            direction = 1
        elif num == 5:
            direction = -1
        else:
            direction = 1 if event.delta > 0 else -1
        self._adjust_zoom(axis, self.ZOOM_STEP if direction > 0 else 1 / self.ZOOM_STEP)

    def _current_ppc_x(self):
        return max(0.001, self.base_ppc_x / self.zoom_x)

    def _current_ppc_y(self):
        return max(0.001, self.base_ppc_y / self.zoom_y)

    def _on_pan_start(self, event):
        self._pan_last = (event.x, event.y)

    def _on_pan_drag(self, event):
        if self.rotated_image is None:
            return
        last_x, last_y = self._pan_last
        dx_preview, dy_preview = event.x - last_x, event.y - last_y
        self._pan_last = (event.x, event.y)
        # Direct-manipulation feel (like dragging a photo with a finger): dragging right should
        # make the image appear to move right, which means the sampling window's origin moves the
        # opposite way in source-image space. X/Y scales are independent since zoom_x/zoom_y are.
        scale_x = self.PREVIEW_CELL_PX / self._current_ppc_x()  # preview px per source px
        scale_y = self.PREVIEW_CELL_PX / self._current_ppc_y()
        self.offset_x -= dx_preview / scale_x
        self.offset_y -= dy_preview / scale_y
        self._redraw_preview()

    # -- background color pick ----------------------------------------------

    def _pick_bg_color(self, event):
        if self.rotated_image is None:
            return
        gx, gy = event.x // self.PREVIEW_CELL_PX, event.y // self.PREVIEW_CELL_PX
        ix = int(self.offset_x + (gx + 0.5) * self._current_ppc_x())
        iy = int(self.offset_y + (gy + 0.5) * self._current_ppc_y())
        w, h = self.rotated_image.size
        if not (0 <= ix < w and 0 <= iy < h):
            return
        r, g, b, a = self.rotated_image.getpixel((ix, iy))
        if a < 10:
            messagebox.showinfo("Import Image", "That spot is already transparent.", parent=self)
            return
        self.bg_color = (r, g, b)
        self.bg_status_var.set(f"Background: custom #{r:02x}{g:02x}{b:02x}")
        self._redraw_preview()

    def _auto_bg_color(self):
        if self.source_image is None:
            return
        r, g, b, _a = detect_background_color(self.source_image)
        self.bg_color = (r, g, b)
        self.bg_status_var.set(f"Background: auto-detected #{r:02x}{g:02x}{b:02x}")
        self._redraw_preview()

    # -- sampling / preview ---------------------------------------------------

    def _sample_grid(self):
        """Returns (blocks, cell_colors) for the current pan/zoom/rotation/background settings --
        cell_colors maps (gx, gy) -> hex color or None (background), for the preview draw. Shares
        merge_cell_color()/luminance()/is_background() with the headless convert() path, just
        windowed into rotated_image via offset_x/offset_y/ppc instead of always covering it
        edge-to-edge.

        Each block also carries its sampled color as an explicit "color" override (same field
        effective_block_color()/the level editor's color picker already understand) -- the point
        being that the imported level looks exactly like the source image right away, with the
        brightness-derived "value" only a rough starting point the user tunes by hand afterward
        (e.g. via the level editor's Bulk Value +1/-1 buttons) rather than something to get right
        here."""
        cols, rows = self.cols_var.get(), self.rows_var.get()
        ppc_x, ppc_y = self._current_ppc_x(), self._current_ppc_y()
        img = self.rotated_image
        w, h = img.size
        bg_color = self.bg_color
        value_min, value_max = self.value_min_var.get(), self.value_max_var.get()
        method = self.method_var.get()
        tolerance = self.bg_tolerance_var.get()

        blocks = []
        cell_colors = {}
        for gy in range(rows):
            y0, y1 = self.offset_y + gy * ppc_y, self.offset_y + (gy + 1) * ppc_y
            for gx in range(cols):
                x0, x1 = self.offset_x + gx * ppc_x, self.offset_x + (gx + 1) * ppc_x
                px0, px1 = max(0, int(x0)), min(w, max(int(x0) + 1, int(x1)))
                py0, py1 = max(0, int(y0)), min(h, max(int(y0) + 1, int(y1)))
                if px1 <= px0 or py1 <= py0:
                    cell_colors[(gx, gy)] = None
                    continue
                cell_pixels = [img.getpixel((x, y)) for y in range(py0, py1) for x in range(px0, px1)]
                avg_rgba = tuple(sum(c[i] for c in cell_pixels) / len(cell_pixels) for i in range(4))
                if is_background(avg_rgba, True, bg_color, tolerance):
                    cell_colors[(gx, gy)] = None
                    continue
                color = merge_cell_color(cell_pixels, method)
                brightness = luminance(color) / 255.0
                value = round(value_min + brightness * (value_max - value_min))
                value = max(value_min, min(value_max, value))
                hex_color = "#%02x%02x%02x" % tuple(round(c) for c in color)
                blocks.append({"x": gx, "y": gy, "type": "square", "value": value, "color": hex_color})
                cell_colors[(gx, gy)] = hex_color
        return blocks, cell_colors

    def _redraw_preview(self, *_args):
        cols, rows = self.cols_var.get(), self.rows_var.get()
        self.canvas.configure(width=cols * self.PREVIEW_CELL_PX, height=rows * self.PREVIEW_CELL_PX)
        self.canvas.delete("all")
        if self.rotated_image is None:
            self.canvas.create_text(
                cols * self.PREVIEW_CELL_PX / 2, rows * self.PREVIEW_CELL_PX / 2,
                text="Open an image or paste one from the clipboard", fill="#888888",
                width=cols * self.PREVIEW_CELL_PX - 20)
            return
        _blocks, cell_colors = self._sample_grid()
        for gy in range(rows):
            for gx in range(cols):
                x0, y0 = gx * self.PREVIEW_CELL_PX, gy * self.PREVIEW_CELL_PX
                x1, y1 = x0 + self.PREVIEW_CELL_PX, y0 + self.PREVIEW_CELL_PX
                color = cell_colors.get((gx, gy))
                self.canvas.create_rectangle(x0, y0, x1, y1, fill=(color or "#1e1e1e"), outline="#3a3a3a")

    def _do_export(self):
        if self.rotated_image is None:
            messagebox.showerror("Import Image", "Open an image or paste one from the clipboard first.", parent=self)
            return
        blocks, _cell_colors = self._sample_grid()
        if not blocks and not messagebox.askyesno(
                "Import Image",
                "No blocks would be created with the current view/background settings -- "
                "export an empty level anyway?", parent=self):
            return
        self.result = (blocks, self.rows_var.get())
        self.destroy()


class BoardCompareWindow(tk.Toplevel):
    """Read-only side-by-side viewer for one or more board snapshots pasted from a debug-export
    report (typically "vor"/"nach dem Zug") -- purely for visually inspecting a reported bug, not
    for editing, so it has no selection or block-editing bindings at all."""

    def __init__(self, parent, snapshots, metadata):
        super().__init__(parent)
        self.title("Board Comparison")
        self.transient(parent)

        outer = ttk.Frame(self, padding=10)
        outer.pack(fill="both", expand=True)

        if metadata:
            info_text = "    ".join(f"{label}: {value}" for label, value in metadata)
            ttk.Label(outer, text=info_text, font=("TkDefaultFont", 9, "bold")).pack(anchor="w", pady=(0, 8))

        # The report's "Level: N" field (see extract_report_metadata()) tells us whether this shot
        # was played on GameBoard's Mini-Bloecke grid -- without it, a Mini-Bloecke board would
        # render as if most of its blocks were out of range (they'd simply be clipped, see
        # draw_blocks_static()'s bounds check).
        cols, rows = BOARD_COLS, MAX_PLAYABLE_ROWS
        for label, value in metadata:
            if label == "Level":
                try:
                    if is_mini_block_level_slot(int(value)):
                        cols, rows = MINI_BOARD_COLS, MINI_MAX_PLAYABLE_ROWS
                except ValueError:
                    pass
                break

        panels = ttk.Frame(outer)
        panels.pack(fill="both", expand=True)

        for label, blocks in snapshots:
            panel = ttk.Frame(panels, padding=(0, 0, 12, 0))
            panel.pack(side="left", anchor="n")
            ttk.Label(panel, text=label, font=("TkDefaultFont", 9, "bold")).pack(anchor="w")
            canvas = tk.Canvas(
                panel, width=cols * COMPARE_CELL_SIZE, height=rows * COMPARE_CELL_SIZE,
                background="#1e1e1e", highlightthickness=1, highlightbackground="#666666",
            )
            canvas.pack(pady=(4, 0))
            draw_blocks_static(canvas, blocks, rows=rows, cols=cols)

        ttk.Button(outer, text="Close", command=self.destroy).pack(anchor="e", pady=(10, 0))


class PasteJsonDialog(tk.Toplevel):
    """Lets the user paste a "Letzten Zug exportieren (Debug)" report (or raw
    {"blocks": [...]} JSON) copied from the app, and opens a read-only BoardCompareWindow showing
    every board snapshot found in it (typically "vor"/"nach dem Zug") side by side -- for visually
    inspecting a reported bug's board state, not editing it.

    Also doubles as the review step for "Import from Phone" (see
    LevelOverviewWindow._import_from_phone()): when opened with initial_text, that's shown instead
    of the live clipboard, but the rest of the flow (Visualize / Save All to Assets) is identical --
    adb pull just replaces manual copy/paste."""

    def __init__(self, parent, initial_text=None, on_saved=None):
        super().__init__(parent)
        self.title("Paste Debug JSON")
        self.geometry("640x480")
        self.transient(parent)
        self.grab_set()
        self.on_saved = on_saved  # called after a successful Save All to Assets, e.g. to refresh
        # a LevelOverviewWindow's thumbnails -- see LevelOverviewWindow._import_from_phone().

        frm = ttk.Frame(self, padding=10)
        frm.pack(fill="both", expand=True)

        ttk.Label(frm, text='Paste a debug-export report (or raw {"blocks": [...]} JSON) below:').pack(anchor="w")

        text_frame = ttk.Frame(frm)
        text_frame.pack(fill="both", expand=True, pady=(4, 8))
        self.text = tk.Text(text_frame, wrap="word", undo=True)
        self.text.pack(side="left", fill="both", expand=True)
        scrollbar = ttk.Scrollbar(text_frame, command=self.text.yview)
        scrollbar.pack(side="right", fill="y")
        self.text.configure(yscrollcommand=scrollbar.set)

        if initial_text is not None:
            self.text.insert("1.0", initial_text)
        else:
            try:
                self.text.insert("1.0", self.clipboard_get())
            except tk.TclError:
                pass
        self.text.focus_set()

        btns = ttk.Frame(frm)
        btns.pack(fill="x")
        ttk.Button(btns, text="Paste from Clipboard", command=self._paste_clipboard).pack(side="left")
        ttk.Button(btns, text="Save All to Assets...", command=self._do_save_all).pack(side="left", padx=5)
        ttk.Button(btns, text="Close", command=self.destroy).pack(side="right")
        ttk.Button(btns, text="Visualize", command=self._do_visualize).pack(side="right", padx=5)

    def _paste_clipboard(self):
        try:
            clipboard = self.clipboard_get()
        except tk.TclError:
            messagebox.showerror("Paste Debug JSON", "Clipboard is empty or not text.", parent=self)
            return
        self.text.delete("1.0", "end")
        self.text.insert("1.0", clipboard)

    def _do_save_all(self):
        content = self.text.get("1.0", "end")
        levels = []
        for label, blocks in extract_json_blocks_objects(content):
            m = LEVEL_LABEL_RE.match(label)
            if m:
                levels.append((int(m.group(1)), blocks))
        if not levels:
            messagebox.showerror(
                "Save All to Assets",
                "No \"Level N (JSON ...)\" blocks found in the pasted text -- use the app's "
                "\"Alle Level exportieren\" export for this.",
                parent=self)
            return
        existing = sorted(n for n, _ in levels if (ASSETS_LEVELS_DIR / f"level{n}.json").exists())
        msg = f"Write {len(levels)} level file(s) to {ASSETS_LEVELS_DIR}?"
        if existing:
            msg += "\n" + f"{len(existing)} already exist and will be overwritten: " + ", ".join(str(n) for n in existing)
        if not messagebox.askyesno("Save All to Assets", msg, parent=self):
            return
        for n, blocks in levels:
            path = ASSETS_LEVELS_DIR / f"level{n}.json"
            sorted_blocks = sorted(blocks, key=lambda b: (b.get("y", 0), b.get("x", 0)))
            path.parent.mkdir(parents=True, exist_ok=True)
            with open(path, "w") as f:
                json.dump({"blocks": sorted_blocks}, f, indent=2)
        messagebox.showinfo("Save All to Assets", f"Wrote {len(levels)} level file(s).", parent=self)
        if self.on_saved:
            self.on_saved()

    def _do_visualize(self):
        content = self.text.get("1.0", "end")
        found = extract_json_blocks_objects(content)
        if not found:
            messagebox.showerror(
                "Paste Debug JSON", 'No {"blocks": [...]} JSON found in the pasted text.', parent=self)
            return
        metadata = extract_report_metadata(content)
        BoardCompareWindow(self, found, metadata)


class LevelOverviewWindow(tk.Toplevel):
    """Scrollable matrix of small thumbnails, one per authored (non-random-slot) level found in
    assets/levels/ -- 9 per row, since that's exactly how many designed levels sit between two
    random-level slots (see is_random_level_slot()). Meant for spotting sparse/boring levels at a
    glance and jumping straight to editing one by clicking its thumbnail. Thumbnails can also be
    dragged to a new position to reorder levels (see _reorder())."""

    THUMB_COLS = 9
    CELL_PX = 6  # one board cell, shrunk down, per thumbnail pixel
    GAP_X = 14
    GAP_Y = 20
    LABEL_H = 14

    def __init__(self, parent, app, is_entry_view=False):
        super().__init__(parent)
        self.app = app
        self.is_entry_view = is_entry_view
        self.title("Level Overview")
        self.geometry("900x700")

        # Tracked on the app so show_level_overview() can raise this same window instead of
        # stacking up duplicates -- the overview is meant to stay open alongside the editor now
        # (picking a level to edit no longer closes it, see _switch_to_editor()), so there needs to
        # be exactly one at a time to switch back to.
        self.app._overview_window = self
        self.protocol("WM_DELETE_WINDOW", self._on_close)

        self._build_menu()

        if is_entry_view:
            toolbar = ttk.Frame(self)
            toolbar.pack(fill="x", side="top")
            ttk.Button(toolbar, text="Neues Level...", command=self._new_level).pack(side="left", padx=6, pady=4)

        # Quick-create row for empty Mini-Bloecke slots (see is_mini_block_level_slot()) -- unlike
        # random-level slots, these are perfectly normal, authorable levels that just don't have a
        # file yet, so they're worth surfacing even though the thumbnail grid below only ever shows
        # levels that already have one. Kept as its own always-visible bar (not thumbnails mixed
        # into the draggable grid) so _reorder()'s file-permutation logic never has to deal with a
        # "slot" that has no file behind it.
        self._mini_bar = ttk.Frame(self)
        self._mini_bar.pack(fill="x", side="top")

        container = ttk.Frame(self)
        container.pack(fill="both", expand=True)

        self.canvas = tk.Canvas(container, background="#1e1e1e", highlightthickness=0)
        vbar = ttk.Scrollbar(container, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=vbar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        vbar.pack(side="right", fill="y")

        self.canvas.bind("<MouseWheel>", self._on_wheel)
        self.canvas.bind("<Button-4>", self._on_wheel)
        self.canvas.bind("<Button-5>", self._on_wheel)
        self.canvas.bind("<Button-1>", self._on_press)
        self.canvas.bind("<B1-Motion>", self._on_drag_motion)
        self.canvas.bind("<ButtonRelease-1>", self._on_release)

        self._levels = []  # [(n, path), ...] in display order
        self._thumb_rects = []  # (idx, path, x0, y0, x1, y1)
        self._drag_idx = None  # index (into _levels/_thumb_rects) currently held down, or None
        self._drag_moved = False
        self._drag_indicator = None  # canvas item id of the drop-target highlight
        self._drag_target_idx = None
        self._populate()
        self._refresh_mini_bar()

    def _on_close(self):
        # Entry view: the main window is still withdrawn at this point (nothing was ever opened
        # for editing), so falling back to the blank editor keeps the process from ending up with
        # no visible window at all if the user closes this via the window manager.
        if self.is_entry_view:
            self.app._overview_window = None
            self.app.root.deiconify()
            self.destroy()
            return
        if self.app.root.state() == "withdrawn":
            # The editor window isn't gone, just hidden -- LevelEditorApp.on_exit() withdraws it
            # instead of destroying it whenever an Overview is open (see its comment). If this
            # Overview is the only other window, closing it here would leave mainloop() running
            # forever with nothing on screen, so finish the exit that on_exit() deferred.
            if not self.app._confirm_discard():
                return
            self.app._overview_window = None
            self.destroy()
            self.app.root.destroy()
            return
        self.app._overview_window = None
        self.destroy()

    def _exit_app(self):
        # File > Exit here must end the whole process -- unlike LevelEditorApp.on_exit() (bound
        # to the plain editor window's own close button), which deliberately only withdraws the
        # editor and leaves an already-open Overview running. Wiring this menu item to on_exit()
        # directly hit exactly that withdraw-only branch (since the Overview -- this very window
        # -- always counts as "open" while its own menu is being used), so Exit appeared to do
        # nothing at all from here. Still goes through the same discard-unsaved-changes guard.
        if not self.app._confirm_discard():
            return
        self.app.root.destroy()

    def _switch_to_editor(self):
        # Brings the editor window to the front without closing the overview -- both stay open so
        # switching back is just a window away (or show_level_overview()'s sidebar button/shortcut,
        # which raises this same instance instead of opening a duplicate).
        # Root can be withdrawn either because this is the very first open (is_entry_view) or
        # because LevelEditorApp.on_exit() hid it earlier while this Overview stayed open --
        # either way, actually opening a level here means it must become visible again.
        if self.app.root.state() == "withdrawn":
            self.app.root.deiconify()
        if self.is_entry_view:
            # The "no visible window at all" safety net in _on_close() only makes sense before
            # any level has ever actually been opened -- once real editing has happened, closing
            # the Overview later must not resurrect a level the user may have since saved and
            # deliberately hidden again via LevelEditorApp.on_exit(). Without clearing this here,
            # _on_close() kept deiconify()-ing the editor on *every* future close of this Overview
            # instance, not just the first one.
            self.is_entry_view = False
        self.app.root.lift()
        self.app.root.focus_force()
        # focus_force() only gives the *window* OS focus -- Tk separately tracks which widget
        # inside it has keyboard focus, and that's whatever it happened to be the last time this
        # window was focused (nothing, the first time, or the Overview if the two windows have
        # been toggled back and forth). The arrow-key/Ctrl+Arrow/Shift+Arrow/Backspace bindings
        # are all on self.app.canvas specifically, so without this they silently do nothing.
        self.app.canvas.focus_set()

    def _build_menu(self):
        # The Level Overview is the level-management hub, so the full File menu lives here rather
        # than on the plain drawing canvas (LevelEditorApp._build_menu()) -- New/Open land a level
        # into the (possibly hidden, see is_entry_view) editor window and switch to it, same as
        # picking a thumbnail (_open_level()) already does; Save/Save As/Export act on whatever's
        # currently loaded there without needing to switch windows. Import Image lives on the
        # editor window instead (see LevelEditorApp._build_menu()) -- unlike New/Open it produces
        # content you then need to hand-clean-up on the canvas, so it belongs where that happens.
        menubar = tk.Menu(self)

        file_menu = tk.Menu(menubar, tearoff=0)
        file_menu.add_command(label="New", command=self._new_level, accelerator="Ctrl+N")
        file_menu.add_command(label="Open Level JSON...", command=self._file_open, accelerator="Ctrl+O")
        file_menu.add_command(label="Paste Debug JSON...", command=self._paste_debug_json, accelerator="Ctrl+Shift+V")
        file_menu.add_command(label="Import from Phone (adb)...", command=self._import_from_phone, accelerator="Ctrl+Shift+I")
        file_menu.add_separator()
        file_menu.add_command(label="Save", command=self.app.save, accelerator="Ctrl+S")
        file_menu.add_command(label="Save As...", command=self.app.save_as)
        file_menu.add_command(label="Export to assets/levels/level<N>.json...", command=self.app.export_to_assets)
        file_menu.add_separator()
        file_menu.add_command(label="Exit", command=self._exit_app)
        menubar.add_cascade(label="File", menu=file_menu)

        self.config(menu=menubar)
        self.bind("<Control-n>", lambda e: self._new_level())
        self.bind("<Control-o>", lambda e: self._file_open())
        self.bind("<Control-Shift-V>", lambda e: self._paste_debug_json())
        self.bind("<Control-Shift-v>", lambda e: self._paste_debug_json())
        self.bind("<Control-Shift-I>", lambda e: self._import_from_phone())
        self.bind("<Control-Shift-i>", lambda e: self._import_from_phone())
        self.bind("<Control-s>", lambda e: self.app.save())

    def _new_level(self):
        if not self.app.new_level():
            return  # user cancelled the discard-unsaved-changes prompt -- stay in the overview
        self._switch_to_editor()

    def _file_open(self):
        if not self.app.open_level():
            return  # dialog cancelled, load failed, or discard declined -- stay in the overview
        self._switch_to_editor()

    def _paste_debug_json(self):
        self.app.paste_debug_json(self)

    def _import_from_phone(self):
        # Parented to this window (not app.root, which may be withdrawn in entry view) and wired
        # to refresh the thumbnail grid once the pulled level(s) are actually saved to assets/ --
        # otherwise the overview would keep showing stale thumbnails until reopened.
        content = _adb_pull_export_text(self)
        if content is None:
            return
        PasteJsonDialog(self, initial_text=content, on_saved=self._refresh)

    def _populate(self):
        files = sorted(ASSETS_LEVELS_DIR.glob("level*.json"), key=_level_sort_key) if ASSETS_LEVELS_DIR.exists() else []
        levels = []
        for p in files:
            m = LEVEL_FILE_RE.match(p.name)
            if not m or is_random_level_slot(int(m.group(1))):
                continue
            levels.append((int(m.group(1)), p))

        self._levels = levels
        self._thumb_rects = []

        if not levels:
            self.canvas.create_text(20, 20, text="No authored levels found.", fill="#aaaaaa", anchor="nw")
            return

        thumb_w = BOARD_COLS * self.CELL_PX
        thumb_h = MAX_PLAYABLE_ROWS * self.CELL_PX
        cell_w = thumb_w + self.GAP_X
        cell_h = thumb_h + self.LABEL_H + self.GAP_Y

        for idx, (n, path) in enumerate(levels):
            col = idx % self.THUMB_COLS
            row = idx // self.THUMB_COLS
            x0 = self.GAP_X + col * cell_w
            y0 = self.GAP_Y + self.LABEL_H + row * cell_h
            self._draw_thumbnail(idx, n, path, x0, y0, thumb_w, thumb_h)

        total_rows = (len(levels) + self.THUMB_COLS - 1) // self.THUMB_COLS
        total_w = self.GAP_X + self.THUMB_COLS * cell_w
        total_h = self.GAP_Y + self.LABEL_H + total_rows * cell_h
        self.canvas.configure(scrollregion=(0, 0, total_w, total_h))

    def _refresh(self):
        self.canvas.delete("all")
        self._populate()
        self._refresh_mini_bar()

    # Lists every not-yet-authored Mini-Bloecke slot as a small button -- clicking one starts a
    # blank level already targeted at that number (see LevelEditorApp._new_level_for()). Looks 10
    # levels past the highest known one too, so at least the next slot is always offered even in a
    # brand new project with no authored levels at all yet.
    MINI_BAR_LOOKAHEAD = 10
    MINI_BAR_BUTTONS_PER_ROW = 15

    def _refresh_mini_bar(self):
        for child in self._mini_bar.winfo_children():
            child.destroy()
        known = {n for n, _ in self._levels}
        max_num = max(known, default=0) + self.MINI_BAR_LOOKAHEAD
        empty_mini = [n for n in range(1, max_num + 1) if is_mini_block_level_slot(n) and n not in known]
        if not empty_mini:
            return
        ttk.Label(self._mini_bar, text="Leere Mini-Bloecke-Slots (Klick = neu anlegen):").grid(
            row=0, column=0, columnspan=self.MINI_BAR_BUTTONS_PER_ROW, sticky="w", padx=6, pady=(4, 0))
        for i, n in enumerate(empty_mini):
            row, col = 1 + i // self.MINI_BAR_BUTTONS_PER_ROW, i % self.MINI_BAR_BUTTONS_PER_ROW
            ttk.Button(self._mini_bar, text=str(n), width=4,
                       command=lambda n=n: self._new_mini_level(n)).grid(row=row, column=col, padx=2, pady=2)

    def _new_mini_level(self, n):
        if not self.app._confirm_discard():
            return
        self.app._new_level_for(n)
        self._switch_to_editor()

    def _draw_thumbnail(self, idx, n, path, x0, y0, w, h):
        self.canvas.create_text(x0 + w / 2, y0 - 2, text=str(n), fill="#aaaaaa", anchor="s", font=("TkDefaultFont", 7))
        self.canvas.create_rectangle(x0, y0, x0 + w, y0 + h, outline="#555555", fill="#111111")

        try:
            data = json.loads(path.read_text())
            blocks = data.get("blocks", [])
        except Exception:
            self.canvas.create_text(x0 + w / 2, y0 + h / 2, text="!", fill="#ff5555")
            blocks = []

        # Every thumbnail box is the same fixed (w, h) shape so the THUMB_COLS grid stays uniform
        # regardless of level -- a Mini-Bloecke level's own (bigger) grid is scaled INTO that same
        # box instead of enlarging the box, so its blocks land at their real relative positions
        # instead of being clipped to the normal grid's smaller bounds.
        mini = is_mini_block_level_slot(n)
        cols = MINI_BOARD_COLS if mini else BOARD_COLS
        rows = MINI_MAX_PLAYABLE_ROWS if mini else MAX_PLAYABLE_ROWS
        cell_px_x = w / cols
        cell_px_y = h / rows

        for b in blocks:
            bx, by, value = b.get("x"), b.get("y"), b.get("value")
            if bx is None or by is None or value is None:
                continue
            if not (0 <= bx < cols) or not (0 <= by < rows):
                continue
            cx0 = x0 + bx * cell_px_x
            cy0 = y0 + by * cell_px_y
            self.canvas.create_rectangle(
                cx0, cy0, cx0 + cell_px_x, cy0 + cell_px_y,
                outline="", fill=effective_block_color(value, b.get("color")))

        if mini:
            self.canvas.create_text(x0 + 2, y0 + 2, text="Mini", fill="#5aaa96", anchor="nw",
                                     font=("TkDefaultFont", 6, "bold"))

        self._thumb_rects.append((idx, path, x0, y0, x0 + w, y0 + h))

    # -- click vs. drag-to-reorder ------------------------------------------
    #
    # A plain click (mouse down + up with negligible movement) opens the level under the
    # cursor, same as before. Once the drag exceeds a small pixel threshold it's treated as a
    # reorder instead: the thumbnail nearest the cursor is highlighted as the drop target, and
    # on release the dragged level is moved to that position in the grid (see _reorder()).

    DRAG_THRESHOLD_PX = 4

    def _thumb_index_at(self, cx, cy):
        for idx, path, x0, y0, x1, y1 in self._thumb_rects:
            if x0 <= cx <= x1 and y0 <= cy <= y1:
                return idx
        return None

    def _nearest_thumb_index(self, cx, cy):
        best_idx, best_dist = None, None
        for idx, path, x0, y0, x1, y1 in self._thumb_rects:
            ccx, ccy = (x0 + x1) / 2, (y0 + y1) / 2
            d = (ccx - cx) ** 2 + (ccy - cy) ** 2
            if best_dist is None or d < best_dist:
                best_dist, best_idx = d, idx
        return best_idx

    def _on_press(self, event):
        cx = self.canvas.canvasx(event.x)
        cy = self.canvas.canvasy(event.y)
        self._press_pos = (cx, cy)
        self._drag_idx = self._thumb_index_at(cx, cy)
        self._drag_moved = False
        self._drag_target_idx = None

    def _on_drag_motion(self, event):
        if self._drag_idx is None:
            return
        cx = self.canvas.canvasx(event.x)
        cy = self.canvas.canvasy(event.y)
        if not self._drag_moved:
            if abs(cx - self._press_pos[0]) < self.DRAG_THRESHOLD_PX and abs(cy - self._press_pos[1]) < self.DRAG_THRESHOLD_PX:
                return
            self._drag_moved = True
        self._update_drag_indicator(self._nearest_thumb_index(cx, cy))

    def _update_drag_indicator(self, target_idx):
        if self._drag_indicator is not None:
            self.canvas.delete(self._drag_indicator)
            self._drag_indicator = None
        self._drag_target_idx = target_idx
        if target_idx is None:
            return
        for idx, path, x0, y0, x1, y1 in self._thumb_rects:
            if idx == target_idx:
                self._drag_indicator = self.canvas.create_rectangle(
                    x0 - 3, y0 - 3, x1 + 3, y1 + 3, outline="#4aa3ff", width=3)
                break

    def _on_release(self, event):
        if self._drag_idx is None:
            return
        if self._drag_indicator is not None:
            self.canvas.delete(self._drag_indicator)
            self._drag_indicator = None
        if self._drag_moved:
            if self._drag_target_idx is not None and self._drag_target_idx != self._drag_idx:
                self._reorder(self._drag_idx, self._drag_target_idx)
        else:
            for idx, path, x0, y0, x1, y1 in self._thumb_rects:
                if idx == self._drag_idx:
                    self._open_level(path)
                    break
        self._drag_idx = None
        self._drag_moved = False
        self._drag_target_idx = None

    def _reorder(self, from_idx, to_idx):
        # Level numbers double as their filenames (GameBoard loads "level<N>.json" by number), and
        # random-level slots (is_random_level_slot()) sit fixed between them without a file at all.
        # So "moving" a level can't rename files across a shifting range -- instead the set of
        # number-slots stays exactly as-is, and only the *content* assigned to each slot is
        # permuted (list.insert semantics: remove the dragged item, insert it at the target index,
        # everything between the two positions shifts by one). All source content is read up front
        # so overwriting slot i can't clobber content still needed for slot i+1.
        numbers = [n for n, _ in self._levels]
        paths = [p for _, p in self._levels]
        contents = [p.read_text(encoding="utf-8") for p in paths]

        order = list(range(len(paths)))
        moved = order.pop(from_idx)
        order.insert(to_idx, moved)

        lo, hi = min(from_idx, to_idx), max(from_idx, to_idx)

        # A slot's grid size (normal vs Mini-Bloecke) is fixed by its level NUMBER, not by whatever
        # content currently lives there (see is_mini_block_level_slot()) -- permuting content across
        # slots of different sizes could silently drop blocks that don't fit the destination's
        # smaller grid. Checked as a separate pass, before any file is written, so a rejected
        # reorder never leaves some slots rewritten and others not.
        for i in range(lo, hi + 1):
            if order[i] == i:
                continue
            dest_n = numbers[i]
            dest_mini = is_mini_block_level_slot(dest_n)
            dest_cols = MINI_BOARD_COLS if dest_mini else BOARD_COLS
            dest_rows = MINI_MAX_PLAYABLE_ROWS if dest_mini else MAX_PLAYABLE_ROWS
            try:
                moved_blocks = json.loads(contents[order[i]]).get("blocks", [])
            except Exception:
                moved_blocks = []
            if any(b.get("x", 0) >= dest_cols or b.get("y", 0) >= dest_rows for b in moved_blocks):
                messagebox.showinfo(
                    "Reorder",
                    f"Level {numbers[order[i]]}'s content doesn't fit level {dest_n}'s grid "
                    f"({dest_cols}x{dest_rows}, {'Mini-Bloecke' if dest_mini else 'normal'}) -- "
                    "reorder cancelled.")
                return

        touched = set()
        for i in range(lo, hi + 1):
            if order[i] != i:
                paths[i].write_text(contents[order[i]], encoding="utf-8")
                touched.add(paths[i])

        # The editor window behind this overview may already have one of the just-rewritten
        # levels open; its in-memory grid would otherwise silently go stale (and a later Save
        # would clobber the reorder for that slot). Reload it from disk, discarding any unsaved
        # edits after asking -- same prompt used when switching levels via the dropdown.
        current = self.app.current_path
        if current is not None and current in touched:
            self.app._confirm_discard()
            self.app._load_level_file(current)

        self._refresh()

    def _open_level(self, path):
        if not self.app._confirm_discard():
            return
        self.app._load_level_file(path)
        self._switch_to_editor()

    def _on_wheel(self, event):
        if event.num == 4:
            self.canvas.yview_scroll(-3, "units")
        elif event.num == 5:
            self.canvas.yview_scroll(3, "units")
        else:
            self.canvas.yview_scroll(-1 if event.delta > 0 else 1, "units")


class LevelEditorApp:
    def __init__(self, root, initial_path=None):
        self.root = root
        self.grid = {}  # (x, y) -> {"type": str, "value": int}
        self.selected = None  # (x, y) or None
        self.current_path = None
        self.dirty = False
        self._overview_window = None  # the one live LevelOverviewWindow, if any -- see show_level_overview()

        # Active grid config -- normal (BOARD_COLS/EDITOR_ROWS/MAX_PLAYABLE_ROWS) by default, or
        # GameBoard's Mini-Bloecke dimensions while current_path targets a Mini-Bloecke level slot
        # (see is_mini_block_level_slot()/_configure_grid_for_level()). self.tool_radios is
        # populated by _build_layout() so _update_tool_palette_for_mini() can grey out the triangle
        # tools -- Mini-Bloecke levels stay square-only, same as the in-app Level-Editor.
        self.cols = BOARD_COLS
        self.max_playable_rows = MAX_PLAYABLE_ROWS
        self.editor_rows = EDITOR_ROWS
        self.mini_blocks = False
        self.tool_radios = {}

        # Undo/redo: each entry is a full snapshot of self.grid taken right before a mutation, so
        # undo/redo just swaps the whole grid back and forth -- see _push_undo()/_undo()/_redo().
        # Reset whenever a different level's content replaces the grid wholesale (_load_blocks(),
        # new_level()) since undoing past a "loaded a different file" boundary makes no sense.
        self._undo_stack = []
        self._redo_stack = []
        self._dragging = False  # coalesces one mouse-drag's cell-by-cell paints/erases into a
        # single undo step -- see _end_drag().
        self._eyedropper_picked = False  # whether this drag has already picked up a source color
        # for the Color Picker tool -- see _paint_eyedropper()/_end_drag().
        self._press_pos = (0, 0)
        self._drag_confirmed = False  # whether this press has moved enough to count as a real
        # drag yet -- see _on_canvas_press()/_on_canvas_drag()/PAINT_DRAG_THRESHOLD_PX.
        self._clone_source = None  # Clone Stamp tool: armed source cell, or None -- see
        # _clone_press()/_clone_drag()/_clone_apply().
        self._clone_offset = None  # Clone Stamp tool: fixed (dx, dy) from source to destination,
        # set by the destination-picking click; None until then.

        self._build_menu()
        self._build_layout()
        self._redraw()

        if initial_path:
            p = Path(initial_path)
            if p.suffix.lower() == ".json":
                self._load_level_file(p)
            else:
                self._import_image_path(p)
            self._update_title()
        else:
            # No file given on the command line: land on the Level Overview first instead of a
            # blank canvas (see show_level_overview()/LevelOverviewWindow) -- the main editor
            # window stays hidden until a thumbnail is picked, "Neues Level..." is chosen, or the
            # overview is closed outright (falls back to showing the blank editor).
            self._update_title()
            self.root.withdraw()
            self.show_level_overview(is_entry_view=True)

    # -- layout ------------------------------------------------------------

    def _build_menu(self):
        # Most of the File menu lives on LevelOverviewWindow now (see its _build_menu()) -- the
        # Level Overview is the level-management hub, so New/Open/etc. fit better there than on
        # this plain drawing canvas. Import Image and Save/Save As are the exceptions: Import
        # Image produces content you then need to hand-clean-up right here on the canvas, and
        # Save/Save As are common enough mid-edit that reaching for the Overview just for those
        # (even with Ctrl+S already bound here, see below) would be annoying.
        menubar = tk.Menu(self.root)

        file_menu = tk.Menu(menubar, tearoff=0)
        file_menu.add_command(label="Import Image...", command=self.import_image, accelerator="Ctrl+I")
        file_menu.add_separator()
        file_menu.add_command(label="Save", command=self.save, accelerator="Ctrl+S")
        file_menu.add_command(label="Save As...", command=self.save_as)
        menubar.add_cascade(label="File", menu=file_menu)

        edit_menu = tk.Menu(menubar, tearoff=0)
        edit_menu.add_command(label="Undo", command=self._undo, accelerator="Ctrl+Z")
        edit_menu.add_command(label="Redo", command=self._redo, accelerator="Ctrl+Y")
        edit_menu.add_separator()
        edit_menu.add_command(label="Clear All", command=self.clear_all)
        menubar.add_cascade(label="Edit", menu=edit_menu)

        self.root.config(menu=menubar)
        self.root.bind("<Control-n>", lambda e: self.new_level())
        self.root.bind("<Control-o>", lambda e: self.open_level())
        self.root.bind("<Control-i>", lambda e: self.import_image())
        self.root.bind("<Control-Shift-V>", lambda e: self.paste_debug_json())
        self.root.bind("<Control-Shift-v>", lambda e: self.paste_debug_json())
        self.root.bind("<Control-Shift-O>", lambda e: self.show_level_overview())
        self.root.bind("<Control-Shift-o>", lambda e: self.show_level_overview())
        self.root.bind("<Control-s>", lambda e: self.save())
        self.root.bind("<Control-z>", lambda e: self._undo())
        self.root.bind("<Control-y>", lambda e: self._redo())
        self.root.bind("<Control-Shift-Z>", lambda e: self._redo())
        self.root.bind("<Control-Shift-z>", lambda e: self._redo())
        self.root.protocol("WM_DELETE_WINDOW", self.on_exit)

    def _build_layout(self):
        main = ttk.Frame(self.root)
        main.pack(fill="both", expand=True)

        self.canvas = tk.Canvas(main, width=self.cols * CELL_SIZE, height=self.editor_rows * CELL_SIZE,
                                 background="#1e1e1e", highlightthickness=1, highlightbackground="#666666")
        self.canvas.grid(row=0, column=0, padx=8, pady=8)
        self.canvas.bind("<Button-1>", self._on_canvas_press)
        self.canvas.bind("<B1-Motion>", self._on_canvas_drag)
        self.canvas.bind("<ButtonRelease-1>", self._end_drag)
        self.canvas.bind("<Button-3>", self._copy_block)
        self.canvas.bind("<B3-Motion>", self._copy_block)
        self.canvas.bind("<Motion>", self._on_motion)
        self.canvas.bind("<MouseWheel>", self._on_wheel)
        self.canvas.bind("<Button-4>", self._on_wheel)
        self.canvas.bind("<Button-5>", self._on_wheel)
        self.canvas.bind("<KeyPress>", self._on_keypress)
        self.canvas.bind("<Left>", lambda e: self._move_selection(-1, 0))
        self.canvas.bind("<Right>", lambda e: self._move_selection(1, 0))
        self.canvas.bind("<Up>", lambda e: self._move_selection(0, -1))
        self.canvas.bind("<Down>", lambda e: self._move_selection(0, 1))
        self.canvas.bind("<Shift-Left>", lambda e: self._cycle_type(-1))
        self.canvas.bind("<Shift-Right>", lambda e: self._cycle_type(1))
        self.canvas.bind("<Shift-Up>", lambda e: self._adjust_selected_value(1))
        self.canvas.bind("<Shift-Down>", lambda e: self._adjust_selected_value(-1))
        self.canvas.bind("<Control-Left>", lambda e: self._move_selected_block(-1, 0))
        self.canvas.bind("<Control-Right>", lambda e: self._move_selected_block(1, 0))
        self.canvas.bind("<Control-Up>", lambda e: self._move_selected_block(0, -1))
        self.canvas.bind("<Control-Down>", lambda e: self._move_selected_block(0, 1))
        self.canvas.bind("<BackSpace>", lambda e: self._delete_selected())
        self.canvas.bind("<Return>", self._overwrite_selected)
        self.canvas.bind("<KP_Enter>", self._overwrite_selected)
        self.canvas.bind("<space>", self._overwrite_selected)
        self.canvas.focus_set()

        sidebar = ttk.Frame(main, padding=(8, 8))
        sidebar.grid(row=0, column=1, sticky="n")

        ttk.Label(sidebar, text="Level", font=("TkDefaultFont", 9, "bold")).pack(anchor="w")
        level_row = ttk.Frame(sidebar)
        level_row.pack(fill="x", pady=(0, 8))
        self.level_var = tk.StringVar(value="")
        self.level_combo = ttk.Combobox(level_row, textvariable=self.level_var, state="readonly", width=14)
        self.level_combo.pack(side="left", fill="x", expand=True)
        self.level_combo.bind("<<ComboboxSelected>>", self._on_level_selected)
        ttk.Button(level_row, text="⟳", width=3, command=self._refresh_level_list).pack(side="left", padx=(4, 0))
        self._refresh_level_list()
        ttk.Button(sidebar, text="Übersicht...", command=self.show_level_overview).pack(fill="x", pady=(4, 0))

        ttk.Separator(sidebar, orient="horizontal").pack(fill="x", pady=8)

        ttk.Label(sidebar, text="Block type", font=("TkDefaultFont", 9, "bold")).pack(anchor="w")
        self.tool_var = tk.StringVar(value="square")
        tools = [
            ("square", "Square"), ("tl", "◤ Triangle TL"), ("tr", "◥ Triangle TR"),
            ("bl", "◣ Triangle BL"), ("br", "◢ Triangle BR"), ("eraser", "Eraser"),
            ("colorpicker", "🎨 Color Picker"), ("clone", "📋 Clone Stamp"),
        ]
        for value, label in tools:
            radio = ttk.Radiobutton(sidebar, text=label, value=value, variable=self.tool_var)
            radio.pack(anchor="w")
            self.tool_radios[value] = radio
        self.clone_status_var = tk.StringVar(value="")
        ttk.Label(sidebar, textvariable=self.clone_status_var, foreground="#888888",
                  font=("TkDefaultFont", 8), wraplength=140, justify="left").pack(anchor="w", pady=(0, 4))
        self.tool_var.trace_add("write", lambda *args: self._update_clone_status())

        ttk.Separator(sidebar, orient="horizontal").pack(fill="x", pady=8)

        ttk.Label(sidebar, text="Value", font=("TkDefaultFont", 9, "bold")).pack(anchor="w")
        self.current_value = tk.IntVar(value=5)
        self.value_spin = ttk.Spinbox(sidebar, from_=1, to=99, textvariable=self.current_value, width=6,
                                       command=self._update_swatch)
        self.value_spin.pack(anchor="w", pady=(0, 4))
        self.current_value.trace_add("write", lambda *args: self._update_swatch())

        # None = color derived from value, the default; else an explicit "#RRGGBB" override (see
        # effective_block_color()). Value and color are otherwise fully independent -- picking a
        # color here doesn't change what's in the Value spinbox, and vice versa.
        self.current_color = None
        self.swatch = tk.Canvas(sidebar, width=70, height=32, highlightthickness=1,
                                 highlightbackground="black", cursor="hand2")
        self.swatch.pack(pady=4)
        self.swatch.bind("<Button-1>", lambda e: self._pick_color())
        self.color_status_var = tk.StringVar(value="")
        ttk.Label(sidebar, textvariable=self.color_status_var, foreground="#888888",
                  font=("TkDefaultFont", 8)).pack(anchor="w")
        ttk.Button(sidebar, text="Reset color to value", command=self._reset_color).pack(fill="x", pady=(2, 0))

        # Quick-pick palette (STANDARD_COLORS) -- same effect as picking a color via the swatch's
        # dialog (_apply_color()), just without opening it. 6 columns x 2 rows.
        palette = ttk.Frame(sidebar)
        palette.pack(pady=(6, 0))
        PALETTE_SWATCH_PX = 18
        for idx, hexcode in enumerate(STANDARD_COLORS):
            row, col = divmod(idx, 6)
            sw = tk.Canvas(palette, width=PALETTE_SWATCH_PX, height=PALETTE_SWATCH_PX,
                            highlightthickness=1, highlightbackground="#666666", cursor="hand2")
            sw.create_rectangle(0, 0, PALETTE_SWATCH_PX, PALETTE_SWATCH_PX, fill=hexcode, outline="")
            sw.grid(row=row, column=col, padx=1, pady=1)
            sw.bind("<Button-1>", lambda e, c=hexcode: self._apply_color(c))
        self._update_swatch()

        ttk.Separator(sidebar, orient="horizontal").pack(fill="x", pady=8)
        ttk.Button(sidebar, text="Clear All", command=self.clear_all).pack(fill="x")

        ttk.Separator(sidebar, orient="horizontal").pack(fill="x", pady=8)
        ttk.Label(sidebar, text="Shift All", font=("TkDefaultFont", 9, "bold")).pack(anchor="w")
        shift_grid = ttk.Frame(sidebar)
        shift_grid.pack(pady=(2, 0))
        ttk.Button(shift_grid, text="↑", width=3,
                   command=lambda: self.shift_all(0, -1)).grid(row=0, column=1)
        ttk.Button(shift_grid, text="←", width=3,
                   command=lambda: self.shift_all(-1, 0)).grid(row=1, column=0)
        ttk.Button(shift_grid, text="→", width=3,
                   command=lambda: self.shift_all(1, 0)).grid(row=1, column=2)
        ttk.Button(shift_grid, text="↓", width=3,
                   command=lambda: self.shift_all(0, 1)).grid(row=2, column=1)

        ttk.Label(sidebar, text="Bulk Value", font=("TkDefaultFont", 9, "bold")).pack(anchor="w", pady=(8, 0))
        bulk_value_row = ttk.Frame(sidebar)
        bulk_value_row.pack(pady=(2, 0))
        ttk.Button(bulk_value_row, text="Alle Werte -1", width=12,
                   command=lambda: self.bump_all_values(-1)).pack(side="left")
        ttk.Button(bulk_value_row, text="Alle Werte +1", width=12,
                   command=lambda: self.bump_all_values(1)).pack(side="left", padx=(4, 0))

        ttk.Label(sidebar, text=(
            "Left-drag: paint (overwrites)\nRight-drag: copy a block's\nshape/value/color, then\n"
            "left-click places copies\nWheel: adjust value\n\n"
            "Color Picker tool: first block\ntouched picks up its color,\n"
            "rest of the drag applies it\n(type/value untouched)\n\n"
            "Click a cell to select it, then:\n"
            "Arrows: move selection\n1-9: set value\n"
            "Shift+←/→: cycle block type\n"
            "Ctrl+Arrows: move the block\nBackspace: delete\n\n"
            "(Erase: select the Eraser\ntool, or Backspace)\n\n"
            "Ctrl+Z: undo\nCtrl+Y / Ctrl+Shift+Z: redo"
        ), justify="left", foreground="#888888").pack(anchor="w", pady=(16, 0))

        status = ttk.Frame(self.root)
        status.pack(side="bottom", fill="x")
        self.hover_var = tk.StringVar(value="")
        self.selected_var = tk.StringVar(value="")
        self.count_var = tk.StringVar(value="Blocks: 0")
        ttk.Label(status, textvariable=self.hover_var, anchor="w").pack(side="left", padx=6)
        ttk.Label(status, textvariable=self.selected_var, anchor="w").pack(side="left", padx=6)
        ttk.Label(status, textvariable=self.count_var, anchor="e").pack(side="right", padx=6)

    def _update_swatch(self):
        try:
            value = self.current_value.get()
        except tk.TclError:
            return
        color = effective_block_color(value, self.current_color)
        self.swatch.delete("all")
        self.swatch.create_rectangle(2, 2, 68, 30, fill=color, outline="")
        self.swatch.create_text(35, 16, text=str(value), fill=contrasting_text_color(color))
        self.color_status_var.set(f"Color: custom {self.current_color}" if self.current_color else "Color: auto (from value)")

    def _apply_color(self, hexcode):
        # Shared by the custom color-picker dialog (_pick_color()) and the STANDARD_COLORS
        # palette swatches -- see effective_block_color(). Applies to the currently selected
        # placed block (if any), and becomes the color new blocks are painted with from here on
        # (like current_value).
        self.current_color = hexcode.lower()
        if self.selected is not None and self.selected in self.grid:
            self._push_undo()
            self.grid[self.selected]["color"] = self.current_color
            self._mark_dirty()
            self._redraw()
        self._update_swatch()

    def _pick_color(self):
        # Click the swatch to open the full color-picker dialog.
        initial = self.current_color or value_to_hex_color(self.current_value.get())
        _rgb, hexcode = colorchooser.askcolor(color=initial, title="Pick block color", parent=self.root)
        if hexcode is None:
            return
        self._apply_color(hexcode)

    def _reset_color(self):
        self.current_color = None
        if self.selected is not None and self.selected in self.grid and "color" in self.grid[self.selected]:
            self._push_undo()
            del self.grid[self.selected]["color"]
            self._mark_dirty()
            self._redraw()
        self._update_swatch()

    # -- grid config (normal vs Mini-Bloecke) ------------------------------

    def _configure_grid_for_level(self, level_number):
        """Switches self.cols/max_playable_rows/editor_rows (and the canvas widget's actual size)
        to GameBoard's Mini-Bloecke dimensions if level_number is a Mini-Bloecke slot (see
        is_mini_block_level_slot()), or back to normal otherwise (including level_number=None, e.g.
        a brand new unsaved level with no target number chosen yet). Called whenever current_path
        changes to a level whose number is known -- see _load_level_file()/save_as()/
        export_to_assets()/_new_level_for()."""
        mini = level_number is not None and is_mini_block_level_slot(level_number)
        self.mini_blocks = mini
        self.cols = MINI_BOARD_COLS if mini else BOARD_COLS
        self.max_playable_rows = MINI_MAX_PLAYABLE_ROWS if mini else MAX_PLAYABLE_ROWS
        self.editor_rows = MINI_EDITOR_ROWS if mini else EDITOR_ROWS
        self.canvas.config(width=self.cols * CELL_SIZE, height=self.editor_rows * CELL_SIZE)
        self._update_tool_palette_for_mini()
        self.value_spin.configure(to=self._max_value())
        if self.current_value.get() > self._max_value():
            self.current_value.set(self._max_value())

    def _update_tool_palette_for_mini(self):
        for value, radio in self.tool_radios.items():
            disabled = self.mini_blocks and value in TRIANGLE_TYPES
            radio.configure(state="disabled" if disabled else "normal")
        if self.mini_blocks and self.tool_var.get() in TRIANGLE_TYPES:
            self.tool_var.set("square")

    def _max_value(self):
        # Mini-Bloecke levels keep block values single-digit (see GameBoard.MINI_MAX_VALUE) so the
        # number painted on a block stays legible at this finer grid's smaller cell size.
        return MINI_MAX_VALUE if self.mini_blocks else 99

    def _blocks_exceed_grid(self, cols, rows):
        return any(x >= cols or y >= rows for (x, y) in self.grid)

    # -- drawing -------------------------------------------------------

    def _redraw(self):
        self.canvas.delete("all")
        for gy in range(self.editor_rows):
            for gx in range(self.cols):
                left, top = gx * CELL_SIZE, gy * CELL_SIZE
                self.canvas.create_rectangle(left, top, left + CELL_SIZE, top + CELL_SIZE, outline="#444444")

        if self.editor_rows > self.max_playable_rows:
            boundary_y = self.max_playable_rows * CELL_SIZE
            self.canvas.create_line(0, boundary_y, self.cols * CELL_SIZE, boundary_y, fill="#e05555", width=2, dash=(6, 3))
            self.canvas.create_text(4, boundary_y + 2, text="not loaded in-game below this line", anchor="nw",
                                     fill="#e05555", font=("TkDefaultFont", 7))

        for (gx, gy), info in self.grid.items():
            left, top = gx * CELL_SIZE, gy * CELL_SIZE
            right, bottom = left + CELL_SIZE, top + CELL_SIZE
            color = effective_block_color(info["value"], info.get("color"))
            block_type = info["type"]
            if block_type == "square":
                self.canvas.create_rectangle(left, top, right, bottom, fill=color, outline="white", width=2)
                cx, cy = (left + right) / 2, (top + bottom) / 2
            else:
                pts = TRIANGLE_POINTS[block_type](left, top, right, bottom)
                self.canvas.create_polygon(pts, fill=color, outline="white", width=2)
                cx = (pts[0] + pts[2] + pts[4]) / 3
                cy = (pts[1] + pts[3] + pts[5]) / 3
            self.canvas.create_text(cx, cy, text=str(info["value"]), fill=contrasting_text_color(color))

        if self.selected is not None:
            sx, sy = self.selected
            left, top = sx * CELL_SIZE, sy * CELL_SIZE
            self.canvas.create_rectangle(left + 1, top + 1, left + CELL_SIZE - 1, top + CELL_SIZE - 1,
                                          outline="#ffdd33", width=3)
            self.selected_var.set(f"Selected: ({sx}, {sy})")
        else:
            self.selected_var.set("")

        self.count_var.set(f"Blocks: {len(self.grid)}")

    # -- mouse interaction -----------------------------------------------

    def _cell_at(self, event):
        gx, gy = event.x // CELL_SIZE, event.y // CELL_SIZE
        if 0 <= gx < self.cols and 0 <= gy < self.editor_rows:
            return gx, gy
        return None

    PAINT_DRAG_THRESHOLD_PX = 4

    def _on_canvas_press(self, event):
        # A plain click (press+release with no real movement) on an *occupied* cell with the
        # square/triangle paint tools only selects it, same as before overwrite-on-click existed
        # -- otherwise just clicking around to look at an already-authored level silently stomps
        # blocks whose type/value/color happen to differ from whatever the sidebar's current tool
        # state is, marking the level dirty despite the user never having intended to edit
        # anything (reported: "ich hatte nichts editiert"). Only once the mouse actually moves
        # past the threshold (_on_canvas_drag()) does it commit to painting. Eraser/Color Picker
        # are exempt: selecting either of those tools is already a deliberate choice, so a single
        # click acting immediately is expected, not a surprise -- same as before. Clone Stamp is
        # exempt too, for the same reason: once armed, every press/drag is a deliberate stamp --
        # see _clone_press().
        cell = self._cell_at(event)
        if cell is None:
            return
        self._press_pos = (event.x, event.y)
        self._drag_confirmed = False
        if self.tool_var.get() == "clone":
            self._drag_confirmed = True
            self._clone_press(cell)
            return
        if cell not in self.grid or self.tool_var.get() in ("eraser", "colorpicker"):
            self._drag_confirmed = True
            self._paint(event)
            return
        self.canvas.focus_set()
        self.selected = cell
        self._redraw()

    def _on_canvas_drag(self, event):
        if self.tool_var.get() == "clone":
            self._clone_drag(event)
            return
        if not self._drag_confirmed:
            px, py = self._press_pos
            if abs(event.x - px) < self.PAINT_DRAG_THRESHOLD_PX and abs(event.y - py) < self.PAINT_DRAG_THRESHOLD_PX:
                return  # still within the original press -- not a real drag yet
            self._drag_confirmed = True
        self._paint(event)

    def _paint(self, event):
        cell = self._cell_at(event)
        if cell is None:
            return
        self.canvas.focus_set()
        self.selected = cell
        tool = self.tool_var.get()
        if tool == "eraser":
            if cell in self.grid:
                self._push_drag_undo()
                del self.grid[cell]
                self._mark_dirty()
            self._redraw()
            return
        if tool == "colorpicker":
            self._paint_eyedropper(cell)
            self._redraw()
            return
        try:
            value = self.current_value.get()
        except tk.TclError:
            self._redraw()
            return
        new_info = self._new_block_info(tool, value)
        if self.grid.get(cell) == new_info:
            # Already exactly this type/value/color -- nothing to do, and more importantly nothing
            # to push onto the undo stack for (a drag re-painting the same block repeatedly would
            # otherwise burn through undo steps that revert to an identical state).
            self._redraw()
            return
        self._push_drag_undo()
        self.grid[cell] = new_info
        self._mark_dirty()
        self._redraw()

    def _copy_block(self, event):
        # Right-click "picks up" a placed block's full spec (shape, value, color) into the current
        # tool state -- switches tool_var to its type, current_value to its value, current_color
        # to its color (None if it was auto/value-derived, same either way visually since value
        # came along too) -- so a plain left-click/drag afterward (paint() already overwrites
        # occupied cells) stamps out copies of it, same as any other paint. Purely a read-only
        # pickup: never touches the grid, so there's nothing to undo here.
        cell = self._cell_at(event)
        if cell is None:
            return
        self.canvas.focus_set()
        if self.tool_var.get() == "clone":
            # Re-pick the clone source instead of touching tool_var -- switching tools out from
            # under the Clone Stamp on a stray right-click would be surprising, and unlike the
            # other tools, Clone Stamp needs its own always-available re-pick gesture (left-click
            # is already spoken for: it's the destination once armed, see _clone_press()).
            if cell in self.grid:
                self._clone_source = cell
                self._clone_offset = None
                self.selected = cell
                self._redraw()
                self._update_clone_status()
            return
        self.selected = cell
        if cell not in self.grid:
            return
        info = self.grid[cell]
        self.tool_var.set(info["type"])
        self.current_value.set(info["value"])
        self.current_color = info.get("color")
        self._update_swatch()
        self._redraw()

    def _paint_eyedropper(self, cell):
        # Color Picker tool: the first block touched in a drag (mouse-down, or wherever the drag
        # first crosses a block if it started over empty cells) is the *source* -- its effective
        # color (explicit override, else value-derived) is picked up into current_color, same as
        # picking a palette swatch, but nothing about that block itself changes. Every block
        # touched afterward in the same drag is the *target*: only its color changes to match,
        # its type and value are left exactly as they were -- that's the whole point versus just
        # painting over it with the current tool.
        if not self._eyedropper_picked:
            if cell in self.grid:
                # Deliberately not routed through _apply_color() -- that method also writes to
                # self.grid[self.selected], and _paint() already set self.selected to this same
                # source cell, which would spuriously touch/undo-push the source block itself.
                self.current_color = effective_block_color(self.grid[cell]["value"], self.grid[cell].get("color"))
                self._update_swatch()
                self._eyedropper_picked = True
            return
        if cell in self.grid and self.grid[cell].get("color") != self.current_color:
            self._push_drag_undo()
            self.grid[cell]["color"] = self.current_color
            self._mark_dirty()

    def _clone_press(self, cell):
        # Clone Stamp setup, two left-clicks: 1) an occupied cell arms it as the *source*; 2) an
        # empty cell fixes the *destination*, pinning the (dx, dy) offset between them and
        # stamping immediately via _clone_apply(). Right-click (_copy_block()) re-picks the
        # source at any time, armed or not.
        self.canvas.focus_set()
        if self._clone_offset is None:
            if cell in self.grid:
                self._clone_source = cell
                self.selected = cell
                self._redraw()
            elif self._clone_source is not None:
                sx, sy = self._clone_source
                dx, dy = cell[0] - sx, cell[1] - sy
                if dx or dy:
                    self._clone_offset = (dx, dy)
                    self._clone_apply(cell)
            self._update_clone_status()
            return
        # Armed: cursor is the *destination* -- every press/drag stamps at the cell under it,
        # sampling from the source cell the fixed offset away. Overwrites whatever's already at
        # the destination, same as the normal paint tools.
        self._clone_apply(cell)

    def _clone_drag(self, event):
        if self._clone_offset is None:
            return
        cell = self._cell_at(event)
        if cell is None:
            return
        self._clone_apply(cell)

    def _clone_apply(self, dest_cell):
        # Cursor drives the *destination*; the source is computed backwards from the fixed
        # offset, so it visibly moves in lockstep with the cursor (self.selected -- the yellow
        # selection frame -- is pointed at it every step, even when there's nothing there to
        # copy) as the drag crosses the board, continuously sampling whatever's currently at that
        # shifted position and stamping it at the destination.
        dx, dy = self._clone_offset
        source_cell = (dest_cell[0] - dx, dest_cell[1] - dy)
        self.selected = source_cell
        if not (0 <= source_cell[0] < self.cols and 0 <= source_cell[1] < self.editor_rows):
            self._redraw()
            return
        info = self.grid.get(source_cell)
        if info is None:
            self._redraw()
            return
        new_info = dict(info)
        if self.grid.get(dest_cell) == new_info:
            self._redraw()
            return
        self._push_drag_undo()
        self.grid[dest_cell] = new_info
        self._mark_dirty()
        self._redraw()

    def _update_clone_status(self):
        if self.tool_var.get() != "clone":
            self.clone_status_var.set("")
            return
        if self._clone_source is None:
            self.clone_status_var.set("Click a block to set as clone source.")
        elif self._clone_offset is None:
            self.clone_status_var.set("Click an empty cell to set the destination.")
        else:
            dx, dy = self._clone_offset
            self.clone_status_var.set(
                f"Armed (offset {dx:+d},{dy:+d}) -- click/drag to stamp, right-click to re-pick source.")

    # -- keyboard interaction ---------------------------------------------

    def _move_selection(self, dx, dy):
        if self.selected is None:
            self.selected = (0, 0)
        else:
            x, y = self.selected
            x = max(0, min(self.cols - 1, x + dx))
            y = max(0, min(self.editor_rows - 1, y + dy))
            self.selected = (x, y)
        self._redraw()
        return "break"

    def _cycle_type(self, direction):
        if self.selected is None or self.selected not in self.grid:
            return "break"
        self._push_undo()
        info = self.grid[self.selected]
        idx = BLOCK_TYPES.index(info["type"])
        info["type"] = BLOCK_TYPES[(idx + direction) % len(BLOCK_TYPES)]
        self._mark_dirty()
        self._redraw()
        return "break"

    def _adjust_selected_value(self, delta):
        # Shift-Up/Down: raise/lower the *selected* block's point value, mirroring Shift-Left/
        # Right's _cycle_type() for type. Same 0..99 clamp and delete-at-0 as the mouse-wheel
        # value adjust (_on_wheel) for a block under the cursor -- 0 points isn't a valid block.
        if self.selected is None or self.selected not in self.grid:
            return "break"
        info = self.grid[self.selected]
        value = max(0, min(self._max_value(), info["value"] + delta))
        self._push_undo()
        if value == 0:
            del self.grid[self.selected]
        else:
            info["value"] = value
        self._mark_dirty()
        self._redraw()
        return "break"

    def _move_selected_block(self, dx, dy):
        if self.selected is None or self.selected not in self.grid:
            return "break"
        x, y = self.selected
        nx, ny = x + dx, y + dy
        if not (0 <= nx < self.cols and 0 <= ny < self.editor_rows):
            return "break"
        if (nx, ny) in self.grid:
            return "break"
        self._push_undo()
        self.grid[(nx, ny)] = self.grid.pop(self.selected)
        self.selected = (nx, ny)
        self._mark_dirty()
        self._redraw()
        return "break"

    def _new_block_info(self, block_type, value):
        info = {"type": block_type, "value": value}
        if self.current_color:
            info["color"] = self.current_color
        return info

    def _set_selected_value(self, value):
        if self.selected is None:
            return
        self._push_undo()
        if self.selected in self.grid:
            self.grid[self.selected]["value"] = value
        else:
            tool = self.tool_var.get()
            block_type = tool if tool != "eraser" else "square"
            self.grid[self.selected] = self._new_block_info(block_type, value)
        self.current_value.set(value)
        self._mark_dirty()
        self._redraw()

    def _delete_selected(self):
        if self.selected is not None and self.selected in self.grid:
            self._push_undo()
            del self.grid[self.selected]
            self._mark_dirty()
            self._redraw()
        return "break"

    def _overwrite_selected(self, event=None):
        # Enter/Space: stamp the currently *selected* cell with the sidebar's current
        # tool/value/color, without needing a drag gesture -- selecting (a plain click,
        # see _on_canvas_press) never mutates a block, so this is the explicit action to
        # apply the tool to it instead. Same skip-if-identical / undo semantics as _paint.
        if self.selected is None:
            return "break"
        tool = self.tool_var.get()
        if tool in ("eraser", "colorpicker"):
            return "break"
        try:
            value = self.current_value.get()
        except tk.TclError:
            return "break"
        new_info = self._new_block_info(tool, value)
        if self.grid.get(self.selected) == new_info:
            return "break"
        self._push_undo()
        self.grid[self.selected] = new_info
        self._mark_dirty()
        self._redraw()
        return "break"

    def _on_keypress(self, event):
        if event.char and event.char.isdigit():
            digit = int(event.char)
            if 1 <= digit <= 9:
                self._set_selected_value(digit)

    def _on_wheel(self, event):
        num = getattr(event, "num", None)
        if num == 4:
            direction = 1
        elif num == 5:
            direction = -1
        else:
            direction = 1 if event.delta > 0 else -1

        cell = self._cell_at(event)
        if cell is None:
            return
        if cell in self.grid:
            value = max(0, min(self._max_value(), self.grid[cell]["value"] + direction))
            self._push_undo()
            if value == 0:
                del self.grid[cell]
            else:
                self.grid[cell]["value"] = value
            self._mark_dirty()
            self._redraw()
        else:
            try:
                value = self.current_value.get()
            except tk.TclError:
                value = 5
            self.current_value.set(max(1, min(self._max_value(), value + direction)))

    def _on_motion(self, event):
        cell = self._cell_at(event)
        self.hover_var.set(f"Cell: ({cell[0]}, {cell[1]})" if cell else "")

    # -- undo/redo ----------------------------------------------------

    UNDO_LIMIT = 200

    def _snapshot_grid(self):
        return {cell: dict(info) for cell, info in self.grid.items()}

    def _push_undo(self):
        self._undo_stack.append(self._snapshot_grid())
        if len(self._undo_stack) > self.UNDO_LIMIT:
            self._undo_stack.pop(0)
        self._redo_stack.clear()

    def _push_drag_undo(self):
        # Only the first mutation of an unbroken mouse drag pushes a snapshot -- see
        # _end_drag() -- so a whole paint/erase stroke undoes as one step instead of one per cell.
        if not self._dragging:
            self._push_undo()
            self._dragging = True

    def _reset_undo_history(self):
        self._undo_stack.clear()
        self._redo_stack.clear()

    def _end_drag(self, event=None):
        self._dragging = False
        self._eyedropper_picked = False
        self._drag_confirmed = False

    def _undo(self):
        if not self._undo_stack:
            return "break"
        self._redo_stack.append(self._snapshot_grid())
        self.grid = self._undo_stack.pop()
        self.selected = None
        self._mark_dirty()
        self._redraw()
        return "break"

    def _redo(self):
        if not self._redo_stack:
            return "break"
        self._undo_stack.append(self._snapshot_grid())
        self.grid = self._redo_stack.pop()
        self.selected = None
        self._mark_dirty()
        self._redraw()
        return "break"

    # -- file actions -------------------------------------------------

    def _mark_dirty(self):
        self.dirty = True
        self._update_title()

    def _update_title(self):
        name = self.current_path.name if self.current_path else "Untitled"
        star = "*" if self.dirty else ""
        mini = " [Mini-Bloecke]" if self.mini_blocks else ""
        self.root.title(f"BlockPong Level Editor - {name}{star}{mini}")
        self._refresh_level_selection()

    # Reserved random-level slots (see is_random_level_slot()) never get a file, so they'd simply
    # be absent from a plain directory listing -- shown here instead as greyed-out, locked entries
    # interspersed with the real levels so the gap is visible rather than silent. Tkinter's
    # Combobox can't style individual popup rows, so "greyed out" is approximated by a distinct
    # label plus outright rejecting the selection in _on_level_selected().
    RANDOM_SLOT_SUFFIX = "  (Zufalls-Level, gesperrt)"
    # Mini-Bloecke slots (see is_mini_block_level_slot()) are NOT locked like random slots -- they
    # just may not have a file yet. Picking one of these opens a blank grid already targeted at
    # that level number (see _new_level_for()) instead of erroring like the random-slot suffix.
    MINI_SLOT_SUFFIX = "  (Mini-Bloecke, noch leer)"

    def _refresh_level_list(self):
        files = sorted(ASSETS_LEVELS_DIR.glob("level*.json"), key=_level_sort_key) if ASSETS_LEVELS_DIR.exists() else []
        real_by_num = {}
        max_num = 0
        for p in files:
            m = LEVEL_FILE_RE.match(p.name)
            if not m:
                continue
            n = int(m.group(1))
            real_by_num[n] = p.name
            max_num = max(max_num, n)

        values = []
        for n in range(1, max_num + 1):
            if is_random_level_slot(n):
                values.append(f"level{n}.json{self.RANDOM_SLOT_SUFFIX}")
            elif n in real_by_num:
                values.append(real_by_num[n])
            elif is_mini_block_level_slot(n):
                values.append(f"level{n}.json{self.MINI_SLOT_SUFFIX}")
        self.level_combo["values"] = values
        self._refresh_level_selection()

    def _refresh_level_selection(self):
        names = self.level_combo["values"]
        if self.current_path and self.current_path.parent == ASSETS_LEVELS_DIR and self.current_path.name in names:
            self.level_var.set(self.current_path.name)
        else:
            self.level_var.set("")

    def _on_level_selected(self, event=None):
        name = self.level_var.get()
        if not name:
            return
        if name.endswith(self.RANDOM_SLOT_SUFFIX):
            messagebox.showinfo(
                "Zufalls-Level",
                name[:-len(self.RANDOM_SLOT_SUFFIX)] + " ist ein Zufalls-Level (wird im Spiel automatisch generiert) und kann nicht bearbeitet werden.",
            )
            self._refresh_level_selection()
            return
        if name.endswith(self.MINI_SLOT_SUFFIX):
            if not self._confirm_discard():
                self._refresh_level_selection()
                return
            base_name = name[:-len(self.MINI_SLOT_SUFFIX)]
            m = LEVEL_FILE_RE.match(base_name)
            self._new_level_for(int(m.group(1)))
            return
        if not self._confirm_discard():
            self._refresh_level_selection()
            return
        self._load_level_file(ASSETS_LEVELS_DIR / name)

    def _confirm_discard(self):
        if not self.dirty:
            return True
        resp = messagebox.askyesnocancel("Unsaved changes", "Save changes to the current level first?")
        if resp is None:
            return False
        if resp:
            return self.save()
        return True

    def _load_blocks(self, blocks):
        self._reset_undo_history()
        self.grid.clear()
        for b in blocks:
            x, y, block_type, value = b["x"], b["y"], b["type"], b["value"]
            if not (0 <= x < self.cols) or not (0 <= y < self.editor_rows):
                print(f"Skipping out-of-range block at ({x},{y})", file=sys.stderr)
                continue
            if block_type not in BLOCK_TYPES:
                print(f"Skipping block with unknown type '{block_type}' at ({x},{y})", file=sys.stderr)
                continue
            info = {"type": block_type, "value": value}
            color = b.get("color")
            if color:
                info["color"] = color
            self.grid[(x, y)] = info

    def _load_level_file(self, path):
        # Reconfigure the grid for this file's level number BEFORE parsing its blocks, so
        # _load_blocks()'s bounds check (and the canvas itself) already matches a Mini-Bloecke
        # level's bigger grid instead of clipping it to the normal board's smaller one.
        m = LEVEL_FILE_RE.match(path.name)
        self._configure_grid_for_level(int(m.group(1)) if m else None)
        try:
            data = json.loads(path.read_text())
            self._load_blocks(data["blocks"])
        except Exception as e:
            messagebox.showerror("Open Level", f"Failed to load {path}:\n{e}")
            return False
        self.current_path = path
        self.dirty = False
        self._redraw()
        self._update_title()
        return True

    def _import_image_path(self, path):
        try:
            blocks, _preview, _rows = convert(str(path), self.cols, None, "avg", 1, 20, 0, None, 24.0,
                                               max_playable_rows=self.max_playable_rows)
        except Exception as e:
            messagebox.showerror("Import Image", f"Conversion failed:\n{e}")
            return
        self._load_blocks(blocks)
        self.current_path = None
        self.dirty = True
        self._redraw()
        self._update_title()

    def new_level(self):
        # Returns whether a (blank) level actually became the current one, e.g. for
        # LevelOverviewWindow._new_level() to decide whether to switch to the editor window.
        # No target level number is known yet (chosen later via Save As/Export to assets), so this
        # always starts on the normal grid -- see _configure_grid_for_level(None).
        if not self._confirm_discard():
            return False
        self._reset_undo_history()
        self.grid.clear()
        self.current_path = None
        self.dirty = False
        self._configure_grid_for_level(None)
        self._redraw()
        self._update_title()
        return True

    def _new_level_for(self, level_number):
        """Like new_level(), but the target level number IS already known (picking an empty
        Mini-Bloecke slot from the dropdown, see _on_level_selected()/MINI_SLOT_SUFFIX) -- starts a
        blank grid sized for it and points current_path straight at it, so Ctrl+S/Save writes there
        directly instead of popping the generic Save As... dialog."""
        self._reset_undo_history()
        self.grid.clear()
        self.current_path = ASSETS_LEVELS_DIR / f"level{level_number}.json"
        self.dirty = False
        self._configure_grid_for_level(level_number)
        self._redraw()
        self._update_title()

    def open_level(self):
        if not self._confirm_discard():
            return False
        initial_dir = str(ASSETS_LEVELS_DIR) if ASSETS_LEVELS_DIR.exists() else str(REPO_ROOT)
        path = filedialog.askopenfilename(filetypes=[("Level JSON", "*.json")], initialdir=initial_dir)
        if not path:
            return False
        return self._load_level_file(Path(path))

    def import_image(self):
        if not self._confirm_discard():
            return False
        default_cols = MINI_BOARD_COLS if self.mini_blocks else ImageImportWindow.DEFAULT_COLS
        default_rows = (MINI_MAX_PLAYABLE_ROWS + 1) if self.mini_blocks else ImageImportWindow.DEFAULT_ROWS
        dialog = ImageImportWindow(self.root, max_cols=self.cols, max_rows=self.editor_rows,
                                    default_cols=default_cols, default_rows=default_rows,
                                    max_value=self._max_value())
        self.root.wait_window(dialog)
        if dialog.result is None:
            return False
        blocks, _rows = dialog.result
        self._load_blocks(blocks)
        # current_path is deliberately left as-is: importing an image while editing an existing
        # level (opened from the Overview) replaces that level's content, it doesn't detach into
        # a new unsaved one -- Ctrl+S should overwrite the same file, same as any other edit. If
        # there was no level open yet (a blank "New" level, current_path already None), this stays
        # None either way, same as before.
        self.dirty = True
        self._redraw()
        self._update_title()
        return True

    def paste_debug_json(self, parent=None):
        # Purely a read-only viewer (see BoardCompareWindow) -- doesn't touch self.grid, so
        # there's nothing to discard/confirm here, unlike import_image()/open_level(). parent
        # defaults to self.root, but LevelOverviewWindow's File menu passes itself so the dialog
        # stacks over the overview instead of a possibly-withdrawn root (entry view).
        PasteJsonDialog(parent or self.root)

    def show_level_overview(self, is_entry_view=False):
        # The overview stays open once opened (see LevelOverviewWindow._switch_to_editor()) rather
        # than closing itself when a level is picked for editing, so this raises the one existing
        # window instead of stacking up a duplicate -- e.g. from the sidebar "Übersicht..." button
        # while already mid-edit.
        win = self._overview_window
        if win is not None and win.winfo_exists():
            win.deiconify()
            win.lift()
            win.focus_force()
            win._refresh()
            return
        # Read-only until a thumbnail is clicked (see LevelOverviewWindow._open_level(), which
        # goes through the same _confirm_discard()/_load_level_file() path as picking a level from
        # the sidebar dropdown) -- so nothing to discard/confirm just to open the window itself.
        LevelOverviewWindow(self.root, self, is_entry_view=is_entry_view)

    def clear_all(self):
        if not self.grid:
            return
        if messagebox.askyesno("Clear All", "Remove all blocks from the level?"):
            self._push_undo()
            self.grid.clear()
            self._mark_dirty()
            self._redraw()

    def shift_all(self, dx, dy):
        if not self.grid:
            return
        for (x, y) in self.grid:
            nx, ny = x + dx, y + dy
            if not (0 <= nx < self.cols and 0 <= ny < self.editor_rows):
                messagebox.showinfo("Shift All", "Cannot shift: a block would move outside the board.")
                return
        self._push_undo()
        self.grid = {(x + dx, y + dy): info for (x, y), info in self.grid.items()}
        if self.selected is not None:
            sx, sy = self.selected
            self.selected = (sx + dx, sy + dy)
        self._mark_dirty()
        self._redraw()

    def bump_all_values(self, delta):
        if not self.grid:
            return
        max_value = self._max_value()
        new_values = {cell: max(1, min(max_value, info["value"] + delta)) for cell, info in self.grid.items()}
        if all(new_values[cell] == info["value"] for cell, info in self.grid.items()):
            return  # every block already clamped at the 1/max_value boundary -- nothing to do or undo
        self._push_undo()
        for cell, info in self.grid.items():
            info["value"] = new_values[cell]
        self._mark_dirty()
        self._redraw()

    def _write_json(self, path):
        blocks = []
        for (x, y), info in sorted(self.grid.items(), key=lambda item: (item[0][1], item[0][0])):
            b = {"x": x, "y": y, "type": info["type"], "value": info["value"]}
            if info.get("color"):
                b["color"] = info["color"]
            blocks.append(b)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w") as f:
            json.dump({"blocks": blocks}, f, indent=2)

    def _refresh_overview_if_open(self):
        # Keeps the Overview's thumbnails in sync with whatever this window just wrote to disk --
        # otherwise it would keep showing stale content until manually reopened. No-op if the
        # Overview isn't currently open.
        win = self._overview_window
        if win is not None and win.winfo_exists():
            win._refresh()

    def save(self):
        if self.current_path is None:
            return self.save_as()
        self._write_json(self.current_path)
        self.dirty = False
        self._update_title()
        self._refresh_overview_if_open()
        return True

    def save_as(self):
        initial_dir = str(ASSETS_LEVELS_DIR) if ASSETS_LEVELS_DIR.exists() else str(REPO_ROOT)
        initial_file = self.current_path.name if self.current_path else "level.json"
        path = filedialog.asksaveasfilename(
            defaultextension=".json", filetypes=[("Level JSON", "*.json")],
            initialdir=initial_dir, initialfile=initial_file,
        )
        if not path:
            return False
        target = Path(path)
        m = LEVEL_FILE_RE.match(target.name)
        target_number = int(m.group(1)) if m else None
        if target_number is not None and target.parent == ASSETS_LEVELS_DIR and is_random_level_slot(target_number):
            messagebox.showerror("Save As", f"{target.name} is a random-level slot (every 10th level) and can't be authored.")
            return False
        # A slot's grid size is fixed by its level number (is_mini_block_level_slot()), not by
        # whatever's currently open -- e.g. Save As-ing a Mini-Bloecke level's content to a normal
        # slot would otherwise silently drop every block past column 11 / row 16 the moment it's
        # reconfigured below.
        target_mini = target_number is not None and is_mini_block_level_slot(target_number)
        target_cols = MINI_BOARD_COLS if target_mini else BOARD_COLS
        target_rows = MINI_EDITOR_ROWS if target_mini else EDITOR_ROWS
        if self._blocks_exceed_grid(target_cols, target_rows):
            messagebox.showerror(
                "Save As",
                f"This level has blocks outside {target.name}'s grid ({target_cols}x{target_rows}"
                f"{', Mini-Bloecke' if target_mini else ''}) -- move/remove them first.")
            return False
        self.current_path = target
        self._configure_grid_for_level(target_number)
        self._write_json(self.current_path)
        self.dirty = False
        self._refresh_level_list()
        self._redraw()
        self._update_title()
        self._refresh_overview_if_open()
        return True

    def export_to_assets(self):
        n = simpledialog.askinteger("Export to assets", "Level number:", initialvalue=1, minvalue=1, parent=self.root)
        if n is None:
            return
        if is_random_level_slot(n):
            messagebox.showerror("Export to assets", f"Level {n} is a random-level slot (every 10th level) and can't be authored.")
            return
        target_mini = is_mini_block_level_slot(n)
        target_cols = MINI_BOARD_COLS if target_mini else BOARD_COLS
        target_rows = MINI_EDITOR_ROWS if target_mini else EDITOR_ROWS
        if self._blocks_exceed_grid(target_cols, target_rows):
            messagebox.showerror(
                "Export to assets",
                f"This level has blocks outside level{n}.json's grid ({target_cols}x{target_rows}"
                f"{', Mini-Bloecke' if target_mini else ''}) -- move/remove them first.")
            return
        path = ASSETS_LEVELS_DIR / f"level{n}.json"
        if path.exists() and not messagebox.askyesno("Export to assets", f"{path.name} already exists. Overwrite?"):
            return
        self._write_json(path)
        self.current_path = path
        self._configure_grid_for_level(n)
        self.dirty = False
        self._refresh_level_list()
        self._redraw()
        self._update_title()
        self._refresh_overview_if_open()
        messagebox.showinfo("Export to assets", f"Wrote {len(self.grid)} blocks to {path}")

    def on_exit(self):
        # self.root is the actual Tk() interpreter root (see launch_gui()) -- destroying it tears
        # down every Toplevel that belongs to it, the Level Overview included, even though that
        # window is meant to survive the editor closing (it stays open across editing sessions).
        # So if the overview is currently open, Exit here only withdraws the editor window
        # (nothing is lost: self.grid stays exactly as-is in memory, and reopening a level from
        # the overview already goes through _confirm_discard() on its own) rather than ending the
        # whole process.
        overview = self._overview_window
        if overview is not None and overview.winfo_exists():
            self.root.withdraw()
            return
        if not self._confirm_discard():
            return
        self.root.destroy()


def launch_gui(initial_path=None):
    root = tk.Tk()
    LevelEditorApp(root, initial_path)
    root.mainloop()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("image", nargs="?", help="Image to trace, or level JSON to open (GUI mode); source image for headless batch conversion")
    parser.add_argument("output", nargs="?", help="Output level JSON path (headless batch conversion only)")
    parser.add_argument("--gui", action="store_true", help="Force the GUI editor to open, even if output/--level is given")
    parser.add_argument("--level", type=int, help="Headless mode: write directly to assets/levels/level<N>.json instead of an explicit output path")
    parser.add_argument("--cols", type=int, default=None,
                         help=f"Grid columns (default {BOARD_COLS}, or {MINI_BOARD_COLS} if --level targets "
                              "a Mini-Bloecke slot -- see is_mini_block_level_slot())")
    parser.add_argument("--rows", type=int, default=None, help="Grid rows (default: derived from the image's aspect ratio)")
    parser.add_argument("--method", choices=["avg", "max"], default="avg", help="How to merge each cell's pixels into one color (default avg)")
    parser.add_argument("--value-min", type=int, default=1, help="Block value for the darkest cells (default 1)")
    parser.add_argument("--value-max", type=int, default=None,
                         help="Block value for the brightest cells (default 20, or "
                              f"{MINI_MAX_VALUE} if --level targets a Mini-Bloecke slot, "
                              "for on-screen legibility at that grid's smaller cells)")
    parser.add_argument("--frame", type=int, default=0, help="Which frame of an animated image to use (default 0)")
    parser.add_argument("--bg-color", default=None, help="Background color to key out as hex RRGGBB (default: auto-detect from image corners, or use alpha if present)")
    parser.add_argument("--bg-tolerance", type=float, default=24.0, help="Color-distance tolerance for background detection (default 24)")
    args = parser.parse_args()

    # GUI mode: no args at all, an explicit --gui, or just a file to open/trace
    # with no headless output destination given.
    if args.gui or (args.output is None and args.level is None):
        launch_gui(args.image)
        return

    target_mini = args.level is not None and is_mini_block_level_slot(args.level)
    cols = args.cols if args.cols is not None else (MINI_BOARD_COLS if target_mini else BOARD_COLS)
    max_playable_rows = MINI_MAX_PLAYABLE_ROWS if target_mini else MAX_PLAYABLE_ROWS
    value_max = args.value_max if args.value_max is not None else (MINI_MAX_VALUE if target_mini else 20)

    output_path = resolve_output_path(args)
    blocks, grid, rows = convert(
        args.image, cols, args.rows, args.method,
        args.value_min, value_max, args.frame, args.bg_color, args.bg_tolerance,
        max_playable_rows=max_playable_rows,
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w") as f:
        json.dump({"blocks": blocks}, f, indent=2)

    print(f"Wrote {len(blocks)} blocks ({cols}x{rows} grid) to {output_path}")
    print("Preview (digit = value mod 10, blank = empty):")
    for row in grid:
        print("  " + row)


if __name__ == "__main__":
    main()
