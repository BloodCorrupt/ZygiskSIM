$ErrorActionPreference = "Stop"

$SCRIPT_DIR = $PSScriptRoot
$MODULE_DIR = Join-Path $SCRIPT_DIR "module"
$BUILD_DIR = Join-Path $SCRIPT_DIR "build"
$OUT_DIR = Join-Path $SCRIPT_DIR "out"

$MODULE_VERSION = "v1.0"
$MODULE_NAME = "ZygiskSIM"
$ZIP_NAME = "${MODULE_NAME}-${MODULE_VERSION}.zip"

# Find NDK
if ($env:ANDROID_NDK_HOME) {
    $NDK = $env:ANDROID_NDK_HOME
} elseif ($env:NDK_HOME) {
    $NDK = $env:NDK_HOME
} elseif ($env:ANDROID_HOME -and (Test-Path (Join-Path $env:ANDROID_HOME "ndk"))) {
    $NDK = (Get-ChildItem (Join-Path $env:ANDROID_HOME "ndk") | Sort-Object Name -Descending | Select-Object -First 1).FullName
} else {
    $NDK = "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\ndk\27.0.12077973" # Guessing default path
    if (-not (Test-Path $NDK)) {
        $ndk_dirs = Get-ChildItem "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\ndk" -Directory
        if ($ndk_dirs) {
            $NDK = ($ndk_dirs | Sort-Object Name -Descending | Select-Object -First 1).FullName
        } else {
            Write-Error "Android NDK not found."
            exit 1
        }
    }
}
Write-Host "Using NDK: $NDK"
$NDK_BUILD = Join-Path $NDK "ndk-build.cmd"

# Find D8
$D8 = ""
if ($env:ANDROID_HOME) {
    $d8_paths = Get-ChildItem (Join-Path $env:ANDROID_HOME "build-tools") -Recurse -Filter "d8.bat"
    if ($d8_paths) {
        $D8 = ($d8_paths | Sort-Object FullName -Descending | Select-Object -First 1).FullName
    }
}
if (-not $D8) {
    $d8_paths = Get-ChildItem "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\build-tools" -Recurse -Filter "d8.bat"
    if ($d8_paths) {
        $D8 = ($d8_paths | Sort-Object FullName -Descending | Select-Object -First 1).FullName
    } else {
        Write-Error "d8.bat not found."
        exit 1
    }
}
Write-Host "Using d8: $D8"

# Find android.jar
$ANDROID_JAR = ""
if ($env:ANDROID_HOME) {
    $jar_paths = Get-ChildItem (Join-Path $env:ANDROID_HOME "platforms") -Recurse -Filter "android.jar"
    if ($jar_paths) {
        $ANDROID_JAR = ($jar_paths | Sort-Object FullName -Descending | Select-Object -First 1).FullName
    }
}
if (-not $ANDROID_JAR) {
    $jar_paths = Get-ChildItem "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\platforms" -Recurse -Filter "android.jar"
    if ($jar_paths) {
        $ANDROID_JAR = ($jar_paths | Sort-Object FullName -Descending | Select-Object -First 1).FullName
    } else {
        Write-Error "android.jar not found."
        exit 1
    }
}
Write-Host "Using android.jar: $ANDROID_JAR"

# Clean
Write-Host "`n=== Cleaning build directories ==="
if (Test-Path $BUILD_DIR) { Remove-Item -Recurse -Force $BUILD_DIR }
if (Test-Path $OUT_DIR) { Remove-Item -Recurse -Force $OUT_DIR }
New-Item -ItemType Directory -Force (Join-Path $BUILD_DIR "java") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $BUILD_DIR "dex") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $BUILD_DIR "zip") | Out-Null
New-Item -ItemType Directory -Force $OUT_DIR | Out-Null

# Step 1: Build native libraries
Write-Host "`n=== Building native libraries ==="
Set-Location $MODULE_DIR
& $NDK_BUILD NDK_PROJECT_PATH="$MODULE_DIR" NDK_OUT="$BUILD_DIR/obj" NDK_LIBS_OUT="$BUILD_DIR/libs" APP_BUILD_SCRIPT="$MODULE_DIR/jni/Android.mk" NDK_APPLICATION_MK="$MODULE_DIR/jni/Application.mk" -j4
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Step 2: Compile Java
Write-Host "`n=== Compiling Java hook payload ==="
Set-Location $SCRIPT_DIR
& javac -source 1.8 -target 1.8 -bootclasspath "$ANDROID_JAR" -d "$BUILD_DIR/java" "$MODULE_DIR/java/com/zygisksim/HookEntry.java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$classFiles = Get-ChildItem "$BUILD_DIR/java" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& $D8 --output "$BUILD_DIR/dex" --min-api 26 $classFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Step 3: Assemble ZIP
Write-Host "`n=== Assembling module ZIP ==="
$ZIP_ROOT = Join-Path $BUILD_DIR "zip"

Copy-Item "$MODULE_DIR/module.prop" $ZIP_ROOT
Copy-Item "$MODULE_DIR/customize.sh" $ZIP_ROOT
Copy-Item "$MODULE_DIR/post-fs-data.sh" $ZIP_ROOT
Copy-Item "$SCRIPT_DIR/webui" "$ZIP_ROOT/webroot" -Recurse
Set-Content -Path "$ZIP_ROOT/config.json" -Value "{}"

Copy-Item "$BUILD_DIR/dex/classes.dex" $ZIP_ROOT

New-Item -ItemType Directory -Force "$ZIP_ROOT/zygisk" | Out-Null
$abiDirs = Get-ChildItem "$BUILD_DIR/libs" -Directory
foreach ($abiDir in $abiDirs) {
    $abi = $abiDir.Name
    $soFile = Join-Path $abiDir.FullName "libzygisksim.so"
    if (Test-Path $soFile) {
        Copy-Item $soFile "$ZIP_ROOT/zygisk/$abi.so"
        Write-Host "  Added: zygisk/$abi.so"
    }
}

$META_DIR = "$ZIP_ROOT/META-INF/com/google/android"
New-Item -ItemType Directory -Force $META_DIR | Out-Null
Set-Content -Path "$META_DIR/updater-script" -Value "#MAGISK"
Set-Content -Path "$META_DIR/update-binary" -Value @"
#!/sbin/sh
umask 022
ui_print() { echo "`$1"; }
require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "*******************************"
  exit 1
}
OUTFD=`$2
ZIPFILE=`$3
mount /data 2>/dev/null
[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ `$MAGISK_VER_CODE -lt 20400 ] && require_new_magisk
install_module
exit 0
"@
# It's better to use bash/sh for update-binary but we are on windows so line endings might be CRLF. Let's fix that.
(Get-Content "$META_DIR/update-binary") -join "`n" + "`n" | Set-Content -NoNewline -Path "$META_DIR/update-binary"

Set-Location $ZIP_ROOT
Compress-Archive -Path "$ZIP_ROOT/*" -DestinationPath "$OUT_DIR/$ZIP_NAME" -Force

Write-Host "`n=== Build complete! ==="
Write-Host "Output: $OUT_DIR/$ZIP_NAME"
