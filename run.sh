#!/usr/bin/env bash
set -eu
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
case "${1:-}" in
  --help|-h)
    cat <<'HELP'
Cách dùng:
  bash run.sh                    Xem Flip3; dùng viewer nếu app đang giữ camera
  bash run.sh --live [serial]    Mở live HTTPS của app Android
  bash run.sh --history [serial] Mở thư viện video lưu trên Flip3
  bash run.sh --stop-viewer-link Dừng giữ kết nối xem; Flip vẫn ghi
  bash run.sh --phone [serial]   Điều khiển màn hình Flip3 qua scrcpy (khi màn hình hỏng)
  bash run.sh --sd [serial]      Chuyển sang chất lượng SD 480p siêu tiết kiệm (~9 MB/phút)
  bash run.sh --hd [serial]      Chuyển sang chất lượng HD 720p độ nét cao (~37 MB/phút)
  bash run.sh --flip [serial]    Xem camera trực tiếp, khi app đã nhả camera
HELP
    exit 0
    ;;
  --sd)
    shift
    exec bash scripts/set-camera-quality.sh sd "$@"
    ;;
  --hd)
    shift
    exec bash scripts/set-camera-quality.sh hd "$@"
    ;;
  --stop-viewer-link)
    shift
    exec python3 scripts/viewer-link.py stop "$@"
    ;;
  ""|--flip|--live|--history|--phone|--mirror)
    case "${1:-}" in
      --flip) export FLIP_VIEWER_MODE=direct ;;
      --live) export FLIP_VIEWER_MODE=live ;;
      --history) export FLIP_VIEWER_MODE=history ;;
      --phone|--mirror) export FLIP_VIEWER_MODE=mirror ;;
      *) export FLIP_VIEWER_MODE=auto ;;
    esac
    if [[ $# != 0 ]]; then shift; fi
    exec bash scripts/view-flip-camera.sh "$@"
    ;;
esac
case "${1:-}" in
  --studio|[0-9]*)
    echo "Bản Flip Studio đã được gộp vào viewer (frontend/ đã chuyển sang archive/frontend-studio)." >&2
    echo "Dùng: bash run.sh --live hoặc bash run.sh --history" >&2
    exit 2
    ;;
esac
echo "Tham số không hợp lệ: ${1:-}" >&2
echo "Xem cách dùng: bash run.sh --help" >&2
exit 2
