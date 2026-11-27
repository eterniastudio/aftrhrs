"""Assets and data for the storage network blocks (Feather storage module; not Oraxen content, so gen_content.py
doesn't know them). Writes into directories gen_content.py owns and clears, so gen_content.py runs this at its end;
run it by hand after changing it:  python mod/tools/gen_storage.py   (Pillow; reads the 26.3 client jar)

  aftrhrs:feather_storage_terminal     polished blackstone box (the live server's chiseled polished blackstone) with a
                                       sky-blue item screen on the front
  aftrhrs:feather_crafting_terminal    chiseled tuff bricks with a violet crafting-grid screen
  aftrhrs:feather_inventory_connector  chiseled tuff with a green port on every side

  aftrhrs:feather_terminal_remote        Crafting Terminal Remote (tier I): a handheld screen, sky-blue
  aftrhrs:feather_terminal_remote_ender  Ender Crafting Terminal Remote (tier II): the same in violet with an ender eye

Writes blockstates, block/item models (models/block/storage, models/item/storage), front and remote textures
(textures/block/storage, textures/item/storage), item model definitions, loot tables, the recipes (Feather's shapes;
gated by config.yml modules.storage + storage.recipes through the aftrhrs:feather_config load condition; the tier II
remote is a transmute recipe, so it keeps its link) and the pickaxe/wither-immune tag entries.
"""
import io
import json
import os
import zipfile

from PIL import Image

MOD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(MOD, 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'aftrhrs')
DATA = os.path.join(RES, 'data', 'aftrhrs')
MC_TAGS = os.path.join(RES, 'data', 'minecraft', 'tags')
JAR_PATH = os.environ.get('MC_CLIENT_JAR') or os.path.expanduser(
    '~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/'
    'minecraft-clientonly-deobf-26.3.jar')

SKY = (0x8F, 0xD3, 0xFF)
ACCENT = (0xC3, 0xA6, 0xFF)
GOOD = (0x86, 0xE8, 0xB0)
INK = (0x24, 0x1C, 0x42)
INK_DEEP = (0x14, 0x0F, 0x26)
MUTED = (0xA4, 0x9D, 0xC2)

TERMINAL, CRAFTING, CONNECTOR = 'feather_storage_terminal', 'feather_crafting_terminal', 'feather_inventory_connector'
REMOTE, REMOTE_ENDER = 'feather_terminal_remote', 'feather_terminal_remote_ender'


def wjson(path, obj, compact=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        if compact:
            json.dump(obj, f, separators=(',', ':'), ensure_ascii=False)
        else:
            json.dump(obj, f, indent=1, ensure_ascii=False)
            f.write('\n')


def vanilla(jar, name):
    return Image.open(io.BytesIO(jar.read('assets/minecraft/textures/block/%s.png' % name))).convert('RGBA')


def shade(c, f):
    return tuple(max(0, min(255, int(round(v * f)))) for v in c[:3]) + (255,)


def screen(base, glow, motif):
    """A recessed screen (x 2..13, y 3..12) in the block's own stone, lit in {@code glow}."""
    img = base.copy()
    px = img.load()
    x0, y0, x1, y1 = 2, 3, 13, 12
    for x in range(x0 - 1, x1 + 2):  # a darker rim around the screen
        for y in (y0 - 1, y1 + 1):
            px[x, y] = shade(px[x, y], 0.6)
    for y in range(y0 - 1, y1 + 2):
        for x in (x0 - 1, x1 + 1):
            px[x, y] = shade(px[x, y], 0.6)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = INK_DEEP + (255,) if y > y0 else INK + (255,)
    motif(px, glow)
    for x in range(x0, x1 + 1):  # the glass catches the light along the bottom edge
        px[x, y1] = shade(glow, 0.35)
    return img


def items_motif(px, glow):
    """Two rows of three item slots, four of them filled."""
    for row in range(2):
        for col in range(3):
            x, y = 3 + col * 4, 5 + row * 3
            filled = row == 0 or col == 0
            for dx in range(2):
                for dy in range(2):
                    px[x + dx, y + dy] = (glow + (255,)) if filled else shade(glow, 0.25)
            if filled:
                px[x, y] = shade(glow, 1.15)


def grid_motif(px, glow):
    """A 3x3 crafting grid and an arrow to the result."""
    for row in range(3):
        for col in range(3):
            px[3 + col * 2, 5 + row * 2] = glow + (255,)
    for x in range(9, 11):
        px[x, 7] = shade(glow, 0.7)
    px[10, 6] = shade(glow, 0.7)
    px[10, 8] = shade(glow, 0.7)
    for dx in range(2):
        for dy in range(2):
            px[11 + dx, 6 + dy] = shade(glow, 1.1)


def port(base, glow):
    """Chiseled tuff with a small lit port in the middle (the connector's faces)."""
    img = base.copy()
    px = img.load()
    for y in range(5, 11):
        for x in range(5, 11):
            edge = x in (5, 10) or y in (5, 10)
            px[x, y] = shade(px[x, y], 0.55) if edge else INK_DEEP + (255,)
    for y in range(7, 9):
        for x in range(7, 9):
            px[x, y] = glow + (255,)
    px[6, 6] = shade(glow, 0.45)
    return img


# The remote's 16px case has a raised aerial and two stepped side grips.
# Palette letters are literal pixel colors, with the screen motif swapped
# between a blue inventory grid and a green Ender Eye.
REMOTE_CASE = (
    '..........oh....',
    '..........omo...',
    '....ooo...omo...',
    '...ohhhoooomo...',
    '..ohmmmmmmsoo...',
    '..ohcccccckso...',
    '..oh::::::kso...',
    '..oh::::::ksoo..',
    '..oh::::::ksomoo',
    '..ohkkkkkkksomoo',
    '..ommmmmsmsomoo.',
    '..ohhpsqsmkoo...',
    '..omsskssmko....',
    '..omggggsmko....',
    '...osssskko.....',
    '....oooooo......',
)
REMOTE_DISPLAY = {
    False: ('ABABAb', 'bBbBbB', 'AbAbAb'),
    True: ('.ijji.', 'iJxxJi', '.ijji.'),
}
REMOTE_COLORS = {
    False: dict(o='#222937', h='#b7cada', m='#6c8599', s='#405369', k='#2f3b51',
                c='#426a87', p='#b8f0ff', q='#82cde6', g='#536e84',
                A='#a1e6fc', B='#65c6e1', b='#224c6d'),
    True: dict(o='#241d36', h='#b5a1d6', m='#756398', s='#493b64', k='#35294e',
               c='#675184', p='#dacfef', q='#ae90e0', g='#5a4879',
               i='#548e90', j='#83b8a9', J='#b1f6b5', x='#223850'),
}


def remote(ender):
    img = Image.new('RGBA', (16, 16))
    colors = {symbol: (*bytes.fromhex(value[1:]), 255)
              for symbol, value in REMOTE_COLORS[ender].items()}
    colors[':'] = (23, 31, 48, 255) if not ender else INK_DEEP + (255,)
    for y, row in enumerate(REMOTE_CASE):
        for x, symbol in enumerate(row):
            if symbol != '.':
                img.putpixel((x, y), colors[symbol])
    for y, row in enumerate(REMOTE_DISPLAY[ender], 6):
        for x, symbol in enumerate(row, 4):
            if symbol != '.':
                img.putpixel((x, y), colors[symbol])
    return img


# ------------------------------------------------------------------ Storage Vault (connected textures)

VAULT = 'storage_vault'
V_OUT = (24, 26, 36)
V_RIM_L, V_RIM, V_RIM_D = (150, 158, 182), (108, 115, 140), (70, 75, 95)
V_SHADOW = (44, 47, 62)
V_PLATE, V_PLATE_L, V_PLATE_D = (78, 84, 105), (88, 95, 118), (67, 72, 91)
V_CAP, V_CAP_L, V_CAP_D = (70, 76, 96), (80, 87, 109), (60, 65, 83)
V_RIVET, V_INK = (194, 201, 220), (28, 31, 46)
# Which world direction is up / right / down / left on each face's texture (Minecraft's own face UVs).
FACE_DIRS = {
    'up': ('north', 'east', 'south', 'west'), 'down': ('south', 'east', 'north', 'west'),
    'north': ('up', 'west', 'down', 'east'), 'south': ('up', 'east', 'down', 'west'),
    'west': ('up', 'south', 'down', 'north'), 'east': ('up', 'north', 'down', 'south'),
}
DIRS6 = ('north', 'east', 'south', 'west', 'up', 'down')


def vault_face(cap, edges):
    """One face of a vault block. {@code edges} = (up, right, down, left): True where the vault ends (a rim is drawn),
    False where it carries on into the next block (a faint plate seam, and a round boss where four plates meet).
    Caps (the vault's two ends) are darker and carry the lock dial on a 1x1 end or the middle of a 3x3 end."""
    up, right, down, left = edges
    base, light, dark = (V_CAP, V_CAP_L, V_CAP_D) if cap else (V_PLATE, V_PLATE_L, V_PLATE_D)
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            # brushed steel: a fixed grain, the same on every block so plates line up
            n = (x * 7 + y * 3 + (x * y) % 5) % 9
            px[x, y] = (light if n == 0 else dark if n == 4 else base) + (255,)
    # Seams where the next plate starts (top and left only, so a seam is drawn once between two blocks).
    if not up:
        for x in range(16):
            px[x, 0] = dark + (255,)
    if not left:
        for y in range(16):
            px[0, y] = dark + (255,)
    # Round bosses where four plates meet (a quarter on each block).
    for cx, cy, ok in ((0, 0, not up and not left), (16, 0, not up and not right), (0, 16, not down and not left), (16, 16, not down and not right)):
        if not ok:
            continue
        for y in range(16):
            for x in range(16):
                d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
                if d <= 2.6:
                    lit = (x + 0.5 - cx) + (y + 0.5 - cy) < 0
                    px[x, y] = (V_RIVET if d < 1.1 and lit else V_RIM_L if lit else V_RIM if d < 1.8 else V_RIM_D) + (255,)
    # Rims where the vault ends: lit from the top left.
    if right:
        for y in range(16):
            px[12, y], px[13, y], px[14, y] = light + (255,), V_RIM + (255,), V_RIM_D + (255,)
    if down:
        for x in range(16):
            px[x, 12], px[x, 13], px[x, 14] = light + (255,), V_RIM + (255,), V_RIM_D + (255,)
    if left:
        for y in range(16):
            px[1, y], px[2, y], px[3, y] = V_RIM_L + (255,), V_RIM + (255,), V_SHADOW + (255,)
    if up:
        for x in range(16):
            px[x, 1], px[x, 2], px[x, 3] = V_RIM_L + (255,), V_RIM + (255,), V_SHADOW + (255,)
    # Where two rims meet, the lit one turns the corner cleanly.
    if up and left:
        px[1, 1], px[2, 2], px[3, 3] = V_RIM_L + (255,), V_RIM + (255,), V_SHADOW + (255,)
        px[2, 1], px[1, 2] = V_RIM_L + (255,), V_RIM_L + (255,)
    if down and right:
        px[13, 13], px[14, 14] = V_RIM + (255,), V_RIM_D + (255,)
    if up and right:
        px[13, 1], px[14, 1], px[14, 2] = V_RIM + (255,), V_RIM_D + (255,), V_RIM_D + (255,)
    if down and left:
        px[1, 13], px[1, 14], px[2, 14] = V_RIM + (255,), V_RIM_D + (255,), V_RIM_D + (255,)
    # Rivets along the rims, two per block side.
    for t in (5, 10):
        if up:
            px[t, 2] = V_RIVET + (255,)
        if left:
            px[2, t] = V_RIVET + (255,)
        if down:
            px[t, 13] = V_RIVET + (255,)
        if right:
            px[13, t] = V_RIVET + (255,)
    # The outline only along the vault's own edges.
    for i in range(16):
        if up:
            px[i, 0] = V_OUT + (255,)
        if down:
            px[i, 15] = V_OUT + (255,)
        if left:
            px[0, i] = V_OUT + (255,)
        if right:
            px[15, i] = V_OUT + (255,)
    if cap and (all(edges) or not any(edges)):
        vault_dial(px, 3.6 if all(edges) else 5.6)
    return img


def vault_dial(px, r):
    """The lock dial: a ringed wheel with four spokes and a lit sky-blue tumbler at the top."""
    c = 7.5
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            d = (dx * dx + dy * dy) ** 0.5
            if d > r + 0.5:
                continue
            if d > r - 1.0:
                px[x, y] = (V_RIM_L if dx + dy < -1 else V_RIM_D if dx + dy > 1 else V_RIM) + (255,)
            elif d > r - 1.8:
                px[x, y] = V_OUT + (255,)
            else:
                px[x, y] = V_INK + (255,)
    # A two-pixel cross of spokes (the wheel's center sits between pixels 7 and 8), then the hub.
    reach = int(r - 1.6)
    for k in range(reach):
        for a in (7, 8):
            for x, y in ((a, 6 - k), (a, 9 + k), (6 - k, a), (9 + k, a)):
                if 0 <= x < 16 and 0 <= y < 16:
                    px[x, y] = V_RIM_D + (255,)
    px[7, 7], px[8, 7], px[7, 8], px[8, 8] = V_RIVET + (255,), V_RIM_L + (255,), V_RIM_L + (255,), V_RIM + (255,)
    # The tumbler light at the top of the ring.
    top = int(round(c - r)) + 1
    px[7, top], px[8, top] = SKY + (255,), SKY + (255,)
    px[7, top - 1], px[8, top - 1] = shade(SKY, 0.6), shade(SKY, 0.6)


def vault_assets():
    """Textures (cap/side x 16 rim combinations), 64 block models (the six "same vault" neighbors; vaults stand upright,
    so the caps are the top and bottom), the blockstate (the old sideways axis property no longer changes the look),
    the item, loot, recipe and the pickaxe tag."""
    tex_dir = os.path.join(ASSETS, 'textures', 'block', 'storage', 'vault')
    os.makedirs(tex_dir, exist_ok=True)
    for cap in (False, True):
        for bits in range(16):
            edges = tuple(bool(bits >> (3 - i) & 1) for i in range(4))
            name = ('cap_' if cap else 'side_') + ''.join('1' if e else '0' for e in edges)
            vault_face(cap, edges).save(os.path.join(tex_dir, name + '.png'))
    model_dir = os.path.join(ASSETS, 'models', 'block', 'storage', 'vault')
    if os.path.isdir(model_dir):
        for f in os.listdir(model_dir):
            os.remove(os.path.join(model_dir, f))
    variants = {}
    for mask in range(64):
        linked = {d: bool(mask >> i & 1) for i, d in enumerate(DIRS6)}
        key = 'v_' + ''.join('1' if linked[d] else '0' for d in DIRS6)
        textures = {}
        for face, dirs in FACE_DIRS.items():
            cap = face in ('up', 'down')
            edge = ''.join('0' if linked[d] else '1' for d in dirs)
            textures[face] = 'aftrhrs:block/storage/vault/%s_%s' % ('cap' if cap else 'side', edge)
        textures['particle'] = 'aftrhrs:block/storage/vault/side_1111'
        wjson(os.path.join(model_dir, key + '.json'), {'parent': 'minecraft:block/cube', 'textures': textures}, True)
        for axis in ('x', 'z'):
            state = 'axis=%s,' % axis + ','.join('%s=%s' % (d, 'true' if linked[d] else 'false') for d in sorted(DIRS6))
            variants[state] = {'model': 'aftrhrs:block/storage/vault/' + key}
    wjson(os.path.join(ASSETS, 'blockstates', VAULT + '.json'), {'variants': variants}, True)
    wjson(os.path.join(ASSETS, 'items', VAULT + '.json'), {'model': {'type': 'minecraft:model', 'model': 'aftrhrs:block/storage/vault/v_000000'}})
    wjson(os.path.join(DATA, 'loot_table', 'blocks', VAULT + '.json'), {
        'type': 'minecraft:block', 'random_sequence': 'aftrhrs:blocks/' + VAULT,
        'pools': [{'rolls': 1, 'bonus_rolls': 0, 'conditions': [{'condition': 'minecraft:survives_explosion'}],
                   'entries': [{'type': 'minecraft:item', 'name': 'aftrhrs:' + VAULT}]}]})


def main():
    jar = zipfile.ZipFile(JAR_PATH)
    vault_assets()
    tex_dir = os.path.join(ASSETS, 'textures', 'block', 'storage')
    os.makedirs(tex_dir, exist_ok=True)
    screen(vanilla(jar, 'polished_blackstone'), SKY, items_motif).save(os.path.join(tex_dir, 'terminal_front.png'))
    screen(vanilla(jar, 'polished_tuff'), ACCENT, grid_motif).save(os.path.join(tex_dir, 'crafting_front.png'))
    port(vanilla(jar, 'chiseled_tuff'), GOOD).save(os.path.join(tex_dir, 'connector_side.png'))
    port(vanilla(jar, 'chiseled_tuff_top'), GOOD).save(os.path.join(tex_dir, 'connector_top.png'))

    models = {
        TERMINAL: {'parent': 'minecraft:block/orientable_with_bottom', 'textures': {
            'front': 'aftrhrs:block/storage/terminal_front', 'side': 'minecraft:block/chiseled_polished_blackstone',
            'top': 'minecraft:block/polished_blackstone', 'bottom': 'minecraft:block/polished_blackstone',
            'particle': 'minecraft:block/chiseled_polished_blackstone'}},
        CRAFTING: {'parent': 'minecraft:block/orientable_with_bottom', 'textures': {
            'front': 'aftrhrs:block/storage/crafting_front', 'side': 'minecraft:block/chiseled_tuff_bricks',
            'top': 'minecraft:block/chiseled_tuff_bricks_top', 'bottom': 'minecraft:block/chiseled_tuff_bricks_top',
            'particle': 'minecraft:block/chiseled_tuff_bricks'}},
        CONNECTOR: {'parent': 'minecraft:block/cube_column', 'textures': {
            'side': 'aftrhrs:block/storage/connector_side', 'end': 'aftrhrs:block/storage/connector_top',
            'particle': 'minecraft:block/chiseled_tuff'}},
    }
    item_tex = os.path.join(ASSETS, 'textures', 'item', 'storage')
    os.makedirs(item_tex, exist_ok=True)
    remote(False).save(os.path.join(item_tex, 'terminal_remote.png'))
    remote(True).save(os.path.join(item_tex, 'terminal_remote_ender.png'))
    for rid, tex in ((REMOTE, 'terminal_remote'), (REMOTE_ENDER, 'terminal_remote_ender')):
        wjson(os.path.join(ASSETS, 'models', 'item', 'storage', rid + '.json'),
              {'parent': 'minecraft:item/handheld', 'textures': {'layer0': 'aftrhrs:item/storage/' + tex}})
        wjson(os.path.join(ASSETS, 'items', rid + '.json'), {'model': {'type': 'minecraft:model', 'model': 'aftrhrs:item/storage/' + rid}})

    for bid, model in models.items():
        wjson(os.path.join(ASSETS, 'models', 'block', 'storage', bid + '.json'), model)
        ref = 'aftrhrs:block/storage/' + bid
        if bid == CONNECTOR:
            state = {'variants': {'': {'model': ref}}}
        else:
            state = {'variants': {'facing=%s' % f: ({'model': ref, 'y': y} if y else {'model': ref})
                                  for f, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}}
        wjson(os.path.join(ASSETS, 'blockstates', bid + '.json'), state)
        wjson(os.path.join(ASSETS, 'items', bid + '.json'), {'model': {'type': 'minecraft:model', 'model': ref}})
        wjson(os.path.join(DATA, 'loot_table', 'blocks', bid + '.json'), {
            'type': 'minecraft:block', 'random_sequence': 'aftrhrs:blocks/' + bid,
            'pools': [{'rolls': 1, 'bonus_rolls': 0, 'conditions': [{'condition': 'minecraft:survives_explosion'}],
                       'entries': [{'type': 'minecraft:item', 'name': 'aftrhrs:' + bid}]}]})

    gate = [{'condition': 'aftrhrs:feather_config', 'keys': ['modules.storage', 'storage.recipes']}]

    def shaped(name, pattern, key, result):
        r = {'fabric:load_conditions': gate, 'type': 'minecraft:crafting_shaped', 'category': 'misc',
             'group': 'feather_storage', 'pattern': pattern, 'key': key, 'result': {'id': result, 'count': 1}}
        wjson(os.path.join(DATA, 'recipe', 'storage', name + '.json'), r)

    shaped('terminal', ['PCP', 'HGL', 'PCP'], {'P': '#minecraft:planks', 'C': 'minecraft:comparator', 'H': 'minecraft:chest',
                                               'G': 'minecraft:glowstone_dust', 'L': 'minecraft:glass'}, 'aftrhrs:' + TERMINAL)
    shaped('connector', ['PCP', 'HDH', 'PEP'], {'P': '#minecraft:planks', 'C': 'minecraft:comparator', 'H': 'minecraft:chest',
                                                'D': 'minecraft:diamond', 'E': 'minecraft:ender_pearl'}, 'aftrhrs:' + CONNECTOR)
    shaped('crafting', ['TDT', 'DSD', 'TDT'], {'T': 'minecraft:crafting_table', 'D': 'minecraft:diamond',
                                               'S': 'aftrhrs:' + TERMINAL}, 'aftrhrs:' + CRAFTING)
    shaped('remote', ['IGI', 'RTR', 'IEI'], {'I': 'minecraft:iron_ingot', 'G': 'minecraft:glass_pane', 'R': 'minecraft:redstone',
                                             'T': 'aftrhrs:' + CRAFTING, 'E': 'minecraft:ender_pearl'}, 'aftrhrs:' + REMOTE)
    # A barrel between two iron ingots (Create's Item Vault uses iron sheets; there are none here).
    shaped('vault', ['I', 'B', 'I'], {'I': 'minecraft:iron_ingot', 'B': 'minecraft:barrel'}, 'aftrhrs:' + VAULT)
    # Tier II: the tier I remote plus an end crystal (ender eye, ghast tear: the Nether and the End). A transmute
    # recipe keeps the remote's components, so its link carries over.
    wjson(os.path.join(DATA, 'recipe', 'storage', 'remote_ender.json'), {
        'fabric:load_conditions': gate, 'type': 'minecraft:crafting_transmute', 'category': 'misc', 'group': 'feather_storage',
        'input': 'aftrhrs:' + REMOTE, 'material': 'minecraft:end_crystal', 'result': {'id': 'aftrhrs:' + REMOTE_ENDER}})

    # Tag entries merge into whatever gen_content.py wrote there.
    for tag in ('block/mineable/pickaxe', 'block/wither_immune', 'block/dragon_immune'):
        path = os.path.join(MC_TAGS, tag + '.json')
        data = {'replace': False, 'values': []}
        if os.path.exists(path):
            with open(path, encoding='utf-8') as f:
                data = json.load(f)
        vals = data.setdefault('values', [])
        for bid in (TERMINAL, CRAFTING, CONNECTOR) + ((VAULT,) if tag.endswith('pickaxe') else ()):
            if 'aftrhrs:' + bid not in vals:
                vals.append('aftrhrs:' + bid)
        wjson(path, data)
    print('storage blocks: 3 blockstates/models/items, 4 textures, 3 recipes, 3 loot tables; 2 remotes (items, textures, recipes)')


if __name__ == '__main__':
    main()
