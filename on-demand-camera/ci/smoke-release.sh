#!/usr/bin/env bash
# Chạy thử APK release trên emulator: cài, mở màn hình chính, chờ 8 giây, kiểm tra app còn sống và không crash.
# (Test tự động chạy trên bản debug; bước này bắt lỗi chỉ xảy ra ở bản release.)
set -euo pipefail

smoke() {
  local pkg="$1" apk="$2"
  adb install -r "$apk"
  adb logcat -c
  adb shell am start -W -n "$pkg/.MainActivity"
  sleep 8
  local crashes
  crashes="$(adb logcat -d -s AndroidRuntime:E || true)"
  if grep -q "FATAL EXCEPTION" <<<"$crashes" || ! adb shell pidof "$pkg" >/dev/null; then
    echo "::error::$pkg (release) bị dừng sau khi mở"
    echo "$crashes" | tail -60
    exit 1
  fi
  echo "OK: $pkg (release) chạy ổn sau 8 giây"
  adb shell am force-stop "$pkg"
}

smoke vn.cnldg.oncam.node camera-node/build/outputs/apk/release/camera-node-release.apk
smoke vn.cnldg.oncam.viewer viewer/build/outputs/apk/release/viewer-release.apk
