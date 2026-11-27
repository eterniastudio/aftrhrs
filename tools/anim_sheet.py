#!/usr/bin/env python3
"""Contact sheet of generated clips (what the mod plays), rendered headless with bb-render.

  python anim_sheet.py <out.png> <clip>[@time|@end] <model> [<model> ...] [--view right] [--size 220]
  python anim_sheet.py sheet.png sit@end tabby_cat corgi maine_coon
  python anim_sheet.py sheet.png "walk@0.2,sit_down@0.45,sit@end" tabby_cat     (several clips: one row per model)

Builds the preview bbmodels with anim_preview.py first (scratch copies; sources untouched).
Needs the blockbench-mcp-headless render cache (installed by its first bbmodel_render).
"""
import json
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import anim_preview  # noqa: E402

CLI = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'blockbench-mcp-headless', 'render', 'cli.mjs')
GEO = os.path.join(anim_preview.RES, 'animations', 'entity')


def render(bb, clip, t, view, size, out):
    cmd = ['node', CLI, bb, '-o', out, '--width', str(size), '--height', str(size), '--supersample', '1',
           '--lighting', 'flat', '--view', view, '--quiet', '--ground', 'none', '--fit', 'box']
    if clip:
        cmd += ['--clip', clip, '--start', '%.3f' % t]
    subprocess.run(cmd, check=True, capture_output=True)


def main(argv):
    view, size = 'right', 220
    args = []
    i = 0
    while i < len(argv):
        if argv[i] == '--view':
            view = argv[i + 1]
            i += 2
        elif argv[i] == '--size':
            size = int(argv[i + 1])
            i += 2
        else:
            args.append(argv[i])
            i += 1
    out, spec, models = args[0], args[1], args[2:]
    shots = []
    for s in spec.split(','):
        name, _, at = s.partition('@')
        shots.append((name, at or '0'))
    cols = len(shots) if len(shots) > 1 else min(6, len(models))
    rows = len(models) if len(shots) > 1 else (len(models) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * size, rows * (size + 16)), (31, 34, 41))
    d = ImageDraw.Draw(sheet)
    tmp = tempfile.mkdtemp()
    k = 0
    for mi, m in enumerate(models):
        bb = anim_preview.preview(m)
        anims = anim_preview.load_gecko(os.path.join(GEO, m + '.animation.json'))['animations']
        for si, (clip, at) in enumerate(shots):
            if clip not in anims:
                label = '%s: no %s' % (m, clip)
                img = None
            else:
                L = float(anims[clip].get('animation_length', 0))
                t = L if at == 'end' else min(L, float(at))
                p = os.path.join(tmp, '%d.png' % k)
                try:
                    render(bb, clip, max(0.0, t - (0.001 if at == 'end' else 0)), view, size, p)
                    img = Image.open(p).convert('RGB')
                except subprocess.CalledProcessError as e:
                    img = None
                    print('render failed', m, clip, e.stderr[:300])
                label = '%s %s@%s' % (m, clip, at)
            if len(shots) > 1:
                x, y = si * size, mi * (size + 16)
            else:
                x, y = (k % cols) * size, (k // cols) * (size + 16)
            if img:
                sheet.paste(img, (x, y + 16))
            d.text((x + 3, y + 2), label[:36], fill=(230, 230, 230))
            k += 1
    sheet.save(out)
    print(out)


if __name__ == '__main__':
    main(sys.argv[1:])
