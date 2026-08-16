"""Cut glowing art off a black background and split a growth strip into stages.

Keying black is the mirror of keying white: the edge pixels are the art faded
toward the backdrop, so a hard threshold leaves a dark fringe. Recovering the
true colour means dividing the edge back out by its own coverage.
"""
import numpy as np
from PIL import Image, ImageFilter


def _close(mask, radius=3):
    """Seal the dark outlines drawn inside the art so they stay opaque.

    A morphological close, not a flood fill: these images are ~1.5M pixels and
    a per-pixel flood in Python is far too slow.
    """
    im = Image.fromarray((mask * 255).astype(np.uint8), "L")
    k = radius * 2 + 1
    im = im.filter(ImageFilter.MaxFilter(k)).filter(ImageFilter.MinFilter(k))
    return np.asarray(im) > 127


def cut(img, body=70, floor=18, feather=1.2):
    """RGB art on black -> RGBA with soft, colour-correct edges."""
    rgb = np.asarray(img.convert("RGB")).astype(np.float32)
    peak = rgb.max(axis=2)

    # coverage ramp: bright art is solid, the fade to black becomes the edge
    cov = np.clip((peak - floor) / max(body - floor, 1), 0, 1)

    # a filled silhouette keeps dark interior detail fully opaque
    solid = _close(peak > body, 3)
    cov = np.maximum(cov, solid.astype(np.float32))

    cov = np.asarray(
        Image.fromarray((cov * 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(feather))
    ).astype(np.float32) / 255.0

    # undo the fade toward black so edges keep the art's real colour
    safe = np.maximum(cov, 0.06)[..., None]
    out = np.clip(rgb / safe, 0, 255)
    out = np.where(cov[..., None] > 0.02, out, 0)

    a = np.dstack([out.astype(np.uint8), (cov * 255).astype(np.uint8)])
    return Image.fromarray(a, "RGBA")


def split_stages(rgba, expect=4, min_gap=8, min_frac=0.004):
    """Split a strip into its stages on the empty columns between them."""
    a = np.asarray(rgba)
    alpha = a[..., 3]
    col = (alpha > 40).sum(axis=0)
    thresh = max(3, int(alpha.shape[0] * 0.004))
    occupied = col > thresh

    runs, start = [], None
    for x, v in enumerate(occupied):
        if v and start is None:
            start = x
        elif not v and start is not None:
            runs.append((start, x))
            start = None
    if start is not None:
        runs.append((start, len(occupied)))

    # merge runs separated by a thin gap (a detached petal is not a new stage)
    merged = []
    for r in runs:
        if merged and r[0] - merged[-1][1] < min_gap:
            merged[-1] = (merged[-1][0], r[1])
        else:
            merged.append(list(r))
            merged[-1] = tuple(merged[-1])
    merged = [tuple(m) for m in merged]

    # drop slivers, then keep the widest `expect` runs in left-to-right order
    total = alpha.shape[1]
    merged = [m for m in merged if (m[1] - m[0]) > total * min_frac]
    if len(merged) > expect:
        merged = sorted(sorted(merged, key=lambda m: -(m[1] - m[0]))[:expect])

    crops = []
    for x0, x1 in merged:
        sub = rgba.crop((x0, 0, x1, rgba.size[1]))
        bbox = sub.getchannel("A").point(lambda v: 255 if v > 12 else 0).getbbox()
        crops.append(sub.crop(bbox) if bbox else sub)
    return crops
