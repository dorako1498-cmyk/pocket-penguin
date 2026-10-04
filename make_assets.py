# Original penguin part art for Pocket Penguin v0.6 (generated with Pillow; no third-party art used).
# Usage: python3 tools/make_assets.py [out_dir]   (copy pg_*.png to app/src/main/res/drawable-nodpi and PartLayout.java next to the sources)
import numpy as np, math, os, sys
from PIL import Image, ImageDraw
from scipy.ndimage import gaussian_filter, distance_transform_edt

W, H, S = 640, 800, 3
OUT_DIR = sys.argv[1] if len(sys.argv) > 1 else 'out'
os.makedirs(OUT_DIR, exist_ok=True)

OUTLINE = (16, 17, 20, 255)
DARK    = (63, 65, 71, 255)
DARK_HI = (92, 95, 104, 255)
CREAM   = (255, 248, 231, 255)
CREAM_SH= (243, 232, 207, 255)
ORANGE  = (245, 184, 94, 255)
ORANGE_HI=(252, 212, 140, 255)
ORANGE_SH=(226, 150, 66, 255)

def blank(): return np.zeros((H*S, W*S), bool)

def _draw(poly_pts):
    im = Image.new('L', (W*S, H*S), 0)
    ImageDraw.Draw(im).polygon([(x*S, y*S) for x, y in poly_pts], fill=255)
    return np.array(im) > 127

def ell(cx, cy, rx, ry, ang=0.0, n=160):
    a = math.radians(ang); pts = []
    for i in range(n):
        t = 2*math.pi*i/n
        x, y = rx*math.cos(t), ry*math.sin(t)
        pts.append((cx + x*math.cos(a) - y*math.sin(a), cy + x*math.sin(a) + y*math.cos(a)))
    return _draw(pts)

def smooth(m, r):
    return gaussian_filter(m.astype(np.float32), r*S) > 0.5

def grow(m, t):
    return distance_transform_edt(~m) <= t*S

def compose(layers):
    im = Image.new('RGBA', (W*S, H*S), (0, 0, 0, 0))
    for mask, col in layers:
        im.paste(Image.new('RGBA', im.size, col), mask=Image.fromarray((mask*255).astype(np.uint8)))
    return im

def with_outline(shape, fill, t=16, extra=()):
    L = [(grow(shape, t), OUTLINE), (shape, fill)]
    for m, c in extra: L.append((m & shape, c))
    return L

def yclip(h, y0, y1=None):
    m = np.zeros_like(h)
    m[int(y0*S):(int(y1*S) if y1 else None), :] = True
    return m

def finish(img, name, layout):
    small = img.resize((W, H), Image.LANCZOS)
    bb = small.getbbox()
    pad = 3
    l, t, r, b = max(0, bb[0]-pad), max(0, bb[1]-pad), min(W, bb[2]+pad), min(H, bb[3]+pad)
    small.crop((l, t, r, b)).save(os.path.join(OUT_DIR, name + '.png'))
    layout[name] = (l, t, r-l, b-t)
    return small

layout = {}
previews = {}

# ---- body
body = smooth(ell(320, 582, 192, 153) | ell(320, 505, 166, 128), 6)
belly = ell(320, 592, 136, 126)
belly_sh = belly & ~ell(300, 568, 138, 126)
body_L = with_outline(body, DARK, 16)
body_L += [(belly, CREAM), (belly_sh & belly, CREAM_SH)]
sheen = ell(212, 520, 14, 60, 12) & body
body_L += [(sheen & ~belly, DARK_HI)]
previews['body'] = finish(compose(body_L), 'pg_body', layout)

# ---- head (big, round; cream face "heart" lobes)
dome = smooth(ell(320, 255, 222, 190) | ell(320, 372, 186, 120), 8)
lobeL = ell(214, 330, 84, 124, 10); lobeR = ell(426, 330, 84, 124, -10)
chin  = ell(320, 420, 140, 76)
face = smooth(lobeL | lobeR | chin, 7)
face &= (dome | yclip(dome, 440))
head_L = [(grow(dome, 16), OUTLINE), (dome, DARK), (face, CREAM)]
gloss = ell(196, 120, 42, 17, -28) & dome
head_L += [(gloss, DARK_HI)]
previews['head'] = finish(compose(head_L), 'pg_head', layout)

# ---- beak
beak = smooth(ell(320, 366, 46, 27), 3)
beak_L = with_outline(beak, ORANGE, 10)
beak_L += [(ell(320, 378, 36, 12) & beak, ORANGE_SH), (ell(318, 357, 24, 8) & beak, ORANGE_HI)]
previews['beak'] = finish(compose(beak_L), 'pg_beak', layout)

# ---- wings (left drawn, right mirrored)
def wing_mask(mirror=False):
    sx, sy_, tx, ty = 172, 462, 98, 640
    m = blank(); N = 26
    for i in range(N+1):
        s = i/N
        cx = sx + (tx-sx)*s**0.95; cy = sy_ + (ty-sy_)*s
        r = 40 + 14*math.sin(min(1, s*1.4)*math.pi*.5) - 22*max(0, s-.55)**1.2*2.0
        m |= ell(cx + 6*math.sin(s*math.pi), cy, r, r*1.02)
    m = smooth(m, 7)
    return m[:, ::-1] if mirror else m
wl = wing_mask(False)
previews['wing_l'] = finish(compose(with_outline(wl, DARK, 16, extra=[(ell(120, 600, 14, 46, 14) & wl, DARK_HI)])), 'pg_wing_l', layout)
wr = wing_mask(True)
previews['wing_r'] = finish(compose(with_outline(wr, DARK, 16, extra=[(ell(520, 600, 14, 46, -14) & wr, DARK_HI)])), 'pg_wing_r', layout)

# ---- feet
def foot_mask(cx, cy):
    m = ell(cx, cy, 52, 24)
    for dx in (-34, 0, 34): m |= ell(cx + dx, cy + 17, 17, 14)
    return smooth(m, 3)
fl = foot_mask(246, 736); fr = foot_mask(394, 736)
previews['foot_l'] = finish(compose(with_outline(fl, ORANGE, 12, extra=[(ell(236, 724, 26, 7) & fl, ORANGE_HI)])), 'pg_foot_l', layout)
previews['foot_r'] = finish(compose(with_outline(fr, ORANGE, 12, extra=[(ell(384, 724, 26, 7) & fr, ORANGE_HI)])), 'pg_foot_r', layout)

# ---- tail (drawn on the right; mirrored at runtime)
tail = smooth(ell(468, 690, 54, 28, -28), 4)
previews['tail'] = finish(compose(with_outline(tail, DARK, 14)), 'pg_tail', layout)

# ---- preview (face features are drawn at runtime by Rig.java; here only for checking the look)
canvas = Image.new('RGBA', (W, H), (200, 226, 236, 255))
for n in ['tail', 'body', 'wing_l', 'wing_r', 'foot_l', 'foot_r', 'head', 'beak']:
    canvas.alpha_composite(previews[n])
d = ImageDraw.Draw(canvas)
for ex in (232, 408):
    d.ellipse([ex-22, 330-30, ex+22, 330+30], fill=(40, 42, 48, 255))
    d.ellipse([ex+2, 330-22, ex+14, 330-10], fill=(255, 255, 255, 255))
for cx in (176, 464):
    d.ellipse([cx-34, 392-24, cx+34, 392+24], fill=(247, 196, 188, 200))
canvas.convert('RGB').save(os.path.join(OUT_DIR, '_preview_rest.png'))

with open(os.path.join(OUT_DIR, 'PartLayout.java'), 'w') as f:
    f.write('package com.pocketpenguin;\n\n/** Generated by tools/make_assets.py. Offsets of each cropped PNG inside the 640x800 design canvas. */\nfinal class PartLayout {\n')
    f.write('    static final int CANVAS_W = %d, CANVAS_H = %d;\n' % (W, H))
    for k, (l, t, w, h) in layout.items():
        f.write('    static final int %s_L = %d, %s_T = %d;\n' % (k.upper(), l, k.upper(), t))
    f.write('}\n')
print(layout)
