#!/usr/bin/env python3
"""Rebuilds the igloo dome from the new snow blocks: overrides vanilla data/minecraft/structure/igloo/top.nbt.

Starts from the 26.3 template (read from the Loom Minecraft jar) and keeps its footprint, interior, furniture,
trapdoor and the basement hookup (igloo/middle and igloo/bottom are untouched, IglooPieces places them as before):
  - walls, dome ring and cap: snow bricks; the floor inside and the tunnel floor: polished snow
  - a chiseled snow keystone over the tunnel mouth, polished snow lintels over the two ice windows, a chiseled snow
    boss in the middle of the ceiling
  - snow brick stairs step the dome down on the outside (two rings) and over the tunnel, snow brick slabs round off
    the four corners, so it reads as a built dome instead of a stack of cubes
The trapdoor hole of a basement-less igloo is filled with polished snow by IglooPieceMixin.

Run: python mod/tools/blocksets/igloo.py [--dump]
"""
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import nbt  # noqa: E402

MOD = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(MOD, 'src', 'main', 'resources', 'data', 'minecraft', 'structure', 'igloo', 'top.nbt')
JAR = os.path.expanduser('~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/26.3/'
                         'minecraft-common-deobf-26.3.jar')

BRICKS = 'aftrhrs:arcana_snow_bricks'
POLISHED = 'aftrhrs:arcana_polished_snow'
CHISELED = 'aftrhrs:arcana_chiseled_snow'
STAIRS = 'aftrhrs:arcana_snow_brick_stairs'
SLAB = 'aftrhrs:arcana_snow_brick_slab'


def load_vanilla():
    with zipfile.ZipFile(JAR) as z:
        return nbt.loads(z.read('data/minecraft/structure/igloo/top.nbt'))


def build():
    name, root = load_vanilla()
    palette = root['palette'][1][1]
    blocks = root['blocks'][1][1]

    def pid(e):
        return e['id'][1]

    def state(bid, **props):
        for i, e in enumerate(palette):
            p = {k: v[1] for k, v in e.get('properties', (nbt.COMPOUND, {}))[1].items()}
            if pid(e) == bid and p == props:
                return i
        e = {'id': nbt.string(bid)}
        if props:
            e['properties'] = (nbt.COMPOUND, {k: nbt.string(v) for k, v in props.items()})
        palette.append(e)
        return len(palette) - 1

    grid = {tuple(b['pos'][1][1]): b for b in blocks}

    def at(x, y, z):
        b = grid.get((x, y, z))
        return pid(palette[b['state'][1]]) if b else None

    snow = 'minecraft:snow_block'
    special = {(3, 3, 0): CHISELED, (0, 2, 4): POLISHED, (6, 2, 4): POLISHED, (3, 4, 4): CHISELED}
    swapped = 0
    for (x, y, z), b in grid.items():
        if pid(palette[b['state'][1]]) != snow:
            continue
        if (x, y, z) in special:
            new = special[(x, y, z)]
        elif y == 0:
            above = at(x, 1, z)
            new = POLISHED if above not in (snow, 'minecraft:ice', None) else BRICKS
        else:
            new = BRICKS
        b['state'] = (nbt.INT, state(new))
        swapped += 1

    added = []

    def put(x, y, z, s):
        if (x, y, z) in grid:
            raise SystemExit('igloo: %s already has a block' % ((x, y, z),))
        b = {'pos': (nbt.LIST, (nbt.INT, [x, y, z])), 'state': (nbt.INT, s)}
        blocks.append(b)
        grid[(x, y, z)] = b
        added.append((x, y, z))

    def stairs(facing):
        return state(STAIRS, facing=facing, half='bottom', shape='straight', waterlogged='false')

    # lower ring of steps on top of the outer walls (y 3)
    for z in (3, 4, 5):
        put(0, 3, z, stairs('east'))
        put(6, 3, z, stairs('west'))
    for x in (2, 3, 4):
        put(x, 3, 7, stairs('north'))
    for z in (0, 1):  # over the tunnel walls
        put(2, 3, z, stairs('east'))
        put(4, 3, z, stairs('west'))
    for x, z in ((1, 2), (5, 2), (1, 6), (5, 6)):
        put(x, 3, z, state(SLAB, type='bottom', waterlogged='false'))
    # upper ring around the cap (y 4)
    for z in (3, 4, 5):
        put(1, 4, z, stairs('east'))
        put(5, 4, z, stairs('west'))
    for x in (2, 3, 4):
        put(x, 4, 2, stairs('south'))
        put(x, 4, 6, stairs('north'))

    # Snow brick slabs on the tunnel corners would need y 3 cells at z 2 outside the ring: those are the slabs above.
    data = nbt.dumps(name, root)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'wb') as f:
        f.write(data)
    return swapped, len(added)


def dump(path):
    with open(path, 'rb') as f:
        _, root = nbt.loads(f.read())
    pal = [nbt.plain((nbt.COMPOUND, e)) for e in root['palette'][1][1]]
    sx, sy, sz = root['size'][1][1]
    grid = {tuple(b['pos'][1][1]): b['state'][1] for b in root['blocks'][1][1]}
    short = {}
    for i, e in enumerate(pal):
        print(i, e)
    letters = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
    for y in range(sy):
        print('y', y)
        for z in range(sz):
            print(''.join(letters[grid[(x, y, z)]] if (x, y, z) in grid else '.' for x in range(sx)))
    return short


if __name__ == '__main__':
    if '--dump' in sys.argv:
        dump(OUT)
    else:
        s, a = build()
        print('igloo top: %d snow blocks replaced, %d steps/slabs added -> %s' % (s, a, OUT))
