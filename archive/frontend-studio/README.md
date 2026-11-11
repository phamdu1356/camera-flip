# Flip Studio (đã lưu trữ)

Đây là bản giao diện Flip Studio độc lập, đã **retire ngày 2026-11-03**. Bản này dùng camera do trình duyệt cung cấp (`getUserMedia`) và lưu cục bộ bằng IndexedDB, không lấy hình từ Flip3.

- Không còn được hỗ trợ và không nằm trong luồng chạy chính (`bash run.sh` đã bỏ `--studio`).
- Giao diện duy nhất của dự án là `viewer/`, được phục vụ từ điện thoại Flip3 qua HTTPS và chỉ đọc.
- Thư mục này được giữ để tham khảo; không sửa đây như một phần của sản phẩm.

Chạy thử thủ công (chỉ loopback, không có backend):

```bash
python3 -m http.server 5173 --bind 127.0.0.1 --directory archive/frontend-studio
```

Sau đó mở http://localhost:5173 bằng trình duyệt. Test còn lại:

```bash
node --test archive/frontend-studio/tests/camera-access.test.mjs
```
