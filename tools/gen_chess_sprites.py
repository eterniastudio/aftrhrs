#!/usr/bin/env python3
"""Chess piece sprites for the chess table screen (client/chess/ChessScreen): 16x16 pixel art in the mod's GUI grammar
(1px dark outline, light top-left, shade bottom-right), one sprite per piece and color.

Writes src/client/resources/assets/aftrhrs/textures/gui/sprites/chess/{white,black}_{pawn,...}.png and, with an
argument, a preview sheet (both colors on light and dark squares, x4).
Usage:  python mod/tools/gen_chess_sprites.py [preview.png]      (needs Pillow)
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), 'src', 'client', 'resources', 'assets', 'aftrhrs', 'textures', 'gui',
                   'sprites', 'chess')

# The front of each carving is lit from the upper left. Narrow bronze collars
# and stepped plinths tie the set together, while the tops identify each piece.
CARVINGS = {
    'pawn': (3, (
        '...oo...',
        '..ohbo..',
        '.ohddbo.',
        '.odddco.',
        '..ocbo..',
        '..ogro..',
        '..odbo..',
        '.ohddco.',
        'ohdddcco',
        'oggrrrro',
        'obccccco',
        '.oooooo.',
    )),
    'rook': (2, (
        '.oo..oo..oo.',
        '.oho.oho.ob.',
        '.ohbohbbobo.',
        '.ohdddddcco.',
        '..oggrrrro..',
        '...ohdco....',
        '...ohdco....',
        '...odcco....',
        '..ohddcco...',
        '.ohdddccco..',
        '.oggrrrrro..',
        '.obcccccco..',
        '..oooooooo..',
    )),
    'knight': (1, (
        '......o.o.....',
        '.....ohobo....',
        '....ohdddbo...',
        '...ohdddccbo..',
        '..ohdobdcobo..',
        '.ohdddddccobo.',
        '.odddbbocdcbo.',
        '..obbbo.ocdco.',
        '...ooo..ohcco.',
        '.......ohddco.',
        '......ohddcco.',
        '....oggrrrrco.',
        '...obccccccco.',
        '....oooooooo..',
    )),
    'bishop': (1, (
        '.....oo.....',
        '....ohbo....',
        '...ohddbo...',
        '..ohddocbo..',
        '..ohdoccbo..',
        '..odocccco..',
        '...occcco...',
        '...ogggrro..',
        '....odco....',
        '...ohdcco...',
        '..ohddccco..',
        '.oggrrrrro..',
        '.obcccccco..',
        '..oooooooo..',
    )),
    'queen': (1, (
        '.o..o.oo.o..o.',
        'ogo.goghgo.oro',
        '.oboobhdbooob.',
        '.ohdddhdddcbo.',
        '..oggrgrrrro..',
        '...ohdddcco...',
        '....ohdcco....',
        '.....odco.....',
        '....ohdcco....',
        '...ohddccco...',
        '..ohdddcccco..',
        '.oggrrrrrrro..',
        '.obcccccccco..',
        '..ooooooooo...',
    )),
    'king': (0, (
        '......oo......',
        '.....ohbo.....',
        '....ohghbo....',
        '.....odco.....',
        '..oo.odco.oo..',
        '.ohdoohbooobo.',
        '.ohddddddddbo.',
        '..oggrggrrro..',
        '...ohdddcco...',
        '....ohdcco....',
        '....oddcco....',
        '...ohddccco...',
        '..oggrrrrrro..',
        '..obccccccco..',
        '...oooooooo...',
    )),
}

PALETTES = {
    'white': dict(o='#382729', b='#ac876e', c='#c9a885', d='#ebd4ae',
                  h='#fff0ce', r='#b17749', g='#e8b170'),
    'black': dict(o='#242235', b='#38374d', c='#4b4a63', d='#65677f',
                  h='#a6a9b9', r='#776584', g='#aca0b8'),
}


def sprite(shape, palette):
    top, rows = CARVINGS[shape]
    width = len(rows[0])
    left = (16 - width) // 2
    assert top + len(rows) <= 16 and width <= 16
    colors = {key: (*bytes.fromhex(color[1:]), 255) for key, color in palette.items()}
    image = Image.new('RGBA', (16, 16))
    for y, row in enumerate(rows, top):
        assert len(row) == width, (shape, row)
        for x, pixel in enumerate(row, left):
            if pixel != '.':
                image.putpixel((x, y), colors[pixel])
    return image


def main():
    os.makedirs(OUT, exist_ok=True)
    made = {}
    for color, pal in PALETTES.items():
        for shape in CARVINGS:
            img = sprite(shape, pal)
            img.save(os.path.join(OUT, '%s_%s.png' % (color, shape)))
            made[(color, shape)] = img
    if len(sys.argv) > 1:
        # ChessScreen's actual board colors.
        light, dark = (0xE6, 0xCF, 0xA0), (0x8B, 0x5A, 0x33)
        sheet = Image.new('RGBA', (6 * 20, 4 * 20), (0, 0, 0, 255))
        for row, (color, bg) in enumerate([('white', light), ('white', dark), ('black', light), ('black', dark)]):
            for col, shape in enumerate(CARVINGS):
                cell = Image.new('RGBA', (20, 20), bg + (255,))
                cell.alpha_composite(made[(color, shape)], (2, 2))
                sheet.paste(cell, (col * 20, row * 20))
        sheet = sheet.resize((sheet.width * 4, sheet.height * 4), Image.NEAREST)
        os.makedirs(os.path.dirname(os.path.abspath(sys.argv[1])), exist_ok=True)
        sheet.save(sys.argv[1])
    print('chess sprites: %d written to %s' % (len(made), OUT))


if __name__ == '__main__':
    main()
