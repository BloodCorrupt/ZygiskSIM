#!/system/bin/sh
# ZygiskSIM Installer
# Compatible with: KernelSU, APatch, Magisk (via ZygiskNext)

SKIPUNZIP=0

ui_print "================================================"
ui_print "         ZygiskSIM - eSIM Spoof Module"
ui_print "================================================"
ui_print ""

# Detect root solution
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
ui_print "- Extracting module files..."

# Create log directory with world-writable permissions
# so app processes (sandboxed) can write activation codes
mkdir -p "$MODPATH/logs"
chmod 0777 "$MODPATH/logs"

# Set permissions for scripts
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755

ui_print ""
ui_print "- ZygiskSIM installed successfully!"
ui_print "- Reboot to activate eSIM spoofing"
ui_print ""
ui_print "  Logs: /data/adb/modules/zygisksim/logs/"
ui_print "  Logcat: adb logcat -s ZygiskSIM"
ui_print "================================================"
