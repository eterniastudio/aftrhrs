"""Salt-water species (reefs, coasts, open and deep ocean): one builder per animal, drawn from the real one."""
import math

import ik
from ik import hx, mix, shade
from kit import BLACK, WHITE, C, alpha, eye, fin, finpal, pectorals, tail, eel, fish_disp, crab_disp, display, GEN, pose
from shapes import (Fish, bands_pattern, spots_pattern, combine, stripe, shark, flatfish, flat_disp, upright_disp,
                    top_disp)
from species_fresh import P

REG = []


def reg(sid, name, lore, disp=None):
    def deco(fn):
        REG.append((sid, name, lore, fn, disp or fish_disp()))
        return fn
    return deco


@reg('fish_sea_butterfly', 'Sea Butterfly', 'A snail that swims with two little wings.', upright_disp(gui_scale=1.0))
def b_sea_butterfly(it):
    shell = alpha('#c8b8e8', 200)

    def sm(face, x, y, z, f):
        r = math.hypot(x - 8, y - 7)
        return alpha('#8a70c0', 230) if int(r * 2.2) % 2 == 0 else shell
    it.wbox((6.0, 4.6, 6.6), (10.0, 8.6, 9.4), sm, density=2, name='shell')
    it.wbox((6.8, 8.6, 7.0), (9.2, 10.0, 9.0), sm, density=2, name='spire')
    it.wbox((7.4, 10.0, 7.5), (8.6, 10.8, 8.5), sm, density=2, name='tip')
    body = lambda face, x, y, z, f: alpha('#f0d8a8', 220)
    it.wbox((7.0, 8.0, 7.4), (9.0, 9.4, 8.6), body, name='body')
    wing = lambda put, w, h, f: [put(i, j, alpha('#e8d8ff', 150 if (i + j) % 3 else 210))
                                 for j in range(h) for i in range(w) if (i - w / 2) ** 2 / (w * w / 4) + (j - h / 2) ** 2 / (h * h / 4) <= 1.05]
    for sgn in (1, -1):
        x0 = 8.6 if sgn > 0 else 1.4
        it.card((x0, 7.6, 8.0), (x0 + 6.0, 13.0, 8.0), wing, rot={'origin': (8.0 + sgn * 0.6, 8.6, 8.0), 'z': sgn * 22},
                shade=False, name='wing')


@reg('fish_sea_horse', 'Sea Horse', 'The father carries the eggs in his pouch.', upright_disp(gui_scale=0.9))
def b_sea_horse(it):
    body, light, dark = hx('#e0a028'), hx('#f4c860'), hx('#9a6a14')

    def m(face, x, y, z, f):
        if face == 'up' or (face in ('north', 'south') and int(math.floor(y * 1.5)) % 2 == 0):
            return light if face != 'down' else dark
        return body if face != 'down' else dark
    # stacked body rings leaning forward, belly bulging, then the curled tail
    rings = [(7.0, 8.6, 3.2, 2.2, 5), (7.2, 10.2, 3.0, 2.0, -5), (7.6, 11.6, 2.4, 1.8, -12), (7.2, 7.0, 2.8, 2.0, 12),
             (6.8, 5.6, 2.0, 1.6, 20), (6.4, 4.4, 1.6, 1.3, 30)]
    for cx, cy, w, d, ang in rings:
        it.wbox((cx - w / 2, cy - 0.8, 8 - d / 2), (cx + w / 2, cy + 0.8, 8 + d / 2), m, rot={'origin': (cx, cy, 8), 'z': ang},
                name='ring')
    it.wbox((7.0, 12.4, 7.2), (9.6, 13.8, 8.8), m, name='head')
    it.wbox((9.4, 12.6, 7.6), (12.2, 13.3, 8.4), lambda *a: body, name='snout')
    it.wbox((7.4, 13.8, 7.6), (8.6, 14.6, 8.4), lambda *a: light, name='crown')
    for k, (cx, cy, s) in enumerate(((6.8, 3.4, 1.1), (7.6, 2.6, 1.0), (8.6, 2.6, 0.9), (9.2, 3.4, 0.8), (8.8, 4.2, 0.7),
                                     (8.0, 4.0, 0.6))):
        it.wbox((cx - s / 2, cy - s / 2, 8 - s / 2), (cx + s / 2, cy + s / 2, 8 + s / 2), m, name='tail')
    fin(it, 4.6, 8.4, ['o#', '#+', 'o#'], finpal('#f4d890', tip='#fff0c0', a=200))
    eye(it, 8.6, 13.2, 0.8, hx('#f4c860'), size=0.7)


@reg('fish_clownfish', 'Clownfish', 'Lives safely among stinging anemones.', fish_disp(gui_scale=1.0))
def b_clownfish(it):
    pal = P('#e8580e', '#f47a1a', '#f89a3a')
    white, black = hx('#fbfbf6'), hx('#141414')

    def bands(face, x, y, z, t, c, f):
        if face not in ('north', 'south', 'up'):
            return None
        for bx, w in ((11.0, 1.4), (7.4, 1.6), (4.4, 0.9)):
            if bx <= x < bx + w:
                return white
            if bx - 0.3 <= x < bx or bx + w <= x < bx + w + 0.3:
                return black
    F = Fish(it, 4.0, 13.0, 5.0, 1.2, pal, bands, peak=0.5, tailf=0.35, nosef=0.5, mouth=0.5)
    fp = finpal('#f07a1a', tip='#141414')
    F.tail(['.o#.', 'o##+', 'o##+', 'o##+', '.o#.'], fp)
    F.dorsal(0.25, ['..o.oo', '.o#o##', 'o#+#+#'], fp)
    F.anal(0.25, ['#+#', 'o#o'], fp)
    F.pect(0.78, ['o#+', '.o#'], fp)
    F.eye('#f0a040', size=0.9, dx=1.1)


@reg('fish_surgeonfish', 'Surgeonfish', 'A scalpel-sharp spine hides by its tail.', fish_disp(gui_scale=0.95))
def b_surgeonfish(it):
    pal = P('#1e3ab8', '#2a5ae0', '#4a7af0')
    black, yellow = hx('#0e1430'), hx('#f2d21e')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south', 'up'):
            if x < 5.4:
                return yellow
            # the black "palette" swoosh along the back, with the blue inside it
            if 0.55 < t < 0.95 and 6.0 < x < 12.4:
                inner = 0.68 < t < 0.82 and 7.0 < x < 10.6
                return c if inner else black
    F = Fish(it, 4.0, 13.4, 6.6, 0.9, pal, pat, peak=0.5, tailf=0.3, nosef=0.42, mouth=0.4)
    fp = finpal('#1e3ab8', tip='#0e1430')
    F.tail(['o#..', 'o##+', '.#++', 'o##+', 'o#..'], finpal('#f2d21e', tip='#ffe860'))
    F.dorsal(0.2, ['oooooo', '######'], fp)
    F.anal(0.2, ['#####', 'ooooo'], fp)
    F.pect(0.78, ['o#+', '.o#'], finpal('#f2d21e', tip='#ffe860'))
    F.eye('#2a3a6a', size=0.8, dx=1.4)


@reg('fish_butterfly_fish', 'Butterfly Fish', 'A false eye near its tail fools hunters.', fish_disp(gui_scale=0.95))
def b_butterfly_fish(it):
    pal = P('#e8c818', '#f8e868', '#fbf6e0')
    black = hx('#141414')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south', 'up'):
            if 11.6 <= x < 12.6:
                return black                                         # the band through the eye
            if face != 'up' and (x - 6.2) ** 2 + (y - 9.6) ** 2 < 0.8:
                return black                                         # the false eye
            if face != 'up' and (x - 6.2) ** 2 + (y - 9.6) ** 2 < 1.4:
                return WHITE
            if face != 'up' and x < 11 and 0.1 < t < 0.6 and int(math.floor(x + y)) % 2 == 0:
                return hx('#f0e0a0')
    F = Fish(it, 4.5, 13.4, 7.0, 0.8, pal, pat, peak=0.48, tailf=0.32, nosef=0.3, mouth=0.6)
    fp = finpal('#e8c818', tip='#141414')
    F.tail(['oo.', 'o#+', 'o#+', 'o#+', 'oo.'], fp)
    F.dorsal(0.15, ['..o.o.o', 'o#o#o#o', '#+#+#+#'], fp)
    F.anal(0.15, ['#+#+#', 'o#o#o'], fp)
    F.eye('#e8c818', size=0.75, dx=1.0)


@reg('fish_napoleonfish', 'Napoleon Fish', 'A giant wrasse with a hump like a bicorne hat.', fish_disp(gui_scale=0.8))
def b_napoleon(it):
    pal = P('#1e6a6a', '#3a9a90', '#8ac8b8', mouth='#2a4a48')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south') and x > 11.5 and (int(math.floor(x * 2)) + int(math.floor(y * 2))) % 4 == 0:
            return hx('#e8c84a')                                     # maze lines on the face
        if face in ('north', 'south') and x < 11.5 and int(math.floor(x * 1.2)) % 2 == 0 and 0.2 < t < 0.85:
            return shade(c, 0.85)
    F = Fish(it, 1.5, 15.0, 7.2, 1.8, pal, pat, peak=0.5, tailf=0.32, nosef=0.5, hump=1.6, mouth=1.4, mouth_t=0.35,
             segs=9)
    fp = finpal('#1e5a5a', tip='#4ab0a0')
    F.tail(['oo.', 'o#+', 'o#+', 'o#+', 'o#+', 'oo.'], fp)
    F.dorsal(0.15, ['..o#o#o#o', 'o#+#+#+#+'], fp)
    F.anal(0.15, ['#+#+#', 'o#o#o'], fp)
    F.pect(0.78, ['oo#+', '.o#+'], fp)
    # thick lips
    it.wbox((14.2, F.B.bot(14.6) + 0.6, 7.0), (15.4, F.B.bot(14.6) + 1.6, 9.0), lambda *a: hx('#4a8a78'), name='lips')
    F.eye('#e8c84a', t=0.6, size=0.8, dx=1.8)


@reg('fish_zebra_turkeyfish', 'Zebra Turkeyfish', 'Lionfish: every stripe and spine is a warning.', fish_disp(gui_scale=0.85))
def b_lionfish(it):
    pal = P('#a83a2a', '#d86a4a', '#f2e0d0')
    pat = bands_pattern([4.5, 5.8, 7.1, 8.4, 9.7, 11.0, 12.3], '#fbf2ea', width=0.6, tmin=0.0)
    F = Fish(it, 3.5, 13.8, 5.2, 1.3, pal, pat, peak=0.55, mouth=0.9)
    rw = {'#': hx('#c84a32'), '+': hx('#a83a2a'), 'w': hx('#fbf2ea'), 'o': hx('#fbf2ea'), '-': hx('#e08a6a'),
          '*': hx('#3a1a14'), 'k': BLACK}
    # the long venomous dorsal spines, each striped
    for k, u in enumerate((0.3, 0.38, 0.46, 0.54, 0.62, 0.7, 0.78)):
        h = 4 if k % 2 else 5
        x = F.x(u)
        it.card((x, F.top(u) - 0.4, 8.0), (x + 0.5, F.top(u) - 0.4 + h, 8.0),
                ik.p_func(lambda i, j, w, hh, f: hx('#fbf2ea') if j % 2 else hx('#a83a2a')), name='spine')
    # the big fan pectorals, striped
    # radial spines: every other column a pale spine, the webbing red between (stripes along the rays)
    shape = ['..ooooo', '.oooooo', 'ooooooo', 'ooooooo', 'ooooooo', '.oooooo']
    fan = [''.join('.' if ch == '.' else ('w' if i % 2 else ('o' if r == 0 or row[i - 1:i] == '.' else '#'))
                   for i, ch in enumerate(row)) for r, row in enumerate(shape)]
    pectorals(it, F.x(0.78), F.mid(0.78) - 2.0, F.hw(0.78), fan, rw, ang=40)
    F.tail(['oo#.', 'o#w+', 'o#w+', 'o#w+', 'oo#.'], rw)
    F.anal(0.25, ['#w#', 'o#o'], rw)
    F.eye('#c8303a', size=0.8, dx=1.2)
    for sgn in (1, -1):                                              # the little tentacles over the eyes
        it.wbox((12.4, F.B.top(12.6) - 0.2, 8 + sgn * 0.4 - 0.1), (12.6, F.B.top(12.6) + 1.2, 8 + sgn * 0.4 + 0.1),
                lambda *a: hx('#fbf2ea'))


@reg('fish_blowfish', 'Blowfish', 'Puffs into a spiny ball when frightened.', fish_disp(gui_scale=0.9, roll=10))
def b_blowfish(it):
    body, belly, spot = hx('#b89a5a'), hx('#f4ecd6'), hx('#2e2214')

    def m(face, x, y, z, f):
        if face == 'down' or (face in ('north', 'south') and y < 6.6):
            return belly
        if (int(math.floor(x * 1.2)) * 5 + int(math.floor(y * 1.2)) * 3 + int(math.floor(z))) % 6 == 0:
            return spot
        return body
    # an inflated ball from stacked boxes, a tiny tail and fins, big eyes, spines all over
    for (x0, x1, y0, y1, h) in ((5.0, 11.0, 4.6, 11.4, 2.6), (4.2, 11.8, 5.4, 10.6, 3.0), (5.6, 10.4, 4.0, 12.0, 2.2)):
        it.wbox((x0, y0, 8 - h), (x1, y1, 8 + h), m, name='ball')
    it.wbox((11.4, 6.6, 6.8), (12.8, 8.6, 9.2), m, name='face')
    it.wbox((12.8, 7.1, 7.5), (13.4, 7.9, 8.5), lambda *a: hx('#e8d8b0'), name='beak')
    sp = lambda face, x, y, z, f: hx('#fbf6e6') if face in ('up', 'east', 'west') else hx('#d8c8a0')
    for k in range(16):
        a = k * math.pi * 2 / 16 + 0.2
        cx, cy = 8 + 3.4 * math.cos(a), 8 + 3.6 * math.sin(a)
        ang = (math.degrees(a) - 90 + 180) % 360 - 180
        for zz in (6.4, 9.6):
            it.wbox((cx - 0.18, cy, zz - 0.18), (cx + 0.18, cy + 1.7, zz + 0.18), sp, rot={'origin': (cx, cy, zz), 'z': ang},
                    name='spine')
    for k in range(10):
        a = k * math.pi * 2 / 10 + 0.3
        cx, cy = 8 + 2.6 * math.cos(a), 8 + 2.8 * math.sin(a)
        for zz, dz in ((5.2, -1), (10.8, 1)):
            z0, z1 = sorted((zz, zz + dz * 1.0))
            it.wbox((cx - 0.15, cy - 0.15, z0), (cx + 0.15, cy + 0.15, z1), sp)
    fp = finpal('#c8b07a', tip='#f0e0b0', a=210)
    fin(it, 2.2, 6.8, ['o#', '#+', 'o#'], fp)
    fin(it, 4.2, 10.8, ['o#'], fp)
    for sgn in (1, -1):
        z = 8 + sgn * 2.2
        it.wbox((10.4, 8.4, z - 0.6), (11.8, 9.8, z + 0.6),
                lambda face, x, y, zz, f: hx('#2a6a8a') if face in ('east', 'south', 'north') and y > 8.8 else BLACK,
                density=3, frame=False, name='eye')


@reg('fish_puffer_fish', 'Tiger Puffer', 'Fugu: a delicacy, and deadly if cut wrong.')
def b_tiger_puffer(it):
    pal = P('#2e3438', '#6a7076', '#f4f4ee')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south'):
            if (x - 10.4) ** 2 + (y - 8.2) ** 2 < 1.0:
                return BLACK                                         # the big black spot behind the fin
            if (x - 10.4) ** 2 + (y - 8.2) ** 2 < 1.6:
                return WHITE
            if t > 0.5 and (int(math.floor(x * 1.4)) * 3 + int(math.floor(y * 1.4))) % 7 == 0:
                return hx('#141618')
    F = Fish(it, 3.0, 14.0, 5.2, 1.8, pal, pat, peak=0.6, tailf=0.32, nosef=0.6, bands=(0.4, 0.55), mouth=0.6, gill=False)
    fp = finpal('#3a4044', tip='#7a8086')
    F.tail(['o#.', 'o#+', 'o#+', 'o#+', 'o#.'], fp)
    F.dorsal(0.25, ['o#', '#+'], fp)
    F.anal(0.25, ['#+', 'o#'], finpal('#e8e8e0'))
    F.pect(0.7, ['o#+'], fp)
    it.wbox((13.7, F.B.bot(13.8) + 1.2, 7.4), (14.4, F.B.bot(13.8) + 2.0, 8.6), lambda *a: hx('#e8e4d4'), name='beak')
    F.eye('#3a8a5a', t=0.7, size=0.9, dx=2.0)


@reg('fish_anchovy', 'Anchovy', 'Swims in schools of millions.', fish_disp(gui_scale=1.0))
def b_anchovy(it):
    pal = P('#2a5a6a', '#b8ccd2', '#f2f6f6')
    F = Fish(it, 3.5, 13.4, 2.8, 0.7, pal, stripe('#e8f0f2', 0.45, 0.58, x_max=12.5), peak=0.55, tailf=0.3,
             nosef=0.5, bands=(0.35, 0.62), mouth=1.4, mouth_t=0.3)
    fp = finpal('#9ab0b8', tip='#d8e4e8', a=210)
    F.tail('fork_s', fp)
    F.dorsal(0.5, ['o#'], fp)
    F.anal(0.25, ['#+', 'o.'], fp)
    F.eye('#d8e4e8', size=1.0, dx=1.1)


@reg('fish_horse_mackerel', 'Horse Mackerel', 'Armoured scutes run along its side.')
def b_horse_mackerel(it):
    pal = P('#3a5a6a', '#b8c4c8', '#f2f4f0')

    def scutes(face, x, y, z, t, c, f):
        if face in ('north', 'south') and abs(t - (0.62 if x > 9 else 0.5)) < 0.06 and x < 13 and int(math.floor(x * 2)) % 2 == 0:
            return hx('#7a8a90')
        if face in ('north', 'south') and 11.6 <= x < 12.2 and 0.62 < t < 0.8:
            return BLACK                                             # the gill spot
    F = Fish(it, 2.5, 14.5, 4.4, 1.1, pal, scutes, bands=(0.32, 0.66), mouth=1.0)
    fp = finpal('#8aa0a8', tip='#c8d8dc')
    F.tail('fork', fp)
    F.dorsal(0.5, ['.o.o', 'o#o#', '#+#+'], fp)
    F.dorsal(0.2, ['o#o#o#', '#+#+#+'], fp)
    F.anal(0.2, ['#+#+#', 'o#o#o'], fp)
    F.pect(0.78, ['oo#', '.o#'], fp)
    F.eye('#d8dcd8', size=1.1, dx=1.3)


@reg('fish_barred_knifejaw', 'Barred Knifejaw', 'Crunches sea urchins with a parrot-like beak.')
def b_knifejaw(it):
    pal = P('#9a9a92', '#d8d6cc', '#f2f0e8')
    pat = bands_pattern([4.0, 6.0, 8.0, 10.0, 12.0], '#1e1e20', width=1.0)
    F = Fish(it, 3.0, 14.0, 6.8, 1.2, pal, pat, peak=0.5, tailf=0.3, nosef=0.45, mouth=0.8)
    fp = finpal('#2a2a2c', tip='#6a6a6a')
    F.tail('fork_s', fp)
    F.dorsal(0.2, ['..o.o.o.', 'o#o#o#o#', '#+#+#+#+'], fp)
    F.anal(0.2, ['#+#+', 'o#o#'], fp)
    F.pect(0.78, ['o#+'], finpal('#d8d6cc'))
    it.wbox((13.6, F.B.bot(13.8) + 2.0, 7.4), (14.6, F.B.bot(13.8) + 3.0, 8.6), lambda *a: hx('#4a4a4c'), name='beak')
    F.eye('#d8c88a', size=0.9, dx=1.5)


@reg('fish_sea_bass', 'Sea Bass', 'Hunts the surf where rivers meet the sea.')
def b_sea_bass(it):
    pal = P('#3e4e5a', '#a8b4ba', '#eef0ee', mouth='#2a3238')
    F = Fish(it, 2.0, 15.0, 5.2, 1.4, pal, spots_pattern('#2e3a44', density=9, tmin=0.6, tmax=0.95), bands=(0.32, 0.66),
             mouth=1.8, mouth_t=0.35)
    fp = finpal('#4a5a66', tip='#8a9aa4')
    F.tail(['o#..', 'o##+', '.#++', 'o##+', 'o#..'], fp)
    F.dorsal(0.45, ['.o.o.o', 'o#o#o#', '#+#+#+'], fp)
    F.dorsal(0.2, ['.o#', 'o#+'], fp)
    F.anal(0.22, ['#+#', 'o#o'], fp)
    F.pect(0.78, ['o#+', '.o#'], fp)
    F.eye('#d8d8c8', size=0.95, dx=1.6)


@reg('fish_dab', 'Dab', 'A flatfish that lies on its left side, both eyes up.', flat_disp(0.95))
def b_dab(it):
    flatfish(it, '#a0845a', '#6a5434', spots=9, eyes_left=False, spot_c='#d8a060', length=12.0, depth=7.0)


@reg('fish_olive_flounder', 'Olive Flounder', 'Changes colour to match the sea floor.', flat_disp(0.9))
def b_olive_flounder(it):
    flatfish(it, '#6a6a4a', '#3e3e2a', spots=6, eyes_left=True, spot_c='#e8e0c0', length=13.5, depth=8.0, seed=7)


@reg('fish_squid', 'Squid', 'Changes colour in a blink; ten arms, three hearts.', fish_disp(yaw=-25, tilt=10, roll=90, gui_scale=0.95))
def b_squid(it):
    M = {'back': hx('#c87a6a'), 'side': hx('#e8b0a0'), 'belly': hx('#f4e0d8')}
    dark = hx('#8a3a2a')

    def mm(bot, top):
        def m(face, x, y, z, f):
            t = 1.0 if face == 'up' else (0.0 if face == 'down' else (y - bot) / max(0.01, top - bot))
            c = M['belly'] if t < 0.3 else (M['side'] if t < 0.7 else M['back'])
            if face in ('north', 'south', 'up') and (int(math.floor(x * 1.5)) * 3 + int(math.floor(y * 1.5)) * 5) % 7 == 0:
                return dark                                          # chromatophore spots
            return c
        return m
    for x0, x1, h, w in ((7.6, 10.4, 3.6, 3.2), (10.4, 12.8, 3.0, 2.6), (12.8, 14.4, 2.0, 1.8), (14.4, 15.4, 1.0, 1.0)):
        it.wbox((x0, 8 - h / 2, 8 - w / 2), (x1, 8 + h / 2, 8 + w / 2), mm(8 - h / 2, 8 + h / 2), name='mantle')
    fp = finpal('#e8b0a0', tip='#f8d8c8', a=220)
    fin(it, 12.6, 9.3, ['.oo', 'o#+', '#++'], fp)                    # the arrowhead fins at the mantle tip
    fin(it, 12.6, 3.7, ['#++', 'o#+', '.oo'], fp)
    it.wbox((6.0, 6.6, 6.8), (7.8, 9.4, 9.2), mm(6.6, 9.4), name='head')
    eye(it, 6.9, 8.3, 1.2, hx('#c8d8e0'), size=1.1)
    am = lambda face, x, y, z, f: M['side'] if face in ('up', 'south', 'north') else M['back']
    for y, z, ang in ((6.9, 7.2, -10), (7.4, 8.8, 12), (7.9, 7.4, 4), (8.6, 7.8, 16), (7.1, 8.6, -16), (8.3, 8.5, -6),
                      (8.9, 8.2, 8), (7.6, 7.0, -4)):
        it.wbox((2.6, y - 0.3, z - 0.3), (6.4, y + 0.3, z + 0.3), am, rot={'origin': (6.2, y, z), 'z': ang})
    for y, z, ang in ((7.2, 7.6, -14), (8.6, 8.4, 10)):
        it.wbox((0.4, y - 0.2, z - 0.2), (6.4, y + 0.2, z + 0.2), am, rot={'origin': (6.2, y, z), 'z': ang})
        it.wbox((0.0, y - 0.45, z - 0.45), (1.2, y + 0.45, z + 0.45), am, rot={'origin': (6.2, y, z), 'z': ang}, name='club')


@reg('fish_moray_eel', 'Moray Eel', 'Gapes from its crevice, a second set of jaws in its throat.', fish_disp(gui_scale=1.0))
def b_moray(it):
    pal = {'back': hx('#5a5a1e'), 'side': hx('#8a8a2e'), 'belly': hx('#b8b04a')}

    def pat(face, x, y, z, t, c, f):
        if (int(math.floor(x * 1.6)) * 5 + int(math.floor(y * 1.6)) * 3 + int(z)) % 5 == 0:
            return hx('#2a2a0a')                                     # dark speckles
    prof = lambda u: (2.0 + 1.4 * min(1.0, u * 1.8), 1.4 + 0.8 * min(1.0, u * 1.8))
    yc, sl = eel(it, 0.5, 14.0, 8, prof, 1.4, 13.0, pal, pattern=pat, phase=0.9)
    hy = yc(14.0)
    head = lambda face, x, y, z, f: hx('#7a7a28') if face != 'down' else hx('#b8b04a')
    it.wbox((13.4, hy - 0.4, 6.8), (16.0, hy + 1.6, 9.2), head, name='head')
    it.wbox((13.4, hy - 1.8, 6.9), (15.8, hy - 0.6, 9.1), head, rot={'origin': (13.4, hy - 0.6, 8), 'z': -16}, name='jaw')
    it.wbox((14.4, hy - 0.7, 7.1), (15.8, hy - 0.4, 8.9),
            lambda face, x, y, z, f: WHITE if int(math.floor(x * 2)) % 2 == 0 else None, density=2, frame=False, name='teeth')
    eye(it, 14.8, hy + 0.9, 1.2, hx('#e8d84a'), size=0.6)
    fp = finpal('#6a6a24', tip='#9a9a3a')
    for cx in (2.0, 4.6, 7.2, 9.8, 12.0):
        top = yc(cx) + prof((cx - 0.5) / 13.5)[0] / 2 - 0.3
        fin(it, cx - 1, top, ['oooo', '####'], fp, rot={'origin': (cx, top, 8), 'z': math.degrees(math.atan(sl(cx)))})


@reg('fish_ribbon_eel', 'Ribbon Eel', 'Electric blue and yellow, with flared nostrils like flowers.', fish_disp(gui_scale=1.05))
def b_ribbon_eel(it):
    pal = {'back': hx('#1e4ae0'), 'side': hx('#2a5af0'), 'belly': hx('#4a7af4')}
    prof = lambda u: (0.9 + 0.5 * min(1.0, u * 2.0), 0.7 + 0.3 * min(1.0, u * 2.0))
    yc, sl = eel(it, 0.5, 14.4, 9, prof, 1.8, 10.0, pal, phase=0.5)
    yel = finpal('#f2d21e', tip='#ffe860')
    for cx in (1.5, 3.5, 5.5, 7.5, 9.5, 11.5):
        top = yc(cx) + 0.4
        fin(it, cx - 1, top, ['o#o#'], yel, rot={'origin': (cx, top, 8), 'z': math.degrees(math.atan(sl(cx)))})
    hy = yc(14.4)
    it.wbox((14.0, hy - 0.6, 7.3), (16.0, hy + 0.6, 8.7), lambda face, x, y, z, f: hx('#f2d21e') if face != 'up' else hx('#2a5af0'),
            name='jaw')
    for sgn in (1, -1):                                              # the flared nostril "petals"
        it.card((15.6, hy + 0.4, 8 + sgn * 0.5), (16.8, hy + 1.6, 8 + sgn * 0.5),
                ik.p_rows(['o#o', '###', '.#.'], {'o': hx('#ffe860'), '#': hx('#f2d21e')}), name='nostril')
    eye(it, 14.4, hy + 0.3, 0.7, hx('#f2d21e'), size=0.55)


@reg('fish_tuna', 'Tuna', 'A warm-blooded torpedo that never stops swimming.', fish_disp(gui_scale=0.85))
def b_tuna(it):
    pal = P('#141e3e', '#8a9ab0', '#eef0f2')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south') and 0.4 < t < 0.55 and x < 12:
            return mix(c, hx('#3a5a8a'), 0.5)
    F = Fish(it, 2.0, 15.0, 6.2, 1.8, pal, pat, peak=0.55, tailf=0.14, nosef=0.45, bands=(0.4, 0.55), mouth=1.0)
    fp = finpal('#141e3e', tip='#3a4a6a')
    F.tail(['o....', 'o#...', '.o#+.', '..#++', '..#++', '.o#+.', 'o#...', 'o....'], fp)
    F.dorsal(0.6, ['.o.', 'o#+', '#+#'], fp)
    F.dorsal(0.38, ['.o', 'o#', '#+'], finpal('#c8a020', tip='#f2d040'))
    F.anal(0.38, ['#+', 'o#', '.o'], finpal('#c8a020', tip='#f2d040'))
    yel = finpal('#f2d21e', tip='#ffe860')
    for u in (0.1, 0.17, 0.24, 0.31):                                # the yellow finlets
        F.dorsal(u, ['o'], yel)
        F.anal(u, ['o'], yel)
    F.pect(0.75, ['oo##', '.o##'], fp)
    F.eye('#c8c8c0', size=0.95, dx=2.0)


@reg('fish_blue_marlin', 'Blue Marlin', 'Spears through baitfish with its bill.', fish_disp(gui_scale=0.75))
def b_marlin(it):
    pal = P('#14205a', '#4a6ab0', '#eef0f4')
    pat = bands_pattern([5.0, 6.4, 7.8, 9.2, 10.6], '#7aa0e8', width=0.5, tmin=0.35, tmax=0.75)
    F = Fish(it, 1.0, 13.0, 4.8, 1.4, pal, pat, peak=0.55, tailf=0.14, nosef=0.5, bands=(0.4, 0.6), mouth=None, segs=9)
    bill = lambda face, x, y, z, f: hx('#1e2a5a') if face != 'down' else hx('#9ab0d8')
    it.wbox((12.6, 7.6, 7.7), (17.8, 8.2, 8.3), bill, rot={'origin': (12.6, 7.9, 8), 'z': -3}, name='bill')
    fp = finpal('#14205a', tip='#3a5ab0')
    fp['*'] = hx('#2a4ac8')
    fin(it, F.x(0.3), F.top(0.5) - 0.5, ['.....o', '....o#', '...o*#', '..o#*#', '.o#*#+', 'o#*#++'], fp)   # the sail
    F.tail(['o.....', 'o#....', '.o#...', '..o#+.', '...#++', '...#++', '..o#+.', '.o#...', 'o#....', 'o.....'], fp)
    F.anal(0.3, ['#+', 'o#', '.o'], fp)
    F.pect(0.75, ['oo##', '.o##', '..o#'], fp)
    F.eye('#c8c8d0', size=0.9, dx=1.0)


@reg('fish_giant_trevally', 'Giant Trevally', 'Leaps from the surf to grab seabirds.', fish_disp(gui_scale=0.8))
def b_gt(it):
    pal = P('#3a4248', '#8a9298', '#d8dcdc')
    F = Fish(it, 2.0, 14.8, 7.4, 1.4, pal, spots_pattern('#2a3036', density=11, tmin=0.4, tmax=0.95), peak=0.55,
             tailf=0.16, nosef=0.55, hump=0.8, mouth=1.2, mouth_t=0.3)
    fp = finpal('#1e2428', tip='#4a5258')
    F.tail(['o.....', 'o#....', '.o#...', '..o#+.', '...#++', '..o#+.', '.o#...', 'o#....', 'o.....'], fp)
    F.dorsal(0.5, ['.o.o', 'o#o#', '#+#+'], fp)
    F.dorsal(0.2, ['..o#', '.o#+', 'o#+#'], fp)
    F.anal(0.2, ['#+#+', 'o#o.'], fp)
    F.pect(0.75, ['oo###', '.oo##'], fp)
    F.eye('#d8c8a0', size=1.0, dx=1.6)


@reg('fish_mahi_mahi', 'Mahi-mahi', 'Blazes gold, green and blue, then fades in minutes.', fish_disp(gui_scale=0.85))
def b_mahi(it):
    pal = P('#1e6a5a', '#7ab84a', '#f2d83a')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south') and t < 0.7 and (int(math.floor(x * 1.3)) * 3 + int(math.floor(y * 1.3)) * 5) % 9 == 0:
            return hx('#2a6ab0')                                     # blue speckles
    F = Fish(it, 2.0, 14.0, 6.4, 1.2, pal, pat, peak=0.85, tailf=0.18, nosef=0.95, bands=(0.35, 0.68), mouth=0.8, segs=9)
    fp = finpal('#1e5aa8', tip='#3a8ad8')
    F.tail(['o....', 'o#...', '.o#+.', '..#++', '..#++', '.o#+.', 'o#...', 'o....'], finpal('#d8c03a', tip='#f2d83a'))
    F.dorsal(0.08, ['oooooooooooo', '############'], fp)             # one fin from head to tail
    F.anal(0.08, ['#######', 'ooooooo'], finpal('#d8c03a', tip='#f2d83a'))
    F.pect(0.8, ['o#+'], fp)
    F.eye('#d8c860', t=0.5, size=0.9, dx=1.0)


@reg('fish_ray', 'Ray', 'Glides over the sand on wide wings; mind the barbed tail.', flat_disp(0.85))
def b_ray(it):
    body, light, edge, under = hx('#7a6a4a'), hx('#9a8a62'), hx('#4a3e2a'), hx('#ece6d6')

    def wing(face, x, y, z, f):
        if face == 'north':
            return under
        if face != 'south':
            return edge
        if (int(math.floor(x * 1.2)) * 7 + int(math.floor(y * 1.2)) * 3) % 8 == 0:
            return hx('#c8b884')                                     # pale spots
        return light if (int(math.floor(x)) + int(math.floor(y))) % 3 else body
    it.wbox((4.8, 3.8, 7.5), (13.2, 12.2, 8.5), wing, rot={'origin': (9, 8, 8), 'z': 45}, name='disc')
    it.wbox((5.6, 6.6, 7.3), (12.6, 9.4, 9.0), wing, name='ridge')
    for ey in (6.9, 9.1):
        it.wbox((11.8, ey - 0.4, 8.9), (12.6, ey + 0.4, 9.4), lambda face, x, y, z, f: BLACK if face == 'south' else edge,
                density=2, frame=False)
    tm = lambda face, x, y, z, f: shade(body, 0.8) if face in ('south', 'up') else edge
    it.wbox((0.4, 7.6, 7.6), (4.2, 8.4, 8.4), tm, name='tail1')
    it.wbox((-3.0, 7.7, 7.7), (0.6, 8.3, 8.3), tm, rot={'origin': (0.5, 8.0, 8.0), 'z': -10}, name='tail2')
    it.wbox((1.6, 8.3, 7.8), (2.8, 8.7, 8.2), lambda *a: hx('#e8dcc0'), name='barb')


@reg('fish_saw_shark', 'Saw Shark', 'Its toothed snout has whiskers to feel for prey.', top_disp(0.8))
def b_saw_shark(it):
    F = shark(it, 0.5, 12.0, 3.0, 1.2, '#8a7a5a', '#ece4d4', dorsal_h=3, snout=0.5,
              pattern=spots_pattern('#6a5a3a', density=9, tmin=0.55, tmax=1.0))
    blade = lambda face, x, y, z, f: hx('#a8987a') if face in ('up', 'down') else hx('#7a6a4a')
    it.wbox((11.8, 7.6, 7.3), (17.8, 8.1, 8.7), blade, name='saw')
    for k in range(7):                                               # teeth along both edges of the saw
        x = 12.4 + k * 0.8
        for sgn in (1, -1):
            z0, z1 = sorted((8 + sgn * 0.7, 8 + sgn * 1.2))
            it.wbox((x, 7.7, z0), (x + 0.25, 8.0, z1), lambda *a: WHITE)
    for sgn in (1, -1):                                              # the barbels halfway along
        it.wbox((15.0, 7.0, 8 + sgn * 0.5 - 0.08), (15.15, 7.6, 8 + sgn * 0.5 + 0.08), lambda *a: hx('#c8b898'))


@reg('fish_hammerhead_shark', 'Hammerhead Shark', 'Its wide head scans the sand like a metal detector.', top_disp(0.8))
def b_hammerhead(it):
    F = shark(it, 0.5, 13.0, 3.6, 1.4, '#7a8288', '#eef0ee', dorsal_h=5, snout=0.55)
    head = lambda face, x, y, z, f: hx('#8a9298') if face in ('up', 'north', 'south') else hx('#c8ccc8') if face == 'down' else hx('#6a7278')
    it.wbox((12.6, 7.3, 3.4), (14.4, 8.7, 12.6), head, name='hammer')
    for sgn in (1, -1):                                              # eyes at the hammer's tips
        z = 8 + sgn * 4.6
        a, b = sorted((z, z + sgn * 0.6))
        it.wbox((13.2, 7.6, a), (13.9, 8.3, b), lambda face, x, y, zz, f: BLACK, frame=False, name='eye')


@reg('fish_great_white_shark', 'Great White Shark', 'The ocean\'s top hunter; it smells blood from far away.',
     fish_disp(gui_scale=0.8))
def b_great_white(it):
    shark(it, 0.5, 15.0, 4.8, 1.8, '#5a646c', '#f4f4f0', dorsal_h=5, snout=0.38, belly_t=0.42)
    # the white jagged line and teeth
    it.wbox((13.0, 6.2, 7.0), (15.0, 6.6, 9.0), lambda face, x, y, z, f: WHITE if int(math.floor(x * 3)) % 2 == 0 else None,
            density=3, frame=False, name='teeth')


@reg('fish_whale_shark', 'Whale Shark', 'The biggest fish in the sea eats only plankton.', fish_disp(gui_scale=0.8))
def b_whale_shark(it):
    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south', 'up') and t > 0.42:
            if (int(math.floor(x * 1.4)) + int(math.floor(y * 1.4 if face != 'up' else z * 1.4))) % 2 == 0 and \
                    (int(math.floor(x * 1.4)) * 3 + int(math.floor(y * 1.4))) % 4 == 0:
                return hx('#e8ecf0')                                 # white spots and pale stripes
            if face != 'up' and int(math.floor(x * 1.4)) % 3 == 0:
                return mix(c, hx('#c8d0d8'), 0.4)
    F = shark(it, 0.5, 15.0, 5.0, 2.2, '#2e3a4e', '#d8dce0', dorsal_h=4, snout=0.75, pattern=pat, belly_t=0.4)
    it.wbox((14.6, 6.6, 6.0), (15.3, 7.6, 10.0), lambda *a: hx('#141a24'), name='mouth')   # the wide flat mouth


@reg('fish_suckerfish', 'Suckerfish', 'Hitches rides on sharks with a sucker on its head.', fish_disp(gui_scale=0.95))
def b_remora(it):
    pal = P('#3a3c40', '#7a7e84', '#c8ccd0')
    F = Fish(it, 1.5, 14.8, 3.2, 1.0, pal, stripe('#1e2024', 0.45, 0.6, x_max=14.6), peak=0.5, tailf=0.4, nosef=0.6,
             mouth=1.2, mouth_t=0.3, segs=9)
    disc = lambda face, x, y, z, f: (hx('#a8acb0') if int(math.floor(x * 3)) % 2 == 0 else hx('#5a5e64')) if face == 'up' else hx('#4a4e54')
    it.wbox((10.8, F.top(0.85) - 0.2, 7.0), (14.4, F.top(0.85) + 0.4, 9.0), disc, density=3, name='sucker')
    fp = finpal('#3a3c40', tip='#7a7e84')
    F.tail(['oo#.', 'o##+', '.#++', 'o##+', 'oo#.'], fp)
    F.dorsal(0.15, ['oooooo', '######'], fp)
    F.anal(0.15, ['######', 'oooooo'], fp)
    F.pect(0.78, ['o#+'], fp)
    F.eye('#c8c8c0', size=0.8, dx=1.4)


@reg('fish_football_fish', 'Football Fish', 'An anglerfish: its glowing lure draws prey into the dark.',
     fish_disp(gui_scale=0.9, roll=15))
def b_football_fish(it):
    pal = P('#1e1a18', '#2e2824', '#3e3630', mouth='#0a0606')

    def warts(face, x, y, z, t, c, f):
        if (int(math.floor(x * 1.5)) * 5 + int(math.floor(y * 1.5)) * 3 + int(math.floor(z * 1.5))) % 7 == 0:
            return hx('#4a4038')
    F = Fish(it, 4.0, 13.4, 7.4, 2.6, pal, warts, peak=0.55, tailf=0.35, nosef=0.75, mouth=None, gill=False)
    # the huge mouth with needle teeth
    it.wbox((12.4, 5.6, 6.2), (14.0, 7.6, 9.8), lambda *a: hx('#0a0606'), name='maw')
    for k in range(5):
        x = 12.6 + k * 0.3
        it.wbox((x, 7.2, 6.5), (x + 0.15, 8.0, 9.5), lambda *a: hx('#e8e4d8'), name='tooth')
        it.wbox((x, 5.4, 6.5), (x + 0.15, 6.1, 9.5), lambda *a: hx('#e8e4d8'), name='tooth')
    # the illicium and its glowing bulb
    stalk = lambda *a: hx('#3e3630')
    it.wbox((11.4, 11.0, 7.85), (11.7, 13.4, 8.15), stalk, rot={'origin': (11.5, 11.0, 8), 'z': -25}, name='stalk')
    it.wbox((12.4, 12.4, 7.4), (13.6, 13.6, 8.6), lambda face, x, y, z, f: hx('#c8ffe8') if face != 'down' else hx('#6ae8c0'),
            shade=False, frame=False, name='lure')
    fp = finpal('#2e2824', tip='#4a4038')
    F.tail(['.o.', 'o#+', 'o#+', 'o#+', '.o.'], fp)
    F.dorsal(0.25, ['o#'], fp)
    F.anal(0.25, ['#+', 'o.'], fp)
    F.pect(0.6, ['o#'], fp)
    F.eye('#2a2420', t=0.72, size=0.6, dx=2.4)


@reg('fish_oarfish', 'Oarfish', 'A silver ribbon longer than a bus, crowned in red.', fish_disp(gui_scale=1.0))
def b_oarfish(it):
    pal = {'back': hx('#a8b4c0'), 'side': hx('#d8e0e8'), 'belly': hx('#f2f4f6')}

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south') and int(math.floor(x * 1.5)) % 3 == 0 and t > 0.4:
            return hx('#5a6a7a')                                     # faint dark streaks
    prof = lambda u: (1.6 + 0.8 * min(1.0, u * 1.5), 0.6 + 0.2 * min(1.0, u * 1.5))
    yc, sl = eel(it, -2.0, 15.0, 10, prof, 1.6, 12.0, pal, pattern=pat, phase=0.3)
    red = finpal('#d81e2a', tip='#ff5a5a')
    for cx in [-1.0 + 1.6 * k for k in range(10)]:
        top = yc(cx) + prof((cx + 2) / 17.0)[0] / 2 - 0.3
        fin(it, cx - 1, top, ['o#'], red, rot={'origin': (cx, top, 8), 'z': math.degrees(math.atan(sl(cx)))})
    hy = yc(15.0)
    for k, (dx, h) in enumerate(((-0.4, 3.2), (0.2, 4.0), (0.8, 2.6))):   # the red crest plumes over the head
        it.card((13.6 + dx, hy + 0.8, 8.0), (14.2 + dx, hy + 0.8 + h, 8.0),
                ik.p_func(lambda i, j, w, hh, f: hx('#ff5a5a') if j < 2 else hx('#d81e2a')), rot={'origin': (13.9 + dx, hy + 0.8, 8), 'z': 18 - 8 * k},
                name='crest')
    eye(it, 14.4, hy + 0.2, 0.5, hx('#e8e8f0'), size=0.6)


@reg('fish_barreleye', 'Barreleye', 'Looks up through a see-through head with green tube eyes.', fish_disp(gui_scale=1.0))
def b_barreleye(it):
    pal = P('#2a1e1a', '#4a3a30', '#6a5a4a')
    F = Fish(it, 3.0, 12.4, 4.0, 1.2, pal, None, peak=0.5, tailf=0.35, nosef=0.6, mouth=0.4, mouth_t=0.3)
    # the transparent dome over the head, with the tubular green eyes looking up inside it
    d0, d1 = (9.6, F.top(0.75) - 0.4, 6.6), (12.6, F.top(0.75) + 2.4, 9.4)

    def dome(face, x, y, z, f):
        # a clear shield: a faint glassy fill (nearly invisible) with brighter rims, so the eyes show through it
        edge = sum(abs(v - lo) < 0.45 or abs(v - hi) < 0.45 for v, lo, hi in zip((x, y, z), d0, d1)) >= 2
        return alpha('#e8fbff', 190) if edge else alpha('#d8f0f8', 22)
    it.wbox(d0, d1, dome, density=2, frame=False, shade=False, name='dome')
    for sgn in (1, -1):
        z = 8 + sgn * 0.55
        it.wbox((10.6, F.top(0.75) - 0.2, z - 0.4), (11.4, F.top(0.75) + 1.7, z + 0.4),
                lambda face, x, y, zz, f: hx('#5aff6a') if face == 'up' else hx('#2a9a3a'), shade=False, name='tube_eye')
    fp = finpal('#4a3a30', tip='#8a7a6a', a=210)
    F.tail(['oo.', 'o#+', 'o#+', 'o#+', 'oo.'], fp)
    F.dorsal(0.35, ['.o', 'o#'], fp)
    F.anal(0.3, ['#+', 'o#'], fp)
    F.pect(0.7, ['oo##', '.o##'], fp, ang=45)


@reg('fish_coelacanth', 'Coelacanth', 'Thought extinct for 66 million years, until 1938.', fish_disp(gui_scale=0.8))
def b_coelacanth(it):
    pal = P('#1e2e5a', '#2e4a8a', '#3a5a9a')
    F = Fish(it, 2.5, 14.8, 5.6, 1.6, pal, spots_pattern('#e8ecf4', density=6, tmin=0.15, tmax=0.95, x_max=13.5),
             peak=0.5, tailf=0.4, nosef=0.55, scales=0.06, mouth=1.2)
    fp = finpal('#2a3e78', tip='#5a7ac8')
    # three-lobed tail: upper and lower lobes, and the little middle lobe
    F.tail(['.oo#.', 'o##+.', '..o#+', 'ooo##', '..o#+', 'o##+.', '.oo#.'], fp)
    # lobed fins: a fleshy stalk with a fin fan on the end
    lobe = lambda face, x, y, z, f: hx('#2e4a8a') if face != 'down' else hx('#1e2e5a')
    for (u, side, rows) in ((0.42, 1, ['o#o', '#+#']), (0.28, 1, ['o#', '#+']), (0.32, -1, ['#+#', 'o#o']), (0.55, -1, ['#+', 'o#'])):
        x = F.x(u)
        y = F.top(u) if side > 0 else F.bot(u) - 1.0
        it.wbox((x, y, 7.5), (x + 1.2, y + 1.0, 8.5), lobe, name='lobe')
        fin(it, x - 1.0 if side > 0 else x - 1.0, y + (0.8 if side > 0 else -1.6), rows, fp)
    for sgn in (1, -1):
        z = 8 + sgn * F.hw(0.75)
        a, b = sorted((z, z + sgn * 1.0))
        it.wbox((F.x(0.74), F.mid(0.74) - 1.4, a), (F.x(0.74) + 1.2, F.mid(0.74) - 0.4, b), lobe, name='pectoral_lobe')
    F.eye('#d8f0a0', size=1.0, dx=1.8)
