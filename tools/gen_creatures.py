#!/usr/bin/env python3
"""Arcana creatures -> native GeckoLib entities (Aftr Hrs mod). Re-runnable.

Reads (read-only):
  MythicMobs/packs/Arcana/models/*.bbmodel      ModelEngine blueprints (bones, texture, animation clips)
  MythicMobs/packs/Arcana/tools/specs/*.json    tameables (pets, dogs, cats, mounts, tameable hostiles, Luna, Kevin, Lurefin)
  MythicMobs/packs/Arcana/tools/wild_specs/*.json  wildlife, livestock (+ young forms), undead, duskflies
  MythicMobs/packs/Arcana/mobs/{bosses,wukong,police}.yml   hand-written bosses and the police officer

Writes (mod/src/main/resources):
  assets/aftrhrs/geckolib/models/entity/<model>.geo.json        bedrock geometry (GeckoLib 5.5 path)
  assets/aftrhrs/geckolib/animations/entity/<model>.animation.json
  assets/aftrhrs/textures/entity/<model>.png (+ <model>_glowmask.png for glow bones, GeckoLib AutoGlowingGeoLayer)
  assets/aftrhrs/textures/item/spawn_egg/<id>.png, assets/aftrhrs/items/<id>_spawn_egg.json, models/item/<id>_spawn_egg.json
  assets/aftrhrs/lang/en_us.entities.json   entity + spawn egg names (folded into en_us.json by the merge step)
  data/aftrhrs/species/<id>.json + data/aftrhrs/species/index.json   the species table the Java side reads at init
  assets/aftrhrs/sounds.json, lang/en_us.sounds.json, data/aftrhrs/sfx/shared.json   creature sound profiles (sfx_gen.py
      from the hand-written tools/sfx_profiles.py; each species table gets its "sfx" block)

Conversions follow Blockbench's own bedrock codec: bone pivot x and cube origin x are mirrored (bedrock x points the other
way), bone/cube rotations negate x and y, up/down face UVs are flipped. Keyframe rotations negate x and y too
(animkit.flip_rotations: GeckoLib negates them again on load; copying them as they were turned every pitch and yaw
around in game, e.g. sits pitched the front down). The tools work on Blockbench-convention clips in memory. Blockbench's interpolation (catmullrom if either end is smooth, step holds the start
value) is mapped onto GeckoLib's per-keyframe lerp_mode (the mode on a keyframe drives the segment that ends there).
"""
import base64
import collections
import copy
import io
import json
import os
import re
import sys

try:
    import yaml
except ImportError:  # only needed for the hand-written bosses
    yaml = None
from PIL import Image

import animkit
import animtags
from spawn_egg_art import write_egg

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
RES = os.path.join(MOD, 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'aftrhrs')
DATA = os.path.join(RES, 'data', 'aftrhrs')
PACK = os.environ.get('ARCANA_PACK', r'C:\Users\kevin\Desktop\plugins\MythicMobs\packs\Arcana')
MODELS = os.path.join(PACK, 'models')
# Models used instead of the pack's copy. 2026-11-19: Wukong
# uses its pre-polish3 version (Documents/Blockbench/models/arcana/backup_prepolish3/): the user preferred it.
OVERRIDES = os.path.join(HERE, 'model_overrides')
SPECS = os.path.join(PACK, 'tools', 'specs')
# Authored animation clips + animation tables per model (mod/tools/clips/<model>.py, see animkit.py); merged into the
# generated <model>.animation.json, never into the source bbmodel.
CLIPS = os.path.join(HERE, 'clips')
WILD = os.path.join(PACK, 'tools', 'wild_specs')

# ModelEngine helper bones that never render (BbModel.hidden in the Companion client): hitbox / shadow cubes,
# sub-hitboxes, seats, name tags and leash points. Their positions become species data (seats, name tag, shadow).
HELPER = re.compile(r'^(hitbox.*|shadow|mount|seat.*|tag_.*|p_.*|l_.*|b_.*|ob_.*)$', re.I)
# ModelEngine behaviour tags on bone names (h_head = head-tracking bone "head")
TAG = re.compile(r'^(h|hi|g|b|ob|l|tag|p|seg|segf|tl|tlf|jig|ih|ir|il)(\[[^\]]*\])?_')

# gen_mm.py / gen_wild.py TERRALITH (Terralith 2.6.5 look-alike biomes added to every overworld rule)
TERRALITH = {
    'PLAINS': ['steppe', 'valley_clearing', 'highlands', 'temperate_highlands', 'lush_valley', 'yellowstone'],
    'SUNFLOWER_PLAINS': ['blooming_valley', 'valley_clearing'],
    'MEADOW': ['highlands', 'temperate_highlands', 'blooming_plateau', 'lush_valley', 'alpine_highlands', 'lavender_valley'],
    'FLOWER_FOREST': ['lavender_forest', 'lavender_valley', 'blooming_valley', 'moonlight_grove', 'moonlight_valley'],
    'CHERRY_GROVE': ['sakura_grove', 'sakura_valley'],
    'FOREST': ['cloud_forest', 'forested_highlands', 'yosemite_lowlands'],
    'BIRCH_FOREST': ['birch_taiga'],
    'OLD_GROWTH_BIRCH_FOREST': ['birch_taiga'],
    'TAIGA': ['shield', 'shield_clearing', 'birch_taiga', 'yosemite_lowlands', 'forested_highlands'],
    'OLD_GROWTH_PINE_TAIGA': ['shield'],
    'OLD_GROWTH_SPRUCE_TAIGA': ['shield'],
    'WINDSWEPT_FOREST': ['yosemite_cliffs', 'forested_highlands'],
    'SNOWY_TAIGA': ['siberian_taiga', 'snowy_shield', 'wintry_forest', 'snowy_maple_forest'],
    'SNOWY_PLAINS': ['wintry_lowlands', 'cold_shrubland', 'ice_marsh'],
    'SNOWY_SLOPES': ['alpine_grove', 'frozen_cliffs', 'scarlet_mountains'],
    'GROVE': ['alpine_grove', 'siberian_grove', 'snowy_cherry_grove'],
    'ICE_SPIKES': ['glacial_chasm'],
    'FROZEN_PEAKS': ['frozen_cliffs', 'emerald_peaks'],
    'JAGGED_PEAKS': ['emerald_peaks', 'windswept_spires'],
    'STONY_PEAKS': ['stony_spires', 'rocky_mountains', 'haze_mountain'],
    'WINDSWEPT_HILLS': ['rocky_mountains', 'windswept_spires', 'alpine_highlands', 'haze_mountain'],
    'WINDSWEPT_GRAVELLY_HILLS': ['gravel_desert', 'rocky_shrubland'],
    'STONY_SHORE': ['basalt_cliffs', 'granite_cliffs', 'white_cliffs', 'gravel_beach'],
    'BEACH': ['gravel_beach'],
    # Creatures 2 (ocean): Terralith's one ocean biome, a deeper warm ocean
    'WARM_OCEAN': ['deep_warm_ocean'],
    'RIVER': ['warm_river'],
    'SWAMP': ['orchid_swamp'],
    'MANGROVE_SWAMP': ['orchid_swamp'],
    'DESERT': ['ancient_sands', 'desert_canyon', 'desert_oasis', 'desert_spires', 'lush_desert', 'sandstone_valley'],
    'SAVANNA': ['ashen_savanna', 'fractured_savanna', 'brushland', 'hot_shrubland', 'shrubland'],
    'SAVANNA_PLATEAU': ['arid_highlands', 'savanna_slopes'],
    'WINDSWEPT_SAVANNA': ['fractured_savanna', 'savanna_slopes'],
    'BADLANDS': ['bryce_canyon', 'white_mesa', 'warped_mesa', 'red_oasis', 'savanna_badlands', 'painted_mountains'],
    'WOODED_BADLANDS': ['savanna_badlands'],
    'ERODED_BADLANDS': ['bryce_canyon', 'painted_mountains', 'volcanic_crater', 'volcanic_peaks'],
    'JUNGLE': ['tropical_jungle', 'rocky_jungle', 'jungle_mountains'],
    'SPARSE_JUNGLE': ['tropical_jungle', 'rocky_jungle'],
    'BAMBOO_JUNGLE': ['tropical_jungle', 'jungle_mountains'],
    'DARK_FOREST': [],
    'DRIPSTONE_CAVES': ['cave/deep_caves', 'cave/infested_caves', 'cave/thermal_caves', 'cave/frostfire_caves'],
    'LUSH_CAVES': ['cave/underground_jungle', 'cave/fungal_caves'],
    'DEEP_DARK': ['cave/deep_caves'],
}
DIMENSIONS = {'world': 'minecraft:overworld', 'overworld': 'minecraft:overworld', 'minecraft:overworld': 'minecraft:overworld',
              'world_nether': 'minecraft:the_nether', 'nether': 'minecraft:the_nether', 'the_nether': 'minecraft:the_nether',
              'minecraft:the_nether': 'minecraft:the_nether', 'world_the_end': 'minecraft:the_end', 'the_end': 'minecraft:the_end',
              'end': 'minecraft:the_end', 'minecraft:the_end': 'minecraft:the_end'}

# gen_mm.py mood brain tables
COLD_NATIVE = {'ArcanaSnowpeltOwlet', 'ArcanaRimeWraith', 'ArcanaSnowmuff', 'ArcanaHusky', 'ArcanaHuskyCopper',
               'ArcanaSaintBernard', 'ArcanaMaineCoon'}
HOT_NATIVE = {'ArcanaEmberkit', 'ArcanaCinderImp', 'ArcanaCinderwingMount', 'ArcanaWickling'}
WATER_LOVERS = {'ArcanaRunebackTortoise', 'ArcanaGoldenRetriever'}
POOLS = {
    'idle': ['sniff', 'scratch', 'groom', 'stretch', 'look_around', 'sigh', 'curious', 'sniff', 'groom', 'sneeze'],
    'idle_sleepy': ['yawn', 'nod_off', 'yawn', 'stretch', 'groom'],
    'idle_cold': ['shiver', 'shiver', 'groom', 'sniff'],
    'idle_hot': ['pant', 'pant', 'groom', 'sigh'],
    'idle_rain': ['shake', 'groom', 'shake'],
    'idle_happy': ['wiggle', 'spin', 'roll_over', 'play_bow', 'hop'],
    'idle_beg': ['beg_trick', 'curious', 'hop', 'beg_trick'],
    'idle_alert': ['sniff', 'look_around'],
    'idle_worried': ['notice', 'curious'],
    'idle_hurt': ['sigh'],
    'idle_scared': ['cower'],
    'dance': ['dance'],
    'dawn': ['stretch', 'yawn'],
}

PREFIX = 'arcana_'



# Not undead, but they burn in daylight like zombies while wild (a sun-shy snow spirit).
SUNBURN = {'rime_wraith'}

def short(model):
    return model[len(PREFIX):] if model and model.startswith(PREFIX) else model


def read_json(path):
    text = open(path, encoding='utf-8').read()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        text = re.sub(r'(?m)\s//[^\n"]*$', '', text)
        text = re.sub(r'(?m)^\s*//.*$', '', text)
        return json.loads(text)


def write_json(path, obj, compact=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        if compact:
            json.dump(obj, f, separators=(',', ':'), ensure_ascii=False)
        else:
            json.dump(obj, f, indent=1, ensure_ascii=False)
        f.write('\n')


def num(v):
    if isinstance(v, str):
        v = v.strip()
        if v == '':
            return 0.0
        return float(v)
    return float(v)


def rnd(v, d=4):
    v = round(float(v), d)
    return int(v) if v == int(v) else v


# ---------------------------------------------------------------------------------------------
# bbmodel -> GeckoLib
# ---------------------------------------------------------------------------------------------

class Model:
    """One converted bbmodel: geo + animations + texture, plus helper-bone data for the species table."""

    def __init__(self, path):
        self.path = path
        self.name = short(os.path.basename(path)[:-len('.bbmodel')])
        self.bb = json.load(open(path, encoding='utf-8'))
        res = self.bb.get('resolution') or {}
        self.tw, self.th = int(res.get('width', 64)), int(res.get('height', 64))
        self.groups = {g['uuid']: g for g in self.bb.get('groups', [])}
        self.elements = {e['uuid']: e for e in self.bb.get('elements', [])}
        self.helpers = {}        # helper bone name -> (origin, [(from, to)])
        self.bones = []          # geo bones in outliner order
        self.bone_names = set()
        self.bone_cubes = {}     # bone name -> [element]
        self.bounds = [1e9, 1e9, 1e9, -1e9, -1e9, -1e9]
        self._walk(self.bb.get('outliner', []), None)
        self.clips = {}
        for a in self.bb.get('animations', []):
            self.clips[a['name']] = {'length': rnd(a.get('length') or 0, 4), 'loop': a.get('loop', 'once')}

    def _walk(self, items, parent):
        for x in items:
            if not isinstance(x, dict):
                continue
            g = self.groups.get(x.get('uuid'))
            if g is None:
                continue
            name = g['name']
            children = x.get('children', [])
            if HELPER.match(name):
                cubes = [self.elements[c] for c in children if isinstance(c, str) and c in self.elements]
                self.helpers[name] = (g.get('origin', [0, 0, 0]), [(c['from'], c['to']) for c in cubes])
                continue
            if g.get('visibility') is False or g.get('export') is False:
                continue
            o = g.get('origin', [0, 0, 0])
            r = g.get('rotation', [0, 0, 0])
            bone = {'name': name, 'pivot': [rnd(-o[0]), rnd(o[1]), rnd(o[2])]}
            if parent:
                bone['parent'] = parent
            if any(abs(v) > 1e-6 for v in r):
                bone['rotation'] = [rnd(-r[0]), rnd(-r[1]), rnd(r[2])]
            cubes = []
            self.bone_cubes[name] = []
            for c in children:
                if isinstance(c, str):
                    e = self.elements.get(c)
                    if e is None or e.get('type', 'cube') != 'cube' or e.get('visibility') is False or e.get('export') is False:
                        continue
                    cube = self._cube(e)
                    if cube:
                        cubes.append(cube)
                        self.bone_cubes[name].append(e)
            if cubes:
                bone['cubes'] = cubes
            self.bones.append(bone)
            self.bone_names.add(name)
            self._walk([c for c in children if isinstance(c, dict)], name)

    def _cube(self, e):
        f, t = e['from'], e['to']
        inflate = float(e.get('inflate') or 0)
        size = [rnd(t[i] - f[i]) for i in range(3)]
        cube = {'origin': [rnd(-t[0]), rnd(f[1]), rnd(f[2])], 'size': size}
        if inflate:
            cube['inflate'] = rnd(inflate)
        r = e.get('rotation', [0, 0, 0])
        if any(abs(v) > 1e-6 for v in r):
            o = e.get('origin', [0, 0, 0])
            cube['pivot'] = [rnd(-o[0]), rnd(o[1]), rnd(o[2])]
            cube['rotation'] = [rnd(-r[0]), rnd(-r[1]), rnd(r[2])]
        for i in range(3):
            self.bounds[i] = min(self.bounds[i], f[i] - inflate, t[i] - inflate)
            self.bounds[i + 3] = max(self.bounds[i + 3], f[i] + inflate, t[i] + inflate)
        if e.get('box_uv'):
            cube['uv'] = list(e.get('uv_offset', [0, 0]))
            if e.get('mirror_uv'):
                cube['mirror'] = True
            return cube
        uv = {}
        for face, fd in (e.get('faces') or {}).items():
            if fd.get('texture') is None:
                continue
            u = fd.get('uv', [0, 0, 0, 0])
            size_uv = [u[2] - u[0], u[3] - u[1]]
            start = [u[0], u[1]]
            if face in ('up', 'down'):
                start = [u[0] + size_uv[0], u[1] + size_uv[1]]
                size_uv = [-size_uv[0], -size_uv[1]]
            entry = {'uv': [rnd(start[0]), rnd(start[1])], 'uv_size': [rnd(size_uv[0]), rnd(size_uv[1])]}
            if fd.get('rotation'):
                entry['uv_rotation'] = fd['rotation']
            uv[face] = entry
        cube['uv'] = uv
        return cube

    def geo(self):
        b = self.bounds
        w = max(b[3] - b[0], b[5] - b[2]) / 16 if b[0] < 1e8 else 1
        h = (b[4] - b[1]) / 16 if b[0] < 1e8 else 1
        return {
            'format_version': '1.12.0',
            'minecraft:geometry': [{
                'description': {
                    'identifier': 'geometry.' + self.name,
                    'texture_width': self.tw, 'texture_height': self.th,
                    'visible_bounds_width': rnd(w + 1, 2), 'visible_bounds_height': rnd(h + 1, 2),
                    'visible_bounds_offset': [0, rnd(h / 2, 2), 0],
                },
                'bones': self.bones,
            }],
        }

    def animations(self):
        bone_by_uuid = {u: g['name'] for u, g in self.groups.items()}
        out = {}
        for a in self.bb.get('animations', []):
            loop = {'loop': True, 'hold': 'hold_on_last_frame'}.get(a.get('loop', 'once'), False)
            anim = {'loop': loop, 'animation_length': rnd(a.get('length') or 0.05, 4), 'bones': {}}
            for key, an in (a.get('animators') or {}).items():
                if an.get('type', 'bone') != 'bone':
                    continue
                bone = bone_by_uuid.get(key) or an.get('name')
                if bone not in self.bone_names:
                    continue
                chans = {}
                for ch in ('rotation', 'position', 'scale'):
                    kfs = [k for k in an.get('keyframes', []) if k.get('channel') == ch and k.get('data_points')]
                    if not kfs:
                        continue
                    chans[ch] = self._channel(kfs, 1.0 if ch == 'scale' else 0.0)
                if chans:
                    anim['bones'][bone] = chans
            out[a['name']] = anim
        return {'format_version': '1.8.0', 'animations': out}

    @staticmethod
    def _channel(kfs, fallback):
        kfs = sorted(kfs, key=lambda k: float(k.get('time', 0)))
        pts = []
        for k in kfs:
            dp = k['data_points']
            vals = []
            for p in (dp[0], dp[-1]):
                v = []
                for ax in 'xyz':
                    x = p.get(ax, fallback)
                    try:
                        v.append(rnd(num(x)))
                    except (TypeError, ValueError):
                        v.append(str(x))  # Molang expression: GeckoLib evaluates it
                vals.append(v)
            pts.append((float(k.get('time', 0)), vals[0], vals[1], (k.get('interpolation') or 'linear').lower()))
        out = {}

        def tkey(t):
            s = ('%.4f' % t).rstrip('0').rstrip('.')
            return s if '.' in s else s + '.0'
        for i, (t, pre, post, interp) in enumerate(pts):
            mode = 'linear'
            if i > 0:
                prev = pts[i - 1]
                if prev[3] == 'step':
                    # Blockbench step: hold the previous value until this keyframe
                    hold_t = max(prev[0], t - 0.001)
                    if hold_t > prev[0]:
                        out[tkey(hold_t)] = prev[2]
                    mode = 'linear'
                elif prev[3] == 'catmullrom' or interp == 'catmullrom':
                    mode = 'catmullrom'
            if pre != post:
                entry = {'pre': pre, 'post': post, 'lerp_mode': mode}
            elif mode == 'linear':
                entry = post          # a bare vector is a linear keyframe
            else:
                entry = {'vector': post, 'lerp_mode': mode}
            out[tkey(t)] = entry
        return out

    def texture(self):
        tex = (self.bb.get('textures') or [None])[0]
        if not tex or not tex.get('source', '').startswith('data:image'):
            return None
        data = base64.b64decode(tex['source'].split(',', 1)[1])
        return Image.open(io.BytesIO(data)).convert('RGBA')

    def glowmask(self, img, glow_bones):
        """Copies the texels of every face of every cube in a glow bone (or with light_emission) onto a transparent
        image: GeckoLib's AutoGlowingGeoLayer draws <texture>_glowmask fullbright over the model.
        Returns (mask, bones): the geo bones whose own cubes went into the mask (outliner order, not their children),
        the only bones ArcanaGeoRenderer.GlowLayer draws again; (None, []) when nothing glows."""
        cubes, took = [], set()
        for b in glow_bones:
            for name, els in self.bone_cubes.items():
                if name == b or TAG.sub('', name, count=1) == b:
                    cubes += els
                    if els:
                        took.add(name)
        for name, els in self.bone_cubes.items():
            lit = [e for e in els if (e.get('light_emission') or 0) > 0]
            cubes += lit
            if lit:
                took.add(name)
        if not cubes:
            return None, []
        bones = [b['name'] for b in self.bones if b['name'] in took]
        mask = Image.new('RGBA', img.size, (0, 0, 0, 0))
        sx, sy = img.size[0] / self.tw, img.size[1] / self.th
        for e in cubes:
            for fd in (e.get('faces') or {}).values():
                if fd.get('texture') is None:
                    continue
                u = fd['uv']
                x0, x1 = sorted((u[0] * sx, u[2] * sx))
                y0, y1 = sorted((u[1] * sy, u[3] * sy))
                box = (int(x0), int(y0), max(int(x0) + 1, int(round(x1))), max(int(y0) + 1, int(round(y1))))
                mask.paste(img.crop(box), box[:2])
        return mask, bones

    def helper_point(self, name):
        h = self.helpers.get(name)
        if not h:
            return None
        o = h[0]
        # entity space in blocks: x as is, y up, model front (-Z) is the entity's forward (+Z local)
        return [rnd(o[0] / 16, 4), rnd(o[1] / 16, 4), rnd(-o[2] / 16, 4)]

    def helper_box(self, name):
        h = self.helpers.get(name)
        if not h or not h[1]:
            return None
        f, t = h[1][0]
        return [abs(t[0] - f[0]) / 16, abs(t[1] - f[1]) / 16, abs(t[2] - f[2]) / 16]


# ---------------------------------------------------------------------------------------------
# MythicMobs lines -> structured ability / effect entries
# ---------------------------------------------------------------------------------------------

def tokenize(line):
    """Split a MythicMobs skill line on spaces outside {} / []."""
    out, cur, depth = [], '', 0
    for ch in line:
        if ch in '{[':
            depth += 1
        elif ch in '}]':
            depth -= 1
        if ch == ' ' and depth == 0:
            if cur:
                out.append(cur)
            cur = ''
        else:
            cur += ch
    if cur:
        out.append(cur)
    return out


BOND_RE = re.compile(r'%bond\(([-\d.]+),([-\d.]+)\)%')
BONDLVL_RE = re.compile(r'%bondlvl\(([-\d.]+),([-\d.]+)\)%')


def value(v):
    """A mechanic argument: number, bond macro ({base, step} / {base, every}) or string."""
    v = v.strip()
    m = BOND_RE.fullmatch(v)
    if m:
        return {'base': float(m.group(1)), 'step': float(m.group(2))}
    m = BONDLVL_RE.fullmatch(v)
    if m:
        return {'base': float(m.group(1)), 'every': float(m.group(2))}
    try:
        f = float(v)
        return int(f) if f == int(f) and '.' not in v else f
    except ValueError:
        if v.lower() in ('true', 'false'):
            return v.lower() == 'true'
        return v


def parse_args(body):
    args, cur, depth = {}, '', 0
    parts = []
    for ch in body:
        if ch in '{[':
            depth += 1
        elif ch in '}]':
            depth -= 1
        if ch == ';' and depth == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += ch
    if cur:
        parts.append(cur)
    for p in parts:
        if '=' in p:
            k, v = p.split('=', 1)
            args[k.strip()] = value(v)
    return args


def parse_line(line):
    """'- potion{type=X;duration=60} @target ~onAttack ?hasowner 0.5' -> dict."""
    line = line.strip()
    if line.startswith('- '):
        line = line[2:]
    toks = tokenize(line)
    if not toks:
        return None
    mech = toks[0]
    m = re.match(r'^([A-Za-z_:]+)(\{(.*)\})?$', mech)
    name = m.group(1) if m else mech
    args = parse_args(m.group(3)) if m and m.group(3) else {}
    out = {'mechanic': name.lower(), 'args': args, 'target': 'self', 'trigger': None, 'every': 0, 'conditions': [], 'chance': 1.0}
    for t in toks[1:]:
        if t.startswith('@'):
            tm = re.match(r'^@([A-Za-z_]+)(\{(.*)\})?$', t)
            out['target'] = tm.group(1).lower() if tm else t[1:].lower()
            if tm and tm.group(3):
                out['target_args'] = parse_args(tm.group(3))
        elif t.startswith('~'):
            trig = t[1:]
            if ':' in trig:
                trig, arg = trig.split(':', 1)
                if trig.lower() == 'ontimer':
                    out['every'] = int(arg)
                else:
                    out['trigger_arg'] = arg
            out['trigger'] = trig.lower()
        elif t.startswith('?'):
            out['conditions'].append(t[1:])
        else:
            try:
                out['chance'] = float(t)
            except ValueError:
                out.setdefault('extra', []).append(t)
    if name.lower() == 'skill':
        out['skill'] = args.get('s') or args.get('skill')
    return out


def mat(m):
    if not m:
        return None
    return 'minecraft:' + str(m).lower()


def sound(entry):
    """[key, pitch, volume] -> {id, pitch, volume}."""
    if not entry:
        return None
    return {'id': entry[0], 'pitch': float(entry[1]), 'volume': float(entry[2])}


def display(text):
    m = re.match(r'^((?:&[0-9a-fk-or])*)(.*)$', text or '')
    codes = re.findall(r'&([0-9a-f])', m.group(1)) if m else []
    name = re.sub(r'<[^>]+>', '', m.group(2) if m else text)
    return name.strip(), (codes[-1] if codes else 'f')


def drops(lines):
    out = []
    for d in lines or []:
        p = d.split()
        if not p:
            continue
        if p[0] == 'exp':
            out.append({'exp': int(float(p[1].split('to')[0])), 'chance': float(p[2]) if len(p) > 2 else 1})
            continue
        if p[0] == 'oraxen':
            item, amt, ch = p[1], p[2] if len(p) > 2 else '1', p[3] if len(p) > 3 else '1'
            custom = True
        else:
            item, amt, ch = p[0], p[1] if len(p) > 1 else '1', p[2] if len(p) > 2 else '1'
            custom = False
        if 'to' in amt:     # MythicMobs "1to3" excludes the upper bound
            lo, hi = amt.split('to')
            lo, hi = int(lo), max(int(lo), int(hi) - 1)
        elif '-' in amt:
            lo, hi = (int(x) for x in amt.split('-'))
        else:
            lo = hi = int(float(amt))
        out.append({'custom' if custom else 'item': item if custom else mat(item), 'min': lo, 'max': hi, 'chance': float(ch)})
    return out


def spawn_rules(spawns, aquatic=False):
    out = []
    for sp in spawns or []:
        dim = DIMENSIONS.get(str(sp.get('world', 'world')).lower(), 'minecraft:overworld')
        biomes = []
        for b in sp['biomes']:
            key = b.lower() if ':' in b else 'minecraft:' + b.lower()
            if key not in biomes:
                biomes.append(key)
        if dim == 'minecraft:overworld':
            for b in list(sp['biomes']):
                for t in TERRALITH.get(b.upper(), []):
                    if 'terralith:' + t not in biomes:
                        biomes.append('terralith:' + t)
        rarity = sp.get('rarity')
        if not rarity:
            ch = sp.get('chance', 0.04)
            rarity = 'common' if ch >= 0.04 else 'uncommon' if ch >= 0.02 else 'rare' if ch >= 0.003 else 'very_rare'
        r = {'dimension': dim, 'biomes': biomes, 'rarity': rarity,
             'position': sp.get('position', 'SEA' if aquatic else 'LAND'),
             'conditions': [c for c in sp.get('conditions', []) if not c.startswith('biome')]}
        if sp.get('structures'):
            r['structures'] = sp['structures']
        if sp.get('cap') is not None:
            r['cap'] = sp['cap']
            r['cap_radius'] = sp.get('cap_radius', 48)
            if sp.get('cap_types'):
                r['cap_types'] = sp['cap_types']
        out.append(r)
    return out


def damage_mods(mods):
    out = {}
    for m in mods or []:
        p = m.split()
        if len(p) == 2:
            out[p[0].upper()] = float(p[1])
    return out


# ---------------------------------------------------------------------------------------------
# species
# ---------------------------------------------------------------------------------------------

def model_block(model, glow_bones, scale=1.0):
    """Model-derived species data: clips, seats, name tag height, shadow radius, render bounds."""
    out = {'model': model.name, 'clips': model.clips, 'scale': scale}
    hb = model.helper_box('hitbox')
    if hb:
        out['model_hitbox'] = [rnd(max(hb[0], hb[2]) * scale, 3), rnd(hb[1] * scale, 3)]
    sh = model.helper_box('shadow')
    out['shadow'] = rnd(max(sh[0], sh[2]) / 2 * scale, 3) if sh else 0.5
    tag = model.helper_point('tag_name')
    if tag:
        out['name_tag_y'] = rnd(tag[1] * scale, 3)
    seats = []
    for n in ['mount'] + sorted(k for k in model.helpers if k.startswith('p_')):
        p = model.helper_point(n)
        if p:
            seats.append([rnd(v * scale, 4) for v in p])
    if seats:
        out['seats'] = seats
    b = model.bounds
    out['render_box'] = [rnd(max(abs(b[0]), abs(b[3]), abs(b[2]), abs(b[5])) / 16 * scale, 3), rnd(b[4] / 16 * scale, 3)]
    heads = [x['name'] for x in model.bones if x['name'].startswith('h_') or x['name'].startswith('hi_')]
    if heads:
        out['head_bone'] = heads[0]
    out['glow_bones'] = glow_bones
    return out


def anim_role(c, clips, key, default=None):
    name = (c.get('anims') or {}).get(key, default if default is not None else key)
    if c.get('sit') and key == 'sit':
        name = c['sit']
    return name if name and name in clips else None


def tame_moods(c, clips):
    cid, flyer = c['id'], bool(c.get('flyer'))
    skip = set(c.get('mood_skip', []))
    if cid in COLD_NATIVE:
        skip.add('idle_cold')
    if cid in HOT_NATIVE:
        skip.add('idle_hot')
    if cid in WATER_LOVERS:
        skip.add('idle_rain')
    heal = mat(c.get('heal'))
    idle = [['idle_sleepy', 'night'], ['idle_cold', 'cold_biome'], ['idle_hot', 'hot_biome'],
            ['idle_rain', 'rain_outside'], ['idle_happy', 'happy']]
    for clip, kind, payload in c.get('moods_extra', []):
        idle.append([clip, mood_cond(kind, payload)])
    idle += [['idle_beg', 'owner_holding:' + heal], ['idle_alert', 'monster_near'], ['idle_worried', 'owner_hurt'],
             ['idle_hurt', 'hurt'], ['idle_scared', 'thunder_outside']]
    walk = [['walk_sleepy', 'night'], ['walk_happy', 'happy'], ['walk_sneak', 'owner_crouching'],
            ['run', 'owner_sprinting'], ['run', 'in_combat'], ['walk_scared', 'thunder_outside'], ['walk_limp', 'hurt']]
    if not flyer:
        idle.append(['idle_swim', 'in_water'])
        walk.append(['swim', 'in_water'])
    idle = [x for x in idle if x[0] not in skip and x[0] in clips and x[1]]
    walk = [x for x in walk if x[0] not in skip and x[0] in clips and x[1]]
    return idle, walk


def mood_cond(kind, payload):
    """moods_extra entries (Luna, Kevin, Kitty, Clover, Luna But The Second One) -> the Java condition vocabulary."""
    if kind == 'owner':
        held = [p for p in payload if p.startswith('holding')]
        m = re.search(r'm=([A-Z_]+)', held[0]) if held else None
        return 'owner_holding:' + mat(m.group(1)) if m else None
    p = payload
    if '#beds' in p:
        return 'on_bed'
    if 'worldtime' in p and 'outside' in p:
        return 'sunbeam'
    if p.startswith('?blocktype') and 'CHEST' in p:
        return 'in_chest_block'
    if p.startswith('?onblock') and ('BARREL' in p or 'CHEST' in p):
        return 'on_container'
    if p.startswith('?onblock') and ('STAIRS' in p or 'SLABS' in p):
        return 'on_seat'
    if p.startswith('?onblock') and ('LECTERN' in p or 'BOOKSHELF' in p):
        return 'on_paperwork'
    if p.startswith('?onblock') and 'GRASS_BLOCK' in p and 'FLOWERS' in p:
        return 'meadow'
    if p.startswith('?onblock') and 'WOOL_CARPETS' in p:
        return 'on_carpet'
    return None


def pools(c, clips):
    p = {k: list(v) for k, v in POOLS.items()}
    for k, v in (c.get('pools_extra') or {}).items():
        p.setdefault(k, []).extend(v)
    out = {}
    for k, v in p.items():
        have = [x for x in v if x in clips]
        if have:
            out[k] = have
    return out


def abilities(lines):
    out = []
    for l in lines or []:
        p = parse_line(l)
        if p:
            p['line'] = l.strip()
            out.append(p)
    return out


def tameable_species(spec, model, skills_text):
    clips = set(model.clips)
    name, color = display(spec.get('display'))
    hostile = bool(spec.get('hostile'))
    kind = spec['kind']
    if hostile:
        kind = 'hostile'
    s = {
        'id': None, 'mm_id': spec['id'], 'kind': kind, 'name': name, 'color': color,
        'health': spec['health'], 'damage': spec.get('damage') or 0, 'speed': spec['speed'],
        'follow_range': 32, 'hostile': hostile, 'untameable': bool(spec.get('untameable')),
        'fights': bool(spec.get('fights')), 'flyer': bool(spec.get('flyer')),
        'egg_only': bool(spec.get('egg_only')), 'preset_name': spec.get('preset_name'),
        'no_fall': bool(spec.get('no_fall')), 'no_nap': bool(spec.get('no_nap')),
        'damage_mods': damage_mods(spec.get('modifiers')),
        'note': spec.get('note'), 'ability_desc': spec.get('ability_desc'),
        'plush': 'arcana_plush_' + short(spec['model']),
    }
    hb = spec.get('hitbox')
    if hb:
        s['hitbox'] = [hb[0], hb[1]]
        s['eye'] = hb[2] if len(hb) > 2 else round(hb[1] * 0.85, 3)
    if spec.get('tame'):
        s['tame'] = {'item': mat(spec['tame']), 'chance': spec.get('chance', 0.33)}
        if spec.get('tame_oraxen'):
            s['tame']['custom'] = spec['tame_oraxen']
        if spec.get('tame_return'):
            s['tame']['return'] = mat(spec['tame_return'])
    if spec.get('heal'):
        s['heal'] = {'item': mat(spec['heal']), 'amount': spec.get('heal_amount', 4)}
    snd = spec.get('sounds') or {}
    s['sounds'] = {k: sound(snd.get(k)) for k in ('ambient', 'hurt', 'death', 'attack', 'eat') if snd.get(k)}
    s['sounds']['every'] = snd.get('every', 120)
    s['sounds']['chance'] = snd.get('chance', 0.5)
    roles = {}
    for key in ('sit', 'sleep', 'eat', 'happy', 'tame', 'ability', 'look_around', 'hurt', 'spawn', 'run', 'death',
                'greet', 'notice', 'celebrate', 'startle', 'shake', 'sit_down', 'stand_up', 'lie_down', 'wake_up',
                'mount', 'dismount', 'ride_idle', 'ride_walk', 'ride_run', 'ride_hover', 'ride_fly'):
        a = anim_role(spec, clips, key)
        if a:
            roles[key] = a
    atk = anim_role(spec, clips, 'attack', {'arcana_geode_crawler': 'bite', 'arcana_volt_wisp': 'zap'}.get(spec['model'], 'attack'))
    if atk:
        roles['attack'] = atk
    s['anims'] = roles
    idle, walk = tame_moods(spec, clips)
    s['moods'] = {'idle': idle, 'walk': walk}
    s['pools'] = pools(spec, clips)
    s['flourish_sounds'] = {k: {'id': v[0], 'volume': v[1], 'pitch': v[2]} for k, v in (spec.get('flourish_sounds') or {}).items()}
    aq = spec.get('aquatic')
    if aq:
        cfg = {'follow': not spec.get('untameable'), 'hover': True, 'speed': 0.3}
        if isinstance(aq, dict):
            cfg.update(aq)
        cfg['speed'] = value(str(cfg['speed']))
        s['aquatic'] = cfg
    if spec.get('mount'):
        m = dict(spec['mount'])
        m.pop('passenger_seat_ids', None)
        if m.get('ride_hint'):
            m['ride_hint'] = re.sub(r'&[0-9a-fk-or]', '', m['ride_hint'])
        if spec.get('motion'):
            m['motion'] = spec['motion']
        s['mount'] = m
    s['abilities'] = abilities(spec.get('ability'))
    if spec.get('drops'):
        s['drops'] = drops(spec['drops'])
    sp = spec.get('spawns') or spec.get('spawns_existing')
    if not sp and spec.get('spawn'):
        sp = spec['spawn'] if isinstance(spec['spawn'], list) else [spec['spawn']]
        extra = spec.get('spawn_extra') or []
        sp = sp + (extra if isinstance(extra, list) else [extra])
    if spec.get('spawns_existing') and spec.get('spawn'):
        sp = (spec['spawn'] if isinstance(spec['spawn'], list) else [spec['spawn']])
    s['spawns'] = [] if spec.get('egg_only') else spawn_rules(sp, bool(aq))
    s['skills_yml'] = skills_text
    s.update(model_block(model, spec.get('glow_bones') or [], 1.0))
    s['glow_out'] = spec.get('glow_out') or []
    # Pockie: the fishbowl state (entity/FishbowlCreature), a glow that is brighter in the dark, and a translucent
    # texture layer (glass, water) drawn in its own pass
    for k in ('fishbowl', 'glow_dark', 'glass_layer'):
        if spec.get(k):
            s[k] = spec[k]
    # Penguins (entity/PenguinCreature): family, chick / adult link, colony group, habitat, boat push and fishing buddy
    # values; "raised" = tamed only by raising its chick (no tame item of its own)
    for k in ('penguin', 'raised'):
        if spec.get(k):
            s[k] = spec[k]
    return s


def wild_species(spec, model, young=None):
    base = spec if young is None else young
    clips = set(model.clips) if model else set()
    name, color = display(base.get('display') or '')
    if not name:
        name = ' '.join(w.capitalize() for w in short(spec.get('model') or spec['id'].replace('Arcana', '')).split('_'))
        if young is not None and spec['kind'] == 'livestock':
            name = 'Young ' + name
    s = {
        'id': None, 'mm_id': base['id'], 'kind': spec['kind'] if young is None else 'livestock',
        'name': name, 'color': color,
        'health': base.get('health', spec.get('health')), 'damage': base.get('damage') or spec.get('damage') or 0,
        'speed': base.get('speed', spec.get('speed')), 'follow_range': base.get('follow_range', 16),
        'despawn': str(base.get('despawn', spec.get('despawn', 'true'))).lower(),
        'damage_mods': damage_mods(base.get('modifiers', spec.get('modifiers'))),
        'note': base.get('note', spec.get('note')),
        'base': spec.get('base'),
    }
    snd = base.get('sounds') or spec.get('sounds') or {}
    pm = base.get('pitch_mult', 1.0) if young is not None else 1.0
    s['sounds'] = {}
    for k in ('ambient', 'ambient2', 'hurt', 'death', 'attack', 'step'):
        if snd.get(k):
            e = sound(snd[k])
            e['pitch'] = min(2.0, e['pitch'] * pm)
            s['sounds'][k] = e
    for k in ('every', 'chance', 'every2', 'chance2', 'step_every'):
        if k in snd:
            s['sounds'][k] = snd[k]
    for k in ('life', 'gaits', 'herd', 'harvest', 'replace', 'panic_run', 'attack_clip', 'attack_trigger', 'equipment',
              'ai', 'ai_toggle', 'options', 'init', 'load', 'restore', 'base_idle', 'base_walk', 'glow_when', 'quiet_cond',
              'aquatic', 'armor', 'snip', 'arthropod'):
        v = base.get(k, spec.get(k) if young is not None and k in ('life', 'gaits', 'panic_run', 'base_idle', 'base_walk', 'ai_toggle') else None)
        if v is not None:
            s[k] = v
    if young is not None:
        s['young_of'] = spec['id']
        s['line'] = bool(base.get('line'))
    elif spec.get('young'):
        s['young_id'] = spec['young']['id']
    lines = [l.replace('%ID%', base['id']).replace('%M%', spec.get('model') or '')
             .replace('%YID%', (spec.get('young') or {}).get('id', '')) for l in base.get('lines', [])]
    s['lines'] = abilities(lines)
    s['drops'] = drops(base.get('drops', [] if young is not None else spec.get('drops')))
    if young is None:
        s['spawns'] = spawn_rules(spec.get('spawns'), bool(spec.get('aquatic')))
    else:
        s['spawns'] = []
    if spec.get('replace') and young is None:
        r = spec['replace']
        biomes = []
        for b in r['biomes']:
            # a namespaced id ('terralith:snowy_badlands') is a biome as is; a vanilla key adds its Terralith look-alikes
            if ':' in b:
                biomes.append(b.lower())
                continue
            biomes.append('minecraft:' + b.lower())
            for t in TERRALITH.get(b.upper(), []):
                biomes.append('terralith:' + t)
        biomes = list(dict.fromkeys(biomes))
        s['replace'] = {'vanilla': mat(r['vanilla']), 'share': r['share'], 'moon_share': r['moon_share'], 'biomes': biomes}
    roles = {}
    for key in ('idle', 'walk', 'run', 'death', 'hurt', 'attack', 'graze', 'lie', 'harvest', 'shake', 'swim'):
        if key in clips:
            roles[key] = key
    if s.get('attack_clip'):
        roles['attack'] = s['attack_clip']
    s['anims'] = roles
    scale = float(base.get('scale', 1.0)) if young is not None else float(spec.get('scale', 1.0))
    if model:
        s.update(model_block(model, base.get('glow_bones', spec.get('glow_bones')) or [], scale))
    else:
        s['model'] = None
        s['clips'] = {}
        s['shadow'] = 0
        s['scale'] = 1.0
        s['glow_bones'] = []
    s['glow_out'] = base.get('glow_out', spec.get('glow_out')) or []
    if spec['kind'] == 'particles':
        s['hitbox'] = [0.6, 0.6]
    return s


BOSS_IDS = {'ArcanaCinderwingPhoenix': 'cinderwing_phoenix_boss', 'ArcanaTempestRoc': 'tempest_roc_boss',
            'ArcanaGlacialWyrm': 'glacial_wyrm', 'ArcanaRuneboundColossus': 'runebound_colossus', 'ArcanaWukong': 'wukong',
            'ArcanaPolice': 'police', 'ArcanaWukongClone': 'wukong_clone'}


def boss_species(mm_id, mob, models, skills):
    m = re.search(r'mid=(arcana_[a-z_]+)', ' '.join(mob.get('Skills', [])))
    model = models[short(m.group(1))]
    name, color = display(mob.get('Display', mm_id))
    opts = mob.get('Options') or {}
    bb = mob.get('BossBar') or {}
    glow = []
    for skill_name, sk in (skills or {}).items():
        if not isinstance(sk, dict):
            continue
        for l in sk.get('Skills') or []:
            if 'brightness{' in l and 'b=15' in l and f"mid={model.bb_name}" in l:
                b = re.search(r'p=([A-Za-z0-9_]+)', l)
                if b and b.group(1) not in glow:
                    glow.append(b.group(1))
    s = {
        'id': BOSS_IDS[mm_id], 'mm_id': mm_id, 'kind': 'police' if mm_id == 'ArcanaPolice' else 'boss',
        'name': name, 'color': color, 'health': mob.get('Health', 100), 'damage': mob.get('Damage', 0),
        'armor': mob.get('Armor', 0), 'speed': opts.get('MovementSpeed', 0.25), 'follow_range': opts.get('FollowRange', 32),
        'knockback_resistance': opts.get('KnockbackResistance', 0), 'base': mob.get('Type'),
        'damage_mods': damage_mods(mob.get('DamageModifiers')), 'drops': drops(mob.get('Drops')),
        'flyer': mob.get('Type') == 'PHANTOM',
        'abilities': abilities(['- ' + l for l in mob.get('Skills', []) if not l.startswith('model{')]),
        'note': None,
    }
    if bb.get('Enabled'):
        s['boss_bar'] = {'title': re.sub(r'&[0-9a-fk-or]', '', bb.get('Title', name)), 'color': bb.get('Color', 'PURPLE'),
                         'style': bb.get('Style', 'SOLID'), 'range': bb.get('Range', 64)}
    roles = {k: k for k in ('idle', 'walk', 'run', 'death', 'hurt', 'attack', 'spawn', 'summon', 'awaken', 'fly', 'hover',
                            'idle_rage', 'walk_rage', 'alert') if k in model.clips}
    for l in mob.get('Skills', []):
        sm = re.match(r"state\{(?:mid=[a-z_]+;)?s=([a-z_0-9]+)[^}]*\} @self ~onAttack", l)
        if sm:
            roles['attack'] = sm.group(1)
        sm = re.match(r"state\{(?:mid=[a-z_]+;)?s=([a-z_0-9]+)[^}]*\} @self ~onSpawn", l)
        if sm:
            roles['spawn'] = sm.group(1)
    s['anims'] = roles
    # model{mid=...;scale=0.78} (the Wukong hair clones) and tint{c=RRGGBB} ~onSpawn
    sc = re.search(r'model\{[^}]*scale=([0-9.]+)', ' '.join(mob.get('Skills', [])))
    tint = re.search(r'tint\{[^}]*c=([0-9A-Fa-f]{6})[^}]*\} @self ~onSpawn', ' '.join(mob.get('Skills', [])))
    if tint:
        s['tint'] = int(tint.group(1), 16)
    if mob.get('Faction'):
        s['faction'] = mob['Faction']
    s.update(model_block(model, glow, float(sc.group(1)) if sc else 1.0))
    s['glow_out'] = glow if mm_id == 'ArcanaRuneboundColossus' else []
    s['spawns'] = []
    return s


def main():
    os.makedirs(ASSETS, exist_ok=True)
    models = {}
    for f in sorted(os.listdir(MODELS)):
        if f.endswith('.bbmodel'):
            src = os.path.join(OVERRIDES, f) if os.path.isfile(os.path.join(OVERRIDES, f)) else os.path.join(MODELS, f)
            m = Model(src)
            m.bb_name = f[:-len('.bbmodel')]
            models[m.name] = m
    # animations: generated from the bbmodel, then the authored clips of tools/clips/<model>.py merged in
    for name, m in models.items():
        m.anim_json = m.animations()
        m.view = animkit.ModelView(name, m.geo(), m.anim_json)
        mods = []
        path = os.path.join(CLIPS, name + '.py')
        if os.path.isfile(path):
            mods.append(animkit.load_module(path))
        # extra modules for one model, merged after its own (clips/<model>__<part>.py: the bosses' clips on the
        # mounts' shared models live in <model>__boss.py)
        for extra in sorted(os.listdir(CLIPS)):
            if extra.startswith(name + '__') and extra.endswith('.py'):
                mods.append(animkit.load_module(os.path.join(CLIPS, extra)))
        m.anim_tables = animkit.merge(m.view, mods)
        # no mob ever blinks: eye-shutting keys outside sleep-type clips are opened (generated json only)
        m.anim_tables['unblinked'] = animtags.strip_blinks(m.view)
        for clip in m.anim_tables['authored']:
            a = m.anim_json['animations'][clip]
            loop = {True: 'loop', 'hold_on_last_frame': 'hold'}.get(a['loop'], 'once')
            m.clips[clip] = {'length': a['animation_length'], 'loop': loop}

    species = []
    for f in sorted(os.listdir(SPECS)):
        if not f.endswith('.json'):
            continue
        spec = read_json(os.path.join(SPECS, f))
        model = models[short(spec['model'])]
        sk = os.path.join(SPECS, (spec.get('skills_file') or f[:-5] + '.skills.yml'))
        text = open(sk, encoding='utf-8').read() if os.path.exists(sk) else ''
        s = tameable_species(spec, model, text)
        s['id'] = short(spec['model'])
        species.append(s)

    for f in sorted(os.listdir(WILD)):
        if not f.endswith('.json'):
            continue
        spec = read_json(os.path.join(WILD, f))
        model = models.get(short(spec.get('model'))) if spec.get('model') else None
        s = wild_species(spec, model)
        s['id'] = short(spec['model']) if spec.get('model') else f[:-5]
        sk = os.path.join(WILD, f[:-5] + '.skills.yml')
        s['skills_yml'] = open(sk, encoding='utf-8').read() if os.path.exists(sk) else ''
        species.append(s)
        if spec.get('young'):
            y = wild_species(spec, model, spec['young'])
            y['id'] = s['id'] + '_young'
            yk = os.path.join(WILD, f[:-5] + '.young.skills.yml')
            y['skills_yml'] = open(yk, encoding='utf-8').read() if os.path.exists(yk) else ''
            species.append(y)
            s['young'] = y['id']

    if yaml:
        mobs_dir = os.path.join(PACK, 'mobs')
        skills_dir = os.path.join(PACK, 'skills')
        skills = {}
        for sf in ('bosses.yml', 'wukong.yml', 'police.yml'):
            p = os.path.join(skills_dir, sf)
            if os.path.exists(p):
                skills.update(yaml.safe_load(open(p, encoding='utf-8')) or {})
        for mf in ('bosses.yml', 'wukong.yml', 'police.yml'):
            data = yaml.safe_load(open(os.path.join(mobs_dir, mf), encoding='utf-8')) or {}
            for mm_id, mob in data.items():
                if mm_id in BOSS_IDS:
                    species.append(boss_species(mm_id, mob, models, skills))

    # the animation block: clip tags, states, transitions, idle pools, procedural bones (animtags.py)
    for s in species:
        if s.get('model'):
            m = models[s['model']]
            s['anim'] = animtags.build(s, m.view, m.anim_tables)
            # a napping pet goes straight from its sit to its sleep pose and back: where the model's own lie_down /
            # wake_up start or end standing, author the direct blends (sit_lie_down, lie_sit_up) from the poses
            a = s['anim']
            sit, sleep = a['states'].get('sit'), a['states'].get('sleep')
            if s['kind'] in ('pet', 'hostile', 'mount') and sit and sleep:
                added = []
                if 'sitting>lying' not in a['transitions'] and 'sit_lie_down' not in m.view.clips:
                    added.append(animkit.transition(m.view, 'sit_lie_down', sit, 'end', sleep, 0, 1.2, 0.1))
                if 'lying>sitting' not in a['transitions'] and 'lie_sit_up' not in m.view.clips:
                    added.append(animkit.transition(m.view, 'lie_sit_up', sleep, 0, sit, 'end', 1.3, 0.1))
                for c in added:
                    m.view.put(c)
                    m.anim_tables['authored'].append(c.name)
                    m.clips[c.name] = {'length': c.L, 'loop': 'once'}
                if added:
                    s['clips'] = m.clips
                    s['anim'] = animtags.build(s, m.view, m.anim_tables)

    # the mounts' boss forms share the model; the tameable keeps the plain id
    ids = [s['id'] for s in species]
    assert len(ids) == len(set(ids)), 'duplicate entity ids: %s' % [i for i in ids if ids.count(i) > 1]

    # assets per model
    used = {s['model'] for s in species if s.get('model')}
    glow_by_model = {}
    for s in species:
        if s.get('model'):
            glow_by_model.setdefault(s['model'], set()).update(s.get('glow_bones') or [])
    for name in sorted(used):
        m = models[name]
        write_json(os.path.join(ASSETS, 'geckolib', 'models', 'entity', name + '.geo.json'), m.geo(), compact=True)
        # slimmed on a copy at write time (animkit.slim_json: within 0.5 deg / 0.05 px / 0.008 of the dense curve as
        # GeckoLib plays it): fewer keyframes held in the client's heap; view.clips and the checkers keep the dense data
        write_json(os.path.join(ASSETS, 'geckolib', 'animations', 'entity', name + '.animation.json'),
                   animkit.flip_rotations(animkit.slim_json(copy.deepcopy(m.anim_json))), compact=True)
        img = m.texture()
        if img is None:
            print('WARNING: no embedded texture in', name)
            continue
        tex_dir = os.path.join(ASSETS, 'textures', 'entity')
        os.makedirs(tex_dir, exist_ok=True)
        glass_path = os.path.join(tex_dir, name + '_glass.png')
        if any(s.get('glass_layer') for s in species if s.get('model') == name):
            # translucent texels (glass, water: 0 < alpha < 255) go to <model>_glass.png, drawn in a translucent pass
            # (ArcanaGeoRenderer.GlassLayer); the cutout base texture keeps only the opaque texels
            glass = Image.new('RGBA', img.size, (0, 0, 0, 0))
            px, gp = img.load(), glass.load()
            for y in range(img.size[1]):
                for x in range(img.size[0]):
                    if 0 < px[x, y][3] < 255:
                        gp[x, y] = px[x, y]
                        px[x, y] = (0, 0, 0, 0)
            glass.save(glass_path)
        elif os.path.exists(glass_path):
            os.remove(glass_path)
        img.save(os.path.join(tex_dir, name + '.png'))
        mask, glow_render = m.glowmask(img, sorted(glow_by_model.get(name, ())))
        mask_path = os.path.join(tex_dir, name + '_glowmask.png')
        if mask is not None:
            mask.save(mask_path)
        elif os.path.exists(mask_path):
            os.remove(mask_path)
        for s in species:
            if s.get('model') == name:
                s['glowmask'] = mask is not None
                # the bones the glow layer draws a second time (instead of the whole model); glow_bones stays the
                # spec's list (Species.glowBones, BossSkills)
                if glow_render:
                    s['glow_render_bones'] = glow_render

    # sound profiles: sounds.json + subtitles + each species' "sfx" block (tools/sfx_profiles.py)
    sys.path.insert(0, HERE)
    import sfx_gen
    sfx_gen.generate(species, ASSETS, DATA, write_json)

    # species table, spawn eggs, names
    lang = {}
    # a boss that shares its name with the tameable (the mounts' boss forms) gets " (Boss)" on its egg, so the two eggs
    # don't read the same in creative / JEI; the entity name stays (the boss bar has its own title)
    name_count = collections.Counter(s['name'] for s in species)
    for s in species:
        s = {k: v for k, v in s.items() if v is not None and k != 'skills_yml'}
        write_json(os.path.join(DATA, 'species', s['id'] + '.json'), s)
        lang['entity.aftrhrs.' + s['id']] = s['name']
        boss = s.get('kind') == 'boss' and name_count[s['name']] > 1
        lang['item.aftrhrs.' + s['id'] + '_spawn_egg'] = s['name'] + (' (Boss)' if boss else '') + ' Spawn Egg'
        write_egg(ASSETS, s)
        write_json(os.path.join(ASSETS, 'items', s['id'] + '_spawn_egg.json'),
                   {'model': {'type': 'minecraft:model', 'model': 'aftrhrs:item/' + s['id'] + '_spawn_egg'}})
        write_json(os.path.join(ASSETS, 'models', 'item', s['id'] + '_spawn_egg.json'),
                   {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'aftrhrs:item/spawn_egg/' + s['id']}})
    eggs = collections.Counter(v for k, v in lang.items() if k.endswith('_spawn_egg'))
    assert all(n == 1 for n in eggs.values()), 'spawn eggs share a name: %s' % [e for e, n in eggs.items() if n > 1]
    write_json(os.path.join(DATA, 'species', 'index.json'), {'species': [s['id'] for s in species]})
    # entity type tags: zombie / skeleton based undead burn in daylight unless PreventSunburn (MythicMobs options)
    # spider variants (spec "arthropod": true) are arthropods like the spider they replace, not undead (smite, healing)
    undead = [s for s in species if s['kind'] == 'undead' and not s.get('arthropod')]
    bugs = [s for s in species if s.get('arthropod')]
    # plus SUNBURN: creatures that aren't undead but burn by day like zombies (wild only: tamed ones are spared, MobSunburnMixin)
    burn = [s for s in species if (s in undead and str((s.get('options') or {}).get('PreventSunburn', 'false')).lower() != 'true')
            or s['id'] in SUNBURN]
    tags = os.path.join(RES, 'data', 'minecraft', 'tags', 'entity_type')
    write_json(os.path.join(tags, 'undead.json'), {'replace': False, 'values': ['aftrhrs:' + s['id'] for s in undead]})
    write_json(os.path.join(tags, 'arthropod.json'), {'replace': False, 'values': ['aftrhrs:' + s['id'] for s in bugs]})
    write_json(os.path.join(tags, 'burn_in_daylight.json'), {'replace': False, 'values': ['aftrhrs:' + s['id'] for s in burn]})
    # penguins never get pushed into a boat by bumping it (wild ones would sit in the back seat); a pet boards its owner's
    # boat on purpose (PenguinCreature.boatSeat)
    boats = [s for s in species if s.get('penguin')]
    write_json(os.path.join(tags, 'cannot_be_pushed_onto_boats.json'), {'replace': False, 'values': ['aftrhrs:' + s['id'] for s in boats]})
    write_json(os.path.join(ASSETS, 'lang', 'en_us.entities.json'), dict(sorted(lang.items())))
    kinds = {}
    for s in species:
        kinds[s['kind']] = kinds.get(s['kind'], 0) + 1
    print('ok:', len(species), 'entity types', kinds, '|', len(used), 'models')


if __name__ == '__main__':
    sys.exit(main())
