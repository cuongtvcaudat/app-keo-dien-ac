"""Xoa stamp cu bang AI local nhe (OpenCV top-hat + inpaint).

Khong can torch, khong can mang, khong can key. Chi bat net chu trang manh
trong dung vung goc co stamp roi inpaint -> nen giu nguyen, chu cu bien mat.
Neu thieu opencv/numpy -> tra ve None (engine tu quay ve che do 'cover').
"""
import numpy as np

try:
    import cv2
    HAVE_CV2 = True
except Exception:
    HAVE_CV2 = False

from PIL import Image


def _regions_for(template, W, H):
    """Cac o co the chua stamp cu, suy ra tu template (noi se ve stamp moi)."""
    boxes = []
    t = template.get("text", {})
    # dai chu o day anh (full rong de bat ca chu loi ra ngoai khoi chu moi)
    boxes.append((0.0, 0.66, 1.0, 1.0))
    if template.get("compass", {}).get("enabled"):
        d = template["compass"].get("diameter_frac", 0.16) + 0.04
        boxes.append((0.0, 0.0, d, d + 0.02))
    if template.get("map", {}).get("enabled"):
        mw = template["map"].get("width_frac", 0.25) + 0.02
        mh = template["map"].get("height_frac", 0.16) + 0.02
        boxes.append((0.0, 1.0 - mh, mw, 1.0))
    return [(int(x0*W), int(y0*H), int(x1*W), int(y1*H)) for x0, y0, x1, y1 in boxes]


def smart_erase(pil_img, template):
    if not HAVE_CV2:
        return None
    img = cv2.cvtColor(np.array(pil_img.convert("RGB")), cv2.COLOR_RGB2BGR)
    H, W = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    sat = hsv[:, :, 2] * 0 + hsv[:, :, 1]  # kenh saturation

    # top-hat: noi net SANG manh, bo vung sang lon (troi/may)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (max(9, W // 60), max(9, W // 60)))
    tophat = cv2.morphologyEx(gray, cv2.MORPH_TOPHAT, k)
    bright = tophat > 30
    whitish = sat < 95          # chu trang/xam, loai bot khe troi xanh
    strokes = (bright & whitish).astype(np.uint8) * 255

    # chi giu trong cac o co stamp
    region = np.zeros((H, W), np.uint8)
    for x0, y0, x1, y1 in _regions_for(template, W, H):
        region[max(0, y0):min(H, y1), max(0, x0):min(W, x1)] = 1
    mask = cv2.bitwise_and(strokes, strokes, mask=region)
    mask = cv2.dilate(mask, np.ones((3, 3), np.uint8), iterations=2)

    # BAO VE cau truc RAT toi (cot thep, day dien = gan den): tranh lam meo cot.
    # Nguong thap de KHONG bao ve nham chu trang tren nen cay/dat toi vua.
    dark = (gray < 45).astype(np.uint8) * 255
    dark = cv2.dilate(dark, np.ones((5, 5), np.uint8), iterations=1)
    mask = cv2.bitwise_and(mask, cv2.bitwise_not(dark))

    out = cv2.inpaint(img, mask, 4, cv2.INPAINT_TELEA)
    return Image.fromarray(cv2.cvtColor(out, cv2.COLOR_BGR2RGB))
