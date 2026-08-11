$ErrorActionPreference = "Stop"

$DIR = $PSScriptRoot
$JNI_DIR = Join-Path $DIR "module/jni"
$TMP_DIR = Join-Path $DIR "tmp_deps"

if (!(Test-Path $TMP_DIR)) {
    New-Item -ItemType Directory -Force -Path $TMP_DIR | Out-Null
}
Set-Location $TMP_DIR

Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/lsposed/lsplant/lsplant/6.4/lsplant-6.4.aar" -OutFile "lsplant.zip"
Expand-Archive -Path "lsplant.zip" -DestinationPath "lsplant_extract" -Force

New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "lsplant/include") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "lsplant/lib/armeabi-v7a") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "lsplant/lib/arm64-v8a") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "lsplant/lib/x86") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "lsplant/lib/x86_64") | Out-Null

Copy-Item -Path "lsplant_extract/prefab/modules/lsplant/include/*" -Destination (Join-Path $JNI_DIR "lsplant/include/") -Recurse -Force
Copy-Item -Path "lsplant_extract/prefab/modules/lsplant/libs/android.armeabi-v7a/liblsplant.so" -Destination (Join-Path $JNI_DIR "lsplant/lib/armeabi-v7a/") -Force
Copy-Item -Path "lsplant_extract/prefab/modules/lsplant/libs/android.arm64-v8a/liblsplant.so" -Destination (Join-Path $JNI_DIR "lsplant/lib/arm64-v8a/") -Force
Copy-Item -Path "lsplant_extract/prefab/modules/lsplant/libs/android.x86/liblsplant.so" -Destination (Join-Path $JNI_DIR "lsplant/lib/x86/") -Force
Copy-Item -Path "lsplant_extract/prefab/modules/lsplant/libs/android.x86_64/liblsplant.so" -Destination (Join-Path $JNI_DIR "lsplant/lib/x86_64/") -Force

Write-Host "Fetching Dobby..."
Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/io/github/vvb2060/ndk/dobby/1.1/dobby-1.1.aar" -OutFile "dobby.zip"
Expand-Archive -Path "dobby.zip" -DestinationPath "dobby_extract" -Force

New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "dobby/include") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "dobby/lib/armeabi-v7a") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "dobby/lib/arm64-v8a") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "dobby/lib/x86") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $JNI_DIR "dobby/lib/x86_64") | Out-Null

Copy-Item -Path "dobby_extract/prefab/modules/dobby/include/*" -Destination (Join-Path $JNI_DIR "dobby/include/") -Recurse -Force
Copy-Item -Path "dobby_extract/prefab/modules/dobby/libs/android.armeabi-v7a/libdobby.a" -Destination (Join-Path $JNI_DIR "dobby/lib/armeabi-v7a/") -Force
Copy-Item -Path "dobby_extract/prefab/modules/dobby/libs/android.arm64-v8a/libdobby.a" -Destination (Join-Path $JNI_DIR "dobby/lib/arm64-v8a/") -Force
Copy-Item -Path "dobby_extract/prefab/modules/dobby/libs/android.x86/libdobby.a" -Destination (Join-Path $JNI_DIR "dobby/lib/x86/") -Force
Copy-Item -Path "dobby_extract/prefab/modules/dobby/libs/android.x86_64/libdobby.a" -Destination (Join-Path $JNI_DIR "dobby/lib/x86_64/") -Force

Write-Host "Done! Prebuilt libraries extracted to module/jni"
