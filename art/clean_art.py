"""Clean the plant sprites: kill the white rim, anti-alias the alpha, and bleed
plant colour into the transparent area so scaling can never drag white in."""
import io
import numpy as np
from PIL import Image, ImageFilter


def _neighbour_mean(rgb, known):
    """Mean of the 4-neighbours that are already known, per pixel."""
    acc = np.zeros_like(rgb, dtype=np.float32)
    cnt = np.zeros(known.shape, dtype=np.float32)
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        r = np.roll(np.roll(rgb, dy, 0), dx, 1)
        k = np.roll(np.roll(known, dy, 0), dx, 1).astype(np.float32)
        acc += r * k[..., None]
        cnt += k
    safe = np.maximum(cnt, 1)[..., None]
    return acc / safe, cnt > 0


def _label(mask):
    """4-connected components, small images so a plain BFS is fine."""
    h, w = mask.shape
    lab = np.zeros((h, w), dtype=np.int32)
    cur = 0
    for sy in range(h):
        for sx in range(w):
            if not mask[sy, sx] or lab[sy, sx]:
                continue
            cur += 1
            stack = [(sy, sx)]
            lab[sy, sx] = cur
            while stack:
                y, x = stack.pop()
                for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not lab[ny, nx]:
                        lab[ny, nx] = cur
                        stack.append((ny, nx))
    return lab, cur


def dewhite(im, min_size=8, pure=243, flat=11, ring_max=170):
    """Punch out leftover page-white trapped inside the silhouette.

    Only uniform, genuinely-white blobs go; gradient-y white (real highlights,
    the smoke species) has a higher spread and is kept.
    """
    a = np.asarray(im.convert("RGBA")).astype(np.int16)
    rgb, alpha = a[..., :3], a[..., 3]
    opaque = alpha > 128
    mn = rgb.min(axis=2)
    sat = rgb.max(axis=2) - mn
    cand = opaque & (mn >= 238) & (sat <= 12)
    if not cand.any():
        return im, 0
    lab, n = _label(cand)
    lum = rgb.mean(axis=2)
    killed = np.zeros_like(cand)
    removed = 0
    for i in range(1, n + 1):
        sel = lab == i
        size = int(sel.sum())
        if size < min_size:
            continue                       # speck: leave it, likely a highlight
        if lum[sel].std() > flat:
            continue                       # gradient: painted highlight, keep
        if mn[sel].mean() < pure:
            continue                       # not actually page white
        # page showing through a gap sits behind the art's dark outline; white
        # that IS the art (the smoke species) fades into light neighbours
        ring = np.zeros_like(sel)
        for dy in (-2, -1, 0, 1, 2):
            for dx in (-2, -1, 0, 1, 2):
                ring |= np.roll(np.roll(sel, dy, 0), dx, 1)
        ring = ring & opaque & ~cand
        if ring.sum() >= 4 and lum[ring].mean() >= ring_max:
            continue                       # surrounded by light art: keep it
        killed |= sel
        removed += size
    if removed:
        alpha = alpha.copy()
        alpha[killed] = 0
        a = np.dstack([rgb, alpha]).astype(np.uint8)
        im = Image.fromarray(a, "RGBA")
    return im, removed


def despeckle(im, iters=3):
    """Eat small white specks by recolouring them from their neighbours.

    Each pass only recolours a white pixel that is mostly surrounded by
    coloured art, so a speck disappears entirely while a large white area
    (the smoke species) only loses its outermost ring, which the rim pass
    would smooth anyway.
    """
    a = np.asarray(im.convert("RGBA")).astype(np.float32)
    rgb, alpha = a[..., :3].copy(), a[..., 3]
    opaque = alpha > 128
    changed = 0
    for _ in range(iters):
        mn = rgb.min(axis=2)
        sat = rgb.max(axis=2) - mn
        white = opaque & (mn >= 238) & (sat <= 12)
        if not white.any():
            break
        art = opaque & ~white
        nb_sum = np.zeros_like(rgb)
        nb_cnt = np.zeros(white.shape, dtype=np.float32)
        wht_cnt = np.zeros(white.shape, dtype=np.float32)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dy == 0 and dx == 0:
                    continue
                r = np.roll(np.roll(rgb, dy, 0), dx, 1)
                k = np.roll(np.roll(art, dy, 0), dx, 1).astype(np.float32)
                nb_sum += r * k[..., None]
                nb_cnt += k
                wht_cnt += np.roll(np.roll(white, dy, 0), dx, 1).astype(np.float32)
        # more coloured neighbours than white ones -> it is a speck, not a region
        target = white & (nb_cnt >= 3) & (nb_cnt > wht_cnt)
        if not target.any():
            break
        mean = nb_sum / np.maximum(nb_cnt, 1)[..., None]
        rgb = np.where(target[..., None], mean, rgb)
        changed += int(target.sum())
    out = np.dstack([rgb.astype(np.uint8), alpha.astype(np.uint8)])
    return Image.fromarray(out, "RGBA"), changed


def clean(im, scale=2.0, erode=1, bleed=14, supersample=4):
    """im: RGBA PIL image with hard-edged, white-contaminated alpha."""
    a = np.asarray(im.convert("RGBA")).astype(np.float32)
    rgb, alpha = a[..., :3], a[..., 3]
    solid = alpha > 128

    # 1. drop the contaminated outer ring (it is a blend of plant and white page)
    keep = solid.copy()
    for _ in range(erode):
        shrunk = keep.copy()
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            shrunk &= np.roll(np.roll(keep, dy, 0), dx, 1)
        keep = shrunk
    if keep.sum() < solid.sum() * 0.25:      # tiny sprite: erosion would eat it
        keep = solid.copy()

    # 2. any surviving rim pixel that is much brighter than its inner
    #    neighbours is still contaminated - repaint it from the body
    inner = keep.copy()
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        inner &= np.roll(np.roll(keep, dy, 0), dx, 1)
    rim = keep & ~inner
    if rim.any() and inner.any():
        nb, ok = _neighbour_mean(rgb * inner[..., None], inner)
        lum = rgb.mean(axis=2)
        nb_lum = nb.mean(axis=2)
        hot = rim & ok & (lum > nb_lum + 20)
        rgb = np.where(hot[..., None], nb, rgb)

    # 3. bleed body colour outwards so filtering never samples white
    out = rgb.copy()
    known = keep.copy()
    for _ in range(bleed):
        nb, ok = _neighbour_mean(out * known[..., None], known)
        fill = ok & ~known
        out = np.where(fill[..., None], nb, out)
        known = known | fill
    out = np.where(known[..., None], out, np.float32(0))

    # 4. anti-alias the alpha: supersample, blur, come back down
    w, h = im.size
    mask = Image.fromarray((keep * 255).astype(np.uint8), "L")
    big = mask.resize((w * supersample, h * supersample), Image.LANCZOS)
    big = big.filter(ImageFilter.GaussianBlur(supersample * 0.5))
    tw, th = max(1, round(w * scale)), max(1, round(h * scale))
    soft = big.resize((tw, th), Image.LANCZOS)
    soft = np.asarray(soft).astype(np.float32)
    soft = np.clip((soft - 96) * (255.0 / (208 - 96)), 0, 255)   # tighten the ramp

    colour = Image.fromarray(out.astype(np.uint8), "RGB").resize((tw, th), Image.LANCZOS)
    res = np.dstack([np.asarray(colour).astype(np.uint8), soft.astype(np.uint8)])
    return Image.fromarray(res, "RGBA")


def encode(im, quality=90):
    buf = io.BytesIO()
    im.save(buf, "WEBP", quality=quality, method=6, alpha_quality=100)
    return buf.getvalue()


def rim_delta(im):
    """How much brighter the outer edge is than the body - the halo metric."""
    a = np.asarray(im.convert("RGBA")).astype(np.float32)
    al = a[..., 3] > 128
    if al.sum() == 0:
        return 0.0
    inner = al.copy()
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        inner &= np.roll(np.roll(al, dy, 0), dx, 1)
    rim = al & ~inner
    if not rim.any() or not inner.any():
        return 0.0
    lum = a[..., :3].mean(axis=2)
    return float(lum[rim].mean() - lum[inner].mean())
