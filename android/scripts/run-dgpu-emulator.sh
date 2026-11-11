#!/usr/bin/env bash
set -euo pipefail

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
avd="${FLIP_CAMERA_AVD:-g1-test-api34}"
port="${FLIP_CAMERA_EMULATOR_PORT:-5554}"

if [[ -z "$sdk" || ! -x "$sdk/emulator/emulator" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to an Android SDK containing emulator/." >&2
  exit 1
fi
if ! command -v systemd-run >/dev/null 2>&1; then
  echo "systemd-run is required to disable swap for this emulator process." >&2
  exit 1
fi
if ! command -v nvidia-smi >/dev/null 2>&1; then
  echo "NVIDIA driver tools were not found; this launcher requires the NVIDIA dGPU." >&2
  exit 1
fi
if [[ ! -x "$sdk/platform-tools/adb" ]]; then
  echo "Android platform-tools/adb was not found in $sdk." >&2
  exit 1
fi
if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 5554 || port > 5682 || port % 2 != 0 )); then
  echo "FLIP_CAMERA_EMULATOR_PORT must be an even port from 5554 to 5682." >&2
  exit 1
fi

adb="$sdk/platform-tools/adb"
serial="emulator-$port"
booted=0
cleanup() {
  result=$?
  if (( result != 0 )); then
    "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

systemd-run --user --scope --property=MemorySwapMax=0 \
  --setenv=__NV_PRIME_RENDER_OFFLOAD=1 \
  --setenv=__GLX_VENDOR_LIBRARY_NAME=nvidia \
  --setenv=__VK_LAYER_NV_optimus=NVIDIA_only \
  "$sdk/emulator/emulator" -avd "$avd" -port "$port" -gpu host -no-snapshot -no-boot-anim "$@" &
emulator_client=$!

echo "Waiting for $avd ($serial) to finish booting..."
for ((attempt = 0; attempt < 180; attempt++)); do
  state="$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$state" == "1" ]]; then
    booted=1
    break
  fi
  if ! kill -0 "$emulator_client" 2>/dev/null; then
    echo "Emulator process exited before Android finished booting." >&2
    exit 1
  fi
  sleep 1
done
if (( ! booted )); then
  echo "Timed out waiting for Android to boot." >&2
  exit 1
fi

# AVD guest images enable zRAM by default. Disable every guest swap device too.
"$adb" -s "$serial" root
for ((attempt = 0; attempt < 30; attempt++)); do
  if "$adb" -s "$serial" shell id 2>/dev/null | grep -q 'uid=0(root)'; then
    break
  fi
  sleep 1
done
if ! "$adb" -s "$serial" shell id 2>/dev/null | grep -q 'uid=0(root)'; then
  echo "Could not get root ADB on the emulator to disable guest swap." >&2
  exit 1
fi

swap_devices="$("$adb" -s "$serial" shell cat /proc/swaps | tr -d '\r' | awk 'NR > 1 { print $1 }')"
while IFS= read -r swap_device; do
  [[ -n "$swap_device" ]] || continue
  "$adb" -s "$serial" shell swapoff "$swap_device"
done <<< "$swap_devices"
swap_total="$("$adb" -s "$serial" shell cat /proc/meminfo | tr -d '\r' | awk '/^SwapTotal:/ { print $2 }')"
if [[ "$swap_total" != "0" ]]; then
  echo "Android guest swap is still enabled (SwapTotal=${swap_total:-unknown} kB)." >&2
  exit 1
fi
gpu_apps="$(nvidia-smi --query-compute-apps=process_name --format=csv,noheader 2>/dev/null || true)"
if ! grep -q 'qemu-system-x86_64' <<< "$gpu_apps"; then
  echo "NVIDIA does not report the emulator process; refusing an iGPU/software fallback." >&2
  exit 1
fi

echo "GPU: NVIDIA RTX dGPU verified by nvidia-smi; emulator uses -gpu host."
echo "Swap: emulator cgroup MemorySwapMax=0; Android guest SwapTotal=0."
wait "$emulator_client"
