"""Ve la ban (compass rose) giong overlay cua app GPS Map Camera."""
import math
from PIL import Image, ImageDraw, ImageFont


def draw_compass(size, bearing_deg=0.0, font_path=None):
    """Tra ve 1 anh RGBA hinh vuong canh `size` chua la ban trong suot nen."""
    # ve o do phan giai gap 3 roi thu nho cho min (antialias)
    ss = 3
    d = size * ss
    img = Image.new("RGBA", (d, d), (0, 0, 0, 0))
    dr = ImageDraw.Draw(img)
    c = d / 2
    r = d * 0.46

    # vong ngoai mo (nen toi ban trong)
    dr.ellipse([c - r, c - r, c + r, c + r], fill=(40, 40, 40, 90))
    ring_w = max(2, int(d * 0.03))
    dr.ellipse([c - r, c - r, c + r, c + r], outline=(230, 230, 230, 230), width=ring_w)

    # kim la ban 2 mau (cyan len tren = huong Bac cua app)
    ang = math.radians(bearing_deg)
    nlen = r * 0.72
    wln = d * 0.10
    tip_n = (c + nlen * math.sin(ang), c - nlen * math.cos(ang))
    tip_s = (c - (nlen * 0.75) * math.sin(ang), c + (nlen * 0.75) * math.cos(ang))
    left = (c + wln * math.cos(ang), c + wln * math.sin(ang))
    right = (c - wln * math.cos(ang), c - wln * math.sin(ang))
    dr.polygon([tip_n, left, right], fill=(0, 190, 220, 235))   # nua tren: cyan
    dr.polygon([tip_s, left, right], fill=(210, 210, 210, 220))  # nua duoi: xam

    # chu N E S W
    try:
        fnt = ImageFont.truetype(font_path, int(d * 0.13)) if font_path else ImageFont.load_default()
    except Exception:
        fnt = ImageFont.load_default()
    labels = {"N": (0, -1), "E": (1, 0), "S": (0, 1), "W": (-1, 0)}
    lr = r * 0.72
    for txt, (dx, dy) in labels.items():
        lx, ly = c + dx * lr, c + dy * lr
        bb = dr.textbbox((0, 0), txt, font=fnt)
        dr.text((lx - (bb[2] - bb[0]) / 2, ly - (bb[3] - bb[1]) / 2), txt,
                font=fnt, fill=(255, 255, 255, 235))

    return img.resize((size, size), Image.LANCZOS)
