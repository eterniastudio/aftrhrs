"""Typed NBT read/write (stdlib only) for structure templates: every value is a (tag type, value) pair, so a file
read and written back is byte-for-byte the same data (ints stay ints, lists keep their element type)."""
import gzip
import io
import struct

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)
_FMT = {BYTE: '>b', SHORT: '>h', INT: '>i', LONG: '>q', FLOAT: '>f', DOUBLE: '>d'}


def _read(f, t):
    if t in _FMT:
        fmt = _FMT[t]
        return struct.unpack(fmt, f.read(struct.calcsize(fmt)))[0]
    if t == BYTE_ARRAY:
        n = struct.unpack('>i', f.read(4))[0]
        return f.read(n)
    if t == STRING:
        n = struct.unpack('>H', f.read(2))[0]
        return f.read(n).decode('utf-8')
    if t == LIST:
        et = f.read(1)[0]
        n = struct.unpack('>i', f.read(4))[0]
        return (et, [_read(f, et) for _ in range(n)])
    if t == COMPOUND:
        out = {}
        while True:
            tt = f.read(1)[0]
            if tt == END:
                return out
            n = struct.unpack('>H', f.read(2))[0]
            name = f.read(n).decode('utf-8')
            out[name] = (tt, _read(f, tt))
    if t == INT_ARRAY:
        n = struct.unpack('>i', f.read(4))[0]
        return list(struct.unpack('>%di' % n, f.read(4 * n)))
    if t == LONG_ARRAY:
        n = struct.unpack('>i', f.read(4))[0]
        return list(struct.unpack('>%dq' % n, f.read(8 * n)))
    raise ValueError('tag type %d' % t)


def _write(f, t, v):
    if t in _FMT:
        f.write(struct.pack(_FMT[t], v))
    elif t == BYTE_ARRAY:
        f.write(struct.pack('>i', len(v)) + bytes(v))
    elif t == STRING:
        b = v.encode('utf-8')
        f.write(struct.pack('>H', len(b)) + b)
    elif t == LIST:
        et, items = v
        f.write(bytes([et]) + struct.pack('>i', len(items)))
        for it in items:
            _write(f, et, it)
    elif t == COMPOUND:
        for name, (tt, vv) in v.items():
            b = name.encode('utf-8')
            f.write(bytes([tt]) + struct.pack('>H', len(b)) + b)
            _write(f, tt, vv)
        f.write(bytes([END]))
    elif t == INT_ARRAY:
        f.write(struct.pack('>i', len(v)) + struct.pack('>%di' % len(v), *v))
    elif t == LONG_ARRAY:
        f.write(struct.pack('>i', len(v)) + struct.pack('>%dq' % len(v), *v))
    else:
        raise ValueError('tag type %d' % t)


def loads(data):
    """gzip'd (or raw) NBT -> (root name, root compound)."""
    if data[:2] == b'\x1f\x8b':
        data = gzip.decompress(data)
    f = io.BytesIO(data)
    t = f.read(1)[0]
    n = struct.unpack('>H', f.read(2))[0]
    name = f.read(n).decode('utf-8')
    return name, _read(f, t)


def dumps(name, root):
    f = io.BytesIO()
    b = name.encode('utf-8')
    f.write(bytes([COMPOUND]) + struct.pack('>H', len(b)) + b)
    _write(f, COMPOUND, root)
    # mtime 0: the same input always gives the same file
    return gzip.compress(f.getvalue(), mtime=0)


def plain(v):
    """(type, value) tree -> plain Python (for printing)."""
    t, x = v
    if t == COMPOUND:
        return {k: plain(vv) for k, vv in x.items()}
    if t == LIST:
        return [plain((x[0], it)) for it in x[1]]
    return x


def string(s):
    return (STRING, s)


def compound(**kw):
    return (COMPOUND, dict(kw))
