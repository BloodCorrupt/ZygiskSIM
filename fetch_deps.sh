#!/bin/bash
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
JNI_DIR="$DIR/module/jni"
TMP_DIR="$DIR/tmp_deps"

mkdir -p "$TMP_DIR"
cd "$TMP_DIR"

echo "Fetching LSplant..."
curl -sL "https://repo1.maven.org/maven2/org/lsposed/lsplant/lsplant-standalone/1.0.3/lsplant-standalone-1.0.3.aar" -o lsplant.aar
unzip -q lsplant.aar -d lsplant_extract

mkdir -p "$JNI_DIR/lsplant/include"
mkdir -p "$JNI_DIR/lsplant/lib/armeabi-v7a"
mkdir -p "$JNI_DIR/lsplant/lib/arm64-v8a"
mkdir -p "$JNI_DIR/lsplant/lib/x86"
mkdir -p "$JNI_DIR/lsplant/lib/x86_64"

cp -r lsplant_extract/prefab/modules/lsplant/include/* "$JNI_DIR/lsplant/include/"
cp lsplant_extract/prefab/modules/lsplant/libs/android.arm.v7a/liblsplant.a "$JNI_DIR/lsplant/lib/armeabi-v7a/"
cp lsplant_extract/prefab/modules/lsplant/libs/android.arm.v8a/liblsplant.a "$JNI_DIR/lsplant/lib/arm64-v8a/"
cp lsplant_extract/prefab/modules/lsplant/libs/android.x86/liblsplant.a "$JNI_DIR/lsplant/lib/x86/"
cp lsplant_extract/prefab/modules/lsplant/libs/android.x86_64/liblsplant.a "$JNI_DIR/lsplant/lib/x86_64/"

echo "Fetching Dobby..."
curl -sL "https://repo1.maven.org/maven2/io/github/vvb2060/ndk/dobby/2.1.0/dobby-2.1.0.aar" -o dobby.aar
unzip -q dobby.aar -d dobby_extract

mkdir -p "$JNI_DIR/dobby/include"
mkdir -p "$JNI_DIR/dobby/lib/armeabi-v7a"
mkdir -p "$JNI_DIR/dobby/lib/arm64-v8a"
mkdir -p "$JNI_DIR/dobby/lib/x86"
mkdir -p "$JNI_DIR/dobby/lib/x86_64"

cp -r dobby_extract/prefab/modules/dobby/include/* "$JNI_DIR/dobby/include/"
cp dobby_extract/prefab/modules/dobby/libs/android.arm.v7a/libdobby.a "$JNI_DIR/dobby/lib/armeabi-v7a/"
cp dobby_extract/prefab/modules/dobby/libs/android.arm.v8a/libdobby.a "$JNI_DIR/dobby/lib/arm64-v8a/"
cp dobby_extract/prefab/modules/dobby/libs/android.x86/libdobby.a "$JNI_DIR/dobby/lib/x86/"
cp dobby_extract/prefab/modules/dobby/libs/android.x86_64/libdobby.a "$JNI_DIR/dobby/lib/x86_64/"

echo "Done! Prebuilt libraries extracted to module/jni"

