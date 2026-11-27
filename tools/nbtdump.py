"""Minimal NBT / Anvil reader for inspecting Paper worlds (stdlib only).
Usage: python nbtdump.py <file.mca> [filter-substring]  -> prints entities / block entities as JSON-ish."""
import gzip, io, json, struct, sys, zlib

def read_tag(f, t):
    if t == 1: return struct.unpack('>b', f.read(1))[0]
    if t == 2: return struct.unpack('>h', f.read(2))[0]
    if t == 3: return struct.unpack('>i', f.read(4))[0]
    if t == 4: return struct.unpack('>q', f.read(8))[0]
    if t == 5: return struct.unpack('>f', f.read(4))[0]
    if t == 6: return struct.unpack('>d', f.read(8))[0]
    if t == 7:
        n = struct.unpack('>i', f.read(4))[0]; return list(f.read(n))
    if t == 8:
        n = struct.unpack('>H', f.read(2))[0]; return f.read(n).decode('utf-8', 'replace')
    if t == 9:
        et = f.read(1)[0]; n = struct.unpack('>i', f.read(4))[0]
        return [read_tag(f, et) for _ in range(n)]
    if t == 10:
        out = {}
        while True:
            tt = f.read(1)[0]
            if tt == 0: return out
            n = struct.unpack('>H', f.read(2))[0]; name = f.read(n).decode('utf-8', 'replace')
            out[name] = read_tag(f, tt)
    if t == 11:
        n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack('>%di' % n, f.read(4 * n)))
    if t == 12:
        n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack('>%dq' % n, f.read(8 * n)))
    raise ValueError(t)

def read_root(data):
    f = io.BytesIO(data); t = f.read(1)[0]
    n = struct.unpack('>H', f.read(2))[0]; f.read(n)
    return read_tag(f, t)

def chunks(path):
    with open(path, 'rb') as fh: buf = fh.read()
    for i in range(1024):
        off = int.from_bytes(buf[i*4:i*4+3], 'big'); cnt = buf[i*4+3]
        if off == 0: continue
        p = off * 4096; ln = struct.unpack('>i', buf[p:p+4])[0]; comp = buf[p+4]
        raw = buf[p+5:p+4+ln]
        data = zlib.decompress(raw) if comp == 2 else gzip.decompress(raw) if comp == 1 else raw
        yield read_root(data)

if __name__ == '__main__':
    flt = sys.argv[2] if len(sys.argv) > 2 else None
    for c in chunks(sys.argv[1]):
        items = c.get('Entities', []) + c.get('block_entities', [])
        for e in items:
            s = json.dumps(e, default=str)
            if flt is None or flt in s:
                print(s[:4000]); print()
