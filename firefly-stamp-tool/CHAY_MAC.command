#!/bin/bash
# Cong cu dan stamp anh - chay tren macOS / Linux. Nhay dup de chay.
cd "$(dirname "$0")" || exit 1

if ! command -v python3 >/dev/null 2>&1; then
  echo "[X] Chua cai Python3. Cai qua https://www.python.org/downloads/ roi chay lai."
  read -r -p "Enter de thoat..."
  exit 1
fi

echo "> Kiem tra thu vien..."
python3 -m pip install --quiet --disable-pip-version-check Pillow openpyxl requests

while true; do
  echo
  echo "============================================="
  echo "  CONG CU DAN STAMP ANH HIEN TRUONG"
  echo "============================================="
  echo "  1. Chay hang loat (tu file Excel)"
  echo "  2. Go tay 1 anh"
  echo "  3. Tao / lam moi file Excel mau"
  echo "  0. Thoat"
  echo "============================================="
  read -r -p "Chon (0-3): " sel
  case "$sel" in
    1) python3 src/main.py ;;
    2) python3 src/main.py --manual ;;
    3) python3 src/make_excel.py ;;
    0) exit 0 ;;
  esac
done
