# Zygisk Spoofer — Universal Zygisk Identifier Spoofer

**Zygisk Spoofer** is a high-performance Zygisk module for Android (compatible with KernelSU, APatch, and Magisk) designed to spoof device identifiers for specific target applications.

---

## ✨ Features

- **5 Device Identifiers Spoofed**:
  1. **ANDROID ID** (`Settings.Secure.ANDROID_ID`)
  2. **GSF ID** (Google Services Framework ID via `ContentResolver`)
  3. **ADS ID** (Google Advertising ID / GAID UUID)
  4. **APP SET ID** (GMS `AppSetIdInfo` UUID)
  5. **MEDIA DRM ID** (`MediaDrm` Device Unique ID / Widevine 32-byte hash)
- **⚡ Universal Direct ART Hook Engine**:
  - Works on all architectures: **arm64-v8a**, **armeabi-v7a**, **x86_64**, and **x86**.
  - Direct JNI pointer swapping with sanitized access flags (`kAccCompileDontBother | kAccNative | kAccPublic`).
  - Safe memory page management compatible with Android 14/15 MarkCompact Userfaultfd GC.
- **🎮 Action Button Integration**:
  - Click the **Action** button in KernelSU / APatch to instantly randomize all enabled identifiers.
- **🌐 Built-in WebUI**:
  - Sleek dark interface matching the Zygisk Spoofer design.
  - Checkboxes, individual "Random" buttons, "Randomize All", and "Save & Apply".
  - Target application package management list.
- **🎯 Precise Process Targeting**:
  - Only injects and intercepts designated target apps (e.g. `travel.eskimo.esim`, `com.wonet.usims`, `com.airalo.android`, `com.trustroam`, `com.nomad.app`, `com.holafly.android`).
  - Zero overhead on system and non-target processes.

---

## 🏗️ Project Structure

```
ZygiskSpoofer/
├── build.sh                  # Build script (compiles DEX, C++ libs, packages flashable ZIP)
├── module/
│   ├── module.prop           # Magisk / KernelSU module metadata
│   ├── customize.sh          # Installation script
│   ├── action.sh             # Action button handler
│   ├── config.json           # Default JSON configuration
│   ├── jni/
│   │   ├── Android.mk        # NDK build file
│   │   ├── Application.mk    # Multi-ABI configuration
│   │   ├── zygisk.hpp        # Zygisk API header
│   │   └── main.cpp          # Zygisk native entry & Direct ART hook engine
│   └── java/
│       └── com/zygisksspoofer/
│           └── HookEntry.java# Java hook implementation
└── webui/
    └── index.html            # WebUI dashboard
```

---

## 🚀 Building & Installation

### Prerequisites
- Android NDK (r25+)
- Android SDK Build-Tools (for `d8` and `android.jar`)
- JDK 8+
- `zip`

### Build Command
```bash
chmod +x build.sh
./build.sh
```

Output: `out/ZygiskSpoofer-v1.0.0.zip`

### Installation
1. Flash `ZygiskSpoofer-v1.0.0.zip` in **KernelSU**, **APatch**, or **Magisk**.
2. Reboot device.
3. Open the **Action** button or WebUI in your manager to configure and randomize identifiers.
