"""Fresh-water species (rivers, ponds and lakes, swamps): one builder per animal, drawn from the real one."""
import math

from ik import hx, mix, shade
from kit import BLACK, WHITE, C, alpha, eye, fin, finpal, pectorals, tail, eel, fish_disp, crab_disp
from shapes import (Fish, bands_pattern, spots_pattern, combine, stripe, crayfish, crab, turtle, turtle_disp,
                    upright_disp)

REG = []


def reg(sid, name, lore, disp=None):
    def deco(fn):
        REG.append((sid, name, lore, fn, disp or fish_disp()))
        return fn
    return deco


def P(back, side, belly, dark=None, mouth=None):
    p = {'back': C(back), 'side': C(side), 'belly': C(belly)}
    p['dark'] = C(dark) if dark else shade(p['back'], 0.65)
    if mouth:
        p['mouth'] = C(mouth)
    return p


@reg('fish_bitterling', 'Bitterling', 'Lays its eggs inside a living mussel.', fish_disp(gui_scale=1.0))
def b_bitterling(it):
    pal = P('#5a5a86', '#b6a8c8', '#efd8dc')
    blue = hx('#3fa0c8')
    pat = combine(stripe(blue, 0.42, 0.56, x_max=9.0), stripe('#e89aa8', 0.18, 0.34, x_min=8.0))
    F = Fish(it, 3.5, 12.8, 5.6, 1.0, pal, pat, peak=0.5, tailf=0.3, bands=(0.3, 0.7))
    red = finpal('#d04858', tip='#f08a90')
    F.tail('fork_s', finpal('#9c90b4', tip='#d0c8e0'))
    F.dorsal(0.42, ['.o#o', 'o#+#'], red)
    F.anal(0.4, ['#+#o', 'o#o.'], red)
    F.pect(0.82, ['o#'], finpal('#c8bcd8'))
    F.eye('#f0c040', size=0.9)


@reg('fish_pale_chub', 'Pale Chub', 'Breeding males blush pink and blue-green.')
def b_pale_chub(it):
    pal = P('#3c5a5a', '#9ab8b4', '#eef0e8')
    pink, teal = hx('#e2788a'), hx('#3e9a90')

    def bars(face, x, y, z, t, c, f):
        if face in ('north', 'south') and 0.25 < t < 0.75 and x < 12.5:
            k = int(math.floor(x)) % 3
            return pink if k == 0 else (teal if k == 1 else None)
    F = Fish(it, 2.5, 14.5, 4.6, 1.0, pal, bars, bands=(0.28, 0.72))
    fp = finpal('#c88a8a', tip='#f0b8b0')
    F.tail('fork', finpal('#7c9a96', tip='#b8d0cc'))
    F.dorsal(0.45, ['.o#', 'o#+'], fp)
    F.anal(0.25, ['#+#+#', 'o#o#o', '.o.o.'], finpal('#e2788a', tip='#f4b0b8'))   # the long anal fin
    F.pect(0.8, ['o#+'], fp)
    F.eye('#e0d8b0')


@reg('fish_crucian_carp', 'Crucian Carp', 'Survives winters in ponds with barely any air.')
def b_crucian_carp(it):
    pal = P('#5a4a1c', '#b89a40', '#efdca0')

    def scales(face, x, y, z, t, c, f):
        if face in ('north', 'south') and 0.25 < t < 0.85 and (int(math.floor(x)) + int(math.floor(y))) % 2 == 0:
            return shade(c, 0.88)
    F = Fish(it, 3.0, 14.2, 7.2, 1.6, pal, scales, peak=0.5, tailf=0.3, nosef=0.4)
    fp = finpal('#6a5424', tip='#a08440')
    F.tail('fork', fp)
    F.dorsal(0.3, ['..o#o#o', '.o#+#+#', 'o#+#+#+'], fp)
    F.anal(0.32, ['#+#', 'o#.'], fp)
    F.pect(0.8, ['o#+', '.o#'], fp)
    F.eye('#d8b858')


@reg('fish_dace', 'Dace', 'A slim silver dart of quick rivers.', fish_disp(gui_scale=0.95))
def b_dace(it):
    pal = P('#4a5868', '#b4c0c8', '#f2f4f0')
    F = Fish(it, 2.5, 14.5, 4.0, 0.9, pal, stripe('#d8e0e6', 0.5, 0.58, x_max=13), bands=(0.32, 0.66))
    fp = finpal('#c8bc8a', tip='#e8dcaa')
    F.tail('fork', finpal('#8a96a0', tip='#c4ccd2'))
    F.dorsal(0.45, ['.o', 'o#', '#+'], fp)
    F.anal(0.3, ['#+', 'o.'], fp)
    F.pect(0.8, ['o#'], fp)
    F.eye('#e8e4cc', size=0.85)


@reg('fish_carp', 'Carp', 'Big, wary and old: some live past fifty.')
def b_carp(it):
    pal = P('#4a3c1c', '#a8823c', '#e8d29a')

    def scales(face, x, y, z, t, c, f):
        if face in ('north', 'south') and 0.22 < t < 0.9:
            ix, iy = int(math.floor(x / 1.5)), int(math.floor(y / 1.2))
            if (ix + iy) % 2 == 0:
                return shade(c, 0.86)
            if (x % 1.5) < 0.35:
                return shade(c, 1.12)
    F = Fish(it, 2.0, 15.0, 6.6, 1.7, pal, scales, peak=0.5, nosef=0.45, mouth=0.8)
    fp = finpal('#5a4620', tip='#8e6e34')
    F.tail('fork', fp)
    F.dorsal(0.25, ['....o#o', '..o#+#+', 'o#+#+#+'], fp)
    F.anal(0.3, ['#+', 'o#'], finpal('#9a5a2a', tip='#c47a3a'))
    F.pect(0.8, ['o#+', '.o#'], finpal('#9a5a2a', tip='#c47a3a'))
    F.eye('#d0a850')
    F.barbels('#8a6a34', n=2, length=1.2)


@reg('fish_koi', 'Koi', 'Bred for centuries for its red and white patches.', fish_disp(gui_scale=0.8))
def b_koi(it):
    pal = P('#f6f2ea', '#f2ece2', '#e2dace', dark='#a8a090')
    red = hx('#d62a1e')

    def kohaku(face, x, y, z, t, c, f):
        if face not in ('north', 'south', 'up'):
            return None
        if 12.6 <= x < 14.0 and (t > 0.55 or face == 'up'):          # the red cap on the head
            return red
        if (4.5 <= x < 7.0 and t > 0.4) or (8.0 <= x < 11.0 and t > 0.3 and not (9.2 <= x < 9.8 and t < 0.6)):
            return red
        if face == 'up' and 4.5 <= x < 11 and (int(x) + int(z * 2)) % 3:
            return red
    F = Fish(it, 2.5, 15.0, 6.0, 1.5, pal, kohaku, peak=0.55, scales=0.04, mouth=0.6)
    fp = finpal('#f2ece4', tip='#ffffff', a=235)
    F.tail(['oo#....', '.o##+..', '..o##+.', '...o#++', '....#++', '...o#++', '..o##+.', '.o##+..', 'oo#....'], fp)
    F.dorsal(0.25, ['.oo#o.', 'o##+#o', '##+#+#'], fp)
    F.anal(0.3, ['#+', 'o#'], fp)
    F.pect(0.8, ['oo#+', '.o#+', '..o#'], fp, ang=32)
    F.eye('#e8e0d0')
    F.barbels('#e8d8c0', n=1, length=1.2)


@reg('fish_goldfish', 'Goldfish', 'A carp, tamed in China over a thousand years ago.', fish_disp(gui_scale=1.0))
def b_goldfish(it):
    pal = P('#d0480e', '#f07a1e', '#f8c070')
    F = Fish(it, 4.0, 12.6, 6.0, 1.6, pal, None, peak=0.5, tailf=0.32, nosef=0.5, scales=0.06, mouth=0.5)
    fp = finpal('#f07a1e', tip='#ffc070', a=230)
    F.tail(['oo##....', '.o###+..', '..o##++.', '...o#++.', '...o#++.', '..o##++.', '.o###+..', 'oo##....'], fp)
    F.dorsal(0.35, ['.o#o', 'o#+#'], fp)
    F.anal(0.3, ['#+', 'o#'], fp)
    F.pect(0.8, ['o#+'], fp)
    F.eye('#e8b040', size=1.0)


@reg('fish_pop_eyed_goldfish', 'Pop-eyed Goldfish', 'Its telescope eyes see poorly, but look magnificent.',
     fish_disp(gui_scale=1.0))
def b_pop_eyed_goldfish(it):
    pal = P('#202024', '#3a3a40', '#5a5058')
    F = Fish(it, 4.5, 12.4, 6.4, 1.8, pal, None, peak=0.45, tailf=0.35, nosef=0.6, scales=0.08, mouth=0.4)
    fp = finpal('#2a2a30', tip='#6a6470', a=225)
    F.tail(['oo##....', '.o###+..', '..o##++.', '...o#++.', '...o#++.', '..o##++.', '.o###+..', 'oo##....'], fp)
    F.dorsal(0.4, ['.o#o', 'o#+#'], fp)
    F.anal(0.35, ['#+#', 'o#o'], fp)
    F.pect(0.8, ['o#+'], fp)
    # the telescope eyes: short cylinders sticking out on both sides, a dark ring and a gleaming lens
    ey = F.mid(0.88) + 0.8
    ex = F.x(0.86)
    for sgn in (1, -1):
        z0 = 8 + sgn * F.hw(0.86)
        a, b = sorted((z0, z0 + sgn * 1.6))
        it.wbox((ex - 0.8, ey - 0.8, a), (ex + 0.8, ey + 0.8, b),
                lambda face, x, y, z, f, s=sgn: (hx('#e8e0d0') if (face == 'south' and s > 0 or face == 'north' and s < 0)
                                                 and abs(x - ex) < 0.4 and abs(y - ey) < 0.4 else
                                                 BLACK if face in ('south', 'north') else hx('#3a3a40')),
                density=3, frame=False, name='eye')


@reg('fish_ranchu_goldfish', 'Ranchu Goldfish', 'No back fin, a round belly and a raspberry crown.',
     fish_disp(gui_scale=1.0))
def b_ranchu(it):
    pal = P('#e83a1a', '#f4602a', '#fbe6d0')
    white = hx('#fbf4ea')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south', 'up') and 7.0 <= x < 10.0 and t < 0.65:
            return white
    F = Fish(it, 4.5, 12.2, 7.0, 2.0, pal, pat, peak=0.45, tailf=0.3, nosef=0.65, belly=0.55, scales=0.06,
             mouth=0.4)
    fp = finpal('#f4602a', tip='#ffd0b0', a=230)
    F.tail(['oo#...', '.o##+.', '..o#++', '..o#++', '.o##+.', 'oo#...'], fp)        # short twin tail, no dorsal
    F.anal(0.3, ['#+', 'o#'], fp)
    F.pect(0.78, ['o#'], fp)
    # the wen: bumpy hood over the head
    hood = lambda face, x, y, z, f: hx('#f8a050') if (int(x * 2) + int(y * 2) + int(z * 2)) % 2 else hx('#e8702a')
    it.wbox((F.x(0.78), F.top(0.86) - 0.6, 6.6), (F.x(1.0) + 0.3, F.top(0.86) + 0.7, 9.4), hood, density=2, name='wen')
    F.eye('#e8c060', t=0.5, dx=1.1)


@reg('fish_killifish', 'Killifish', 'Tiny and tough; some live in puddles that dry up.', fish_disp(gui_scale=1.05))
def b_killifish(it):
    pal = P('#7a7448', '#c8c08a', '#f2ecd0')
    F = Fish(it, 4.5, 12.0, 3.4, 0.8, pal, spots_pattern('#5a7ab0', density=6, tmin=0.4, tmax=0.8), peak=0.5,
             tailf=0.35, mouth=0.5, mouth_t=0.6)
    fp = finpal('#d8c890', tip='#f4e8b8', a=220)
    F.tail(['o#.', 'o#+', 'o#+', 'o#.'], fp)
    F.dorsal(0.25, ['o#'], fp)
    F.anal(0.25, ['#+#', 'o#o'], fp)
    F.eye('#b8d0e8', size=1.0, dx=1.1)


@reg('fish_crawfish', 'Crawfish', 'Walks backward faster than it walks forward.', fish_disp(yaw=0, tilt=60, roll=90, gui_scale=0.8))
def b_crawfish(it):
    crayfish(it, '#9c2818', '#4a1008', '#c84a2a', '#b8301c', '#e05a3a')


@reg('fish_soft_shelled_turtle', 'Soft-shelled Turtle', 'Leathery shell, snorkel nose, quick temper.', turtle_disp())
def b_softshell(it):
    turtle(it, '#7a7a4a', '#4e4e2c', '#8a8a5a', '#5a5a34', '#e8e0c8', head_len=2.4, snout='#6a6a40', soft=True,
           tail_len=1.5)


@reg('fish_snapping_turtle', 'Snapping Turtle', 'Hunts at night with a hooked beak. Keep fingers clear.', turtle_disp())
def b_snapper(it):
    turtle(it, '#4a4230', '#2a2418', '#6a6048', '#3a3424', '#c8b890', head_len=3.0, ridges=True, tail_len=4.0,
           beak='#3a3020')


@reg('fish_tadpole', 'Tadpole', 'A frog in the making, all head and tail.', fish_disp(gui_scale=1.05))
def b_tadpole(it):
    body, light = hx('#3a3424'), hx('#6a5e42')

    def m(face, x, y, z, f):
        if face == 'down':
            return hx('#8a806a')
        return light if (int(x * 2) + int(y * 2)) % 5 == 0 else body
    it.wbox((9.5, 6.0, 6.4), (14.0, 9.6, 9.6), m, name='body')
    it.wbox((10.2, 5.6, 6.8), (13.4, 6.0, 9.2), m, name='belly')
    # the tail: a muscular root and a wide see-through fin around it, pale at the edge
    fp = finpal('#7a6e50', tip='#cfc4a0', a=225)
    it.wbox((5.0, 7.0, 7.4), (9.8, 8.6, 8.6), m, name='tail_root')
    fin(it, 0.6, 5.4, ['..ooooo..', '.o#####o.', 'o###+###o', 'o##+++##o', 'o###+###o', '.o#####o.', '..ooooo..'], fp)
    for sgn in (1, -1):
        it.wbox((12.4, 8.2, 8 + sgn * 1.55 - 0.3), (13.2, 9.0, 8 + sgn * 1.55 + 0.3),
                lambda face, x, y, z, f: BLACK if face != 'down' else body, frame=False, name='eye')


@reg('fish_frog', 'Frog', 'Sings loudest after rain.', upright_disp(gui_scale=0.85, yaw=210, tilt=10))
def b_frog(it):
    green, dark, belly = hx('#5a8a3a'), hx('#2e4a1e'), hx('#e8e0a0')

    def skin(face, x, y, z, f):
        if face == 'down':
            return belly
        if (int(x * 1.3) * 5 + int(y * 1.3) * 3 + int(z * 1.3) * 7) % 11 == 0:
            return dark
        return green if face != 'south' or y > 6.4 else belly
    # crouched body sloping up toward the head (+x), front legs, folded back legs
    it.wbox((5.0, 4.5, 5.4), (11.0, 8.2, 10.6), skin, rot={'origin': (8, 4.5, 8), 'z': 12}, name='body')
    it.wbox((10.0, 6.8, 5.8), (13.8, 9.6, 10.2), skin, name='head')
    it.wbox((13.6, 7.0, 6.4), (14.2, 8.4, 9.6), lambda *a: shade(green, 0.85), name='lip')
    for sgn in (1, -1):
        z = 8 + sgn * 1.6
        it.wbox((11.4, 9.2, z - 0.7), (12.8, 10.6, z + 0.7),
                lambda face, x, y, z_, f: (hx('#d8b030') if face in ('up', 'east') else BLACK) if face != 'down' else dark,
                density=3, frame=False, name='eye')
        it.wbox((3.6, 3.6, 8 + sgn * 2.6 - 0.9), (8.4, 6.0, 8 + sgn * 2.6 + 0.9), skin, name='thigh')
        it.wbox((5.6, 2.6, 8 + sgn * 3.0 - 0.8), (10.2, 3.6, 8 + sgn * 3.0 + 0.8), skin, name='foot')
        it.wbox((11.0, 2.6, 8 + sgn * 1.8 - 0.5), (12.0, 6.6, 8 + sgn * 1.8 + 0.5), skin, name='arm')
        it.wbox((11.0, 2.6, 8 + sgn * 1.8 - 0.8), (13.0, 3.2, 8 + sgn * 1.8 + 0.8), skin, name='hand')


@reg('fish_freshwater_goby', 'Freshwater Goby', 'Clings to stones with its belly fins.', fish_disp(gui_scale=1.0))
def b_goby(it):
    pal = P('#5a4a34', '#a08868', '#e2d6bc')
    F = Fish(it, 3.0, 13.5, 4.0, 1.4, pal, spots_pattern('#3a2e20', density=4, tmin=0.3, tmax=0.95), peak=0.75,
             tailf=0.4, nosef=0.7, belly=0.4, mouth=1.0, mouth_t=0.35)
    fp = finpal('#7a6648', tip='#c8b48a')
    F.tail(['o#.', 'o#+', 'o#+', 'o#+', 'o#.'], fp)
    F.dorsal(0.62, ['.o', 'o#', '#+'], fp)
    F.dorsal(0.3, ['o#o#', '#+#+'], fp)
    F.anal(0.3, ['#+#+', 'o#o#'], fp)
    F.pect(0.75, ['oo#', 'o##'], fp, ang=40)
    F.eye('#d8c890', t=0.75, size=1.0, dx=1.6)


@reg('fish_loach', 'Loach', 'Feels the weather change and wriggles before storms.', fish_disp(gui_scale=1.05))
def b_loach(it):
    pal = {'back': hx('#6a5a3a'), 'side': hx('#a08c62'), 'belly': hx('#e0d4b0')}

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south', 'up') and (int(x * 1.5) * 3 + int(y * 2)) % 5 == 0 and t > 0.35:
            return hx('#3e3220')
    prof = lambda u: (1.4 + 0.9 * min(1.0, u * 2.0), 1.0 + 0.5 * min(1.0, u * 2.0))
    yc, sl = eel(it, 2.0, 14.5, 7, prof, 0.6, 14.0, pal, pattern=pat, phase=0.2)
    fin(it, 0.8, yc(2.0) - 1.4, ['oo', '#+', '#+', 'oo'], finpal('#7a6a48', tip='#b8a47a'))
    eye(it, 13.8, yc(13.8) + 0.4, 0.9, hx('#d8c88a'), size=0.6)
    for k in range(3):
        for sgn in (1, -1):
            z = 8 + sgn * (0.3 + 0.25 * k)
            it.wbox((14.3, yc(14.4) - 0.5 - 0.2 * k, z - 0.1), (15.6 - 0.3 * k, yc(14.4) - 0.3 - 0.2 * k, z + 0.1),
                    lambda *a: hx('#c8b080'), rot={'origin': (14.4, yc(14.4) - 0.4, z), 'z': -30 - 12 * k})


@reg('fish_giant_snakehead', 'Giant Snakehead', 'Breathes air and guards its young fiercely.')
def b_snakehead(it):
    pal = P('#1e2a24', '#4a5a48', '#c8c8b0', mouth='#101410')
    pat = combine(stripe('#141c18', 0.46, 0.58, x_max=14.6), spots_pattern('#6a7a62', density=7, tmin=0.6, tmax=0.95))
    F = Fish(it, 1.0, 15.6, 4.4, 1.4, pal, pat, peak=0.7, tailf=0.5, nosef=0.6, mouth=1.5, mouth_t=0.35, segs=9)
    fp = finpal('#182420', tip='#3c5ab0')
    F.tail(['oo#.', 'o##+', 'o##+', 'o##+', 'oo#.'], fp)
    F.dorsal(0.08, ['o#o#o#o#o#o#', '#+#+#+#+#+#+'], fp)            # the long back fin
    F.anal(0.08, ['#+#+#+#+#', 'o#o#o#o#o'], fp)
    F.pect(0.82, ['o#+', '.o#'], fp)
    F.eye('#c84a1a', t=0.68, size=0.9, dx=1.4)


@reg('fish_bluegill', 'Bluegill', 'A dark blue flap behind the gills gives it the name.')
def b_bluegill(it):
    pal = P('#3a4a3a', '#7a8a6a', '#e8a04a')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south'):
            if 11.2 <= x < 12.4 and 0.55 < t < 0.8:
                return hx('#141e3a')                                 # the ear flap
            if t > 0.3 and int(math.floor(x)) in (5, 7, 9) and x < 11:
                return shade(c, 0.78)
            if t < 0.35 and x > 9:
                return hx('#f08a3a')
    F = Fish(it, 3.0, 13.6, 7.6, 1.2, pal, pat, peak=0.5, tailf=0.28, nosef=0.45, mouth=0.6)
    fp = finpal('#3a4a40', tip='#6a7a6a')
    F.tail('fork_s', fp)
    F.dorsal(0.2, ['..o.o.o', '.o#o#o#', 'o#+#+#+'], fp)
    F.anal(0.2, ['#+#+', 'o#o#'], fp)
    F.pect(0.78, ['o#+', '.o#'], finpal('#8a9a7a', tip='#c8d0b0'))
    F.eye('#2a3a6a', size=0.9)


@reg('fish_tilapia', 'Tilapia', 'Raises its young in its mouth.')
def b_tilapia(it):
    pal = P('#4a5450', '#9aa49c', '#e8e6dc')
    pat = combine(bands_pattern([4.5, 6.5, 8.5, 10.5], '#6e7a72', width=0.8, tmin=0.3),
                  stripe('#c8a8a0', 0.12, 0.26, x_min=8))
    F = Fish(it, 2.5, 14.2, 6.6, 1.3, pal, pat, peak=0.5, mouth=0.8)
    fp = finpal('#5a6460', tip='#d0605a')
    F.tail(['oo#.', 'o##+', 'o##+', 'o##+', 'o##+', 'oo#.'], fp)
    F.dorsal(0.15, ['..o.o.o.o', '.o#o#o#o#', 'o#+#+#+#+'], fp)
    F.anal(0.2, ['#+#+', 'o#o#'], fp)
    F.pect(0.78, ['o#+', '.o#'], fp)
    F.eye('#d8b050')


@reg('fish_pond_smelt', 'Pond Smelt', 'Caught through the ice in winter.', fish_disp(gui_scale=1.0))
def b_smelt(it):
    pal = {'back': hx('#7a9a8a'), 'side': alpha('#c8dcd4', 230), 'belly': alpha('#eef4f0', 220), 'dark': hx('#5a7a6a')}
    F = Fish(it, 3.0, 13.6, 3.0, 0.8, pal, stripe('#d8e8e4', 0.45, 0.6, x_max=12), peak=0.55, tailf=0.3, mouth=0.9)
    fp = finpal('#b8d0c8', tip='#e8f4f0', a=200)
    F.tail('fork_s', fp)
    F.dorsal(0.45, ['.o', 'o#'], fp)
    F.dorsal(0.15, ['o'], fp)                                       # adipose
    F.anal(0.25, ['#+#', 'o#o'], fp)
    F.eye('#e8f0f0', size=0.95, dx=1.1)


@reg('fish_guppy', 'Guppy', 'Males wear tails of every colour.', fish_disp(gui_scale=1.1))
def b_guppy(it):
    pal = P('#7a7a68', '#b8b49a', '#e8e4d0')
    F = Fish(it, 6.5, 12.0, 2.6, 0.7, pal, spots_pattern('#3aa0d8', density=3, tmin=0.3, tmax=0.7, x_max=9), peak=0.5,
             tailf=0.45, mouth=0.4, mouth_t=0.6)
    orange, blue = hx('#f07a1a'), hx('#2a6ad8')

    def tp(x, y, w, h, f):
        if (x - 0) ** 2 / 25 + (y - h / 2) ** 2 / ((h / 2) ** 2) > 1.15 and x > 2:
            return None
        if (x + y) % 4 == 0:
            return BLACK
        return orange if x < 3 else blue
    it.card((2.0, 5.0, 8.0), (7.0, 11.0, 8.0), ik_func(tp), name='tail')
    F.dorsal(0.35, ['o#o', '#+#'], finpal('#f07a1a', tip='#ffc040'))
    F.eye('#e8e0b0', size=0.9, dx=0.9)


def ik_func(fn):
    import ik
    return ik.p_func(fn)


@reg('fish_neon_tetra', 'Neon Tetra', 'Its blue stripe glows in dark jungle water.', fish_disp(gui_scale=1.1))
def b_neon(it):
    pal = P('#4a5a5a', '#b8c0bc', '#e8ecea')
    pat = combine(stripe('#20c8ff', 0.5, 0.68, x_max=13.0), stripe('#e82a2a', 0.15, 0.45, x_max=9.0))
    F = Fish(it, 4.5, 12.4, 3.0, 0.75, pal, pat, peak=0.55, tailf=0.32, mouth=0.4)
    fp = finpal('#c8d0d0', tip='#f0f4f4', a=170)
    F.tail('fork_s', fp)
    F.dorsal(0.45, ['o#'], fp)
    F.anal(0.3, ['#+', 'o#'], fp)
    F.eye('#c8d8e0', size=1.0, dx=1.0)


@reg('fish_mitten_crab', 'Mitten Crab', 'Furry claws, as if it wore mittens.', crab_disp(gui_scale=0.9))
def b_mitten_crab(it):
    crab(it, '#6a5a34', '#2e2614', '#c8b88a', '#4e4226', fur='#a08850', legs='#5a4a2a', width=7.0)


@reg('fish_sweetfish', 'Sweetfish (Ayu)', 'Its flesh smells faintly of melon.')
def b_ayu(it):
    pal = P('#4a5a34', '#b0b47a', '#f2eedc')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south') and 10.8 <= x < 12.0 and 0.5 < t < 0.7:
            return hx('#f2c83a')                                     # the yellow shoulder spot
    F = Fish(it, 2.5, 14.5, 4.2, 1.0, pal, pat, bands=(0.3, 0.66))
    fp = finpal('#8a8a5a', tip='#e8c860')
    F.tail('fork', fp)
    F.dorsal(0.45, ['.o#', 'o#+', '#+#'], fp)
    F.dorsal(0.18, ['o'], fp)
    F.anal(0.25, ['#+', 'o#'], finpal('#d8b048', tip='#f4d878'))
    F.pect(0.8, ['o#'], fp)
    F.eye('#d8d0a0', size=0.85)


@reg('fish_cherry_salmon', 'Cherry Salmon', 'Runs upriver when the cherry trees bloom.')
def b_cherry_salmon(it):
    pal = P('#3a4430', '#a08a7a', '#f2e4dc')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south'):
            if 0.42 < t < 0.72 and int(math.floor(x / 1.6)) % 2 == 0 and x < 12.5:
                return hx('#4a3a3a')                                # parr bars
            if 0.25 < t < 0.42:
                return hx('#e88a9a')                                # the cherry blush
    F = Fish(it, 2.5, 14.8, 4.8, 1.2, pal, pat, mouth=1.1)
    fp = finpal('#5a5a48', tip='#9a8a7a')
    F.tail('fork', fp)
    F.dorsal(0.45, ['.o#', 'o#+'], fp)
    F.dorsal(0.18, ['o'], fp)
    F.anal(0.25, ['#+', 'o#'], fp)
    F.pect(0.8, ['o#+'], fp)
    F.eye('#d8c8a0', size=0.9)


@reg('fish_char', 'Char', 'Lives in the coldest lakes; pale spots on a dark back.')
def b_char(it):
    pal = P('#2e4232', '#5a6e52', '#e8763a')
    pat = spots_pattern('#f0c890', density=5, tmin=0.4, tmax=0.95, x_max=13.5)
    F = Fish(it, 2.5, 14.8, 4.8, 1.2, pal, pat, bands=(0.3, 0.6), mouth=1.1)
    fp = finpal('#c84a2a', tip='#f4f0e8')                       # white leading edges
    F.tail('fork_s', finpal('#3a4a36', tip='#7a8a6a'))
    F.dorsal(0.45, ['.o#', 'o#+'], finpal('#3a4a36', tip='#7a8a6a'))
    F.dorsal(0.18, ['o'], finpal('#3a4a36'))
    F.anal(0.25, ['#+', 'o#'], fp)
    F.pect(0.8, ['o#+'], fp)
    F.eye('#d8c890', size=0.9)


@reg('fish_golden_trout', 'Golden Trout', 'Found only in high, cold mountain streams.')
def b_golden_trout(it):
    pal = P('#7a7a2a', '#f2c83a', '#f8dca0')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south'):
            if 0.3 < t < 0.42:
                return hx('#e8483a')                                # the red stripe
            if 0.45 < t < 0.78 and int(math.floor(x / 1.5)) % 2 == 0 and x < 12.5:
                return hx('#a8a040')                                # olive parr marks
            if t > 0.78 and (int(x) * 3 + int(y)) % 4 == 0:
                return hx('#3a3a14')
    F = Fish(it, 2.5, 14.8, 4.8, 1.2, pal, pat, bands=(0.28, 0.75), mouth=1.0)
    fp = finpal('#e8a83a', tip='#ffffff')
    F.tail('fork', finpal('#c8a83a', tip='#f8e098'))
    F.dorsal(0.45, ['.o#', 'o#+'], fp)
    F.dorsal(0.18, ['o'], fp)
    F.anal(0.25, ['#+', 'o#'], fp)
    F.pect(0.8, ['o#+'], fp)
    F.eye('#e8c850', size=0.9)


@reg('fish_stringfish', 'Stringfish (Taimen)', 'The giant salmon of Siberian rivers.', fish_disp(gui_scale=0.8))
def b_taimen(it):
    pal = P('#3a3c34', '#9a7a62', '#e8dcc8')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south', 'up') and t > 0.4 and x < 14:
            ix, iy = int(math.floor(x * 1.2)), int(math.floor(y * 1.2))
            if (ix + iy) % 3 == 0 and (ix * 5 + iy) % 2 == 0:
                return hx('#24221e')                                # dark x-spots
        if face in ('north', 'south') and x < 5.5:
            return mix(c, hx('#b84a32'), 0.55)                       # the reddish tail end
    F = Fish(it, 1.0, 15.4, 4.6, 1.4, pal, pat, peak=0.6, tailf=0.3, nosef=0.5, bands=(0.3, 0.62), mouth=1.6, segs=9)
    fp = finpal('#b84a32', tip='#e07a5a')
    F.tail('fork', fp)
    F.dorsal(0.5, ['.o#', 'o#+', '#+#'], finpal('#4a4a40', tip='#8a7a62'))
    F.dorsal(0.2, ['o'], finpal('#4a4a40'))
    F.anal(0.25, ['#+', 'o#'], fp)
    F.pect(0.8, ['o#+', '.o#'], fp)
    F.eye('#c8a860', size=0.9, dx=1.5)


@reg('fish_salmon', 'Atlantic Salmon', 'Leaps waterfalls to spawn where it was born.')
def b_salmon(it):
    pal = P('#3a4a5a', '#b8c0c8', '#f4f4f0')
    F = Fish(it, 1.8, 15.0, 5.2, 1.4, pal, spots_pattern('#1e2830', density=6, tmin=0.55, tmax=1.0, x_max=13.5),
             bands=(0.3, 0.66), mouth=1.2)
    fp = finpal('#4a5868', tip='#8a98a8')
    F.tail('fork_s', fp, s=1.1)
    F.dorsal(0.45, ['.o#', 'o#+', '#+#'], fp)
    F.dorsal(0.18, ['o'], fp)
    F.anal(0.22, ['#+', 'o#'], fp)
    F.pect(0.8, ['o#+'], fp)
    F.eye('#d8d8c8', size=0.9)


@reg('fish_king_salmon', 'King Salmon', 'The biggest of the Pacific salmon.', fish_disp(gui_scale=0.8))
def b_king_salmon(it):
    pal = P('#3a3438', '#8a5a54', '#e8d8cc', mouth='#101010')        # black gums: the "blackmouth"
    pat = combine(spots_pattern('#1a1618', density=4, tmin=0.6, tmax=1.0), stripe('#a0625a', 0.35, 0.5, x_max=13))
    F = Fish(it, 1.0, 15.4, 5.8, 1.6, pal, pat, peak=0.5, tailf=0.28, bands=(0.3, 0.6), mouth=1.6, segs=9)
    fp = finpal('#3a3438', tip='#6a5a58')
    F.tail(['o#*..', 'o##+.', 'o*#++', '.##++', 'o#*++', 'o##+.', 'o*#..'], dict(fp, **{'*': hx('#141012')}), s=1.1)
    F.dorsal(0.45, ['.o#', 'o#+', '#+#'], fp)
    F.dorsal(0.18, ['o'], fp)
    F.anal(0.22, ['#+', 'o#'], fp)
    F.pect(0.8, ['o#+', '.o#'], fp)
    F.eye('#c8b8a0', size=0.9, dx=1.6)


@reg('fish_nibble_fish', 'Nibble Fish', 'Nibbles dead skin off your feet, if you let it.', fish_disp(gui_scale=1.0))
def b_garra(it):
    pal = P('#5a5a50', '#9a988a', '#dedcd0')
    F = Fish(it, 3.0, 13.4, 3.4, 1.0, pal, stripe('#4a4a44', 0.45, 0.55, x_max=12), peak=0.6, belly=0.4,
             mouth=0.6, mouth_t=0.1)
    fp = finpal('#7a786a', tip='#b8b6a8')
    F.tail('fork_s', fp)
    F.dorsal(0.45, ['.o', 'o#'], fp)
    F.anal(0.25, ['#+', 'o.'], fp)
    F.pect(0.78, ['o#'], fp, t=0.15)
    F.eye('#c8c8b8', t=0.68, size=0.8, dx=1.2)
    # the sucker disc under the mouth
    it.wbox((F.x(0.86), F.bot(0.9) - 0.3, 7.4), (F.x(0.98), F.bot(0.9) + 0.05, 8.6), lambda *a: hx('#c8b8a0'), name='disc')


@reg('fish_angelfish', 'Angelfish', 'Glides through flooded forests on tall fins.', fish_disp(gui_scale=0.85, roll=25))
def b_angelfish(it):
    pal = P('#9a9890', '#e2e0d8', '#f4f2ea')
    pat = bands_pattern([7.0, 10.0, 12.6], '#1e1e22', width=0.9)
    F = Fish(it, 5.5, 13.0, 6.4, 0.7, pal, pat, peak=0.5, tailf=0.35, nosef=0.45, mouth=0.4)
    fp = finpal('#d8d6cc', tip='#1e1e22', a=220)
    fin(it, 5.0, F.top(0.5) - 0.5, ['....o', '...o#', '..o##', '.o###', 'o####', '#####', '+####'], fp)  # tall dorsal
    fin(it, 5.0, F.bot(0.5) - 6.5, ['+####', '#####', 'o####', '.o###', '..o##', '...o#', '....o'], fp)  # tall anal
    for sgn in (1, -1):                                              # trailing pelvic threads
        it.wbox((F.x(0.7), F.bot(0.7) - 4.5, 8 + sgn * 0.4 - 0.1), (F.x(0.7) + 0.3, F.bot(0.7), 8 + sgn * 0.4 + 0.1),
                lambda *a: hx('#e8e6dc'))
    F.tail(['oo#.', 'o##+', 'o##+', 'o##+', 'oo#.'], fp)
    F.eye('#c83a2a', size=0.9, dx=1.2)


@reg('fish_betta', 'Betta', 'A fighting fish in a ball gown of fins.', fish_disp(gui_scale=0.95))
def b_betta(it):
    pal = P('#2a1a6a', '#4a2aa8', '#6a3ab8')
    F = Fish(it, 6.0, 12.6, 3.2, 0.8, pal, spots_pattern('#6aa8ff', density=3, tmin=0.3, tmax=0.9), peak=0.55,
             tailf=0.4, mouth=0.5, mouth_t=0.55)
    red = finpal('#c81e3a', tip='#3a5aff', a=235)
    red['o'] = hx('#4a7aff')
    fin(it, 1.0, 4.0, ['oo#.....', 'o##+#...', 'o#+#++#.', '.o#++#++', '.o#++#++', 'o#+#++#.', 'o##+#...', 'oo#.....'], red)
    fin(it, 5.0, F.top(0.4) - 0.5, ['....oo', '..oo##', 'oo##+#', '##+#++'], red)
    fin(it, 5.0, F.bot(0.4) - 4.5, ['##+#++', 'oo##+#', '..oo##', '....oo'], red)
    F.pect(0.8, ['o#'], red)
    F.eye('#e8c040', size=0.8, dx=1.0)


@reg('fish_rainbowfish', 'Rainbowfish', 'Blue up front, orange behind, brighter when happy.', fish_disp(gui_scale=1.0))
def b_rainbowfish(it):
    pal = P('#3a4a8a', '#5a7ac8', '#c8d0e8')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south', 'up') and x < 8.5:
            return mix(hx('#f07a1a'), hx('#ffb040'), max(0.0, min(1.0, t)))
        if face in ('north', 'south') and 0.4 < t < 0.5:
            return shade(c, 0.8)
    F = Fish(it, 3.5, 13.0, 5.4, 0.9, pal, pat, peak=0.55, tailf=0.3, nosef=0.38, mouth=0.4, mouth_t=0.6)
    fp = finpal('#f07a1a', tip='#ffd070')
    F.tail('fork_s', fp)
    F.dorsal(0.55, ['.o', 'o#'], finpal('#3a4a8a', tip='#8aa0e8'))
    F.dorsal(0.2, ['o#o#', '#+#+'], fp)
    F.anal(0.2, ['#+#+#', 'o#o#o'], fp)
    F.eye('#e8e8f0', size=0.95, dx=1.0)


@reg('fish_arowana', 'Arowana', 'Jumps from the water to snatch insects off branches.', fish_disp(gui_scale=0.85))
def b_arowana(it):
    pal = P('#5a6a6a', '#c8d0cc', '#eef0ea')

    def scales(face, x, y, z, t, c, f):
        if face in ('north', 'south') and 0.15 < t < 0.9 and x < 14:
            ix, iy = int(math.floor(x / 1.4)), int(math.floor(y / 1.1))
            if (x % 1.4) < 0.3 or (y % 1.1) < 0.2:
                return shade(c, 0.86)
            if (ix + iy) % 3 == 0:
                return hx('#e8d8a8')
    F = Fish(it, 1.0, 15.4, 4.4, 1.0, pal, scales, peak=0.45, tailf=0.35, nosef=0.6, belly=0.6, mouth=1.6,
             mouth_t=0.85, segs=9)
    fp = finpal('#7a8a88', tip='#c8d0cc')
    F.tail(['oo.', 'o#+', 'o#+', 'o#+', 'oo.'], fp)
    F.dorsal(0.08, ['o#o#o#', '#+#+#+'], fp)                        # long fins set far back
    F.anal(0.06, ['#+#+#+#', 'o#o#o#o', '.o.o.o.'], fp)
    F.pect(0.82, ['oo#+', '.o##'], fp)
    F.eye('#e8d8a0', t=0.72, size=0.9, dx=1.6)
    for sgn in (1, -1):                                             # the chin barbels, pointing forward and up
        z = 8 + sgn * 0.4
        y = F.B.top(15.2) - 0.2
        it.wbox((15.2, y - 0.12, z - 0.12), (17.2, y + 0.12, z + 0.12), lambda *a: hx('#c8c8b8'),
                rot={'origin': (15.2, y, z), 'z': 22})


@reg('fish_dorado', 'Dorado', 'The golden tiger of South American rivers.', fish_disp(gui_scale=0.85))
def b_dorado(it):
    pal = P('#b07a14', '#f0b82a', '#f8e0a0')

    def lines(face, x, y, z, t, c, f):
        if face in ('north', 'south') and t > 0.25 and x < 14 and int(math.floor(y * 1.6)) % 2 == 0 and \
                int(math.floor(x * 2)) % 3 == 0:
            return hx('#5a3a0a')
    F = Fish(it, 1.5, 15.2, 5.2, 1.3, pal, lines, bands=(0.28, 0.7), mouth=1.6, segs=9)
    fp = finpal('#e8501a', tip='#ff8a3a')
    F.tail(['o#*..', 'o##+.', '.##++', '..#++', '.##++', 'o##+.', 'o#*..'], dict(fp, **{'*': hx('#2a1a0a')}))
    F.dorsal(0.45, ['.o#', 'o#+', '#+#'], fp)
    F.dorsal(0.18, ['o'], fp)
    F.anal(0.22, ['#+#', 'o#o'], fp)
    F.pect(0.8, ['o#+', '.o#'], fp)
    F.eye('#e8c040', size=0.9, dx=1.5)


@reg('fish_gar', 'Gar', 'A living fossil with a needle-toothed beak.', fish_disp(gui_scale=0.95))
def b_gar(it):
    pal = P('#4a5030', '#9a9a62', '#e8e4c8')
    F = Fish(it, 1.5, 12.0, 3.0, 0.9, pal, spots_pattern('#2e3218', density=5, tmin=0.3, tmax=1.0), peak=0.4,
             tailf=0.55, nosef=0.75, mouth=None)
    beak = lambda face, x, y, z, f: hx('#6a6a40') if face != 'down' else hx('#c8c4a0')
    it.wbox((11.8, 7.4, 7.6), (17.4, 8.2, 8.4), beak, name='beak')
    it.wbox((12.4, 7.25, 7.7), (17.0, 7.45, 8.3), lambda face, x, y, z, f: WHITE if int(math.floor(x * 3)) % 2 == 0 else None,
            density=3, frame=False, name='teeth')
    fp = finpal('#5a5a34', tip='#8a8a50')
    F.tail(['.oo.', 'o##+', 'o##+', 'o##+', '.oo.'], fp)
    F.dorsal(0.12, ['.o', 'o#', '#+'], fp)
    F.anal(0.12, ['#+', 'o#', '.o'], fp)
    F.pect(0.82, ['o#'], fp)
    F.eye('#d8c890', t=0.62, size=0.75, dx=0.8)


@reg('fish_arapaima', 'Arapaima', 'A giant that gulps air at the surface; its tail glows red.', fish_disp(gui_scale=0.8))
def b_arapaima(it):
    pal = P('#3a4038', '#6a7464', '#c8c8b4')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south', 'up') and x < 9.0:
            k = max(0.0, min(1.0, (9.0 - x) / 6.0))
            c2 = mix(c, hx('#b8281e'), 0.35 + 0.55 * k)
            if (int(math.floor(x)) + int(math.floor(y))) % 2 == 0:
                return shade(c2, 0.85)
            return c2
    F = Fish(it, 1.0, 15.6, 4.8, 1.5, pal, pat, peak=0.55, tailf=0.42, nosef=0.55, belly=0.45, mouth=1.4, mouth_t=0.6,
             segs=9)
    fp = finpal('#8a2418', tip='#c8483a')
    F.tail(['.oo.', 'o##+', 'o##+', 'o##+', 'o##+', '.oo.'], fp)
    F.dorsal(0.05, ['o#o#o#', '#+#+#+'], fp)
    F.anal(0.05, ['#+#+#+', 'o#o#o#'], fp)
    F.pect(0.82, ['o#+', '.o#'], finpal('#4a5048', tip='#7a8474'))
    F.eye('#c8b870', t=0.62, size=0.85, dx=1.4)


@reg('fish_saddled_bichir', 'Saddled Bichir', 'Has lungs, and a row of little flags down its back.',
     fish_disp(gui_scale=0.95))
def b_bichir(it):
    pal = P('#6a5a3a', '#a08a5a', '#e2d6b4')
    pat = bands_pattern([3.0, 5.6, 8.2, 10.8], '#2e2418', width=1.2, tmin=0.25)
    F = Fish(it, 1.5, 14.6, 3.2, 1.0, pal, pat, peak=0.7, tailf=0.6, nosef=0.65, mouth=1.0, segs=9)
    fp = finpal('#5a4a30', tip='#c8a868')
    for u in (0.15, 0.28, 0.41, 0.54, 0.67):                          # the dorsal finlets
        F.dorsal(u, ['o#', '.#'], fp)
    F.tail(['.oo', 'o#+', 'o#+', 'o#+', '.oo'], fp)
    F.pect(0.82, ['oo#', '.##'], fp, ang=40)
    F.eye('#c8b070', t=0.7, size=0.8, dx=1.2)


@reg('fish_sturgeon', 'Sturgeon', 'Older than the dinosaurs; armour plates instead of scales.', fish_disp(gui_scale=0.8))
def b_sturgeon(it):
    pal = P('#3e4448', '#7a8288', '#dcdcd8')
    F = Fish(it, 2.0, 14.2, 3.8, 1.3, pal, None, peak=0.55, tailf=0.35, nosef=0.45, belly=0.45, mouth=None, segs=9)
    scute = lambda face, x, y, z, f: hx('#d8d4c4') if face in ('up', 'south', 'north') else hx('#9a968a')
    for u in (0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8):                     # bony scutes: back row and side rows
        x = F.x(u)
        it.wbox((x - 0.35, F.top(u) - 0.2, 7.65), (x + 0.35, F.top(u) + 0.4, 8.35), scute, name='scute')
        for sgn in (1, -1):
            z = 8 + sgn * F.hw(u)
            a, b = sorted((z, z + sgn * 0.35))
            it.wbox((x - 0.3, F.mid(u) - 0.3, a), (x + 0.3, F.mid(u) + 0.3, b), scute, name='scute')
    snout = lambda face, x, y, z, f: hx('#5a6064') if face != 'down' else hx('#b8b8b0')
    it.wbox((14.0, 7.4, 7.4), (16.8, 8.4, 8.6), snout, rot={'origin': (14.0, 7.9, 8), 'z': 6}, name='snout')
    for sgn in (1, -1):
        for bx in (14.6, 15.2):
            it.wbox((bx - 0.1, 6.6, 8 + sgn * 0.3 - 0.1), (bx + 0.1, 7.5, 8 + sgn * 0.3 + 0.1), lambda *a: hx('#c8c4b4'))
    fp = finpal('#4a5054', tip='#7a8288')
    tail(it, ['o.....', '.o#...', '..##..', '...##.', '...+##', '..o#+.', '.o#...'], 2.6, F.mid(0.0) + 1.2, fp)
    F.dorsal(0.18, ['.o', 'o#'], fp)
    F.anal(0.2, ['#+', 'o#'], fp)
    F.pect(0.8, ['oo#', '.o#'], fp)
    F.eye('#c8c8b8', t=0.65, size=0.6, dx=1.8)


@reg('fish_black_bass', 'Black Bass', 'Ambushes anything that fits in its huge mouth.')
def b_black_bass(it):
    pal = P('#3a4a24', '#8a9a5a', '#ece8cc', mouth='#20280e')

    def pat(face, x, y, z, t, c, f):
        if face in ('north', 'south') and 0.42 < t < 0.62 and x < 13.5 and (int(math.floor(x * 1.3)) % 3 != 0):
            return hx('#26300e')                                     # the dark blotchy side band
    F = Fish(it, 2.5, 14.8, 6.0, 1.4, pal, pat, peak=0.5, nosef=0.55, mouth=1.8, mouth_t=0.35)
    fp = finpal('#4a5a2e', tip='#8a9a5a')
    F.tail(['o#..', 'o##+', '.#++', 'o##+', 'o#..'], fp)
    F.dorsal(0.42, ['.o.o.', 'o#o#o', '#+#+#'], fp)
    F.dorsal(0.2, ['.o#', 'o#+'], fp)
    F.anal(0.25, ['#+#', 'o#o'], fp)
    F.pect(0.78, ['o#+', '.o#'], fp)
    F.eye('#c8a040', size=0.9)


@reg('fish_pike', 'Pike', 'A patient ambush hunter with a duck-bill snout.')
def b_pike(it):
    pal = P('#2e3e1e', '#6a7a3a', '#e8e4c4', mouth='#1a2010')

    def beans(face, x, y, z, t, c, f):
        if face in ('north', 'south') and 0.3 < t < 0.8 and x < 13:
            ix, iy = int(math.floor(x * 1.2)), int(math.floor(y * 1.4))
            if (ix * 2 + iy * 3) % 5 == 0:
                return hx('#d8dca0')                                 # the pale bean spots
    F = Fish(it, 1.0, 15.6, 3.6, 1.1, pal, beans, peak=0.45, tailf=0.4, nosef=0.5, bands=(0.3, 0.72), mouth=2.2,
             mouth_t=0.45, segs=9)
    fp = finpal('#4a5a2a', tip='#a8a050')
    F.tail('fork', fp)
    F.dorsal(0.12, ['.o#', 'o#+', '#+#'], fp)                       # dorsal far back
    F.anal(0.12, ['#+#', 'o#.'], fp)
    F.anal(0.45, ['o#'], fp)
    F.pect(0.8, ['o#+', '.o#'], fp)
    F.eye('#d8c860', t=0.66, size=0.9, dx=2.6)
