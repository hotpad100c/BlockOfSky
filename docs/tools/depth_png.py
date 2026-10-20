"""Regenerate the shadow-space depth pictures from the smoke test's raw .f32 dumps.

All maps of one shot share the grey scale of that shot's shadow map (1st..99th percentile of what is nearer than the
far depth, in the middle crop): nearer the sun is whiter, farther darker, nothing (depth 1) dark blue.
Usage: depth_png.py <capture dir> <shot> <out dir> [crop]
"""
import array
import math
import os
import struct
import sys
import zlib


def read(path):
    a = array.array('f')
    with open(path, 'rb') as f:
        a.frombytes(f.read())
    if sys.byteorder != 'little':
        a.byteswap()
    return a


def write_png(path, width, height, rows):
    raw = b''.join(b'\x00' + bytes(row) for row in rows)

    def chunk(kind, data):
        c = struct.pack('>I', len(data)) + kind + data
        return c + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)))
        f.write(chunk(b'IDAT', zlib.compress(raw, 9)))
        f.write(chunk(b'IEND', b''))


def block_centre(capture, shot, size, fallback):
    """The middle of where the sky blocks are in the map (front and back faces), from any shot's maps."""
    xs, ys = [], []
    for name in ('frontNear', 'throughNear', 'far'):
        path = os.path.join(capture, f'depth-{shot}-{name}.f32')
        if os.path.exists(path):
            for i, v in enumerate(read(path)):
                if v < 1.0:
                    xs.append(i % size)
                    ys.append(i // size)
    if not xs:
        return fallback
    return (min(xs) + max(xs)) // 2, (min(ys) + max(ys)) // 2


def main():
    capture, shot, out = sys.argv[1], sys.argv[2], sys.argv[3]
    crop = int(sys.argv[4]) if len(sys.argv) > 4 else 384
    # Where to centre: the blocks of this shot, or of another shot of the same view (holes off has no block maps).
    centre_shot = sys.argv[5] if len(sys.argv) > 5 else shot
    os.makedirs(out, exist_ok=True)
    shadow = read(os.path.join(capture, f'depth-{shot}-shadowtex0.f32'))
    size = int(math.isqrt(len(shadow)))
    crop = min(crop, size)
    cx, cy = block_centre(capture, centre_shot, size, (size // 2, size // 2))
    x0 = max(0, min(size - crop, cx - crop // 2))
    y0 = max(0, min(size - crop, cy - crop // 2))
    near = sorted(shadow[y * size + x] for y in range(y0, y0 + crop) for x in range(x0, x0 + crop)
                  if shadow[y * size + x] < 1.0)
    lo = near[len(near) // 100] if near else 0.0
    hi = max(near[len(near) * 99 // 100] if near else 1.0, lo + 1e-6)
    # The blocks and what is beyond them are inside the box, farther than its roof: widen the scale to them.
    for name in ('frontNear', 'throughNear', 'far', 'throughDepth'):
        path = os.path.join(capture, f'depth-{shot}-{name}.f32')
        if os.path.exists(path):
            values = [v for v in read(path) if v < 1.0]
            if values:
                lo = min(lo, min(values))
                hi = max(hi, max(values) + (max(values) - lo) * 0.05)
    for name in ('shadowtex0', 'frontNear', 'throughNear', 'far', 'throughDepth'):
        path = os.path.join(capture, f'depth-{shot}-{name}.f32')
        if not os.path.exists(path):
            continue
        depth = read(path)
        rows = []
        for y in range(crop):
            row = bytearray()
            source = (y0 + crop - 1 - y) * size
            for x in range(crop):
                d = depth[source + x0 + x]
                if d >= 1.0:
                    row += bytes((0x20, 0x30, 0x60))
                else:
                    g = int(max(0, min(255, 30 + 220 * (hi - d) / (hi - lo))))
                    row += bytes((g, g, g))
            rows.append(row)
        write_png(os.path.join(out, f'{name}.png'), crop, crop, rows)
        print(name, 'ok')


main()
