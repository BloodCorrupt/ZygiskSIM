#!/bin/bash
#
# Axe Spoofer Build Script
#
# Prerequisites:
#   - Android NDK (set ANDROID_NDK_HOME or NDK_HOME)
#   - Android SDK build-tools (for d8 to compile DEX)
#   - Java JDK 8+ (for javac)
#   - zip command
#
# Usage:
#   chmod +x build.sh
#   ./build.sh
#
# Output: out/AxeSpoofer-v1.0.0.zip (flashable Magisk / KernelSU / APatch module)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/module"
BUILD_DIR="$SCRIPT_DIR/build"
OUT_DIR="$SCRIPT_DIR/out"

MODULE_VERSION="v1.0.0"
MODULE_NAME="AxeSpoofer"
ZIP_NAME="${MODULE_NAME}-${MODULE_VERSION}.zip"

echo "================================================"
echo "         Building ${MODULE_NAME} ${MODULE_VERSION}"
echo "================================================"

# =====================================================================
# 1. Locate Toolchain
# =====================================================================

# NDK
if [ -n "$ANDROID_NDK_HOME" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -n "$NDK_HOME" ]; then
    NDK="$NDK_HOME"
elif [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK=$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -1)
else
    echo "ERROR: Android NDK not found. Set ANDROID_NDK_HOME or NDK_HOME."
    exit 1
fi
echo "Using NDK: $NDK"
NDK_BUILD="$NDK/ndk-build"
if [ ! -f "$NDK_BUILD" ]; then
    NDK_BUILD="$NDK/ndk-build.cmd"
fi

# d8
if [ -n "$ANDROID_HOME" ]; then
    D8=$(find "$ANDROID_HOME/build-tools" -name "d8" -o -name "d8.bat" 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$D8" ]; then
    D8=$(which d8 2>/dev/null || true)
fi
if [ -z "$D8" ]; then
    echo "ERROR: d8 not found. Install Android SDK build-tools and set ANDROID_HOME."
    exit 1
fi
echo "Using d8: $D8"

# android.jar
if [ -n "$ANDROID_HOME" ]; then
    ANDROID_JAR=$(ls "$ANDROID_HOME/platforms/android-"*/android.jar 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "WARNING: android.jar not found under ANDROID_HOME/platforms."
    echo "Falling back to local search..."
    ANDROID_JAR=$(find / -name "android.jar" 2>/dev/null | head -1 || true)
fi
if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: android.jar not found. Install an Android platform SDK (e.g. android-34)."
    exit 1
fi
echo "Using android.jar: $ANDROID_JAR"

# =====================================================================
# 2. Prepare Clean Build Directory
# =====================================================================

rm -rf "$BUILD_DIR" "$OUT_DIR"
mkdir -p "$BUILD_DIR" "$OUT_DIR"
mkdir -p "$BUILD_DIR/zygisk"
mkdir -p "$BUILD_DIR/webroot"

# =====================================================================
# 3. Compile Java Source to DEX
# =====================================================================

echo ""
echo "=== Compiling Java hook payload ==="
JAVA_SRC_DIR="$MODULE_DIR/java"
JAVA_CLASSES_DIR="$BUILD_DIR/classes"
mkdir -p "$JAVA_CLASSES_DIR"

JAVA_FILES=$(find "$JAVA_SRC_DIR" -name "*.java")

javac -source 1.8 -target 1.8 \
    -bootclasspath "$ANDROID_JAR" \
    -d "$JAVA_CLASSES_DIR" \
    $JAVA_FILES

echo "Compiled Java classes successfully."

echo "=== Packaging DEX with d8 ==="
CLASS_FILES=$(find "$JAVA_CLASSES_DIR" -name "*.class")

"$D8" --min-api 26 \
    --output "$BUILD_DIR" \
    --lib "$ANDROID_JAR" \
    $CLASS_FILES

if [ ! -f "$BUILD_DIR/classes.dex" ]; then
    echo "ERROR: classes.dex was not generated!"
    exit 1
fi
echo "DEX generation complete: $BUILD_DIR/classes.dex"

# =====================================================================
# 4. Compile Native Zygisk Libraries (C++)
# =====================================================================

echo ""
echo "=== Compiling native C++ Zygisk libraries ==="

"$NDK_BUILD" -C "$MODULE_DIR/jni" \
    NDK_PROJECT_PATH=null \
    NDK_APPLICATION_MK="$MODULE_DIR/jni/Application.mk" \
    APP_BUILD_SCRIPT="$MODULE_DIR/jni/Android.mk" \
    NDK_OUT="$BUILD_DIR/obj" \
    NDK_LIBS_OUT="$BUILD_DIR/libs"

# Copy compiled native libraries to Magisk zygisk/ layout
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    if [ -f "$BUILD_DIR/libs/$abi/libaxespoofer.so" ]; then
        cp "$BUILD_DIR/libs/$abi/libaxespoofer.so" "$BUILD_DIR/zygisk/$abi.so"
        echo "Packaged zygisk/$abi.so"
    fi
done

# =====================================================================
# 5. Copy Module Files & WebUI
# =====================================================================

echo ""
echo "=== Assembling flashable module structure ==="

cp "$MODULE_DIR/module.prop" "$BUILD_DIR/"
cp "$MODULE_DIR/customize.sh" "$BUILD_DIR/"
cp "$MODULE_DIR/action.sh" "$BUILD_DIR/"
cp "$MODULE_DIR/config.json" "$BUILD_DIR/"

# WebUI
if [ -d "$SCRIPT_DIR/webui" ]; then
    cp -r "$SCRIPT_DIR/webui/"* "$BUILD_DIR/webroot/" 2>/dev/null || true
    echo "Included WebUI in webroot/"
fi

# =====================================================================
# 6. Create Zip Package
# =====================================================================

echo ""
echo "=== Packaging Magisk / KernelSU module zip ==="
cd "$BUILD_DIR"

zip -r9 "$OUT_DIR/$ZIP_NAME" \
    module.prop \
    customize.sh \
    action.sh \
    config.json \
    classes.dex \
    zygisk/ \
    webroot/ \
    -x "obj/*" "libs/*" "classes/*" "*.tmp"

echo ""
echo "================================================"
echo "  BUILD SUCCESSFUL!"
echo "  Output: $OUT_DIR/$ZIP_NAME"
echo "================================================"
