import numpy as np
from PIL import Image

def _nb_mean(rgb, known):
    acc=np.zeros_like(rgb); cnt=np.zeros(known.shape, np.float32)
    for dy,dx in ((-1,0),(1,0),(0,-1),(0,1)):
        r=np.roll(np.roll(rgb,dy,0),dx,1); k=np.roll(np.roll(known,dy,0),dx,1).astype(np.float32)
        acc+=r*k[...,None]; cnt+=k
    return acc/np.maximum(cnt,1)[...,None], cnt>0

def cut(src, y0, y1, x0, x1, pad=6, bleed=10):
    """Crop a sprite using the file's own alpha, then push art colour outward.

    Transparent pixels in this sheet carry leftover gradient data. Left alone,
    any downscale samples it and drags coloured fringing onto every edge, so the
    colour outside the silhouette is replaced with the colour just inside it."""
    h,w = src.shape[:2]
    yy0,yy1=max(0,y0-pad),min(h,y1+pad); xx0,xx1=max(0,x0-pad),min(w,x1+pad)
    crop=src[yy0:yy1, xx0:xx1].astype(np.float32)
    rgb, alpha = crop[...,:3].copy(), crop[...,3]
    known = alpha > 32
    if known.any():
        out=np.where(known[...,None], rgb, 0)
        k=known.copy()
        for _ in range(bleed):
            nb, ok = _nb_mean(out, k)
            fill = ok & ~k
            out = np.where(fill[...,None], nb, out)
            k = k | fill
        rgb = np.where(k[...,None], out, 0)
    img=Image.fromarray(np.dstack([rgb.astype(np.uint8), alpha.astype(np.uint8)]), "RGBA")
    bb=img.getchannel("A").point(lambda v:255 if v>12 else 0).getbbox()
    return img.crop(bb) if bb else img
