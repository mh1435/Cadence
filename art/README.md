# Plant art pipeline

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
