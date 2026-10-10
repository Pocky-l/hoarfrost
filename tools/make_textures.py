"""Draws the textures of Hoarfrost.

Usage: python tools/make_textures.py   (from the mod folder; needs numpy and pillow)

* textures/fx/flakes.png: a strip of 16 sprites of 64x64 (11 used), white with the shape in the alpha channel:
  speck, round flake, two six-armed crystals, two fluffy clumps of crystals, grain, puff of powder, motion streak and an
  out-of-focus disc; and a cloud of snow, shaded lighter on top.
* textures/block/snow_cover.png: fresh snow, 64x64 for 4x4 blocks (16 pixels per block like vanilla), tiling seamlessly.
  Its alpha holds soft noise between 0.52 and 1: the renderer scales it by how deep the snow is, and the cutout shader
  drops what falls below one half, so thin snow becomes a dusting with gaps of an organic shape.
"""
import math
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "hoarfrost", "textures")
S = 64


def grid():
    y, x = np.mgrid[0:S, 0:S].astype(np.float64)
    return x - (S - 1) / 2.0, y - (S - 1) / 2.0


def smoothstep(e0, e1, v):
    t = np.clip((v - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def segment_distance(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    t = np.clip(((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy), 0.0, 1.0)
    return np.hypot(px - ax - t * dx, py - ay - t * dy), t


def stroke(px, py, ax, ay, bx, by, width_a, width_b):
    """Anti-aliased tapered line from a to b."""
    d, t = segment_distance(px, py, ax, ay, bx, by)
    w = width_a + (width_b - width_a) * t
    return 1.0 - smoothstep(w * 0.5 - 0.6, w * 0.5 + 0.6, d)


def gaussian(sigma, px=None, py=None, cx=0.0, cy=0.0):
    if px is None:
        px, py = grid()
    return np.exp(-((px - cx) ** 2 + (py - cy) ** 2) / (2.0 * sigma * sigma))


def speck():
    return gaussian(6.5)


def round_flake(rng):
    x, y = grid()
    angle = np.arctan2(y, x)
    r = np.hypot(x, y)
    edge = 16.0 + 1.4 * np.sin(angle * 3 + rng.uniform(0, 6)) + 1.0 * np.sin(angle * 5 + rng.uniform(0, 6))
    body = 1.0 - smoothstep(edge - 3.5, edge + 1.5, r)
    return np.clip(body * (0.85 + 0.15 * gaussian(8.0)) + 0.25 * gaussian(11.0), 0, 1)


def dendrite(rng, length=27.0, scale=1.0, cx=0.0, cy=0.0, rotation=0.0, px=None, py=None):
    if px is None:
        px, py = grid()
    a = np.zeros_like(px)
    length *= scale
    for k in range(6):
        theta = rotation + k * math.pi / 3.0
        ux, uy = math.cos(theta), math.sin(theta)
        ex, ey = cx + ux * length, cy + uy * length
        a = np.maximum(a, stroke(px, py, cx, cy, ex, ey, 2.6 * scale + 0.4, 1.0))
        for along, side in ((0.38, 0.42), (0.58, 0.3), (0.78, 0.17)):
            bx, by = cx + ux * length * along, cy + uy * length * along
            for sign in (-1.0, 1.0):
                phi = theta + sign * math.pi / 3.0
                sx, sy = bx + math.cos(phi) * length * side, by + math.sin(phi) * length * side
                a = np.maximum(a, stroke(px, py, bx, by, sx, sy, 1.7 * scale + 0.3, 0.8))
    hexagon = np.hypot(px - cx, py - cy) < 3.6 * scale + 0.5
    a = np.maximum(a, hexagon * 1.0)
    return a


def crystal(rng):
    a = dendrite(rng, rotation=rng.uniform(0, math.pi / 3))
    return np.clip(a + 0.22 * gaussian(7.0), 0, 1)


def plate_star(rng):
    x, y = grid()
    a = np.zeros_like(x)
    rotation = rng.uniform(0, math.pi / 3)
    for k in range(6):
        theta = rotation + k * math.pi / 3.0
        ux, uy = math.cos(theta), math.sin(theta)
        a = np.maximum(a, stroke(x, y, 0, 0, ux * 23, uy * 23, 6.0, 1.6) * 0.85)
        a = np.maximum(a, stroke(x, y, ux * 8, uy * 8, ux * 23, uy * 23, 1.6, 0.9))
    r = np.hypot(x, y)
    angle = np.arctan2(y, x) - rotation
    hexr = 8.0 / np.cos(((angle + math.pi / 6) % (math.pi / 3)) - math.pi / 6)
    plate = 1.0 - smoothstep(hexr - 1.0, hexr + 0.6, r)
    rim = (1.0 - smoothstep(0.6, 1.6, np.abs(r - hexr * 0.6))) * plate
    a = np.maximum(a, plate * 0.8)
    a = np.maximum(a, rim)
    return np.clip(a + 0.2 * gaussian(7.0), 0, 1)


def clump(rng):
    """Several small crystals stuck together, as large flakes are."""
    x, y = grid()
    a = np.zeros_like(x)
    for _ in range(8):
        r = rng.uniform(0, 15)
        phi = rng.uniform(0, 2 * math.pi)
        cx, cy = math.cos(phi) * r, math.sin(phi) * r
        if rng.uniform() < 0.65:
            part = dendrite(rng, length=12.0, scale=rng.uniform(0.6, 1.0), cx=cx, cy=cy, rotation=rng.uniform(0, 1.1), px=x, py=y)
        else:
            part = gaussian(rng.uniform(3.0, 5.0), x, y, cx, cy) * 1.2
        a = a + part * rng.uniform(0.7, 1.0)
    a = np.clip(a, 0, 1)
    return np.clip(a * 0.95 + 0.25 * gaussian(13.0), 0, 1)


def grain(rng):
    x, y = grid()
    angle = np.arctan2(y, x)
    edge = 7.5 + 0.8 * np.sin(angle * 4 + rng.uniform(0, 6))
    return 1.0 - smoothstep(edge - 1.5, edge + 1.0, np.hypot(x, y))


def puff(rng):
    x, y = grid()
    base = gaussian(15.0)
    noise = np.zeros_like(x)
    for _ in range(6):
        noise += gaussian(rng.uniform(5, 9), x, y, rng.uniform(-10, 10), rng.uniform(-10, 10))
    return np.clip(base * (0.55 + 0.45 * np.clip(noise, 0, 1)) * 0.9, 0, 1)


def streak():
    x, y = grid()
    across = np.exp(-(y ** 2) / (2.0 * 4.5 ** 2))
    along = smoothstep(-31.0, -18.0, x) * (1.0 - smoothstep(18.0, 31.0, x))
    return across * along * 0.9


def bokeh():
    x, y = grid()
    r = np.hypot(x, y)
    disc = 1.0 - smoothstep(23.0, 28.0, r)
    rim = np.exp(-((r - 24.0) ** 2) / 18.0)
    return np.clip(disc * 0.5 + rim * 0.2, 0, 1)


def veil(rng):
    """A cloud of snow: soft billowing lobes, lit from above (brighter top, a cooler grey below) for a sense of volume."""
    x, y = grid()
    a = np.zeros_like(x)
    for _ in range(14):
        cx, cy = rng.uniform(-14, 14), rng.uniform(-9, 9)
        a += gaussian(rng.uniform(6, 11), x, y, cx, cy) * rng.uniform(0.5, 1.0)
    edge = 1.0 - smoothstep(16.0, 31.0, np.hypot(x, y * 1.15))
    billows = periodic_noise(rng, 4) * 0.5 + periodic_noise(rng, 8) * 0.35 + periodic_noise(rng, 16) * 0.15
    a = np.clip(a / a.max(), 0, 1) * edge * (0.25 + 0.95 * billows)
    a = np.clip(a * 1.3, 0, 1)
    shade = 0.86 + 0.14 * np.clip(0.5 - y / 40.0, 0, 1)
    return a * 0.85, shade


def flakes():
    rng = np.random.default_rng(1203)
    sprites = [speck(), round_flake(rng), crystal(rng), plate_star(rng), clump(rng), clump(rng), grain(rng), puff(rng), streak(), bokeh()]
    sheet = np.zeros((S, S * 16, 4), dtype=np.uint8)
    sheet[..., 0:3] = 255
    for i, alpha in enumerate(sprites):
        sheet[:, i * S:(i + 1) * S, 3] = np.round(np.clip(alpha, 0, 1) * 255).astype(np.uint8)
    cloud, shade = veil(rng)
    sheet[:, 10 * S:11 * S, 3] = np.round(np.clip(cloud, 0, 1) * 255).astype(np.uint8)
    for c, tone in enumerate((1.0, 1.0, 1.03)):
        sheet[:, 10 * S:11 * S, c] = np.round(np.clip(shade * tone, 0, 1) * 255).astype(np.uint8)
    path = os.path.join(OUT, "fx", "flakes.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(sheet, "RGBA").save(path, optimize=True)


def periodic_noise(rng, cells, size=S):
    """Smooth value noise that tiles over the texture: a lattice of cells x cells, wrapped."""
    lattice = rng.uniform(0, 1, (cells, cells))
    coords = np.arange(size) * cells / size
    i0 = np.floor(coords).astype(int)
    f = coords - i0
    f = f * f * (3 - 2 * f)
    i1 = (i0 + 1) % cells
    a = lattice[np.ix_(i0, i0)]
    b = lattice[np.ix_(i0, i1)]
    c = lattice[np.ix_(i1, i0)]
    d = lattice[np.ix_(i1, i1)]
    fy = f[:, None]
    fx = f[None, :]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def snow_cover():
    rng = np.random.default_rng(77)
    coverage = periodic_noise(rng, 8) * 0.55 + periodic_noise(rng, 16) * 0.3 + periodic_noise(rng, 32) * 0.15
    coverage = (coverage - coverage.min()) / (coverage.max() - coverage.min())
    shade = periodic_noise(rng, 4) * 0.6 + periodic_noise(rng, 16) * 0.4
    grain_noise = rng.uniform(-1, 1, (S, S))
    image = np.zeros((S, S, 4), dtype=np.float64)
    # Neutral and bright: the game tints it to the colour of the vanilla snow texture in use.
    base = np.array([250.0, 250.0, 250.0])
    for c in range(3):
        image[..., c] = base[c] - 5.0 * (shade - 0.5) + 1.6 * grain_noise
    # A few sparkling crystals.
    sparkle = rng.uniform(0, 1, (S, S)) > 0.985
    image[sparkle, 0:3] = 255.0
    image[..., 3] = 0.52 + 0.48 * coverage
    path = os.path.join(OUT, "block", "snow_cover.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out = np.round(np.clip(image, 0, 255)).astype(np.uint8)
    out[..., 3] = np.round(np.clip(image[..., 3], 0, 1) * 255).astype(np.uint8)
    Image.fromarray(out, "RGBA").save(path, optimize=True)


def snow_leaves():
    """White, snowy leaves (as in Bedrock Edition): drawn over the sides of leaves under snow.

    A leafy pattern in white and pale grey, with a few holes like fancy leaves; alpha 0.55..1 elsewhere, so a thin
    snow shows only the whitest leaves and a full one all of them.
    """
    size = 16
    rng = np.random.default_rng(31)
    leaf = np.kron(rng.uniform(0, 1, (size // 2, size // 2)), np.ones((2, 2))) * 0.6 + rng.uniform(0, 1, (size, size)) * 0.4
    image = np.zeros((size, size, 4), dtype=np.uint8)
    tone = 0.84 + 0.16 * leaf
    image[..., 0] = np.round(tone * 250).astype(np.uint8)
    image[..., 1] = np.round(tone * 252).astype(np.uint8)
    image[..., 2] = np.round(np.minimum(1.0, tone * 1.02) * 255).astype(np.uint8)
    holes = rng.uniform(0, 1, (size, size)) < 0.1
    alpha = 0.55 + 0.45 * leaf
    alpha[holes] = 0.0
    image[..., 3] = np.round(alpha * 255).astype(np.uint8)
    path = os.path.join(OUT, "block", "snow_leaves.png")
    Image.fromarray(image, "RGBA").save(path, optimize=True)


def main():
    flakes()
    snow_cover()
    snow_leaves()


if __name__ == "__main__":
    main()
