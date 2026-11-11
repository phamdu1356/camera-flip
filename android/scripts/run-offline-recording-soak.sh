#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/tmp/camera-g1-toolchain/sdk}}"
adb="$sdk/platform-tools/adb"
serial="${ANDROID_SERIAL:-emulator-5554}"
avd="${FLIP_CAMERA_AVD:-g1-test-api34}"
duration_ms="${SOAK_DURATION_MS:-1800000}"
screen_off="${SOAK_SCREEN_OFF:-false}"

if [[ ! -x "$adb" ]]; then
  echo "Android platform-tools were not found; set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
  exit 1
fi
if [[ ! "$duration_ms" =~ ^[0-9]+$ ]] || (( duration_ms < 3000 || duration_ms > 1800000 )); then
  echo "SOAK_DURATION_MS must be between 3000 and 1800000." >&2
  exit 1
fi
if [[ "$screen_off" != "true" && "$screen_off" != "false" ]]; then
  echo "SOAK_SCREEN_OFF must be true or false." >&2
  exit 1
fi
if [[ "$("$adb" -s "$serial" get-state 2>/dev/null || true)" != "device" ]]; then
  echo "Start the dGPU AVD first and ensure $serial is online." >&2
  exit 1
fi
if [[ "$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "$serial is not an Android emulator." >&2
  exit 1
fi

# Require this exact AVD to be rendered by NVIDIA and isolated from host swap.
emulator_pid=""
while IFS= read -r pid; do
  [[ -n "$pid" ]] || continue
  command_line="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
  if [[ "$command_line" == *"-avd $avd"* ]]; then
    emulator_pid="$pid"
    break
  fi
done < <(pgrep -f '[q]emu-system-x86_64' || true)
if [[ -z "$emulator_pid" ]] || ! nvidia-smi --query-compute-apps=pid --format=csv,noheader,nounits 2>/dev/null | tr -d ' ' | grep -Fxq "$emulator_pid"; then
  echo "NVIDIA is not rendering the $avd emulator; refusing an iGPU/software fallback." >&2
  exit 1
fi
cgroup="$(awk -F: '$1 == 0 { print $3 }' "/proc/$emulator_pid/cgroup")"
swap_limit="$(cat "/sys/fs/cgroup${cgroup}/memory.swap.max" 2>/dev/null || true)"
guest_swap="$("$adb" -s "$serial" shell cat /proc/meminfo | sed -n 's/^SwapTotal:[[:space:]]*//p' | awk '{ print $1 }' | tr -d '\r')"
if [[ "$swap_limit" != "0" || "$guest_swap" != "0" ]]; then
  echo "Emulator swap must be disabled (cgroup=$swap_limit, guest=${guest_swap:-unknown} kB)." >&2
  exit 1
fi

# ADB root is needed only to take the emulator interfaces down and restore them.
"$adb" -s "$serial" root >/dev/null
"$adb" -s "$serial" wait-for-device
wifi_was_on="$("$adb" -s "$serial" shell settings get global wifi_on | tr -d '\r')"
data_was_on="$("$adb" -s "$serial" shell settings get global mobile_data | tr -d '\r')"
eth_was_up="$("$adb" -s "$serial" shell ip -brief link show dev eth0 | tr -d '\r' | sed -E 's/^eth0[[:space:]]+([^[:space:]]+).*/\1/')"

restore_network() {
  if [[ "$eth_was_up" == "UP" ]]; then
    "$adb" -s "$serial" shell ip link set eth0 up >/dev/null 2>&1 || true
  else
    "$adb" -s "$serial" shell ip link set eth0 down >/dev/null 2>&1 || true
  fi
  if [[ "$wifi_was_on" == "1" ]]; then
    "$adb" -s "$serial" shell svc wifi enable >/dev/null 2>&1 || true
  else
    "$adb" -s "$serial" shell svc wifi disable >/dev/null 2>&1 || true
  fi
  if [[ "$data_was_on" == "1" ]]; then
    "$adb" -s "$serial" shell svc data enable >/dev/null 2>&1 || true
  else
    "$adb" -s "$serial" shell svc data disable >/dev/null 2>&1 || true
  fi
}
cleanup() {
  result=$?
  trap - EXIT INT TERM
  restore_network
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$adb" -s "$serial" shell svc wifi disable
"$adb" -s "$serial" shell svc data disable
"$adb" -s "$serial" shell ip link set eth0 down
offline_links="$("$adb" -s "$serial" shell ip -brief link | tr -d '\r')"
routes="$("$adb" -s "$serial" shell ip route | tr -d '\r')"
if ! grep -Eq '^eth0[[:space:]]+DOWN' <<< "$offline_links" ||
   ! grep -Eq '^wlan0[[:space:]]+DOWN' <<< "$offline_links" ||
   grep -Eq '(^|[[:space:]])default([[:space:]]|$)' <<< "$routes"; then
  echo "Could not isolate the emulator from the network." >&2
  echo "$offline_links" >&2
  echo "$routes" >&2
  exit 1
fi

echo "Running ${duration_ms}ms camera recording with emulator Wi-Fi, mobile data, and Ethernet disconnected; screen-off=${screen_off}."
echo "GPU: NVIDIA dGPU; emulator cgroup and Android guest swap are both 0."
cd "$android_dir"
export ANDROID_HOME="$sdk"
export ANDROID_SDK_ROOT="$sdk"
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  "-PinstrumentationTestClass=com.phamdu.flipcamera.CameraRecordingIntegrationTest" \
  "-PrecordingDurationMs=$duration_ms" \
  "-PscreenOffDuringRecording=$screen_off"
