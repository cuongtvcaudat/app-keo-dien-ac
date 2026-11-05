# Khoá ký debug dùng chung

`debug.keystore` chỉ dùng để ký **APK debug** (build từ GitHub Actions hoặc máy cá nhân), để các bản build
khác nhau cài đè lên nhau được mà không phải gỡ app (gỡ app là mất cấu hình).

| Thuộc tính | Giá trị |
|---|---|
| Alias | `androiddebugkey` |
| Mật khẩu kho / khoá | `android` (giá trị mặc định công khai của Android) |
| SHA-256 | `EF:FD:61:22:41:AA:89:73:DD:31:D5:A0:A0:D3:ED:E8:1E:AE:27:3A:5B:52:F9:0B:CF:4D:3E:DC:4B:0C:71:D8` |

**Không phải bí mật và không bảo vệ gì.** Bản release (Phase 6) ký bằng khoá riêng, lưu trong GitHub Secrets,
không bao giờ commit vào repo.
