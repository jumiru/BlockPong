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
import re
import sys
import tkinter as tk
from pathlib import Path
from tkinter import colorchooser, filedialog, messagebox, simpledialog, ttk

from PIL import Image

BOARD_COLS = 11
MAX_PLAYABLE_ROWS = 16  # GameBoard reserves the bottom 2 rows of its 18-row board.
EDITOR_ROWS = 20  # Editor's drawable grid height. Larger than MAX_PLAYABLE_ROWS on purpose --
                   # GameBoard still only loads the top MAX_PLAYABLE_ROWS rows, so blocks placed
                   # below that line won't appear in-game; the canvas marks that boundary.
CELL_SIZE = 44

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS_LEVELS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "levels"

TRIANGLE_TYPES = ("tl", "tr", "bl", "br")
BLOCK_TYPES = ("square",) + TRIANGLE_TYPES

LEVEL_FILE_RE = re.compile(r"level(\d+)\.json$", re.IGNORECASE)


def _level_sort_key(path):
    m = LEVEL_FILE_RE.match(path.name)
    return (0, int(m.group(1))) if m else (1, path.name.lower())


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


def convert(image_path, cols, rows, method, value_min, value_max, frame_index, bg_color_hex, bg_tolerance):
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
    if rows > MAX_PLAYABLE_ROWS:
        print(f"Warning: {rows} rows requested, but only the top {MAX_PLAYABLE_ROWS} are playable "
              f"(GameBoard reserves the bottom 2 rows) -- clipping to {MAX_PLAYABLE_ROWS}.", file=sys.stderr)
        rows = MAX_PLAYABLE_ROWS

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

class ImportImageDialog(tk.Toplevel):
    """Collects gif_to_level-style options, runs convert(), and hands the
    resulting blocks back to the caller via self.result."""

    def __init__(self, parent):
        super().__init__(parent)
        self.title("Import Image")
        self.resizable(False, False)
        self.result = None
        self.transient(parent)
        self.grab_set()

        self.path_var = tk.StringVar()
        self.cols_var = tk.IntVar(value=BOARD_COLS)
        self.rows_var = tk.StringVar(value="")
        self.method_var = tk.StringVar(value="avg")
        self.value_min_var = tk.IntVar(value=1)
        self.value_max_var = tk.IntVar(value=20)
        self.frame_var = tk.IntVar(value=0)
        self.bg_color_var = tk.StringVar(value="")
        self.bg_tolerance_var = tk.DoubleVar(value=24.0)

        frm = ttk.Frame(self, padding=10)
        frm.grid(row=0, column=0, sticky="nsew")

        row = 0
        ttk.Label(frm, text="Image file:").grid(row=row, column=0, sticky="w")
        ttk.Entry(frm, textvariable=self.path_var, width=40).grid(row=row, column=1, columnspan=2, sticky="we")
        ttk.Button(frm, text="Browse...", command=self._browse).grid(row=row, column=3)
        row += 1

        ttk.Label(frm, text="Columns:").grid(row=row, column=0, sticky="w")
        ttk.Spinbox(frm, from_=1, to=BOARD_COLS, textvariable=self.cols_var, width=6).grid(row=row, column=1, sticky="w")
        ttk.Label(frm, text="Rows (blank=auto):").grid(row=row, column=2, sticky="w")
        ttk.Entry(frm, textvariable=self.rows_var, width=6).grid(row=row, column=3, sticky="w")
        row += 1

        ttk.Label(frm, text="Merge method:").grid(row=row, column=0, sticky="w")
        ttk.Combobox(frm, textvariable=self.method_var, values=["avg", "max"], width=6, state="readonly").grid(row=row, column=1, sticky="w")
        row += 1

        ttk.Label(frm, text="Value min:").grid(row=row, column=0, sticky="w")
        ttk.Spinbox(frm, from_=0, to=99, textvariable=self.value_min_var, width=6).grid(row=row, column=1, sticky="w")
        ttk.Label(frm, text="Value max:").grid(row=row, column=2, sticky="w")
        ttk.Spinbox(frm, from_=0, to=99, textvariable=self.value_max_var, width=6).grid(row=row, column=3, sticky="w")
        row += 1

        ttk.Label(frm, text="Frame (0-based):").grid(row=row, column=0, sticky="w")
        self.frame_spin = ttk.Spinbox(frm, from_=0, to=0, textvariable=self.frame_var, width=6)
        self.frame_spin.grid(row=row, column=1, sticky="w")
        self.frame_info = ttk.Label(frm, text="")
        self.frame_info.grid(row=row, column=2, columnspan=2, sticky="w")
        row += 1

        ttk.Label(frm, text="Background color (hex, blank=auto):").grid(row=row, column=0, columnspan=2, sticky="w")
        ttk.Entry(frm, textvariable=self.bg_color_var, width=10).grid(row=row, column=2, sticky="w")
        row += 1

        ttk.Label(frm, text="Background tolerance:").grid(row=row, column=0, sticky="w")
        ttk.Spinbox(frm, from_=0, to=255, textvariable=self.bg_tolerance_var, width=6).grid(row=row, column=1, sticky="w")
        row += 1

        btns = ttk.Frame(frm)
        btns.grid(row=row, column=0, columnspan=4, pady=(10, 0))
        ttk.Button(btns, text="Import", command=self._do_import).pack(side="left", padx=5)
        ttk.Button(btns, text="Cancel", command=self.destroy).pack(side="left", padx=5)

    def _browse(self):
        path = filedialog.askopenfilename(
            parent=self,
            filetypes=[("Images", "*.gif *.png *.jpg *.jpeg *.bmp"), ("All files", "*.*")],
        )
        if not path:
            return
        self.path_var.set(path)
        try:
            n_frames = getattr(Image.open(path), "n_frames", 1)
        except Exception:
            n_frames = 1
        self.frame_spin.configure(to=max(0, n_frames - 1))
        self.frame_info.configure(text=f"({n_frames} frame{'s' if n_frames != 1 else ''} in file)")

    def _do_import(self):
        path = self.path_var.get().strip()
        if not path:
            messagebox.showerror("Import Image", "Choose an image file first.", parent=self)
            return
        rows_text = self.rows_var.get().strip()
        rows = int(rows_text) if rows_text else None
        bg_color = self.bg_color_var.get().strip().lstrip("#") or None
        try:
            blocks, _preview, rows_used = convert(
                path, self.cols_var.get(), rows, self.method_var.get(),
                self.value_min_var.get(), self.value_max_var.get(),
                self.frame_var.get(), bg_color, self.bg_tolerance_var.get(),
            )
        except Exception as e:
            messagebox.showerror("Import Image", f"Conversion failed: {e}", parent=self)
            return
        self.result = (blocks, rows_used)
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

        panels = ttk.Frame(outer)
        panels.pack(fill="both", expand=True)

        for label, blocks in snapshots:
            panel = ttk.Frame(panels, padding=(0, 0, 12, 0))
            panel.pack(side="left", anchor="n")
            ttk.Label(panel, text=label, font=("TkDefaultFont", 9, "bold")).pack(anchor="w")
            canvas = tk.Canvas(
                panel, width=BOARD_COLS * COMPARE_CELL_SIZE, height=MAX_PLAYABLE_ROWS * COMPARE_CELL_SIZE,
                background="#1e1e1e", highlightthickness=1, highlightbackground="#666666",
            )
            canvas.pack(pady=(4, 0))
            draw_blocks_static(canvas, blocks)

        ttk.Button(outer, text="Close", command=self.destroy).pack(anchor="e", pady=(10, 0))


class PasteJsonDialog(tk.Toplevel):
    """Lets the user paste a "Letzten Zug exportieren (Debug)" report (or raw
    {"blocks": [...]} JSON) copied from the app, and opens a read-only BoardCompareWindow showing
    every board snapshot found in it (typically "vor"/"nach dem Zug") side by side -- for visually
    inspecting a reported bug's board state, not editing it."""

    def __init__(self, parent):
        super().__init__(parent)
        self.title("Paste Debug JSON")
        self.geometry("640x480")
        self.transient(parent)
        self.grab_set()

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
    glance and jumping straight to editing one by clicking its thumbnail."""

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

        if is_entry_view:
            # This is the whole app's landing view (main window still withdrawn) -- closing it via
            # the window manager needs to fall back to showing the blank editor instead of leaving
            # the process running with no visible window at all.
            self.protocol("WM_DELETE_WINDOW", self._close_to_blank_editor)
            toolbar = ttk.Frame(self)
            toolbar.pack(fill="x", side="top")
            ttk.Button(toolbar, text="Neues Level...", command=self._new_level).pack(side="left", padx=6, pady=4)

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
        self.canvas.bind("<Button-1>", self._on_click)

        self._thumb_rects = []  # (path, x0, y0, x1, y1)
        self._populate()

    def _close_to_blank_editor(self):
        self.destroy()
        self.app.root.deiconify()

    def _new_level(self):
        self.app.new_level()
        if self.is_entry_view:
            self.app.root.deiconify()
        self.destroy()

    def _populate(self):
        files = sorted(ASSETS_LEVELS_DIR.glob("level*.json"), key=_level_sort_key) if ASSETS_LEVELS_DIR.exists() else []
        levels = []
        for p in files:
            m = LEVEL_FILE_RE.match(p.name)
            if not m or is_random_level_slot(int(m.group(1))):
                continue
            levels.append((int(m.group(1)), p))

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
            self._draw_thumbnail(n, path, x0, y0, thumb_w, thumb_h)

        total_rows = (len(levels) + self.THUMB_COLS - 1) // self.THUMB_COLS
        total_w = self.GAP_X + self.THUMB_COLS * cell_w
        total_h = self.GAP_Y + self.LABEL_H + total_rows * cell_h
        self.canvas.configure(scrollregion=(0, 0, total_w, total_h))

    def _draw_thumbnail(self, n, path, x0, y0, w, h):
        self.canvas.create_text(x0 + w / 2, y0 - 2, text=str(n), fill="#aaaaaa", anchor="s", font=("TkDefaultFont", 7))
        self.canvas.create_rectangle(x0, y0, x0 + w, y0 + h, outline="#555555", fill="#111111")

        try:
            data = json.loads(path.read_text())
            blocks = data.get("blocks", [])
        except Exception:
            self.canvas.create_text(x0 + w / 2, y0 + h / 2, text="!", fill="#ff5555")
            blocks = []

        for b in blocks:
            bx, by, value = b.get("x"), b.get("y"), b.get("value")
            if bx is None or by is None or value is None:
                continue
            if not (0 <= bx < BOARD_COLS) or not (0 <= by < MAX_PLAYABLE_ROWS):
                continue
            cx0 = x0 + bx * self.CELL_PX
            cy0 = y0 + by * self.CELL_PX
            self.canvas.create_rectangle(
                cx0, cy0, cx0 + self.CELL_PX, cy0 + self.CELL_PX,
                outline="", fill=effective_block_color(value, b.get("color")))

        self._thumb_rects.append((path, x0, y0, x0 + w, y0 + h))

    def _on_click(self, event):
        cx = self.canvas.canvasx(event.x)
        cy = self.canvas.canvasy(event.y)
        for path, x0, y0, x1, y1 in self._thumb_rects:
            if x0 <= cx <= x1 and y0 <= cy <= y1:
                self._open_level(path)
                return

    def _open_level(self, path):
        if not self.app._confirm_discard():
            return
        self.app._load_level_file(path)
        if self.is_entry_view:
            self.app.root.deiconify()
        self.destroy()

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
        menubar = tk.Menu(self.root)

        file_menu = tk.Menu(menubar, tearoff=0)
        file_menu.add_command(label="New", command=self.new_level, accelerator="Ctrl+N")
        file_menu.add_command(label="Open Level JSON...", command=self.open_level, accelerator="Ctrl+O")
        file_menu.add_command(label="Import Image...", command=self.import_image, accelerator="Ctrl+I")
        file_menu.add_command(label="Paste Debug JSON...", command=self.paste_debug_json, accelerator="Ctrl+Shift+V")
        file_menu.add_command(label="Level Overview...", command=self.show_level_overview, accelerator="Ctrl+Shift+O")
        file_menu.add_separator()
        file_menu.add_command(label="Save", command=self.save, accelerator="Ctrl+S")
        file_menu.add_command(label="Save As...", command=self.save_as)
        file_menu.add_command(label="Export to assets/levels/level<N>.json...", command=self.export_to_assets)
        file_menu.add_separator()
        file_menu.add_command(label="Exit", command=self.on_exit)
        menubar.add_cascade(label="File", menu=file_menu)

        edit_menu = tk.Menu(menubar, tearoff=0)
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
        self.root.protocol("WM_DELETE_WINDOW", self.on_exit)

    def _build_layout(self):
        main = ttk.Frame(self.root)
        main.pack(fill="both", expand=True)

        self.canvas = tk.Canvas(main, width=BOARD_COLS * CELL_SIZE, height=EDITOR_ROWS * CELL_SIZE,
                                 background="#1e1e1e", highlightthickness=1, highlightbackground="#666666")
        self.canvas.grid(row=0, column=0, padx=8, pady=8)
        self.canvas.bind("<Button-1>", self._paint)
        self.canvas.bind("<B1-Motion>", self._paint)
        self.canvas.bind("<Button-3>", self._erase)
        self.canvas.bind("<B3-Motion>", self._erase)
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
        self.canvas.bind("<Control-Left>", lambda e: self._move_selected_block(-1, 0))
        self.canvas.bind("<Control-Right>", lambda e: self._move_selected_block(1, 0))
        self.canvas.bind("<Control-Up>", lambda e: self._move_selected_block(0, -1))
        self.canvas.bind("<Control-Down>", lambda e: self._move_selected_block(0, 1))
        self.canvas.bind("<BackSpace>", lambda e: self._delete_selected())
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
        ]
        for value, label in tools:
            ttk.Radiobutton(sidebar, text=label, value=value, variable=self.tool_var).pack(anchor="w")

        ttk.Separator(sidebar, orient="horizontal").pack(fill="x", pady=8)

        ttk.Label(sidebar, text="Value", font=("TkDefaultFont", 9, "bold")).pack(anchor="w")
        self.current_value = tk.IntVar(value=5)
        spin = ttk.Spinbox(sidebar, from_=1, to=99, textvariable=self.current_value, width=6, command=self._update_swatch)
        spin.pack(anchor="w", pady=(0, 4))
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

        ttk.Label(sidebar, text=(
            "Left-drag: paint\nRight-drag: erase\nWheel: adjust value\n\n"
            "Click a cell to select it, then:\n"
            "Arrows: move selection\n1-9: set value\n"
            "Shift+←/→: cycle block type\n"
            "Ctrl+Arrows: move the block\nBackspace: delete"
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

    def _pick_color(self):
        # Click the swatch to pick a color independent of the block's point value -- see
        # effective_block_color(). Applies to the currently selected placed block (if any), and
        # becomes the color new blocks are painted with from here on (like current_value).
        initial = self.current_color or value_to_hex_color(self.current_value.get())
        _rgb, hexcode = colorchooser.askcolor(color=initial, title="Pick block color", parent=self.root)
        if hexcode is None:
            return
        self.current_color = hexcode.lower()
        if self.selected is not None and self.selected in self.grid:
            self.grid[self.selected]["color"] = self.current_color
            self._mark_dirty()
            self._redraw()
        self._update_swatch()

    def _reset_color(self):
        self.current_color = None
        if self.selected is not None and self.selected in self.grid:
            if self.grid[self.selected].pop("color", None) is not None:
                self._mark_dirty()
                self._redraw()
        self._update_swatch()

    # -- drawing -------------------------------------------------------

    def _redraw(self):
        self.canvas.delete("all")
        for gy in range(EDITOR_ROWS):
            for gx in range(BOARD_COLS):
                left, top = gx * CELL_SIZE, gy * CELL_SIZE
                self.canvas.create_rectangle(left, top, left + CELL_SIZE, top + CELL_SIZE, outline="#444444")

        if EDITOR_ROWS > MAX_PLAYABLE_ROWS:
            boundary_y = MAX_PLAYABLE_ROWS * CELL_SIZE
            self.canvas.create_line(0, boundary_y, BOARD_COLS * CELL_SIZE, boundary_y, fill="#e05555", width=2, dash=(6, 3))
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
        if 0 <= gx < BOARD_COLS and 0 <= gy < EDITOR_ROWS:
            return gx, gy
        return None

    def _paint(self, event):
        cell = self._cell_at(event)
        if cell is None:
            return
        self.canvas.focus_set()
        self.selected = cell
        tool = self.tool_var.get()
        if tool == "eraser":
            if self.grid.pop(cell, None) is not None:
                self._mark_dirty()
            self._redraw()
            return
        if cell in self.grid:
            # An occupied cell is only selected, never repainted -- use the
            # eraser or the keyboard (1-9 / Shift+Left/Right) to change it.
            self._redraw()
            return
        try:
            value = self.current_value.get()
        except tk.TclError:
            self._redraw()
            return
        self.grid[cell] = self._new_block_info(tool, value)
        self._mark_dirty()
        self._redraw()

    def _erase(self, event):
        cell = self._cell_at(event)
        if cell is None:
            return
        self.canvas.focus_set()
        self.selected = cell
        if self.grid.pop(cell, None) is not None:
            self._mark_dirty()
        self._redraw()

    # -- keyboard interaction ---------------------------------------------

    def _move_selection(self, dx, dy):
        if self.selected is None:
            self.selected = (0, 0)
        else:
            x, y = self.selected
            x = max(0, min(BOARD_COLS - 1, x + dx))
            y = max(0, min(EDITOR_ROWS - 1, y + dy))
            self.selected = (x, y)
        self._redraw()
        return "break"

    def _cycle_type(self, direction):
        if self.selected is None or self.selected not in self.grid:
            return "break"
        info = self.grid[self.selected]
        idx = BLOCK_TYPES.index(info["type"])
        info["type"] = BLOCK_TYPES[(idx + direction) % len(BLOCK_TYPES)]
        self._mark_dirty()
        self._redraw()
        return "break"

    def _move_selected_block(self, dx, dy):
        if self.selected is None or self.selected not in self.grid:
            return "break"
        x, y = self.selected
        nx, ny = x + dx, y + dy
        if not (0 <= nx < BOARD_COLS and 0 <= ny < EDITOR_ROWS):
            return "break"
        if (nx, ny) in self.grid:
            return "break"
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
        if self.selected is not None and self.grid.pop(self.selected, None) is not None:
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
            value = max(0, min(99, self.grid[cell]["value"] + direction))
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
            self.current_value.set(max(1, min(99, value + direction)))

    def _on_motion(self, event):
        cell = self._cell_at(event)
        self.hover_var.set(f"Cell: ({cell[0]}, {cell[1]})" if cell else "")

    # -- file actions -------------------------------------------------

    def _mark_dirty(self):
        self.dirty = True
        self._update_title()

    def _update_title(self):
        name = self.current_path.name if self.current_path else "Untitled"
        star = "*" if self.dirty else ""
        self.root.title(f"BlockPong Level Editor - {name}{star}")
        self._refresh_level_selection()

    # Reserved random-level slots (see is_random_level_slot()) never get a file, so they'd simply
    # be absent from a plain directory listing -- shown here instead as greyed-out, locked entries
    # interspersed with the real levels so the gap is visible rather than silent. Tkinter's
    # Combobox can't style individual popup rows, so "greyed out" is approximated by a distinct
    # label plus outright rejecting the selection in _on_level_selected().
    RANDOM_SLOT_SUFFIX = "  (Zufalls-Level, gesperrt)"

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
        self.grid.clear()
        for b in blocks:
            x, y, block_type, value = b["x"], b["y"], b["type"], b["value"]
            if not (0 <= x < BOARD_COLS) or not (0 <= y < EDITOR_ROWS):
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
        try:
            data = json.loads(path.read_text())
            self._load_blocks(data["blocks"])
        except Exception as e:
            messagebox.showerror("Open Level", f"Failed to load {path}:\n{e}")
            return
        self.current_path = path
        self.dirty = False
        self._redraw()
        self._update_title()

    def _import_image_path(self, path):
        try:
            blocks, _preview, _rows = convert(str(path), BOARD_COLS, None, "avg", 1, 20, 0, None, 24.0)
        except Exception as e:
            messagebox.showerror("Import Image", f"Conversion failed:\n{e}")
            return
        self._load_blocks(blocks)
        self.current_path = None
        self.dirty = True
        self._redraw()
        self._update_title()

    def new_level(self):
        if not self._confirm_discard():
            return
        self.grid.clear()
        self.current_path = None
        self.dirty = False
        self._redraw()
        self._update_title()

    def open_level(self):
        if not self._confirm_discard():
            return
        initial_dir = str(ASSETS_LEVELS_DIR) if ASSETS_LEVELS_DIR.exists() else str(REPO_ROOT)
        path = filedialog.askopenfilename(filetypes=[("Level JSON", "*.json")], initialdir=initial_dir)
        if not path:
            return
        self._load_level_file(Path(path))

    def import_image(self):
        if not self._confirm_discard():
            return
        dialog = ImportImageDialog(self.root)
        self.root.wait_window(dialog)
        if dialog.result is None:
            return
        blocks, _rows = dialog.result
        self._load_blocks(blocks)
        self.current_path = None
        self.dirty = True
        self._redraw()
        self._update_title()

    def paste_debug_json(self):
        # Purely a read-only viewer (see BoardCompareWindow) -- doesn't touch self.grid, so
        # there's nothing to discard/confirm here, unlike import_image()/open_level().
        PasteJsonDialog(self.root)

    def show_level_overview(self, is_entry_view=False):
        # Read-only until a thumbnail is clicked (see LevelOverviewWindow._open_level(), which
        # goes through the same _confirm_discard()/_load_level_file() path as picking a level from
        # the sidebar dropdown) -- so nothing to discard/confirm just to open the window itself.
        LevelOverviewWindow(self.root, self, is_entry_view=is_entry_view)

    def clear_all(self):
        if not self.grid:
            return
        if messagebox.askyesno("Clear All", "Remove all blocks from the level?"):
            self.grid.clear()
            self._mark_dirty()
            self._redraw()

    def shift_all(self, dx, dy):
        if not self.grid:
            return
        for (x, y) in self.grid:
            nx, ny = x + dx, y + dy
            if not (0 <= nx < BOARD_COLS and 0 <= ny < EDITOR_ROWS):
                messagebox.showinfo("Shift All", "Cannot shift: a block would move outside the board.")
                return
        self.grid = {(x + dx, y + dy): info for (x, y), info in self.grid.items()}
        if self.selected is not None:
            sx, sy = self.selected
            self.selected = (sx + dx, sy + dy)
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

    def save(self):
        if self.current_path is None:
            return self.save_as()
        self._write_json(self.current_path)
        self.dirty = False
        self._update_title()
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
        if m and target.parent == ASSETS_LEVELS_DIR and is_random_level_slot(int(m.group(1))):
            messagebox.showerror("Save As", f"{target.name} is a random-level slot (every 10th level) and can't be authored.")
            return False
        self.current_path = target
        self._write_json(self.current_path)
        self.dirty = False
        self._refresh_level_list()
        self._update_title()
        return True

    def export_to_assets(self):
        n = simpledialog.askinteger("Export to assets", "Level number:", initialvalue=1, minvalue=1, parent=self.root)
        if n is None:
            return
        if is_random_level_slot(n):
            messagebox.showerror("Export to assets", f"Level {n} is a random-level slot (every 10th level) and can't be authored.")
            return
        path = ASSETS_LEVELS_DIR / f"level{n}.json"
        if path.exists() and not messagebox.askyesno("Export to assets", f"{path.name} already exists. Overwrite?"):
            return
        self._write_json(path)
        self.current_path = path
        self.dirty = False
        self._refresh_level_list()
        self._update_title()
        messagebox.showinfo("Export to assets", f"Wrote {len(self.grid)} blocks to {path}")

    def on_exit(self):
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
    parser.add_argument("--cols", type=int, default=BOARD_COLS, help=f"Grid columns (default {BOARD_COLS}, matches the board width)")
    parser.add_argument("--rows", type=int, default=None, help="Grid rows (default: derived from the image's aspect ratio)")
    parser.add_argument("--method", choices=["avg", "max"], default="avg", help="How to merge each cell's pixels into one color (default avg)")
    parser.add_argument("--value-min", type=int, default=1, help="Block value for the darkest cells (default 1)")
    parser.add_argument("--value-max", type=int, default=20, help="Block value for the brightest cells (default 20)")
    parser.add_argument("--frame", type=int, default=0, help="Which frame of an animated image to use (default 0)")
    parser.add_argument("--bg-color", default=None, help="Background color to key out as hex RRGGBB (default: auto-detect from image corners, or use alpha if present)")
    parser.add_argument("--bg-tolerance", type=float, default=24.0, help="Color-distance tolerance for background detection (default 24)")
    args = parser.parse_args()

    # GUI mode: no args at all, an explicit --gui, or just a file to open/trace
    # with no headless output destination given.
    if args.gui or (args.output is None and args.level is None):
        launch_gui(args.image)
        return

    output_path = resolve_output_path(args)
    blocks, grid, rows = convert(
        args.image, args.cols, args.rows, args.method,
        args.value_min, args.value_max, args.frame, args.bg_color, args.bg_tolerance,
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w") as f:
        json.dump({"blocks": blocks}, f, indent=2)

    print(f"Wrote {len(blocks)} blocks ({args.cols}x{rows} grid) to {output_path}")
    print("Preview (digit = value mod 10, blank = empty):")
    for row in grid:
        print("  " + row)


if __name__ == "__main__":
    main()
