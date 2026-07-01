#!/bin/bash
#
# ZygiskSIM Build Script
#
# Prerequisites:
#   - Android NDK (set ANDROID_NDK_HOME or NDK_HOME)
#   - Android SDK build-tools (for d8/dx to compile DEX)
#   - Java JDK 8+ (for javac)
#   - zip command
#
# Usage:
#   chmod +x build.sh
#   ./build.sh
#
# Output: ZygiskSIM-v1.0.zip (flashable Magisk module)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/module"
BUILD_DIR="$SCRIPT_DIR/build"
OUT_DIR="$SCRIPT_DIR/out"

# Module version (keep in sync with module.prop)
MODULE_VERSION="v1.0"
MODULE_NAME="ZygiskSIM"
ZIP_NAME="${MODULE_NAME}-${MODULE_VERSION}.zip"

# =====================================================================
# Find tools
# =====================================================================

# Find NDK
if [ -n "$ANDROID_NDK_HOME" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -n "$NDK_HOME" ]; then
    NDK="$NDK_HOME"
elif [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK=$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -1)
else
    echo "ERROR: Android NDK not found."
    echo "Set ANDROID_NDK_HOME or NDK_HOME environment variable."
    exit 1
fi

echo "Using NDK: $NDK"
NDK_BUILD="$NDK/ndk-build"
if [ ! -f "$NDK_BUILD" ]; then
    NDK_BUILD="$NDK/ndk-build.cmd"
fi

# Find d8 (DEX compiler)
if [ -n "$ANDROID_HOME" ]; then
    D8=$(find "$ANDROID_HOME/build-tools" -name "d8" -o -name "d8.bat" 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$D8" ]; then
    D8=$(which d8 2>/dev/null || true)
fi
if [ -z "$D8" ]; then
    echo "ERROR: d8 (DEX compiler) not found."
    echo "Install Android SDK build-tools and set ANDROID_HOME."
    exit 1
fi
echo "Using d8: $D8"

# Find android.jar (for compiling Java against framework APIs)
if [ -n "$ANDROID_HOME" ]; then
    ANDROID_JAR=$(ls "$ANDROID_HOME/platforms/android-"*/android.jar 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$ANDROID_JAR" ]; then
    echo "ERROR: android.jar not found."
    echo "Install an Android SDK platform (e.g., API 33) and set ANDROID_HOME."
    exit 1
fi
echo "Using android.jar: $ANDROID_JAR"

# =====================================================================
# Clean
# =====================================================================

echo ""
echo "=== Cleaning build directories ==="
rm -rf "$BUILD_DIR" "$OUT_DIR"
mkdir -p "$BUILD_DIR/java" "$BUILD_DIR/dex" "$BUILD_DIR/zip" "$OUT_DIR"

# =====================================================================
# Step 1: Build native libraries (ndk-build)
# =====================================================================

echo ""
echo "=== Building native libraries ==="
cd "$MODULE_DIR"
"$NDK_BUILD" \
    NDK_PROJECT_PATH="$MODULE_DIR" \
    NDK_OUT="$BUILD_DIR/obj" \
    NDK_LIBS_OUT="$BUILD_DIR/libs" \
    APP_BUILD_SCRIPT="$MODULE_DIR/jni/Android.mk" \
    NDK_APPLICATION_MK="$MODULE_DIR/jni/Application.mk" \
    -j$(nproc 2>/dev/null || echo 4)

echo "Native build complete"

# =====================================================================
# Step 2: Compile Java to DEX
# =====================================================================

echo ""
echo "=== Compiling Java hook payload ==="

# Compile Java source
javac \
    -source 1.8 \
    -target 1.8 \
    -bootclasspath "$ANDROID_JAR" \
    -cp "$MODULE_DIR/java/pine.jar" \
    -d "$BUILD_DIR/java" \
    "$MODULE_DIR/java/com/zygisksim/HookEntry.java"

# Convert to DEX (include ALL .class files — anonymous inner classes like HookEntry$1.class are critical)
"$D8" \
    --output "$BUILD_DIR/dex" \
    --min-api 26 \
    $(find "$BUILD_DIR/java" -name "*.class") \
    "$MODULE_DIR/java/pine.jar"

echo "DEX compilation complete"

# =====================================================================
# Step 3: Assemble module ZIP
# =====================================================================

echo ""
echo "=== Assembling module ZIP ==="

ZIP_ROOT="$BUILD_DIR/zip"

# Copy module metadata
cp "$MODULE_DIR/module.prop" "$ZIP_ROOT/"
cp "$MODULE_DIR/customize.sh" "$ZIP_ROOT/"
cp "$MODULE_DIR/post-fs-data.sh" "$ZIP_ROOT/"

# Copy web UI and default config placeholder (KernelSU WebUI uses 'webroot')
cp -r "$BASE_DIR/webui" "$ZIP_ROOT/webroot"
echo "{}" > "$ZIP_ROOT/config.json"

# Copy DEX payload
cp "$BUILD_DIR/dex/classes.dex" "$ZIP_ROOT/"

# Copy Pine native libraries into system paths so apps can access them
mkdir -p "$ZIP_ROOT/system/lib"
mkdir -p "$ZIP_ROOT/system/lib64"
if [ -f "$MODULE_DIR/pine/armeabi-v7a/libpine.so" ]; then
    cp "$MODULE_DIR/pine/armeabi-v7a/libpine.so" "$ZIP_ROOT/system/lib/"
fi
if [ -f "$MODULE_DIR/pine/arm64-v8a/libpine.so" ]; then
    cp "$MODULE_DIR/pine/arm64-v8a/libpine.so" "$ZIP_ROOT/system/lib64/"
fi

# Copy system overlay
mkdir -p "$ZIP_ROOT/system/etc/permissions"
cp "$MODULE_DIR/system/etc/permissions/esim_feature.xml" "$ZIP_ROOT/system/etc/permissions/"

# Copy native libraries (rename to ABI.so for Zygisk)
mkdir -p "$ZIP_ROOT/zygisk"
for abi_dir in "$BUILD_DIR/libs/"*/; do
    abi=$(basename "$abi_dir")
    if [ -f "$abi_dir/libzygisksim.so" ]; then
        cp "$abi_dir/libzygisksim.so" "$ZIP_ROOT/zygisk/${abi}.so"
        echo "  Added: zygisk/${abi}.so"
    fi
done

# Create META-INF (standard Magisk module installer)
mkdir -p "$ZIP_ROOT/META-INF/com/google/android"

cat > "$ZIP_ROOT/META-INF/com/google/android/updater-script" << 'EOF'
#MAGISK
EOF

cat > "$ZIP_ROOT/META-INF/com/google/android/update-binary" << 'BINEOF'
#!/sbin/sh

#################
# Initialization
#################

umask 022

# echo before loading util_functions
ui_print() { echo "$1"; }

require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "*******************************"
  exit 1
}

#########################
# Load util_functions.sh
#########################

OUTFD=$2
ZIPFILE=$3

mount /data 2>/dev/null

[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ $MAGISK_VER_CODE -lt 20400 ] && require_new_magisk

install_module
exit 0
BINEOF

chmod 755 "$ZIP_ROOT/META-INF/com/google/android/update-binary"

# Create the ZIP
cd "$ZIP_ROOT"
zip -r9 "$OUT_DIR/$ZIP_NAME" . \
    -x "*.DS_Store" -x "*__MACOSX*"

# =====================================================================
# Done
# =====================================================================

echo ""
echo "=== Build complete! ==="
echo ""
echo "  Output: $OUT_DIR/$ZIP_NAME"
echo "  Size:   $(du -h "$OUT_DIR/$ZIP_NAME" | cut -f1)"
echo ""
echo "To install:"
echo "  1. Copy $ZIP_NAME to your device"
echo "  2. Flash via Magisk Manager"
echo "  3. Reboot"
echo ""
echo "Logs will appear in: /data/adb/modules/zygisksim/logs/esim_log.txt"
echo "Also check: adb logcat -s ZygiskSIM"
