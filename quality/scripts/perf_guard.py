#!/usr/bin/env python3
"""Guard for perf-loop.sh: is the phone still showing the chat the run was set up in?

    adb exec-out screencap -p | python3 -I perf_guard.py save  REF.png
    adb exec-out screencap -p | python3 -I perf_guard.py check REF.png

Reads a full screenshot (PNG) from stdin and keeps only the chat's title bar — rows
TITLE_TOP..TITLE_BOTTOM, below the status bar so the clock doesn't count. `save` stores
that strip as the reference; `check` exits 0 if the current strip matches it, 1 if not
(another chat, the chat list, another app, a dialog). Full frames are never written.

The comparison counts clearly changed pixels in the middle of the strip, where the title
text is: a mean difference over the whole strip is dominated by the identical background,
and a different chat's name moved it only from 0 to 7.5 — too close to any threshold.
"""
import io
import os
import sys

from PIL import Image, ImageChops

TOP = int(os.environ.get("TITLE_TOP", "130"))
BOTTOM = int(os.environ.get("TITLE_BOTTOM", "260"))
# A pixel "changed" when a channel differs by more than this (anti-aliasing noise stays well under).
PIXEL_DELTA = int(os.environ.get("TITLE_PIXEL_DELTA", "48"))
# Mismatch when more than this fraction of the title area changed. A different name changes
# a few percent; the same title re-rendered changes ~0.
MAX_CHANGED = float(os.environ.get("TITLE_MAX_CHANGED", "0.002"))


def strip(png: bytes) -> Image.Image:
    im = Image.open(io.BytesIO(png)).convert("RGB")
    return im.crop((0, TOP, im.width, BOTTOM))


def title_area(im: Image.Image) -> Image.Image:
    """The middle 60 %: the title text, without the back arrow and the menu button."""
    return im.crop((im.width // 5, 0, im.width * 4 // 5, im.height))


def changed_fraction(a: Image.Image, b: Image.Image) -> float:
    diff = ImageChops.difference(title_area(a), title_area(b))
    # Per pixel, the largest channel difference; then the share above PIXEL_DELTA.
    per_pixel = ImageChops.lighter(ImageChops.lighter(*diff.split()[:2]), diff.split()[2])
    histogram = per_pixel.histogram()
    changed = sum(histogram[PIXEL_DELTA + 1:])
    return changed / (per_pixel.width * per_pixel.height)


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in ("save", "check"):
        print(__doc__)
        return 2
    mode, ref = sys.argv[1], sys.argv[2]
    current = strip(sys.stdin.buffer.read())
    if mode == "save":
        current.save(ref)
        print(f"reference title bar saved: {ref}")
        return 0
    reference = Image.open(ref).convert("RGB")
    if reference.size != current.size:
        print("guard: screen size changed")
        return 1
    changed = changed_fraction(reference, current)
    if changed > MAX_CHANGED:
        print(f"guard: not the reference chat ({changed:.2%} of the title changed > {MAX_CHANGED:.2%})")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
