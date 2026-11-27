"""Ships Feather's Oraxen font glyphs as mod font assets (stdlib only).

Reads (never writes) the Oraxen folder and rewrites:

  assets/feather/font/hub.json, assets/feather/textures/hub/*.png
      the hub's own font feather:hub (night panels, moon phases U+E200+phase, shift spaces), copied as is from
      Oraxen/pack/assets/feather (drawn by Feather's art/hub_art.py)
  assets/feather/font/hub_banner.json, assets/feather/textures/hub/banner.png
      the banner (ex Oraxen animated glyph feather_hub_banner): its 16 stacked frames become U+E000..U+E00F; the hub
      only ever sends U+E000 and the client (BannerGlyphMixin) swaps in the frame for the time at 6 fps
  assets/aftrhrs/font/glyph/<name>.json, assets/aftrhrs/textures/font/glyph/<name>.png
      every glyph of Oraxen/glyphs/arcana_guide.yml (guide_<x>, guide_icon_<x>): one font per glyph drawing U+E000
      (core GuideGlyphs). The market stall background (feather_stall_gui) ships with the shop port.

Core reads these through HubArt / GuideGlyphs (only when the font file is in the jar). Rerun after hub_art.py or
gen_guide.py change the art:  python mod/tools/gen_glyphs.py [--oraxen <Oraxen folder>]
"""
import argparse
import json
import os
import re
import shutil
import struct

ap = argparse.ArgumentParser()
ap.add_argument('--oraxen', default=r'C:/Users/kevin/Desktop/plugins/Oraxen')
args = ap.parse_args()

ORAXEN = args.oraxen
MOD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(MOD, 'src', 'main', 'resources', 'assets')

BANNER_FRAMES = 16
BANNER_FONT_CP = 0xE000


def png_size(path):
    with open(path, 'rb') as f:
        head = f.read(24)
    if head[:8] != b'\x89PNG\r\n\x1a\n':
        raise SystemExit(path + ' is not a PNG')
    return struct.unpack('>II', head[16:24])


def clear(path):
    if os.path.isdir(path):
        shutil.rmtree(path)
    os.makedirs(path, exist_ok=True)


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=1, ensure_ascii=True)
        f.write('\n')


def glyph_yml(path):
    """The flat glyph files: '<name>:' then indented 'key: value' lines (texture, ascent, height, animation ...)."""
    out, cur = {}, None
    with open(path, encoding='utf-8') as f:
        for raw in f:
            line = raw.split('#', 1)[0].rstrip()
            if not line.strip():
                continue
            m = re.match(r'^([A-Za-z0-9_\-]+):\s*$', line)
            if m:
                cur = out.setdefault(m.group(1), {})
                continue
            m = re.match(r'^  ([a-z_]+):\s*(.+)$', line)
            if m and cur is not None:
                cur[m.group(1)] = m.group(2).strip().strip('"\'')
    return out


def main():
    pack = os.path.join(ORAXEN, 'pack')

    # ---- feather:hub (copied as is) ---------------------------------------------------------------------------
    src_font = os.path.join(pack, 'assets', 'feather', 'font', 'hub.json')
    src_tex = os.path.join(pack, 'assets', 'feather', 'textures', 'hub')
    font_dir = os.path.join(ASSETS, 'feather', 'font')
    tex_dir = os.path.join(ASSETS, 'feather', 'textures', 'hub')
    clear(font_dir)
    clear(tex_dir)
    with open(src_font, encoding='utf-8') as f:
        hub = json.load(f)
    write_json(os.path.join(font_dir, 'hub.json'), hub)
    for name in sorted(os.listdir(src_tex)):
        if name.endswith('.png'):
            shutil.copyfile(os.path.join(src_tex, name), os.path.join(tex_dir, name))

    # ---- feather:hub_banner (animated glyph -> one char per frame) ---------------------------------------------
    banner = glyph_yml(os.path.join(ORAXEN, 'glyphs', 'feather_hub.yml'))['feather_hub_banner']
    frames = BANNER_FRAMES  # animation: frames 16, fps 6 (nested keys; the client mixin assumes 16)
    btex = os.path.join(pack, 'textures', banner['texture'] + '.png')
    w, h = png_size(btex)
    if h % frames:
        raise SystemExit('banner height %d is not %d frames' % (h, frames))
    shutil.copyfile(btex, os.path.join(tex_dir, 'banner.png'))
    write_json(os.path.join(font_dir, 'hub_banner.json'), {'providers': [{
        'type': 'bitmap', 'file': 'feather:hub/banner.png',
        'ascent': int(banner['ascent']), 'height': int(banner['height']),
        'chars': [chr(BANNER_FONT_CP + i) for i in range(frames)],
    }]})

    # ---- aftrhrs:glyph/<name> (taming guide) ------------------------------------------------------------------
    gfont = os.path.join(ASSETS, 'aftrhrs', 'font', 'glyph')
    gtex = os.path.join(ASSETS, 'aftrhrs', 'textures', 'font', 'glyph')
    # Only our own files: the shop's feather_stall_gui lives in font/glyph too.
    os.makedirs(gfont, exist_ok=True)
    clear(gtex)
    for old in os.listdir(gfont):
        if old.startswith('guide_'):
            os.remove(os.path.join(gfont, old))
    guide = glyph_yml(os.path.join(ORAXEN, 'glyphs', 'arcana_guide.yml'))
    for name, g in sorted(guide.items()):
        src = os.path.join(pack, 'textures', g['texture'] + '.png')
        if not os.path.isfile(src):
            print('missing texture for', name, src)
            continue
        shutil.copyfile(src, os.path.join(gtex, name + '.png'))
        write_json(os.path.join(gfont, name + '.json'), {'providers': [{
            'type': 'bitmap', 'file': 'aftrhrs:font/glyph/' + name + '.png',
            'ascent': int(g['ascent']), 'height': int(g['height']), 'chars': ['\ue000'],
        }]})
    print('hub font, banner (%d frames, %dx%d), %d guide glyphs' % (frames, w, h // frames, len(guide)))


main()
