# SecureCam 24/7 - Hệ Thống Camera Giám Sát Đa Nền Tảng (PC & Mobile)

Hệ thống camera giám sát an ninh 24/7 chuyên nghiệp với giao diện chuẩn tương tự các ứng dụng thương mại như **Ezviz, Imou, Yoosee, UniFi Protect**.

## 🚀 Tính Năng Chính
- **Xem trực tiếp (Live View):** Độ trễ siêu thấp (< 300ms) qua giao thức MJPEG/WebSocket.
- **Xem đa camera:** Hỗ trợ xem 1 camera toàn màn hình hoặc lưới 4 camera (2x2).
- **Hỗ trợ camera Samsung Galaxy Z Flip 3 & Webcam:** Tự động kết nối camera vật lý hoặc chuyển sang chế độ mô phỏng an ninh.
- **Thanh tua thời gian 24/7 (Timeline Scrubber):** Tua theo thanh giờ 00:00 -> 24:00 để xem lại các đoạn video đã quay trong ngày (tự động phân đoạn 5 phút/chunk).
- **Tự động lưu trữ cuốn chiếu (Rolling Storage):** Tự động xóa video cũ khi vượt quá giới hạn bộ nhớ (mặc định 50GB).
- **Phát hiện chuyển động (Motion Detection):** Tự động đóng khung đối tượng chuyển động và chụp ảnh cảnh báo vào thư viện Sự Kiện.
- **Công cụ điều khiển CCTV:**
  - 📸 Chụp ảnh nhanh (Snapshot)
  - 🔦 Bật/Tắt đèn Flash trợ sáng ban đêm trên điện thoại
  - 🌙 Chế độ nhìn đêm hồng ngoại (Night Vision IR Filter)
  - 🔍 Digital Pan-Tilt-Zoom (1.0x - 4.0x)
  - 🚨 Còi hú báo động dọa trộm (Siren Alarm)
- **Đa nền tảng (PC & Mobile PWA):**
  - **Trên PC:** Xem trên trình duyệt Chrome, Edge, Firefox, Brave.
  - **Trên Điện thoại (iOS / Android):** Mở link bằng trình duyệt rồi bấm **"Thêm vào Màn hình chính" (Add to Home Screen)** để cài đặt thành ứng dụng độc lập toàn màn hình.

---

## 💻 Cách Khởi Chạy

```bash
cd /home/pham-du/code/camera
./scripts/start.sh
```

---

## 📱 Cách Truy Cập Từ Điện Thoại & Máy Tính Khác Cùng Mạng Wi-Fi

1. Đảm bảo điện thoại và máy tính kết nối chung mạng Wi-Fi.
2. Mở trình duyệt trên điện thoại (Safari trên iPhone, Chrome trên Android) và truy cập:
   ```
   http://172.17.162.27:8000
   ```
3. **Cài đặt như App thật:**
   - **Trên iPhone (Safari):** Bấm nút Chia sẻ (biểu tượng mũi tên hướng lên) -> Chọn **"Thêm vào MH chính" (Add to Home Screen)**.
   - **Trên Android (Chrome):** Bấm dấu 3 chấm góc trên -> Chọn **"Cài đặt ứng dụng"** hoặc **"Thêm vào Màn hình chính"**.
