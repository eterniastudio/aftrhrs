#!/usr/bin/env python3
"""Contact sheets of the in-repo fishing models (dev tool, needs numpy + Pillow and the Minecraft-faithful software
renderer arcana_items/plants3d/mcrender.py, like tools/verify_render.py). Writes to mod/build/fishart/:
  gui.png           every species as the inventory shows it (GUI pose), 10 a row, with its id
  views_<n>.png     per species: GUI | 3/4 model | 1st person | 3rd person | ground | frame/plaque
  compare.png       the 22 Oraxen fish next to a few new ones in the GUI pose (style check)
Run: python mod/tools/fishart/sheet.py [--views] [fish_id ...]
"""
import glob
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, r'C:/Users/kevin/Documents/Blockbench/models/arcana_items/plants3d')
import mcrender as R  # noqa: E402

PACK = os.path.join(MOD, 'content', 'pack')
ORAXEN_PACK = r'C:/Users/kevin/Desktop/plugins/Oraxen/pack'
MD = [os.path.join(PACK, 'models'), os.path.join(ORAXEN_PACK, 'models')]
TD = [os.path.join(PACK, 'textures'), os.path.join(ORAXEN_PACK, 'textures')]
OUT = os.path.join(MOD, 'build', 'fishart')


def rxyz(r):
    x, y, z = (math.radians(v) for v in r)
    rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return rx @ ry @ rz


def quads(ref, ctx, extra=np.zeros(3)):
    m = R.load_model(ref, MD)
    d = m['display'].get(ctx, {})
    T = np.array(d.get('translation', [0, 0, 0]), dtype=float) / 16
    S = np.array(d.get('scale', [1, 1, 1]), dtype=float)
    Rm = rxyz(d.get('rotation', [0, 0, 0]))
    out = []
    for verts, uvs, tarr, sh, tint in R.model_quads(m, tex_dirs=TD):
        v = (Rm @ ((verts - 0.5) * S).T).T + T + extra
        n = np.cross(v[1] - v[0], v[2] - v[1])
        n0 = np.cross(verts[1] - verts[0], verts[2] - verts[1])
        nn = np.linalg.norm(n)
        unshaded = sh == 1.0 and np.linalg.norm(n0) > 1e-9 and -n0[1] / np.linalg.norm(n0) < 0.7
        if nn > 1e-9 and not unshaded:
            n = -n / nn
            ax = int(np.argmax(np.abs(n)))
            sh2 = {0: 0.7, 1: 1.0 if n[1] > 0 else 0.55, 2: 0.9 if n[2] > 0 else 0.6}[ax]
        else:
            sh2 = sh
        out.append((v, uvs, tarr, sh2, tint))
    return out


def view(ref, ctx, W=200, H=200):
    if ctx == 'gui':
        return R.render(quads(ref, 'gui'), R.Camera((0, 0, 0), 3, 0, 0, ortho=1.0), W, H, bg=(139, 139, 139), sky_grad=False)
    if ctx == 'firstperson_righthand':
        q = quads(ref, ctx, np.array([0.56, -0.52, -0.72]))
        return R.render(q, R.Camera((0, 0, -1), 1, 0, 0, fov=70), int(H * 16 / 9), H, bg=(120, 160, 210))
    if ctx == 'ground':
        return R.render(quads(ref, 'ground'), R.Camera((0, 0, 0), 1.6, 30, 25, fov=40), W, H, bg=(120, 160, 210))
    if ctx == 'thirdperson_righthand':
        return R.render(quads(ref, ctx), R.Camera((0, 0.1, 0), 1.4, 120, 15, fov=40), W, H, bg=(120, 160, 210))
    if ctx == 'fixed':
        return R.render(quads(ref, 'fixed'), R.Camera((0, 0, 0), 2.2, 180, 0, ortho=1.0), W, H, bg=(110, 80, 50), sky_grad=False)
    m = R.load_model(ref, MD)
    q = R.model_quads(m, (-0.5, -0.5, -0.5), tex_dirs=TD)
    return R.render(q, R.Camera((0, 0, 0), 2.4, 35, 25, fov=35), W, H, bg=(60, 64, 76))


def names_new():
    return sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(PACK, 'models', 'arcana', 'fishing', 'fish_*.json')))


def gui_grid(names, path, S=96, cols=10):
    rows = (len(names) + cols - 1) // cols
    im = Image.new('RGB', (cols * S, rows * (S + 14)), (139, 139, 139))
    d = ImageDraw.Draw(im)
    for i, n in enumerate(names):
        x, y = (i % cols) * S, (i // cols) * (S + 14)
        g = view('arcana/fishing/' + n, 'gui', S, S)
        im.paste(g.convert('RGB'), (x, y))
        d.text((x + 2, y + S), n[5:][:16], fill=(20, 20, 20))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)
    return path


def views(names, path):
    ctxs = ['gui', 'free', 'firstperson_righthand', 'thirdperson_righthand', 'ground', 'fixed']
    S = 160
    widths = [S, S, int(S * 16 / 9), S, S, S]
    im = Image.new('RGB', (sum(widths), len(names) * (S + 14)), (30, 30, 36))
    d = ImageDraw.Draw(im)
    for r, n in enumerate(names):
        x = 0
        for c, w in zip(ctxs, widths):
            im.paste(view('arcana/fishing/' + n, c, S, S).convert('RGB'), (x, r * (S + 14) + 14))
            x += w
        d.text((4, r * (S + 14) + 1), n + '   (gui | model | 1st person | 3rd person | ground | frame)', fill=(230, 230, 230))
    im.save(path)
    return path


if __name__ == '__main__':
    args = sys.argv[1:]
    want_views = '--views' in args
    args = [a for a in args if a != '--views']
    names = [a if a.startswith('fish_') else 'fish_' + a for a in args] or names_new()
    print(gui_grid(names, os.path.join(OUT, 'gui.png')))
    old = sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(ORAXEN_PACK, 'models', 'arcana', 'fishing', 'fish_*.json')))
    print(gui_grid(old + names[:8], os.path.join(OUT, 'compare.png')))
    if want_views:
        for i in range(0, len(names), 8):
            print(views(names[i:i + 8], os.path.join(OUT, 'views_%d.png' % (i // 8))))
