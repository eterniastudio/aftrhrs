"""Fairy lights content generator (stdlib only). Based on Fairy Lights by Paul Fulham (pau101), MIT.

Writes, from the one table of light shapes below:
  src/client/java/net/axther/aftrhrs/client/fairylights/BulbShapes.java   in-world light geometry (px)
  assets/aftrhrs/models/fairylights/**        item models (3D, same shapes) + the String Hook block model
  assets/aftrhrs/items/fairy_*.json           item definitions (dye / custom model data tints)
  assets/aftrhrs/blockstates/fairy_hook.json
  assets/aftrhrs/lang/en_us.fairylights.json
  data/aftrhrs/recipe/fairy_*.json            crafting, dyeing, twinkle (glowstone) and the pattern recipe
  data/aftrhrs/loot_table/blocks/fairy_hook.json
  data/aftrhrs/tags/item/fairy_*.json
gen_content.py calls main() at its end (it clears the shared folders first). Textures come from fairylights_art.py.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
RES = os.path.join(MOD, 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'aftrhrs')
DATA = os.path.join(RES, 'data', 'aftrhrs')
JAVA = os.path.join(MOD, 'src', 'client', 'java', 'net', 'axther', 'aftrhrs', 'client', 'fairylights', 'BulbShapes.java')

# ------------------------------------------------------------------------------------------------ materials
MATS = ['dark', 'green', 'brass', 'iron', 'glass', 'clear', 'paper', 'rib', 'candle', 'flame', 'filament', 'yellow', 'stem',
        'pumpkin', 'face', 'eyes']
COLORS = {'dark': 0x2B2A2C, 'green': 0x2F4A2C, 'brass': 0xC79A3E, 'iron': 0x3D3E44, 'glass': 0xFFFFFF, 'clear': 0xE8EEF2,
          'paper': 0xFFFFFF, 'rib': 0xFFFFFF, 'candle': 0xF0E4C8, 'flame': 0xFFB347, 'filament': 0xFFC46A, 'yellow': 0xFFD54A,
          'stem': 0x4F7A2E, 'pumpkin': 0xE47A22, 'face': 0xFFD86A, 'eyes': 0x231A1A}
TINTED = {'glass', 'paper', 'rib'}

# ------------------------------------------------------------------------------------------------ light shapes
# Pixels; the light hangs from the cord at (0, 0, 0), y goes down. x runs along the cord for the flat "along cord"
# styles (star, snowflake, heart, flower). Each part: (x0, y0, z0, x1, y1, z1, material).


def sym(x, y0, z, y1, mat, zt=None):
    """Box centred on x/z = 0: half widths x and z (zt: z half width, default = x)."""
    zz = x if zt is None else zt
    return (-x, y0, -zz, x, y1, zz, mat)


SHAPES = {
    # rounded voxel bulbs: a cross of boxes plus a taller core reads round at a distance
    'fairy': [sym(0.5, -1.0, 0, 0.0, 'dark'), (-1.1, -3.1, -0.7, 1.1, -1.3, 0.7, 'glass'), (-0.7, -3.1, -1.1, 0.7, -1.3, 1.1, 'glass'),
              sym(0.7, -3.5, 0, -0.95, 'glass')],
    'holiday': [sym(0.9, -1.8, 0, 0.0, 'green'), sym(1.05, -0.6, 0, -0.3, 'green'), (-1.4, -3.5, -1.0, 1.4, -1.8, 1.0, 'glass'),
                (-1.0, -3.5, -1.4, 1.0, -1.8, 1.4, 'glass'), sym(1.1, -4.4, 0, -3.5, 'glass'), sym(0.7, -5.1, 0, -4.4, 'glass'),
                sym(0.35, -5.6, 0, -5.1, 'glass')],
    'edison': [sym(1.0, -1.6, 0, 0.0, 'brass'), sym(1.25, -2.0, 0, -1.6, 'brass'), (-1.9, -5.4, -1.3, 1.9, -2.2, 1.3, 'clear'), (-1.3, -5.4, -1.9, 1.3, -2.2, 1.9, 'clear'), sym(1.3, -5.9, 0, -2.0, 'clear'),
               (-0.6, -4.6, -0.15, 0.6, -2.4, 0.15, 'filament'), sym(0.15, -2.4, 0, -2.0, 'iron')],
    'paper_lantern': [sym(0.35, -1.0, 0, 0.0, 'iron'), sym(2.2, -1.6, 0, -1.0, 'dark'), sym(3.0, -7.6, 0, -1.6, 'paper'),
                      sym(3.1, -3.7, 0, -3.3, 'rib'), sym(3.1, -6.0, 0, -5.6, 'rib'), sym(2.2, -8.2, 0, -7.6, 'dark'),
                      sym(0.3, -9.4, 0, -8.2, 'dark')],
    'orb_lantern': [sym(0.4, -1.4, 0, 0.0, 'iron'), sym(2.2, -6.2, 0, -1.4, 'paper'), (-2.8, -5.6, -1.6, 2.8, -2.0, 1.6, 'paper'),
                    (-1.6, -5.6, -2.8, 1.6, -2.0, 2.8, 'paper'), sym(2.85, -4.0, 0, -3.6, 'rib', 1.65), sym(1.65, -4.0, 0, -3.6, 'rib', 2.85)],
    'star': [sym(0.3, -1.0, 0, 0.0, 'dark'), (-1.5, -5.4, -0.6, 1.5, -2.6, 0.6, 'glass'), (-0.6, -2.6, -0.6, 0.6, -1.0, 0.6, 'glass'),
             (-3.4, -4.4, -0.6, -1.5, -3.4, 0.6, 'glass'), (1.5, -4.4, -0.6, 3.4, -3.4, 0.6, 'glass'),
             (-2.6, -7.6, -0.6, -1.0, -5.4, 0.6, 'glass'), (1.0, -7.6, -0.6, 2.6, -5.4, 0.6, 'glass')],
    'snowflake': [sym(0.3, -1.0, 0, 0.0, 'dark'), (-0.45, -7.8, -0.45, 0.45, -1.0, 0.45, 'glass'),
                  (-3.4, -4.85, -0.45, 3.4, -3.95, 0.45, 'glass'), (-1.1, -5.5, -0.5, 1.1, -3.3, 0.5, 'glass'),
                  (-2.6, -7.0, -0.4, -1.6, -6.0, 0.4, 'glass'), (1.6, -7.0, -0.4, 2.6, -6.0, 0.4, 'glass'),
                  (-2.6, -2.8, -0.4, -1.6, -1.8, 0.4, 'glass'), (1.6, -2.8, -0.4, 2.6, -1.8, 0.4, 'glass'),
                  (-3.6, -5.6, -0.4, -2.9, -3.2, 0.4, 'glass'), (2.9, -5.6, -0.4, 3.6, -3.2, 0.4, 'glass'),
                  (-1.2, -8.2, -0.4, 1.2, -7.6, 0.4, 'glass')],
    'icicle': [sym(0.4, -0.6, 0, 0.0, 'dark'), sym(1.1, -3.0, 0, -0.6, 'glass'), sym(0.8, -6.0, 0, -3.0, 'glass'),
               sym(0.55, -9.0, 0, -6.0, 'glass'), sym(0.3, -11.2, 0, -9.0, 'glass')],
    'candle_lantern': [(-0.6, -1.2, -0.6, 0.6, 0.0, 0.6, 'iron'), sym(2.4, -2.0, 0, -1.2, 'iron'), sym(2.4, -7.8, 0, -7.0, 'iron'),
                       (-2.4, -7.0, -2.4, -1.8, -2.0, -1.8, 'iron'), (1.8, -7.0, -2.4, 2.4, -2.0, -1.8, 'iron'),
                       (-2.4, -7.0, 1.8, -1.8, -2.0, 2.4, 'iron'), (1.8, -7.0, 1.8, 2.4, -2.0, 2.4, 'iron'),
                       sym(0.7, -7.0, 0, -4.4, 'candle'), sym(0.3, -4.4, 0, -3.4, 'flame'), sym(1.9, -7.0, 0, -2.0, 'clear')],
    'heart': [sym(0.3, -1.0, 0, 0.0, 'dark'), (-3.0, -3.8, -0.6, -0.2, -1.2, 0.6, 'glass'), (0.2, -3.8, -0.6, 3.0, -1.2, 0.6, 'glass'),
              (-2.4, -5.0, -0.6, 2.4, -2.6, 0.6, 'glass'), (-1.4, -6.0, -0.6, 1.4, -5.0, 0.6, 'glass'),
              (-0.5, -6.6, -0.6, 0.5, -6.0, 0.6, 'glass')],
    'flower': [sym(0.3, -0.6, 0, 0.0, 'stem'), (-0.8, -2.1, -0.5, 0.8, -0.6, 0.5, 'glass'), (-0.8, -5.4, -0.5, 0.8, -3.9, 0.5, 'glass'),
               (-3.0, -3.8, -0.5, -0.9, -2.2, 0.5, 'glass'), (0.9, -3.8, -0.5, 3.0, -2.2, 0.5, 'glass'),
               (-0.9, -3.9, -0.7, 0.9, -2.1, 0.7, 'yellow')],
    'pumpkin': [sym(0.4, -1.4, 0, 0.0, 'stem'), (-2.6, -6.0, -2.0, 2.6, -1.4, 2.0, 'pumpkin'), (-2.0, -6.3, -2.6, 2.0, -1.1, 2.6, 'pumpkin'),
                (-1.6, -3.2, 2.6, -0.6, -2.4, 2.75, 'face'), (0.6, -3.2, 2.6, 1.6, -2.4, 2.75, 'face'),
                (-1.6, -4.8, 2.6, 1.6, -4.0, 2.75, 'face')],
    'ghost': [sym(0.3, -1.0, 0, 0.0, 'dark'), sym(1.6, -1.8, 0, -1.0, 'glass'), sym(2.2, -6.0, 0, -1.8, 'glass'),
              (-2.2, -7.0, -2.2, -1.0, -6.0, 2.2, 'glass'), (-0.5, -7.4, -2.2, 0.5, -6.0, 2.2, 'glass'), (1.0, -7.0, -2.2, 2.2, -6.0, 2.2, 'glass'),
              (-1.2, -3.4, 2.2, -0.4, -2.4, 2.32, 'eyes'), (0.4, -3.4, 2.2, 1.2, -2.4, 2.32, 'eyes')],
}
# Far detail (LOD): 1-4 boxes per style that read the same once the light is a few pixels tall. The body is roughly its
# material's colour envelope (a little inside it, so it doesn't look bigger than the full shape) plus the cap it hangs by;
# the Edison bulb and the candle lantern keep their opaque core (filament / frame and flame) inside one CLEAR envelope.
# Picked by hand and checked with screenshots at 20-70 blocks. Never only a halo: a body is drawn at every distance.
LOD = {
    'fairy': [sym(0.5, -1.0, 0, 0.0, 'dark'), sym(0.95, -3.3, 0, -1.1, 'glass')],
    'holiday': [sym(0.9, -1.8, 0, 0.0, 'green'), sym(1.15, -5.0, 0, -1.8, 'glass')],
    'edison': [sym(1.1, -2.0, 0, 0.0, 'brass'), (-0.6, -4.6, -0.15, 0.6, -2.4, 0.15, 'filament'), sym(1.6, -5.9, 0, -2.0, 'clear')],
    'paper_lantern': [sym(2.2, -1.6, 0, -0.6, 'dark'), sym(3.0, -8.2, 0, -1.6, 'paper')],
    'orb_lantern': [sym(0.4, -1.4, 0, 0.0, 'iron'), sym(2.5, -6.0, 0, -1.6, 'paper')],
    'star': [sym(0.3, -1.0, 0, 0.0, 'dark'), (-2.4, -6.6, -0.6, 2.4, -1.8, 0.6, 'glass')],
    'snowflake': [sym(0.3, -1.0, 0, 0.0, 'dark'), (-2.6, -7.0, -0.45, 2.6, -2.0, 0.45, 'glass')],
    'icicle': [sym(0.4, -0.6, 0, 0.0, 'dark'), sym(0.75, -9.0, 0, -0.6, 'glass')],
    'candle_lantern': [sym(2.4, -2.0, 0, -1.2, 'iron'), sym(2.4, -7.8, 0, -7.0, 'iron'), sym(0.45, -7.0, 0, -3.4, 'flame'),
                       sym(1.9, -7.0, 0, -2.0, 'clear')],
    'heart': [sym(0.3, -1.0, 0, 0.0, 'dark'), (-2.4, -5.4, -0.6, 2.4, -1.4, 0.6, 'glass')],
    'flower': [(-2.0, -4.8, -0.5, 2.0, -1.0, 0.5, 'glass'), (-0.9, -3.9, -0.7, 0.9, -2.1, 0.7, 'yellow')],
    'pumpkin': [sym(2.4, -6.1, 0, -1.3, 'pumpkin'), (-1.4, -4.6, 2.4, 1.4, -2.6, 2.5, 'face')],
    'ghost': [sym(0.3, -1.0, 0, 0.0, 'dark'), sym(2.0, -7.0, 0, -1.2, 'glass')],
}


def lod_dist(key):
    """Blocks from which the far shape is drawn: about where the light is 8 px tall at 1080p and 70 degrees (96 x height)."""
    height = max(-p[1] for p in SHAPES[key]) / 16
    return max(12.0, round(96 * height, 1))


# glow halo: centre y and size (px)
GLOW = {'fairy': (-2.2, 7), 'holiday': (-3.4, 9), 'edison': (-3.9, 11), 'paper_lantern': (-4.6, 15), 'orb_lantern': (-3.8, 13),
        'star': (-4.2, 12), 'snowflake': (-4.4, 12), 'icicle': (-4.5, 8), 'candle_lantern': (-4.5, 12), 'heart': (-3.8, 11),
        'flower': (-3.0, 10), 'pumpkin': (-3.7, 13), 'ghost': (-4.0, 12)}
# Java enum order (BulbStyle) and item ids
STYLES = [('FAIRY', 'fairy', 'bulb'), ('HOLIDAY', 'holiday', 'holiday_bulb'), ('EDISON', 'edison', 'edison_bulb'),
          ('PAPER_LANTERN', 'paper_lantern', 'paper_lantern'), ('ORB_LANTERN', 'orb_lantern', 'orb_lantern'), ('STAR', 'star', 'star'),
          ('SNOWFLAKE', 'snowflake', 'snowflake'), ('ICICLE', 'icicle', 'icicle'), ('CANDLE_LANTERN', 'candle_lantern', 'candle_lantern'),
          ('HEART', 'heart', 'heart'), ('FLOWER', 'flower', 'flower'), ('PUMPKIN', 'pumpkin', 'pumpkin'), ('GHOST', 'ghost', 'ghost')]
DEFAULT_COLOR = {'fairy': 0xFFE2A8, 'holiday': 0xE0393B, 'edison': 0xFFB45A, 'paper_lantern': 0xE0483B, 'orb_lantern': 0xF4F0E4,
                 'star': 0xFFD45E, 'snowflake': 0xCFEAFF, 'icicle': 0xE6F6FF, 'candle_lantern': 0xFFB050, 'heart': 0xFF6F9F,
                 'flower': 0xFFB0D2, 'pumpkin': 0xFFB43C, 'ghost': 0xEEF4FF}
DYEABLE = {'fairy', 'holiday', 'paper_lantern', 'orb_lantern', 'star', 'snowflake', 'icicle', 'heart', 'flower', 'ghost'}
NAMES = {'bulb': 'Fairy Bulb', 'holiday_bulb': 'Holiday Bulb', 'edison_bulb': 'Edison Bulb', 'paper_lantern': 'Paper Lantern Light',
         'orb_lantern': 'Orb Lantern Light', 'star': 'Star Light', 'snowflake': 'Snowflake Light', 'icicle': 'Icicle Light',
         'candle_lantern': 'Candle Lantern Light', 'heart': 'Heart Light', 'flower': 'Flower Light', 'pumpkin': 'Pumpkin Light',
         'ghost': 'Ghost Light'}


def wjson(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
        f.write('\n')


def argb(rgb):
    v = 0xFF000000 | rgb
    return v - (1 << 32)


# ------------------------------------------------------------------------------------------------ java
def write_java():
    out = ['// Generated by mod/tools/gen_fairylights.py: do not edit. Shapes of the fairy lights (px; hangs from (0, 0, 0)).',
           '// Based on Fairy Lights by Paul Fulham (pau101), MIT; the shapes are redrawn for Aftr Hrs.',
           'package net.axther.aftrhrs.client.fairylights;', '', 'final class BulbShapes {']
    for i, m in enumerate(MATS):
        out.append('    static final int %s = %d;' % (m.upper(), i))
    out.append('    static final int[] COLORS = {%s};' % ', '.join('0x%06X' % COLORS[m] for m in MATS))
    out.append('    /** Per BulbStyle (ordinal): parts {x0, y0, z0, x1, y1, z1, material}. */')
    out.append('    static final float[][][] PARTS = {')
    for enum, key, _ in STYLES:
        parts = ', '.join('{%s, %s}' % (', '.join('%gf' % v for v in p[:6]), MATS.index(p[6])) for p in SHAPES[key])
        out.append('        {%s}, // %s' % (parts, enum))
    out.append('    };')
    out.append('    /** Per BulbStyle: the far shape (same layout as PARTS), drawn beyond LOD_DIST. */')
    out.append('    static final float[][][] LOD_PARTS = {')
    for enum, key, _ in STYLES:
        parts = ', '.join('{%s, %s}' % (', '.join('%gf' % v for v in p[:6]), MATS.index(p[6])) for p in LOD[key])
        out.append('        {%s}, // %s' % (parts, enum))
    out.append('    };')
    out.append('    /** Per BulbStyle: blocks from which LOD_PARTS is drawn (96 x the light\'s height: about 8 px tall at 1080p, 70 degrees). */')
    out.append('    static final float[] LOD_DIST = {%s};' % ', '.join('%gf' % lod_dist(k) for _, k, _ in STYLES))
    out.append('    /** Per BulbStyle: glow halo {centre y, size} (px). */')
    out.append('    static final float[][] GLOW = {%s};' % ', '.join('{%gf, %gf}' % GLOW[k] for _, k, _ in STYLES))
    out += ['', '    private BulbShapes() {', '    }', '}', '']
    os.makedirs(os.path.dirname(JAVA), exist_ok=True)
    with open(JAVA, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(out))


# ------------------------------------------------------------------------------------------------ item models
# Palette texture (fairylights_art.py): 4x4 px cells on a 32x32 sheet, one per material, cell index = position below.
PALETTE = MATS + ['pine', 'berry', 'tinsel', 'fabric', 'cord', 'wire', 'gold']
PAL_TEX = 'aftrhrs:item/fairylights/palette'


def cell_uv(mat):
    i = PALETTE.index(mat)
    x, y = (i % 8) * 4, (i // 8) * 4
    s = 16 / 32
    return [round(x * s + 0.25, 4), round(y * s + 0.25, 4), round((x + 4) * s - 0.25, 4), round((y + 4) * s - 0.25, 4)]


def element(f, t, mat, tint=None, rot=None):
    faces = {}
    for face in ('north', 'south', 'east', 'west', 'up', 'down'):
        e = {'uv': cell_uv(mat), 'texture': '#0'}
        if tint is not None:
            e['tintindex'] = tint
        faces[face] = e
    el = {'from': [round(v, 4) for v in f], 'to': [round(v, 4) for v in t], 'faces': faces}
    if rot:
        el['rotation'] = rot
    return el


GUI_HANG = {
    'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.55, 0.55, 0.55]},
    'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.55, 0.55, 0.55]},
    'firstperson_righthand': {'rotation': [0, -60, 15], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, -60, 15], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
    'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
    'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
    'gui': {'rotation': [18, -32, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
}


def bulb_model(key):
    parts = SHAPES[key]
    x0 = min(p[0] for p in parts); x1 = max(p[3] for p in parts)
    y0 = min(p[1] for p in parts); y1 = max(p[4] for p in parts)
    z0 = min(p[2] for p in parts); z1 = max(p[5] for p in parts)
    size = max(x1 - x0, y1 - y0, z1 - z0)
    s = min(3.0, 13.0 / size)
    cx, cy, cz = (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2
    els = []
    for p in parts:
        f = [8 + (p[0] - cx) * s, 8 + (p[1] - cy) * s, 8 + (p[2] - cz) * s]
        t = [8 + (p[3] - cx) * s, 8 + (p[4] - cy) * s, 8 + (p[5] - cz) * s]
        mat = p[6]
        tint = 0 if mat in TINTED and key in DYEABLE else None
        if mat in TINTED and key not in DYEABLE:
            mat = 'clear'
        els.append(element(f, t, mat, tint))
    # a stub of cord above, so it reads as a hanging light
    top = 8 + (y1 - cy) * s
    if top < 15.5:
        els.append(element([7.6, top, 7.6], [8.4, min(16, top + 2.5), 8.4], 'wire'))
    return {'credit': 'Aftr Hrs fairy lights (tools/gen_fairylights.py), based on Fairy Lights by pau101 (MIT)',
            'textures': {'0': PAL_TEX, 'particle': PAL_TEX}, 'gui_light': 'side', 'elements': els, 'display': GUI_HANG}


def ring(cx, cy, cz, r, w, mat, tint=None, seg=8, axis='z'):
    """An octagon loop of boxes in the XY plane (a coil seen from the front)."""
    import math
    els = []
    side = 2 * r * math.tan(math.pi / seg)
    for i in range(seg):
        ang = 360 * i / seg
        a = math.radians(ang)
        px, py = cx + math.sin(a) * r, cy + math.cos(a) * r
        f = [px - side / 2 - 0.05, py - w / 2, cz - w / 2]
        t = [px + side / 2 + 0.05, py + w / 2, cz + w / 2]
        rot = {'origin': [round(px, 4), round(py, 4), round(cz, 4)], 'z': round(-ang if ang <= 180 else 360 - ang, 4)}
        if abs(rot['z']) < 1e-6 or abs(abs(rot['z']) - 180) < 1e-6:
            rot = None
        els.append(element(f, t, mat, tint, rot))
    return els


def strand_model(kind):
    import math
    els = []
    disp = dict(GUI_HANG)
    disp['gui'] = {'rotation': [10, -20, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]}
    if kind == 'lights':
        els += ring(8, 8.6, 8, 5.2, 0.8, 'wire')
        for i, ang in enumerate([200, 250, 300, 350, 30, 80, 130, 160]):
            a = math.radians(ang)
            px, py = 8 + math.sin(a) * 5.2, 8.6 + math.cos(a) * 5.2
            els.append(element([px - 0.4, py - 1.2, 8.3], [px + 0.4, py - 0.3, 9.1], 'dark'))
            els.append(element([px - 0.9, py - 3.0, 8.0], [px + 0.9, py - 1.2, 9.4], 'glass', tint=i % 4))
        els.append(element([12.6, 0.6, 7.6], [14.0, 1.8, 8.6], 'dark'))
        els.append(element([13.0, 1.8, 7.8], [13.6, 3.6, 8.4], 'wire'))
    elif kind == 'garland':
        for k in range(16):
            a = math.radians(k * 22.5)
            px, py = 8 + math.sin(a) * 5.0, 8 + math.cos(a) * 5.0
            sz = 1.7 + (k % 3) * 0.25
            els.append(element([px - sz, py - sz, 8 - sz * 0.8], [px + sz, py + sz, 8 + sz * 0.8], 'pine',
                               rot={'origin': [round(px, 3), round(py, 3), 8], 'z': (k * 22.5) % 45 - 22.5 if k % 2 else 0} if k % 2 else None))
        for ang in (30, 140, 250, 320):
            a = math.radians(ang)
            px, py = 8 + math.sin(a) * 5.3, 8 + math.cos(a) * 5.3
            els.append(element([px - 0.8, py - 0.8, 9.3], [px + 0.8, py + 0.8, 10.6], 'berry'))
        els += [element([6.6, 1.2, 9.4], [9.4, 3.2, 10.4], 'berry'), element([5.2, 1.6, 9.4], [6.6, 3.0, 10.2], 'berry'),
                element([9.4, 1.6, 9.4], [10.8, 3.0, 10.2], 'berry')]
    elif kind == 'tinsel':
        for k in range(24):
            a = math.radians(k * 15)
            px, py = 8 + math.sin(a) * 5.0, 8 + math.cos(a) * 5.0
            els.append(element([px - 1.3, py - 0.35, 7.0], [px + 1.3, py + 0.35, 9.0], 'tinsel', tint=0,
                               rot={'origin': [round(px, 3), round(py, 3), 8], 'z': round(-(k * 15) % 45 + 22.5, 3)}))
            els.append(element([px - 0.35, py - 1.2, 6.8], [px + 0.35, py + 1.2, 9.2], 'tinsel', tint=0,
                               rot={'origin': [round(px, 3), round(py, 3), 8], 'x': 22.5 if k % 2 else -22.5}))
    else:  # bunting
        pts = [(1.5, 13.0), (5.0, 11.4), (8.5, 10.8), (12.0, 11.4), (15.0, 13.0)]
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            ln = math.hypot(bx - ax, by - ay)
            mx, my = (ax + bx) / 2, (ay + by) / 2
            ang = math.degrees(math.atan2(by - ay, bx - ax))
            els.append(element([mx - ln / 2 - 0.1, my - 0.35, 7.65], [mx + ln / 2 + 0.1, my + 0.35, 8.35], 'cord',
                               rot={'origin': [round(mx, 3), round(my, 3), 8], 'z': round(ang, 3)}))
        for i, (px, py) in enumerate([(3.3, 12.0), (8.5, 10.6), (13.7, 12.0)]):
            # a triangle from three stacked boxes
            for j, (w, h) in enumerate([(4.4, 1.6), (3.0, 1.6), (1.6, 1.6), (0.6, 1.2)]):
                y1 = py - j * 1.6
                els.append(element([px - w / 2, y1 - h, 7.6], [px + w / 2, y1, 8.4], 'fabric', tint=i))
    return {'credit': 'Aftr Hrs fairy lights (tools/gen_fairylights.py)', 'textures': {'0': PAL_TEX, 'particle': PAL_TEX},
            'gui_light': 'side', 'elements': els, 'display': disp}


def pennant_model(shape):
    els = []
    if shape == 'triangle':
        for j, w in enumerate([10, 8, 6, 4, 2]):
            els.append(element([8 - w / 2, 14 - (j + 1) * 2.4, 7.5], [8 + w / 2, 14 - j * 2.4, 8.5], 'fabric', tint=0))
    elif shape == 'swallowtail':
        els.append(element([3, 6, 7.5], [13, 14, 8.5], 'fabric', tint=0))
        els.append(element([3, 2, 7.5], [6.5, 6, 8.5], 'fabric', tint=0))
        els.append(element([9.5, 2, 7.5], [13, 6, 8.5], 'fabric', tint=0))
        els.append(element([6.5, 4, 7.5], [9.5, 6, 8.5], 'fabric', tint=0))
    else:
        els.append(element([3, 3, 7.5], [13, 14, 8.5], 'fabric', tint=0))
    els.append(element([1.5, 14, 7.6], [14.5, 14.8, 8.4], 'cord'))
    disp = dict(GUI_HANG)
    disp['gui'] = {'rotation': [0, -20, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]}
    return {'credit': 'Aftr Hrs fairy lights (tools/gen_fairylights.py)', 'textures': {'0': PAL_TEX, 'particle': PAL_TEX},
            'gui_light': 'side', 'elements': els, 'display': disp}


def hook_model():
    """Brass screw-eye, made for facing=north: plate on the wall at z=16, the eye sticking out north."""
    g, s = '#plate', '#ring'

    def el(f, t, tex):
        return {'from': f, 'to': t, 'faces': {k: {'uv': [6, 6, 9, 9], 'texture': tex} for k in ('north', 'south', 'east', 'west', 'up', 'down')}}

    els = [el([6.5, 6.5, 15], [9.5, 9.5, 16], g), el([7.5, 7.5, 13], [8.5, 8.5, 15], g),
           el([7.5, 9.3, 10.3], [8.5, 10.1, 13.0], s), el([7.5, 5.9, 10.3], [8.5, 6.7, 13.0], s),
           el([7.5, 6.7, 10.3], [8.5, 9.3, 11.1], s), el([7.5, 6.7, 12.2], [8.5, 9.3, 13.0], s)]
    return {'credit': 'Aftr Hrs fairy lights (tools/gen_fairylights.py)', 'ambientocclusion': False,
            'textures': {'plate': 'minecraft:block/gold_block', 'ring': 'minecraft:block/gold_block', 'particle': 'minecraft:block/gold_block'},
            'elements': els,
            'display': {'gui': {'rotation': [30, 135, 0], 'translation': [0, 0, 0], 'scale': [1.8, 1.8, 1.8]},
                        'ground': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [0.8, 0.8, 0.8]},
                        'fixed': {'rotation': [0, 0, 0], 'translation': [0, 0, -4], 'scale': [1.5, 1.5, 1.5]},
                        'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.8, 0.8, 0.8]},
                        'firstperson_righthand': {'rotation': [0, 135, 0], 'translation': [0, 2, 0], 'scale': [0.9, 0.9, 0.9]}}}


# ------------------------------------------------------------------------------------------------ recipes
def shaped(name, pattern, key, result, count=1):
    k = {c: v for c, v in key.items()}
    wjson(os.path.join(DATA, 'recipe', name + '.json'),
          {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'group': 'fairy_lights', 'pattern': pattern, 'key': k,
           'result': {'id': result, 'count': count}})


def recipes():
    A = 'aftrhrs:'
    shaped('fairy_hook', ['G', 'C'], {'G': 'minecraft:gold_nugget', 'C': 'minecraft:copper_ingot'}, A + 'fairy_hook', 4)
    shaped('fairy_lights', ['N-N'], {'N': 'minecraft:iron_nugget', '-': 'minecraft:string'}, A + 'fairy_lights')
    shaped('fairy_garland', ['LSL'], {'L': 'minecraft:spruce_leaves', 'S': 'minecraft:string'}, A + 'fairy_garland')
    shaped('fairy_tinsel', ['P-P'], {'P': 'minecraft:paper', '-': 'minecraft:string'}, A + 'fairy_tinsel')
    shaped('fairy_bunting', ['S-S'], {'S': 'minecraft:stick', '-': 'minecraft:string'}, A + 'fairy_bunting')
    N, G, L = 'minecraft:iron_nugget', 'minecraft:glass_pane', 'minecraft:glowstone_dust'
    bulbs = {
        'bulb': ([' N ', 'GLG'], {'N': N, 'G': G, 'L': L}, 4),
        'holiday_bulb': ([' N ', 'GLG', ' G '], {'N': N, 'G': G, 'L': L}, 4),
        'edison_bulb': ([' C ', 'GLG', ' G '], {'C': 'minecraft:copper_ingot', 'G': G, 'L': L}, 4),
        'paper_lantern': ([' N ', 'PTP', 'PPP'], {'N': N, 'P': 'minecraft:paper', 'T': 'minecraft:torch'}, 2),
        'orb_lantern': ([' N ', 'SLS', ' W '], {'N': N, 'S': 'minecraft:string', 'L': L, 'W': 'minecraft:white_wool'}, 2),
        'star': ([' N ', 'GLG', ' g '], {'N': N, 'G': G, 'L': L, 'g': 'minecraft:gold_nugget'}, 4),
        'snowflake': ([' N ', 'SLS', ' S '], {'N': N, 'S': 'minecraft:snowball', 'L': L}, 4),
        'icicle': ([' N ', ' L ', ' I '], {'N': N, 'L': L, 'I': 'minecraft:ice'}, 4),
        'candle_lantern': ([' N ', 'NCN', 'NNN'], {'N': N, 'C': '#minecraft:candles'}, 1),
        'heart': ([' N ', 'PLP', ' P '], {'N': N, 'L': L, 'P': 'minecraft:pink_stained_glass_pane'}, 4),
        'flower': ([' N ', 'FLF', ' F '], {'N': N, 'L': L, 'F': '#minecraft:small_flowers'}, 4),
        'pumpkin': ([' N ', ' J '], {'N': N, 'J': 'minecraft:jack_o_lantern'}, 4),
        'ghost': ([' N ', 'WLW', ' W '], {'N': N, 'L': L, 'W': 'minecraft:white_wool'}, 4),
    }
    for _, key, item in STYLES:
        pat, k, n = bulbs[item]
        shaped('fairy_' + item, pat, k, A + 'fairy_' + item, n)
        rid = A + 'fairy_' + item
        if key in DYEABLE:
            wjson(os.path.join(DATA, 'recipe', 'fairy_' + item + '_dyed.json'),
                  {'type': 'minecraft:crafting_dye', 'dye': '#minecraft:dyes', 'group': 'fairy_lights_dyed', 'target': rid, 'result': {'id': rid}})
        wjson(os.path.join(DATA, 'recipe', 'fairy_' + item + '_twinkle.json'),
              {'type': 'minecraft:crafting_transmute', 'category': 'misc', 'group': 'fairy_lights_twinkle', 'input': rid,
               'material': 'minecraft:glowstone_dust', 'result': {'id': rid, 'components': {'aftrhrs:twinkle': True}}})
    pen = {'triangle': ['PSP', ' P '], 'swallowtail': ['PSP', 'P P'], 'square': ['PSP', 'PPP']}
    for shape, pat in pen.items():
        rid = A + 'fairy_pennant_' + shape
        shaped('fairy_pennant_' + shape, pat, {'P': 'minecraft:paper', 'S': 'minecraft:string'}, rid, 4)
        wjson(os.path.join(DATA, 'recipe', 'fairy_pennant_' + shape + '_dyed.json'),
              {'type': 'minecraft:crafting_dye', 'dye': '#minecraft:dyes', 'group': 'fairy_pennants_dyed', 'target': rid, 'result': {'id': rid}})
    wjson(os.path.join(DATA, 'recipe', 'fairy_tinsel_dyed.json'),
          {'type': 'minecraft:crafting_dye', 'dye': '#minecraft:dyes', 'group': 'fairy_tinsel_dyed', 'target': A + 'fairy_tinsel',
           'result': {'id': A + 'fairy_tinsel'}})
    wjson(os.path.join(DATA, 'recipe', 'fairy_pattern.json'), {'type': 'aftrhrs:fairy_pattern'})


# ------------------------------------------------------------------------------------------------ main
def main():
    write_java()
    md = os.path.join(ASSETS, 'models', 'fairylights')
    for enum, key, item in STYLES:
        wjson(os.path.join(md, 'item', 'fairy_' + item + '.json'), bulb_model(key))
        model = {'type': 'minecraft:model', 'model': 'aftrhrs:fairylights/item/fairy_' + item}
        if key in DYEABLE:
            model['tints'] = [{'type': 'minecraft:dye', 'default': argb(DEFAULT_COLOR[key])}]
        wjson(os.path.join(ASSETS, 'items', 'fairy_' + item + '.json'), {'model': model})
    defaults = {'lights': [0xC9C2B5, 0xA9A398, 0xC9C2B5, 0xA9A398], 'bunting': [0xD8322F, 0xF2EEE4, 0x2F9E4A]}
    for kind, iid in (('lights', 'fairy_lights'), ('garland', 'fairy_garland'), ('tinsel', 'fairy_tinsel'), ('bunting', 'fairy_bunting')):
        wjson(os.path.join(md, 'item', iid + '.json'), strand_model(kind))
        model = {'type': 'minecraft:model', 'model': 'aftrhrs:fairylights/item/' + iid}
        if kind in defaults:
            model['tints'] = [{'type': 'minecraft:custom_model_data', 'index': i, 'default': argb(c)} for i, c in enumerate(defaults[kind])]
        elif kind == 'tinsel':
            model['tints'] = [{'type': 'minecraft:dye', 'default': argb(0xDDE3EA)}]
        wjson(os.path.join(ASSETS, 'items', iid + '.json'), {'model': model})
    for shape in ('triangle', 'swallowtail', 'square'):
        wjson(os.path.join(md, 'item', 'fairy_pennant_' + shape + '.json'), pennant_model(shape))
        wjson(os.path.join(ASSETS, 'items', 'fairy_pennant_' + shape + '.json'),
              {'model': {'type': 'minecraft:model', 'model': 'aftrhrs:fairylights/item/fairy_pennant_' + shape,
                         'tints': [{'type': 'minecraft:dye', 'default': argb(0xF2EEE4)}]}})
    wjson(os.path.join(md, 'hook.json'), hook_model())
    wjson(os.path.join(ASSETS, 'items', 'fairy_hook.json'), {'model': {'type': 'minecraft:model', 'model': 'aftrhrs:fairylights/hook'}})
    rot = {'north': {}, 'south': {'y': 180}, 'west': {'y': 270}, 'east': {'y': 90}, 'up': {'x': 270}, 'down': {'x': 90}}
    variants = {}
    for facing, r in rot.items():
        for placed in ('false', 'true'):
            variants['facing=%s,placed=%s' % (facing, placed)] = dict({'model': 'aftrhrs:fairylights/hook'}, **r)
    wjson(os.path.join(ASSETS, 'blockstates', 'fairy_hook.json'), {'variants': variants})
    wjson(os.path.join(DATA, 'loot_table', 'blocks', 'fairy_hook.json'), {
        'type': 'minecraft:block', 'random_sequence': 'aftrhrs:blocks/fairy_hook',
        # 26.3 loot format: one "condition" per pool; block state checks are "match_block"
        'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'aftrhrs:fairy_hook'}],
                   'condition': {'type': 'minecraft:all_of', 'terms': [
                       {'type': 'minecraft:survives_explosion'},
                       {'type': 'minecraft:match_block', 'blocks': 'aftrhrs:fairy_hook', 'state': {'placed': 'true'}}]}}]})
    recipes()
    wjson(os.path.join(DATA, 'tags', 'item', 'fairy_lights.json'), {'values': ['aftrhrs:fairy_' + i for _, _, i in STYLES]})
    wjson(os.path.join(DATA, 'tags', 'item', 'fairy_strands.json'),
          {'values': ['aftrhrs:fairy_lights', 'aftrhrs:fairy_garland', 'aftrhrs:fairy_tinsel', 'aftrhrs:fairy_bunting']})
    lang = {
        'itemGroup.aftrhrs.fairy_lights': 'Lights & Garlands',
        'block.aftrhrs.fairy_hook': 'String Hook',
        'item.aftrhrs.fairy_lights': 'String of Lights', 'item.aftrhrs.fairy_garland': 'Pine Garland',
        'item.aftrhrs.fairy_tinsel': 'Tinsel', 'item.aftrhrs.fairy_bunting': 'Pennant Bunting',
        'item.aftrhrs.fairy_pennant_triangle': 'Triangle Pennant', 'item.aftrhrs.fairy_pennant_swallowtail': 'Swallowtail Pennant',
        'item.aftrhrs.fairy_pennant_square': 'Square Pennant',
        'entity.aftrhrs.fairy_knot': 'Strand Knot',
        'aftrhrs.fairy_lights.claim': "You can't hang that here: this land is claimed.",
        'aftrhrs.fairy_lights.full': 'This spot already holds %s strands.',
        'aftrhrs.fairy_lights.chunk': 'Too many strands around here (%s per chunk).',
        'aftrhrs.fairy_lights.joined': 'These two are already strung together.',
        'aftrhrs.fairy_lights.long': 'Too far: %s blocks (at most %s).',
        'aftrhrs.fairy_lights.started': 'Now walk to the other end and right-click it (up to %s blocks).',
        'aftrhrs.fairy_lights.mode.on': 'Lights: always on', 'aftrhrs.fairy_lights.mode.night': 'Lights: on from dusk to dawn',
        'aftrhrs.fairy_lights.mode.off': 'Lights: off',
        'aftrhrs.fairy_lights.tip.bare': 'No lights yet: craft it with up to 8 lights',
        'aftrhrs.fairy_lights.tip.bare_bunting': 'No pennants yet: craft it with up to 8 pennants',
        'aftrhrs.fairy_lights.tip.garland': 'Craft it with lights to weave them in',
        'aftrhrs.fairy_lights.tip.twinkle': ' (twinkling)', 'aftrhrs.fairy_lights.tip.twinkles': 'Twinkles',
        'aftrhrs.fairy_lights.tip.bulb': 'Goes on a String of Lights',
        'aftrhrs.fairy_lights.tip.use': 'Right-click a fence, wall or block, then the other end',
        'aftrhrs.fairy_lights.wire.white': 'White wire', 'aftrhrs.fairy_lights.wire.green': 'Green wire',
        'aftrhrs.fairy_lights.letters.title': 'Bunting Letters',
        'aftrhrs.fairy_lights.letters.allowed': "A-Z 0-9 !?.,'&+-*#@:",
        'aftrhrs.fairy_lights.letters.count': '%s / %s letters',
    }
    for _, _, item in STYLES:
        lang['item.aftrhrs.fairy_' + item] = NAMES[item]
    wjson(os.path.join(ASSETS, 'lang', 'en_us.fairylights.json'), lang)
    print('gen_fairylights: %d light styles, %d recipes' % (len(STYLES), len([f for f in os.listdir(os.path.join(DATA, 'recipe')) if f.startswith('fairy_')])))


if __name__ == '__main__':
    main()
