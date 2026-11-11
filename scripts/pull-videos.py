#!/usr/bin/env python3
"""Tải video đã ghi từ Flip3 về máy tính qua ADB (Tailscale hoặc USB).

Chỉ đọc: không xóa, không sửa gì trên điện thoại. Cần bản debug của app
(com.phamdu.flipcamera.debug) vì dùng `adb run-as`.

Ví dụ:
  python3 scripts/pull-videos.py --list --last 60
  python3 scripts/pull-videos.py --last 30 --out ~/Videos/flip
  python3 scripts/pull-videos.py --from "2026-11-06 08:00" --to "2026-11-06 09:00"
Giờ nhập theo UTC+7. File lưu tên YYYY-MM-DD_HH-MM-SS.mp4 (giờ bắt đầu, UTC+7).
"""
import argparse
import os
import sqlite3
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone

PKG = "com.phamdu.flipcamera.debug"
TZ = timezone(timedelta(hours=7))


def adb(serial, *args, out=None):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, stdout=out, stderr=subprocess.PIPE, check=True)


def pick_serial(given):
    if given:
        return given
    env = os.environ.get("FLIP_CAMERA_SERIAL")
    if env:
        return env
    lines = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    devs = [l.split()[0] for l in lines if l.strip().endswith("device")]
    tail = [d for d in devs if d.startswith("100.")]
    chosen = tail or devs
    if len(chosen) != 1:
        sys.exit(f"Cần chỉ định --serial (thấy: {chosen or 'không có thiết bị'}).")
    return chosen[0]


def parse_local(text):
    return int(datetime.strptime(text, "%Y-%m-%d %H:%M").replace(tzinfo=TZ).timestamp() * 1000)


def load_rows(serial, lo, hi):
    with tempfile.TemporaryDirectory() as tmp:
        for name in ("flip_camera.db", "flip_camera.db-wal", "flip_camera.db-shm"):
            with open(os.path.join(tmp, name), "wb") as fh:
                try:
                    adb(serial, "exec-out", "run-as", PKG, "cat", f"databases/{name}", out=fh)
                except subprocess.CalledProcessError:
                    if name == "flip_camera.db":
                        raise
        con = sqlite3.connect(os.path.join(tmp, "flip_camera.db"))
        rows = con.execute(
            "select id, start_utc, duration, bytes, internal_name, width, height, audio "
            "from segments where status='READY' and start_utc>=? and start_utc<? order by start_utc",
            (lo, hi),
        ).fetchall()
        con.close()
    return rows


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--serial", help="serial ADB, mặc định: thiết bị Tailscale duy nhất")
    p.add_argument("--last", type=int, metavar="PHÚT", help="lấy N phút gần nhất")
    p.add_argument("--from", dest="start", help='bắt đầu "YYYY-MM-DD HH:MM" (UTC+7)')
    p.add_argument("--to", dest="end", help='kết thúc "YYYY-MM-DD HH:MM" (UTC+7)')
    p.add_argument("--out", default="flip-videos", help="thư mục lưu (mặc định ./flip-videos)")
    p.add_argument("--list", action="store_true", help="chỉ liệt kê, không tải")
    a = p.parse_args()

    now = int(datetime.now(tz=TZ).timestamp() * 1000)
    if a.last:
        lo, hi = now - a.last * 60_000, now + 60_000
    else:
        if not a.start:
            sys.exit("Cần --last N hoặc --from [--to].")
        lo = parse_local(a.start)
        hi = parse_local(a.end) if a.end else now + 60_000

    serial = pick_serial(a.serial)
    rows = load_rows(serial, lo, hi)
    total = sum(r[3] for r in rows)
    print(f"{len(rows)} đoạn, {total / 1e6:.1f} MB")
    os.makedirs(a.out, exist_ok=True) if not a.list else None
    for sid, start, dur, size, internal, w, h, audio in rows:
        label = datetime.fromtimestamp(start / 1000, TZ).strftime("%Y-%m-%d_%H-%M-%S")
        dest = os.path.join(a.out, f"{label}.mp4")
        info = f"{label}  {dur / 1000:5.1f}s  {size / 1e6:6.1f} MB  {w}x{h}{'  có tiếng' if audio else ''}"
        if a.list:
            print(info)
            continue
        if os.path.exists(dest) and os.path.getsize(dest) == size:
            print("bỏ qua (đã có)  " + info)
            continue
        part = dest + ".part"
        with open(part, "wb") as fh:
            adb(serial, "exec-out", "run-as", PKG, "cat", f"files/segments/{internal}", out=fh)
        if os.path.getsize(part) != size:
            os.remove(part)
            sys.exit(f"Sai kích thước: {internal} (cần {size})")
        os.replace(part, dest)
        print("đã tải          " + info)


if __name__ == "__main__":
    main()
