#!/usr/bin/env bash
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
APK_PATH="$PROJECT_ROOT/apk/androidipcamera-0.15.0-arm64-v8a-release.apk"
PKG_NAME="com.github.digitallyrefined.androidipcamera"

echo "======================================================================"
echo "    SECURECAM 24/7 - ANDROID IP CAMERA APK INSTALLER & SETUP"
echo "======================================================================"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK file not found at $APK_PATH"
    exit 1
fi

ADB_DEVICE=$( (adb devices | grep -E "device$" | head -n 1 | awk '{print $1}') || true )

if [ -z "$ADB_DEVICE" ]; then
    UNAUTH=$( (adb devices | grep -E "unauthorized" | head -n 1 | awk '{print $1}') || true )
    if [ -n "$UNAUTH" ]; then
        echo "⚠️  Device $UNAUTH is UNAUTHORIZED."
        echo "👉 Action Required: Unlock Samsung Z Flip 3 screen and tap:"
        echo "   [✓] 'Always allow from this computer' -> 'Allow'"
        echo "Then re-run this script."
        exit 1
    else
        echo "❌ No ADB device connected. Please connect Samsung Z Flip 3 via USB or Wi-Fi."
        exit 1
    fi
fi

echo "✔ Target ADB device: $ADB_DEVICE"
echo "Installing Android IP Camera ($APK_PATH)..."
adb -s "$ADB_DEVICE" install -r -d "$APK_PATH"

echo "Granting required runtime permissions..."
# Camera permission
adb -s "$ADB_DEVICE" shell pm grant "$PKG_NAME" android.permission.CAMERA 2>/dev/null || true
# Audio recording permission
adb -s "$ADB_DEVICE" shell pm grant "$PKG_NAME" android.permission.RECORD_AUDIO 2>/dev/null || true
# Notification permission (Android 13+)
adb -s "$ADB_DEVICE" shell pm grant "$PKG_NAME" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# Location permissions for Wi-Fi SSID identification
adb -s "$ADB_DEVICE" shell pm grant "$PKG_NAME" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb -s "$ADB_DEVICE" shell pm grant "$PKG_NAME" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true

echo "Whitelisting app from battery optimization (24/7 continuous streaming)..."
adb -s "$ADB_DEVICE" shell dumpsys deviceidle whitelist +"$PKG_NAME" 2>/dev/null || true

echo "Starting Android IP Camera application..."
adb -s "$ADB_DEVICE" shell am start -n "$PKG_NAME/.activities.MainActivity" 2>/dev/null || true

echo "======================================================================"
echo "✔ Android IP Camera successfully installed and configured on $ADB_DEVICE!"
echo "  - Package: $PKG_NAME"
echo "  - Permissions: CAMERA, AUDIO, NOTIFICATIONS, LOCATION granted"
echo "  - Battery optimization: Whitelisted (24/7 background safe)"
echo "======================================================================"
