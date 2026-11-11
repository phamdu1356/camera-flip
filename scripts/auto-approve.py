#!/usr/bin/env python3
"""Tự động phát hiện và bấm duyệt các hộp thoại xin quyền, xác nhận cài đặt APK trên Android.

Hoạt động:
- Quét các nút xác nhận bảo mật phổ biến trên Android (OriginOS/Vivo, HyperOS/Xiaomi, OneUI/Samsung):
  "Cài đặt", "Tiếp tục cài đặt", "Cho phép", "Luôn cho phép", "Xác nhận", "Install", "Allow", "Continue".
- Tự động bấm nút bằng uiautomator2 nếu có, hoặc fallback qua ADB uiautomator dump thuần túy.

Cách dùng:
  python3 scripts/auto-approve.py --device 10ADB93BM4000SH --timeout 30
  python3 scripts/auto-approve.py --once --device 10ADB93BM4000SH
"""

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# Ưu tiên load uiautomator2 từ auto-approve-env nếu có
U2_SITE = "/home/pham-du/.local/share/auto-approve-env/lib/python3.14/site-packages"
if os.path.exists(U2_SITE) and U2_SITE not in sys.path:
    sys.path.insert(0, U2_SITE)

KEYWORDS = [
    "Cài đặt",
    "Tiếp tục cài đặt",
    "Cho phép",
    "Luôn cho phép",
    "Cho phép mọi lúc",
    "Chỉ cho phép khi dùng ứng dụng",
    "Xác nhận",
    "Đồng ý",
    "Cài đặt qua USB",
    "Install",
    "Install via USB",
    "Continue install",
    "Allow",
    "Confirm",
    "OK",
]


def adb_cmd(serial, *args):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def try_u2_approve(serial):
    try:
        import uiautomator2 as u2
        d = u2.connect(serial) if serial else u2.connect()
        for kw in KEYWORDS:
            el = d(text=kw)
            if el.exists:
                print(f"[u2] Phát hiện phần tử: '{kw}' -> Đang bấm...")
                el.click()
                return True
        for kw in KEYWORDS:
            el = d(textContains=kw)
            if el.exists:
                print(f"[u2] Phát hiện phần tử chứa: '{kw}' -> Đang bấm...")
                el.click()
                return True
    except Exception as e:
        # Fallback qua raw ADB
        pass
    return False


def get_ui_dump(serial):
    # Dùng uiautomator dump của Android
    res = adb_cmd(serial, "shell", "uiautomator", "dump", "/data/local/tmp/uidump.xml")
    if res.returncode != 0:
        # Thử lưu vào sdcard nếu tmp không ghi được
        res = adb_cmd(serial, "shell", "uiautomator", "dump", "/sdcard/uidump.xml")
        path = "/sdcard/uidump.xml"
    else:
        path = "/data/local/tmp/uidump.xml"

    cat_res = adb_cmd(serial, "shell", "cat", path)
    return cat_res.stdout


def parse_bounds(bounds_str):
    # bounds="[x1,y1][x2,y2]"
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str)
    if not match:
        return None
    x1, y1, x2, y2 = map(int, match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def raw_adb_approve(serial):
    xml_data = get_ui_dump(serial)
    if not xml_data or "<hierarchy" not in xml_data:
        return False

    try:
        root = ET.fromstring(xml_data)
    except Exception:
        return False

    for node in root.iter("node"):
        text = node.attrib.get("text", "").strip()
        desc = node.attrib.get("content-desc", "").strip()
        bounds = node.attrib.get("bounds", "")

        matched = False
        target_label = ""
        for kw in KEYWORDS:
            if kw.lower() == text.lower() or kw.lower() == desc.lower():
                matched = True
                target_label = text or desc
                break

        if not matched:
            for kw in KEYWORDS:
                if (text and kw.lower() in text.lower()) or (desc and kw.lower() in desc.lower()):
                    matched = True
                    target_label = text or desc
                    break

        if matched and bounds:
            coords = parse_bounds(bounds)
            if coords:
                cx, cy = coords
                print(f"[ADB] Tìm thấy nút: '{target_label}' tại ({cx}, {cy}) -> Đang chạm...")
                tap_res = adb_cmd(serial, "shell", "input", "tap", str(cx), str(cy))
                return tap_res.returncode == 0

    return False


def main():
    parser = argparse.ArgumentParser(description="Tự động bấm duyệt quyền và cài đặt trên Android")
    parser.add_argument("--device", "-s", help="Mã serial thiết bị ADB (mặc định lấy thiết bị đầu tiên)")
    parser.add_argument("--timeout", type=int, default=15, help="Thời gian theo dõi (giây), mặc định 15s")
    parser.add_argument("--interval", type=float, default=1.0, help="Khoảng thời gian quét lại (giây)")
    parser.add_argument("--once", action="store_true", help="Chỉ quét 1 lần rồi thoát ngay")
    args = parser.parse_args()

    serial = args.device
    if not serial:
        dev_res = subprocess.run(["adb", "devices"], capture_output=True, text=True)
        lines = [l.split()[0] for l in dev_res.stdout.strip().splitlines()[1:] if "\tdevice" in l]
        if lines:
            serial = lines[0]
            print(f"Sử dụng thiết bị mặc định: {serial}")
        else:
            print("Lỗi: Không tìm thấy thiết bị ADB nào kết nối.")
            sys.exit(1)

    print(f"Bắt đầu lắng nghe hộp thoại xác nhận trên thiết bị {serial}...")

    start_time = time.time()
    approved_count = 0

    while True:
        # Ưu tiên uiautomator2 nếu có
        handled = try_u2_approve(serial)
        if not handled:
            handled = raw_adb_approve(serial)

        if handled:
            approved_count += 1
            print(f"-> Đã duyệt thành công (lần {approved_count})!")
            time.sleep(1.0)  # Chờ hiệu ứng chuyển cảnh

        if args.once:
            break

        if time.time() - start_time >= args.timeout:
            print(f"Hết thời gian theo dõi ({args.timeout}s).")
            break

        time.sleep(args.interval)

    if approved_count > 0:
        print(f"Hoàn tất: Đã duyệt {approved_count} hộp thoại.")
    else:
        print("Không có hộp thoại nào xuất hiện trong thời gian chờ.")


if __name__ == "__main__":
    main()
