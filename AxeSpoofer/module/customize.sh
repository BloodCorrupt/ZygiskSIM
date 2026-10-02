#!/system/bin/sh
# Axe Spoofer Installer
# Compatible with: KernelSU, APatch, Magisk (via Zygisk / ZygiskNext)

SKIPUNZIP=0

ui_print "================================================"
ui_print "           Axe Spoofer - Zygisk Module"
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
        ui_print "! WARNING: Enable Zygisk in Magisk settings or install ZygiskNext."
    fi
else
    ui_print "- Root: Standard / Unknown"
fi

ui_print ""

# 2. Preserve existing config.json if present
if [ -f "/data/adb/modules/axespoofer/config.json" ]; then
    ui_print "- Preserving existing Axe Spoofer config.json"
    cp -f "/data/adb/modules/axespoofer/config.json" "$MODPATH/config.json"
fi

# 3. Architecture check
DEVICE_ABI=$(getprop ro.product.cpu.abi)
ui_print "- Detected Device ABI: $DEVICE_ABI"

# 4. Set permissions
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/action.sh" 0 0 0755
if [ -f "$MODPATH/service.sh" ]; then
    set_perm "$MODPATH/service.sh" 0 0 0755
fi

# 5. Create log directory
mkdir -p /data/adb/modules/axespoofer/logs
chmod 0777 /data/adb/modules/axespoofer/logs

ui_print ""
ui_print "================================================"
ui_print " Axe Spoofer installed successfully!"
ui_print " Use the Action button in Manager or WebUI"
ui_print " to randomize / configure device identifiers."
ui_print "================================================"
