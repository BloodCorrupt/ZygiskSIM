/*
 * ZygiskSIM - Zygisk module to spoof eSIM support system-wide
 *
 * Architecture (LSplant Edition):
 *   1. Companion process reads classes.dex and config.json
 *   2. preAppSpecialize: fetch payloads via companion socket
 *   3. postAppSpecialize: Load DEX, initialize LSplant, register JNI methods,
 *      and call HookEntry.init()
 */

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <android/log.h>
#include <jni.h>

#include "zygisk.hpp"
#include "lsplant.hpp"
#include <dobby.h>
#include "elf_util.h"

#define LOG_TAG "ZygiskSIM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// =====================================================================
// Target process list
// =====================================================================

static const char *TARGET_PACKAGES[] = {
    "travel.eskimo.esim",           // Eskimo eSIM
    "com.wonet.usims",              // USIMs
    "com.airalo.android",           // Airalo eSIM
    "com.trustroam",                // Trustroam eSIM
    "com.nomad.app",                // Nomad eSIM
    "com.holafly.android",          // Holafly eSIM
    "com.samsung.android.euicc",    // Samsung eSIM manager
    nullptr  // sentinel
};

static bool is_target_process(const char *package_name) {
    if (package_name == nullptr) return false;
    for (int i = 0; TARGET_PACKAGES[i] != nullptr; i++) {
        if (strcmp(package_name, TARGET_PACKAGES[i]) == 0) {
            return true;
        }
    }
    return false;
}

// =====================================================================
// Helper: read all bytes from fd
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

static bool write_all(int fd, const void *buf, size_t count) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t remaining = count;
    while (remaining > 0) {
        ssize_t n = write(fd, p, remaining);
        if (n <= 0) return false;
        p += n;
        remaining -= (size_t)n;
    }
    return true;
}

// =====================================================================
// Helper: send a file over socket (companion side)
// =====================================================================

static void send_file(int socket_fd, const char *path) {
    int file_fd = open(path, O_RDONLY);
    if (file_fd < 0) {
        LOGE("Companion: cannot open %s", path);
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
    send_file(fd, "/data/adb/modules/zygisksim/classes.dex");

    // 2. Send config.json (optional)
    send_file(fd, "/data/adb/modules/zygisksim/config.json");
}

// =====================================================================
// JNI Methods for HookEntry (LSplant Java Bridge)
// =====================================================================

extern "C" JNIEXPORT jobject JNICALL
nativeHook(JNIEnv* env, jclass, jobject target, jobject hooker, jobject callback) {
    if (!target || !callback) return nullptr;
    return lsplant::Hook(env, target, hooker, callback);
}

extern "C" JNIEXPORT jboolean JNICALL
nativeDeoptimize(JNIEnv* env, jclass, jobject target) {
    if (!target) return JNI_FALSE;
    return lsplant::Deoptimize(env, target) ? JNI_TRUE : JNI_FALSE;
}

static JNINativeMethod gMethods[] = {
    {"nativeHook", "(Ljava/lang/reflect/Member;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/Object;", (void*)nativeHook},
    {"nativeDeoptimize", "(Ljava/lang/reflect/Member;)Z", (void*)nativeDeoptimize},
};

// =====================================================================
// Zygisk module
// =====================================================================

class ZygiskSIMModule : public zygisk::ModuleBase {
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

        LOGI("Target process detected: %s — loading payloads", raw_name);
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

        // --- Read config.json payload (optional) ---
        uint32_t cfg_sz = 0;
        if (read_exact(companion_fd, &cfg_sz, sizeof(cfg_sz)) && cfg_sz > 0) {
            config_data = (uint8_t *)malloc(cfg_sz + 1);
            config_size = cfg_sz;
            if (read_exact(companion_fd, config_data, cfg_sz)) {
                config_data[cfg_sz] = '\0';
                LOGI("Config received: %u bytes", cfg_sz);
            } else {
                free(config_data); config_data = nullptr; config_size = 0;
            }
        } else {
            LOGD("No config.json found (using defaults)");
        }

        close(companion_fd);
    }

    void postAppSpecialize([[maybe_unused]] const zygisk::AppSpecializeArgs *args) override {
        if (dex_data == nullptr || dex_size == 0) return;

        LOGI("Initializing LSplant for %s...", package_name);

        lsplant::InitInfo info{
            .inline_hooker = [](void* target, void* hooker) -> void* {
                void* backup = nullptr;
                DobbyHook(target, hooker, &backup);
                return backup;
            },
            .inline_unhooker = [](void* target) -> bool {
                return DobbyDestroy(target) == 0;
            },
            .art_symbol_resolver = resolve_art_symbol
        };

        if (!lsplant::Init(env, info)) {
            LOGE("Failed to initialize LSplant");
            return;
        }
        LOGI("LSplant initialized successfully.");

        // ----------------------------------------------------------
        // Load DEX via InMemoryDexClassLoader
        // ----------------------------------------------------------
        jobject dex_buffer = env->NewDirectByteBuffer(dex_data, dex_size);
        jclass cl_class = env->FindClass("java/lang/ClassLoader");
        jmethodID get_system_cl = env->GetStaticMethodID(cl_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        jobject system_cl = env->CallStaticObjectMethod(cl_class, get_system_cl);

        jclass dex_cl_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
        jmethodID dex_cl_init = env->GetMethodID(dex_cl_class, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        jobject dex_cl = env->NewObject(dex_cl_class, dex_cl_init, dex_buffer, system_cl);

        jmethodID load_class = env->GetMethodID(env->GetObjectClass(dex_cl), "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring class_name = env->NewStringUTF("com.zygisksim.HookEntry");
        jclass hook_class = static_cast<jclass>(env->CallObjectMethod(dex_cl, load_class, class_name));

        if (hook_class == nullptr) {
            LOGE("Failed to load HookEntry class");
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
            return;
        }

        // Register JNI Methods
        if (env->RegisterNatives(hook_class, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) != JNI_OK) {
            LOGE("Failed to register native methods for HookEntry");
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
            return;
        }

        // ----------------------------------------------------------
        // Call HookEntry.init(logDir, configJson)
        // ----------------------------------------------------------
        char log_dir[512];
        if (app_data_dir[0] != '\0') {
            snprintf(log_dir, sizeof(log_dir), "%s/cache/zygisksim_logs", app_data_dir);
        } else {
            snprintf(log_dir, sizeof(log_dir), "/data/adb/modules/zygisksim/logs");
        }

        jmethodID init_method = env->GetStaticMethodID(hook_class, "init", "(Ljava/lang/String;Ljava/lang/String;)V");

        if (init_method == nullptr) {
            LOGE("Failed to find HookEntry.init method");
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
            if (config_data) { free(config_data); config_data = nullptr; }
            return;
        }

        jstring j_log_dir = env->NewStringUTF(log_dir);
        jstring j_config = env->NewStringUTF(config_data != nullptr ? (const char *)config_data : "");

        env->CallStaticVoidMethod(hook_class, init_method, j_log_dir, j_config);

        if (env->ExceptionCheck()) {
            LOGE("Exception during HookEntry.init() — clearing");
            env->ExceptionDescribe();
            env->ExceptionClear();
        } else {
            LOGI("Hook initialization complete for %s", package_name);
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

REGISTER_ZYGISK_MODULE(ZygiskSIMModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
