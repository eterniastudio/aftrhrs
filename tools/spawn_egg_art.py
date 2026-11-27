"""Build 16px mob portraits from the textured geometry shipped with Aftr Hrs.

Spawn eggs use the model's head where it carries the creature's identity and
the body where the outline does (wings, shells, fins). Each pose keeps the
silhouette and markings of the creature at the native item resolution.
"""
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw

from geo import apply, rot_matrix


# These creatures read best from the whole silhouette, with a side angle for
# long, horizontal models. Others use a head-and-accessories portrait.
PROFILE_VIEWS = {
    'bubblefin': 55, 'driftglow': 25,
    'geode_crawler': 39, 'gloomweaver': 20,
    'inkcurl': 20, 'kelpback': 54, 'lanternsnail': 62,
    'loomworm': 59, 'lurefin': 52, 'runeback_tortoise': 45,
    'shellpip': 35, 'shulkling': 19, 'skyfin_ray': 8,
    'tidemane': 47, 'voidpuff': 37, 'volt_wisp': 16, 'wickling': 38,
}

# Head-linked parts vary between the imported rigs.
PORTRAIT_ROOTS = {
    'honeycub': ('h_head', 'pot'),
    'runebound_colossus': ('torso', 'eye'),
    'tempest_roc': ('neck', 'h_head', 'crest', 'beak'),
    'velvetstag': ('neck',),
    'wartgrazer': ('h_head', 'fleece'),
    'wukong': ('h_head',),
}

# Held props and alternate expressions are visible only during gameplay.
POSE_HIDDEN = {'held_fish', 'bite_fish', 'saddle', 'bowl', 'cloud'}
FACE_LIGHT = dict(north=.98, south=.79, west=.82, east=.87, up=1.14, down=.68)
# Species that share an adult model still need quickly distinguishable eggs.
YOUNG_TINTS = {
    'bristleboar': (231, 181, 147),
    'lanternsnail': (231, 224, 145),
    'loomworm': (237, 220, 178),
    'pondwaddle': (254, 220, 121),
    'shroomwallow': (234, 149, 153),
    'tufthorn': (246, 196, 151),
    'wartgrazer': (192, 221, 218),
}
BOSS_FORMS = {'cinderwing_phoenix_boss', 'tempest_roc_boss'}
SAMPLE = 48


def cube_faces(low, high):
    x, y, z = low
    X, Y, Z = high
    # Top left, top right, bottom left in each face's texture orientation.
    return {
        'north': ((X,Y,z), (x,Y,z), (X,y,z)),
        'south': ((x,Y,Z), (X,Y,Z), (x,y,Z)),
        'east': ((X,Y,Z), (X,Y,z), (X,y,Z)),
        'west': ((x,Y,z), (x,Y,Z), (x,y,z)),
        'up': ((X,Y,Z), (x,Y,Z), (X,Y,z)),
        'down': ((X,y,z), (x,y,z), (X,y,Z)),
    }


def rotated(point, matrix, pivot):
    moved = apply(matrix, tuple(p - o for p, o in zip(point, pivot)))
    return tuple(p + o for p, o in zip(moved, pivot))


def portrait_faces(bones, roots, hidden):
    by_name = {bone['name']: bone for bone in bones}
    for bone in bones:
        chain = [bone]
        while chain[-1].get('parent') in by_name:
            chain.append(by_name[chain[-1]['parent']])
        names = {b['name'] for b in chain}
        if names & hidden or (roots and not names.intersection(roots)):
            continue
        bone_transforms = [(rot_matrix(dict(zip('xyz', b['rotation']))), b['pivot']) for b in chain if b.get('rotation')]
        for cube in bone.get('cubes', ()):
            origin = cube['origin']
            size = cube['size']
            inflate = cube.get('inflate', 0)
            low = tuple(v - inflate for v in origin)
            high = tuple(v + width + inflate for v, width in zip(origin, size))
            transforms = bone_transforms
            if cube.get('rotation'):
                transforms = [(rot_matrix(dict(zip('xyz', cube['rotation']))), cube['pivot']), *transforms]
            faces = cube_faces(low, high)
            for name, uv in cube['uv'].items():
                if not uv['uv_size'][0] or not uv['uv_size'][1]:
                    continue
                points = []
                for point in faces[name]:
                    for matrix, pivot in transforms:
                        point = rotated(point, matrix, pivot)
                    points.append(point)
                yield name, points, uv


def camera(yaw, pitch):
    a, b = math.radians(yaw), math.radians(pitch)
    ca, sa, cb, sb = math.cos(a), math.sin(a), math.cos(b), math.sin(b)

    def project(point):
        x, y, z = point
        depth = x * sa - z * ca
        return -x * ca - z * sa, -y * cb + depth * sb, depth * cb + y * sb

    return project


def sample_model(faces, texture, yaw, pitch):
    project = camera(yaw, pitch)
    projected = []
    bounds = []
    for face, vertices, uv in faces:
        p, q, r = map(project, vertices)
        fourth = tuple(q[i] + r[i] - p[i] for i in range(3))
        projected.append((face, (p, q, r, fourth), uv))
        bounds.extend((p, q, r, fourth))
    left = min(p[0] for p in bounds)
    right = max(p[0] for p in bounds)
    top = min(p[1] for p in bounds)
    bottom = max(p[1] for p in bounds)
    scale = (SAMPLE - 4) / max(right - left, bottom - top)
    cx, cy = (left + right) / 2, (top + bottom) / 2
    image = Image.new('RGBA', (SAMPLE, SAMPLE))
    pixels = image.load()
    texels = texture.load()
    depth = [[-math.inf] * SAMPLE for _ in range(SAMPLE)]
    for face, points, uv in projected:
        p, q, r, s = [((x - cx) * scale + SAMPLE / 2, (y - cy) * scale + SAMPLE / 2, d) for x,y,d in points]
        vx, vy = q[0] - p[0], q[1] - p[1]
        wx, wy = r[0] - p[0], r[1] - p[1]
        det = vx * wy - vy * wx
        if abs(det) < 1e-6:
            continue
        u0, v0 = uv['uv']
        uw, vh = uv['uv_size']
        min_x, max_x = max(0, int(min(v[0] for v in (p,q,r,s)))), min(SAMPLE, math.ceil(max(v[0] for v in (p,q,r,s))))
        min_y, max_y = max(0, int(min(v[1] for v in (p,q,r,s)))), min(SAMPLE, math.ceil(max(v[1] for v in (p,q,r,s))))
        for y in range(min_y, max_y):
            dy = y + .5 - p[1]
            for x in range(min_x, max_x):
                dx = x + .5 - p[0]
                u, v = (dx * wy - dy * wx) / det, (dy * vx - dx * vy) / det
                if not (0 <= u < 1 and 0 <= v < 1):
                    continue
                z = p[2] + u * (q[2] - p[2]) + v * (r[2] - p[2])
                if z <= depth[y][x]:
                    continue
                tx = max(0, min(texture.width - 1, math.floor(u0 + u * uw)))
                ty = max(0, min(texture.height - 1, math.floor(v0 + v * vh)))
                red, green, blue, alpha = texels[tx, ty]
                if alpha < 128:
                    continue
                depth[y][x] = z
                light = FACE_LIGHT[face]
                pixels[x,y] = tuple(min(255, round(c * light)) for c in (red,green,blue)) + (255,)
    return image.crop(image.getbbox())


def load_portrait(assets, model, *, turn=False):
    geo = assets / 'geckolib' / 'models' / 'entity' / (model + '.geo.json')
    bones = json.loads(geo.read_text(encoding='utf-8'))['minecraft:geometry'][0]['bones']
    by_name = {b['name'] for b in bones}
    yaw, pitch = PROFILE_VIEWS.get(model, 20), 14
    if model == 'skyfin_ray':
        pitch = 57
    if model in PROFILE_VIEWS:
        roots = ()
    else:
        roots = PORTRAIT_ROOTS.get(model, ('h_head',))
        if not by_name.intersection(roots):
            roots = ()
    if turn:
        yaw = -yaw
    with Image.open(assets / 'textures' / 'entity' / (model + '.png')) as art:
        texture = art.convert('RGBA')
    hidden = POSE_HIDDEN
    if model == 'wukong':
        hidden = hidden | {'plume_l', 'plume_r'}
    return sample_model(portrait_faces(bones, roots, hidden), texture, yaw, pitch)


def pastel(color, mix, towards):
    return tuple(round(c * (1 - mix) + b * mix) for c, b in zip(color, towards))


def portrait_icon(portrait, species_id, model):
    """Reduce the textured scene to inventory pixels, then preserve the outer reads."""
    young = species_id.endswith('_young')
    boss = species_id in BOSS_FORMS
    clone = species_id == 'wukong_clone'
    limit = 11 if young else 14
    max_height = 12 if young or boss else 14
    if model == 'wukong':
        max_height = 11  # Reserve space for the two long pheasant plumes.
    scale = min(limit / portrait.width, max_height / portrait.height)
    size = (max(1, round(portrait.width * scale)), max(1, round(portrait.height * scale)))
    scaled = portrait.resize(size, Image.Resampling.LANCZOS)
    x0 = (16 - size[0]) // 2
    y0 = (16 - size[1]) // 2 + (2 if model == 'wukong' else 0)
    face = {}
    for y in range(scaled.height):
        for x in range(scaled.width):
            *rgb, alpha = scaled.getpixel((x, y))
            if alpha < 110:
                continue
            color = tuple(rgb)
            if max(color) < 130:
                color = pastel(color, .11, (172, 163, 186))
            if young:
                color = pastel(color, .23, YOUNG_TINTS.get(model, (251, 227, 190)))
            if clone:
                color = pastel(color, .38, (164, 229, 235))
            face[x + x0, y + y0] = color

    if model == 'wukong':
        quill = (217, 95, 61) if not clone else (118, 195, 210)
        tip = (247, 161, 88) if not clone else (176, 226, 229)
        for x, y in ((3, 1), (4, 2), (4, 3), (5, 4), (5, 5),
                     (11, 0), (10, 1), (10, 2), (9, 3), (9, 4), (9, 5)):
            face.setdefault((x, y), tip if y < 2 else quill)

    image = Image.new('RGBA', (16, 16))
    # Average adjacent face colors for a consistent colored contour.
    outer = {}
    for (x, y), color in face.items():
        for dx, dy in ((1, 0), (-1, 0), (0, -1), (0, 1)):
            neighbor = (x + dx, y + dy)
            if neighbor not in face and 0 <= neighbor[0] < 16 and 0 <= neighbor[1] < 16:
                outer.setdefault(neighbor, []).append(color)
    for pos, colors in outer.items():
        average = tuple(sum(channel) // len(channel) for channel in zip(*colors))
        image.putpixel(pos, pastel(average, .66, (27, 24, 35)) + (255,))
    for pos, color in face.items():
        image.putpixel(pos, color + (255,))
    if boss:
        add_boss_clasp(image)
    return image


def add_boss_clasp(image):
    # The wild bosses and tame mounts share a rig and coat.
    draw = ImageDraw.Draw(image)
    draw.line(((4, 13), (6, 15), (9, 15), (11, 13)), fill='#593547', width=1)
    draw.line(((5, 13), (7, 14), (8, 14), (10, 13)), fill='#e5a455', width=1)
    draw.line(((7, 14), (8, 14)), fill='#ffe3a2', width=1)


# The penguin shells carry the coat markings of each species and age.
PENGUIN_EGGS = {
    'emperor_penguin': (
        dict(o='#202539', d='#384354', m='#4e6071', l='#7890a0', h='#adbdc5',
             a='#d69459', b='#f9ce80', c='#fff0bf', s='#bdb99d', w='#e4dfbf'),
        (
            "......ood...",
            ".....olhldo.",
            "....olhmlddo",
            "...olhmmbado",
            "..olmmbcbado",
            ".olmmcbbswdo",
            ".odmbcswwsdo",
            "odmbwswwsmdo",
            "odlswwwsmdo.",
            "odsswwswddo.",
            ".odsswsmdo..",
            ".oodssddo...",
            "...ooooo....",
        ),
    ),
    'emperor_penguin_chick': (
        dict(o='#35445c', d='#54677d', m='#73899b', l='#9dafb8', h='#c2d2d7',
             a='#687886', b='#d9e6e4', c='#f4f4e7', s='#94a5b1', w='#e5e7dd'),
        (
            ".....ood....",
            "....olhlo...",
            "...olhhlado.",
            "..olhmlahldo",
            "..olmlhblsdo",
            ".olmlhbcsldo",
            ".omlbccwlsdo",
            "odlbwbcslmdo",
            "odllwssmldo.",
            ".odlmlsddo..",
            ".oommdddo...",
            "...ooooo....",
        ),
    ),
    'rockhopper_penguin': (
        dict(o='#211d31', d='#342d44', m='#4c4257', l='#675e73', h='#9990a5',
             a='#a26b37', b='#ecb74a', c='#ffe88b', r='#bb5348', s='#aeabba', w='#e0dadd'),
        (
            "......ooo...",
            ".....olhldo.",
            "....ollmlddo",
            "...oalmmlado",
            "..olcbmcbbdo",
            ".olmmcbamddo",
            ".odmrabmlrdo",
            "odmmrdmlwddo",
            "odlmlswswdo.",
            "odmswswddo..",
            ".odssmdodo..",
            ".ooddddo....",
            "...oooo.....",
        ),
    ),
    'rockhopper_penguin_chick': (
        dict(o='#332936', d='#514152', m='#715a64', l='#9b7e83', h='#bd9da0',
             a='#bd824e', b='#e8b279', s='#bca7a0', w='#e4d5c1'),
        (
            ".....ooo....",
            "....olhlo...",
            "...olhlmdo..",
            "..olmmldmdo.",
            "..omlblamdo.",
            ".olmlmllmddo",
            ".odmlslmwddo",
            "odlswmswlmdo",
            "odmswwssmdo.",
            ".odlsmsddo..",
            ".oomddddo...",
            "...ooooo....",
        ),
    ),
}


# Small anatomy features need drawn accents when the full rig reduces to a
# few pixels. These use the same lighting and contour as the sampled heads.
FEATURE_EGGS = {
    'pockie': (
        dict(o='#233348', d='#395869', m='#508388', l='#83b6a6', h='#c1e2cd',
             a='#293a47', b='#355760', c='#6ba17c', f='#eaf6c2', s='#a0bbb1'),
        (
            '.....oddo....',
            '....odllmdo..',
            '...odhlmdddo.',
            '..odhlmlllmd.',
            '.odhhllmmldo.',
            'odhlmladmmldo',
            'odhlmaacdmldo',
            'odhmaccbcdldo',
            'odlmbbfcmmldo',
            '.odlbcfsbldo.',
            '.oddlllmddo..',
            '..oddddddo...',
            '...oooooo....',
        ),
    ),
    'glimmermoth': (
        dict(o='#272439', d='#423652', m='#645a82', l='#8b77a6', h='#c4a1ce',
             a='#395366', b='#558293', c='#8ec6bd', f='#cfe8cf'),
        (
            '.....h...h.....',
            '.....l...l.....',
            '.oh..ml.lm..ho.',
            'ohll..dod..llho',
            'olllodbfbdolllo',
            'odhlmmfcfmmlddo',
            '.odmlcclcclmdo.',
            '..odlcmlmcldo..',
            '...odmldlmdo...',
            '...ommldlmmo...',
            '....ododo......',
            '.....o.o.......',
        ),
    ),
    'hushwing': (
        dict(o='#171d2c', d='#273748', m='#384d60', l='#53677f', h='#8b859e',
             a='#345b64', b='#588c91', c='#9bccbb', f='#dce8c7'),
        (
            '.a...b...b...a.',
            '.do..ac.ca..od.',
            'odmo.odldo.omdo',
            'odlmodldldomldo',
            'odmmodmdmdommdo',
            '.oddodbfbdoddo.',
            '..oodlmdmldoo..',
            '...odmlmlmdo...',
            '...odmdmddo....',
            '....odcddo.....',
            '.....odo.......',
        ),
    ),
    'rime_wraith': (
        dict(o='#25253c', d='#394362', m='#546080', l='#7793ae', h='#a8c6d2',
             a='#2a344b', b='#56838f', c='#9ddacb', f='#d8f4e8'),
        (
            '.....odo....',
            '....ohhldo..',
            '...ohlllmd..',
            '..ohhllmddo.',
            '.ohllaadldo.',
            '.olmacafldo.',
            'odmdaabddmdo',
            'odlmdmbdllmo',
            '.odllcbmlmdo',
            '..odmcbmddo.',
            '..odmdlmdlo.',
            '.oddo.dmdlo.',
            '..oo...ooo..',
        ),
    ),
    'cinderwing_phoenix': (
        dict(o='#321c2c', d='#552b34', m='#8b3a35', l='#bf5235', h='#e4763a',
             a='#984631', b='#ed8d46', c='#ffc573', f='#fff0ae'),
        (
            '...ohc.........',
            '..ohcbl........',
            '.ohbhmdo.......',
            '.olmmlmdo......',
            '..ohfmlmdo.....',
            '..ohcmmbhbo....',
            '..odlmlhbbbo...',
            '..odmllhbboo...',
            '...odlhbddo....',
            '...ohhblddo....',
            '..ohblmlmdo....',
            '..ohbllmdo.....',
            '...odmddo......',
            '....oooo.......',
        ),
    ),
}


def indexed_icon(palette, rows, *, width=None):
    colors = {key: (*bytes.fromhex(value[1:]), 255) for key, value in palette.items()}
    width = width or max(len(row.rstrip('.')) for row in rows)
    left = (16 - width) // 2
    top = (16 - len(rows)) // 2
    image = Image.new('RGBA', (16, 16))
    for y, row in enumerate(rows, top):
        for x, key in enumerate(row, left):
            if key != '.':
                image.putpixel((x, y), colors[key])
    return image


def firefly_egg():
    """Duskflies are an amber particle swarm, with no entity mesh."""
    rows = (
        '......122.......',
        '.....234321.....',
        '....23545321....',
        '...2345443221...',
        '..123445464321..',
        '..234444554321..',
        '..233647443431..',
        '..233564335332..',
        '..233443346431..',
        '..233453436321..',
        '...2346743432...',
        '...2335643321...',
        '....23343321....',
        '.....122221.....',
    )
    palette = ('#221d36', '#29243e', '#373054', '#48405d', '#695277',
               '#ab8050', '#efbc58', '#fff2a3')
    image = indexed_icon({str(i): col for i, col in enumerate(palette)}, rows, width=16)
    for x, y in ((2, 3), (12, 2), (13, 9), (3, 12)):
        image.putpixel((x, y), (227, 173, 80, 255))
    return image


def egg_image(assets, species):
    species_id = species['id']
    model = species.get('model')
    if species_id in PENGUIN_EGGS:
        palette, rows = PENGUIN_EGGS[species_id]
        return indexed_icon(palette, rows, width=max(map(len, rows)))
    if model == 'cinderwing_phoenix' and species_id in BOSS_FORMS:
        image = indexed_icon(*FEATURE_EGGS[model]).transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        add_boss_clasp(image)
        return image
    if species_id in FEATURE_EGGS:
        return indexed_icon(*FEATURE_EGGS[species_id])
    if not model:
        return firefly_egg()
    turn = species_id.endswith('_young') or species_id in BOSS_FORMS or species_id == 'wukong_clone'
    portrait = load_portrait(assets, model, turn=turn)
    return portrait_icon(portrait, species_id, model)


def write_egg(assets, species):
    assets = Path(assets)
    image = egg_image(assets, species)
    path = assets / 'textures' / 'item' / 'spawn_egg' / (species['id'] + '.png')
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    return path


def main():
    resources = Path(__file__).resolve().parents[1] / 'src' / 'main' / 'resources'
    assets = resources / 'assets' / 'aftrhrs'
    definitions = resources / 'data' / 'aftrhrs' / 'species'
    index = json.loads((definitions / 'index.json').read_text(encoding='utf-8'))
    for species_id in index['species']:
        species = json.loads((definitions / (species_id + '.json')).read_text(encoding='utf-8'))
        write_egg(assets, species)
    print(f"Wrote {len(index['species'])} Aftr Hrs spawn egg icons.")


if __name__ == '__main__':
    main()
