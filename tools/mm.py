"""MiniMessage subset -> styled segments (stdlib only). Used by gen_content.py for item names, lore and titles."""
import re

NAMED = {
    'black': '000000', 'dark_blue': '0000aa', 'dark_green': '00aa00', 'dark_aqua': '00aaaa', 'dark_red': 'aa0000',
    'dark_purple': 'aa00aa', 'gold': 'ffaa00', 'gray': 'aaaaaa', 'grey': 'aaaaaa', 'dark_gray': '555555',
    'dark_grey': '555555', 'blue': '5555ff', 'green': '55ff55', 'aqua': '55ffff', 'red': 'ff5555',
    'light_purple': 'ff55ff', 'yellow': 'ffff55', 'white': 'ffffff',
}
DECOS = {'b': 'bold', 'bold': 'bold', 'i': 'italic', 'em': 'italic', 'italic': 'italic', 'u': 'underlined',
         'underlined': 'underlined', 'st': 'strikethrough', 'strikethrough': 'strikethrough', 'obf': 'obfuscated',
         'obfuscated': 'obfuscated'}
TAG = re.compile(r'<(/?)([!a-zA-Z_#][^<>]*)>')


def _color(name):
    name = name.strip().lower()
    if name.startswith('color:') or name.startswith('colour:') or name.startswith('c:'):
        name = name.split(':', 1)[1]
    if name.startswith('#') and len(name) == 7:
        return name
    if name in NAMED:
        return name.replace('grey', 'gray')
    return None


def _hex(c):
    if c.startswith('#'):
        return c[1:]
    return NAMED[c]


def _lerp(a, b, t):
    a, b = _hex(a), _hex(b)
    ca = [int(a[i:i + 2], 16) for i in (0, 2, 4)]
    cb = [int(b[i:i + 2], 16) for i in (0, 2, 4)]
    return '#' + ''.join('%02x' % round(ca[i] + (cb[i] - ca[i]) * t) for i in range(3))


def parse(s):
    """Returns a list of segments: {"text", "color"?, "bold"?, ...}. Gradients become one segment per character."""
    stack = [{}]  # style frames
    out = []
    gradient = None  # (colors, chars collected list of (char, style))
    pos = 0
    pending = []

    def emit(text):
        if not text:
            return
        st = dict(stack[-1])
        if gradient is not None:
            for ch in text:
                gradient[1].append((ch, st))
        else:
            out.append(dict(st, text=text))

    def flush_gradient():
        cols, chars = gradient
        n = len([c for c, _ in chars])
        for i, (ch, st) in enumerate(chars):
            t = i / max(1, n - 1)
            seg = len(cols) - 1
            k = min(int(t * seg), seg - 1) if seg > 0 else 0
            lt = (t * seg - k) if seg > 0 else 0
            col = _lerp(cols[k], cols[k + 1], lt) if seg > 0 else cols[0]
            out.append(dict(st, text=ch, color=col))

    for m in TAG.finditer(s):
        emit(s[pos:m.start()])
        pos = m.end()
        closing, body = m.group(1) == '/', m.group(2)
        low = body.lower()
        if low.startswith('gradient'):
            if closing:
                if gradient is not None:
                    flush_gradient()
                    gradient = None
            else:
                parts = [p for p in body.split(':')[1:] if _color(p)]
                cols = [_color(p) for p in parts] or ['#ffffff', '#ffffff']
                if len(cols) == 1:
                    cols = cols * 2
                gradient = (cols, [])
            continue
        if low in ('reset', 'r'):
            stack[:] = [{}]
            continue
        neg = low.startswith('!')
        key = low[1:] if neg else low
        if key in DECOS:
            if closing:
                if len(stack) > 1:
                    stack.pop()
            else:
                stack.append(dict(stack[-1], **{DECOS[key]: not neg}))
            continue
        col = _color(body)
        if col:
            if closing:
                if len(stack) > 1:
                    stack.pop()
            else:
                stack.append(dict(stack[-1], color=col))
            continue
        # unknown tag (glyph, lang, ...): dropped
    emit(s[pos:])
    if gradient is not None:
        flush_gradient()
    # merge adjacent segments with identical style
    merged = []
    for seg in out:
        if merged and {k: v for k, v in merged[-1].items() if k != 'text'} == {k: v for k, v in seg.items() if k != 'text'}:
            merged[-1]['text'] += seg['text']
        else:
            merged.append(seg)
    return merged


def plain(segs):
    return ''.join(s['text'] for s in segs)


def style_of(segs):
    """The single style of a one-segment text, or None when it has several styles."""
    if len(segs) == 1:
        return {k: v for k, v in segs[0].items() if k != 'text'}
    if not segs:
        return {}
    return None
