"""Shared authoring helpers for the phase-2 mounts / tameable hostiles (dunebun, snowmuff, cinderwing_phoenix,
tempest_roc, tidemane, cinder_imp, rime_wraith, volt_wisp, geode_crawler, lurefin). Not a model module itself
(gen_creatures only loads clips/<model>.py); the species modules import it.

Everything here works on frozen copies of the source clips, so a module may replace a clip ('idle', 'walk') and still
derive others from the original."""
import copy
import math

from animkit import Clip, keys, pulse, sample, smooth, step, wave, overshoot, decay  # noqa: F401

REST = {'rotation': (0.0, 0.0, 0.0), 'position': (0.0, 0.0, 0.0), 'scale': (1.0, 1.0, 1.0)}


def t3(v):
    if isinstance(v, (int, float)):
        return (float(v),) * 3
    return tuple(float(x) for x in v)


def frozen(m, clip):
    """A deep copy of a generated clip (survives the clip being replaced later)."""
    return copy.deepcopy(m.clips[clip])


def chan(src, bone, ch):
    return ((src.get('bones') or {}).get(bone) or {}).get(ch)


def sampler(src, bone, ch, offset=0.0, loop=True, speed=1.0):
    """f(t) sampling a frozen clip channel (modulo its length when loop)."""
    data = chan(src, bone, ch)
    L = float(src.get('animation_length') or 0)
    rest = REST[ch]
    if data is None:
        return lambda t: rest

    def f(t):
        u = offset + t * speed
        if loop and L > 0:
            u %= L
        else:
            u = min(max(0.0, u), L)
        return sample(data, u, rest)
    return f


def copy_from(c, src, offset=0.0, loop=True, speed=1.0, skip=(), only=None):
    """Every track of a frozen clip into c (modulo its length when loop)."""
    for bone, chs in (src.get('bones') or {}).items():
        if bone in skip or (only is not None and bone not in only):
            continue
        if not c.m.has_bone(bone):
            continue
        for ch in chs:
            c.tracks[(bone, ch)] = sampler(src, bone, ch, offset, loop, speed)
    return c


def pose_of(src, t=0.0):
    """{(bone, ch): xyz} of a frozen clip at t ('end' = last frame)."""
    L = float(src.get('animation_length') or 0)
    if t == 'end':
        t = L
    out = {}
    for bone, chs in (src.get('bones') or {}).items():
        for ch, data in chs.items():
            out[(bone, ch)] = sample(data, t, REST[ch])
    return out


def hold(c, pose, skip=()):
    for (bone, ch), v in pose.items():
        if bone in skip or not c.m.has_bone(bone):
            continue
        c.tracks[(bone, ch)] = (lambda v: lambda t: v)(v)
    return c


def add(c, bone, ch, fn):
    """Adds a delta track fn(t) -> xyz onto whatever c already has for (bone, ch) (or the rest value)."""
    if bone is None or not c.m.has_bone(bone):
        return c
    base = c.tracks.get((bone, ch))
    rest = REST[ch]
    if base is None:
        c.tracks[(bone, ch)] = (lambda f: lambda t: tuple(r + d for r, d in zip(rest, t3(f(t)))))(fn)
    else:
        c.tracks[(bone, ch)] = (lambda b, f: lambda t: tuple(x + d for x, d in zip(t3(b(t)), t3(f(t)))))(base, fn)
    return c


def rot(c, bone, fn):
    return add(c, bone, 'rotation', fn)


def pos(c, bone, fn):
    return add(c, bone, 'position', fn)


def scl(c, bone, fn):
    return add(c, bone, 'scale', fn)


def mirror(v):
    """A right-side rotation mirrored to the left side (y and z flip)."""
    return (v[0], -v[1], -v[2])


def pair(c, right, left, fn):
    """fn(t) -> right-side rotation delta; the left bone gets it mirrored."""
    rot(c, right, fn)
    rot(c, left, lambda t: mirror(t3(fn(t))))
    return c


def env(t, a, b, c_, d):
    """0 before a, up to 1 by b, held, down to 0 between c_ and d."""
    return step(t, a, b) * (1 - step(t, c_, d))


def blend_to(c, pose_fn, e):
    """Every track of c blends toward pose_fn(bone, ch, t) by e(t) (0 = c, 1 = pose)."""
    for key in list(c.tracks):
        f = c.tracks[key]
        c.tracks[key] = (lambda f, key: lambda t: (lambda a, b, u: tuple(x + (y - x) * u for x, y in zip(a, b)))(
            t3(f(t)), t3(pose_fn(key[0], key[1], t)), e(t)))(f, key)
    return c


def subtree(m, root):
    out = [root] if m.has_bone(root) else []
    i = 0
    while i < len(out):
        out += m.children(out[i])
        i += 1
    return out


def lerp(a, b, u):
    return tuple(x + (y - x) * u for x, y in zip(t3(a), t3(b)))


# ------------------------------------------------------------------------------------------------- birds
def fold(m, name, folded, wing_bones, k=0.3, spread=None, new_name=None):
    """Re-authors a ground clip whose wings were left spread / flapping: the wing bones sit in the folded pose plus k x
    the original motion around its mean; spread(t) (0..1) lets the original wing motion through (a celebratory
    spread that opens and refolds). Everything else is the original clip."""
    src = frozen(m, name)
    L = float(src.get('animation_length') or 0.05)
    loop = {True: 'loop', 'hold_on_last_frame': 'hold'}.get(src.get('loop'), 'once')
    c = Clip(m, new_name or name, L, loop)
    copy_from(c, src, loop=False, skip=set(wing_bones))
    for b in wing_bones:
        f = sampler(src, b, 'rotation', loop=False)
        n = 24
        vals = [t3(f(L * i / n)) for i in range(n + 1)]
        mean = tuple(sum(v[j] for v in vals) / len(vals) for j in range(3))
        fv = t3(folded.get((b, 'rotation'), (0, 0, 0)))
        if chan(src, b, 'rotation') is None and fv == (0, 0, 0):
            continue

        def g(t, f=f, mean=mean, fv=fv):
            o = t3(f(t))
            v = tuple(fv[j] + k * (o[j] - mean[j]) for j in range(3))
            e = spread(t) if spread else 0.0
            return tuple(v[j] + (o[j] - v[j]) * e for j in range(3))
        c.tracks[(b, 'rotation')] = g
        for ch in ('position', 'scale'):
            if chan(src, b, ch) is not None:
                c.tracks[(b, ch)] = sampler(src, b, ch, loop=False)
    return c


def keep(m, c):
    """Bakes c into the model now and freezes it: the module's lambdas read names (L, e, up ...) that later clips in
    the same function reassign, and animkit.merge would only bake after clips() returns."""
    data = c.bake()
    m.clips[c.name] = data
    c.bake = lambda: data
    return c


def alias(m, name, src_name):
    """A one-shot copy of another clip of the model under a reaction name (pet, pet_sit, bond_level), sounds included."""
    from animkit import Clip as _Clip
    src = frozen(m, src_name)
    c = _Clip(m, name, float(src['animation_length']), 'once')
    copy_from(c, src, loop=False)
    for k, v in (src.get('sound_effects') or {}).items():
        c.sounds.append((float(k), v['effect']))
    return keep(m, c)


def reactions(m, out, pet=None, pet_sit=None, bond_level=None):
    """Adds the reaction aliases (each only if the source clip exists) to out."""
    for name, src in (('pet', pet), ('pet_sit', pet_sit), ('bond_level', bond_level)):
        if src and m.has(src):
            out.append(alias(m, name, src))
    return out
