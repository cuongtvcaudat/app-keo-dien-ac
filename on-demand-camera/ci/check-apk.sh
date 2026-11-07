#!/usr/bin/env bash
# Kiểm tra APK release trước khi đăng: ký đúng khoá dùng chung (keystore/README.md), không debuggable,
# đúng mã ứng dụng và phiên bản. Dùng: ci/check-apk.sh <file.apk> <applicationId> <versionName>
set -euo pipefail

apk="$1"
expected_pkg="$2"
expected_version="$3"
expected_cert="effd612241aa8973dd31d5a0a0d3ede81eae273a5b52f90bcf4d3edc4b0c71d8"

tools="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"

certs="$("$tools/apksigner" verify --print-certs "$apk")"
if ! grep -qi "SHA-256 digest: $expected_cert" <<<"$certs"; then
  echo "::error::$apk không ký bằng khoá dùng chung"
  echo "$certs"
  exit 1
fi

badging="$("$tools/aapt2" dump badging "$apk")"
if grep -q "application-debuggable" <<<"$badging"; then
  echo "::error::$apk đang bật debuggable"
  exit 1
fi
if ! grep -q "package: name='$expected_pkg' .*versionName='$expected_version'" <<<"$badging"; then
  echo "::error::$apk sai mã ứng dụng/phiên bản (cần $expected_pkg $expected_version)"
  grep "^package:" <<<"$badging"
  exit 1
fi

echo "OK: $apk — $expected_pkg $expected_version, ký đúng khoá, không debuggable, $(du -h "$apk" | cut -f1)"
