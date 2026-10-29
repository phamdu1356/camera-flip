#!/usr/bin/env bash
set -eo pipefail

PHONE_IP="${1:-${PHONE_IP:-172.17.161.89}}"
PORT="${2:-${PHONE_PORT:-5555}}"
WATCHDOG_MODE=0

for arg in "$@"; do
    case "$arg" in
        --watch|-w|--daemon)
            WATCHDOG_MODE=1
            ;;
    esac
done

echo "======================================================================"
echo "    SECURECAM 24/7 - ADB WIRELESS PAIRING & WATCHDOG"
echo "    Target IP: ${PHONE_IP}:${PORT}"
echo "======================================================================"

connect_device() {
    # Check if target is already connected
    if adb devices | grep -q "${PHONE_IP}:${PORT}[[:space:]]\+device"; then
        return 0
    fi

    # Check for active USB device to initialize tcpip mode
    USB_DEV=$( (adb devices | grep -E "device$" | grep -v ":" | head -n 1 | awk '{print $1}') || true )
    if [ -n "$USB_DEV" ]; then
        echo "Found authorized USB device $USB_DEV. Enabling TCP/IP port $PORT..."
        adb -s "$USB_DEV" tcpip "$PORT" 2>/dev/null || true
        sleep 1
    fi

    echo -n "Attempting connection to ${PHONE_IP}:${PORT}... "
    RES=$(adb connect "${PHONE_IP}:${PORT}" 2>&1 || true)
    echo "$RES"

    if echo "$RES" | grep -q "connected to"; then
        return 0
    fi
    return 1
}

# Initial connection
connect_device || true

if [ "$WATCHDOG_MODE" -eq 1 ]; then
    echo "Starting persistent wireless watchdog (Interval: 10s)..."
    echo "Press Ctrl+C to stop."
    while true; do
        if ! adb devices | grep -q "${PHONE_IP}:${PORT}[[:space:]]\+device"; then
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] ⚠️  Wireless ADB connection lost. Reconnecting to ${PHONE_IP}:${PORT}..."
            connect_device || true
        fi
        sleep 10
    done
else
    # Status summary
    if adb devices | grep -q "${PHONE_IP}:${PORT}[[:space:]]\+device"; then
        echo "✔ Wireless ADB connected successfully to ${PHONE_IP}:${PORT}."
    else
        echo "ℹ Note: If connection failed, make sure:"
        echo "  1. Phone and PC are on the same Wi-Fi subnet."
        echo "  2. USB was plugged in once to run 'adb tcpip 5555'."
        echo "  3. Phone screen authorization prompt was accepted."
        echo "  Run with '--watch' to keep watchdog running: ./scripts/adb_wireless_setup.sh --watch"
    fi
fi
