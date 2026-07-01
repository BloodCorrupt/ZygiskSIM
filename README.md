# ZygiskSIM

A Zygisk-based Magisk module that **spoofs eSIM support system-wide**. Equivalent to the [SureSIM](https://github.com/thatmarcel/suresim) Xposed/LSPosed module, but implemented as a native Zygisk module — **no LSPosed/Xposed required**.

## What it does

| Hook | Behavior |
|------|----------|
| `EuiccManager.isEnabled()` | Always returns `true` — apps see eSIM as supported |
| `EuiccManager.downloadSubscription(...)` | Logs the activation code to a file, then silently discards (no-op) |
| System feature `android.hardware.telephony.euicc` | Declared via system overlay so `PackageManager.hasSystemFeature()` returns `true` |

### Activation Code Logging

When any app tries to install an eSIM profile, the activation code is logged to:
```
/data/adb/modules/zygisksim/logs/esim_log.txt
```

You can also view logs via logcat:
```bash
adb logcat -s ZygiskSIM
```

## Requirements

- Rooted Android device with **Magisk v20.4+**
- **Zygisk** enabled in Magisk settings
- Android 9+ (API 28) for eSIM API availability
- Android 8+ (API 26) for DEX loading mechanism

## Installation

1. Download `ZygiskSIM-v1.0.zip` from [Releases](../../releases)
2. Open **Magisk Manager** → Modules → Install from storage
3. Select the ZIP file
4. **Reboot**

## Building from source

### Prerequisites

- Android NDK (r21+)
- Android SDK build-tools (for `d8`)
- Android SDK platform (any, for `android.jar`)
- Java JDK 8+
- Linux/macOS/WSL (for the build script)

### Environment Setup

```bash
export ANDROID_HOME=/path/to/android/sdk
export ANDROID_NDK_HOME=/path/to/android/ndk   # or use $ANDROID_HOME/ndk/<version>
```

### Build

```bash
chmod +x build.sh
./build.sh
```

Output: `out/ZygiskSIM-v1.0.zip`

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Zygisk Native Module (main.cpp)                                 │
│                                                                 │
│  onLoad()            → Store Api* and JNIEnv*                   │
│  preAppSpecialize()  → Fetch classes.dex via root companion     │
│  postAppSpecialize() → Load DEX → InMemoryDexClassLoader        │
│                        → Register JNI → Call HookEntry.init()   │
└─────────────────────────┬───────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│ Java DEX Payload (HookEntry.java)                               │
│                                                                 │
│  init()  → Resolve EuiccManager via reflection                  │
│          → nativeHookMethod() to swap ArtMethod structs         │
│                                                                 │
│  hookIsEnabled()              → return true                     │
│  hookDownloadSubscription()   → log activation code + no-op     │
└─────────────────────────────────────────────────────────────────┘

Root Companion Process:
  Reads /data/adb/modules/zygisksim/classes.dex and sends via socket

System Overlay:
  system/etc/permissions/esim_feature.xml → declares eSIM feature
```

### How ArtMethod swapping works

1. The native module calculates `sizeof(ArtMethod)` at runtime using two adjacent static methods
2. `nativeHookMethod(target, hook)` does a `memcpy()` of the hook's ArtMethod data into the target's ArtMethod slot
3. After the swap, any call to the target method executes the hook's bytecode instead
4. This technique is used by many production hooking frameworks (SandHook, Epic, etc.)

## Module structure (installed)

```
/data/adb/modules/zygisksim/
├── module.prop
├── customize.sh
├── post-fs-data.sh
├── classes.dex                      # Java hook payload
├── logs/
│   └── esim_log.txt                 # Activation code log
├── system/
│   └── etc/
│       └── permissions/
│           └── esim_feature.xml     # eSIM feature declaration
└── zygisk/
    ├── arm64-v8a.so                 # Native module (64-bit ARM)
    ├── armeabi-v7a.so               # Native module (32-bit ARM)
    ├── x86.so                       # Native module (x86)
    └── x86_64.so                    # Native module (x86_64)
```

## Troubleshooting

**Module not working after install:**
- Ensure Zygisk is enabled in Magisk settings
- Reboot after installation
- Check `adb logcat -s ZygiskSIM` for errors

**No log file created:**
- Check permissions: `ls -la /data/adb/modules/zygisksim/logs/`
- The directory should be `chmod 0777`

**eSIM still shows as unsupported:**
- Verify module is active: `adb shell ls /data/adb/modules/zygisksim/`
- Check for a `disable` or `remove` file in the module directory
- Some apps may use additional checks beyond `EuiccManager.isEnabled()`

## Credits

- [SureSIM](https://github.com/thatmarcel/suresim) by thatmarcel — original Xposed module concept
- [Magisk](https://github.com/topjohnwu/Magisk) by topjohnwu — Zygisk framework
- [zygisk-module-sample](https://github.com/topjohnwu/zygisk-module-sample) — Zygisk API reference

## License

GPL-3.0
