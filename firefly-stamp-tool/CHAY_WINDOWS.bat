@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Cong cu dan stamp anh - Firefly

where python >nul 2>nul
if errorlevel 1 (
  echo [X] Chua cai Python. Vao https://www.python.org/downloads/ tai va cai
  echo     (nho tich "Add Python to PATH"^), roi chay lai file nay.
  pause
  exit /b
)

echo ^> Kiem tra thu vien...
python -m pip install --quiet --disable-pip-version-check Pillow openpyxl requests

:menu
echo.
echo =============================================
echo   CONG CU DAN STAMP ANH HIEN TRUONG
echo =============================================
echo   1. Chay hang loat (tu file Excel)
echo   2. Go tay 1 anh
echo   3. Tao / lam moi file Excel mau
echo   0. Thoat
echo =============================================
set /p sel="Chon (0-3): "
if "%sel%"=="1" ( python src\main.py & goto menu )
if "%sel%"=="2" ( python src\main.py --manual & goto menu )
if "%sel%"=="3" ( python src\make_excel.py & goto menu )
if "%sel%"=="0" ( exit /b )
goto menu
