"""Block-model element geometry for gen_content.py (stdlib only): rigid transforms of model elements, bounds."""
import copy
import math

MIN_E, MAX_E = -16.0, 32.0


def _m(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == 'x':
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    if axis == 'y':
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def apply(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def rot_matrix(rot):
    """Matrix of a model element rotation (single axis or Euler x/y/z = Rz*Ry*Rx), without rescale."""
    if rot is None:
        return [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    if 'axis' in rot:
        return _m(rot['axis'], float(rot.get('angle', 0)))
    return mul(_m('z', float(rot.get('z', 0))), mul(_m('y', float(rot.get('y', 0))), _m('x', float(rot.get('x', 0)))))


def to_euler(r):
    y = math.asin(max(-1.0, min(1.0, -r[2][0])))
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(r[2][1], r[2][2])
        z = math.atan2(r[1][0], r[0][0])
    else:  # gimbal lock
        x = math.atan2(-r[1][2], r[1][1])
        z = 0.0
    return [round(math.degrees(v), 4) + 0.0 for v in (x, y, z)]


def _r(v):
    return round(v, 4) + 0.0


def rescale_factors(rot):
    m = rot_matrix(rot)
    out = []
    for i in range(3):
        col = [m[0][i], m[1][i], m[2][i]]
        out.append(1.0 / max(abs(c) for c in col))
    return out


def transform(e, scale=1.0, translate=(0, 0, 0), yaw=0.0, pivot=(8.0, 8.0), shift=(0, 0, 0), strip_cull=True):
    """A copy of element e moved rigidly: uniform scale about (8,8,8), translate (px), rotate about the vertical
    axis through pivot (x, z) by yaw degrees (right-handed about +Y, so negative = clockwise seen from above), then
    shifted by -shift (px). Rotations are folded into the element's own rotation."""
    e = copy.deepcopy(e)
    f, t = [float(v) for v in e['from']], [float(v) for v in e['to']]
    rot = e.get('rotation')
    if rot is not None:
        rot = dict(rot)
        o = [float(v) for v in rot.get('origin', [8, 8, 8])]
    else:
        o = None
    # scale about block centre
    if scale != 1.0:
        f = [(v - 8) * scale + 8 for v in f]
        t = [(v - 8) * scale + 8 for v in t]
        if o is not None:
            o = [(v - 8) * scale + 8 for v in o]
    f = [f[i] + translate[i] for i in range(3)]
    t = [t[i] + translate[i] for i in range(3)]
    if o is not None:
        o = [o[i] + translate[i] for i in range(3)]
    if yaw:
        is_identity = rot is None or ('axis' in rot and float(rot.get('angle', 0)) == 0) or \
                      ('axis' not in rot and not any(float(rot.get(k, 0)) for k in 'xyz'))
        if is_identity:
            o = [(f[i] + t[i]) / 2 for i in range(3)]
            newrot = {'axis': 'y', 'angle': _r(yaw)}
        elif 'axis' in rot and rot['axis'] == 'y':
            newrot = {'axis': 'y', 'angle': _r(float(rot['angle']) + yaw)}
            if rot.get('rescale'):
                newrot['rescale'] = False
                fac = rescale_factors(rot)
                f = [(f[i] - o[i]) * fac[i] + o[i] for i in range(3)]
                t = [(t[i] - o[i]) * fac[i] + o[i] for i in range(3)]
        else:
            if rot.get('rescale'):
                fac = rescale_factors(rot)
                f = [(f[i] - o[i]) * fac[i] + o[i] for i in range(3)]
                t = [(t[i] - o[i]) * fac[i] + o[i] for i in range(3)]
            m = mul(_m('y', yaw), rot_matrix(rot))
            x, y, z = to_euler(m)
            newrot = {'x': x, 'y': y, 'z': z}
        c = [pivot[0], o[1], pivot[1]]
        ry = _m('y', yaw)
        d = apply(ry, [o[i] - c[i] for i in range(3)])
        o2 = [d[i] + c[i] for i in range(3)]
        dv = [o2[i] - o[i] for i in range(3)]
        f = [f[i] + dv[i] for i in range(3)]
        t = [t[i] + dv[i] for i in range(3)]
        o = o2
        rot = newrot
    f = [f[i] - shift[i] for i in range(3)]
    t = [t[i] - shift[i] for i in range(3)]
    if o is not None:
        o = [o[i] - shift[i] for i in range(3)]
    e['from'] = [_r(v) for v in f]
    e['to'] = [_r(v) for v in t]
    if rot is not None:
        rot['origin'] = [_r(v) for v in o]
        e['rotation'] = rot
    if strip_cull:
        for face in (e.get('faces') or {}).values():
            face.pop('cullface', None)
    return e


def in_range(e, eps=1e-3):
    return all(MIN_E - eps <= v <= MAX_E + eps for v in list(e['from']) + list(e['to']))


def bounds(elements):
    if not elements:
        return None
    mn = [min(min(e['from'][i], e['to'][i]) for e in elements) for i in range(3)]
    mx = [max(max(e['from'][i], e['to'][i]) for e in elements) for i in range(3)]
    return mn, mx


def ebox(e):
    """Approximate world-space AABB of an element including its rotation (corners rotated about origin)."""
    f, t = e['from'], e['to']
    rot = e.get('rotation')
    corners = [[(f, t)[i][0], (f, t)[j][1], (f, t)[k][2]] for i in (0, 1) for j in (0, 1) for k in (0, 1)]
    if rot is not None:
        m = rot_matrix(rot)
        if rot.get('rescale'):
            fac = rescale_factors(rot)
        else:
            fac = [1, 1, 1]
        o = rot.get('origin', [8, 8, 8])
        out = []
        for c in corners:
            v = [(c[i] - o[i]) * fac[i] for i in range(3)]
            v = apply(m, v)
            out.append([v[i] + o[i] for i in range(3)])
        corners = out
    mn = [min(c[i] for c in corners) for i in range(3)]
    mx = [max(c[i] for c in corners) for i in range(3)]
    return mn, mx


def rotate180_y(e, px, pz):
    """Rigid 180 degree turn about the vertical axis through (px, pz): faces swap sides, top/bottom UVs turn."""
    e = copy.deepcopy(e)
    f, t = e['from'], e['to']
    nf = [2 * px - t[0], f[1], 2 * pz - t[2]]
    nt = [2 * px - f[0], t[1], 2 * pz - f[2]]
    e['from'], e['to'] = [_r(v) for v in nf], [_r(v) for v in nt]
    faces = e.get('faces') or {}
    swap = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east', 'up': 'up', 'down': 'down'}
    nfaces = {}
    for k, v in faces.items():
        v = dict(v)
        v.pop('cullface', None)
        if k in ('up', 'down'):
            v['rotation'] = (int(v.get('rotation', 0)) + 180) % 360
        nfaces[swap[k]] = v
    e['faces'] = nfaces
    if e.get('rotation'):
        r = dict(e['rotation'])
        o = r.get('origin', [8, 8, 8])
        r['origin'] = [_r(2 * px - o[0]), o[1], _r(2 * pz - o[2])]
        if 'axis' in r and r['axis'] in ('x', 'z'):
            r['angle'] = -float(r['angle'])
        elif 'axis' not in r:
            r['x'] = -float(r.get('x', 0))
            r['z'] = -float(r.get('z', 0))
        e['rotation'] = r
    return e
