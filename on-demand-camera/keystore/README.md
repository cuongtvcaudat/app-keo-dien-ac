# Khoá ký dùng chung

`debug.keystore` ký **cả APK debug lẫn APK release** (build từ GitHub Actions hoặc máy cá nhân), để các bản
build khác nhau cài đè lên nhau được mà không phải gỡ app (gỡ app là mất cấu hình và token).

| Thuộc tính | Giá trị |
|---|---|
| Alias | `androiddebugkey` |
| Mật khẩu kho / khoá | `android` (giá trị mặc định công khai của Android) |
| SHA-256 | `EF:FD:61:22:41:AA:89:73:DD:31:D5:A0:A0:D3:ED:E8:1E:AE:27:3A:5B:52:F9:0B:CF:4D:3E:DC:4B:0C:71:D8` |
| Hạn dùng | đến 03/2054 |

**Không phải bí mật và không bảo vệ gì.** Chủ dự án đã chọn cách này ở Phase 6 (phương án B) vì app chỉ cài
tay cho vài máy trong nhà, không đưa lên CH Play.

Hệ quả cần biết:

- Ai có repo cũng ký được một APK khác mang cùng chữ ký → Android vẫn cho cài đè. **Chỉ cài APK tải từ trang
  Release `oncam-latest` của repo này.** CI kiểm tra vân tay SHA-256 ở trên trước khi đăng APK.
- Muốn chuyển sang khoá riêng (khi phát hành rộng): tạo khoá mới, lưu vào GitHub Secrets, đổi `signingConfig`
  của `release`. Đổi khoá thì phải **gỡ app cũ rồi cài lại** (mất cấu hình) — làm một lần duy nhất.
