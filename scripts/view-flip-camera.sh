#!/usr/bin/env bash
# View the native app or direct camera over an authorized private ADB connection.
set -euo pipefail
if [[ $# -gt 1 ]]; then
  echo 'Chỉ nhận một serial ADB. Xem: bash run.sh --help' >&2
  exit 1
fi
flip_mode="${FLIP_VIEWER_MODE:-auto}"
case "$flip_mode" in
  auto|direct|live|history|mirror) ;;
  *) echo 'Chế độ xem không hợp lệ. Xem: bash run.sh --help' >&2; exit 1 ;;
esac
for dependency in adb python3; do
  command -v "$dependency" >/dev/null || { echo "Thiếu lệnh: $dependency" >&2; exit 1; }
done
flip_serial="${1:-${FLIP_CAMERA_SERIAL:-}}"
if [[ -z "$flip_serial" ]]; then
  candidates=()
  while read -r serial state rest; do
    [[ "$state" == device && "$rest" == *model:SM_F711B* ]] || continue
    if python3 - "$serial" <<'PY'
import ipaddress, sys
try:
    host, port = sys.argv[1].rsplit(':', 1)
    valid = ipaddress.ip_address(host) in ipaddress.ip_network('100.64.0.0/10') and port.isdigit()
except ValueError:
    valid = False
sys.exit(0 if valid else 1)
PY
    then candidates+=("$serial"); fi
  done < <(adb devices -l)
  if [[ ${#candidates[@]} != 1 ]]; then
    echo 'Cần đúng một Flip3 đã kết nối ADB qua Tailscale.' >&2
    echo 'Kết nối: adb connect <IP-Tailscale-Flip>:5555 rồi chạy lại.' >&2
    echo 'Hoặc chỉ định: bash run.sh --flip <serial-ADB>' >&2
    exit 1
  fi
  flip_serial="${candidates[0]}"
fi
[[ "$(adb -s "$flip_serial" get-state)" == device ]] || exit 1
flip_model="$(adb -s "$flip_serial" shell getprop ro.product.model | tr -d '\r')"
[[ "$flip_model" == SM-F711* ]] || { echo "Thiết bị không phải Flip3: $flip_model" >&2; exit 1; }
if [[ "$flip_mode" == mirror ]]; then
  command -v scrcpy >/dev/null || { echo 'Thiếu lệnh: scrcpy' >&2; exit 1; }
  echo 'Mở màn hình Flip3. Bạn có thể bật ghi hoặc duyệt ghép đôi trong app.'
  exec scrcpy --serial="$flip_serial" --hwdec=disabled --window-title='Điều khiển Flip3'
fi

# Inspect only active clients. Historical CONNECT/DISCONNECT log entries must
# never make us think the native app is still using the camera.
camera_owner="$(adb -s "$flip_serial" shell dumpsys media.camera | python3 -c '
import re, sys
match = re.search(r"Active Camera Clients:\s*\[(.*?)\]", sys.stdin.read(), re.S)
if not match:
    print("unknown")
elif re.search(r"Client Package Name:\s*com\.phamdu\.flipcamera(?:\.debug)?\s*[,)]", match[1]):
    print("native")
elif match[1].strip():
    print("other")
else:
    print("idle")
')"
if [[ "$flip_mode" == auto && "$camera_owner" == native ]]; then
  flip_mode=live
  echo 'App Flip Camera đang giữ camera; mở luồng của app để xem cùng lúc ghi hình.'
fi
if [[ "$flip_mode" == live || "$flip_mode" == history ]]; then
  if [[ "$camera_owner" == other ]]; then
    echo 'Camera đang được một ứng dụng khác sử dụng. Đóng cửa sổ camera trực tiếp trước khi kết nối camera trong app native.' >&2
    echo 'Dùng bash run.sh --phone để thao tác trên Flip3; cửa sổ phản chiếu màn hình không tranh camera.' >&2
  fi
  # Existing HTTPS listener binds phone loopback; ADB carries it over the
  # authorized private connection. TLS and native pairing remain mandatory.
  python3 "$(dirname -- "${BASH_SOURCE[0]}")/viewer-link.py" start "$flip_serial"
  viewer_url='https://localhost:18443'
  if [[ "$flip_mode" == live ]]; then viewer_url+='/?live=1'; fi
  echo "Viewer trên Flip3: $viewer_url"
  echo 'Trên Flip: Kết nối camera → bật viewer HTTPS → tạo mã ghép đôi và duyệt máy tính.'
  echo 'Xác minh fingerprint chứng chỉ với ứng dụng trên Flip trước khi ghép đôi.'
  echo 'Đóng đường chuyển tiếp và tự phục hồi: bash run.sh --stop-viewer-link'
  if command -v xdg-open >/dev/null; then
    xdg-open "$viewer_url" >/dev/null 2>&1 || true
  fi
  exit 0
fi
if [[ "$camera_owner" != idle ]]; then
  if [[ "$camera_owner" == native ]]; then
    echo 'App Android đang giữ camera. Dùng bash run.sh --live để xem cùng lúc ghi, hoặc --phone để điều khiển app.' >&2
    echo 'Chỉ dùng --flip sau khi bạn chủ động chọn Dừng camera trên Flip3.' >&2
  elif [[ "$camera_owner" == unknown ]]; then
    echo 'Chưa xác định được camera có rảnh hay không. Kiểm tra kết nối ADB hoặc dùng bash run.sh --phone.' >&2
  else
    echo 'Camera Flip3 đang được ứng dụng khác sử dụng. Đóng phiên đó trước khi mở thêm camera trực tiếp.' >&2
  fi
  exit 1
fi
command -v scrcpy >/dev/null || { echo 'Thiếu lệnh: scrcpy' >&2; exit 1; }
# Flip3 exposes its ultrawide rear lens as camera 2 (1.74 mm), separately
# from the main rear camera 0 (4.25 mm). Digital zoom cannot widen camera 0.
flip_camera_id="${FLIP_CAMERA_ID:-2}"
[[ "$flip_camera_id" == 0 || "$flip_camera_id" == 2 ]] || {
  echo 'FLIP_CAMERA_ID phải là 2 (siêu rộng) hoặc 0 (camera chính).' >&2
  exit 1
}
echo "Đang mở camera sau ID $flip_camera_id của Flip3 ($flip_serial; 2 = góc siêu rộng). Đóng cửa sổ để dừng xem."
exec scrcpy --serial="$flip_serial" --video-source=camera --camera-id="$flip_camera_id" \
  --camera-size=1280x720 --camera-fps=15 --video-bit-rate=2M \
  --no-audio --no-control --hwdec=disabled --window-title='Camera an ninh — Flip3'
