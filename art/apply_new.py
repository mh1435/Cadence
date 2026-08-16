import re, io, base64, json
import numpy as np
from PIL import Image

TARGET = 460
QUALITY = 88
ASSIGN = {                      # species id -> uploaded strip
    "blushcap":  "0873bb2f",    # blue mushrooms
    "emberfung": "46e427e2",    # red mushrooms on a blue base
    "cherry":    "4cd69527",    # pink cherry blossom
    "orchard":   "6d70ea5b",    # white blossom with the heart fruit
    "willow":    "ddacc879",    # blue willow with lanterns
    "crystal":   "19622dd7",    # blue/purple crystal tree
    "elder":     "1d6b4e4c",    # glowing green tree
}

def frames_for(key):
    ims = [Image.open("new_stages/%s_%d.png" % (key, k)).convert("RGBA") for k in range(4)]
    # one scale factor for the whole species, so the seed stays small next to the tree
    biggest = max(max(im.size) for im in ims)
    f = min(1.0, TARGET / biggest)
    out = []
    for im in ims:
        w, h = max(1, round(im.size[0]*f)), max(1, round(im.size[1]*f))
        out.append(im.resize((w, h), Image.LANCZOS))
    return out

def encode(im):
    b = io.BytesIO()
    im.save(b, "WEBP", quality=QUALITY, method=6, alpha_quality=100)
    return b.getvalue()

def dark_fringe(im):
    """Edge pixels should not be darker than the body: that would be black halo."""
    a = np.asarray(im).astype(np.float32)
    al = a[..., 3] > 140
    if al.sum() < 20: return 0.0
    inner = al.copy()
    for dy, dx in ((-1,0),(1,0),(0,-1),(0,1)):
        inner &= np.roll(np.roll(al, dy, 0), dx, 1)
    rim = al & ~inner
    if not rim.any() or not inner.any(): return 0.0
    lum = a[..., :3].mean(axis=2)
    return float(lum[inner].mean() - lum[rim].mean())

src = open('/home/user/Last-Pact/index.html', encoding='utf-8').read()
i = src.index('const PLANT_ART'); j = src.index('\n};', i)
block = src[i:j]
pairs = re.findall(r'"(\w+)":\[((?:"data:image/webp;base64,[^"]+",?)+)\]', block)
art = {sid: re.findall(r'"(data:image/webp;base64,[^"]+)"', arr) for sid, arr in pairs}
order = [sid for sid, _ in pairs]

total = 0
for sid, key in ASSIGN.items():
    ims = frames_for(key)
    uris = []
    for k, im in enumerate(ims):
        d = encode(im); total += len(d)
        uris.append("data:image/webp;base64," + base64.b64encode(d).decode())
    art[sid] = uris
    print("  %-10s <- %s  %s  fringe %+.1f" % (sid, key, [im.size for im in ims], dark_fringe(ims[3])))

js = "const PLANT_ART = {\n" + ",\n".join('  "%s":[%s]' % (s, ",".join('"%s"' % u for u in art[s])) for s in order) + "\n};"
open("plant_art_new.js", "w").write(js)
print("new art %.0f KB webp; whole block %.0f KB" % (total/1024, len(js)/1024))
