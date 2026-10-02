#!/system/bin/sh
# ZygiskSIM Installer
# Compatible with: KernelSU, APatch, Magisk (via ZygiskNext)

SKIPUNZIP=0

ui_print "================================================"
ui_print "         ZygiskSIM - eSIM Spoof Module"
ui_print "================================================"
ui_print ""

# 1. Detect root solution
if [ "$KSU" = "true" ]; then
    ui_print "- Root: KernelSU (v${KSU_VER_CODE:-unknown})"
    ui_print "  Make sure ZygiskNext is installed!"
elif [ "$APATCH" = "true" ]; then
    ui_print "- Root: APatch (v${APATCH_VER_CODE:-unknown})"
    ui_print "  Make sure ZygiskNext is installed!"
elif [ "$MAGISK_VER_CODE" ]; then
    ui_print "- Root: Magisk (v${MAGISK_VER_CODE})"
    if [ "$ZYGISK_ENABLED" != "1" ]; then
        ui_print ""
        ui_print "! WARNING: Zygisk is not enabled!"
        ui_print "  Enable Zygisk in Magisk settings or"
        ui_print "  install ZygiskNext, then reboot."
    fi
else
    ui_print "- Root: Unknown"
    ui_print "  Ensure Zygisk/ZygiskNext is available!"
fi

ui_print ""

# 2. Architecture Detection & Hook Engine Selection
DEVICE_ARCH="$ARCH"
if [ -z "$DEVICE_ARCH" ]; then
    DEVICE_ABI=$(getprop ro.product.cpu.abi)
    case "$DEVICE_ABI" in
        arm64*|aarch64*) DEVICE_ARCH="arm64" ;;
        armeabi*|armv7*) DEVICE_ARCH="arm" ;;
        x86_64*)         DEVICE_ARCH="x64" ;;
        x86*)            DEVICE_ARCH="x86" ;;
        *)               DEVICE_ARCH="unknown" ;;
    esac
fi

ui_print "- Detected CPU Architecture: $DEVICE_ARCH"

case "$DEVICE_ARCH" in
    x86|x64)
        HOOK_ENGINE="dobby"
        ui_print "- Selected Hook Engine: Dobby (Native Hook for x86/x64)"
        # Remove incompatible ARM Pine libraries on x86/x64 emulators
        rm -rf "$MODPATH/system/lib" "$MODPATH/system/lib64"
        ;;
    arm|arm64|*)
        HOOK_ENGINE="pine"
        ui_print "- Selected Hook Engine: Pine (ART Hook for ARM/ARM64)"
        ;;
esac

# Persist selected engine
echo "$HOOK_ENGINE" > "$MODPATH/engine"

# Update config.json if not present or preserve existing config
if [ ! -f "$MODPATH/config.json" ] || [ ! -s "$MODPATH/config.json" ] || [ "$(cat "$MODPATH/config.json")" = "{}" ]; then
    cat << EOF > "$MODPATH/config.json"
{
  "engine": "$HOOK_ENGINE",
  "arch": "$DEVICE_ARCH"
}
EOF
fi

ui_print ""
ui_print "- Extracting module files..."

# Create log directory with world-writable permissions
# so app processes (sandboxed) can write activation codes
mkdir -p "$MODPATH/logs"
chmod 0777 "$MODPATH/logs"

# Set permissions for scripts
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755

ui_print ""
ui_print "- ZygiskSIM installed successfully!"
ui_print "- Active Engine: $HOOK_ENGINE ($DEVICE_ARCH)"
ui_print "- Reboot to activate eSIM spoofing"
ui_print ""
ui_print "  Logs: /data/adb/modules/zygisksim/logs/"
ui_print "  Logcat: adb logcat -s ZygiskSIM"
ui_print "================================================"
