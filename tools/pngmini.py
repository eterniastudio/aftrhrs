"""Minimal PNG reader/writer (stdlib only) for the generators: non-interlaced PNGs of any color type at bit depth
1/2/4/8 in, 8-bit RGBA out."""
import struct
import zlib


def _chunks(data):
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a PNG')
    i = 8
    while i < len(data):
        n, = struct.unpack('>I', data[i:i + 4])
        yield data[i + 4:i + 8], data[i + 8:i + 8 + n]
        i += 12 + n


def read(data):
    """(width, height, bytearray RGBA) of PNG bytes."""
    idat, plte, trns = b'', None, None
    for kind, body in _chunks(data):
        if kind == b'IHDR':
            w, h, depth, ctype, _, _, interlace = struct.unpack('>IIBBBBB', body)
        elif kind == b'PLTE':
            plte = body
        elif kind == b'tRNS':
            trns = body
        elif kind == b'IDAT':
            idat += body
    if interlace or depth not in (1, 2, 4, 8):
        raise ValueError('unsupported PNG (interlaced or 16-bit)')
    chans = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bpp = max(1, chans * depth // 8)
    stride = (w * chans * depth + 7) // 8
    raw = zlib.decompress(idat)
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    out = bytearray(w * h * 4)
    for y, line in enumerate(rows):
        if depth < 8:
            per = 8 // depth
            vals = [(line[x // per] >> (8 - depth * (x % per + 1))) & ((1 << depth) - 1) for x in range(w * chans)]
        else:
            vals = line
        for x in range(w):
            o = (y * w + x) * 4
            if ctype == 6:
                out[o:o + 4] = vals[x * 4:x * 4 + 4]
            elif ctype == 2:
                out[o:o + 3] = vals[x * 3:x * 3 + 3]
                out[o + 3] = 0 if trns and tuple(vals[x * 3:x * 3 + 3]) == (trns[1], trns[3], trns[5]) else 255
            elif ctype == 3:
                k = vals[x]
                out[o:o + 3] = plte[k * 3:k * 3 + 3]
                out[o + 3] = trns[k] if trns and k < len(trns) else 255
            else:
                v = vals[x * chans]
                g = v * (255 // ((1 << depth) - 1))
                out[o:o + 3] = bytes((g, g, g))
                if ctype == 4:
                    out[o + 3] = vals[x * 2 + 1]
                else:
                    out[o + 3] = 0 if trns and v == (trns[0] << 8 | trns[1]) else 255
    return w, h, out


def write(w, h, rgba):
    """PNG bytes of 8-bit RGBA pixels."""
    raw = b''.join(b'\x00' + bytes(rgba[y * w * 4:(y + 1) * w * 4]) for y in range(h))

    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body) & 0xffffffff)

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
