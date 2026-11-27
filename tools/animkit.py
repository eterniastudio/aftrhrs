#!/usr/bin/env python3
"""Animation kit for the Aftr Hrs creatures (GeckoLib 5.5 animation json): sampling, clip authoring DSL, merging.

Phase-2 authors write `mod/tools/clips/<model>.py` (see clips/README in PORTING.md "Animation"):

    from animkit import Clip, wave, pulse, step, keys, overshoot, decay

    def clips(m):                       # m: animkit.ModelView of the generated model
        c = Clip(m, 'sit_down', 0.9)     # once / loop / hold
        idle0, sit0 = m.pose('idle', 0), m.pose('sit', 0)
        for bone in m.bones_keyed('sit'):
            c.blend(bone, idle0, sit0, 0.0, 0.9)
        c.rot('tail', lambda t: (0, 8 * pulse(t, 0.3, 0.9), 0), add=True)
        return [c]

    TAGS = {'belly_up': {'p': 'belly'}}  # optional: hand overrides for the clip classification (animtags.py)
    STATES = {'sit': 'sit'}              # optional: state -> clip overrides
    IDLE = {'standing': [{'clip': 'look_around', 'w': 3, 'cd': 10}]}   # optional: idle pool additions
    TRANSITIONS = {'standing>sitting': 'sit_down'}                    # optional

gen_creatures.py imports every clips/<model>.py, bakes the clips at 20 fps into the generated
`<model>.animation.json` (the source .bbmodel is never touched) and merges the tables into the species' `anim` block.

Sign conventions are bedrock / GeckoLib keyframe values (what a .bbmodel stores, see arcmotion.py):
  rotation x + : front of the bone pitches UP (head up / leg forward / rear drops)
  rotation y + : turns towards -x
  rotation z + : the +x side rises
  position in model pixels (1/16 block), y up.
Rules baked in: keys on a 0.05 s grid, loops end exactly where they start (seamless), eased curves.
"""
import math

FPS = 20


# ----------------------------------------------------------------------------------------------- curve helpers
def smooth(u):
    u = max(0.0, min(1.0, u))
    return u * u * (3 - 2 * u)


def wave(t, L, cycles=1, phase=0.0):
    """sin wave with an integer number of cycles over L (seamless for loops)."""
    return math.sin(2 * math.pi * (cycles * t / L + phase))


def pulse(t, t0, t1):
    """0 -> 1 -> 0 between t0 and t1 (sin^2)."""
    if t <= t0 or t >= t1:
        return 0.0
    return math.sin(math.pi * (t - t0) / (t1 - t0)) ** 2


def step(t, t0, t1):
    """smoothstep 0 -> 1 between t0 and t1."""
    if t1 <= t0:
        return 1.0 if t >= t1 else 0.0
    return smooth((t - t0) / (t1 - t0))


def keys(t, pts, ease='smooth'):
    """Eased piecewise curve through [(time, value)] (value: number or tuple). ease: smooth | out | in | linear."""
    if t <= pts[0][0]:
        return pts[0][1]
    for (t0, v0), (t1, v1) in zip(pts, pts[1:]):
        if t <= t1:
            u = (t - t0) / (t1 - t0) if t1 > t0 else 1
            if ease == 'smooth':
                u = smooth(u)
            elif ease == 'out':
                u = 1 - (1 - u) ** 2
            elif ease == 'in':
                u = u * u
            if isinstance(v0, tuple):
                return tuple(a + (b - a) * u for a, b in zip(v0, v1))
            return v0 + (v1 - v0) * u
    return pts[-1][1]


def overshoot(t, t0, t1, amt=0.15):
    """0 -> 1 with one overshoot of `amt` and settle (back-out)."""
    if t <= t0:
        return 0.0
    if t >= t1:
        return 1.0
    u = (t - t0) / (t1 - t0)
    s = 1.70158 * (amt / 0.1)
    u -= 1
    return u * u * ((s + 1) * u + s) + 1


def decay(t, t0, freq, damp):
    """Damped oscillation from t0 (settle wobble / follow-through), 0 before t0."""
    if t < t0:
        return 0.0
    x = t - t0
    return math.exp(-damp * x) * math.sin(2 * math.pi * freq * x)


def lerp3(a, b, u):
    return tuple(a[i] + (b[i] - a[i]) * u for i in range(3))


# ----------------------------------------------------------------------------------------------- sampling
def _vec(entry, side):
    """A GeckoLib keyframe entry -> (x, y, z) floats (Molang strings count as 0)."""
    if isinstance(entry, dict):
        if 'vector' in entry:
            v = entry['vector']
        else:
            v = entry.get(side) if side in entry else entry.get('post', entry.get('pre'))
    else:
        v = entry
    if not isinstance(v, (list, tuple)):
        v = [v, v, v]
    out = []
    for x in v:
        try:
            out.append(float(x))
        except (TypeError, ValueError):
            out.append(0.0)
    return tuple(out)


def channel_points(ch):
    """GeckoLib channel dict {time: entry} (or a bare vector) -> sorted [(t, pre, post)]."""
    if not isinstance(ch, dict) or any(k in ch for k in ('vector', 'pre', 'post')):
        v = _vec(ch, 'post')
        return [(0.0, v, v)]
    pts = []
    for k, e in ch.items():
        try:
            t = float(k)
        except ValueError:
            continue
        pts.append((t, _vec(e, 'pre'), _vec(e, 'post')))
    pts.sort(key=lambda p: p[0])
    return pts


def sample(ch, t, default=(0.0, 0.0, 0.0)):
    """Value of a channel at time t (linear between keys; catmull-rom keys are close enough for analysis)."""
    if ch is None:
        return default
    pts = channel_points(ch)
    if not pts:
        return default
    if t <= pts[0][0]:
        return pts[0][1]
    for (t0, _, v0), (t1, v1, _) in zip(pts, pts[1:]):
        if t <= t1:
            u = (t - t0) / (t1 - t0) if t1 > t0 else 1
            return lerp3(v0, v1, u)
    return pts[-1][2]


class ModelView:
    """Read access to one generated model for clip authors: bones, clips, poses."""

    def __init__(self, name, geo, anims):
        self.name = name
        self.geo = geo
        self.anims = anims                      # {'animations': {...}} (mutated by merge)
        bones = geo['minecraft:geometry'][0]['bones']
        self.bones = [b['name'] for b in bones]
        self.parent = {b['name']: b.get('parent') for b in bones}
        self.pivot = {b['name']: b.get('pivot', [0, 0, 0]) for b in bones}

    @property
    def clips(self):
        return self.anims['animations']

    def has(self, clip):
        return clip in self.clips

    def has_bone(self, bone):
        return bone in self.parent

    def length(self, clip):
        return float(self.clips[clip].get('animation_length', 0))

    def bones_keyed(self, clip, channels=('rotation', 'position', 'scale')):
        c = self.clips.get(clip) or {}
        return [b for b, chs in (c.get('bones') or {}).items() if any(ch in chs for ch in channels)]

    def value(self, clip, bone, channel, t):
        c = self.clips.get(clip) or {}
        ch = ((c.get('bones') or {}).get(bone) or {}).get(channel)
        return sample(ch, t, (1.0, 1.0, 1.0) if channel == 'scale' else (0.0, 0.0, 0.0))

    def pose(self, clip, t):
        """{(bone, channel): (x, y, z)} for every keyed bone of `clip` at time t ('end' = last frame)."""
        if t == 'end':
            t = self.length(clip)
        out = {}
        for bone, chs in ((self.clips.get(clip) or {}).get('bones') or {}).items():
            for ch in chs:
                out[(bone, ch)] = self.value(clip, bone, ch, t)
        return out

    def put(self, clip):
        """Bakes an authored Clip into the model now (later clips in the same module can sample it); return it from
        clips() as well so it is recorded as authored."""
        self.clips[clip.name] = clip.bake()
        return clip

    def children(self, bone):
        return [b for b, p in self.parent.items() if p == bone]

    def find(self, *names):
        """The first bone that exists among `names` (also matches ModelEngine-tagged h_/hi_ names), or None."""
        for n in names:
            if n in self.parent:
                return n
            for b in self.bones:
                if b.split('_', 1)[-1] == n and b.split('_', 1)[0] in ('h', 'hi', 'g', 'b'):
                    return b
        return None


# ----------------------------------------------------------------------------------------------- authoring
class Clip:
    """One authored clip. Tracks are functions of t (seconds) -> (x, y, z); baked at 20 fps on done()."""

    def __init__(self, m, name, length, loop='once'):
        self.m = m
        self.name = name
        self.L = float(length)
        self.loop = loop                       # 'once' | 'loop' | 'hold'
        self.tracks = {}
        self.sounds = []                       # [(t, effect)] -> GeckoLib sound_effects
        self.particles = []                    # [(t, effect)] -> GeckoLib particle_effects

    def sound(self, t, event, volume=1.0, pitch=1.0):
        """A sound keyframe at t seconds (played on the client by CreatureAnim's keyframe handler, every loop).
        event: a species sfx event ('splash' -> aftrhrs:<species>.splash) or a full id ('minecraft:entity.axolotl.splash').
        volume / pitch multiply the event's own (a small per-play pitch jitter is added in game)."""
        self.sounds.append((max(0.0, min(float(t), self.L)), '%s|%s|%s' % (event, _num(volume), _num(pitch))))
        return self

    def particle(self, t, particle, count=4, spread=0.25, speed=0.02, height=0.5):
        """A particle keyframe at t seconds (spawned on the client by CreatureAnim's keyframe handler, every loop): `count`
        vanilla simple particles ('minecraft:end_rod') around the creature, `spread` blocks wide, at `height` x its
        hitbox height, drifting at `speed`."""
        self.particles.append((max(0.0, min(float(t), self.L)), '%s|%d|%s|%s|%s' % (
            particle, count, _num(spread), _num(speed), _num(height))))
        return self

    def _add(self, bone, ch, fn, add):
        if bone is None:
            return self
        if self.m is not None and not self.m.has_bone(bone):
            raise KeyError('%s: no bone %r in %s' % (self.name, bone, self.m.name))
        key = (bone, ch)
        if add and key in self.tracks:
            prev = self.tracks[key]
            base = 1.0 if ch == 'scale' else 0.0
            self.tracks[key] = (lambda f, g: (lambda t: tuple(a + b - base for a, b in zip(_t3(f(t)), _t3(g(t))))))(prev, fn)
        else:
            self.tracks[key] = fn
        return self

    def rot(self, bone, fn, add=False):
        return self._add(bone, 'rotation', fn, add)

    def pos(self, bone, fn, add=False):
        return self._add(bone, 'position', fn, add)

    def scl(self, bone, fn, add=False):
        return self._add(bone, 'scale', fn, add)

    def copy(self, clip, t0=0.0, speed=1.0, bones=None):
        """Starts every track from another clip of the model (sampled at t0 + t * speed)."""
        src = self.m.clips[clip]
        for bone, chs in (src.get('bones') or {}).items():
            if bones is not None and bone not in bones:
                continue
            for ch, data in chs.items():
                self.tracks[(bone, ch)] = (lambda d, ch: lambda t: sample(d, t0 + t * speed,
                                                                          (1, 1, 1) if ch == 'scale' else (0, 0, 0)))(data, ch)
        return self

    def blend(self, bone, pose_a, pose_b, t0, t1, ease=step, channels=('rotation', 'position', 'scale')):
        """bone goes from pose_a to pose_b ({(bone, ch): xyz} from ModelView.pose) between t0 and t1."""
        for ch in channels:
            rest = (1.0, 1.0, 1.0) if ch == 'scale' else (0.0, 0.0, 0.0)
            a, b = pose_a.get((bone, ch), rest), pose_b.get((bone, ch), rest)
            if a == rest and b == rest:
                continue
            self.tracks[(bone, ch)] = (lambda a, b: lambda t: lerp3(a, b, ease(t, t0, t1)))(a, b)
        return self

    def blend_all(self, pose_a, pose_b, t0, t1, ease=step):
        """Every bone keyed by either pose goes from pose_a to pose_b (a pose-to-pose transition)."""
        for bone in sorted({k[0] for k in pose_a} | {k[0] for k in pose_b}):
            self.blend(bone, pose_a, pose_b, t0, t1, ease)
        return self

    def follow(self, bone, src, lag_s, gain=(1, 1, 1), extra=None):
        """bone rotation = gain * src rotation evaluated lag_s earlier (+ extra(t)): overlap / follow-through."""
        f = self.tracks[(src, 'rotation')]

        def g(t):
            ts = t - lag_s
            ts = ts % self.L if self.loop == 'loop' else max(0.0, ts)
            v = _t3(f(ts))
            e = _t3(extra(t)) if extra else (0, 0, 0)
            return tuple(v[i] * gain[i] + e[i] for i in range(3))
        return self._add(bone, 'rotation', g, False)

    def bake(self):
        """-> GeckoLib animation dict."""
        n = max(1, int(round(self.L * FPS)))
        bones = {}
        for (bone, ch), fn in sorted(self.tracks.items()):
            ks = []
            for k in range(n + 1):
                t = round(k / FPS, 4)
                ks.append((t, tuple(round(x, 3) for x in _t3(fn(min(t, self.L))))))
            if self.loop == 'loop':
                ks[-1] = (ks[-1][0], ks[0][1])
            pre = {}
            if ch == 'rotation':
                # a spin (an axis that ends whole turns away from where it starts: flips, rolls, twirls) is wrapped
                # to within 180 deg of its first frame, with a pre / post key at each wrap: the same rotation on
                # screen, but the clip ends numerically where it started, so the cross-fade into the next clip does
                # not unwind the turn backwards. Other axes and posture transitions (which must land exactly on the
                # next pose's values) are left as authored.
                ref, end = ks[0][1], ks[-1][1]
                spin = [abs(end[j] - ref[j]) > 180 and abs((end[j] - ref[j]) - 360 * round((end[j] - ref[j]) / 360)) < 1
                        and self.name not in _TRANSITIONS for j in range(3)]
                if any(spin):
                    ks = [(t, tuple(ref[j] + _wrap(v[j] - ref[j]) if spin[j] else v[j] for j in range(3))) for t, v in ks]
                for i in range(1, len(ks)):
                    a, b = ks[i - 1][1], ks[i][1]
                    p = tuple(round(a[j] + _wrap(b[j] - a[j]), 3) for j in range(3))
                    if any(abs(p[j] - b[j]) > 1e-3 for j in range(3)):
                        pre[i] = p
            slim = [ks[0]]
            keep = [0]
            last = 0
            for i in range(1, len(ks) - 1):
                p, nx = ks[last], ks[i + 1]
                ok = i not in pre and (i + 1) not in pre
                for c in ks[last + 1:i + 1] if ok else ():
                    u = (c[0] - p[0]) / (nx[0] - p[0])
                    if any(abs(p[1][j] + (nx[1][j] - p[1][j]) * u - c[1][j]) > 0.03 for j in range(3)):
                        ok = False
                        break
                if not ok:
                    slim.append(ks[i])
                    keep.append(i)
                    last = i
            if len(ks) > 1:
                slim.append(ks[-1])
                keep.append(len(ks) - 1)
            data = {}
            for i, (t, v) in zip(keep, slim):
                post = [_num(x) for x in v]
                data[_tkey(t)] = {'pre': [_num(x) for x in pre[i]], 'post': post} if i in pre else post
            bones.setdefault(bone, {})[ch] = data
        loop = {'loop': True, 'hold': 'hold_on_last_frame'}.get(self.loop, False)
        out = {'loop': loop, 'animation_length': _num(round(self.L, 4)), 'bones': bones}
        if self.sounds:
            fx = {}
            for t, effect in sorted(self.sounds):
                while _tkey(t) in fx:
                    t += 0.001
                fx[_tkey(t)] = {'effect': effect}
            out['sound_effects'] = fx
        if self.particles:
            fx = {}
            for t, effect in sorted(self.particles):
                while _tkey(t) in fx:
                    t += 0.001
                fx[_tkey(t)] = {'effect': effect}
            out['particle_effects'] = fx
        return out


def _t3(v):
    if isinstance(v, (int, float)):
        return (float(v), float(v), float(v))
    return tuple(float(x) for x in v)


_TRANSITIONS = {'sit_down', 'stand_up', 'lie_down', 'wake_up', 'sit_lie_down', 'lie_sit_up', 'unhide', 'emerge'}


def _wrap(x):
    """Degrees into (-180, 180]."""
    y = math.fmod(x + 180.0, 360.0)
    if y <= 0:
        y += 360.0
    return y - 180.0


def _num(x):
    x = round(float(x), 4)
    return int(x) if x == int(x) else x


def _tkey(t):
    s = ('%.4f' % t).rstrip('0').rstrip('.')
    return s if '.' in s else s + '.0'


# ----------------------------------------------------------------------------------------------- merge
def load_module(path):
    import importlib.util
    spec = importlib.util.spec_from_file_location('clips_' + path.replace('\\', '/').rsplit('/', 1)[-1][:-3], path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def merge(view, modules):
    """Runs every clip module for the model and writes the baked clips into view.anims. Returns the merged tables
    {'TAGS': {}, 'STATES': {}, 'IDLE': {}, 'TRANSITIONS': {}, 'PROCEDURAL': {}, 'authored': [names]}."""
    tables = {'TAGS': {}, 'STATES': {}, 'IDLE': {}, 'TRANSITIONS': {}, 'PROCEDURAL': {}, 'GAIT': {}, 'authored': []}
    for mod in modules:
        fn = getattr(mod, 'clips', None)
        if fn is not None:
            for c in fn(view) or []:
                view.clips[c.name] = c.bake()
                tables['authored'].append(c.name)
        for key in ('TAGS', 'STATES', 'TRANSITIONS', 'PROCEDURAL', 'GAIT'):
            v = getattr(mod, key, None)
            if callable(v):
                v = v(view)
            if v:
                tables[key].update(v)
        idle = getattr(mod, 'IDLE', None)
        if callable(idle):
            idle = idle(view)
        for posture, entries in (idle or {}).items():
            tables['IDLE'].setdefault(posture, []).extend(entries)
    return tables


# ----------------------------------------------------------------------------------------------- posture helpers
def transition(m, name, a, ta, b, tb, length, settle=0.0, ease=None):
    """A pose-to-pose transition clip: every bone of clip `a` at ta blends into clip `b` at tb ('end' = last frame).
    settle: seconds at the end spent exactly on the target pose (so the next stage starts where this one ends)."""
    c = Clip(m, name, length)
    pa, pb = m.pose(a, ta), m.pose(b, tb)
    c.blend_all(pa, pb, 0.0, max(0.05, length - settle), ease or step)
    return c


def posture_set(m, sit='sit', sleep='sleep', idle='idle', which=('sit_down', 'stand_up', 'lie_down', 'wake_up'),
                lengths=None):
    """The four posture transitions between idle, sit and sleep, authored from the poses themselves (they line up
    exactly with the clips they join): sit_down idle->sit, stand_up sit->idle, lie_down sit->sleep, wake_up sleep->sit."""
    L = {'sit_down': 0.8, 'stand_up': 0.7, 'lie_down': 1.2, 'wake_up': 1.3}
    L.update(lengths or {})
    out = []
    if 'sit_down' in which and m.has(sit):
        out.append(transition(m, 'sit_down', idle, 0, sit, 'end', L['sit_down'], 0.1))
    if 'stand_up' in which and m.has(sit):
        out.append(transition(m, 'stand_up', sit, 'end', idle, 0, L['stand_up'], 0.05))
    if 'lie_down' in which and m.has(sit) and m.has(sleep):
        out.append(transition(m, 'lie_down', sit, 'end', sleep, 0, L['lie_down'], 0.1))
    if 'wake_up' in which and m.has(sit) and m.has(sleep):
        out.append(transition(m, 'wake_up', sleep, 0, sit, 'end', L['wake_up'], 0.1))
    return out


def held_pose(m, name, pose, length=1.0):
    """A hold clip that sits on `pose` ({(bone, ch): xyz}) the whole time."""
    c = Clip(m, name, length, 'hold')
    for (bone, ch), v in pose.items():
        c.tracks[(bone, ch)] = (lambda v: lambda t: v)(v)
    return c


# ----------------------------------------------------------------------------------------------- write-time slimming
# How far a slimmed channel may move from the dense one (rotation degrees, position model pixels, scale factor). Above
# about 0.5 deg long necks and wings magnify the error visibly.
SLIM_TOL = {'rotation': 0.5, 'position': 0.05, 'scale': 0.008}
# GeckoLib bakes a pre / post keyframe pair 1.0E-5F seconds either side of its timestamp (ActorBoneAnimationKeyframe)
_GL_EPS = 9.999999747378752e-06
_SLIM_STEP = 0.01      # a segment shorter than this is a step (a Blockbench step hold, a pre / post pair): both ends stay
_SLIM_GRID = 0.01      # extra sample spacing for channels with catmull-rom segments


def _slim_vec(v):
    if not isinstance(v, (list, tuple)) or len(v) != 3:
        return None
    if any(isinstance(x, bool) or not isinstance(x, (int, float)) for x in v):
        return None
    return tuple(float(x) for x in v)


def _slim_keys(ch):
    """A GeckoLib channel {time: entry} -> sorted [(t, pre, post, mode, split, key, entry)], or None when slim_json
    must leave it alone (a bare vector, a single key, Molang, easings other than linear / catmullrom)."""
    if not isinstance(ch, dict) or len(ch) < 2 or any(k in ch for k in ('vector', 'pre', 'post')):
        return None
    out = []
    for k, e in ch.items():
        try:
            t = float(k)
        except (TypeError, ValueError):
            return None
        mode, split = 'linear', False
        if isinstance(e, dict):
            if set(e) - {'vector', 'pre', 'post', 'lerp_mode'}:
                return None
            mode = e.get('lerp_mode', 'linear')
            if mode not in ('linear', 'catmullrom'):
                return None
            if 'vector' in e:
                if 'pre' in e or 'post' in e:
                    return None
                pre = post = _slim_vec(e['vector'])
            elif 'pre' in e and 'post' in e:
                pre, post, split = _slim_vec(e['pre']), _slim_vec(e['post']), True
            else:
                return None
        else:
            pre = post = _slim_vec(e)
        if pre is None or post is None:
            return None
        out.append((t, pre, post, mode, split, k, e))
    out.sort(key=lambda x: x[0])
    if any(b[0] - a[0] <= 0 for a, b in zip(out, out[1:])):
        return None
    return out


def _gl_flat(keys):
    """Keys -> GeckoLib's baked keyframe list [(time, value, mode)]: a pre / post key bakes into two keyframes."""
    flat = []
    for t, pre, post, mode, split, _, _ in keys:
        if split:
            flat.append((t - _GL_EPS, pre, mode))
            flat.append((t + _GL_EPS, post, mode))
        else:
            flat.append((t, post, mode))
    return flat


def _gl_eval(flat, times, t):
    """GeckoLib's value of a baked channel at animation time t (AnimationPoint.findKeyframePointForward, then
    AnimationProcessor: lerp, or CatmullRomEasing.getPointOnSpline with its modifyKeyframes control points), as a
    3-tuple. `times` is [k[0] for k in flat]."""
    import bisect
    n = len(flat)
    if flat[0][0] > t:
        i = 0
    else:
        m = bisect.bisect_left(times, t, 1)
        i = m - 1 if m < n else n - 1
    j = min(i + 1, n - 1)
    length = flat[j][0] - (flat[j - 1][0] if j > 0 else 0.0)
    d = 0.0 if length == 0 else (t - flat[i][0]) / length
    frm, to = flat[i][1], flat[j][1]
    if flat[j][2] != 'catmullrom':
        return tuple(frm[a] + d * (to[a] - frm[a]) for a in range(3))
    if d >= 1:
        return to
    p0 = flat[j][1] if j == 0 else flat[j - 1][1] if j == 1 else flat[j - 2][1]
    p3 = flat[j][1] if j + 1 >= n else flat[j + 1][1]
    return tuple(0.5 * (2 * frm[a] + (to[a] - p0[a]) * d + (2 * p0[a] - 5 * frm[a] + 4 * to[a] - p3[a]) * d * d
                        + (3 * frm[a] - p0[a] - 3 * to[a] + p3[a]) * d * d * d) for a in range(3))


def _slim_keep(keys, tol, clip_len):
    """Indexes of the keys to keep (greedy, every check against the dense curve)."""
    n = len(keys)
    must = {0, n - 1}
    for i, k in enumerate(keys):
        if k[4]:
            must.add(i)        # spins wrap through pre / post keys: never drop one
    for i in range(n - 1):
        if keys[i + 1][0] - keys[i][0] < _SLIM_STEP:
            must.update((i, i + 1))
    if keys[0][0] > 1e-9:
        must.add(1)            # GeckoLib extrapolates from keys 0 -> 1 before the first key
    kept = [0]
    if all(k[3] == 'linear' for k in keys):
        # all-linear: the dense and the slimmed curve are both piecewise linear, so the largest gap over a removed run
        # is at one of the removed keys' times
        a = 0
        while a < n - 1:
            c = a + 1
            while c < n - 1 and c not in must:
                ta = keys[a][0] + (_GL_EPS if keys[a][4] else 0.0)
                tc = keys[c + 1][0] - (_GL_EPS if keys[c + 1][4] else 0.0)
                va, vc = keys[a][2], keys[c + 1][1]
                ok = True
                for m in range(a + 1, c + 1):
                    u = (keys[m][0] - ta) / (tc - ta)
                    v = keys[m][1]
                    if any(abs(va[x] + (vc[x] - va[x]) * u - v[x]) > tol for x in range(3)):
                        ok = False
                        break
                if not ok:
                    break
                c += 1
            kept.append(c)
            a = c
        return kept
    # catmull-rom segments: removing a key moves its neighbours' tangents, so each candidate is evaluated exactly (the
    # GeckoLib way) over two keys either side, at the dense keyframe times and on a fine grid
    flat = _gl_flat(keys)
    ftimes = [f[0] for f in flat]
    # GeckoLib clamps the animation time to [0, animation_length]
    end = clip_len if clip_len > 0 else keys[-1][0]
    pts = set(ftimes) | {g * _SLIM_GRID for g in range(int(end / _SLIM_GRID) + 1)} | {end}
    for t0, t1 in zip(ftimes, ftimes[1:]):
        if t1 - t0 > _SLIM_STEP:
            pts.update(t0 + (t1 - t0) * q / 4 for q in (1, 2, 3))
    samples = sorted(s for s in pts if 0.0 <= s <= end)
    dense = {s: _gl_eval(flat, ftimes, s) for s in samples}
    import bisect

    def ok(cand, p):
        lo = keys[cand[max(0, p - 2)]][0] if p - 2 > 0 else -1.0
        hi = keys[cand[p + 3]][0] if p + 3 < len(cand) - 1 else end
        w0 = max(0, p - 5)
        win = [keys[i] for i in cand[w0:p + 7]]
        wf = _gl_flat(win)
        wt = [f[0] for f in wf]
        for s in samples[bisect.bisect_left(samples, lo):bisect.bisect_right(samples, hi)]:
            v, d = _gl_eval(wf, wt, s), dense[s]
            if any(abs(v[x] - d[x]) > tol for x in range(3)):
                return False
        return True
    a = 0
    while a < n - 1:
        c = a + 1
        while c < n - 1 and c not in must and ok(kept + list(range(c + 1, n)), len(kept) - 1):
            c += 1
        kept.append(c)
        a = c
    return kept


def slim_json(anims, tol=None):
    """Write-time keyframe slimming (in place; returns `anims`): drops every key whose removal keeps the channel
    within `tol` (SLIM_TOL) of the dense curve as GeckoLib evaluates it. First and last keys, pre / post keys and both
    ends of step segments always stay, so posture transitions land exactly and spins keep their wrap keys. Kept keys
    are written back as they were (bare vector, {vector, lerp_mode} or {pre, post[, lerp_mode]}). Channels with Molang,
    other easings or a single key are left alone. Call it on a copy: the tools sample the dense data."""
    tol = dict(SLIM_TOL, **(tol or {}))
    for clip in (anims.get('animations') or {}).values():
        try:
            clip_len = float(clip.get('animation_length') or 0)
        except (TypeError, ValueError):
            clip_len = 0.0
        for chs in (clip.get('bones') or {}).values():
            for name in ('rotation', 'position', 'scale'):
                keys = _slim_keys(chs.get(name))
                if keys is None or len(keys) < 3:
                    continue
                keep = _slim_keep(keys, tol[name], clip_len)
                if len(keep) < len(keys):
                    chs[name] = {keys[i][5]: keys[i][6] for i in keep}
    return anims


# ----------------------------------------------------------------------------------------------- GeckoLib convention
def _flip_vec(v):
    if not isinstance(v, (list, tuple)) or len(v) < 2:
        return v
    out = list(v)
    for i in (0, 1):
        x = out[i]
        if isinstance(x, (int, float)):
            out[i] = _num(-x) if x != 0 else x
        elif isinstance(x, str) and x.strip():
            out[i] = '-(%s)' % x if not x.startswith('-(') else x[2:-1]
    return out


def flip_rotations(anim_json):
    """Blockbench / ModelEngine keyframe rotations <-> GeckoLib: GeckoLib negates rotation x and y when it loads a
    bedrock animation (ActorBoneAnimationKeyframe), so the .bbmodel values (Blockbench convention, what bb-render and
    the ModelEngine server showed) are written with x and y negated. The same call converts back (it is an involution).
    Returns a new dict; positions and scales are untouched."""
    import copy
    out = copy.deepcopy(anim_json)
    for clip in out.get('animations', {}).values():
        for chs in (clip.get('bones') or {}).values():
            rot = chs.get('rotation')
            if rot is None:
                continue
            if isinstance(rot, list):
                chs['rotation'] = _flip_vec(rot)
                continue
            if isinstance(rot, dict) and any(k in rot for k in ('vector', 'pre', 'post')):
                rot = {'0.0': rot}
                chs['rotation'] = rot
            for k, e in rot.items():
                if isinstance(e, dict):
                    for side in ('pre', 'post', 'vector'):
                        if side in e:
                            e[side] = _flip_vec(e[side])
                else:
                    rot[k] = _flip_vec(e)
    return out


def load_gecko(path):
    """A generated <model>.animation.json, back in Blockbench convention (what ModelView / the tools work in)."""
    import json
    return flip_rotations(json.load(open(path, encoding='utf-8')))
