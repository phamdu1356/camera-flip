# Flip Studio

Ứng dụng camera cá nhân cho Samsung Galaxy Z Flip3 với giao diện viewer trên Linux. Đây là bước đầu của dự án biến Samsung Galaxy Z Flip3 thành camera ghi hình, camera giám sát và webcam.

Ứng dụng Android native trong `android/` có preview, ghi MP4 theo phân đoạn, viewer HTTPS, ghép đôi, live/timeline/playback, sự kiện chuyển động và quản trị thư viện. Trên Flip3 thật đã xác minh lens siêu rộng 0.5×, MP4 720p/~15 fps, live cùng ghi và giải mã cả hai đoạn qua một lần xoay. Người dùng đã vào thư viện trên browser sau bước chứng chỉ/ghép đôi; đang xác nhận phát/tua thực tế. Bitrate đo khoảng 5 Mbps, chưa đạt target 2 Mbps; ghi lâu/gập máy vẫn chưa nghiệm thu. Xem [trạng thái chung](docs/PROJECT_STATUS.md), [tiêu chí nghiệm thu](docs/ACCEPTANCE.md) và [hướng dẫn Android](android/README_ANDROID.md). Agent bắt đầu từ [AGENTS.md](AGENTS.md).

## Xem camera Flip3 trên Linux

Chạy cửa sổ camera sau của Flip3:

```bash
cd /home/pham-du/code/camera
bash run.sh
```

Lệnh mặc định kiểm tra camera đang được ai sử dụng. Nếu app Flip Camera đang giữ camera, lệnh mở viewer HTTPS của app để xem cùng lúc ghi hình. Nếu camera đang rảnh, lệnh mở cửa sổ scrcpy lấy trực tiếp camera siêu rộng. Lệnh không tự dừng recorder hoặc bật ghi/micro. Cần ADB đã cấp quyền, Python 3 và scrcpy hỗ trợ camera source khi dùng chế độ trực tiếp.

Các chế độ riêng:

```bash
bash run.sh --live       # Live của app Android, dùng chung camera với recorder
bash run.sh --history    # Thư viện video trên Flip3
bash run.sh --stop-viewer-link # Đóng đường xem, Flip vẫn ghi
bash run.sh --phone      # Điều khiển màn hình Flip3, không mở camera thứ hai
bash run.sh --flip       # Camera scrcpy trực tiếp, chỉ khi camera đang rảnh
bash run.sh --help
```

Với viewer HTTPS, bật camera và viewer trong app trên Flip, đối chiếu fingerprint chứng chỉ rồi ghép đôi/duyệt máy tính. Có thể dùng `--phone` để thao tác trên điện thoại từ xa. Đường xem lại và điều khiển dùng kết nối ADB/Tailscale hiện có; video gốc nằm trên Flip3.

`--live`/`--history` tự giữ cổng xem bằng dịch vụ systemd user khi có sẵn: tạo lại forward bị mất và thử nối lại đúng Flip qua Tailscale. Dùng `--stop-viewer-link` để dừng helper và đóng đúng đường xem; không dừng ghi. TLS và ghép đôi vẫn bắt buộc. Xem [hướng dẫn kết nối](docs/RECORDING.md) cho giới hạn và cách kiểm tra dịch vụ.

Hai thiết bị cần online trong cùng tailnet. Nếu chưa có kết nối ADB:

```bash
adb connect <IP-Tailscale-Flip>:5555
bash run.sh
```

Điện thoại phải bật ADB TCP/IP đã được cấp quyền; sau khi reboot có thể cần USB để bật lại. Không mở cổng ADB ra Internet. Có thể chỉ định thiết bị bằng `bash run.sh --live <serial-ADB>` hoặc các chế độ khác. Cửa sổ scrcpy camera đã được thử ngắn sau khi đổi Wi-Fi; chưa kiểm tra 4G hoặc tự phục hồi sau reboot.

## Giao diện duy nhất: viewer

Giao diện chính là `viewer/` (HTML/CSS/JS đóng gói vào APK, phục vụ từ Flip3 qua HTTPS, chỉ đọc). Bản Flip Studio dùng camera trình duyệt và IndexedDB đã được retire ngày 2026-11-03 và lưu trữ tại `archive/frontend-studio/`; không còn chế độ `--studio` và không còn là một phần của sản phẩm. Xem `archive/frontend-studio/README.md` nếu cần tham khảo mã cũ.

## Cấu trúc code

```text
viewer/            Giao diện duy nhất (live, timeline, playback, events)
archive/
  frontend-studio/ Bản Studio đã retire, chỉ lưu trữ
android/           Ứng dụng Flip3 native
docs/              Kiến trúc, bảo mật, thiết kế, kiểm tra
scripts/           Công cụ kết nối ADB/scrcpy/viewer
```

## Quy trình Git

Các file cũ đã bị xóa từ trước vẫn được giữ nguyên trạng thái. Khi muốn lưu mốc viewer và Android hiện tại, xem `git status` rồi thêm các thư mục dưới đây:

```bash
git status
git add README.md run.sh archive/ viewer/ android/ docs/
git diff --staged --stat
git commit -m "Implement motion events and camera admin"
git push
```

## Thiết kế và nguồn tham khảo

Áp dụng hướng dẫn `frontend-design` từ https://github.com/anthropics/skills/tree/main/skills/frontend-design.

Font Manrope từ Google Fonts (SIL Open Font License, trong `viewer/assets/OFL-Manrope.txt`). Ảnh văn phòng mẫu từ Unsplash; nguồn gốc và token thiết kế được ghi trong `docs/DESIGN.md`.
