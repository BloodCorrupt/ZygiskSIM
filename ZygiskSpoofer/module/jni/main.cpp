/*
 * Zygisk Spoofer - Universal Zygisk Device Identifier Spoofer
 *
 * Targets specific apps to spoof:
 *   1. Android ID (Settings.Secure.ANDROID_ID)
 *   2. GSF ID (Google Services Framework ID via ContentResolver)
 *   3. Ads ID (Google Advertising ID / GAID)
 *   4. App Set ID (GMS AppSetIdInfo)
 *   5. Media DRM ID (MediaDrm Device Unique ID)
 */

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <time.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "zygisk.hpp"

#define LOG_TAG "ZygiskSpoofer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#ifndef PAGE_SIZE
#define PAGE_SIZE 4096
#endif

#define PAGE_START(addr) ((uintptr_t)(addr) & ~(PAGE_SIZE - 1))

// =====================================================================
// Target process list
// =====================================================================

static const char *DEFAULT_TARGETS[] = {
    "travel.eskimo.esim",           // Eskimo eSIM
    "com.wonet.usims",              // USIMs
    "com.airalo.android",           // Airalo eSIM
    "com.trustroam",                // Trustroam eSIM
    "com.nomad.app",                // Nomad eSIM
    "com.holafly.android",          // Holafly eSIM
    "com.samsung.android.euicc",    // Samsung eSIM manager
    nullptr  // sentinel
};

static std::vector<std::string> g_target_packages;

static bool is_target_process(const char *package_name) {
    if (package_name == nullptr) return false;

    // Check dynamic targets from config if parsed
    for (const auto &pkg : g_target_packages) {
        if (pkg == package_name) return true;
    }

    // Check defaults
    for (int i = 0; DEFAULT_TARGETS[i] != nullptr; i++) {
        if (strcmp(package_name, DEFAULT_TARGETS[i]) == 0) {
            return true;
        }
    }
    return false;
}

// Global configuration storage
static char g_log_dir[512] = "/data/adb/modules/zygisksspoofer/logs";
static char g_android_id[64] = "f5ff848873c1a17d";
static bool g_android_id_enabled = true;
static char g_media_drm_id[128] = "c32f4a943fe9840c2e6f554dcd6d6987bff8a56ca6e406d00435578214c5547c";
static bool g_media_drm_id_enabled = true;

// Helper to parse simple JSON
static void parse_native_config(const char *cfg_str) {
    if (cfg_str == nullptr || strlen(cfg_str) == 0) return;

    // Extract android_id
    const char *aid_key = "\"android_id\"";
    const char *p = strstr(cfg_str, aid_key);
    if (p) {
        const char *en_key = "\"enabled\":";
        const char *ep = strstr(p, en_key);
        if (ep && ep < p + 60) {
            g_android_id_enabled = (strstr(ep + strlen(en_key), "false") == nullptr);
        }
        const char *val_key = "\"value\":";
        const char *vp = strstr(p, val_key);
        if (vp) {
            vp += strlen(val_key);
            while (*vp == ' ' || *vp == '\"') vp++;
            size_t idx = 0;
            while (*vp && *vp != '\"' && *vp != ',' && *vp != '}' && idx < sizeof(g_android_id) - 1) {
                g_android_id[idx++] = *vp++;
            }
            g_android_id[idx] = '\0';
        }
    }

    // Extract media_drm_id
    const char *drm_key = "\"media_drm_id\"";
    p = strstr(cfg_str, drm_key);
    if (p) {
        const char *en_key = "\"enabled\":";
        const char *ep = strstr(p, en_key);
        if (ep && ep < p + 60) {
            g_media_drm_id_enabled = (strstr(ep + strlen(en_key), "false") == nullptr);
        }
        const char *val_key = "\"value\":";
        const char *vp = strstr(p, val_key);
        if (vp) {
            vp += strlen(val_key);
            while (*vp == ' ' || *vp == '\"') vp++;
            size_t idx = 0;
            while (*vp && *vp != '\"' && *vp != ',' && *vp != '}' && idx < sizeof(g_media_drm_id) - 1) {
                g_media_drm_id[idx++] = *vp++;
            }
            g_media_drm_id[idx] = '\0';
        }
    }

    LOGI("Native config parsed - Android ID: [%s] %s, Media DRM ID: [%s] %s",
         g_android_id_enabled ? "ON" : "OFF", g_android_id,
         g_media_drm_id_enabled ? "ON" : "OFF", g_media_drm_id);
}

// =====================================================================
// Universal ART Method Hooking Engine (x86, x86_64, ARM, ARM64)
// =====================================================================

static size_t g_art_method_size = 0;
static size_t g_offset_access_flags = 4;
static size_t g_offset_jni = 0;
static size_t g_offset_quick_code = 0;

static uintptr_t get_art_method_ptr(JNIEnv *env, jobject reflected_method) {
    if (reflected_method == nullptr) return 0;

    // 1. Try reading `private long artMethod` from java.lang.reflect.Executable
    jclass exec_cls = env->FindClass("java/lang/reflect/Executable");
    if (exec_cls != nullptr) {
        jfieldID art_method_fid = env->GetFieldID(exec_cls, "artMethod", "J");
        if (art_method_fid != nullptr) {
            jlong art_ptr = env->GetLongField(reflected_method, art_method_fid);
            if (art_ptr != 0) {
                return (uintptr_t)art_ptr;
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    // 2. Try directly on Method class
    jclass meth_cls = env->GetObjectClass(reflected_method);
    if (meth_cls != nullptr) {
        jfieldID art_method_fid = env->GetFieldID(meth_cls, "artMethod", "J");
        if (art_method_fid != nullptr) {
            jlong art_ptr = env->GetLongField(reflected_method, art_method_fid);
            if (art_ptr != 0) {
                return (uintptr_t)art_ptr;
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    // 3. Fallback to JNI FromReflectedMethod
    jmethodID mid = env->FromReflectedMethod(reflected_method);
    return (uintptr_t)mid;
}

static void init_art_offsets(JNIEnv *env, jobject m1, jobject m2) {
    uintptr_t a1 = get_art_method_ptr(env, m1);
    uintptr_t a2 = get_art_method_ptr(env, m2);

    if (a1 != 0 && a2 != 0 && a1 != a2) {
        size_t diff = (a2 > a1) ? (a2 - a1) : (a1 - a2);
        if (diff >= 16 && diff <= 64) {
            g_art_method_size = diff;
            LOGI("Probed ArtMethod size: %zu bytes", g_art_method_size);
        }
    }

    if (g_art_method_size == 0) {
#if defined(__x86_64__) || defined(__aarch64__)
        g_art_method_size = 32;
#else
        g_art_method_size = 24;
#endif
        LOGI("Using default ArtMethod size: %zu bytes", g_art_method_size);
    }

    g_offset_access_flags = 4;
    g_offset_jni = g_art_method_size - 2 * sizeof(void*);
    g_offset_quick_code = g_art_method_size - sizeof(void*);

    LOGI("ArtMethod offsets: access_flags=%zu, jni_entry=%zu, quick_code=%zu",
         g_offset_access_flags, g_offset_jni, g_offset_quick_code);
}

static jboolean JNICALL native_hook_method(
    JNIEnv *env, [[maybe_unused]] jclass clazz, jobject target_method, jobject hook_method) {

    if (!target_method || !hook_method) {
        LOGE("native_hook_method: target or hook method is null");
        return JNI_FALSE;
    }

    uintptr_t target_art = get_art_method_ptr(env, target_method);
    uintptr_t hook_art = get_art_method_ptr(env, hook_method);

    if (!target_art || !hook_art) {
        LOGE("native_hook_method: failed to get ArtMethod pointers (target=%p, hook=%p)",
             (void*)target_art, (void*)hook_art);
        return JNI_FALSE;
    }

    if (g_art_method_size == 0) {
        init_art_offsets(env, hook_method, target_method);
    }

    // Make target ArtMethod memory page writable (PROT_READ | PROT_WRITE, NEVER PROT_EXEC to avoid VMA splitting GC crash)
    if (mprotect((void*)PAGE_START(target_art), PAGE_SIZE * 2, PROT_READ | PROT_WRITE) != 0) {
        LOGE("native_hook_method: mprotect target failed: %s", strerror(errno));
    }

    // Read hook JNI entry point and quick compiled code entry point (hook_art is already readable, DO NOT mprotect it)
    void *hook_jni = *(void**)((char*)hook_art + g_offset_jni);
    void *hook_quick = *(void**)((char*)hook_art + g_offset_quick_code);

    // Read target current flags
    uint32_t target_flags = *(uint32_t*)((char*)target_art + g_offset_access_flags);
    uint32_t is_static = (target_flags & 0x0008); // kAccStatic = 0x0008

    // Write hook entry points to target ArtMethod
    *(void**)((char*)target_art + g_offset_jni) = hook_jni;
    *(void**)((char*)target_art + g_offset_quick_code) = hook_quick;

    // Reset hotness count to 0
    if (g_art_method_size >= 16) {
        *(uint16_t*)((char*)target_art + 14) = 0;
    }

    // Set clean access flags: kAccPublic (0x01) | kAccNative (0x100) | kAccCompileDontBother (0x02000000) | is_static
    uint32_t new_flags = 0x0001 /* kAccPublic */ | 0x0100 /* kAccNative */ | 0x02000000 /* kAccCompileDontBother */ | is_static;
    *(uint32_t*)((char*)target_art + g_offset_access_flags) = new_flags;

#if defined(__arm__) || defined(__aarch64__)
    __builtin___clear_cache((char*)target_art, (char*)target_art + g_art_method_size);
#endif

    LOGI("Direct ART Hook installed: %p -> %p (jni=%p, quick=%p, flags=0x%x->0x%x)",
         (void*)target_art, (void*)hook_art, hook_jni, hook_quick, target_flags, new_flags);

    return JNI_TRUE;
}

static void JNICALL native_probe_offsets(
    JNIEnv *env, [[maybe_unused]] jclass clazz, jobject m1, jobject m2) {
    init_art_offsets(env, m1, m2);
}

// =====================================================================
// Native Hook Implementations for System Methods
// =====================================================================

// Helper to convert hex string to byte array
static jbyteArray hex_to_byte_array(JNIEnv *env, const char *hex_str) {
    if (!hex_str) return nullptr;
    size_t len = strlen(hex_str);
    size_t byte_len = len / 2;
    jbyteArray arr = env->NewByteArray(byte_len);
    if (!arr) return nullptr;

    jbyte *bytes = (jbyte*)malloc(byte_len);
    for (size_t i = 0; i < byte_len; i++) {
        char buf[3] = { hex_str[i * 2], hex_str[i * 2 + 1], '\0' };
        bytes[i] = (jbyte)strtol(buf, nullptr, 16);
    }
    env->SetByteArrayRegion(arr, 0, byte_len, bytes);
    free(bytes);
    return arr;
}

// Static: Settings.Secure.getString(ContentResolver resolver, String name) -> (JNIEnv*, jclass, jobject, jstring)
static jstring JNICALL hook_native_getString(
    JNIEnv *env, [[maybe_unused]] jclass clazz, [[maybe_unused]] jobject resolver, jstring name) {
    if (g_android_id_enabled && name != nullptr) {
        const char *name_str = env->GetStringUTFChars(name, nullptr);
        if (name_str != nullptr) {
            if (strcmp(name_str, "android_id") == 0) {
                env->ReleaseStringUTFChars(name, name_str);
                LOGI("Spoofed Settings.Secure.getString(android_id) -> %s", g_android_id);
                return env->NewStringUTF(g_android_id);
            }
            env->ReleaseStringUTFChars(name, name_str);
        }
    }
    return nullptr;
}

// Static: Settings.Secure.getStringForUser(ContentResolver resolver, String name, int userHandle)
static jstring JNICALL hook_native_getStringForUser(
    JNIEnv *env, [[maybe_unused]] jclass clazz, [[maybe_unused]] jobject resolver, jstring name, [[maybe_unused]] jint user_handle) {
    if (g_android_id_enabled && name != nullptr) {
        const char *name_str = env->GetStringUTFChars(name, nullptr);
        if (name_str != nullptr) {
            if (strcmp(name_str, "android_id") == 0) {
                env->ReleaseStringUTFChars(name, name_str);
                LOGI("Spoofed Settings.Secure.getStringForUser(android_id) -> %s", g_android_id);
                return env->NewStringUTF(g_android_id);
            }
            env->ReleaseStringUTFChars(name, name_str);
        }
    }
    return nullptr;
}

// Non-static: MediaDrm.getPropertyByteArray(String propertyName) -> (JNIEnv*, jobject, jstring)
static jbyteArray JNICALL hook_native_getPropertyByteArray(
    JNIEnv *env, [[maybe_unused]] jobject thiz, jstring prop_name) {
    if (g_media_drm_id_enabled && prop_name != nullptr) {
        const char *prop_str = env->GetStringUTFChars(prop_name, nullptr);
        if (prop_str != nullptr) {
            if (strcmp(prop_str, "deviceUniqueId") == 0 ||
                strcmp(prop_str, "device_unique_id") == 0) {
                env->ReleaseStringUTFChars(prop_name, prop_str);
                LOGI("Spoofed MediaDrm.getPropertyByteArray(%s) -> %s", prop_str, g_media_drm_id);
                return hex_to_byte_array(env, g_media_drm_id);
            }
            env->ReleaseStringUTFChars(prop_name, prop_str);
        }
    }
    return nullptr;
}

// Non-static: MediaDrm.getPropertyString(String propertyName) -> (JNIEnv*, jobject, jstring)
static jstring JNICALL hook_native_getPropertyString(
    JNIEnv *env, [[maybe_unused]] jobject thiz, jstring prop_name) {
    if (g_media_drm_id_enabled && prop_name != nullptr) {
        const char *prop_str = env->GetStringUTFChars(prop_name, nullptr);
        if (prop_str != nullptr) {
            if (strcmp(prop_str, "deviceUniqueId") == 0 ||
                strcmp(prop_str, "device_unique_id") == 0) {
                env->ReleaseStringUTFChars(prop_name, prop_str);
                LOGI("Spoofed MediaDrm.getPropertyString(%s) -> %s", prop_str, g_media_drm_id);
                return env->NewStringUTF(g_media_drm_id);
            }
            env->ReleaseStringUTFChars(prop_name, prop_str);
        }
    }
    return nullptr;
}

static const JNINativeMethod G_HOOK_ENTRY_METHODS[] = {
    { "nativeHookMethod", "(Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;)Z", (void*)native_hook_method },
    { "nativeProbeOffsets", "(Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;)V", (void*)native_probe_offsets },
    { "hook_native_getString", "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;", (void*)hook_native_getString },
    { "hook_native_getStringForUser", "(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;", (void*)hook_native_getStringForUser },
    { "hook_native_getPropertyByteArray", "(Ljava/lang/String;)[B", (void*)hook_native_getPropertyByteArray },
    { "hook_native_getPropertyString", "(Ljava/lang/String;)Ljava/lang/String;", (void*)hook_native_getPropertyString },
};

// =====================================================================
// Socket helper utilities
// =====================================================================

static bool read_exact(int fd, void *buf, size_t count) {
    uint8_t *p = (uint8_t *)buf;
    size_t remaining = count;
    while (remaining > 0) {
        ssize_t n = read(fd, p, remaining);
        if (n <= 0) return false;
        p += n;
        remaining -= (size_t)n;
    }
    return true;
}

static void send_file(int socket_fd, const char *path) {
    int file_fd = open(path, O_RDONLY);
    if (file_fd < 0) {
        LOGD("Companion: cannot open %s (skipping)", path);
        uint32_t zero = 0;
        write(socket_fd, &zero, sizeof(zero));
        return;
    }

    struct stat st;
    fstat(file_fd, &st);
    uint32_t size = static_cast<uint32_t>(st.st_size);
    LOGI("Companion: sending %s (%u bytes)", path, size);

    write(socket_fd, &size, sizeof(size));

    uint8_t buf[4096];
    uint32_t remaining = size;
    while (remaining > 0) {
        ssize_t n = read(file_fd, buf, (remaining < sizeof(buf)) ? remaining : sizeof(buf));
        if (n <= 0) break;
        write(socket_fd, buf, static_cast<size_t>(n));
        remaining -= static_cast<uint32_t>(n);
    }
    close(file_fd);
}

// =====================================================================
// Companion process (runs as root)
// =====================================================================

static void companion_handler(int fd) {
    // 1. Send classes.dex
    send_file(fd, "/data/adb/modules/zygisksspoofer/classes.dex");

    // 2. Send config.json
    send_file(fd, "/data/adb/modules/zygisksspoofer/config.json");
}

// =====================================================================
// Zygisk module class
// =====================================================================

class ZygiskSpooferModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        const char *raw_name = nullptr;
        if (args->nice_name) {
            raw_name = env->GetStringUTFChars(args->nice_name, nullptr);
        }

        if (raw_name == nullptr || !is_target_process(raw_name)) {
            LOGD("Skipping non-target process: %s", raw_name ? raw_name : "(null)");
            if (raw_name) env->ReleaseStringUTFChars(args->nice_name, raw_name);
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        LOGI("Target process detected: %s — loading Zygisk Spoofer payloads", raw_name);

        strncpy(package_name, raw_name, sizeof(package_name) - 1);
        package_name[sizeof(package_name) - 1] = '\0';
        env->ReleaseStringUTFChars(args->nice_name, raw_name);

        if (args->app_data_dir) {
            const char *dir = env->GetStringUTFChars(args->app_data_dir, nullptr);
            if (dir) {
                strncpy(app_data_dir, dir, sizeof(app_data_dir) - 1);
                app_data_dir[sizeof(app_data_dir) - 1] = '\0';
                env->ReleaseStringUTFChars(args->app_data_dir, dir);
            }
        }

        // Connect to companion
        int companion_fd = api->connectCompanion();
        if (companion_fd < 0) {
            LOGE("Failed to connect to companion process");
            return;
        }

        // --- Read DEX payload ---
        uint32_t dex_sz = 0;
        if (!read_exact(companion_fd, &dex_sz, sizeof(dex_sz)) || dex_sz == 0) {
            LOGE("Failed to read DEX size from companion");
            close(companion_fd);
            return;
        }
        dex_data = (uint8_t *)malloc(dex_sz);
        dex_size = dex_sz;
        if (!read_exact(companion_fd, dex_data, dex_sz)) {
            LOGE("Incomplete DEX read");
            free(dex_data); dex_data = nullptr; dex_size = 0;
            close(companion_fd);
            return;
        }
        LOGI("DEX payload received: %u bytes", dex_sz);

        // --- Read config.json payload ---
        uint32_t cfg_sz = 0;
        if (read_exact(companion_fd, &cfg_sz, sizeof(cfg_sz)) && cfg_sz > 0) {
            config_data = (uint8_t *)malloc(cfg_sz + 1);
            config_size = cfg_sz;
            if (read_exact(companion_fd, config_data, cfg_sz)) {
                config_data[cfg_sz] = '\0';
                LOGI("Config received: %u bytes", cfg_sz);
                parse_native_config((const char *)config_data);
            } else {
                free(config_data); config_data = nullptr; config_size = 0;
            }
        }

        close(companion_fd);
    }

    void postAppSpecialize([[maybe_unused]] const zygisk::AppSpecializeArgs *args) override {
        if (dex_data == nullptr || dex_size == 0) {
            return;
        }

        LOGI("Initializing Zygisk Spoofer for target app %s...", package_name);

        // Step 1: Load DEX via InMemoryDexClassLoader
        jobject dex_buffer = env->NewDirectByteBuffer(dex_data, dex_size);
        if (dex_buffer == nullptr) {
            LOGE("Failed to create ByteBuffer for DEX data");
            return;
        }

        jclass cl_class = env->FindClass("java/lang/ClassLoader");
        jmethodID get_system_cl = env->GetStaticMethodID(
            cl_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        jobject system_cl = env->CallStaticObjectMethod(cl_class, get_system_cl);

        jclass dex_cl_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
        if (dex_cl_class == nullptr) {
            LOGE("InMemoryDexClassLoader not available");
            return;
        }

        jmethodID dex_cl_init = env->GetMethodID(
            dex_cl_class, "<init>",
            "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        jobject dex_cl = env->NewObject(dex_cl_class, dex_cl_init, dex_buffer, system_cl);
        if (dex_cl == nullptr) {
            LOGE("Failed to create InMemoryDexClassLoader");
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
            return;
        }

        // Step 2: Load HookEntry class and register native ART hooks
        jmethodID load_class = env->GetMethodID(
            env->GetObjectClass(dex_cl), "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring class_name = env->NewStringUTF("com.zygisksspoofer.HookEntry");
        jclass hook_class = static_cast<jclass>(
            env->CallObjectMethod(dex_cl, load_class, class_name));

        if (hook_class == nullptr) {
            LOGE("Failed to load com.zygisksspoofer.HookEntry class from DEX");
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
            return;
        }

        LOGI("HookEntry class loaded successfully. Registering native methods...");

        jint reg_res = env->RegisterNatives(
            hook_class, G_HOOK_ENTRY_METHODS,
            sizeof(G_HOOK_ENTRY_METHODS) / sizeof(G_HOOK_ENTRY_METHODS[0]));
        if (reg_res != JNI_OK) {
            LOGE("RegisterNatives failed with code %d", reg_res);
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        } else {
            LOGI("Registered %zu native ART hook methods successfully",
                 sizeof(G_HOOK_ENTRY_METHODS) / sizeof(G_HOOK_ENTRY_METHODS[0]));
        }

        // Step 3: Call HookEntry.init(logDir, configJson)
        char log_dir[512];
        if (app_data_dir[0] != '\0') {
            snprintf(log_dir, sizeof(log_dir), "%s/cache/zygisksspoofer_logs", app_data_dir);
        } else {
            snprintf(log_dir, sizeof(log_dir), "/data/adb/modules/zygisksspoofer/logs");
        }
        strncpy(g_log_dir, log_dir, sizeof(g_log_dir) - 1);

        jmethodID init_method = env->GetStaticMethodID(
            hook_class, "init",
            "(Ljava/lang/String;Ljava/lang/String;)V");

        if (init_method == nullptr) {
            LOGE("Failed to find HookEntry.init method");
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
            if (config_data) { free(config_data); config_data = nullptr; }
            return;
        }

        jstring j_log_dir = env->NewStringUTF(log_dir);
        jstring j_config = env->NewStringUTF(
            config_data != nullptr ? (const char *)config_data : "");

        env->CallStaticVoidMethod(hook_class, init_method, j_log_dir, j_config);

        if (env->ExceptionCheck()) {
            LOGE("Exception during Zygisk Spoofer HookEntry.init() — clearing to prevent crash");
            env->ExceptionDescribe();
            env->ExceptionClear();
        } else {
            LOGI("Zygisk Spoofer initialization complete for %s", package_name);
        }

        if (config_data) { free(config_data); config_data = nullptr; }
    }

private:
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
    uint8_t *dex_data = nullptr;
    uint32_t dex_size = 0;
    uint8_t *config_data = nullptr;
    uint32_t config_size = 0;
    char package_name[256] = {};
    char app_data_dir[512] = {};
};

REGISTER_ZYGISK_MODULE(ZygiskSpooferModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
