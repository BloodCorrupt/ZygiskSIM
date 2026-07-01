#!/system/bin/sh
# ZygiskSIM Installer

SKIPUNZIP=0

ui_print "================================================"
ui_print "         ZygiskSIM - eSIM Spoof Module"
ui_print "================================================"
ui_print ""

# Check Zygisk is enabled
if [ "$ZYGISK_ENABLED" != "1" ]; then
    ui_print "! Zygisk is not enabled!"
    ui_print "  Please enable Zygisk in Magisk settings"
    ui_print "  and reboot before installing this module."
    abort
fi

ui_print "- Extracting module files..."

# Create log directory
mkdir -p "$MODPATH/logs"
chmod 0777 "$MODPATH/logs"

# Set permissions for scripts
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755

ui_print ""
ui_print "- ZygiskSIM installed successfully!"
ui_print "- Reboot to activate eSIM spoofing"
ui_print ""
ui_print "  Logs: /data/adb/modules/zygisksim/logs/"
ui_print "================================================"
