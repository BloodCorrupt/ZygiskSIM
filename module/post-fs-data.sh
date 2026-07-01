#!/system/bin/sh
# ZygiskSIM post-fs-data script
# Creates the log directory with world-writable permissions so app processes can write to it

MODDIR=${0%/*}

mkdir -p "$MODDIR/logs"
chmod 0777 "$MODDIR/logs"
