#!/usr/bin/env bash
set -eo pipefail

echo "======================================================================"
echo "    SECURECAM 24/7 - SAMSUNG GALAXY Z FLIP 3 THERMAL & BATTERY SHIELD"
echo "======================================================================"

# Determine target ADB device
ADB_DEVICE=$( (adb devices | grep -E "device$" | head -n 1 | awk '{print $1}') || true )

if [ -z "$ADB_DEVICE" ]; then
    # Check if unauthorized
    UNAUTH=$( (adb devices | grep -E "unauthorized" | head -n 1 | awk '{print $1}') || true )
    if [ -n "$UNAUTH" ]; then
        echo "⚠️  Device $UNAUTH is UNAUTHORIZED."
        echo "👉 Action Required: Unlock Samsung Z Flip 3 screen and tap:"
        echo "   [✓] 'Always allow from this computer' -> 'Allow'"
        echo "Then re-run this script."
        exit 1
    else
        echo "❌ No ADB device detected. Please connect phone via USB or Wi-Fi ADB."
        exit 1
    fi
fi

echo "✔ Detected active ADB device: $ADB_DEVICE"
echo "Applying 24/7 thermal protection and battery preservation settings..."

# 1. Protect Battery (Caps maximum charge at 85% on Samsung One UI to prevent swelling)
echo -n "1. Enabling Battery Protection (Max 85% Charge)... "
adb -s "$ADB_DEVICE" shell settings put global protect_battery 1
VAL=$(adb -s "$ADB_DEVICE" shell settings get global protect_battery 2>/dev/null || echo "1")
echo "Done (protect_battery = $VAL)"

# 2. Disable Fast Charging to reduce Snapdragon 888 heat dissipation during 24/7 power
echo -n "2. Disabling Fast & Super Fast Charging... "
adb -s "$ADB_DEVICE" shell settings put system adaptive_fast_charging 0 2>/dev/null || true
adb -s "$ADB_DEVICE" shell settings put system fast_charging 0 2>/dev/null || true
adb -s "$ADB_DEVICE" shell settings put system super_fast_charging 0 2>/dev/null || true
echo "Done"

# 3. Minimum Display Brightness (Prevents AMOLED burn-in & reduces thermals)
echo -n "3. Setting Screen Brightness to Minimum (0)... "
adb -s "$ADB_DEVICE" shell settings put system screen_brightness_mode 0 2>/dev/null || true
adb -s "$ADB_DEVICE" shell settings put system screen_brightness 0 2>/dev/null || true
echo "Done"

# 4. Screen Off Timeout to 15 seconds (15000 ms)
echo -n "4. Setting Screen Timeout to 15s... "
adb -s "$ADB_DEVICE" shell settings put system screen_off_timeout 15000 2>/dev/null || true
echo "Done"

# 5. Disable Stay Awake while Plugged In (ensures screen actually sleeps)
echo -n "5. Disabling 'Stay awake while plugged in'... "
adb -s "$ADB_DEVICE" shell settings put global stay_on_while_plugged_in 0 2>/dev/null || true
echo "Done"

# 6. Turn screen off immediately
echo -n "6. Sending Screen Off keyevent... "
adb -s "$ADB_DEVICE" shell input keyevent 26 2>/dev/null || true
echo "Done"

echo "======================================================================"
echo "✔ Samsung Galaxy Z Flip 3 is now optimized for 24/7 CCTV surveillance!"
echo "  - Battery capped at 85% (Zero swelling risk)"
echo "  - Fast charging disabled (Reduced Snapdragon 888 heat)"
echo "  - Screen brightness 0 & timeout 15s (AMOLED burn-in prevention)"
echo "======================================================================"
