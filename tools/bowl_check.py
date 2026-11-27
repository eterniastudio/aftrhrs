#!/usr/bin/env python3
"""Fishbowl containment check (species with a "fishbowl" spec: the Pockie). Fails if the creature ever pokes through
its glass bowl in any pose the game can show while the bowl is there.

  python bowl_check.py [species ...]        (default: every fishbowl species; exit 1 on any violation)
  anim_check.py runs it too (its errors fail anim_check).

What it models (the generated clips, exactly as the game plays them):
  * Pose math in the clips' Blockbench convention (GeckoLib reproduces it in game; animkit.flip_rotations): every bone
    = T(keyframed position) T(pivot) Rz Ry Rx (rest + keyframed rotation) S(keyframed scale) T(-pivot), parents first.
    Keys are sampled at 60 fps with GeckoLib's linear interpolation; the client's playback-speed scaling (the roll gait
    at 0.75-1.4x) only changes how fast the same curve is walked, so sampling the whole clip covers every speed.
  * The glass: the cubes of the glass bone (fishbowl.glass_bone, default bowl_roll), in that bone's own frame (so the
    roll, bounce and pop of the bowl are followed). The walls are the exposed faces of their union (faces inside
    another glass cube are not on screen); the opening is the top face of the highest cube inset by
    fishbowl.opening_inset (the painted rim). Water, pebbles and seagrass are not walls.
  * The creature: every cube outside the bowl bone as an oriented box in the glass frame. Exact for boxes: the
    separating-axis gap (15 axes) to every wall must be >= MARGIN (0.1 px; the glass is a single surface). Outside
    the transitions every cube corner must also be in the bowl (inside the glass, or above the rim within the opening).
  * Clips: every clip the state machine can play with the bowl out (FishbowlCreature.clipFor: bowl_* and sleep_bowl*
    twins), each overlay that may play over them (bowl_hurt, bowl twins of water overlays, water overlays without a
    twin) combined with every base frame (GeckoLib layers controllers per channel), and the cross-fades (GeckoLib lerps
    the previous pose into the next clip's first frame) at 25 / 50 / 75 %: out of a loop or hold at any frame into any
    clip, out of a one-shot at any frame into what can cut it short (another one-shot, the roll gait, death) and from
    its last 0.25 s into anything, out of bowl_in's ending into the bowl clips, out of bowl_out's ending into the water.
  * The procedural layer in the bowl (ArcanaGeoRenderer): the head look clamped to procedural.bowl_look_yaw / _pitch
    (its 8 extremes; none while the clip drives the head, asleep, dead or lying), the idle tail sway where a clip
    leaves the tail free (its maximum, in phase and as an S both ways). No breathing (every bowl clip drives the body)
    and no banking (the renderer never banks a creature in its bowl).
  * bowl_in / bowl_out: while the bowl exists (glass scale >= 0.05) the creature never comes within MARGIN of a wall or
    the rim: it gets in and out through the open top only. Nothing else plays during them (FishbowlCreature.play), and
    a creature killed half way loses the bowl.
"""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import channel_points, load_gecko  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
RES = os.path.join(MOD, 'src', 'main', 'resources')
GECKO = os.path.join(RES, 'assets', 'aftrhrs', 'geckolib')
SPECIES = os.path.join(RES, 'data', 'aftrhrs', 'species')

FPS = 60
MARGIN = 0.1
ABSENT = 0.05          # bowl scale below which the bowl is not there (popping in / out)
BLEND = (0.25, 0.5, 0.75)
TRANSITIONS = ('bowl_in', 'bowl_out')


# ----------------------------------------------------------------------------------------------- math
def rot(r):
    x, y, z = (math.radians(v) for v in r)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


def affine(m3, t):
    a = np.eye(4)
    a[:3, :3] = m3
    a[:3, 3] = t
    return a


def bone_matrix(pivot, rest, r, p, s):
    pv = np.array(pivot, float)
    m = rot([rest[i] + r[i] for i in range(3)]) @ np.diag(s)
    return affine(np.eye(3), np.array(p, float) + pv) @ affine(m, [0, 0, 0]) @ affine(np.eye(3), -pv)


def sample(ch, t, default):
    if ch is None:
        return default
    pts = channel_points(ch)
    if not pts:
        return default
    if t <= pts[0][0]:
        return pts[0][1]
    for (t0, _, v0), (t1, v1, _) in zip(pts, pts[1:]):
        if t <= t1:
            u = (t - t0) / (t1 - t0) if t1 > t0 else 1.0
            return tuple(a + (b - a) * u for a, b in zip(v0, v1))
    return pts[-1][2]


# ----------------------------------------------------------------------------------------------- model
class Rig:
    def __init__(self, geo, anims, fishbowl):
        bones = geo['minecraft:geometry'][0]['bones']
        self.order = [b['name'] for b in bones]                          # parents come first in the geo
        self.parent = {b['name']: b.get('parent') for b in bones}
        # geo.json is GeckoLib space (x mirrored): back to the Blockbench convention the clips are authored in
        self.pivot = {b['name']: (-b.get('pivot', [0, 0, 0])[0], b.get('pivot', [0, 0, 0])[1], b.get('pivot', [0, 0, 0])[2]) for b in bones}
        self.rest = {b['name']: (-(b.get('rotation') or [0, 0, 0])[0], -(b.get('rotation') or [0, 0, 0])[1], (b.get('rotation') or [0, 0, 0])[2])
                     for b in bones}
        self.cubes = {}
        for b in bones:
            out = []
            for c in b.get('cubes') or []:
                o, s = c['origin'], c['size']
                inf = c.get('inflate', 0)
                f = np.array([-(o[0] + s[0]) - inf, o[1] - inf, o[2] - inf])
                t = np.array([-o[0] + inf, o[1] + s[1] + inf, o[2] + s[2] + inf])
                out.append((f, t))
            self.cubes[b['name']] = out
        self.anims = anims
        self.glass_bone = fishbowl.get('glass_bone', 'bowl_roll')
        self.bowl_bone = fishbowl.get('bowl_bone', 'bowl')

    def under(self, bone, root):
        while bone is not None:
            if bone == root:
                return True
            bone = self.parent[bone]
        return False

    def pose(self, tracks):
        """tracks: {(bone, ch): xyz} -> {bone: 4x4 world matrix}"""
        world = {}
        for b in self.order:
            m = bone_matrix(self.pivot[b], self.rest[b], tracks.get((b, 'rotation'), (0, 0, 0)),
                            tracks.get((b, 'position'), (0, 0, 0)), tracks.get((b, 'scale'), (1, 1, 1)))
            p = self.parent[b]
            world[b] = world[p] @ m if p else m
        return world

    def tracks(self, clip, t):
        out = {}
        for bone, chs in (self.anims[clip].get('bones') or {}).items():
            for ch, data in chs.items():
                out[(bone, ch)] = sample(data, t, (1.0, 1.0, 1.0) if ch == 'scale' else (0.0, 0.0, 0.0))
        return out


class Glass:
    """The exposed faces of the union of the glass cubes (rest space of the glass bone) minus the opening."""

    def __init__(self, boxes, inset):
        self.boxes = boxes
        lo = np.array([b[0] for b in boxes])
        hi = np.array([b[1] for b in boxes])
        self.lo, self.hi = lo, hi
        top = max(range(len(boxes)), key=lambda i: hi[i][1])
        self.rim = float(hi[top][1])
        self.hole = (lo[top][0] + inset, hi[top][0] - inset, lo[top][2] + inset, hi[top][2] - inset)
        cuts = [sorted(set([v for b in boxes for v in (b[0][i], b[1][i])] + ([self.hole[0], self.hole[1]] if i == 0 else [])
                           + ([self.hole[2], self.hole[3]] if i == 2 else []))) for i in range(3)]
        rects = []          # (axis, plane, (u0, u1), (v0, v1)) with u, v the other two axes in order
        for (f, t) in boxes:
            for i in range(3):
                j, k = [a for a in range(3) if a != i]
                for side, plane in ((-1, f[i]), (1, t[i])):
                    us = [c for c in cuts[j] if f[j] <= c <= t[j]]
                    vs = [c for c in cuts[k] if f[k] <= c <= t[k]]
                    for u0, u1 in zip(us, us[1:]):
                        for v0, v1 in zip(vs, vs[1:]):
                            c = np.zeros(3)
                            c[i] = plane + side * 1e-3
                            c[j] = (u0 + u1) / 2
                            c[k] = (v0 + v1) / 2
                            if self.inside(c[None])[0]:
                                continue            # covered by another glass cube: not on screen
                            if i == 1 and side == 1 and abs(plane - self.rim) < 1e-6 and self.hole[0] <= c[0] <= self.hole[1] \
                                    and self.hole[2] <= c[2] <= self.hole[3]:
                                continue            # the open mouth of the bowl
                            rects.append((i, plane, (u0, u1), (v0, v1)))
        rects = self._merge(rects)
        self.rects = rects
        ax = np.array([r[0] for r in rects])
        self.r_axis = ax
        self.r_plane = np.array([r[1] for r in rects])
        self.r_u = np.array([r[2] for r in rects])
        self.r_v = np.array([r[3] for r in rects])
        self.r_j = np.array([[a for a in range(3) if a != i][0] for i in ax])
        self.r_k = np.array([[a for a in range(3) if a != i][1] for i in ax])

    @staticmethod
    def _merge(rects):
        """greedy merge of coplanar grid cells into bigger rectangles (same walls, fewer distance tests)"""
        out = []
        groups = {}
        for r in rects:
            groups.setdefault((r[0], round(r[1], 6)), set()).add((r[2], r[3]))
        for (axis, plane), cells in groups.items():
            cells = set(cells)
            while cells:
                (u, v) = min(cells)
                # grow along u, then along v while every cell is present
                us = sorted({c[0] for c in cells if c[1] == v and c[0][0] >= u[0]})
                urun = [u]
                for nxt in us:
                    if nxt[0] == urun[-1][1] and (nxt, v) in cells:
                        urun.append(nxt)
                vrun = [v]
                vs = sorted({c[1] for c in cells if c[1][0] >= v[0]})
                for nxt in vs:
                    if nxt[0] == vrun[-1][1] and all((uu, nxt) in cells for uu in urun):
                        vrun.append(nxt)
                for uu in urun:
                    for vv in vrun:
                        cells.discard((uu, vv))
                out.append((axis, plane, (urun[0][0], urun[-1][1]), (vrun[0][0], vrun[-1][1])))
        return out

    def inside(self, p):
        # closed boxes (a point on the shared face of two glass cubes is inside); 1e-6 against rounding
        return np.any(np.all((p[:, None, :] >= self.lo[None] - 1e-6) & (p[:, None, :] <= self.hi[None] + 1e-6), axis=2), axis=1)


# ----------------------------------------------------------------------------------------------- the check
class Check:
    def __init__(self, sid):
        self.sid = sid
        sp = json.load(open(os.path.join(SPECIES, sid + '.json'), encoding='utf-8'))
        self.sp = sp
        fb = sp.get('fishbowl') or {}
        model = sp['model']
        geo = json.load(open(os.path.join(GECKO, 'models', 'entity', model + '.geo.json'), encoding='utf-8'))
        anims = load_gecko(os.path.join(GECKO, 'animations', 'entity', model + '.animation.json'))['animations']
        self.rig = Rig(geo, anims, fb)
        self.tags = sp['anim']['clips']
        self.proc = sp['anim'].get('procedural') or {}
        glass = []
        for (f, t) in self.rig.cubes[self.rig.glass_bone]:
            glass.append((f, t))
        self.glass = Glass(glass, float(fb.get('opening_inset', 1.0)))
        # the creature's cubes: every cube outside the bowl bone (rest space of its bone)
        self.body = {}
        for b in self.rig.order:
            if self.rig.under(b, self.rig.bowl_bone):
                continue
            for (f, t) in self.rig.cubes[b]:
                self.body.setdefault(b, []).append((f, t))
        self.points = sum(len(v) for v in self.body.values())
        self._wall_boxes()

    # which clips play in the bowl (FishbowlCreature.clipFor, MoodBrain, the hold / idle / gait mapping)
    def bowl_clips(self):
        a = self.rig.anims
        base, over = [], []
        for c in a:
            tag = self.tags.get(c, {})
            if c in TRANSITIONS:
                continue
            if c.startswith('bowl_') or c.startswith('sleep_bowl'):
                (over if tag.get('layer') == 'overlay' else base).append(c)
            elif tag.get('layer') == 'overlay' and 'bowl_' + c not in a:
                over.append(c)      # a water overlay without a twin plays over the bowl clips
        return sorted(base), sorted(over)

    def procedural_variants(self, clip, corners=False):
        """the head-look / tail-sway offsets the renderer may add over this base clip: list of {(bone, ch): add}"""
        tag = self.tags.get(clip, {})
        drv = tag.get('drv', '')
        head = self.proc.get('head')
        # ArcanaGeoRenderer in a bowl: the look is clamped to bowl_look_yaw / _pitch, off while the clip drives the
        # head ('h'), asleep (a sleep hold) or dead, and off in a lying posture
        yaw = float(self.proc.get('bowl_look_yaw', self.proc.get('look_yaw', 55)))
        pitch = float(self.proc.get('bowl_look_pitch', self.proc.get('look_pitch', 35)))
        off = 'h' in drv or clip.startswith('sleep') or clip.endswith('death') or tag.get('p') in ('lying', 'belly')
        heads = [(0.0, 0.0)]
        if head and not off:
            heads = [(p, y) for p in (-pitch, 0.0, pitch) for y in (-yaw, 0.0, yaw) if not corners or (p == 0) == (y == 0)]
        tails = [None]
        sway = float(self.proc.get('tail_sway', 0))
        if self.proc.get('tail') and sway > 0 and 't' not in drv:
            tails = [(1, 1, 1), (-1, -1, -1), (1, -1, 1), (-1, 1, -1)]      # in phase and the S-wave both ways
        out = []
        for (p, y) in heads:
            for s in tails:
                add = {}
                if head and (p or y):
                    add[(head, 'rotation')] = (p, y, 0.0)
                for i, b in enumerate(self.proc.get('tail') or []):
                    if s:
                        add[(b, 'rotation')] = (0.0, s[min(i, len(s) - 1)] * sway * (1 + 0.35 * i), 0.0)
                out.append(add)
        return out

    def _wall_boxes(self):
        """the glass wall rectangles as flat boxes (center, half extents) in the glass bone's rest space"""
        q, h = [], []
        for (i, plane, (u0, u1), (v0, v1)) in self.glass.rects:
            j, k = [a for a in range(3) if a != i]
            c, e = np.zeros(3), np.zeros(3)
            c[i], c[j], c[k] = plane, (u0 + u1) / 2, (v0 + v1) / 2
            e[j], e[k] = (u1 - u0) / 2, (v1 - v0) / 2
            q.append(c)
            h.append(e)
        self.wall_q, self.wall_h = np.array(q), np.array(h)
        x0, x1, z0, z1 = self.glass.hole
        self.hole_lo = np.array([x0 + MARGIN, -1e9, z0 + MARGIN])
        self.hole_hi = np.array([x1 - MARGIN, 1e9, z1 - MARGIN])

    def frame(self, tracks, adds, transition):
        """worst margin of one pose (+ adds), world units; None if the bowl is not there.

        Exact for boxes: the separating-axis gap (15 axes) between every creature cube (an oriented box in the glass
        bone's frame) and every glass wall rectangle; a cube touching or crossing a wall gives a gap <= 0. Outside the
        transitions the creature also has to be in the bowl: every cube corner inside the glass, or above the rim
        within the opening (peeking out of the open top); a corner anywhere else is margin -1."""
        tr = dict(tracks)
        for k, v in adds.items():
            base = tr.get(k, (0.0, 0.0, 0.0))
            tr[k] = tuple(a + b for a, b in zip(base, v))
        w = self.rig.pose(tr)
        g = w[self.rig.glass_bone]
        scale = np.cbrt(abs(np.linalg.det(g[:3, :3])))
        if scale < ABSENT:
            return None
        inv = np.linalg.inv(g)
        cs, us = [], []
        for b, cubes in self.body.items():
            m = inv @ w[b]
            r, t = m[:3, :3], m[:3, 3]
            for (f, to) in cubes:
                cs.append(r @ ((f + to) / 2) + t)
                us.append((r * ((to - f) / 2)[None, :]).T)      # rows: the three half-edge vectors
        c = np.array(cs)                     # (N, 3)
        u = np.array(us)                     # (N, 3, 3)
        n = len(c)
        dirs = u / np.maximum(np.linalg.norm(u, axis=2, keepdims=True), 1e-9)
        world = np.broadcast_to(np.eye(3), (n, 3, 3))
        cross = np.cross(dirs[:, :, None, :], world[:, None, :, :]).reshape(n, 9, 3)
        axes = np.concatenate([world, dirs, cross], axis=1)               # (N, 15, 3)
        norm = np.linalg.norm(axes, axis=2)                               # (N, 15)
        ok = norm > 1e-6
        axes = axes / np.where(ok, norm, 1)[..., None]
        ra = np.abs(np.einsum('nak,nik->nai', axes, u)).sum(axis=2)       # (N, 15)
        rb = np.abs(axes) @ self.wall_h.T                                 # (N, 15, R)
        dist = np.abs(np.einsum('nak,nk->na', axes, c)[..., None] - axes @ self.wall_q.T)
        gap = np.where(ok[..., None], dist - ra[..., None] - rb, -1e9).max(axis=1)   # (N, R)
        worst = float(gap.min()) * scale
        if not transition:
            signs = np.array([[sx, sy, sz] for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)], float)
            corners = c[:, None, :] + np.einsum('cs,nsk->nck', signs, u)          # (N, 8, 3)
            p = corners.reshape(-1, 3)
            ins = self.glass.inside(p)
            up = (p[:, 1] > self.glass.rim) & np.all((p > self.hole_lo) & (p < self.hole_hi), axis=1)
            out = ~(ins | up)
            if np.any(out):
                q = p[out]
                d = np.maximum(0, np.maximum(self.glass.lo[None] - q[:, None], q[:, None] - self.glass.hi[None]))
                depth = np.sqrt((d * d).sum(axis=2)).min(axis=1).max()        # how far the worst corner is out
                worst = min(worst, -max(float(depth), 0.01) * scale)
        return worst

    def clip_frames(self, c, step=1.0 / FPS):
        L = float(self.rig.anims[c].get('animation_length') or 0)
        n = max(1, int(round(L / step)))
        return [min(L, k * step) for k in range(n + 1)]

    def jobs(self):
        base, over = self.bowl_clips()
        out = [('clip', c) for c in base + list(TRANSITIONS)]
        out += [('overlay', o, c) for o in over for c in base]
        out += [('blend', a) for a in base + list(TRANSITIONS)]
        return base, over, out

    def job(self, j):
        """-> (key, worst margin, where, frames)"""
        rig = self.rig
        base, over = self.bowl_clips()
        worst, at, frames = 1e9, None, 0
        if j[0] == 'clip':
            c = j[1]
            variants = self.procedural_variants(c)
            for t in self.clip_frames(c):
                tracks = rig.tracks(c, t)
                for add in variants:
                    m = self.frame(tracks, add, c in TRANSITIONS)
                    frames += 1
                    if m is not None and m < worst:
                        worst, at = m, '%.3f s%s' % (t, ' look %s' % (add,) if add else '')
            return c, worst, at, frames
        if j[0] == 'overlay':
            o, c = j[1], j[2]
            for tb in self.clip_frames(c, 0.2):
                bt = rig.tracks(c, tb)
                for to in self.clip_frames(o, 1.0 / 20):
                    # GeckoLib layers controllers per channel: the overlay replaces only the channels it keys
                    tr = dict(bt)
                    tr.update(rig.tracks(o, to))
                    for add in self.procedural_variants(c, corners=True):
                        m = self.frame(tr, add, False)
                        frames += 1
                        if m is not None and m < worst:
                            worst, at = m, '%s@%.2f + %s@%.2f' % (c, tb, o, to)
            return o + ' (over every bowl clip)', worst, at, frames
        a = j[1]
        transition = a == 'bowl_out'
        L = float(rig.anims[a]['animation_length'])
        if transition:
            # bowl_out's last 0.25 s fade into the water clips (the bowl is nearly gone; the transition rule applies)
            targets = [c for c in ('idle_swim', 'swim', 'idle') if c in rig.anims]
            ta_list = [L - k / 20 for k in range(6)]
        else:
            # bowl_out never follows bowl_in directly (it needs IN_BOWL and the settle ticks; bowl_in has faded out)
            targets = base + (['bowl_out'] if a != 'bowl_in' else [])
            ta_list = self.clip_frames(a, 0.1) if a != 'bowl_in' else [L - k / 20 for k in range(6)]
        # what can cut into a one-shot before its last 0.25 s (CreatureAnim.Driver keeps a triggered clip playing): another
        # triggered one-shot, the gait (moving cancels it) and death; loops are only switched to once it ends. A loop or
        # a hold can be left for anything at any time.
        once = self.tags.get(a, {}).get('loop', 'once') == 'once'
        cut_in = {b for b in targets if self.tags.get(b, {}).get('loop', 'once') == 'once'} | {'bowl_roll', 'bowl_death'}
        for ta in ta_list:
            pa = rig.tracks(a, ta)
            ending = ta >= L - 0.25 - 1e-9
            for b in targets:
                if b == a or (once and not ending and not transition and b not in cut_in):
                    continue
                pb = rig.tracks(b, 0.0)
                keys = set(pa) | set(pb)
                for u in BLEND:
                    tr = {}
                    for k in keys:
                        d = (1.0, 1.0, 1.0) if k[1] == 'scale' else (0.0, 0.0, 0.0)
                        va, vb = pa.get(k, d), pb.get(k, d)
                        tr[k] = tuple(x + (y - x) * u for x, y in zip(va, vb))
                    for add in self.procedural_variants(b, corners=True):
                        m = self.frame(tr, add, transition)
                        frames += 1
                        if m is not None and m < worst:
                            worst, at = m, '%s@%.2f -> %s %d%%' % (a, ta, b, u * 100)
        return ('fade out of bowl_out' if transition else 'cross-fades'), worst, at, frames

    def run(self, verbose=True, procs=None):
        base, over, jobs = self.jobs()
        if procs == 1:
            res = [self.job(j) for j in jobs]
        else:
            import multiprocessing as mp
            with mp.get_context('spawn').Pool(procs or min(6, max(1, (os.cpu_count() or 2) - 1)), initializer=_init, initargs=(self.sid,)) as pool:
                res = pool.map(_work, jobs, chunksize=1)
        results = {}
        frames = 0
        for key, worst, at, n in res:
            frames += n
            if key not in results or worst < results[key][0]:
                results[key] = (worst, at)
        self.results, self.frames = results, frames
        errors = ['%s: %s pokes through the glass (margin %.3f at %s)' % (self.sid, k, v[0], v[1])
                  for k, v in results.items() if v[0] < MARGIN]
        if verbose:
            print('bowl_check %s: %d clips (%d bowl + 2 transitions), %d overlays over each, cross-fades between all of them; '
                  '%d frames at %d fps, %d creature cubes, %d glass wall faces, rim y=%.2f, opening x/z %s'
                  % (self.sid, len(base) + len(TRANSITIONS), len(base), len(over), frames, FPS, self.points,
                     len(self.glass.rects), self.glass.rim, tuple(round(float(v), 2) for v in self.glass.hole)))
            for k, (m, at) in sorted(results.items(), key=lambda kv: kv[1][0]):
                print('  %-38s worst margin %7.3f  at %s%s' % (k, m, at, '   <-- VIOLATION' if m < MARGIN else ''))
        return errors


_CHECK = None


def _init(sid):
    global _CHECK
    _CHECK = Check(sid)


def _work(j):
    return _CHECK.job(j)


def species_with_bowls():
    ids = json.load(open(os.path.join(SPECIES, 'index.json')))['species']
    out = []
    for i in ids:
        sp = json.load(open(os.path.join(SPECIES, i + '.json'), encoding='utf-8'))
        if sp.get('fishbowl') and sp.get('model'):
            out.append(i)
    return out


def check_all(verbose=True, ids=None):
    errors = []
    for sid in ids or species_with_bowls():
        errors += Check(sid).run(verbose)
    return errors


if __name__ == '__main__':
    errs = check_all(True, sys.argv[1:] or None)
    print('bowl_check: %d violation(s)' % len(errs))
    sys.exit(1 if errs else 0)
