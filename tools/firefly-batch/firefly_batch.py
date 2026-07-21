#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
firefly_batch.py — Tool RPA tự động hóa Adobe Firefly (bản Web / Firefly Workspace)
để tạo ảnh nghiệm thu trạm HÀNG LOẠT.

Ý tưởng: dùng pyautogui điều khiển chuột/bàn phím trên giao diện Firefly.
Dữ liệu trạm đọc từ Excel (pandas). Với mỗi dòng: upload ảnh mẫu, dán prompt
đã ghép dữ liệu, bấm Generate, chờ render, bấm Download rồi dọn ảnh về
thư mục Output/<MaTram>.

Chạy nhanh:
    python firefly_batch.py init-config      # tạo file config.json mẫu
    python firefly_batch.py make-sample       # tạo data.xlsx mẫu
    python firefly_batch.py calibrate         # lấy toạ độ các nút trên màn hình
    python firefly_batch.py run               # chạy batch thật
    python firefly_batch.py run --dry-run     # chạy thử, KHÔNG click, chỉ in ra

Xem README.md để biết cách cấu hình toạ độ và đường dẫn.
"""

import argparse
import json
import os
import re
import shutil
import sys
import time
from datetime import datetime
from pathlib import Path

# ---------------------------------------------------------------------------
# Import mềm các thư viện bên thứ 3 — báo lỗi rõ ràng nếu thiếu.
# pandas chỉ cần cho 'make-sample' và 'run'; pyautogui/pyperclip chỉ cần khi
# điều khiển giao diện. 'init-config' / 'calibrate' không cần pandas, nên ta
# import muộn (lazy) để tool vẫn chạy được khi chưa cài đủ thư viện.
# ---------------------------------------------------------------------------
pd = None
pyautogui = None
pyperclip = None


def _load_pandas():
    """Nạp pandas khi cần đọc/ghi dữ liệu bảng."""
    global pd
    if pd is not None:
        return
    try:
        import pandas as _pd
    except ImportError:
        sys.exit("Thiếu thư viện 'pandas'. Cài bằng: pip install -r requirements.txt")
    pd = _pd


def _load_gui_libs():
    """Nạp pyautogui + pyperclip khi cần điều khiển giao diện."""
    global pyautogui, pyperclip
    if pyautogui is not None:
        return
    try:
        import pyautogui as _pg
        import pyperclip as _pc
    except ImportError as e:
        sys.exit(
            "Thiếu thư viện điều khiển giao diện (%s).\n"
            "Cài bằng: pip install -r requirements.txt" % e.name
        )
    # An toàn: kéo chuột lên góc trên-trái màn hình để DỪNG KHẨN CẤP.
    _pg.FAILSAFE = True
    # Nghỉ ngắn giữa các thao tác để giao diện kịp phản hồi.
    _pg.PAUSE = 0.4
    pyautogui = _pg
    pyperclip = _pc


HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"


# ---------------------------------------------------------------------------
# Cấu hình mặc định — được ghi ra config.json khi chạy `init-config`.
# Người dùng chỉ cần sửa file JSON, KHÔNG cần đụng vào code.
# ---------------------------------------------------------------------------
DEFAULT_CONFIG = {
    "excel_file": "data.xlsx",
    "excel_sheet": 0,
    "template_image": "templates/mau_nghiem_thu.png",
    "output_folder": "Output",
    "downloads_folder": "",  # rỗng = tự dò thư mục Downloads của HĐH

    "prompt_template": (
        "Perform a precise text substitution in the top-right red text block "
        "of the image. Replace with [ThoiGian], [ToaDo], and [MaTram] "
        "[NoiDung]. Keep all other background elements unchanged."
    ),

    # Số ảnh Firefly trả ra mỗi lần Generate (để đặt tên result_1..result_N).
    "images_per_generate": 1,

    # Thời gian chờ (giây).
    "wait_after_generate": 25,   # chờ Firefly render xong (15–30s tuỳ mạng)
    "wait_upload": 4,            # chờ dialog chọn file mở ra
    "wait_download": 6,          # chờ trình duyệt tải xong ảnh
    "start_countdown": 5,        # đếm ngược trước khi bắt đầu để bạn mở Firefly

    # ------------------------------------------------------------------
    # TOẠ ĐỘ các nút trên màn hình (pixel). Lấy bằng `python firefly_batch.py
    # calibrate`. Đơn vị: [x, y]. Nếu để null thì bước đó bị bỏ qua.
    # ------------------------------------------------------------------
    "coords": {
        "upload_button": None,      # vùng/nút tải ảnh lên
        "prompt_box": None,         # ô nhập prompt
        "generate_button": None,    # nút Generate (xanh dương, góc phải dưới)
        "download_button": None,    # nút Download (phía trên kết quả)
        "confirm_download": None    # (tuỳ chọn) nút xác nhận trong hộp thoại tải
    },

    # ------------------------------------------------------------------
    # (Tuỳ chọn) Nhận diện nút bằng HÌNH ẢNH thay vì toạ độ cố định.
    # Nếu bật, tool sẽ tìm ảnh nút trên màn hình (cần OpenCV). Ưu tiên
    # image trước, không thấy thì rơi về toạ độ ở trên.
    # ------------------------------------------------------------------
    "use_image_recognition": False,
    "button_images": {
        "upload_button": "buttons/upload.png",
        "generate_button": "buttons/generate.png",
        "download_button": "buttons/download.png"
    },
    "image_match_confidence": 0.85
}


# ---------------------------------------------------------------------------
# Tiện ích
# ---------------------------------------------------------------------------
def log(msg):
    print("[%s] %s" % (datetime.now().strftime("%H:%M:%S"), msg), flush=True)


def sanitize_foldername(name):
    """Bỏ các ký tự không hợp lệ để làm tên thư mục an toàn."""
    name = str(name).strip()
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name)
    return name or "UNKNOWN"


def load_config():
    if not CONFIG_PATH.exists():
        sys.exit(
            "Chưa có config.json. Chạy trước: python firefly_batch.py init-config"
        )
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    # Gộp với mặc định để không thiếu khoá khi bổ sung tính năng mới.
    merged = json.loads(json.dumps(DEFAULT_CONFIG))
    _deep_update(merged, cfg)
    return merged


def _deep_update(base, extra):
    for k, v in extra.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            _deep_update(base[k], v)
        else:
            base[k] = v
    return base


def resolve_path(p):
    """Đường dẫn tương đối tính từ thư mục chứa script."""
    if not p:
        return None
    p = Path(p)
    return p if p.is_absolute() else (HERE / p)


def default_downloads_dir():
    """Dò thư mục Downloads mặc định trên Windows/macOS/Linux."""
    home = Path.home()
    for name in ("Downloads", "Tải xuống"):
        cand = home / name
        if cand.exists():
            return cand
    return home / "Downloads"


def build_prompt(template, row):
    """Thay các placeholder [MaTram] [ThoiGian] [ToaDo] [NoiDung] bằng dữ liệu."""
    def get(col):
        val = row.get(col, "")
        return "" if pd.isna(val) else str(val).strip()

    mapping = {
        "[MaTram]": get("MaTram"),
        "[ThoiGian]": get("ThoiGian"),
        "[ToaDo]": get("ToaDo"),
        "[NoiDung]": get("NoiDung"),
    }
    out = template
    for key, val in mapping.items():
        out = out.replace(key, val)
    return out


# ---------------------------------------------------------------------------
# Thao tác giao diện
# ---------------------------------------------------------------------------
def click_at(coord, label, dry_run=False):
    """Click vào toạ độ [x, y]. Trả về False nếu toạ độ chưa cấu hình."""
    if not coord:
        log("  ⚠ Bỏ qua '%s' (chưa cấu hình toạ độ)." % label)
        return False
    x, y = coord
    log("  → Click %s tại (%d, %d)" % (label, x, y))
    if not dry_run:
        pyautogui.moveTo(x, y, duration=0.3)
        pyautogui.click()
    return True


def click_button(cfg, key, label, dry_run=False):
    """
    Click nút theo thứ tự ưu tiên:
      1. Nhận diện hình ảnh (nếu bật use_image_recognition).
      2. Toạ độ pixel cố định trong config.
    """
    if cfg.get("use_image_recognition") and not dry_run:
        img_rel = cfg.get("button_images", {}).get(key)
        img_path = resolve_path(img_rel) if img_rel else None
        if img_path and img_path.exists():
            try:
                loc = pyautogui.locateCenterOnScreen(
                    str(img_path),
                    confidence=cfg.get("image_match_confidence", 0.85),
                )
                if loc:
                    log("  → Click %s (nhận diện ảnh) tại (%d, %d)"
                        % (label, loc.x, loc.y))
                    pyautogui.moveTo(loc.x, loc.y, duration=0.3)
                    pyautogui.click()
                    return True
                log("  ⚠ Không thấy '%s' trên màn hình, thử toạ độ cố định."
                    % label)
            except Exception as e:  # OpenCV chưa cài / lỗi so khớp
                log("  ⚠ Lỗi nhận diện ảnh '%s' (%s), dùng toạ độ cố định."
                    % (label, e))
    return click_at(cfg["coords"].get(key), label, dry_run=dry_run)


def paste_text(text, dry_run=False):
    """Dán text qua clipboard (an toàn với tiếng Việt / Unicode)."""
    log("  → Dán prompt (%d ký tự)" % len(text))
    if dry_run:
        log("     %s" % text)
        return
    pyperclip.copy(text)
    time.sleep(0.3)
    # Xoá nội dung cũ trong ô rồi dán.
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.1)
    pyautogui.press("delete")
    time.sleep(0.1)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(0.4)


def upload_template(cfg, template_path, dry_run=False):
    """
    Mở dialog chọn file của HĐH rồi gõ đường dẫn ảnh mẫu để tải lên.
    Cách này chạy tốt trên Windows; trên macOS dùng Cmd+Shift+G để nhập path.
    """
    if not click_button(cfg, "upload_button", "nút Upload", dry_run=dry_run):
        return
    time.sleep(cfg["wait_upload"])
    log("  → Nhập đường dẫn ảnh mẫu vào hộp thoại: %s" % template_path)
    if dry_run:
        return
    if sys.platform == "darwin":
        pyautogui.hotkey("command", "shift", "g")  # macOS: ô "Go to folder"
        time.sleep(0.6)
    pyperclip.copy(str(template_path))
    pyautogui.hotkey("command" if sys.platform == "darwin" else "ctrl", "v")
    time.sleep(0.4)
    pyautogui.press("enter")   # xác nhận đường dẫn
    time.sleep(0.6)
    pyautogui.press("enter")   # nút Open trong dialog
    time.sleep(1.0)


def snapshot_downloads(downloads_dir):
    """Chụp danh sách file ảnh hiện có trong Downloads (để phát hiện file mới)."""
    exts = (".jpg", ".jpeg", ".png", ".webp")
    return {
        p.name
        for p in downloads_dir.iterdir()
        if p.is_file() and p.suffix.lower() in exts
    }


def wait_new_downloads(downloads_dir, before, timeout, expected):
    """
    Chờ file mới xuất hiện trong Downloads và tải xong (không còn .crdownload/.part).
    Trả về danh sách Path mới, sắp theo thời gian tạo.
    """
    exts = (".jpg", ".jpeg", ".png", ".webp")
    deadline = time.time() + max(timeout, 5)
    while time.time() < deadline:
        pending = list(downloads_dir.glob("*.crdownload")) + \
            list(downloads_dir.glob("*.part"))
        now = {
            p.name
            for p in downloads_dir.iterdir()
            if p.is_file() and p.suffix.lower() in exts
        }
        new = now - before
        if new and not pending and len(new) >= expected:
            paths = sorted(
                (downloads_dir / n for n in new),
                key=lambda p: p.stat().st_mtime,
            )
            return paths
        time.sleep(1)
    # Hết giờ: trả về những gì có (có thể rỗng).
    now = {
        p.name
        for p in downloads_dir.iterdir()
        if p.is_file() and p.suffix.lower() in exts
    }
    new = now - before
    return sorted((downloads_dir / n for n in new),
                  key=lambda p: p.stat().st_mtime)


def move_results(new_files, dest_dir, images_per_generate):
    """Đổi tên result_1, result_2... và chuyển vào thư mục trạm."""
    if not new_files:
        log("  ⚠ Không phát hiện file tải về nào để di chuyển.")
        return
    # Chỉ lấy tối đa số ảnh kỳ vọng (những file mới nhất).
    files = new_files[-images_per_generate:] if images_per_generate else new_files
    for i, src in enumerate(files, start=1):
        ext = src.suffix.lower() or ".jpg"
        dest = dest_dir / ("result_%d%s" % (i, ext))
        # Tránh ghi đè nếu chạy lại nhiều lần.
        n = i
        while dest.exists():
            n += 1
            dest = dest_dir / ("result_%d%s" % (n, ext))
        shutil.move(str(src), str(dest))
        log("  ✓ Lưu ảnh: %s" % dest)


# ---------------------------------------------------------------------------
# Các lệnh (subcommands)
# ---------------------------------------------------------------------------
def cmd_init_config(_args):
    if CONFIG_PATH.exists():
        log("config.json đã tồn tại — không ghi đè. Xoá file cũ nếu muốn tạo lại.")
        return
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(DEFAULT_CONFIG, f, ensure_ascii=False, indent=2)
    log("Đã tạo %s. Hãy mở ra sửa toạ độ (chạy 'calibrate') và đường dẫn." % CONFIG_PATH)


def cmd_make_sample(_args):
    """Tạo data.xlsx mẫu với đúng 4 cột yêu cầu."""
    _load_pandas()
    sample = pd.DataFrame(
        [
            {
                "MaTram": "LDG0100-11",
                "ThoiGian": "08:30 ngày 21/07/2026",
                "ToaDo": "11.9404, 108.4583",
                "NoiDung": "Nghiệm thu kéo dây AC tuyến 1 pha",
            },
            {
                "MaTram": "LDG_TA_NUNG",
                "ThoiGian": "09:15 ngày 21/07/2026",
                "ToaDo": "11.9210, 108.3907",
                "NoiDung": "Nghiệm thu trạm Tà Nung",
            },
        ]
    )
    out = HERE / "data.xlsx"
    try:
        sample.to_excel(out, index=False)
    except ImportError:
        sys.exit("Cần 'openpyxl' để ghi Excel. Cài: pip install openpyxl")
    log("Đã tạo file mẫu: %s" % out)


def cmd_calibrate(_args):
    """In toạ độ chuột theo thời gian thực để lấy vị trí các nút."""
    _load_gui_libs()
    print(
        "\n=== CHẾ ĐỘ LẤY TOẠ ĐỘ ===\n"
        "Di chuột đến từng nút trên Firefly rồi đọc toạ độ (x, y) hiển thị.\n"
        "Ghi các số này vào 'coords' trong config.json.\n"
        "Nhấn Ctrl+C để thoát.\n"
    )
    try:
        while True:
            x, y = pyautogui.position()
            print("  Toạ độ chuột: (%4d, %4d)   " % (x, y), end="\r", flush=True)
            time.sleep(0.1)
    except KeyboardInterrupt:
        print("\nĐã thoát chế độ lấy toạ độ.")


def cmd_run(args):
    _load_pandas()
    cfg = load_config()

    # --- Nạp & kiểm tra dữ liệu ---
    excel_path = resolve_path(cfg["excel_file"])
    if not excel_path or not excel_path.exists():
        sys.exit("Không tìm thấy file Excel: %s (chạy 'make-sample' để tạo mẫu)"
                 % excel_path)
    df = pd.read_excel(excel_path, sheet_name=cfg["excel_sheet"])
    required = {"MaTram", "ThoiGian", "ToaDo", "NoiDung"}
    missing = required - set(df.columns)
    if missing:
        sys.exit("File Excel thiếu cột: %s. Cần đủ: MaTram, ThoiGian, ToaDo, NoiDung"
                 % ", ".join(sorted(missing)))

    template_path = resolve_path(cfg["template_image"])
    if not args.dry_run and (not template_path or not template_path.exists()):
        sys.exit("Không tìm thấy ảnh mẫu (template): %s" % template_path)

    output_root = resolve_path(cfg["output_folder"])
    output_root.mkdir(parents=True, exist_ok=True)

    downloads_dir = (
        resolve_path(cfg["downloads_folder"])
        if cfg["downloads_folder"]
        else default_downloads_dir()
    )

    dry = args.dry_run
    if not dry:
        _load_gui_libs()

    log("Tổng số trạm cần xử lý: %d" % len(df))
    log("Thư mục Output: %s" % output_root)
    log("Thư mục Downloads theo dõi: %s" % downloads_dir)
    if dry:
        log("*** CHẠY THỬ (dry-run): chỉ in các bước, KHÔNG click chuột ***")

    # Đếm ngược để bạn kịp click sang cửa sổ Firefly.
    if not dry:
        for s in range(cfg["start_countdown"], 0, -1):
            print("  Bắt đầu sau %d giây... (kéo chuột lên góc trái để HUỶ)" % s,
                  end="\r", flush=True)
            time.sleep(1)
        print()

    ok, fail = 0, 0
    for idx, row in df.iterrows():
        ma_tram = sanitize_foldername(row.get("MaTram", "UNKNOWN"))
        log("──────── [%d/%d] Trạm: %s ────────" % (idx + 1, len(df), ma_tram))

        # 1) Tạo thư mục output cho trạm.
        dest_dir = output_root / ma_tram
        dest_dir.mkdir(parents=True, exist_ok=True)

        # 2) Ghép prompt.
        prompt = build_prompt(cfg["prompt_template"], row)

        try:
            # 3) Upload ảnh mẫu.
            upload_template(cfg, template_path, dry_run=dry)

            # 4) Nhập prompt.
            click_button(cfg, "prompt_box", "ô nhập Prompt", dry_run=dry)
            paste_text(prompt, dry_run=dry)

            # 5) Generate.
            click_button(cfg, "generate_button", "nút Generate", dry_run=dry)

            # 6) Chờ render.
            before = set() if dry else snapshot_downloads(downloads_dir)
            log("  ⏳ Chờ Firefly render %d giây..." % cfg["wait_after_generate"])
            if not dry:
                time.sleep(cfg["wait_after_generate"])

            # 7) Download.
            click_button(cfg, "download_button", "nút Download", dry_run=dry)
            if cfg["coords"].get("confirm_download"):
                time.sleep(0.8)
                click_at(cfg["coords"]["confirm_download"],
                         "xác nhận Download", dry_run=dry)

            # 8) Chờ tải xong & dọn file về thư mục trạm.
            if not dry:
                new_files = wait_new_downloads(
                    downloads_dir, before,
                    timeout=cfg["wait_download"] + 20,
                    expected=cfg["images_per_generate"],
                )
                move_results(new_files, dest_dir, cfg["images_per_generate"])
            else:
                log("  → (dry-run) Sẽ lưu ảnh vào: %s" % dest_dir)

            ok += 1
        except pyautogui.FailSafeException if pyautogui else Exception:
            log("‼ DỪNG KHẨN CẤP (fail-safe). Thoát chương trình.")
            break
        except Exception as e:
            fail += 1
            log("  ✗ LỖI ở trạm %s: %s" % (ma_tram, e))
            continue

    log("HOÀN TẤT. Thành công: %d — Lỗi: %d" % (ok, fail))


# ---------------------------------------------------------------------------
def build_parser():
    p = argparse.ArgumentParser(
        description="Tool RPA tự động hoá Adobe Firefly xử lý ảnh hàng loạt.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("init-config", help="Tạo file config.json mẫu")
    sub.add_parser("make-sample", help="Tạo data.xlsx mẫu")
    sub.add_parser("calibrate", help="Lấy toạ độ chuột các nút trên màn hình")

    runp = sub.add_parser("run", help="Chạy batch")
    runp.add_argument("--dry-run", action="store_true",
                      help="Chạy thử: in các bước, không click chuột thật")
    return p


def main():
    parser = build_parser()
    args = parser.parse_args()
    handlers = {
        "init-config": cmd_init_config,
        "make-sample": cmd_make_sample,
        "calibrate": cmd_calibrate,
        "run": cmd_run,
    }
    handlers[args.command](args)


if __name__ == "__main__":
    main()
