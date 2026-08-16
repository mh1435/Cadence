# Plant art pipeline

## Check the alpha channel before writing any keying code

The full 28-species sheet arrived as a 6144x4096 **RGBA** PNG with a perfectly
good alpha channel: 62.5% of it fully transparent. Transparent pixels still
carry RGB, and in this file that leftover data is a colourful gradient. Opening
it with `.convert("RGB")` throws the alpha away and shows that gradient as if it
were a background, which is exactly what happened: a whole background-estimation
pipeline was built to key out something that was never there, and the results
were worse than the alpha already in the file.

`extract_alpha.py` is the correct path: read the alpha, segment on it, and only
replace the RGB *outside* the silhouette so a downscale cannot sample that
leftover gradient and fringe every edge.

If a future sheet does need keying, check `Image.open(f).mode` first.

## Two sources, two pipelines

Seven species now use hand-drawn growth strips supplied at roughly 1536x1024
per strip — about 14x the pixel area of the original sheet. Those go through
`extract_black.py` + `apply_new.py`. Everything else still comes from the
original growth sheet via `clean_art.py`, described further down.

| Species | Strip | Species | Strip |
| --- | --- | --- | --- |
| Blush Cap | blue mushrooms | Golden Orchard | white blossom, heart fruit |
| Ember Fungus | red mushrooms on blue | Moon Willow | blue willow with lanterns |
| Cherry Blossom | pink blossom | Crystal Bloom | blue/purple crystal tree |
| Elder Oak | glowing green tree | | |

### Keying black instead of white

These strips are drawn on black, which is the mirror of the original problem:
a hard threshold leaves a *dark* fringe, because the edge pixels are the art
faded toward the backdrop. `extract_black.py` builds a coverage ramp, seals
dark interior outlines with a morphological close (not a flood fill — these
are ~1.5M pixel images and a per-pixel flood in Python is far too slow), then
divides the colour back out by its own coverage so edges keep the real hue.

Stages are split on the empty columns between them, and all four frames of a
species are scaled by **one** factor so the seed stays small next to the tree.

### Strips that cannot be keyed

Two supplied strips have a glowing backdrop rather than a black one. No
threshold separates art from backdrop: keying softly keeps the glow as opaque
rectangles, keying hard strips the trunk and leaves off the plant. They are
not in the app. A re-export on solid black is the fix — there is no image
processing trick that recovers them.


The 112 sprites in `PLANT_ART` (28 species x 4 growth stages) are cut from the
growth-stage sheet and cleaned by `clean_art.py` before being embedded in
`index.html` as WebP data URIs.

## Why the cleanup exists

The original cut left three defects, all visible once a sprite was scaled up on
a phone:

1. **A white rim.** The outermost opaque pixels were a blend of plant and the
   sheet's white page — measured at luminance 195 against a body average of
   113, on every single frame.
2. **White trapped inside the silhouette.** Gaps enclosed by the art (inside the
   Crimson Curl's loops, between the canopy leaves) were never reached by the
   edge-seeded background fill, so they stayed page-white.
3. **White bleeding in when scaled.** The transparent region still carried RGB
   247,247,247, so any filtering sampled white and smeared it over the edges.

`clean_art.py` fixes them in order: erode the contaminated ring, delete enclosed
white blobs, recolour small white specks from their neighbours, bleed body
colour outward into the transparent area, anti-alias the alpha, and upscale.

## The one rule that needs care

Deleting enclosed white is only safe when the white is *background*. The Mist
Vine's art is legitimately white smoke, and an early version punched holes
straight through it. The discriminator is the ring around the blob: page seen
through a gap sits behind the art's dark outline (ring luminance 99-150), while
white that *is* the art fades into light neighbours (ring luminance 172-200).
`ring_max=170` splits them. The two populations do overlap slightly, so after
any change to these thresholds, render a contact sheet of all 28 species and
look at it rather than trusting the numbers.

## species-slice-mapping.json

**Read this before regenerating the art.** The species list in `index.html` was
reordered by Sunlight price after the sheet was sliced, so species index N does
*not* correspond to slice `sNN`. Rebuilding on the naive assumption scrambles
every plant — the name and the picture stop matching, which is easy to miss in
a diff and obvious to a user.

This file records the true `species id -> slice index` mapping, recovered by
matching colour histograms of the mature frame against the previously shipped
art. It is a bijection over all 28 species.
