"""Geometry of every Christmas piece. Each function returns (elements, textures) for one model; texture values are
pack refs without a namespace ("block/spruce_log", "arcana/christmas/fir_needles")."""
import math

from geom import E, box, star8, star_radius, mirror_x, scale_els, shift, SIDES, FACES

X = 'arcana/christmas/'
BULB_UV = lambda col, row: [col, row, col + 1, row + 1]  # noqa: E731
BAUBLE_IDX = {'red': 0, 'gold': 1, 'silver': 2, 'blue': 3, 'green': 4, 'purple': 5, 'pink': 6, 'white': 7}


def bauble_uv(colour, matte=False):
    i = BAUBLE_IDX[colour]
    y = 2 if matte else 0
    return [2 * i, y, 2 * i + 2, y + 2]


# ================================================================ the fir tree
# Six tiers, each a wider lower "branch star" and a narrower upper one: (y0, y1, apothem low, apothem high).
TIERS = [(4.0, 13.0, 12.0, 9.5), (11.0, 19.5, 10.5, 8.0), (17.5, 25.5, 8.8, 6.4),
         (23.5, 31.0, 7.0, 4.8), (29.5, 36.0, 5.2, 3.2), (34.5, 40.5, 3.4, 1.9)]
TIP_TOP = 44.0


def tier_mid(t):
    y0, y1, _, _ = t
    return round(y0 + (y1 - y0) * 0.5, 2)


def foliage(side='side', top='top', under='under'):
    els = []
    for i, t in enumerate(TIERS):
        y0, y1, h0, h1 = t
        ym = tier_mid(t)
        tex_l = {'north': side, 'east': side, 'south': side, 'west': side, 'up': top, 'down': under}
        tex_u = {'north': side, 'east': side, 'south': side, 'west': side, 'up': top}
        els += star8(f't{i}_lo', 8, 8, y0, ym, h0, tex_l, rot_inset=(0.4, 0.4))
        els += star8(f't{i}_hi', 8, 8, ym, y1, h1, tex_u, faces=('north', 'east', 'south', 'west', 'up'),
                     rot_inset=(0.0, 0.4))
    els.append(E('tip', (7.2, 40.5, 7.2), (8.8, TIP_TOP, 8.8),
                 {'north': side, 'east': side, 'south': side, 'west': side, 'up': top}))
    return els


def ring_points(t, n, y, off=0.0, out=0.35, low=True):
    """n points around tier t's lower (or upper) star at height y: (x, z, theta, r)."""
    y0, y1, h0, h1 = t
    h = h0 if low else h1
    pts = []
    for k in range(n):
        th = off + k * 2 * math.pi / n
        r = star_radius(th, h) + out
        pts.append((8 + r * math.cos(th), 8 + r * math.sin(th), th, r))
    return pts


LIGHT_TIERS = 6


def bulb_layout():
    """(x, y, z, colour column, phase row) of every tree bulb (shared by the decorated trees and the fir tree's
    BER-drawn light strand)."""
    out = []
    for i, t in enumerate(TIERS[:LIGHT_TIERS]):
        y0, y1, h0, h1 = t
        n = max(4, round(2 * math.pi * h0 * 1.15 / 5.2))
        for k, (x, z, th, r) in enumerate(ring_points(t, n, 0, off=0.35 * i + 0.2, out=0.3)):
            out.append((x, y0 + 1.1 + (0.8 if k % 2 else 0), z, k, (k * 7 + i * 5) % 16))
        if i < 4:
            m = max(4, round(2 * math.pi * h1 * 1.15 / 5.5))
            ym = tier_mid(t)
            for k, (x, z, th, r) in enumerate(ring_points(t, m, 0, off=0.35 * i + 0.9, out=0.3, low=False)):
                out.append((x, ym + 1.2, z, k + 3, (k * 3 + i * 11 + 4) % 16))
    return out


def bulbs(colours, var='bulbs', size=1.25, emit=15, scale=None):
    els = []
    for j, (x, y, z, k, ph) in enumerate(bulb_layout()):
        col = colours[k % len(colours)]
        s = size / 2
        f, t = (x - s, y, z - s), (x + s, y + size, z + s)
        if scale:
            f = tuple(8 + (v - 8) * scale for v in f)
            t = tuple(8 + (v - 8) * scale for v in t)
        els.append(E(f'bulb{j}', f, t, var, uv=BULB_UV(col, ph), emit=emit))
    return els


def baubles(colours, size=2.0):
    els = []
    j = 0
    for i, t in enumerate(TIERS[:5]):
        y0, y1, h0, h1 = t
        n = [6, 5, 4, 4, 3][i]
        for k, (x, z, th, r) in enumerate(ring_points(t, n, 0, off=0.35 * i + 0.75, out=0.55)):
            c = colours[(k + i) % len(colours)]
            y = y0 + 2.4 + (0.6 if (k + i) % 2 else 0) - size / 2
            els.append(box(f'bauble{j}', x, y, z, size, size, size, 'baubles', uv=bauble_uv(c)))
            j += 1
    return els


def garland(var='bead', tiers=4, size=0.8):
    els = []
    j = 0
    for i, t in enumerate(TIERS[:tiers]):
        y0, y1, h0, h1 = t
        ym = tier_mid(t)
        swags = 6 if i < 2 else 5
        n = swags * 5
        for k in range(n):
            th = 0.35 * i + k * 2 * math.pi / n
            f = (k % 5) / 5.0
            sag = 1.7 * math.sin(math.pi * f)
            r = star_radius(th, h0) + 0.2
            x, z = 8 + r * math.cos(th), 8 + r * math.sin(th)
            y = ym - 0.7 - sag
            els.append(box(f'bead{j}', x, y - size / 2, z, size, size, size, var))
            j += 1
    return els


def star_topper(cy=47.0, var='star', emit=15, k=1.0):
    """Two crossed eight-point stars (one facing north, one facing east) centred at (8, cy, 8)."""
    c = (8, cy, 8)
    els = [
        E('star_core', (8 - 1.25 * k, cy - 1.25 * k, 8 - 0.75 * k), (8 + 1.25 * k, cy + 1.25 * k, 8 + 0.75 * k), var, emit=emit),
        E('star_v', (8 - 0.6 * k, cy - 2.8 * k, 8 - 0.6 * k), (8 + 0.6 * k, cy + 3.2 * k, 8 + 0.6 * k), var, emit=emit),
        E('star_hx', (8 - 3.4 * k, cy - 0.6 * k, 8 - 0.45 * k), (8 + 3.4 * k, cy + 0.6 * k, 8 + 0.45 * k), var, emit=emit),
        E('star_hz', (8 - 0.45 * k, cy - 0.55 * k, 8 - 3.4 * k), (8 + 0.45 * k, cy + 0.55 * k, 8 + 3.4 * k), var, emit=emit),
        E('star_d1', (8 - 2.4 * k, cy - 0.45 * k, 8 - 0.4 * k), (8 + 2.4 * k, cy + 0.45 * k, 8 + 0.4 * k), var, emit=emit,
          rot=('z', 45, c)),
        E('star_d2', (8 - 2.4 * k, cy - 0.45 * k, 8 - 0.4 * k), (8 + 2.4 * k, cy + 0.45 * k, 8 + 0.4 * k), var, emit=emit,
          rot=('z', -45, c)),
        E('star_d3', (8 - 0.4 * k, cy - 0.45 * k, 8 - 2.4 * k), (8 + 0.4 * k, cy + 0.45 * k, 8 + 2.4 * k), var, emit=emit,
          rot=('x', 45, c)),
        E('star_d4', (8 - 0.4 * k, cy - 0.45 * k, 8 - 2.4 * k), (8 + 0.4 * k, cy + 0.45 * k, 8 + 2.4 * k), var, emit=emit,
          rot=('x', -45, c)),
    ]
    return els


def angel_topper(b=43.0, k=1.0):
    """A small angel: white robe, gold hair, glowing halo, white wings. b = bottom of the robe."""
    def bx(name, x0, y0, z0, x1, y1, z1, tex, **kw):
        return E(name, (8 + (x0 - 8) * k, b + y0 * k, 8 + (z0 - 8) * k), (8 + (x1 - 8) * k, b + y1 * k, 8 + (z1 - 8) * k),
                 tex, **kw)
    els = [
        bx('robe_lo', 6.0, 0.1, 6.4, 10.0, 2.6, 9.6, 'robe'),
        bx('hem', 5.8, 0, 6.2, 10.2, 0.6, 9.8, 'halo'),
        bx('robe_hi', 6.6, 2.6, 6.9, 9.4, 4.6, 9.1, 'robe'),
        bx('sleeves', 5.6, 3.0, 7.2, 10.4, 4.0, 8.8, 'robe'),
        bx('hands', 7.4, 3.0, 6.5, 8.6, 3.9, 7.2, 'skin'),
        bx('head', 7.0, 4.6, 7.0, 9.0, 6.6, 9.0, 'skin'),
        bx('hair', 6.8, 5.7, 7.6, 9.2, 6.85, 9.25, 'hair'),
        bx('hair_back', 6.85, 4.4, 8.6, 9.15, 5.7, 9.3, 'hair'),
    ]
    for i, (x0, z0, x1, z1) in enumerate(((6.6, 6.6, 9.4, 7.0), (6.6, 9.0, 9.4, 9.4), (6.6, 7.0, 7.0, 9.0), (9.0, 7.0, 9.4, 9.0))):
        els.append(bx(f'halo{i}', x0, 7.4, z0, x1, 7.8, z1, 'halo', emit=15))
    wing = bx('wing_l', 3.6, 2.4, 9.3, 7.4, 6.6, 9.8, 'wing', rot=('z', 22.5, (8 + (7.4 - 8) * k, b + 3.0 * k, 8 + (9.5 - 8) * k)))
    els.append(wing)
    els += mirror_x([wing], suffix='_r')
    return els


def tree_skirt(skirt='skirt', trim='trim'):
    no_down = ('north', 'east', 'south', 'west', 'up')
    els = star8('skirt', 8, 8, 0, 0.75, 11.5, skirt, faces=no_down, rot_inset=(0.0, 0.25))
    els += star8('skirt_trim', 8, 8, 0, 0.45, 12.1, trim, faces=no_down, rot_inset=(0.0, 0.05))
    return els


def trunk(y0=0.75, y1=5.0):
    return [E('trunk', (6.5, y0, 6.5), (9.5, y1, 9.5), {f: 'trunk' for f in SIDES} | {'up': 'trunk_top', 'down': 'trunk_top'})]


def mini_present(name, x0, z0, w, d, h, paper, ribbon, y0=0.0):
    x1, z1 = x0 + w, z0 + d
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2
    els = [E(name, (x0, y0, z0), (x1, y0 + h, z1), paper, faces=('north', 'east', 'south', 'west', 'up'))]
    rw = 1.2
    els.append(E(name + '_rx', (cx - rw / 2, y0, z0 - 0.15), (cx + rw / 2, y0 + h + 0.15, z1 + 0.15), ribbon,
                 faces=('north', 'south', 'up')))
    els.append(E(name + '_rz', (x0 - 0.15, y0, cz - rw / 2), (x1 + 0.15, y0 + h + 0.1, cz + rw / 2), ribbon,
                 faces=('east', 'west', 'up')))
    els.append(E(name + '_bow', (cx - 1.0, y0 + h + 0.15, cz - 0.6), (cx + 1.0, y0 + h + 1.3, cz + 0.6), ribbon,
                 rot=('y', 45, (cx, 0, cz))))
    return els


def decorated_tree(kind):
    if kind == 'classic':
        tex = {'side': X + 'fir_needles', 'top': X + 'fir_needles', 'under': X + 'fir_needles_alt',
               'trunk': 'block/spruce_log', 'trunk_top': 'block/spruce_log_top', 'skirt': X + 'felt_red',
               'trim': X + 'fur_white', 'bulbs': X + 'bulbs', 'baubles': X + 'baubles', 'bead': X + 'gold_shine',
               'star': X + 'star_gold', 'p1': X + 'wrap_green', 'r1': X + 'ribbon_red', 'p2': X + 'wrap_blue',
               'r2': X + 'ribbon_silver', 'particle': X + 'fir_needles'}
        els = foliage() + trunk() + tree_skirt()
        els += bulbs([0, 1, 2, 3, 4, 5])
        els += baubles(['red', 'gold', 'red', 'gold', 'silver'])
        els += garland('bead')
        els += star_topper()
    else:  # frosted
        tex = {'side': X + 'fir_snow_side', 'top': X + 'fir_snow_top', 'under': X + 'fir_needles_alt',
               'trunk': 'block/spruce_log', 'trunk_top': 'block/spruce_log_top', 'skirt': X + 'fur_white',
               'trim': X + 'silver_shine', 'bulbs': X + 'bulbs', 'baubles': X + 'baubles', 'bead': X + 'silver_shine',
               'robe': X + 'paint_white', 'skin': X + 'paint_skin', 'hair': X + 'gold_shine', 'halo': X + 'star_gold',
               'wing': X + 'fur_white', 'p1': X + 'wrap_white', 'r1': X + 'ribbon_green', 'p2': X + 'wrap_gold',
               'r2': X + 'ribbon_red', 'particle': X + 'fir_snow_side'}
        els = foliage() + trunk() + tree_skirt()
        els += bulbs([6, 6, 1, 7, 6, 2])
        els += baubles(['silver', 'blue', 'white', 'blue', 'silver'])
        els += garland('bead')
        els += angel_topper(b=TIP_TOP - 1.2)
    els += mini_present('gift_a', 0.5, -2.5, 4.0, 3.5, 3.0, 'p1', 'r1', y0=0.75)
    els += mini_present('gift_b', 11.0, -1.8, 3.5, 3.0, 2.4, 'p2', 'r2', y0=0.75)
    return els, tex


def fir_tree():
    tex = {'side': X + 'fir_needles', 'top': X + 'fir_needles', 'under': X + 'fir_needles_alt',
           'trunk': 'block/spruce_log', 'trunk_top': 'block/spruce_log_top', 'stand': 'block/spruce_planks',
           'particle': X + 'fir_needles'}
    els = foliage()
    els += [E('trunk', (6.5, 0.0, 6.5), (9.5, 5.0, 9.5), {f: 'trunk' for f in SIDES} | {'up': 'trunk_top'}),
            E('stand_x', (2.5, 0, 7.0), (13.5, 1.5, 9.0), 'stand'),
            E('stand_z', (7.0, 0, 2.5), (9.0, 1.25, 13.5), 'stand', faces=('north', 'east', 'south', 'west', 'up'))]
    return els, tex


def fir_slots():
    """Ornament slots on the plain fir tree: (x, y, z, angle_deg) in block-model px, plus the topper point."""
    out = []
    for i, t in enumerate(TIERS[:5]):
        y0, y1, h0, h1 = t
        n = [6, 5, 4, 3, 2][i]
        for k, (x, z, th, r) in enumerate(ring_points(t, n, 0, off=0.5 * i + 0.4, out=0.9)):
            out.append((round(x, 3), round(y0 + 1.4 + (0.5 if k % 2 else 0), 3), round(z, 3), round(math.degrees(th), 2)))
    return out, (8.0, TIP_TOP, 8.0)


def fir_lights(lit):
    """The fir tree's light strand, drawn by the tree's block entity renderer as an item model at half scale."""
    tex = {'bulbs': X + ('bulbs' if lit else 'bulbs_unlit'), 'particle': X + 'bulbs_unlit'}
    els = bulbs([0, 1, 2, 3, 4, 5], emit=15 if lit else 0, size=1.25, scale=0.5)
    for e in els:
        e['from'][1] = round(e['from'][1], 4)
    return els, tex


def tabletop_tree():
    tex = {'side': X + 'fir_needles', 'top': X + 'fir_needles', 'under': X + 'fir_needles_alt',
           'crate': 'block/spruce_planks', 'band': X + 'paint_red', 'soil': 'block/rooted_dirt',
           'trunk': 'block/spruce_log', 'trunk_top': 'block/spruce_log_top', 'bulbs': X + 'bulbs',
           'baubles': X + 'baubles', 'star': X + 'star_gold', 'particle': X + 'fir_needles'}
    els = [E('crate', (4.5, 0, 4.5), (11.5, 4.0, 11.5), 'crate'),
           E('band', (4.3, 1.4, 4.3), (11.7, 2.4, 11.7), 'band', faces=SIDES),
           E('soil', (5.0, 4.0, 5.0), (11.0, 4.3, 11.0), 'soil', faces=('up',)),
           E('trunk', (7.3, 4.0, 7.3), (8.7, 6.0, 8.7), {f: 'trunk' for f in SIDES})]
    tiers = [(5.5, 9.0, 4.6, 3.4), (8.2, 11.4, 3.6, 2.4), (10.6, 13.4, 2.5, 1.3)]
    for i, (y0, y1, h0, h1) in enumerate(tiers):
        ym = (y0 + y1) / 2
        tl = {'north': 'side', 'east': 'side', 'south': 'side', 'west': 'side', 'up': 'top', 'down': 'under'}
        tu = {'north': 'side', 'east': 'side', 'south': 'side', 'west': 'side', 'up': 'top'}
        els += star8(f't{i}_lo', 8, 8, y0, ym, h0, tl, rot_inset=(0.25, 0.25))
        els += star8(f't{i}_hi', 8, 8, ym, y1, h1, tu, faces=('north', 'east', 'south', 'west', 'up'), rot_inset=(0.0, 0.25))
        n = [8, 6, 4][i]
        for k in range(n):
            th = 0.4 * i + k * 2 * math.pi / n
            r = star_radius(th, h0) + 0.2
            x, z = 8 + r * math.cos(th), 8 + r * math.sin(th)
            y = y0 + 0.5 + (0.5 if k % 2 else 0)
            if k % 3 == 2 and i < 2:
                els.append(box(f'b{i}_{k}', x, y, z, 1.2, 1.2, 1.2, 'baubles', uv=bauble_uv(['red', 'gold'][k % 2])))
            else:
                els.append(E(f'l{i}_{k}', (x - 0.35, y, z - 0.35), (x + 0.35, y + 0.7, z + 0.35), 'bulbs',
                             uv=BULB_UV(k % 6, (k * 5 + i * 3) % 16), emit=15))
    els.append(E('tip', (7.5, 13.4, 7.5), (8.5, 14.4, 8.5), {'north': 'side', 'east': 'side', 'south': 'side',
                                                              'west': 'side', 'up': 'top'}))
    els += star_topper(cy=15.0, k=0.42)
    return els, tex


# ================================================================ ornaments (items, also drawn on the fir tree)
def bauble_item(colour):
    tex = {'glass': X + 'bauble_' + colour, 'cap': X + 'paint_gold', 'particle': X + 'bauble_' + colour}
    els = [E('a', (5, 5.5, 6), (11, 10.5, 10), 'glass'),
           E('b', (6, 5.75, 5), (10, 10.25, 11), 'glass'),
           E('c', (6.25, 4.5, 6.25), (9.75, 11.5, 9.75), 'glass'),
           E('cap', (7, 11.5, 7), (9, 12.4, 9), 'cap'),
           E('loop_a', (7.6, 12.4, 7.8), (7.9, 13.4, 8.2), 'cap'),
           E('loop_b', (8.1, 12.4, 7.8), (8.4, 13.4, 8.2), 'cap'),
           E('loop_c', (7.6, 13.4, 7.8), (8.4, 13.7, 8.2), 'cap')]
    return els, tex


def candy_cane_item():
    tex = {'stripe': X + 'candy_stripe_fine', 'particle': X + 'candy_stripe_fine'}
    els = [E('shaft', (7.25, 1.5, 7.25), (8.75, 11.0, 8.75), 'stripe'),
           E('bend', (7.25, 10.4, 7.25), (8.75, 12.6, 8.75), 'stripe', rot=('z', 45, (8, 11.5, 8))),
           E('top', (5.0, 11.2, 7.3), (8.0, 12.7, 8.7), 'stripe'),
           E('bend2', (4.4, 10.4, 7.3), (5.9, 12.6, 8.7), 'stripe', rot=('z', -45, (5.15, 11.5, 8))),
           E('end', (3.8, 8.8, 7.3), (5.3, 11.0, 8.7), 'stripe')]
    return els, tex


def gingerbread_man_item():
    tex = {'dough': X + 'gingerbread', 'icing': X + 'frosting', 'red': X + 'paint_red', 'green': X + 'paint_green',
           'particle': X + 'gingerbread'}
    els = [E('head', (6.4, 10.0, 7.2), (9.6, 13.2, 8.8), 'dough'),
           E('body', (6.0, 4.6, 7.25), (10.0, 10.2, 8.75), 'dough'),
           E('arms', (3.4, 7.6, 7.35), (12.6, 9.6, 8.65), 'dough'),
           E('leg_l', (5.6, 1.4, 7.3), (7.8, 4.8, 8.7), 'dough'),
           E('leg_r', (8.2, 1.4, 7.3), (10.4, 4.8, 8.7), 'dough'),
           E('eye_l', (6.9, 11.6, 7.0), (7.5, 12.2, 7.2), 'icing'),
           E('eye_r', (8.5, 11.6, 7.0), (9.1, 12.2, 7.2), 'icing'),
           E('smile', (7.2, 10.6, 7.0), (8.8, 10.9, 7.2), 'icing'),
           E('btn1', (7.65, 8.4, 7.0), (8.35, 9.1, 7.25), 'red'),
           E('btn2', (7.65, 6.6, 7.0), (8.35, 7.3, 7.25), 'green'),
           E('cuff_l', (3.9, 7.8, 7.1), (4.3, 9.4, 7.35), 'icing'),
           E('cuff_r', (11.7, 7.8, 7.1), (12.1, 9.4, 7.35), 'icing'),
           E('sock_l', (5.8, 2.2, 7.05), (7.6, 2.5, 7.3), 'icing'),
           E('sock_r', (8.4, 2.2, 7.05), (10.2, 2.5, 7.3), 'icing')]
    return els, tex


def star_item():
    els = star_topper(cy=8.0, k=1.65)
    return els, {'star': X + 'star_gold', 'particle': X + 'star_gold'}


def angel_item():
    els = angel_topper(b=1.0, k=1.75)
    return els, {'robe': X + 'paint_white', 'skin': X + 'paint_skin', 'hair': X + 'gold_shine',
                 'halo': X + 'star_gold', 'wing': X + 'fur_white', 'particle': X + 'paint_white'}


def tree_lights_item():
    tex = {'wire': X + 'paint_wire', 'bulbs': X + 'bulbs', 'particle': X + 'paint_wire'}
    els = []
    for j, (y, h) in enumerate(((6.0, 4.6), (7.2, 4.1), (8.4, 4.6))):
        els += star8(f'coil{j}', 8, 8, y, y + 0.5, h, 'wire', hr=h * 0.93, rot_inset=(0.05, 0.05))
    k = 0
    for j, (y, h) in enumerate(((6.0, 4.6), (7.2, 4.1), (8.4, 4.6))):
        for i in range(7):
            th = j * 0.45 + i * 2 * math.pi / 7
            r = star_radius(th, h, h * 0.93) + 0.3
            x, z = 8 + r * math.cos(th), 8 + r * math.sin(th)
            els.append(E(f'bulb{k}', (x - 0.6, y + 0.5, z - 0.6), (x + 0.6, y + 1.9, z + 0.6), 'bulbs',
                         uv=BULB_UV(k % 6, (k * 5) % 16), emit=15))
            k += 1
    return els, tex


# ================================================================ wall and table decorations
def _ring(prefix, r_in, r_out, z0, z1, tex, cx=8.0, cy=8.0, faces=('north', 'east', 'west', 'up', 'down')):
    """A square-ish ring in the x/y plane: four sides plus four sides turned 45 degrees about z."""
    w = r_out - r_in
    half = r_in * 0.62
    els = [E(prefix + '_t', (cx - half, cy + r_in, z0), (cx + half, cy + r_out, z1), tex, faces=faces),
           E(prefix + '_b', (cx - half, cy - r_out, z0), (cx + half, cy - r_in, z1), tex, faces=faces),
           E(prefix + '_l', (cx - r_out, cy - half, z0 + 0.2), (cx - r_in, cy + half, z1), tex, faces=faces),
           E(prefix + '_r', (cx + r_in, cy - half, z0 + 0.2), (cx + r_out, cy + half, z1), tex, faces=faces)]
    for i, ang in enumerate((45, -45)):
        els.append(E(f'{prefix}_d{i}t', (cx - half, cy + r_in, z0 + 0.1), (cx + half, cy + r_out, z1), tex,
                     faces=faces, rot=('z', ang, (cx, cy, z0))))
        els.append(E(f'{prefix}_d{i}b', (cx - half, cy - r_out, z0 + 0.1), (cx + half, cy - r_in, z1), tex,
                     faces=faces, rot=('z', ang, (cx, cy, z0))))
    return els


def bow(prefix, cx, cy, z, var, k=1.0, tails=True):
    """Front-facing ribbon bow centred at (cx, cy) with its front at z."""
    els = [E(prefix + '_knot', (cx - 0.9 * k, cy - 0.9 * k, z), (cx + 0.9 * k, cy + 0.9 * k, z + 1.4 * k), var)]
    loop = E(prefix + '_loop_l', (cx - 3.4 * k, cy - 1.1 * k, z + 0.3 * k), (cx - 0.7 * k, cy + 1.3 * k, z + 1.3 * k), var,
             rot=('z', -22.5, (cx - 0.7 * k, cy, z)))
    els.append(loop)
    els += mirror_x([loop], cx=cx, suffix='_r')
    if tails:
        tail = E(prefix + '_tail_l', (cx - 1.6 * k, cy - 3.6 * k, z + 0.5 * k), (cx - 0.5 * k, cy - 0.6 * k, z + 1.1 * k),
                 var, rot=('z', 22.5, (cx - 0.5 * k, cy - 0.6 * k, z)))
        els.append(tail)
        els += mirror_x([tail], cx=cx, suffix='_r')
    return els


def wreath(lit=False):
    tex = {'needles': X + 'fir_needles', 'tuft': X + 'fir_needles_alt', 'holly': X + 'holly', 'berry': X + 'baubles',
           'ribbon': X + 'ribbon_red', 'cone': X + 'paint_brown', 'particle': X + 'fir_needles'}
    els = _ring('ring', 3.0, 7.0, 13.0, 16.0, 'needles')
    for i in range(8):
        a = i * 45 + 22.5
        r = 5.0
        x, y = 8 + r * math.cos(math.radians(a)), 8 + r * math.sin(math.radians(a))
        els.append(E(f'tuft{i}', (x - 1.3, y - 1.3, 12.2 + 0.05 * (i % 3)), (x + 1.3, y + 1.3, 13.4), 'tuft',
                     faces=('north', 'east', 'west', 'up', 'down'),
                     rot=('z', 22.5 if i % 2 else -22.5, (x, y, 12.2))))
    # holly leaf pairs and berry clusters
    for i, a in enumerate((70, 130, 200, 340)):
        x, y = 8 + 5.0 * math.cos(math.radians(a)), 8 + 5.0 * math.sin(math.radians(a))
        els.append(E(f'holly{i}a', (x - 2.2, y - 0.6, 11.7), (x, y + 0.6, 12.2), 'holly', rot=('z', 22.5, (x, y, 11.7))))
        els.append(E(f'holly{i}b', (x, y - 0.6, 11.75), (x + 2.2, y + 0.6, 12.25), 'holly', rot=('z', -22.5, (x, y, 11.75))))
        for j, (dx, dy) in enumerate(((-0.5, 0.5), (0.5, 0.6), (0.0, -0.3))):
            els.append(E(f'berry{i}_{j}', (x + dx - 0.5, y + dy - 0.5, 11.0 + 0.1 * j), (x + dx + 0.5, y + dy + 0.5, 12.0 + 0.1 * j),
                         'berry', uv=[1, 0, 2, 1]))
    for i, a in enumerate((20, 160)):
        x, y = 8 + 5.0 * math.cos(math.radians(a)), 8 + 5.0 * math.sin(math.radians(a))
        els.append(E(f'cone{i}', (x - 0.8, y - 1.1, 11.6), (x + 0.8, y + 1.1, 13.0), 'cone'))
    els += bow('bow', 8, 2.4, 11.3, 'ribbon')
    if lit:
        tex['bulbs'] = X + 'bulbs'
        for i in range(12):
            a = i * 30 + 8
            x, y = 8 + 6.4 * math.cos(math.radians(a)), 8 + 6.4 * math.sin(math.radians(a))
            if 245 < a < 295:
                continue
            els.append(E(f'light{i}', (x - 0.5, y - 0.5, 12.4), (x + 0.5, y + 0.5, 13.3), 'bulbs',
                         uv=BULB_UV([6, 1, 0, 2][i % 4], (i * 5) % 16), emit=15))
    return els, tex


def mantel_garland():
    tex = {'needles': X + 'fir_needles', 'tuft': X + 'fir_needles_alt', 'ribbon': X + 'ribbon_red',
           'cone': 'block/spruce_log', 'bulbs': X + 'bulbs', 'berry': X + 'baubles', 'particle': X + 'fir_needles'}
    els = []
    rope = [(-0.8, 4.6, 2.0, 6.6, 2.6), (3.6, 9.0, 2.3, 6.9, 3.0), (8.0, 12.6, 2.1, 6.5, 2.7), (11.6, 16.8, 2.4, 6.8, 2.9)]
    for i, (x0, x1, z0, z1, h) in enumerate(rope):
        els.append(E(f'rope{i}', (x0, 0, z0), (x1, h, z1), 'needles', faces=('north', 'east', 'west', 'up', 'south')))
    for i in range(5):
        x = 0.5 + i * 3.75
        els.append(E(f'lip{i}', (x - 2.0, -2.0, -0.6 + 0.1 * (i % 2)), (x + 2.0, 0.9 + 0.2 * (i % 2), 2.8), 'tuft',
                     faces=('north', 'east', 'west', 'up'), rot=('y', 22.5 if i % 2 else -22.5, (x, 0, 1.0))))
    # two swags hanging over the front edge
    k = 0
    for s, (xa, xb) in enumerate(((0.5, 8.0), (8.0, 15.5))):
        for j in range(6):
            f = (j + 0.5) / 6
            x = xa + (xb - xa) * f
            y = -2.6 - 3.0 * math.sin(math.pi * f) + 0.04 * j + 0.13 * s
            els.append(E(f'swag{s}_{j}', (x - 0.95, y - 0.95, -1.9 + 0.05 * (j % 2)), (x + 0.95, y + 0.95, -0.15 - 0.07 * (j % 2)),
                         'needles' if j % 2 else 'tuft'))
            if j in (1, 3, 5):
                els.append(E(f'light{k}', (x - 0.45, y - 1.6, -2.3), (x + 0.45, y - 0.7, -1.4), 'bulbs',
                             uv=BULB_UV([6, 1, 6, 2][k % 4], (k * 7) % 16), emit=15))
                k += 1
    for i, x in enumerate((0.5, 8.0, 15.5)):
        els += bow(f'bow{i}', x, -1.6, -2.7, 'ribbon', k=0.6, tails=i == 1)
    for i, (x, z) in enumerate(((4.2, 3.2), (12.0, 3.6))):
        els.append(E(f'cone{i}', (x - 0.8, 2.6, z - 0.8), (x + 0.8, 4.2, z + 0.8), 'cone'))
        for j, (dx, dz) in enumerate(((1.3, -0.4), (1.9, 0.5))):
            els.append(E(f'berry{i}_{j}', (x + dx - 0.55, 2.9 + 0.1 * j, z + dz - 0.55), (x + dx + 0.45, 3.9 + 0.1 * j, z + dz + 0.45),
                         'berry', uv=[1, 0, 2, 1]))
    return els, tex


def stocking(body, cuff):
    tex = {'body': body, 'cuff': cuff, 'hook': X + 'paint_gold', 'cane': X + 'candy_stripe_fine', 'particle': body}
    nf = ('north', 'east', 'west', 'up', 'down')
    els = [E('hook', (7.4, 14.8, 14.8), (8.6, 16.0, 16.0), 'hook', faces=nf),
           E('cuff', (4.8, 11.6, 12.8), (10.8, 14.8, 16.0), 'cuff', faces=nf),
           E('leg', (5.4, 5.2, 13.3), (10.2, 11.6, 16.0), 'body', faces=nf),
           E('foot', (5.4, 1.4, 13.3), (12.0, 5.2, 15.9), 'body', faces=nf),
           E('heel', (4.9, 1.8, 13.5), (5.4, 4.6, 15.7), 'cuff', faces=('north', 'west', 'up', 'down')),
           E('toe', (12.0, 1.4, 13.1), (14.2, 4.8, 16.0), 'cuff', faces=nf),
           E('sole', (5.7, 1.0, 13.4), (12.0, 1.4, 15.8), 'body', faces=('north', 'down', 'east', 'west')),
           E('cane', (8.6, 13.4, 13.9), (9.6, 17.2, 14.9), 'cane'),
           E('cane_top', (9.6, 16.2, 13.9), (11.4, 17.2, 14.9), 'cane'),
           E('cane_end', (10.4, 15.2, 13.9), (11.4, 16.2, 14.9), 'cane', faces=('north', 'east', 'south', 'west', 'down'))]
    return els, tex


def _present_box(open_):
    nf = ('north', 'east', 'south', 'west', 'up')
    if not open_:
        els = [E('box', (3, 0, 3), (13, 7.6, 13), 'paper', faces=('north', 'east', 'south', 'west')),
               E('lid', (2.6, 7.0, 2.6), (13.4, 9.5, 13.4), 'paper')]
        for i, (f, t, faces) in enumerate((((7, 0, 2.8), (9, 7.0, 3.0), ('north', 'east', 'west')),
                                           ((7, 0, 13.0), (9, 7.0, 13.2), ('south', 'east', 'west')),
                                           ((2.8, 0, 7), (3.0, 7.0, 9), ('west', 'north', 'south')),
                                           ((13.0, 0, 7), (13.2, 7.0, 9), ('east', 'north', 'south')),
                                           ((7, 7.0, 2.4), (9, 9.5, 2.6), ('north', 'east', 'west', 'down')),
                                           ((7, 7.0, 13.4), (9, 9.5, 13.6), ('south', 'east', 'west', 'down')),
                                           ((2.4, 7.0, 7), (2.6, 9.5, 9), ('west', 'north', 'south', 'down')),
                                           ((13.4, 7.0, 7), (13.6, 9.5, 9), ('east', 'north', 'south', 'down')))):
            els.append(E(f'band{i}', f, t, 'ribbon', faces=faces))
        els.append(E('top_x', (2.4, 9.5, 7), (13.6, 9.7, 9), 'ribbon', faces=('up', 'north', 'south')))
        els.append(E('top_z', (7, 9.5, 2.4), (9, 9.65, 13.6), 'ribbon', faces=('up', 'east', 'west')))
        loop = E('loop_l', (4.4, 9.7, 7.2), (8.0, 12.0, 8.8), 'ribbon', rot=('z', -22.5, (8.0, 9.7, 8)))
        els += [loop] + mirror_x([loop], suffix='_r')
        loop2 = E('loop_f', (7.2, 9.75, 4.4), (8.8, 11.6, 8.0), 'ribbon', rot=('x', 22.5, (8, 9.75, 8.0)))
        els.append(loop2)
        els.append(E('knot', (7.1, 9.7, 7.1), (8.9, 11.2, 8.9), 'ribbon'))
        tail = E('tail_l', (6.3, 9.72, 2.2), (7.5, 9.9, 7.2), 'ribbon', rot=('y', -22.5, (7.5, 9.72, 7.2)))
        els += [tail] + mirror_x([tail], suffix='_r')
        els.append(E('tag', (9.8, 4.4, 2.75), (12.4, 6.8, 2.85), 'tag', faces=('north', 'south')))
        els.append(E('tag_string', (10.9, 6.8, 2.7), (11.3, 7.2, 2.9), 'ribbon', faces=('north', 'east', 'west')))
    else:
        els = [E('wall_n', (3, 0, 3), (13, 7.6, 3.8), 'paper'),
               E('wall_s', (3, 0, 12.2), (13, 7.6, 13), 'paper'),
               E('wall_w', (3, 0, 3.8), (3.8, 7.6, 12.2), 'paper', faces=('west', 'east', 'up')),
               E('wall_e', (12.2, 0, 3.8), (13, 7.6, 12.2), 'paper', faces=('west', 'east', 'up')),
               E('tissue', (3.8, 5.6, 3.8), (12.2, 8.1, 12.2), 'tissue', faces=('up',))]
        for i, (x, z, a) in enumerate(((6.0, 6.5, 22.5), (10.0, 7.5, -22.5), (7.5, 10.2, 45))):
            els.append(E(f'puff{i}', (x - 1.6, 7.2, z - 0.25), (x + 1.6, 10.4 - 0.4 * i, z + 0.25), 'tissue',
                         rot=('y', a, (x, 0, z))))
        lid = E('lid', (2.6, 7.6, 13.0), (13.4, 18.4, 15.5), 'paper', rot=('x', 22.5, (8, 7.6, 13.0)))
        els.append(lid)
        els.append(E('lid_band', (7, 7.6, 15.5), (9, 18.4, 15.7), 'ribbon', faces=('south', 'east', 'west', 'up'),
                     rot=('x', 22.5, (8, 7.6, 13.0))))
    return els


def present(open_, paper, ribbon):
    tex = {'paper': paper, 'ribbon': ribbon, 'tissue': X + 'tissue', 'tag': X + 'gift_tag', 'particle': paper}
    return _present_box(open_), tex


def present_pile():
    tex = {'p1': X + 'wrap_red', 'r1': X + 'ribbon_gold', 'p2': X + 'wrap_green', 'r2': X + 'ribbon_red',
           'p3': X + 'wrap_blue', 'r3': X + 'ribbon_silver', 'particle': X + 'wrap_red'}
    els = mini_present('big', 1.0, 2.5, 9.0, 9.0, 6.5, 'p1', 'r1')
    els += mini_present('small', 3.0, 4.5, 5.0, 5.0, 3.6, 'p3', 'r3', y0=6.65)
    els += mini_present('tall', 10.6, 6.0, 4.6, 4.6, 9.6, 'p2', 'r2')
    for e in els:
        if e['name'].endswith('_bow'):
            e['from'][0] -= 0.4
            e['to'][0] += 0.4
            e['to'][1] += 0.4
    return els, tex


def giant_candy_cane():
    tex = {'stripe': X + 'candy_cane_block', 'ribbon': X + 'ribbon_green', 'particle': X + 'candy_cane_block'}
    els = [E('shaft', (6.5, 0, 6.5), (9.5, 21.0, 9.5), 'stripe', faces=('north', 'east', 'south', 'west', 'down')),
           E('corner_r', (6.5, 19.6, 6.6), (9.5, 22.6, 9.4), 'stripe', rot=('z', 45, (8, 21.1, 8))),
           E('top', (3.0, 21.4, 6.55), (8.0, 24.4, 9.45), 'stripe', faces=('north', 'south', 'up', 'down')),
           E('corner_l', (1.5, 19.6, 6.6), (4.5, 22.6, 9.4), 'stripe', rot=('z', -45, (3.0, 21.1, 8))),
           E('end', (1.5, 16.0, 6.5), (4.5, 21.0, 9.5), 'stripe', faces=('north', 'east', 'south', 'west', 'down'))]
    els += bow('bow', 8.0, 17.5, 5.2, 'ribbon', k=0.85)
    return els, tex


def snow_globe():
    tex = {'base': 'block/dark_oak_planks', 'trim': X + 'paint_gold', 'snow': X + 'snow_ground', 'glass': X + 'globe_glass',
           'house': X + 'paint_red', 'roof': X + 'snow_ground', 'door': X + 'paint_brown', 'window': X + 'lantern_glow',
           'side': X + 'fir_needles', 'top': X + 'fir_needles', 'under': X + 'fir_needles', 'particle': X + 'globe_glass'}
    els = [E('base', (3.6, 0, 3.6), (12.4, 2.0, 12.4), 'base'),
           E('trim', (3.4, 0.5, 3.4), (12.6, 1.2, 12.6), 'trim', faces=SIDES),
           E('plinth', (4.2, 2.0, 4.2), (11.8, 2.6, 11.8), 'snow', faces=('up',)),
           E('house', (5.2, 2.6, 6.6), (8.0, 4.6, 9.2), 'house', faces=('north', 'east', 'south', 'west')),
           E('roof', (4.9, 4.6, 6.3), (8.3, 5.4, 9.5), 'roof'),
           E('roof2', (5.6, 5.4, 6.8), (7.6, 6.0, 9.0), 'roof'),
           E('door', (6.2, 2.6, 6.45), (7.0, 3.9, 6.6), 'door', faces=('north',)),
           E('window', (7.25, 3.4, 6.45), (7.75, 3.9, 6.6), 'window', faces=('north',), emit=10),
           E('chimney', (7.2, 5.2, 8.0), (7.8, 6.6, 8.6), 'house')]
    els += star8('tree_lo', 9.8, 9.0, 2.6, 4.4, 1.1, {'north': 'side', 'east': 'side', 'south': 'side', 'west': 'side', 'up': 'top'},
                 faces=('north', 'east', 'south', 'west', 'up'), rot_inset=(0.1, 0.15))
    els += star8('tree_hi', 9.8, 9.0, 4.4, 6.0, 0.7, {'north': 'side', 'east': 'side', 'south': 'side', 'west': 'side', 'up': 'top'},
                 faces=('north', 'east', 'south', 'west', 'up'), rot_inset=(0.0, 0.15))
    els += [E('glass', (4.1, 2.0, 4.1), (11.9, 9.3, 11.9), 'glass', faces=('north', 'east', 'south', 'west', 'up')),
            E('glass_cap', (5.0, 9.3, 5.0), (11.0, 10.3, 11.0), 'glass', faces=('north', 'east', 'south', 'west', 'up'))]
    return els, tex


def nutcracker(coat):
    tex = {'coat': X + 'paint_' + coat, 'trim': X + 'paint_gold', 'white': X + 'paint_white', 'black': X + 'paint_black',
           'skin': X + 'paint_skin', 'beard': X + 'fur_white', 'plume': X + 'paint_red' if coat != 'red' else X + 'paint_white',
           'base': 'block/spruce_planks', 'cheek': X + 'paint_pink', 'particle': X + 'paint_' + coat}
    els = [E('base', (4.5, 0, 5.0), (11.5, 1.0, 11.0), 'base'),
           E('boot_l', (5.2, 1.0, 6.4), (7.6, 4.0, 9.6), 'black'),
           E('boot_r', (8.4, 1.0, 6.4), (10.8, 4.0, 9.6), 'black'),
           E('leg_l', (5.5, 4.0, 6.8), (7.5, 8.6, 9.2), 'white'),
           E('leg_r', (8.5, 4.0, 6.8), (10.5, 8.6, 9.2), 'white'),
           E('coat', (5.0, 8.6, 6.3), (11.0, 15.4, 9.7), 'coat'),
           E('coat_tail', (5.2, 7.6, 7.8), (10.8, 8.6, 9.8), 'coat', faces=('east', 'south', 'west', 'down')),
           E('belt', (4.8, 10.2, 6.1), (11.2, 11.2, 9.9), 'black', faces=SIDES),
           E('buckle', (7.3, 10.0, 5.9), (8.7, 11.4, 6.1), 'trim', faces=('north', 'east', 'west', 'up', 'down')),
           E('sash_a', (7.6, 11.2, 6.15), (8.4, 15.2, 6.3), 'trim', faces=('north',)),
           E('epaulet_l', (3.8, 14.6, 6.8), (5.4, 15.6, 9.2), 'trim'),
           E('epaulet_r', (10.6, 14.6, 6.8), (12.2, 15.6, 9.2), 'trim'),
           E('arm_l', (3.9, 9.4, 7.0), (5.0, 14.6, 9.0), 'coat'),
           E('arm_r', (11.0, 9.4, 7.0), (12.1, 14.6, 9.0), 'coat'),
           E('glove_l', (3.9, 8.4, 7.1), (5.0, 9.4, 8.9), 'white'),
           E('glove_r', (11.0, 8.4, 7.1), (12.1, 9.4, 8.9), 'white'),
           E('head', (5.6, 15.4, 6.0), (10.4, 20.0, 10.0), 'skin'),
           E('jaw', (5.8, 14.6, 5.6), (10.2, 16.6, 8.6), 'beard'),
           E('teeth', (6.6, 16.6, 5.75), (9.4, 17.1, 5.95), 'white', faces=('north',)),
           E('nose', (7.5, 17.4, 5.5), (8.5, 18.6, 6.0), 'skin', faces=('north', 'east', 'west', 'up', 'down')),
           E('eye_l', (6.4, 18.6, 5.85), (7.3, 19.4, 6.0), 'black', faces=('north',)),
           E('eye_r', (8.7, 18.6, 5.85), (9.6, 19.4, 6.0), 'black', faces=('north',)),
           E('cheek_l', (6.0, 17.3, 5.9), (6.8, 17.9, 6.0), 'cheek', faces=('north',)),
           E('cheek_r', (9.2, 17.3, 5.9), (10.0, 17.9, 6.0), 'cheek', faces=('north',)),
           E('hair_l', (5.2, 15.4, 7.0), (5.6, 19.6, 10.2), 'beard', faces=('west', 'north', 'south', 'down')),
           E('hair_r', (10.4, 15.4, 7.0), (10.8, 19.6, 10.2), 'beard', faces=('east', 'north', 'south', 'down')),
           E('hair_b', (5.6, 15.4, 10.0), (10.4, 19.6, 10.4), 'beard', faces=('south', 'down')),
           E('hat', (5.4, 20.0, 5.8), (10.6, 25.0, 10.2), 'black'),
           E('hat_band', (5.2, 20.0, 5.6), (10.8, 20.9, 10.4), 'trim', faces=SIDES),
           E('badge', (7.4, 22.0, 5.6), (8.6, 23.4, 5.8), 'trim', faces=('north', 'east', 'west', 'up', 'down')),
           E('plume', (7.5, 25.0, 7.4), (8.5, 27.6, 8.6), 'plume')]
    return els, tex


def nutcracker_open(coat):
    els, tex = nutcracker(coat)
    for e in els:
        if e['name'] in ('jaw',):
            e['from'][1] -= 1.6
            e['to'][1] -= 1.6
            e['from'][2] -= 0.4
            e['to'][2] -= 0.4
    els = [e for e in els if e['name'] != 'teeth']
    els.append(E('mouth', (6.6, 15.0, 5.85), (9.4, 16.6, 6.0), 'black', faces=('north',)))
    els.append(E('teeth_t', (6.6, 16.1, 5.75), (9.4, 16.6, 5.85), 'white', faces=('north',)))
    from geom import reuv
    return reuv(els), tex


def christmas_candle():
    tex = {'wax': X + 'wax_red', 'dish': X + 'paint_gold', 'wick': X + 'paint_black', 'flame': X + 'candle_flame',
           'holly': X + 'holly', 'berry': X + 'baubles', 'particle': X + 'wax_red'}
    els = [E('dish', (4.4, 0, 4.4), (11.6, 0.8, 11.6), 'dish'),
           E('rim', (4.0, 0.2, 4.0), (12.0, 1.3, 12.0), 'dish', faces=SIDES + ('up',)),
           E('candle', (6.0, 0.8, 6.0), (10.0, 10.0, 10.0), 'wax'),
           E('drip', (5.85, 7.6, 7.2), (6.0, 10.0, 8.4), 'wax', faces=('west', 'down')),
           E('drip2', (8.6, 8.4, 5.85), (9.4, 10.0, 6.0), 'wax', faces=('north', 'down')),
           E('wick', (7.75, 10.0, 7.75), (8.25, 10.9, 8.25), 'wick'),
           E('flame_a', (6.9, 10.4, 8.0), (9.1, 13.4, 8.0), 'flame', faces=('north', 'south'), uv=[0, 0, 16, 16],
             rot=('y', 45, (8, 10.4, 8)), emit=15),
           E('flame_b', (8.0, 10.4, 6.9), (8.0, 13.4, 9.1), 'flame', faces=('east', 'west'), uv=[0, 0, 16, 16],
             rot=('y', 45, (8, 10.4, 8)), emit=15)]
    for i in range(6):
        a = i * 60 + 15
        x, z = 8 + 3.6 * math.cos(math.radians(a)), 8 + 3.6 * math.sin(math.radians(a))
        els.append(E(f'holly{i}', (x - 1.4, 1.3 + 0.05 * i, z - 0.55), (x + 1.4, 1.8 + 0.05 * i, z + 0.55), 'holly',
                     rot=('y', 45 if i % 2 else -45, (x, 0, z))))
    for i, a in enumerate((50, 170, 290)):
        x, z = 8 + 2.9 * math.cos(math.radians(a)), 8 + 2.9 * math.sin(math.radians(a))
        for j, (dx, dz) in enumerate(((0, 0), (0.7, 0.4), (-0.2, 0.8))):
            els.append(E(f'berry{i}_{j}', (x + dx - 0.45, 1.6 + 0.05 * j, z + dz - 0.45), (x + dx + 0.45, 2.5 + 0.05 * j, z + dz + 0.45),
                         'berry', uv=[1, 0, 2, 1]))
    return els, tex


ADVENT_ORDER = [(8.0, 2.6, 'purple'), (13.4, 8.0, 'purple'), (8.0, 13.4, 'pink'), (2.6, 8.0, 'purple')]


def advent_wreath(lit_count):
    tex = {'needles': X + 'fir_needles', 'tuft': X + 'fir_needles_alt', 'holly': X + 'holly', 'berry': X + 'baubles',
           'purple': X + 'wax_purple', 'pink': X + 'wax_pink', 'wick': X + 'paint_black', 'flame': X + 'candle_flame',
           'cone': 'block/spruce_log', 'particle': X + 'fir_needles'}
    els = []
    ring = [((1.2, 0, 1.2), (14.8, 2.4, 4.0)), ((1.2, 0, 12.0), (14.8, 2.4, 14.8)),
            ((1.2, 0, 4.0), (4.0, 2.2, 12.0)), ((12.0, 0, 4.0), (14.8, 2.2, 12.0))]
    for i, (f, t) in enumerate(ring):
        els.append(E(f'ring{i}', f, t, 'needles', faces=('north', 'east', 'south', 'west', 'up')))
    for i in range(8):
        a = i * 45 + 22.5
        x, z = 8 + 5.6 * math.cos(math.radians(a)), 8 + 5.6 * math.sin(math.radians(a))
        els.append(E(f'tuft{i}', (x - 1.5, 0.2, z - 1.5), (x + 1.5, 2.9 + 0.1 * (i % 3), z + 1.5), 'tuft',
                     faces=('north', 'east', 'south', 'west', 'up'), rot=('y', 22.5 if i % 2 else -22.5, (x, 0, z))))
    for i, a in enumerate((0, 90, 180, 270)):
        a += 45
        x, z = 8 + 5.6 * math.cos(math.radians(a)), 8 + 5.6 * math.sin(math.radians(a))
        els.append(E(f'holly{i}', (x - 1.8, 2.8, z - 0.6), (x + 1.8, 3.3, z + 0.6), 'holly', rot=('y', 22.5, (x, 0, z))))
        for j, (dx, dz) in enumerate(((0.3, 0.9), (-0.5, 1.1))):
            els.append(E(f'berry{i}_{j}', (x + dx - 0.5, 3.0 + 0.1 * j, z + dz - 0.5), (x + dx + 0.5, 4.0 + 0.1 * j, z + dz + 0.5),
                         'berry', uv=[1, 0, 2, 1]))
    for i, (x, z, col) in enumerate(ADVENT_ORDER):
        els.append(E(f'candle{i}', (x - 1.2, 2.0, z - 1.2), (x + 1.2, 9.0 - 0.4 * (i % 2), z + 1.2), col))
        top = 9.0 - 0.4 * (i % 2)
        els.append(E(f'wick{i}', (x - 0.2, top, z - 0.2), (x + 0.2, top + 0.7, z + 0.2), 'wick'))
        if i < lit_count:
            els.append(E(f'flame{i}a', (x - 0.9, top + 0.3, z), (x + 0.9, top + 2.7, z), 'flame', faces=('north', 'south'),
                         uv=[0, 0, 16, 16], rot=('y', 45, (x, top, z)), emit=15))
            els.append(E(f'flame{i}b', (x, top + 0.3, z - 0.9), (x, top + 2.7, z + 0.9), 'flame', faces=('east', 'west'),
                         uv=[0, 0, 16, 16], rot=('y', 45, (x, top, z)), emit=15))
    return els, tex


def _ball(prefix, cx, b, cz, r, tex):
    return [E(prefix + '_a', (cx - r, b + 1, cz - r + 1), (cx + r, b + 2 * r - 1, cz + r - 1), tex),
            E(prefix + '_b', (cx - r + 1, b + 1.1, cz - r), (cx + r - 1, b + 2 * r - 1.1, cz + r), tex),
            E(prefix + '_c', (cx - r + 1.2, b, cz - r + 1.2), (cx + r - 1.2, b + 2 * r, cz + r - 1.2), tex,
              faces=('up', 'down'))]


def snowman(scarf):
    tex = {'snow': 'block/snow', 'coal': X + 'paint_black', 'carrot': X + 'paint_orange', 'scarf': scarf,
           'stick': 'block/spruce_log', 'band': X + 'paint_red' if 'red' not in scarf else X + 'paint_green',
           'particle': 'block/snow'}
    els = _ball('bottom', 8, 0, 8, 5.0, 'snow') + _ball('middle', 8, 8.8, 8, 3.8, 'snow') + _ball('head', 8, 15.6, 8, 3.0, 'snow')
    els += [E('eye_l', (6.4, 19.2, 4.8), (7.4, 20.2, 5.1), 'coal', faces=('north', 'east', 'west', 'up', 'down')),
            E('eye_r', (8.6, 19.2, 4.8), (9.6, 20.2, 5.1), 'coal', faces=('north', 'east', 'west', 'up', 'down')),
            E('nose', (7.5, 17.9, 2.4), (8.5, 18.9, 5.0), 'carrot', faces=('north', 'east', 'west', 'up', 'down'))]
    for i, (x, y) in enumerate(((6.2, 17.4), (7.1, 16.9), (8.9, 16.9), (9.8, 17.4))):
        els.append(E(f'mouth{i}', (x - 0.4, y - 0.4, 4.8), (x + 0.4, y + 0.4, 5.05), 'coal',
                     faces=('north', 'east', 'west', 'up', 'down')))
    for i, y in enumerate((10.4, 12.4, 14.2)):
        els.append(E(f'button{i}', (7.5, y, 4.0), (8.5, y + 1.0, 4.3), 'coal', faces=('north', 'east', 'west', 'up', 'down')))
    els += [E('scarf', (4.5, 15.4, 4.5), (11.5, 17.0, 11.5), 'scarf'),
            E('scarf_tail', (9.0, 11.2, 4.0), (10.8, 15.6, 4.6), 'scarf', faces=('north', 'east', 'west', 'down'),
              rot=('z', -22.5, (9.9, 15.6, 4.3))),
            E('brim', (4.4, 21.4, 4.4), (11.6, 22.0, 11.6), 'coal'),
            E('crown', (5.4, 22.0, 5.4), (10.6, 26.2, 10.6), 'coal'),
            E('band', (5.3, 22.0, 5.3), (10.7, 22.9, 10.7), 'band', faces=SIDES)]
    arm = E('arm_l', (-1.8, 12.9, 7.6), (4.4, 13.7, 8.4), 'stick', rot=('z', -22.5, (4.4, 13.3, 8)))
    twig = E('twig_l', (-0.6, 13.6, 7.7), (0.1, 15.8, 8.3), 'stick', rot=('z', 22.5, (0.0, 13.6, 8)))
    els += [arm, twig] + mirror_x([arm, twig], suffix='_r')
    return els, tex


def mistletoe():
    tex = {'leaf': X + 'mistletoe_leaf', 'stem': X + 'paint_green', 'berry': X + 'paint_white', 'ribbon': X + 'ribbon_red',
           'particle': X + 'mistletoe_leaf'}
    els = [E('strip', (7.6, 12.6, 7.6), (8.4, 16.0, 8.4), 'ribbon')]
    els += bow('bow', 8.0, 12.6, 7.0, 'ribbon', k=0.7)
    els.append(E('stem', (7.7, 7.2, 7.7), (8.3, 12.0, 8.3), 'stem'))
    leaves = [('z', 45, (8, 9.6, 8), (4.6, 9.2, 7.6), (8.0, 10.6, 8.2)),
              ('z', -45, (8, 9.6, 8), (8.0, 9.2, 7.8), (11.4, 10.6, 8.4)),
              ('x', 45, (8, 9.0, 8), (7.5, 8.6, 4.6), (8.3, 10.0, 8.0)),
              ('x', -45, (8, 9.0, 8), (7.7, 8.6, 8.0), (8.5, 10.0, 11.4)),
              ('z', 22.5, (8, 7.6, 8), (5.0, 7.0, 7.9), (8.0, 8.2, 8.5)),
              ('z', -22.5, (8, 7.6, 8), (8.0, 7.0, 7.5), (11.0, 8.2, 8.1))]
    for i, (ax, ang, o, f, t) in enumerate(leaves):
        els.append(E(f'leaf{i}', f, t, 'leaf', rot=(ax, ang, o)))
    for i, (x, y, z) in enumerate(((7.4, 6.6, 7.6), (8.4, 6.9, 8.2), (7.9, 6.2, 8.7), (8.7, 7.6, 7.3), (7.2, 7.7, 8.6))):
        els.append(E(f'berry{i}', (x - 0.45, y - 0.45, z - 0.45), (x + 0.45, y + 0.45, z + 0.45), 'berry'))
    return els, tex


def christmas_lantern():
    tex = {'frame': X + 'paint_red', 'trim': X + 'paint_gold', 'glow': X + 'lantern_glow', 'handle': X + 'paint_black',
           'holly': X + 'holly', 'berry': X + 'baubles', 'particle': X + 'paint_red'}
    els = [E('base', (4.0, 0, 4.0), (12.0, 1.2, 12.0), 'frame'),
           E('base_trim', (3.8, 0.3, 3.8), (12.2, 0.9, 12.2), 'trim', faces=SIDES),
           E('glass', (5.0, 1.2, 5.0), (11.0, 9.4, 11.0), 'glow', faces=SIDES, emit=15)]
    for i, (x, z) in enumerate(((4.4, 4.4), (10.6, 4.4), (4.4, 10.6), (10.6, 10.6))):
        els.append(E(f'post{i}', (x, 1.2, z), (x + 1.0, 9.4, z + 1.0), 'frame'))
    els += [E('bar_n', (5.4, 4.9, 4.6), (10.6, 5.5, 5.0), 'frame', faces=('north', 'up', 'down')),
            E('bar_s', (5.4, 4.9, 11.0), (10.6, 5.5, 11.4), 'frame', faces=('south', 'up', 'down')),
            E('bar_w', (4.6, 4.9, 5.4), (5.0, 5.5, 10.6), 'frame', faces=('west', 'up', 'down')),
            E('bar_e', (11.0, 4.9, 5.4), (11.4, 5.5, 10.6), 'frame', faces=('east', 'up', 'down')),
            E('roof', (3.6, 9.4, 3.6), (12.4, 10.6, 12.4), 'frame'),
            E('roof_trim', (3.4, 9.7, 3.4), (12.6, 10.2, 12.6), 'trim', faces=SIDES),
            E('roof2', (5.0, 10.6, 5.0), (11.0, 11.6, 11.0), 'frame'),
            E('finial', (7.2, 11.6, 7.2), (8.8, 12.6, 8.8), 'trim'),
            E('handle_l', (5.6, 11.6, 7.6), (6.2, 14.6, 8.4), 'handle'),
            E('handle_r', (9.8, 11.6, 7.6), (10.4, 14.6, 8.4), 'handle'),
            E('handle_t', (5.6, 14.6, 7.6), (10.4, 15.2, 8.4), 'handle')]
    els.append(E('holly_a', (3.6, 10.7, 3.4), (6.6, 11.2, 4.6), 'holly', rot=('y', 22.5, (5.1, 0, 4.0))))
    els.append(E('holly_b', (5.4, 10.7, 3.0), (8.0, 11.25, 4.2), 'holly', rot=('y', -22.5, (6.7, 0, 3.6))))
    for j, (x, z) in enumerate(((5.6, 3.6), (6.4, 3.2), (6.1, 4.3))):
        els.append(E(f'berry{j}', (x - 0.45, 11.0 + 0.05 * j, z - 0.45), (x + 0.45, 11.9 + 0.05 * j, z + 0.45), 'berry',
                     uv=[0, 0, 1, 1]))
    return els, tex


def santa_hat():
    tex = {'felt': X + 'felt_red', 'fur': X + 'fur_white', 'particle': X + 'felt_red'}
    els = [E('band', (3.0, 5.6, 3.0), (13.0, 8.6, 13.0), 'fur'),
           E('crown1', (3.6, 8.6, 3.6), (12.4, 11.2, 12.4), 'felt'),
           E('crown2', (4.6, 11.2, 4.8), (11.2, 13.2, 11.4), 'felt'),
           E('flop1', (5.6, 13.0, 6.0), (10.4, 15.0, 10.6), 'felt', rot=('z', -22.5, (8.0, 13.0, 8.3))),
           E('flop2', (9.0, 12.2, 6.8), (12.6, 14.2, 9.8), 'felt', rot=('z', -45, (9.6, 13.6, 8.3))),
           E('pompom', (11.6, 9.6, 6.9), (14.4, 12.4, 9.7), 'fur')]
    return els, tex
