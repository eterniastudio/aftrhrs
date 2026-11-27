"""Element builders for the Christmas set (same conventions as arcana_items/woodwork + lighting/furnlib.py):
0..16 per block, floor y=0, front = north (-z), auto UVs that stay in 0..16 (shift, never stretch), single-axis
rotations of 0 / +-22.5 / +-45 degrees, and a coplanar-face check that must report nothing."""
import copy
import math

import numpy as np

FACES = ('north', 'east', 'south', 'west', 'up', 'down')
SIDES = ('north', 'east', 'south', 'west')


def wrap(a, b):
    if a > b:
        return tuple(reversed(wrap(b, a)))
    while a < 0:
        a, b = a + 16, b + 16
    while a >= 16:
        a, b = a - 16, b - 16
    if b > 16:
        a, b = max(0.0, a - (b - 16)), 16
    return a, b


def auto_uv(face, f, t):
    (x0, y0, z0), (x1, y1, z1) = f, t
    raw = {'north': (16 - x1, 16 - y1, 16 - x0, 16 - y0), 'east': (16 - z1, 16 - y1, 16 - z0, 16 - y0),
           'south': (x0, 16 - y1, x1, 16 - y0), 'west': (z0, 16 - y1, z1, 16 - y0),
           'up': (x0, z0, x1, z1), 'down': (x0, 16 - z1, x1, 16 - z0)}[face]
    u0, u1 = wrap(raw[0], raw[2])
    v0, v1 = wrap(raw[1], raw[3])
    return [round(u0, 4), round(v0, 4), round(u1, 4), round(v1, 4)]


def E(name, f, t, tex, faces=FACES, uv=None, rot=None, emit=0, frot=None, shade=None):
    """One cube. tex: texture variable or {face: var}; uv: one [u0,v0,u1,v1] for every face or {face: uv};
    rot: (axis, angle, origin); emit: light_emission (0..15)."""
    f = [round(float(v), 4) for v in f]
    t = [round(float(v), 4) for v in t]
    fc = {}
    for face in faces:
        var = tex.get(face) if isinstance(tex, dict) else tex
        if var is None:
            continue
        if isinstance(uv, dict):
            u = uv.get(face) or auto_uv(face, f, t)
        elif uv is not None:
            u = uv
        else:
            u = auto_uv(face, f, t)
        d = {'uv': [round(v, 4) for v in u], 'texture': '#' + var}
        if frot and face in frot:
            d['rotation'] = frot[face]
        fc[face] = d
    e = {'name': name, 'from': f, 'to': t, 'faces': fc}
    if rot:
        axis, angle, origin = rot
        e['rotation'] = {'angle': angle, 'axis': axis, 'origin': [round(float(v), 4) for v in origin]}
    if emit:
        e['light_emission'] = int(emit)
    if shade is not None:
        e['shade'] = shade
    return e


def box(name, cx, cy, cz, sx, sy, sz, tex, **kw):
    """Box by centre/size (y = bottom)."""
    return E(name, (cx - sx / 2, cy, cz - sz / 2), (cx + sx / 2, cy + sy, cz + sz / 2), tex, **kw)


def star8(name, cx, cz, y0, y1, h, tex, faces=FACES, rot_inset=(0.4, 0.4), hr=None, rot_faces=None):
    """An eight-pointed 'branch star': a square box (apothem h) plus the same box turned 45 degrees (apothem hr).
    The turned box is inset in y so no faces are coplanar."""
    hr = h if hr is None else hr
    out = []
    for nm, hh, ya, yb, fs, rot in ((name, h, y0, y1, faces, None),
                                    (name + '_d', hr, y0 + rot_inset[0], y1 - rot_inset[1], rot_faces or faces,
                                     ('y', 45, (cx, 0, cz)))):
        if 2 * hh <= 16.01:
            out.append(E(nm, (cx - hh, ya, cz - hh), (cx + hh, yb, cz + hh), tex, faces=fs, rot=rot))
            continue
        # wider than a texture: four quadrants (inner faces dropped) so no face stretches its texture
        for qx in (0, 1):
            for qz in (0, 1):
                x0, x1 = (cx - hh, cx) if qx == 0 else (cx, cx + hh)
                z0, z1 = (cz - hh, cz) if qz == 0 else (cz, cz + hh)
                inner = {'east' if qx == 0 else 'west', 'south' if qz == 0 else 'north'}
                out.append(E(f'{nm}_q{qx}{qz}', (x0, ya, z0), (x1, yb, z1), tex,
                             faces=tuple(f for f in fs if f not in inner), rot=rot))
    return out


def star_radius(theta, h, hr=None):
    """Distance from the centre to the surface of star8 at angle theta (radians, 0 = +x, pi/2 = +z)."""
    hr = h if hr is None else hr
    c, s = abs(math.cos(theta)), abs(math.sin(theta))
    rs = h / max(c, s)
    c2, s2 = abs(math.cos(theta - math.pi / 4)), abs(math.sin(theta - math.pi / 4))
    rr = hr / max(c2, s2)
    return max(rs, rr)


def shift(els, dx=0.0, dy=0.0, dz=0.0, prefix=''):
    out = []
    for e in els:
        e = copy.deepcopy(e)
        e['name'] = prefix + e['name']
        e['from'] = [round(e['from'][0] + dx, 4), round(e['from'][1] + dy, 4), round(e['from'][2] + dz, 4)]
        e['to'] = [round(e['to'][0] + dx, 4), round(e['to'][1] + dy, 4), round(e['to'][2] + dz, 4)]
        if 'rotation' in e:
            o = e['rotation']['origin']
            e['rotation']['origin'] = [round(o[0] + dx, 4), round(o[1] + dy, 4), round(o[2] + dz, 4)]
        for face, fc in e['faces'].items():
            if fc.get('_auto', True) and 'uv' in fc:
                pass
        out.append(e)
    return out


def reuv(els, keep_vars=()):
    """Recomputes auto UVs after a move (except faces whose texture variable is in keep_vars: palettes)."""
    for e in els:
        for face, fc in e['faces'].items():
            if fc['texture'][1:] not in keep_vars:
                fc['uv'] = auto_uv(face, e['from'], e['to'])
    return els


def scale_els(els, k, cx=8.0, cy=0.0, cz=8.0, keep_vars=()):
    """Scales a group about (cx, cy, cz) (palette faces keep their UVs)."""
    out = []
    for e in els:
        e = copy.deepcopy(e)
        e['from'] = [round(cx + (e['from'][0] - cx) * k, 4), round(cy + (e['from'][1] - cy) * k, 4),
                     round(cz + (e['from'][2] - cz) * k, 4)]
        e['to'] = [round(cx + (e['to'][0] - cx) * k, 4), round(cy + (e['to'][1] - cy) * k, 4),
                   round(cz + (e['to'][2] - cz) * k, 4)]
        if 'rotation' in e:
            o = e['rotation']['origin']
            e['rotation']['origin'] = [round(cx + (o[0] - cx) * k, 4), round(cy + (o[1] - cy) * k, 4),
                                       round(cz + (o[2] - cz) * k, 4)]
        out.append(e)
    return reuv(out, keep_vars)


def mirror_x(els, cx=8.0, suffix='_r'):
    out = []
    flip = {'east': 'west', 'west': 'east'}
    for e in els:
        e = copy.deepcopy(e)
        e['name'] += suffix
        f, t = e['from'], e['to']
        e['from'], e['to'] = [round(2 * cx - t[0], 4), f[1], f[2]], [round(2 * cx - f[0], 4), t[1], t[2]]
        e['faces'] = {flip.get(k, k): v for k, v in e['faces'].items()}
        if 'rotation' in e:
            r = e['rotation']
            r['origin'][0] = round(2 * cx - r['origin'][0], 4)
            if r['axis'] in ('y', 'z'):
                r['angle'] = -r['angle']
        out.append(e)
    return out


# ---------------------------------------------------------------- checks
def _face_rects(e):
    (x0, y0, z0), (x1, y1, z1) = e['from'], e['to']
    r = e.get('rotation')
    key = None if not r or r['angle'] == 0 else (r['axis'], r['angle'], tuple(r['origin']) if r['axis'] != 'y' else
                                                  (r['origin'][0], 0, r['origin'][2]))
    for face in e['faces']:
        if face == 'north':
            yield key, face, z0, (x0, y0, x1, y1)
        elif face == 'south':
            yield key, face, z1, (x0, y0, x1, y1)
        elif face == 'west':
            yield key, face, x0, (z0, y0, z1, y1)
        elif face == 'east':
            yield key, face, x1, (z0, y0, z1, y1)
        elif face == 'down':
            yield key, face, y0, (x0, z0, x1, z1)
        elif face == 'up':
            yield key, face, y1, (x0, z0, x1, z1)


def coplanar(elements, eps=1e-6):
    rects = []
    for i, e in enumerate(elements):
        for key, face, pos, rc in _face_rects(e):
            rects.append((i, key, face, round(pos, 4), rc))
    bad = []
    by = {}
    for r in rects:
        by.setdefault((r[1], r[2], r[3]), []).append(r)
    for grp in by.values():
        for a in range(len(grp)):
            i, _, f1, p1, r1 = grp[a]
            for b in range(a + 1, len(grp)):
                j, _, _, _, r2 = grp[b]
                if i == j:
                    continue
                w = min(r1[2], r2[2]) - max(r1[0], r2[0])
                h = min(r1[3], r2[3]) - max(r1[1], r2[1])
                if w > eps and h > eps:
                    bad.append((elements[i]['name'], elements[j]['name'], f1, p1))
    return bad


def check(elements):
    bad = []
    for e in elements:
        if not e.get('faces'):
            bad.append(e['name'] + ': no faces')
        if any(v < -16 or v > 32 for v in e['from'] + e['to']):
            bad.append(e['name'] + ': out of -16..32')
        if any(e['from'][i] > e['to'][i] for i in range(3)):
            bad.append(e['name'] + ': inverted')
        for fc in e['faces'].values():
            if any(u < 0 or u > 16 for u in fc['uv']):
                bad.append(e['name'] + ': uv')
        r = e.get('rotation')
        if r and r['angle'] not in (0, 22.5, -22.5, 45, -45):
            bad.append(e['name'] + ': angle')
    return bad


def bounds(els):
    lo = [min(e['from'][i] for e in els) for i in range(3)]
    hi = [max(e['to'][i] for e in els) for i in range(3)]
    return lo, hi


# ---------------------------------------------------------------- display transforms
def _rot(rx, ry, rz):
    x, y, z = (math.radians(v) for v in (rx, ry, rz))
    mx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    my = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    mz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return mx @ my @ mz


def _fit(lo, hi, rot, size):
    """Scale + translation (px) that fit the box into `size` px and centre it after the rotation."""
    corners = np.array([[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]) - 8
    R = _rot(*rot)
    pc = corners @ R.T
    ext = max(pc[:, 0].max() - pc[:, 0].min(), pc[:, 1].max() - pc[:, 1].min())
    s = min(1.6, size / max(ext, 1e-3))
    c = (pc.max(axis=0) + pc.min(axis=0)) / 2 * s
    return round(s, 4), [round(-c[0], 3), round(-c[1], 3), round(-c[2], 3)]


def display(els, gui=15.0, gui_rot=(30, 225, 0), hand=8.0, ground=7.0, fixed=13.0, head=None):
    lo, hi = bounds(els)
    out = {}
    s, t = _fit(lo, hi, gui_rot, gui)
    out['gui'] = {'rotation': list(gui_rot), 'translation': t, 'scale': [s] * 3}
    s, t = _fit(lo, hi, (0, 0, 0), ground)
    out['ground'] = {'rotation': [0, 0, 0], 'translation': [t[0], round(t[1] + 2, 3), t[2]], 'scale': [s] * 3}
    s, t = _fit(lo, hi, (0, 180, 0), fixed)
    out['fixed'] = {'rotation': [0, 180, 0], 'translation': t, 'scale': [s] * 3}
    s, t = _fit(lo, hi, (75, 45, 0), hand)
    th = {'rotation': [75, 45, 0], 'translation': [t[0], round(t[1] + 2.5, 3), t[2]], 'scale': [s] * 3}
    out['thirdperson_righthand'] = th
    out['thirdperson_lefthand'] = th
    s, t = _fit(lo, hi, (0, 45, 0), hand * 1.1)
    out['firstperson_righthand'] = {'rotation': [0, 45, 0], 'translation': [t[0], round(t[1] + 1.5, 3), t[2]], 'scale': [s] * 3}
    out['firstperson_lefthand'] = {'rotation': [0, 225, 0], 'translation': [t[0], round(t[1] + 1.5, 3), t[2]], 'scale': [s] * 3}
    out['head'] = head or {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [0.6] * 3}
    return out
