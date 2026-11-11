#!/usr/bin/env python3
"""Maintain the development ADB viewer link; never control the phone camera."""

import argparse
from contextlib import contextmanager
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


UNIT = "flip-camera-viewer-link.service"
SCRIPT = Path(__file__).resolve()
INSTALLED_HELPER = Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'flip-camera/viewer-link.py'
KNOWN_HELPERS = {SCRIPT, INSTALLED_HELPER.resolve()}
LOCAL = "tcp:18443"
REMOTE = "tcp:8443"


class LinkError(RuntimeError):
    pass


def run(*args):
    return subprocess.run(args, text=True, capture_output=True, timeout=10)


def adb(*args):
    return run("adb", *args)


def forward_owner():
    result = adb("forward", "--list")
    if result.returncode:
        raise LinkError("Chưa đọc được danh sách chuyển tiếp ADB.")
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[1] == LOCAL:
            return fields[0], fields[2]
    return None


def is_tailscale_target(serial):
    try:
        host, port = serial.rsplit(":", 1)
        return ipaddress.ip_address(host) in ipaddress.ip_network("100.64.0.0/10") and port == "5555"
    except ValueError:
        return False


def is_flip(serial):
    result = adb("-s", serial, "shell", "getprop", "ro.product.model")
    return result.returncode == 0 and result.stdout.strip().startswith("SM-F711")


def ensure_link(serial, reconnect=False):
    state = adb("-s", serial, "get-state")
    if state.returncode or state.stdout.strip() != "device":
        # Only reconnect the already selected private target. Never scan peers,
        # restart ADB, enable TCP/IP, or accept a new device authorization.
        if reconnect and is_tailscale_target(serial):
            adb("connect", serial)
        return "waiting"
    if not is_flip(serial):
        return "wrong_device"
    owner = forward_owner()
    if owner is not None:
        return "ready" if owner == (serial, REMOTE) else "conflict"
    result = adb("-s", serial, "forward", "--no-rebind", LOCAL, REMOTE)
    if result.returncode:
        return "conflict" if forward_owner() is not None else "forward_failed"
    return "ready"


MESSAGES = {
    "ready": "Cổng xem HTTPS đã sẵn sàng.",
    "waiting": "Đang chờ kết nối ADB đã cấp quyền tới Flip3.",
    "conflict": "Cổng 18443 thuộc đường chuyển tiếp khác; giữ nguyên, không chiếm lại.",
    "wrong_device": "Thiết bị hiện tại không phải Flip3; không tạo đường chuyển tiếp.",
    "forward_failed": "ADB đã kết nối nhưng chưa mở được cổng xem 18443.",
    "error": "Chưa kiểm tra được ADB; sẽ thử lại.",
}


def watch(serial):
    previous = None
    while True:
        try:
            state = ensure_link(serial, reconnect=True)
        except (OSError, subprocess.TimeoutExpired, LinkError):
            state = "error"
        if state != previous:
            print(MESSAGES[state], flush=True)
            previous = state
        time.sleep(3)


def service_target():
    result = run("systemctl", "--user", "show", "--property=LoadState",
                 "--property=ActiveState", "--property=SubState", UNIT)
    state = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    if state.get("LoadState") == "not-found" or (result.returncode and not state):
        return None
    if state.get("LoadState") != "loaded" or not state.get("ActiveState") or not state.get("SubState"):
        raise LinkError("Chưa xác định được vòng đời dịch vụ giữ kết nối.")
    # ExecStart remains available during activating/auto-restart when MainPID=0.
    # Read its typed argv from D-Bus instead of splitting systemctl's display text.
    try:
        unit = run("busctl", "--user", "--json=short", "call", "org.freedesktop.systemd1",
                   "/org/freedesktop/systemd1", "org.freedesktop.systemd1.Manager", "GetUnit", "s", UNIT)
        if unit.returncode:
            raise ValueError("GetUnit failed")
        unit_path = json.loads(unit.stdout)["data"][0]
        config = run("busctl", "--user", "--json=short", "get-property", "org.freedesktop.systemd1",
                     unit_path, "org.freedesktop.systemd1.Service", "ExecStart")
        if config.returncode:
            raise ValueError("ExecStart failed")
        entries = json.loads(config.stdout)["data"]
        if len(entries) != 1:
            raise ValueError("Ambiguous ExecStart")
        args = entries[0][1]
    except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
        raise LinkError("Không đọc được cấu hình dịch vụ; giữ nguyên kết nối hiện tại.") from error
    if len(args) != 4 or Path(args[1]).resolve() not in KNOWN_HELPERS or args[2] != "watch":
        raise LinkError("Tên dịch vụ giữ kết nối đang được chương trình khác sử dụng.")
    return args[3]


@contextmanager
def management_lock():
    directory = Path(os.environ.get("XDG_RUNTIME_DIR") or tempfile.gettempdir())
    descriptor = os.open(directory / f"flip-viewer-link-{os.getuid()}.lock",
                         os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w") as lock:
        if os.fstat(lock.fileno()).st_uid != os.getuid():
            raise LinkError("Không sử dụng khóa kết nối thuộc tài khoản khác.")
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def start(serial):
    with management_lock():
        start_locked(serial)


def start_locked(serial):
    # Starting is also useful without a systemd user session: the one-time link
    # remains available, with an explicit message that recovery is not running.
    try:
        target = service_target()
    except FileNotFoundError:
        target = None
    if target is not None and target != serial:
        raise LinkError("Đang giữ kết nối tới Flip khác. Dùng --stop-viewer-link trước khi đổi thiết bị.")
    state = ensure_link(serial)
    if state != "ready":
        raise LinkError(MESSAGES[state])
    if target is not None:
        if run("systemctl", "--user", "start", UNIT).returncode:
            raise LinkError("Chưa khởi động được dịch vụ giữ kết nối hiện có.")
        print("Tự khôi phục cổng xem đang chạy.")
        return
    try:
        result = run(
            "systemd-run", "--user", "--quiet", "--collect", f"--unit={UNIT}",
            "--service-type=exec", "--property=Restart=on-failure",
            "--property=RestartSec=3", "--property=TimeoutStopSec=5",
            sys.executable, str(SCRIPT), "watch", serial,
        )
        if result.returncode and service_target() != serial:
            raise LinkError("Không khởi động được dịch vụ giữ kết nối.")
    except (FileNotFoundError, LinkError):
        print("Đã mở cổng xem. Chưa có tự phục hồi; chạy lại --live nếu ADB mất kết nối.", file=sys.stderr)
        return
    print("Đã bật tự khôi phục cổng xem. Dừng bằng: bash run.sh --stop-viewer-link")


def stop(serial=None):
    with management_lock():
        stop_locked(serial)


def stop_locked(serial=None):
    try:
        target = service_target()
    except FileNotFoundError:
        target = None
    if target is not None:
        if serial is not None and serial != target:
            raise LinkError("Dịch vụ đang giữ thiết bị khác; không dừng nhầm kết nối.")
        result = run("systemctl", "--user", "stop", UNIT)
        if result.returncode:
            raise LinkError("Chưa dừng được dịch vụ giữ kết nối.")
        serial = target
    owner = forward_owner()
    if serial is None and owner is not None:
        print("Không có dịch vụ giữ kết nối. Giữ nguyên cổng hiện tại; muốn đóng, chỉ định serial sau --stop-viewer-link.")
        return
    if serial is not None and owner == (serial, REMOTE):
        result = adb("-s", serial, "forward", "--remove", LOCAL)
        if result.returncode:
            raise LinkError("Đã dừng tự phục hồi nhưng chưa đóng được cổng xem.")
    print("Đã dừng giữ kết nối xem; không thay đổi camera hoặc phiên ghi trên Flip.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "watch", "stop"))
    parser.add_argument("serial", nargs="?")
    args = parser.parse_args()
    if args.action != "stop" and not args.serial:
        parser.error("start/watch cần serial ADB đã được chọn")
    try:
        {"start": start, "watch": watch, "stop": stop}[args.action](args.serial)
    except (LinkError, OSError, subprocess.TimeoutExpired) as error:
        print(f"Không thiết lập được kết nối xem: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
