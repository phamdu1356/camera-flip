#!/usr/bin/env bash
set -euo pipefail

QUALITY="${1:-sd}"
SERIAL="${2:-100.115.117.61:5555}"

if [[ "$QUALITY" != "sd" && "$QUALITY" != "hd" ]]; then
    echo "Cách dùng: bash scripts/set-camera-quality.sh [sd|hd] [serial]" >&2
    echo "  sd: 480p siêu tiết kiệm (~9 MB/phút, giảm 75% dung lượng)" >&2
    echo "  hd: 720p độ nét cao (~37 MB/phút)" >&2
    exit 1
fi

# Kiểm tra adb
if ! command -v adb >/dev/null 2>&1; then
    echo "Lỗi: Không tìm thấy lệnh adb." >&2
    exit 1
fi

# Kiểm tra thiết bị
if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    # Thử lấy thiết bị adb đầu tiên nếu serial mặc định không có
    FIRST_DEVICE=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
    if [[ -n "$FIRST_DEVICE" ]]; then
        SERIAL="$FIRST_DEVICE"
    else
        echo "Lỗi: Không tìm thấy thiết bị Android nào kết nối qua ADB." >&2
        exit 1
    fi
fi

echo "Đang đổi chất lượng video sang: ${QUALITY^^} trên thiết bị ($SERIAL)..."

# Ghi trực tiếp vào SharedPreferences của ứng dụng
adb -s "$SERIAL" shell "run-as com.phamdu.flipcamera.debug sh -c 'printf \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?>\n<map>\n    <string name=\\\"video-quality\\\">$QUALITY</string>\n</map>\n\" > shared_prefs/camera-settings.xml'"

# Khởi động lại ứng dụng để áp dụng cấu hình mới
adb -s "$SERIAL" shell am force-stop com.phamdu.flipcamera.debug
adb -s "$SERIAL" shell am start -n com.phamdu.flipcamera.debug/com.phamdu.flipcamera.MainActivity >/dev/null

echo "✅ Đã chuyển chất lượng thành công sang: ${QUALITY^^}"
if [[ "$QUALITY" == "sd" ]]; then
    echo "📊 Chế độ SD (480p): Dung lượng chỉ còn ~9.3 MB/phút (~560 MB/giờ, ~13.4 GB/ngày)."
    echo "   Tiết kiệm 75% bộ nhớ, thẻ/bộ nhớ 77 GB có thể lưu được gần 6 ngày liên tục."
else
    echo "📊 Chế độ HD (720p): Dung lượng ~37.4 MB/phút (~2.2 GB/giờ, ~53.8 GB/ngày)."
fi
