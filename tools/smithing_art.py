"""Anvil & Ember (smithing) item art (Pillow). Original 16x16 pixel art for Aftr Hrs.

Sprite drawings live in smithing_sprites.py. This script bakes them into the
existing item layers, palettes and animation states.

Run with --items-only to regenerate just the flat item textures, or
--sheet-only to render a contact sheet without writing game assets.
"""
import colorsys
import json
import os
import sys
import zlib

from PIL import Image, ImageDraw, ImageFont

import smithing_sprites as sprites

MOD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(MOD, 'src', 'main', 'resources')
DATA = os.path.join(RES, 'data', 'aftrhrs', 'smithing')
TEX = os.path.join(RES, 'assets', 'aftrhrs', 'textures')
ITEM = os.path.join(TEX, 'item', 'smithing')
EQUIP = os.path.join(TEX, 'entity', 'equipment', 'humanoid')
EQUIP_LEGS = os.path.join(TEX, 'entity', 'equipment', 'humanoid_leggings')
SHEET = os.path.join(MOD, 'tools', 'out', 'smithing_sheet.png')
# tool frame textures that are not written (gen_smithing.py reads it): 'tool/<tid>/<layer>/<mid><suffix>' -> the rel of
# a pixel-identical texture of the same tool layer and material, or null for a fully transparent frame (no layer)
FRAMES = os.path.join(MOD, 'tools', 'smithing_frames.json')


def load(name):
    with open(os.path.join(DATA, name), encoding='utf-8') as f:
        return json.load(f)


MATERIALS = load('materials.json')['materials']
PARTS = load('parts.json')['parts']
TOOLS = load('tools.json')['tools']
MAT = {m['id']: m for m in MATERIALS}
PART = {p['id']: p for p in PARTS}


def fits(m, role):
    """Same rule as the code: may material m fill a part of this role?"""
    k = m['kind']
    if role == 'head':
        return 'head' in m
    if role == 'handle':
        return 'handle' in m
    if role == 'limb':
        return 'limb' in m
    if role == 'grip':
        return 'grip' in m or 'handle' in m
    if role == 'plating':
        return 'plating' in m
    if role == 'string':
        return k == 'string'
    if role == 'fletching':
        return k == 'fletching'
    if role == 'binding':
        return k not in ('string', 'fletching')
    raise ValueError(role)


def tool_layers(tool):
    """[(layer, role)] in order of first appearance; a layer takes the role of its first part."""
    out, seen = [], set()
    for p in tool['parts']:
        if p['layer'] not in seen:
            seen.add(p['layer'])
            out.append((p['layer'], PART[p['part']]['role']))
    return out


# ================================================================================================ colour helpers
def hexc(s):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


def hsh(x, y, salt):
    v = (x * 73856093 ^ y * 19349663 ^ salt * 83492791) & 0xffffffff
    v = ((v ^ (v >> 13)) * 0x5bd1e995) & 0xffffffff
    return (v ^ (v >> 15)) & 0xffffffff


def salt_of(name):
    return zlib.crc32(name.encode()) & 0xffff


PAL = {m['id']: [hexc(c) for c in m['palette']] for m in MATERIALS}
# Sprite ramps share warm highlights and cool shadows with the station art.
# The material data and equipped armour use their original ramps.
def ramp(*colors):
    return [hexc(c) for c in colors]


ITEM_RAMPS = dict(PAL)
ITEM_RAMPS.update({
    'iron': ramp('30303c', '494b5b', '676d7c', '979ca9', 'c3c9cc', 'ecede2'),
    'steel': ramp('252832', '3c414d', '525b6a', '748391', 'a7b4bd', 'dde0d8'),
    'wood': ramp('392520', '593b2c', '79513a', '996a48', 'bd885c', 'deb079'),
    'leather': ramp('362221', '55342a', '714636', '925a42', 'ad7858', 'cf9a70'),
    'copper': ramp('41232c', '693635', '9f5345', 'bf6c50', 'e49b6c', 'ffd498'),
    'stone': ramp('313039', '474550', '5e5c65', '79777a', 'a29b91', 'cbc0a7'),
    'flint': ramp('262531', '383645', '4a495a', '606174', '828391', 'a9a4a4'),
    'silk': ramp('454154', '69607c', '8e82a0', 'b5a9bf', 'dacada', 'f6e8dc'),
    'down_feather': ramp('51414b', '75615d', 'ae9380', 'ceb49b', 'ead7b8', 'fff2d5'),
    'netherite': ramp('211b26', '342933', '4b3c43', '655154', '8b7167', 'b99a81'),
    'emberforged_steel': ramp('35222a', '593039', '7c433e', 'a8563f', 'e27842', 'ffc075'),
    'voidgold': ramp('282435', '3b354b', '515268', '63776e', '9baf8a', 'e5dba5'),
    'echo_steel': ramp('14212a', '20343d', '28535a', '337475', '54a39b', 'a1d3b1'),
})
FIXED = ITEM_RAMPS | {
    'sand': ramp('624536', '947054', 'b39268', 'ceb17b', 'e1c998', 'ecd7ac'),
    'clay': ramp('463036', '684548', '94614e', 'b68160', 'cca079', 'e0b98b'),
    'raw_mold': ramp('34303d', '4c4657', '68616f', '898390', 'aea2a1', 'c9bbb1'),
    'terracotta': ramp('4b2c32', '6d3c3b', '995447', 'bc7056', 'd58f67', 'e9ab7c'),
    'oil': ramp('4b3031', '754137', 'a15b39', 'cd813e', 'f0ad50', 'ffda84'),
    'glass': ramp('293741', '465663', '64818a', '82a6a7', 'afd0bf', 'e0efcf'),
    'cork': ramp('3f2c29', '674936', '8f6348', 'aa7952', 'c89a68', 'e7bb87'),
    'cyan': ramp('293246', '3b5069', '466d82', '569aa7', '80c5bf', 'd0edc7'),
    'paper': ramp('45333a', '695047', '97795d', 'c5a783', 'e7d1a3', 'f4e3b9'),
    'book': ramp('2b202d', '45303b', '65404a', '874d4d', 'b87560', 'd89573'),
    'firebrick': ramp('513039', '744248', '9b544d', 'b87258', 'd99b70', 'ebba89'),
    'whet': ramp('24272f', '373741', '555461', '74727c', '989293', 'bbb0a3'),
    'chime': ramp('282337', '443650', '604667', '785e81', 'a782a2', 'dbbac5'),
    'ember': ramp('60303b', '8d3c3c', 'b95f46', 'ef8c4c', 'ffba65', 'ffe5a4'),
}

# Short material signatures land on the drawn surfaces, inside the contour.
ACCENTS = {
    'rimesteel': (4, 7, hexc('d6e7eb')),
    'emberforged_steel': (3, 5, hexc('ffae54')),
    'echo_steel': (3, 7, hexc('79d7ba')),
    'chime_bronze': (4, 6, hexc('e0b2d3')),
    'voidgold': (3, 7, hexc('977abf')),
    'starforged': (4, 6, hexc('ffefd5')),
    'obsidian': (3, 6, hexc('9275a9')),
    'blazebone': (3, 7, hexc('ffc374')),
    'pearlsteel': (4, 6, hexc('efdadf')),
    'tidebronze': (3, 7, hexc('87c2a7')),
}

INCANDESCENT = [hexc(c) for c in ('#8c2a0a', '#c4470e', '#ec7a1c', '#ffa934', '#ffd774', '#fff6cc')]


def hot(pal):
    """Molten version of a palette: brighter and more saturated in the metal's own hue, pushed toward an
    incandescent glow (grey metals almost all the way, coloured alloys keep their tint)."""
    out = []
    for i in range(6):
        r, g, b = (v / 255 for v in pal[min(5, i + 1)][:3])
        h, sat, val = colorsys.rgb_to_hsv(r, g, b)
        sat2 = min(1.0, sat * 1.5 + 0.08)
        val2 = min(1.0, val * 1.3 + 0.18)
        c = tuple(int(round(v * 255)) for v in colorsys.hsv_to_rgb(h, sat2, val2)) + (255,)
        t = 0.30 + 0.45 * (1 - min(1.0, sat * 2.2))
        out.append(mix(c, INCANDESCENT[i], t))
    return out


# ================================================================================================ material looks
def mat_shade(mid, role, c, s, edge, x, y):
    """Index-level variation by material kind (same mask, different shading)."""
    m = MAT[mid]
    kind = m['kind']
    h = hsh(x, y, salt_of(mid))
    if kind == 'carved' and role == 'head':
        if mid == 'flint':  # knapped: scalloped facets, chipped highlights
            if edge == 'in':
                k = (x * 2 + y * 3) % 5
                s = min(5, s + 1) if k == 0 else (max(2, s - 1) if k == 2 else s)
            elif edge == 'tl' and h % 3 == 0:
                s = 2
        elif mid == 'stone':
            if edge == 'in':
                s = max(1, s - 1) if h % 4 == 0 else (min(5, s + 1) if h % 7 == 1 else s)
            elif edge == 'tl' and h % 4 == 0:
                s = 2
        elif mid == 'bone':
            if edge == 'in' and h % 7 == 0 and s >= 3:
                s -= 1
    if mid == 'wood' and edge == 'in' and h % 4 == 0 and role in ('head', 'handle', 'limb', 'grip', 'binding', 'plating'):
        s = max(1, s - 1)
    if mid == 'bamboo' and role in ('handle', 'limb', 'grip', 'binding'):
        if y % 4 == 0 and edge != 'br':
            s = 1
        elif y % 4 == 1 and edge == 'in':
            s = 5
    if kind == 'wrap' and role in ('handle', 'grip', 'binding'):
        if edge == 'in':
            s = 4 if y % 2 == 0 else 2
        elif edge == 'tl':
            s = 2 if y % 2 == 0 else 1
        if mid == 'tufthorn_hide' and edge == 'in' and h % 5 == 0:
            s = 5
    return s


ACCENT_ROLES = ('head', 'handle', 'binding', 'limb', 'grip', 'plating')


def mat_color(mid, role, c, s, edge, x, y, sparse=1):
    """sparse > 1 thins the accents out (big surfaces such as the worn armor)."""
    s = mat_shade(mid, role, c, s, edge, x, y)
    col = PAL[mid][s]
    if role not in ACCENT_ROLES or edge != 'in':
        return col
    if sparse > 1 and hsh(x, y, 0x77) % sparse:
        return col
    h = hsh(x, y, salt_of(mid) ^ 0x5a5a)
    if mid == 'rimesteel':
        if s >= 4 and h % 3 == 0:
            return hexc('#eef8ff')
        if s == 3 and h % 9 == 0:
            return hexc('#cdeaff')
    elif mid == 'emberforged_steel':
        if s in (2, 3) and h % 9 == 0:
            return hexc('#ffc94a')
        if s >= 4 and h % 9 == 0:
            return hexc('#fff0a0')
    elif mid == 'echo_steel':
        if s >= 3 and h % 5 == 0:
            return hexc('#3ee8dc')
        if s == 2 and h % 9 == 0:
            return hexc('#1a9c98')
    elif mid == 'chime_bronze':
        if s == 5:
            return hexc('#f6e2ff') if h % 2 else hexc('#e6bdff')
        if s == 4 and h % 6 == 0:
            return hexc('#dca8f8')
    elif mid == 'voidgold':
        if s in (2, 3, 4) and h % 5 == 0:
            return hexc('#7a3cbc') if s < 4 else hexc('#b27ae8')
    elif mid == 'starforged':
        if s >= 3 and h % 8 == 0:
            return hexc('#ffffff')
        if s == 5:
            return hexc('#fffbea')
    elif mid == 'obsidian':
        if s >= 4:
            return hexc('#9a6ad0') if h % 2 else hexc('#7f54b4')
        if s == 3 and h % 6 == 0:
            return hexc('#6a40a0')
    elif mid == 'blazebone':
        if s >= 2 and (x + 2 * y + (h % 2)) % 6 == 0:
            return hexc('#ff8a1e') if s <= 3 else hexc('#ffc457')
    elif mid == 'pearlsteel':
        if s == 5:
            return hexc('#f6eaf8') if (x + y) % 2 else hexc('#e4f6f2')
        if s == 4 and h % 4 == 0:
            return hexc('#dcd2f0')
    elif mid == 'tidebronze':
        if s == 2 and h % 7 == 0:
            return hexc('#5ccaa8')
    return col


def render(sprite, paint):
    image = Image.new('RGBA', (16, 16))
    for (x, y), (layer, shade) in sprite.pixels.items():
        color = paint(layer, shade, x, y)
        if color is not None:
            image.putpixel((x, y), color)
    return image


def material_pixel(mid, shade, x, y):
    if mid in ACCENTS:
        threshold, period, accent = ACCENTS[mid]
        if shade == threshold and (x * 3 + y * 2) % period == 0:
            return accent
    elif mid == 'bamboo' and shade == 3 and (x - y) % 7 < 2:
        shade = 2
    elif mid in ('flint', 'stone', 'bone') and shade == 3 and (x * 2 + y * 3) % 9 == 1:
        shade = 2
    return ITEM_RAMPS[mid][shade]


AMMO_RAMPS = {
    'bolt': FIXED['wood'], 'bolt_head': FIXED['iron'],
    'bolt_feather': FIXED['paper'], 'rocket': FIXED['ember'],
}


def tool_variants(tid):
    yield '', sprites.TOOLS[tid]
    for variant, sprite in sprites.VARIANTS.get(tid, {}).items():
        yield '_' + variant, sprite


def layer_image(sprite, layer, mid):
    def paint(group, shade, x, y):
        if group in AMMO_RAMPS:
            return AMMO_RAMPS[group][shade] if layer == 'string' else None
        if group == layer:
            return material_pixel(mid, shade, x, y)
        return None

    return render(sprite, paint)


def check_sprites():
    assert sprites.TOOLS.keys() == {t['id'] for t in TOOLS}
    assert sprites.PARTS.keys() == PART.keys()
    for tool in TOOLS:
        tid = tool['id']
        layers = {layer for layer, _ in tool_layers(tool)}
        for suffix, sprite in tool_variants(tid):
            used = {'string' if group in AMMO_RAMPS else group for group, _ in sprite.pixels.values()}
            expected = layers - {'hook'} if suffix == '_cast' else layers
            assert used == expected, (tid, suffix, used, expected)


def part_image(pid, mid):
    return material_image(sprites.PARTS[pid], mid)


def material_image(sprite, mid):
    return render(sprite, lambda layer, shade, x, y: material_pixel(mid, shade, x, y))


def fixed_image(sprite, fluid=None):
    def paint(group, shade, x, y):
        if group == 'fluid':
            if fluid is None:
                return FIXED['steel'][shade]
            return fluid[min(shade + 2, 5)]
        if group == 'handle':
            group = 'wood'
        return FIXED[group][shade]

    return render(sprite, paint)


ALLOYS = [a['id'] for a in load('alloys.json')['alloys']]
# ================================================================================================ worn armor
def armor_index(piece):
    """Index map for the worn armor (64x32 humanoid layout): {(x, y): (shade, style)}; style 'p' plate, 'm' mail."""
    out = {}

    def face(x0, y0, w, h, rows, style_fn=None, rivets=()):
        """rows: list of (row range in this face) -> style ('p' / 'm'), None = transparent."""
        for j in range(h):
            st = rows(j) if callable(rows) else rows
            if st is None:
                continue
            for i in range(w):
                if style_fn:
                    s2 = style_fn(i, j)
                    if s2 is None:
                        continue
                if st == 'm':
                    s = 4 if (i + j) % 2 == 0 else 2
                    if j == 0 or i == 0:
                        s = min(s, 3)
                else:
                    s = 3
                    if j == 0 or i == 0:
                        s = 4
                    if j == 1 and 0 < i < w - 1:
                        s = 5 if i < w // 2 else 4
                    if j == h - 1 or i == w - 1:
                        s = 2
                    if (i, j) in rivets:
                        s = 5
                out[(x0 + i, y0 + j)] = (s, st)

    def box(u, v, w, h, d, rows, top=True, bottom=False, front_fn=None, rivets_front=()):
        # top, bottom faces
        if top:
            face(u + d, v, w, d, 'p')
        if bottom:
            face(u + d + w, v, w, d, 'p')
        face(u, v + d, d, h, rows)
        face(u + d, v + d, w, h, rows, front_fn, rivets_front)
        face(u + d + w, v + d, d, h, rows)
        face(u + d + w + d, v + d, w, h, rows)

    if piece == 'humanoid':
        # helmet: head cube 8x8x8 at (0, 0); face opening on the front, mail aventail on the lowest rows
        box(0, 0, 8, 8, 8, lambda j: 'm' if j >= 6 else 'p',
            front_fn=lambda i, j: None if (j >= 3 and 2 <= i <= 5 and not (i in (3, 4) and j <= 5)) else 1,
            rivets_front=((1, 1), (6, 1)))
        # ridge along the top of the helm
        for j in range(8):
            out[(11, j)] = (5, 'p')
            out[(12, j)] = (2, 'p')
        # chestplate: body 8x12x4 at (16, 16); plate down to the belt, mail fauld below
        box(16, 16, 8, 12, 4, lambda j: 'm' if j >= 9 else 'p', rivets_front=((1, 2), (6, 2)))
        for j in range(9):  # breastplate ridge
            out[(23, 20 + j)] = (5, 'p')
            out[(24, 20 + j)] = (2, 'p')
        for i in range(16, 40):  # belt
            if (i, 28) in out:
                out[(i, 28)] = (1, 'p')
        # shoulders: arm 4x12x4 at (40, 16): pauldron rows 0..4, mail sleeve 5..7, bare below
        box(40, 16, 4, 12, 4, lambda j: 'p' if j <= 4 else ('m' if j <= 7 else None))
        # boots: leg 4x12x4 at (0, 16), lowest 6 rows (mail cuff on the top two)
        box(0, 16, 4, 12, 4, lambda j: None if j < 6 else ('m' if j < 8 else 'p'), top=False, bottom=True)
        for i in range(0, 16):  # toe cap line
            if (i, 29) in out and out[(i, 29)][1] == 'p':
                out[(i, 29)] = (4, 'p')
    else:
        # leggings: waist (lower rows of the body) and thighs/knees
        box(16, 16, 8, 12, 4, lambda j: None if j < 7 else ('p' if j <= 8 else 'm'), top=False)
        for i in range(16, 40):
            if (i, 27) in out:
                out[(i, 27)] = (1, 'p')
        out[(23, 27)] = (5, 'p')
        out[(24, 27)] = (5, 'p')
        box(0, 16, 4, 12, 4, lambda j: 'm' if j <= 1 else ('p' if j <= 8 else None))
        for i in range(0, 16):  # knee cop
            if (i, 25) in out:
                out[(i, 25)] = (5, 'p')
                out[(i, 26)] = (2, 'p')
    return out


def armor_image(piece, mid):
    idx = armor_index(piece)
    img = Image.new('RGBA', (64, 32), (0, 0, 0, 0))
    px = img.load()
    for (x, y), (s, st) in idx.items():
        edge = 'in' if 1 <= s <= 4 else 'tl'
        px[x, y] = mat_color(mid, 'plating', 'P', s, edge, x, y, sparse=3)
    return img


# ================================================================================================ writing
def save(img, path, written):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    written.append(path)


def mold_shapes():
    return list(sprites.PARTS.items()) + [('ingot', sprites.INGOT), ('nugget', sprites.NUGGET)]


def write_all(items_only=False):
    check_sprites()
    written = []
    # tool frames (bow pulls, crossbow loads, the rod's cast): the base frame first; a frame that is fully transparent
    # or pixel-identical to one already written for the same tool layer and material is not written, only aliased
    alias = {}
    seen = {}      # (tid, layer, mid) -> {pixels: rel}
    for t in TOOLS:
        tid = t['id']
        for suffix, sprite in tool_variants(tid):
            for layer, role in tool_layers(t):
                for m in MATERIALS:
                    if fits(m, role):
                        mid = m['id']
                        img = layer_image(sprite, layer, mid)
                        rel = 'tool/%s/%s/%s%s' % (tid, layer, mid, suffix)
                        same = seen.setdefault((tid, layer, mid), {})
                        key = (img.mode, img.size, img.tobytes())
                        if suffix and img.getchannel('A').getbbox() is None:
                            alias[rel] = None
                            continue
                        if suffix and key in same:
                            alias[rel] = same[key]
                            continue
                        same.setdefault(key, rel)
                        save(img, os.path.join(ITEM, *rel.split('/')) + '.png', written)
    # frames this run no longer writes (aliased now, or a removed variant) go
    produced = {os.path.normcase(os.path.abspath(p)) for p in written}
    variant_suffixes = {s for t in TOOLS for s, _ in tool_variants(t['id']) if s}
    for root, _, files in os.walk(os.path.join(ITEM, 'tool')):
        for f in files:
            p = os.path.join(root, f)
            if f.endswith('.png') and os.path.normcase(os.path.abspath(p)) not in produced \
                    and any(f[:-4].endswith(s) for s in variant_suffixes | {'_pulling_0', '_pulling_1', '_pulling_2', '_cast', '_arrow', '_firework'}):
                os.remove(p)
    with open(FRAMES, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(dict(sorted(alias.items())), f, indent=1)
        f.write('\n')
    for p in PARTS:
        for m in MATERIALS:
            if fits(m, p['role']):
                mid = m['id']
                save(part_image(p['id'], mid), os.path.join(ITEM, 'part', p['id'], mid + '.png'), written)
    md = os.path.join(ITEM, 'misc')
    for name, sprite in sprites.MISC.items():
        save(fixed_image(sprite), os.path.join(md, name + '.png'), written)
    for m in MATERIALS:
        if m['kind'] == 'metal':
            mid = m['id']
            save(fixed_image(sprites.MISC['ladle'], hot(ITEM_RAMPS[mid])),
                 os.path.join(md, f'ladle_{mid}.png'), written)
    save(fixed_image(sprites.MISC['ladle'], FIXED['oil']), os.path.join(md, 'ladle_fish_oil.png'), written)
    save(fixed_image(sprites.mold('sand', None)), os.path.join(md, 'sand_mold.png'), written)
    save(fixed_image(sprites.tray()), os.path.join(md, 'molding_box.png'), written)
    for shape, sprite in mold_shapes():
        for base, prefix in (('sand', 'sand_mold'), ('raw_mold', 'clay_mold_unfired'), ('terracotta', 'clay_mold')):
            save(fixed_image(sprites.mold(base, sprite)), os.path.join(md, f'{prefix}_{shape}.png'), written)
    for mid in ALLOYS:
        save(material_image(sprites.INGOT, mid), os.path.join(md, f'ingot_{mid}.png'), written)
        save(material_image(sprites.NUGGET, mid), os.path.join(md, f'nugget_{mid}.png'), written)

    if not items_only:
        for m in MATERIALS:
            if fits(m, 'plating'):
                name = f"smith_{m['id']}.png"
                save(armor_image('humanoid', m['id']), os.path.join(EQUIP, name), written)
                save(armor_image('leggings', m['id']), os.path.join(EQUIP_LEGS, name), written)
    return written


# ================================================================================================ contact sheet
SETS = [
    ('iron / leather / wood', {'head': 'iron', 'binding': 'leather', 'handle': 'wood', 'limb': 'wood', 'grip': 'leather',
                               'plating': 'iron', 'mail': 'iron', 'hook': 'iron', 'shaft': 'wood'}),
    ('flint / leather / wood', {'head': 'flint', 'binding': 'leather', 'handle': 'wood', 'limb': 'bone',
                                'grip': 'deer_hide', 'plating': 'copper', 'mail': 'leather', 'hook': 'bone'}),
    ('steel / deer hide / bone', {'head': 'steel', 'binding': 'deer_hide', 'handle': 'bone', 'limb': 'steel',
                                  'grip': 'deer_hide', 'plating': 'steel', 'mail': 'steel', 'shaft': 'bone'}),
    ('rose gold / silk / bamboo', {'head': 'rose_gold', 'binding': 'copper', 'handle': 'bamboo', 'limb': 'bamboo',
                                   'grip': 'leather', 'plating': 'rose_gold', 'mail': 'copper', 'shaft': 'bamboo'}),
    ('netherite / tufthorn', {'head': 'netherite', 'binding': 'tufthorn_hide', 'handle': 'netherite',
                              'limb': 'netherite', 'grip': 'tufthorn_hide', 'plating': 'netherite',
                              'mail': 'netherite'}),
    ('starforged / echo / blazebone', {'head': 'starforged', 'binding': 'echo_steel', 'handle': 'blazebone',
                                       'limb': 'starforged', 'grip': 'deer_hide', 'plating': 'starforged',
                                       'mail': 'echo_steel', 'hook': 'voidgold'}),
    ('rimesteel / obsidian / wood', {'head': 'rimesteel', 'binding': 'obsidian', 'handle': 'wood', 'limb': 'pearlsteel',
                                     'grip': 'leather', 'plating': 'rimesteel', 'mail': 'iron', 'hook': 'chime_bronze'}),
    ('emberforged / gold / blazebone', {'head': 'emberforged_steel', 'binding': 'gold', 'handle': 'blazebone',
                                        'limb': 'copper', 'grip': 'tufthorn_hide', 'plating': 'emberforged_steel',
                                        'mail': 'gold', 'hook': 'gold'}),
    ('chime / voidgold / pearl', {'head': 'chime_bronze', 'binding': 'voidgold', 'handle': 'pearlsteel',
                                  'limb': 'iron', 'grip': 'echo_steel', 'plating': 'chime_bronze', 'mail': 'tidebronze',
                                  'hook': 'tidebronze'}),
]


def set_material(st, layer, role):
    for key in (layer, role):
        mid = st.get(key)
        if mid and fits(MAT[mid], role):
            return mid
    if role == 'head' and fits(MAT[st['head']], 'head'):
        return st['head']
    for m in MATERIALS:
        if fits(m, role):
            return m['id']


def composite(tid, suffix, st):
    t = next(x for x in TOOLS if x['id'] == tid)
    sprite = dict(tool_variants(tid))[suffix]
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for layer, role in tool_layers(t):
        img.alpha_composite(layer_image(sprite, layer, set_material(st, layer, role)))
    return img


def font(sz):
    for f in ('C:/Windows/Fonts/consola.ttf', 'C:/Windows/Fonts/arial.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'):
        if os.path.exists(f):
            return ImageFont.truetype(f, sz)
    return ImageFont.load_default()


def build_sheet():
    S = 4
    cell = 16 * S + 6
    label_w = 190
    rows = []  # (label, [(img, caption)])
    tool_ids = [t['id'] for t in TOOLS]
    for name, st in SETS:
        rows.append((name, [(composite(tid, '', st), tid) for tid in tool_ids]))
    st = SETS[0][1]
    var = []
    for tid in tool_ids:
        for suffix, g in tool_variants(tid):
            if suffix:
                var.append((composite(tid, suffix, st), tid[:5] + suffix))
    rows.append(('variants (set 1)', var))
    st = SETS[5][1]
    var = []
    for tid in tool_ids:
        for suffix, g in tool_variants(tid):
            if suffix:
                var.append((composite(tid, suffix, st), tid[:5] + suffix))
    rows.append(('variants (set 6)', var))
    parts = []
    for p in PARTS:
        role = p['role']
        mid = 'iron' if fits(MAT['iron'], role) else next(m['id'] for m in MATERIALS if fits(m, role))
        parts.append((part_image(p['id'], mid), p['id']))
    rows.append(('parts (iron)', parts))
    rows.append(('pick head, all', [(part_image('pick_head', m['id']), m['id']) for m in MATERIALS if fits(m, 'head')]))
    rows.append(('handle, all', [(part_image('handle', m['id']), m['id']) for m in MATERIALS if fits(m, 'handle')]))
    rows.append(('pickaxe heads', [(composite('pickaxe', '', {'head': m['id'], 'binding': 'leather', 'handle': 'wood'}),
                                    m['id']) for m in MATERIALS if fits(m, 'head')]))
    md = [(fixed_image(sprite), name) for name, sprite in sprites.MISC.items()]
    md.append((fixed_image(sprites.tray()), 'molding_box'))
    md.append((fixed_image(sprites.mold('sand', None)), 'sand_mold'))
    rows.append(('Workshop', md))
    ladles = [(fixed_image(sprites.MISC['ladle'], hot(ITEM_RAMPS[m['id']])), m['id'])
              for m in MATERIALS if m['kind'] == 'metal']
    ladles.append((fixed_image(sprites.MISC['ladle'], FIXED['oil']), 'fish_oil'))
    rows.append(('Ladles', ladles))
    rows.append(('Ingots', [(material_image(sprites.INGOT, mid), mid) for mid in ALLOYS]))
    rows.append(('Nuggets', [(material_image(sprites.NUGGET, mid), mid) for mid in ALLOYS]))
    for base, label in (('sand', 'Sand molds'), ('raw_mold', 'Unfired clay'), ('terracotta', 'Fired clay')):
        rows.append((label, [(fixed_image(sprites.mold(base, sprite)), shape)
                             for shape, sprite in mold_shapes()]))
    ncol = max(len(r[1]) for r in rows)
    W = label_w + ncol * cell + 10
    H = len(rows) * (cell + 14) + 20
    sheet = Image.new('RGBA', (W, H), (46, 50, 64, 255))
    dr = ImageDraw.Draw(sheet)
    f_big, f_small = font(13), font(9)
    y = 10
    for label, items in rows:
        dr.text((8, y + cell // 2 - 6), label, fill=(235, 235, 240, 255), font=f_big)
        for i, (img, cap) in enumerate(items):
            x = label_w + i * cell
            bg = Image.new('RGBA', (16 * S, 16 * S), (74, 82, 104, 255) if (i % 2 == 0) else (68, 76, 98, 255))
            bg.alpha_composite(img.resize((16 * S, 16 * S), Image.NEAREST))
            sheet.alpha_composite(bg, (x, y))
            dr.text((x, y + 16 * S + 1), cap[:12], fill=(170, 175, 190, 255), font=f_small)
        y += cell + 14
    os.makedirs(os.path.dirname(SHEET), exist_ok=True)
    sheet.save(SHEET)
    return SHEET


def main():
    if '--sheet-only' in sys.argv:
        check_sprites()
    else:
        written = write_all(items_only='--items-only' in sys.argv)
        counts = {}
        for p in written:
            rel = os.path.relpath(p, TEX).replace('\\', '/')
            parts = rel.split('/')
            key = '/'.join(parts[:4]) if parts[0] == 'item' else '/'.join(parts[:3])
            if parts[0] == 'item' and parts[2] == 'misc':
                key = 'item/smithing/misc'
            counts[key] = counts.get(key, 0) + 1
        print('smithing_art: wrote %d textures (%s)' % (len(written), FRAMES))
        for k in sorted(counts):
            print('  %5d  %s' % (counts[k], k))
    print('smithing_art: sheet', build_sheet())


if __name__ == '__main__':
    main()
