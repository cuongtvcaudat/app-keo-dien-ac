"""Tao thumbnail ban do tu toa do bang tile OpenStreetMap (khong can API key).

Neu khong co mang => tra ve None (tool se bo qua thumbnail, khong loi).
"""
import io
import math
import requests
from PIL import Image, ImageDraw

TILE = 256
UA = {"User-Agent": "firefly-stamp-tool/1.0 (field documentation)"}


def _deg2tile(lat, lon, z):
    lat_r = math.radians(lat)
    n = 2 ** z
    x = (lon + 180.0) / 360.0 * n
    y = (1.0 - math.asinh(math.tan(lat_r)) / math.pi) / 2.0 * n
    return x, y


def make_map_thumb(lat, lon, width, height, zoom=15, timeout=8):
    try:
        xf, yf = _deg2tile(lat, lon, zoom)
        xt, yt = int(xf), int(yf)
        # ghep luoi 3x3 tile quanh tam roi crop
        big = Image.new("RGB", (TILE * 3, TILE * 3))
        for i, dx in enumerate((-1, 0, 1)):
            for j, dy in enumerate((-1, 0, 1)):
                url = f"https://tile.openstreetmap.org/{zoom}/{xt+dx}/{yt+dy}.png"
                r = requests.get(url, headers=UA, timeout=timeout)
                r.raise_for_status()
                big.paste(Image.open(io.BytesIO(r.content)).convert("RGB"),
                          (TILE * (i), TILE * (j)))
        # tam that trong big
        cx = TILE + int((xf - xt) * TILE)
        cy = TILE + int((yf - yt) * TILE)
        box = (cx - width // 2, cy - height // 2, cx - width // 2 + width, cy - height // 2 + height)
        thumb = big.crop(box)
        # ve marker do
        dr = ImageDraw.Draw(thumb)
        mx, my = width // 2, height // 2
        rr = max(4, width // 22)
        dr.ellipse([mx - rr, my - rr - rr, mx + rr, my - rr + rr], fill=(220, 40, 40), outline=(255, 255, 255), width=2)
        dr.polygon([(mx - rr // 2, my - rr), (mx + rr // 2, my - rr), (mx, my)], fill=(220, 40, 40))
        return thumb.convert("RGBA")
    except Exception:
        return None
