"""A small YAML subset parser (stdlib only) for the Oraxen item and recipe configs.

Supports what those files use: block mappings and sequences (including "- key: value" compact mappings and sequences
indented at the same level as their key), flow mappings/sequences ({a: 1, b: [x, y]}), single/double quoted and plain
scalars, comments, null/bool/int/float. No anchors, tags, multi-documents or block scalars.
"""
import re

_INT = re.compile(r'^[-+]?\d+$')
_FLOAT = re.compile(r'^[-+]?(\d+\.\d*|\.\d+|\d+)([eE][-+]?\d+)?$')


def _plain(s):
    s = s.strip()
    if s in ('', '~', 'null', 'Null', 'NULL'):
        return None
    if s in ('true', 'True', 'TRUE'):
        return True
    if s in ('false', 'False', 'FALSE'):
        return False
    if _INT.match(s):
        return int(s)
    if _FLOAT.match(s):
        return float(s)
    return s


def _strip_comment(line):
    """Removes a trailing comment (a '#' at line start or after whitespace, outside quotes)."""
    q = None
    depth = 0
    for i, ch in enumerate(line):
        if q:
            if q == '"' and ch == '\\':
                continue
            if ch == q:
                q = None
            continue
        if ch in ('"', "'") and (i == 0 or line[i - 1] in ' \t[{,:-'):
            q = ch
        elif ch == '#' and (i == 0 or line[i - 1] in ' \t'):
            return line[:i].rstrip()
    return line.rstrip()


class _Flow:
    def __init__(self, s):
        self.s = s
        self.i = 0

    def ws(self):
        while self.i < len(self.s) and self.s[self.i] in ' \t\r\n':
            self.i += 1

    def value(self):
        self.ws()
        c = self.s[self.i] if self.i < len(self.s) else ''
        if c == '{':
            return self.mapping()
        if c == '[':
            return self.seq()
        if c in ('"', "'"):
            return self.quoted()
        start = self.i
        depth = 0
        while self.i < len(self.s):
            ch = self.s[self.i]
            if ch in ',}]' and depth == 0:
                break
            if ch == ':' and (self.i + 1 >= len(self.s) or self.s[self.i + 1] in ' ,}]'):
                break
            self.i += 1
        return _plain(self.s[start:self.i])

    def quoted(self):
        q = self.s[self.i]
        self.i += 1
        out = []
        while self.i < len(self.s):
            ch = self.s[self.i]
            if q == "'" and ch == "'":
                if self.i + 1 < len(self.s) and self.s[self.i + 1] == "'":
                    out.append("'")
                    self.i += 2
                    continue
                self.i += 1
                return ''.join(out)
            if q == '"' and ch == '\\':
                nxt = self.s[self.i + 1]
                out.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\', '/': '/'}.get(nxt, '\\' + nxt))
                self.i += 2
                continue
            if q == '"' and ch == '"':
                self.i += 1
                return ''.join(out)
            out.append(ch)
            self.i += 1
        raise ValueError('unterminated quote: ' + self.s)

    def mapping(self):
        self.i += 1
        d = {}
        while True:
            self.ws()
            if self.s[self.i] == '}':
                self.i += 1
                return d
            k = self.value()
            self.ws()
            if self.i < len(self.s) and self.s[self.i] == ':':
                self.i += 1
                v = self.value()
            else:
                v = None
            d[k] = v
            self.ws()
            if self.s[self.i] == ',':
                self.i += 1

    def seq(self):
        self.i += 1
        out = []
        while True:
            self.ws()
            if self.s[self.i] == ']':
                self.i += 1
                return out
            out.append(self.value())
            self.ws()
            if self.s[self.i] == ',':
                self.i += 1


def _scalar(text):
    text = text.strip()
    if not text:
        return None
    if text[0] in '{[':
        f = _Flow(text)
        return f.value()
    if text[0] in ('"', "'"):
        f = _Flow(text)
        v = f.quoted()
        return v
    return _plain(text)


def _split_key(text):
    """'key: rest' -> (key, rest) or None when the text is not a mapping entry."""
    if text[0] in ('"', "'"):
        f = _Flow(text)
        k = f.quoted()
        rest = text[f.i:]
        if rest.startswith(':') and (len(rest) == 1 or rest[1] == ' '):
            return k, rest[1:].strip()
        return None
    if text[0] in '{[':
        return None
    # the key ends at the first ':' followed by a space or the end of the line (so 'minecraft:nausea:' works)
    m = re.match(r'^(.*?[^\s]):(?:\s+(.*))?$', text)
    if not m or ': ' in m.group(1) or m.group(1).endswith(':'):
        m2 = re.match(r'^(.*?):(?:\s+(.*))?$', text)
        if not m2:
            return None
        m = m2
    key = m.group(1)
    if ': ' in key:
        key, _, rest = text.partition(': ')
        return key, rest.strip()
    return key, (m.group(2) or '').strip()


def loads(src):
    lines = []
    for raw in src.splitlines():
        if raw.strip() in ('---', '...'):
            continue
        s = _strip_comment(raw.replace('\t', '    '))
        if not s.strip():
            continue
        ind = len(s) - len(s.lstrip(' '))
        lines.append((ind, s.strip()))
    pos = [0]

    def block(indent):
        """Parses a block node whose lines start at exactly `indent`."""
        ind, text = lines[pos[0]]
        if text.startswith('- ') or text == '-':
            return seq(ind)
        return mapping(ind)

    def seq(indent):
        out = []
        while pos[0] < len(lines):
            ind, text = lines[pos[0]]
            if ind != indent or not (text.startswith('- ') or text == '-'):
                break
            body = text[1:].strip()
            pos[0] += 1
            if not body:
                if pos[0] < len(lines) and lines[pos[0]][0] > indent:
                    out.append(block(lines[pos[0]][0]))
                else:
                    out.append(None)
                continue
            kv = _split_key(body) if body[0] not in '{["\'' or body[0] in '"\'' else None
            if kv is not None and body[0] not in '{[':
                # compact mapping: "- key: value" with more keys at indent + 2
                sub_indent = indent + (len(text) - len(body))
                d = {}
                k, rest = kv
                d[k] = value_after(rest, sub_indent)
                while pos[0] < len(lines) and lines[pos[0]][0] == sub_indent and not lines[pos[0]][1].startswith('- '):
                    k2, rest2 = _split_key(lines[pos[0]][1])
                    pos[0] += 1
                    d[k2] = value_after(rest2, sub_indent)
                out.append(d)
            else:
                out.append(_scalar(body))
        return out

    def value_after(rest, indent):
        """Value of a 'key: rest' entry at `indent`; nested blocks are deeper (or a sequence at the same indent)."""
        if rest:
            return _scalar(rest)
        if pos[0] < len(lines):
            ind, text = lines[pos[0]]
            if ind > indent:
                return block(ind)
            if ind == indent and (text.startswith('- ') or text == '-'):
                return seq(ind)
        return None

    def mapping(indent):
        d = {}
        while pos[0] < len(lines):
            ind, text = lines[pos[0]]
            if ind != indent or text.startswith('- '):
                break
            kv = _split_key(text)
            if kv is None:
                raise ValueError('bad line: %r' % text)
            k, rest = kv
            pos[0] += 1
            d[k] = value_after(rest, indent)
        return d

    if not lines:
        return {}
    if lines[0][1].startswith('{') and len(lines) == 1:
        return _scalar(lines[0][1])
    out = block(lines[0][0])
    if pos[0] < len(lines):
        raise ValueError('unparsed line %d: %r' % (pos[0], lines[pos[0]]))
    return out


def load(path):
    with open(path, encoding='utf-8') as f:
        return loads(f.read())
