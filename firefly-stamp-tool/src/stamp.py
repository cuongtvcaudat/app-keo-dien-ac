"""Engine dan stamp: lam sach vung stamp cu (cover) + ve stamp moi len anh."""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

from compass import draw_compass
from mapthumb import make_map_thumb

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_REGULAR = os.path.join(HERE, "fonts", "DejaVuSans.ttf")
FONT_BOLD = os.path.join(HERE, "fonts", "DejaVuSans-Bold.ttf")


class _SafeDict(dict):
    def __missing__(self, key):
        return ""


def _render_line(tpl, row):
    """Dien du lieu vao 1 dong. Tra ve (text, bo_qua?)."""
    required = tpl.get("required", [])
    for f in required:
        if not str(row.get(f, "")).strip():
            return "", True  # thieu du lieu bat buoc => bo dong
    text = tpl["text"].format_map(_SafeDict(row))
    text = " ".join(text.split())  # gom khoang trang thua khi thieu field
    if not text.strip():
        return "", True
    return text, False


def _load_font(bold, px):
    path = FONT_BOLD if bold else FONT_REGULAR
    try:
        return ImageFont.truetype(path, px)
    except Exception:
        return ImageFont.load_default()


def _cover_old_stamp(img, cfg):
    """Lam sach vung stamp cu bang cach phu 1 dai o day anh."""
    W, H = img.size
    c = cfg["cover"]
    region_h = int(H * c["height_frac"])
    y0 = H - region_h if c.get("region", "bottom") == "bottom" else 0
    style = c.get("style", "darken")

    if style == "blur":
        band = img.crop((0, y0, W, H)).filter(ImageFilter.GaussianBlur(c.get("blur_radius", 18)))
        img.paste(band, (0, y0))
    elif style == "solid":
        overlay = Image.new("RGB", (W, region_h), tuple(c.get("solid_color", [0, 0, 0])))
        img.paste(overlay, (0, y0))
    elif style == "scrim":
        # gradient toi dan tu tren xuong day: tu nhien tren anh sach, che stamp cu tot hon
        max_a = int(255 * c.get("opacity", 0.55))
        col = tuple(c.get("solid_color", [0, 0, 0]))
        exp = c.get("gradient_exp", 0.8)
        grad = Image.new("L", (1, region_h))
        for yy in range(region_h):
            grad.putpixel((0, yy), int(max_a * (yy / max(1, region_h - 1)) ** exp))
        alpha = grad.resize((W, region_h))
        overlay = Image.new("RGBA", (W, region_h), col + (0,))
        overlay.putalpha(alpha)
        base = img.crop((0, y0, W, H)).convert("RGBA")
        img.paste(Image.alpha_composite(base, overlay).convert("RGB"), (0, y0))
    else:  # darken: phu lop den mo deu
        overlay = Image.new("RGBA", (W, region_h), (0, 0, 0, int(255 * c.get("opacity", 0.45))))
        base = img.crop((0, y0, W, H)).convert("RGBA")
        img.paste(Image.alpha_composite(base, overlay).convert("RGB"), (0, y0))
    return img


def _draw_text_block(img, template, row):
    """Ve khoi chu (nhieu dong) voi bong do, canh trai/phai."""
    W, H = img.size
    t = template["text"]
    base_px = max(10, int(W * t["font_size_frac"]))
    spacing = t.get("line_spacing", 1.16)
    align = t.get("align", "right")

    # do rong toi da cho 1 dong (tru le 2 ben)
    pad_x = int(W * t.get("pad_x_frac", 0.02))
    margin = int(W * 0.02)
    avail = W - pad_x - margin

    # chuan bi cac dong (co auto-fit: dong nao dai qua thi thu nho font vua khung)
    _probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    items = []
    for ln in template["lines"]:
        text, skip = _render_line(ln, row)
        if skip:
            continue
        px = max(10, int(base_px * ln.get("size", 1.0)))
        font = _load_font(True, px)
        bb = _probe.textbbox((0, 0), text, font=font)
        w = bb[2] - bb[0]
        if w > avail:
            px = max(10, int(px * avail / w))
            font = _load_font(True, px)
        items.append((text, font, px))
    if not items:
        return img

    # do cao tong
    total_h = sum(int(px * spacing) for _, _, px in items)
    pad_b = int(H * t.get("pad_bottom_frac", 0.015))
    y = H - pad_b - total_h

    img = img.convert("RGBA")
    txt_layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    dr = ImageDraw.Draw(txt_layer)

    sh = t.get("shadow", {})
    st = t.get("stroke", {})
    stroke_w = int(W * st.get("width_frac", 0.002)) if st.get("enabled") else 0

    for text, font, px in items:
        bb = dr.textbbox((0, 0), text, font=font, stroke_width=stroke_w)
        tw = bb[2] - bb[0]
        x = (W - pad_x - tw) if align == "right" else pad_x
        dr.text((x, y), text, font=font, fill=tuple(t["color"]) + (255,),
                stroke_width=stroke_w or None,
                stroke_fill=tuple(st.get("color", [0, 0, 0])) + (255,) if stroke_w else None)
        y += int(px * spacing)

    # bong do: blur lop chu (mau toi) roi dat duoi
    if sh.get("enabled", True):
        off = max(1, int(W * sh.get("offset_frac", 0.003)))
        alpha = txt_layer.split()[3]
        shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
        col = tuple(sh.get("color", [0, 0, 0])) + (int(255 * sh.get("opacity", 0.75)),)
        solid = Image.new("RGBA", img.size, col)
        shadow.paste(solid, (off, off), alpha)
        shadow = shadow.filter(ImageFilter.GaussianBlur(sh.get("blur", 4)))
        img = Image.alpha_composite(img, shadow)

    img = Image.alpha_composite(img, txt_layer)
    return img.convert("RGB")


def _paste_decorations(img, template, row):
    W, H = img.size
    img = img.convert("RGBA")

    comp = template.get("compass", {})
    if comp.get("enabled"):
        size = int(W * comp.get("diameter_frac", 0.16))
        pad = int(W * comp.get("pad_frac", 0.015))
        try:
            bearing = float(str(row.get("bearing_deg", "") or 0).replace("°", "").strip() or 0)
        except ValueError:
            bearing = 0.0
        c_img = draw_compass(size, bearing, FONT_BOLD)
        pos = {"top-left": (pad, pad), "top-right": (W - size - pad, pad)}.get(
            comp.get("corner", "top-left"), (pad, pad))
        img.alpha_composite(c_img, pos)

    mp = template.get("map", {})
    if mp.get("enabled"):
        try:
            lat = float(str(row.get("lat", "")).rstrip("NnSs ") or 0)
            lon = float(str(row.get("lon", "")).rstrip("EeWw ") or 0)
        except ValueError:
            lat = lon = 0
        if lat or lon:
            mw = int(W * mp.get("width_frac", 0.25))
            mh = int(H * mp.get("height_frac", 0.16))
            thumb = make_map_thumb(lat, lon, mw, mh, mp.get("zoom", 15))
            if thumb is not None:
                pad = int(W * mp.get("pad_frac", 0.0))
                pos = {"bottom-left": (pad, H - mh - pad),
                       "bottom-right": (W - mw - pad, H - mh - pad)}.get(
                    mp.get("corner", "bottom-left"), (pad, H - mh - pad))
                img.alpha_composite(thumb, pos)

    return img.convert("RGB")


def stamp_image(src_path, dst_path, template, row, config, firefly_client=None):
    """Xu ly 1 anh: lam sach stamp cu -> ve stamp moi -> luu."""
    img = Image.open(src_path).convert("RGB")

    cleanup = config.get("old_stamp_cleanup", {})
    mode = cleanup.get("mode", "cover")
    if mode == "firefly" and firefly_client is not None:
        cleaned = firefly_client.remove_stamp(img)
        if cleaned is not None:
            img = cleaned
        else:
            img = _cover_old_stamp(img, cleanup)  # fallback neu Firefly loi
    elif mode == "cover":
        img = _cover_old_stamp(img, cleanup)
    # mode == "none": khong lam gi (anh sach san)

    img = _paste_decorations(img, template, row)
    img = _draw_text_block(img, template, row)

    os.makedirs(os.path.dirname(dst_path) or ".", exist_ok=True)
    img.save(dst_path, quality=92)
    return dst_path
