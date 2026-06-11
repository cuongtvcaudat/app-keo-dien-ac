# QUYẾT ĐỊNH KỸ THUẬT

## Vòng 1 (đã thực hiện)
- **PDF**: dùng jsPDF vẽ sơ đồ bằng shapes thay vì chụp Leaflet → tránh CORS tile, offline được, in đẹp hơn
- **Backend**: GAS + Google Sheets (miễn phí vĩnh viễn, data xem được trong Sheets)
- **Hosting**: GitHub Pages (HTTPS, auto-deploy khi push)
- **Offline map**: Cache API qua SW, nút "Tải bản đồ khu vực" pre-cache 3km radius zoom 14–17
- **STT cột**: số nguyên liên tục, tính lại sau mỗi thao tác (đơn giản, dễ sửa)
- **Sync**: append-only log, không ghi đè, hàng đợi tự đẩy khi online
- **Không đăng nhập**: tên đội nhập 1 lần lưu localStorage, gắn vào mọi bản ghi

## Vòng 2 (CHƯA LÀM)
- Nhật ký thi công hàng ngày
- Upload ảnh thi công lên Google Drive (watermark GPS + thời gian)
- Checklist nghiệm thu
- So sánh dự toán vs thực tế
- Telegram bot báo tiến độ tự động
- Chèn STT kiểu 4a/4b (hiện tại tính lại từ đầu)
- Server-side permission check

## Android (Giai đoạn 2)
- Dùng **Capacitor** để wrap PWA thành APK
- Thay GPS bằng Capacitor Geolocation plugin
- Thay Camera bằng Capacitor Camera plugin
- Build APK → phân phối qua Zalo/Telegram (không cần Play Store)
