#!/usr/bin/env bash
# Chạy thử APK release trên emulator: cài, mở màn hình chọn vai trò, chờ 8 giây, kiểm tra app còn sống không crash.
# (Test tự động chạy trên bản debug; bước này bắt lỗi chỉ xảy ra ở bản release.)
set -euo pipefail

pkg="vn.cnldg.oncam"
apk="app/build/outputs/apk/release/app-release.apk"
activity="vn.cnldg.oncam.app.RootActivity"

adb install -r "$apk"
adb logcat -c
# Mở màn hình chọn vai trò (chưa chọn vai trò → RootActivity hiện lựa chọn và ở lại, không crash).
adb shell am start -W -n "$pkg/$activity"
sleep 8
crashes="$(adb logcat -d -s AndroidRuntime:E || true)"
if grep -q "FATAL EXCEPTION" <<<"$crashes" || ! adb shell pidof "$pkg" >/dev/null; then
  echo "::error::$pkg (release) bị dừng sau khi mở"
  echo "$crashes" | tail -60
  exit 1
fi
echo "OK: $pkg (release) chạy ổn sau 8 giây"
adb shell am force-stop "$pkg"
