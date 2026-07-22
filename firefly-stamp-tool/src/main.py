#!/usr/bin/env python3
"""Cong cu dan stamp anh hien truong (Firefly + compositing).

Chay: python src/main.py            -> xu ly hang loat tu Excel
      python src/main.py --manual   -> go tay 1 anh
      python src/main.py --demo      -> tao anh demo tu du lieu mau
"""
import os
import sys
import json
import glob

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stamp import stamp_image  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IMG_EXT = (".jpg", ".jpeg", ".png", ".JPG", ".JPEG", ".PNG")


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def load_config():
    return load_json(os.path.join(HERE, "config.json"))


def load_template(name):
    return load_json(os.path.join(HERE, "templates", f"{name}.json"))


def build_firefly(config):
    ff = config.get("firefly", {})
    if config.get("old_stamp_cleanup", {}).get("mode") != "firefly":
        return None
    if not ff.get("enabled") or not ff.get("client_id") or not ff.get("client_secret"):
        print("  ! Firefly bat nhung thieu client_id/secret -> dung che do 'cover'.")
        return None
    from firefly import FireflyClient
    return FireflyClient(ff["client_id"], ff["client_secret"],
                         ff.get("fill_prompt", ""), ff.get("region_height_frac", 0.28))


def read_excel_rows(path):
    from openpyxl import load_workbook
    wb = load_workbook(path, data_only=True)
    ws = wb.active
    rows = list(ws.iter_rows(values_only=True))
    if not rows:
        return []
    headers = [str(h).strip() if h is not None else "" for h in rows[0]]
    out = []
    for r in rows[1:]:
        if all(c is None or str(c).strip() == "" for c in r):
            continue
        d = {}
        for h, v in zip(headers, r):
            if not h:
                continue
            d[h] = "" if v is None else (str(v).strip() if not isinstance(v, float)
                                         else _fmt_num(v))
        out.append(d)
    return out


def _fmt_num(v):
    # bo .0 thua o so nguyen
    return str(int(v)) if float(v).is_integer() else str(v)


def find_image_for(row, input_dir, match_by):
    key = str(row.get(match_by, "")).strip()
    if not key:
        return None
    # khop chinh xac ten file
    for ext in ("",) + IMG_EXT:
        p = os.path.join(input_dir, key + ext)
        if os.path.isfile(p):
            return p
    # khop theo phan dau ten (vd ma_tram nam trong ten file)
    for p in glob.glob(os.path.join(input_dir, "*")):
        name = os.path.splitext(os.path.basename(p))[0]
        if p.lower().endswith(IMG_EXT) and (name == key or key in name):
            return p
    return None


def run_batch(config):
    input_dir = os.path.join(HERE, config["input_dir"])
    output_dir = os.path.join(HERE, config["output_dir"])
    excel = os.path.join(HERE, config["excel_file"])
    match_by = config.get("match_by", "file")

    if not os.path.isfile(excel):
        print("! Chua co file Excel -> dang tao file mau...")
        import make_excel
        make_excel.main()
        print("\n>>> DA TAO file mau: " + excel)
        print(">>> Buoc tiep theo:")
        print("    1. Mo file Excel do, dien du lieu cac tram (moi dong 1 anh).")
        print("    2. Copy anh goc vao thu muc data/input/ (ten anh = cot 'file').")
        print("    3. Chay lai cong cu.")
        return
    rows = read_excel_rows(excel)
    if not rows:
        print("X File Excel khong co du lieu.")
        return

    firefly = build_firefly(config)
    ok, miss = 0, 0
    print(f"> Doc {len(rows)} dong du lieu tu Excel.")
    for i, row in enumerate(rows, 1):
        tpl_name = str(row.get("template", "")).strip() or config.get("default_template", "kieu_B")
        template = load_template(tpl_name)
        src = find_image_for(row, input_dir, match_by)
        if not src:
            print(f"  [{i}] BO QUA: khong tim thay anh cho '{row.get(match_by,'?')}'")
            miss += 1
            continue
        dst = os.path.join(output_dir, os.path.basename(src))
        stamp_image(src, dst, template, row, config, firefly)
        print(f"  [{i}] OK  {os.path.basename(src)}  ({tpl_name})")
        ok += 1
    print(f"\n=== XONG: {ok} anh -> {output_dir} | bo qua {miss} ===")


def run_manual(config):
    print("=== Go tay 1 anh ===")
    src = input("Duong dan anh goc: ").strip().strip('"')
    if not os.path.isfile(src):
        print("X Khong thay anh.")
        return
    tpl_name = input(f"Kieu stamp [{config.get('default_template','kieu_B')}]: ").strip() \
        or config.get("default_template", "kieu_B")
    template = load_template(tpl_name)
    fields = ["ngay", "lat", "lon", "huong", "dia_chi", "huyen", "phuong",
              "tinh", "altitude", "speed", "ma_tram", "ghi_chu", "index", "bearing_deg"]
    row = {}
    print("(Enter de bo trong)")
    for f in fields:
        row[f] = input(f"  {f}: ").strip()
    out_dir = os.path.join(HERE, config["output_dir"])
    dst = os.path.join(out_dir, "manual_" + os.path.basename(src))
    stamp_image(src, dst, template, row, config, build_firefly(config))
    print(f"OK -> {dst}")


def main():
    config = load_config()
    if "--manual" in sys.argv:
        run_manual(config)
    else:
        run_batch(config)


if __name__ == "__main__":
    main()
