#!/usr/bin/env python3
"""Counter keepers (feature/shop/CounterKeeper): four custom villager shopkeepers. Re-runnable; gen_content.py runs it
at its end (it clears the item definitions and recipes this writes).

One shared villager body (vanilla proportions: tall head, big nose, robe, crossed arms) plus each trade's own pieces:
  general     bowler hat, spectacles, waistcoat with brass buttons, ledger-clerk apron
  fishmonger  yellow sou'wester with a back flap, rubber apron, knitted navy jumper, grey beard
  grocer      wide straw hat with a red band and a sprig, green apron, rosy cheeks
  miner       yellow hard hat with a lamp, denim overalls, soot on the face, a pickaxe slung on the back

Writes (mod/):
  tools/keepers/keeper_<trade>.bbmodel                              the source model (open in Blockbench to review)
  src/main/resources/assets/aftrhrs/geckolib/models/entity/keeper_<trade>.geo.json
  src/main/resources/assets/aftrhrs/geckolib/animations/entity/keeper_<trade>.animation.json   (idle loop)
  src/main/resources/assets/aftrhrs/textures/entity/keeper_<trade>.png
  src/main/resources/assets/aftrhrs/textures/item/keeper/keeper_<trade>.png, items/, models/item/   (the placing item)
  src/main/resources/assets/aftrhrs/lang/en_us.keepers.json
  src/main/resources/data/aftrhrs/recipe/keeper_<trade>.json

Geometry is in Blockbench space (y up, the front faces -z); the bedrock export follows Blockbench's codec: x mirrored,
rotations x and y negated, up/down UVs flipped. Every face has its own UV rect (1 texel per pixel) painted by material.
"""
import base64
import io
import json
import os
import random
import uuid

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
RES = os.path.join(MOD, 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'aftrhrs')
DATA = os.path.join(RES, 'data', 'aftrhrs')
SRC = os.path.join(HERE, 'keepers')
TEX_W, TEX_H = 128, 128
FACES = ('north', 'east', 'south', 'west', 'up', 'down')


def rgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255)


def shade(c, f):
    return tuple(max(0, min(255, round(v * f))) for v in c)


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


# ---------------------------------------------------------------------------------------------- trades
SKIN = rgb(0xC08E6A)
TRADES = {
    'general': dict(name='Shopkeeper', hair=rgb(0x4A3426), robe=rgb(0x6E2B33), trim=rgb(0xC9A24A), shirt=rgb(0xE8E0CC),
                    apron=rgb(0xD9D2BF), legs=rgb(0x3A2E28), hat=rgb(0x2A2622), band=rgb(0x7A1F28), eyes=rgb(0x3E7A3A),
                    beard=None, recipe=['emerald', 'emerald', 'book', 'paper']),
    'fishmonger': dict(name='Fishmonger', hair=rgb(0x8E8A84), robe=rgb(0x27385A), trim=rgb(0x3B5080), shirt=rgb(0x27385A),
                       apron=rgb(0xE3B92C), legs=rgb(0x2B2B2E), hat=rgb(0xE8BE2E), band=rgb(0xB98F17), eyes=rgb(0x3A6A8C),
                       beard=rgb(0xA9A49C), recipe=['emerald', 'emerald', 'cod', 'fishing_rod']),
    'grocer': dict(name='Grocer', hair=rgb(0x8A5A2E), robe=rgb(0x9C7A52), trim=rgb(0x7B5E3D), shirt=rgb(0xEDE3C8),
                   apron=rgb(0x4F8A3C), legs=rgb(0x4D3B2A), hat=rgb(0xE0C77A), band=rgb(0xB8322E), eyes=rgb(0x4C8A3A),
                   beard=None, recipe=['emerald', 'emerald', 'wheat', 'carrot']),
    'miner': dict(name='Miner', hair=rgb(0x2E2522), robe=rgb(0x8A8F96), trim=rgb(0x6E737A), shirt=rgb(0x8A8F96),
                  apron=rgb(0x3D5D8C), legs=rgb(0x33496E), hat=rgb(0xE6B22A), band=rgb(0x9C7414), eyes=rgb(0x5C4A3A),
                  beard=rgb(0x3A302C), recipe=['emerald', 'emerald', 'iron_pickaxe', 'torch']),
}


# ---------------------------------------------------------------------------------------------- geometry
def cube(name, f, t, mat, inflate=0.0, origin=None, rot=None, faces=FACES):
    return dict(name=name, f=list(f), t=list(t), mat=mat, inflate=inflate, origin=origin, rot=rot, faces=faces)


def base():
    """bone name -> (pivot, rotation, parent, cubes). The shared villager."""
    return {
        'root': ([0, 0, 0], [0, 0, 0], None, []),
        'body': ([0, 24, 0], [0, 0, 0], 'root', [
            cube('torso', (-4, 12, -3), (4, 24, 3), 'shirt'),
            cube('robe', (-4, 6, -3), (4, 24, 3), 'robe', inflate=0.5),
        ]),
        'head': ([0, 24, 0], [0, 0, 0], 'body', [
            cube('skull', (-4, 24, -4), (4, 34, 4), 'head'),
            cube('nose', (-1, 23, -6), (1, 27, -4), 'nose'),
        ]),
        'arms': ([0, 21, -1], [45, 0, 0], 'body', [
            cube('arm_r', (-8, 15, -3), (-4, 23, 1), 'arm'),
            cube('arm_l', (4, 15, -3), (8, 23, 1), 'arm'),
            cube('arm_mid', (-4, 15, -3), (4, 19, 1), 'armmid'),
        ]),
        'leg_r': ([-2, 12, 0], [0, 0, 0], 'root', [cube('leg_r', (-4, 0, -2), (0, 12, 2), 'leg')]),
        'leg_l': ([2, 12, 0], [0, 0, 0], 'root', [cube('leg_l', (0, 0, -2), (4, 12, 2), 'leg')]),
    }


def extras(trade):
    """(bone, cube) pairs for the trade's own pieces."""
    h, b = 'head', 'body'
    if trade == 'general':
        return [(h, cube('hat_brim', (-6, 31.5, -6), (6, 32.5, 6), 'hatbrim')),
                (h, cube('hat_crown', (-4.5, 32, -4.5), (4.5, 38, 4.5), 'hatcrown', inflate=0.25)),
                (h, cube('specs', (-3.5, 28, -4.3), (3.5, 29.5, -4.1), 'specs', faces=('north',))),
                (b, cube('apron', (-3.5, 6, -3.9), (3.5, 20, -3.55), 'apron', faces=('north', 'east', 'west', 'down')))]
    if trade == 'fishmonger':
        return [(h, cube('hat_brim', (-6, 30.5, -6.5), (6, 31.5, 5), 'hatbrim')),
                (h, cube('hat_crown', (-4.5, 31, -4.5), (4.5, 34.5, 4.5), 'hatcrown', inflate=0.25)),
                (h, cube('hat_top', (-3.5, 34.5, -3.5), (3.5, 35.5, 3.5), 'hatcrown')),
                (h, cube('hat_flap', (-5, 26, 4.6), (5, 31.5, 6), 'hatflap')),
                (h, cube('beard', (-4, 22, -4.6), (4, 27, -4.1), 'beard', faces=('north', 'east', 'west', 'down'))),
                (b, cube('apron', (-4, 4, -4.1), (4, 22, -3.6), 'apron', faces=('north', 'east', 'west', 'down')))]
    if trade == 'grocer':
        return [(h, cube('hat_brim', (-7.5, 32, -7.5), (7.5, 33, 7.5), 'hatbrim')),
                (h, cube('hat_crown', (-4.5, 32.5, -4.5), (4.5, 37, 4.5), 'hatcrown', inflate=0.25)),
                (h, cube('hat_sprig', (3.6, 34, -2), (5.2, 37.5, 0), 'sprig')),
                (b, cube('apron', (-3.75, 5, -3.9), (3.75, 21, -3.55), 'apron', faces=('north', 'east', 'west', 'down')))]
    if trade == 'miner':
        return [(h, cube('hat_brim', (-5.5, 31, -6.5), (5.5, 32, 5.5), 'hatbrim')),
                (h, cube('hat_crown', (-4.5, 31.5, -4.5), (4.5, 36, 4.5), 'hatcrown', inflate=0.25)),
                (h, cube('hat_top', (-3.5, 36, -3.5), (3.5, 37, 3.5), 'hatcrown')),
                (h, cube('lamp', (-1.5, 32.5, -6.2), (1.5, 35.5, -4.6), 'lamp')),
                (b, cube('pick_handle', (-0.5, 5, 3.6), (0.5, 25, 4.6), 'handle', origin=[0, 15, 4], rot=[0, 0, 32])),
                (b, cube('pick_head', (-6, 22, 3.4), (6, 24, 4.8), 'pickhead', origin=[0, 15, 4], rot=[0, 0, 32])),
                (b, cube('bib', (-3.5, 14, -3.9), (3.5, 21, -3.55), 'apron', faces=('north', 'east', 'west', 'down')))]
    return []


# ---------------------------------------------------------------------------------------------- painting
def face_size(c, face):
    dx, dy, dz = (c['t'][i] - c['f'][i] for i in range(3))
    return {'north': (dx, dy), 'south': (dx, dy), 'east': (dz, dy), 'west': (dz, dy), 'up': (dx, dz), 'down': (dx, dz)}[face]


def noisy(w, h, base_c, rnd, amp=0.06, grain=None):
    im = Image.new('RGBA', (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            f = 1 + rnd.uniform(-amp, amp)
            if grain == 'v':
                f *= 1 + 0.05 * ((x * 7) % 3 - 1)
            px[x, y] = shade(base_c, f) + (255,)
    return im


def paint(mat, face, w, h, T, rnd, trade):
    """One face of one material, w x h texels."""
    if mat == 'head':
        im = noisy(w, h, SKIN, rnd, 0.04)
        px = im.load()
        hair, hairs = T['hair'], shade(T['hair'], 0.8)
        if face == 'up':
            return noisy(w, h, hair, rnd, 0.08)
        if face == 'down':
            return noisy(w, h, shade(SKIN, 0.8), rnd, 0.03)
        if face == 'north':
            for x in range(w):
                px[x, 0] = hair + (255,)
                if x in (0, w - 1):
                    px[x, 1] = hair + (255,)
            brow = shade(T['hair'], 0.7)
            for x in range(1, w - 1):
                px[x, 3] = brow + (255,)
            for x, c in ((1, (240, 240, 236)), (2, T['eyes']), (5, T['eyes']), (6, (240, 240, 236))):
                px[x, 4] = c + (255,)
            for x in range(1, w - 1):  # cheek shadow under the eyes
                if x not in (3, 4):
                    px[x, 5] = shade(px[x, 5][:3], 0.94) + (255,)
            if trade == 'grocer':
                for x in (1, 6):
                    px[x, 6] = mix(SKIN, (214, 96, 96), 0.55) + (255,)
            if trade == 'general':  # waxed moustache under the nose
                for x in range(2, 6):
                    px[x, 8] = T['hair'] + (255,)
                px[1, 7] = px[6, 7] = T['hair'] + (255,)
            if trade == 'miner':
                for y in range(6, h):
                    for x in range(w):
                        if rnd.random() < 0.35:
                            px[x, y] = shade(px[x, y][:3], 0.72) + (255,)
            if trade != 'general':
                for x in range(2, 6):
                    px[x, 8] = shade(SKIN, 0.7) + (255,)  # mouth
            return im
        # sides and back: hair down to the ears / the neck
        cut = h - 2 if face == 'south' else 3
        for y in range(h):
            for x in range(w):
                if y < cut or (face != 'south' and y < 5 and (x >= w - 3 if face == 'east' else x < 3)):
                    px[x, y] = shade(hair if (x + y) % 3 else hairs, 1 + rnd.uniform(-0.05, 0.05)) + (255,)
        if face in ('east', 'west'):  # ear
            ex = 3 if face == 'east' else w - 4
            for y in (4, 5):
                px[ex, y] = shade(SKIN, 0.85) + (255,)
        return im
    if mat == 'nose':
        im = noisy(w, h, shade(SKIN, 0.95), rnd, 0.03)
        if face == 'north':
            im.putpixel((0, h - 1), shade(SKIN, 0.8) + (255,))
            im.putpixel((w - 1, h - 1), shade(SKIN, 0.8) + (255,))
        return im
    if mat == 'shirt':
        im = noisy(w, h, T['shirt'], rnd, 0.04)
        if face == 'north' and trade == 'general':  # cravat
            px = im.load()
            for y in range(0, 3):
                for x in range(3, 5):
                    px[x, y] = T['band'] + (255,)
        return im
    if mat == 'robe':
        im = noisy(w, h, T['robe'], rnd, 0.05, grain='v' if trade == 'fishmonger' else None)
        px = im.load()
        if trade == 'fishmonger':  # knitted rows
            for y in range(h):
                if y % 4 == 3:
                    for x in range(w):
                        px[x, y] = shade(T['robe'], 0.82) + (255,)
        if trade == 'miner' and face in ('north', 'south'):  # overall straps
            for y in range(0, 8):
                for x in (1, 2, w - 3, w - 2):
                    px[x, y] = T['apron'] + (255,)
            for y in range(8, h):
                for x in range(w):
                    px[x, y] = shade(T['apron'], 1 + rnd.uniform(-0.06, 0.06)) + (255,)
        if trade == 'miner' and face in ('east', 'west'):
            for y in range(8, h):
                for x in range(w):
                    px[x, y] = shade(T['apron'], 0.9 + rnd.uniform(-0.05, 0.05)) + (255,)
        if trade == 'general' and face == 'north':  # waistcoat opening and brass buttons
            for y in range(h - 2):
                px[3, y] = px[4, y] = shade(T['shirt'], 0.95) + (255,)
            for y in (3, 6, 9):
                px[3, y] = T['trim'] + (255,)
        if face in ('north', 'south', 'east', 'west'):  # hem
            for x in range(w):
                px[x, h - 1] = shade(T['trim'], 0.9) + (255,)
        return im
    if mat in ('arm', 'armmid'):
        im = noisy(w, h, T['robe'] if trade != 'miner' else T['shirt'], rnd, 0.05)
        px = im.load()
        if trade == 'general':
            im = noisy(w, h, T['shirt'], rnd, 0.04)  # shirt sleeves
            px = im.load()
        if mat == 'arm' and face in ('north', 'south', 'east', 'west'):  # hands at the end (bottom of the box)
            for y in range(h - 3, h):
                for x in range(w):
                    px[x, y] = shade(SKIN, 1 + rnd.uniform(-0.04, 0.04)) + (255,)
            for x in range(w):
                px[x, h - 4] = shade(T['trim'], 0.9) + (255,)  # cuff
        if mat == 'arm' and face == 'down':
            im = noisy(w, h, SKIN, rnd, 0.04)
        if mat == 'armmid' and face in ('north', 'down', 'up'):
            pass
        return im
    if mat == 'leg':
        im = noisy(w, h, T['legs'], rnd, 0.05)
        px = im.load()
        boot = rgb(0xE0B22A) if trade == 'fishmonger' else shade(T['legs'], 0.55)
        for y in range(h - 4, h):
            for x in range(w):
                px[x, y] = shade(boot, 1 + rnd.uniform(-0.05, 0.05)) + (255,)
        if face == 'down':
            return noisy(w, h, shade(boot, 0.7), rnd, 0.04)
        return im
    if mat == 'hatbrim':
        im = noisy(w, h, shade(T['hat'], 0.92 if face != 'up' else 1.0), rnd, 0.06)
        if trade == 'grocer':  # woven straw
            px = im.load()
            for y in range(h):
                for x in range(w):
                    if (x + 2 * y) % 4 == 0:
                        px[x, y] = shade(T['hat'], 0.82) + (255,)
        return im
    if mat == 'hatcrown':
        im = noisy(w, h, T['hat'], rnd, 0.05)
        px = im.load()
        if trade == 'grocer':
            for y in range(h):
                for x in range(w):
                    if (x + 2 * y) % 4 == 0:
                        px[x, y] = shade(T['hat'], 0.82) + (255,)
        if face in ('north', 'south', 'east', 'west') and trade in ('general', 'grocer'):
            for x in range(w):
                px[x, h - 2] = T['band'] + (255,)
                px[x, h - 3] = shade(T['band'], 0.85) + (255,)
        if face in ('north', 'south', 'east', 'west') and trade in ('fishmonger', 'miner'):
            for x in range(w):
                px[x, h - 1] = shade(T['hat'], 0.8) + (255,)
            if trade == 'miner' and face in ('east', 'west'):  # ridge
                for y in range(h):
                    px[w // 2, y] = shade(T['hat'], 1.1) + (255,)
        if face == 'up' and trade == 'miner':
            for y in range(h):
                px[w // 2, y] = shade(T['hat'], 1.1) + (255,)
        if face == 'up' and trade == 'general':
            for x in range(w):
                for y in range(h):
                    if x in (0, w - 1) or y in (0, h - 1):
                        px[x, y] = shade(T['hat'], 0.85) + (255,)
        return im
    if mat == 'hatflap':
        return noisy(w, h, shade(T['hat'], 0.9), rnd, 0.06)
    if mat == 'specs':
        im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        px = im.load()
        rim = (52, 42, 30, 255)
        for x in range(w):
            if x != 3:
                px[x, 0] = rim
        px[3, 0] = rim
        for x in (0, 2, 4, 6):
            if x < w:
                px[x, h - 1] = rim
        return im
    if mat == 'beard':
        im = noisy(w, h, T['beard'], rnd, 0.08)
        px = im.load()
        if face == 'north':  # clear for the mouth and the nose above it
            for y in range(0, 2):
                for x in range(2, 6):
                    px[x, y] = (0, 0, 0, 0)
            for y in range(0, h):
                for x in (0, w - 1):
                    if y < 1:
                        px[x, y] = (0, 0, 0, 0)
        return im
    if mat == 'apron':
        im = noisy(w, h, T['apron'], rnd, 0.05)
        px = im.load()
        if face == 'north':
            if trade == 'fishmonger':  # rubber shine
                for y in range(h):
                    px[1, y] = shade(T['apron'], 1.12) + (255,)
            if trade in ('grocer', 'general'):  # pocket
                for x in range(1, w - 1):
                    px[x, h - 6] = shade(T['apron'], 0.8) + (255,)
                for x in (1, w - 2):
                    px[x, h - 5] = px[x, h - 4] = shade(T['apron'], 0.8) + (255,)
            if trade == 'miner':  # bib pocket and buckles
                px[1, 0] = px[w - 2, 0] = (196, 196, 196, 255)
                for x in range(2, w - 2):
                    px[x, 3] = shade(T['apron'], 0.8) + (255,)
        return im
    if mat == 'sprig':
        return noisy(w, h, rgb(0x4E9A35), rnd, 0.12)
    if mat == 'lamp':
        im = noisy(w, h, rgb(0x4A4A4E), rnd, 0.05)
        if face == 'north':
            px = im.load()
            for y in range(h):
                for x in range(w):
                    px[x, y] = (255, 244, 190, 255) if 0 < x < w - 1 and 0 < y < h - 1 else (120, 120, 124, 255)
        return im
    if mat == 'handle':
        return noisy(w, h, rgb(0x7A5634), rnd, 0.06, grain='v')
    if mat == 'pickhead':
        return noisy(w, h, rgb(0x9EA3A8), rnd, 0.07)
    raise ValueError(mat)


# ---------------------------------------------------------------------------------------------- packing
class Packer:
    def __init__(self):
        self.x = self.y = self.row = 0

    def place(self, w, h):
        if self.x + w > TEX_W:
            self.x, self.y, self.row = 0, self.y + self.row, 0
        at = (self.x, self.y)
        self.x += w
        self.row = max(self.row, h)
        if self.y + h > TEX_H:
            raise RuntimeError('texture full')
        return at


def build(trade):
    T = TRADES[trade]
    rnd = random.Random(hash(trade) & 0xFFFF if False else sum(map(ord, trade)))
    bones = base()
    for bone, c in extras(trade):
        bones[bone][3].append(c)
    tex = Image.new('RGBA', (TEX_W, TEX_H), (0, 0, 0, 0))
    pk = Packer()
    for bone, (_, _, _, cubes) in bones.items():
        for c in cubes:
            c['uv'] = {}
            for face in c['faces']:
                fw, fh = face_size(c, face)
                w, h = max(1, round(fw)), max(1, round(fh))
                x, y = pk.place(w, h)
                tex.alpha_composite(paint(c['mat'], face, w, h, T, rnd, trade), (x, y))
                c['uv'][face] = [x, y, x + fw if fw >= 1 else x + 1, y + fh if fh >= 1 else y + 1]
    return bones, tex


def geo(trade, bones):
    out = []
    for name, (pivot, rot, parent, cubes) in bones.items():
        b = {'name': name, 'pivot': [-pivot[0], pivot[1], pivot[2]]}
        if parent:
            b['parent'] = parent
        if any(rot):
            b['rotation'] = [-rot[0], -rot[1], rot[2]]
        cs = []
        for c in cubes:
            f, t = c['f'], c['t']
            e = {'origin': [-t[0], f[1], f[2]], 'size': [t[i] - f[i] for i in range(3)]}
            if c['inflate']:
                e['inflate'] = c['inflate']
            if c['rot']:
                e['pivot'] = [-c['origin'][0], c['origin'][1], c['origin'][2]]
                e['rotation'] = [-c['rot'][0], -c['rot'][1], c['rot'][2]]
            uv = {}
            for face, (u1, v1, u2, v2) in c['uv'].items():
                if face in ('up', 'down'):
                    uv[face] = {'uv': [u2, v2], 'uv_size': [u1 - u2, v1 - v2]}
                else:
                    uv[face] = {'uv': [u1, v1], 'uv_size': [u2 - u1, v2 - v1]}
            e['uv'] = uv
            cs.append(e)
        if cs:
            b['cubes'] = cs
        out.append(b)
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': f'geometry.keeper_{trade}', 'texture_width': TEX_W, 'texture_height': TEX_H,
                        'visible_bounds_width': 2, 'visible_bounds_height': 3, 'visible_bounds_offset': [0, 1.5, 0]},
        'bones': out}]}


def bbmodel(trade, bones, tex):
    buf = io.BytesIO()
    tex.save(buf, 'PNG')
    tex_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, f'keeper/{trade}/tex'))
    elements, groups, outliner, nodes = [], [], [], {}
    for name, (pivot, rot, parent, cubes) in bones.items():
        gid = str(uuid.uuid5(uuid.NAMESPACE_URL, f'keeper/{trade}/{name}'))
        groups.append({'name': name, 'uuid': gid, 'origin': pivot, 'rotation': rot, 'export': True, 'visibility': True,
                       'mirror_uv': False, 'reset': False, 'color': 0, 'autouv': 0, 'shade': True, 'locked': False})
        node = {'uuid': gid, 'isOpen': True, 'children': []}
        nodes[name] = node
        for c in cubes:
            cid = str(uuid.uuid5(uuid.NAMESPACE_URL, f'keeper/{trade}/{name}/{c["name"]}'))
            faces = {f: ({'uv': c['uv'][f], 'texture': 0} if f in c['uv'] else {'uv': [0, 0, 0, 0], 'texture': None}) for f in FACES}
            elements.append({'name': c['name'], 'box_uv': False, 'rescale': False, 'locked': False, 'light_emission': 0,
                             'render_order': 'default', 'allow_mirror_modeling': True, 'from': c['f'], 'to': c['t'],
                             'autouv': 0, 'color': 0, 'inflate': c['inflate'],
                             'origin': c['origin'] or pivot, 'rotation': c['rot'] or [0, 0, 0], 'faces': faces,
                             'type': 'cube', 'uuid': cid})
            node['children'].append(cid)
        if parent:
            nodes[parent]['children'].append(node)
        else:
            outliner.append(node)
    return {'meta': {'format_version': '5.0', 'model_format': 'free', 'box_uv': False}, 'name': f'keeper_{trade}',
            'model_identifier': f'keeper_{trade}', 'visible_box': [1, 2, 0], 'resolution': {'width': TEX_W, 'height': TEX_H},
            'elements': elements, 'groups': groups, 'outliner': outliner,
            'textures': [{'name': f'keeper_{trade}.png', 'folder': '', 'namespace': '', 'id': '0', 'width': TEX_W,
                          'height': TEX_H, 'uv_width': TEX_W, 'uv_height': TEX_H, 'particle': False, 'render_mode': 'default',
                          'render_sides': 'auto', 'pbr_channel': 'color', 'visible': True, 'internal': True, 'saved': True,
                          'uuid': tex_uuid, 'relative_path': '',
                          'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}],
            'animations': []}


def animation():
    """Idle: a slow breath on the body, the crossed arms rising with it."""
    def kf(vals):
        return {f'{t:.1f}': {'vector': v, 'lerp_mode': 'catmullrom'} for t, v in vals}
    return {'format_version': '1.8.0', 'animations': {'idle': {'loop': True, 'animation_length': 4.0, 'bones': {
        'body': {'scale': kf([(0, [1, 1, 1]), (2, [1.015, 1.01, 1.015]), (4, [1, 1, 1])])},
        'arms': {'rotation': kf([(0, [0, 0, 0]), (2, [-2.5, 0, 0]), (4, [0, 0, 0])])},
    }}}}


def item_sprite(trade):
    """16x16: the keeper's head and hat."""
    T = TRADES[trade]
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = im.load()
    out = (40, 30, 26, 255)
    for y in range(6, 15):
        for x in range(4, 12):
            px[x, y] = shade(SKIN, 1.0 if x < 10 else 0.88) + (255,)
    for x, c in ((5, (240, 240, 236)), (6, T['eyes']), (9, T['eyes']), (10, (240, 240, 236))):
        px[x, 9] = c + (255,)
    for x in range(5, 11):
        px[x, 8] = shade(T['hair'], 0.7) + (255,)
    for y in range(10, 14):
        for x in (7, 8):
            px[x, y] = shade(SKIN, 0.92 if x == 7 else 0.8) + (255,)
    if T['beard']:
        for y in (13, 14):
            for x in range(4, 12):
                if x not in (7, 8) or y == 14:
                    px[x, y] = T['beard'] + (255,)
    hat, dark = T['hat'], shade(T['hat'], 0.78)
    brim = {'general': (2, 14, 6), 'fishmonger': (2, 14, 6), 'grocer': (0, 16, 6), 'miner': (3, 13, 6)}[trade]
    for x in range(brim[0], brim[1]):
        px[x, brim[2]] = dark + (255,)
    top = 1 if trade == 'general' else 2
    for y in range(top, 6):
        for x in range(4, 12):
            px[x, y] = (hat if x < 10 else dark) + (255,)
    if trade in ('general', 'grocer'):
        for x in range(4, 12):
            px[x, 5] = T['band'] + (255,)
    if trade == 'miner':
        for y in (3, 4):
            for x in (7, 8):
                px[x, y] = (255, 244, 190, 255)
    if trade == 'grocer':
        px[11, 3] = px[12, 2] = (78, 154, 53, 255)
    # outline
    src = im.copy().load()
    for y in range(16):
        for x in range(16):
            if src[x, y][3] == 0 and any(0 <= x + dx < 16 and 0 <= y + dy < 16 and src[x + dx, y + dy][3]
                                         for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = out
    return im


def main():
    os.makedirs(SRC, exist_ok=True)
    paths = {k: os.path.join(ASSETS, *k.split('/')) for k in ('geckolib/models/entity', 'geckolib/animations/entity',
                                                              'textures/entity', 'textures/item/keeper', 'items', 'models/item')}
    for p in paths.values():
        os.makedirs(p, exist_ok=True)
    os.makedirs(os.path.join(DATA, 'recipe'), exist_ok=True)
    lang = {}
    for trade, T in TRADES.items():
        tid = f'keeper_{trade}'
        bones, tex = build(trade)
        tex.save(os.path.join(paths['textures/entity'], tid + '.png'))
        json.dump(geo(trade, bones), open(os.path.join(paths['geckolib/models/entity'], tid + '.geo.json'), 'w'))
        json.dump(animation(), open(os.path.join(paths['geckolib/animations/entity'], tid + '.animation.json'), 'w'))
        json.dump(bbmodel(trade, bones, tex), open(os.path.join(SRC, tid + '.bbmodel'), 'w'))
        item_sprite(trade).save(os.path.join(paths['textures/item/keeper'], tid + '.png'))
        json.dump({'model': {'type': 'minecraft:model', 'model': f'aftrhrs:item/{tid}'}},
                  open(os.path.join(paths['items'], tid + '.json'), 'w'), indent=1)
        json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'aftrhrs:item/keeper/{tid}'}},
                  open(os.path.join(paths['models/item'], tid + '.json'), 'w'), indent=1)
        json.dump({'type': 'minecraft:crafting_shapeless', 'category': 'misc',
                   'ingredients': [f'minecraft:{i}' for i in T['recipe']], 'result': {'id': f'aftrhrs:{tid}'}},
                  open(os.path.join(DATA, 'recipe', tid + '.json'), 'w'), indent=1)
        lang[f'entity.aftrhrs.{tid}'] = T['name']
        lang[f'item.aftrhrs.{tid}'] = T['name']
    json.dump(lang, open(os.path.join(ASSETS, 'lang', 'en_us.keepers.json'), 'w'), indent=1)
    print(f'keepers: {len(TRADES)} models, textures, items, recipes')


if __name__ == '__main__':
    main()
