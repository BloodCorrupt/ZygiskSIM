/*
 * ZygiskSIM - Zygisk module to spoof eSIM support system-wide
 *
 * Architecture:
 *   1. Companion process (root) reads classes.dex AND libpine.so from module directory
 *   2. preAppSpecialize: fetch both payloads via companion socket
 *   3. postAppSpecialize: write libpine.so to app cache, load DEX, call HookEntry.init()
 *
 * The Pine library is sent via companion because NeoZygisk does NOT mount
 * module files into /system/lib64/ — so System.loadLibrary("pine") fails.
 */

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <android/log.h>

#include "zygisk.hpp"

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
// Protocol: [uint32_t size][data]  (size=0 if file not found)
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

    // 2. Send libpine.so for the matching ABI
#if defined(__LP64__)
    send_file(fd, "/data/adb/modules/zygisksim/system/lib64/libpine.so");
#else
    send_file(fd, "/data/adb/modules/zygisksim/system/lib/libpine.so");
#endif

    // 3. Send config.json (optional — size=0 if not present)
    send_file(fd, "/data/adb/modules/zygisksim/config.json");
}

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
        // Get the package name to decide if we should inject
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

        // Save app_data_dir
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

        // --- Read Pine library payload ---
        uint32_t pine_sz = 0;
        if (!read_exact(companion_fd, &pine_sz, sizeof(pine_sz)) || pine_sz == 0) {
            LOGE("Failed to read Pine library size from companion");
            close(companion_fd);
            return;
        }
        pine_data = (uint8_t *)malloc(pine_sz);
        pine_size = pine_sz;
        if (!read_exact(companion_fd, pine_data, pine_sz)) {
            LOGE("Incomplete Pine library read");
            free(pine_data); pine_data = nullptr; pine_size = 0;
            close(companion_fd);
            return;
        }
        LOGI("Pine library received: %u bytes", pine_sz);

        // --- Read config.json payload (optional) ---
        uint32_t cfg_sz = 0;
        if (read_exact(companion_fd, &cfg_sz, sizeof(cfg_sz)) && cfg_sz > 0) {
            config_data = (uint8_t *)malloc(cfg_sz + 1);
            config_size = cfg_sz;
            if (read_exact(companion_fd, config_data, cfg_sz)) {
                config_data[cfg_sz] = '\0'; // null-terminate
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
        if (dex_data == nullptr || dex_size == 0) {
            return;
        }

        LOGI("Initializing hooks for %s...", package_name);

        // ----------------------------------------------------------
        // Step 1: Write libpine.so to app's cache directory
        // ----------------------------------------------------------
        char pine_path[512] = {};
        if (pine_data != nullptr && pine_size > 0 && app_data_dir[0] != '\0') {
            // Recursively ensure app data dir and cache dir exist
            // On first launch in work profiles, app_data_dir may not exist yet
            mkdir(app_data_dir, 0755);
            char cache_dir[512];
            snprintf(cache_dir, sizeof(cache_dir), "%s/cache", app_data_dir);
            mkdir(cache_dir, 0755);

            snprintf(pine_path, sizeof(pine_path), "%s/cache/libpine_zygisksim.so", app_data_dir);
            
            struct stat st;
            if (stat(pine_path, &st) == 0 && st.st_size == (off_t)pine_size) {
                LOGI("Pine library already exists and size matches, skipping write");
            } else {
                char tmp_path[512];
                snprintf(tmp_path, sizeof(tmp_path), "%s/cache/libpine_zygisksim.so.%d.tmp", app_data_dir, getpid());
                int pine_fd = open(tmp_path, O_WRONLY | O_CREAT | O_TRUNC, 0755);
                if (pine_fd >= 0) {
                    if (write_all(pine_fd, pine_data, pine_size)) {
                        close(pine_fd);
                        if (rename(tmp_path, pine_path) == 0) {
                            LOGI("Wrote libpine.so to %s (%u bytes) atomically", pine_path, pine_size);
                        } else {
                            LOGE("Failed to rename %s to %s (errno=%d)", tmp_path, pine_path, errno);
                            pine_path[0] = '\0';
                        }
                    } else {
                        LOGE("Failed to write Pine library data");
                        close(pine_fd);
                        unlink(tmp_path);
                        pine_path[0] = '\0';
                    }
                } else {
                    LOGE("Failed to open %s for writing (errno=%d)", tmp_path, errno);
                    pine_path[0] = '\0';
                }
            }
            free(pine_data);
            pine_data = nullptr;
        }

        // ----------------------------------------------------------
        // Step 2: Load DEX via InMemoryDexClassLoader
        // ----------------------------------------------------------
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
            LOGE("InMemoryDexClassLoader not available (requires API 26+)");
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

        // ----------------------------------------------------------
        // Step 3: Load HookEntry class
        // ----------------------------------------------------------
        jmethodID load_class = env->GetMethodID(
            env->GetObjectClass(dex_cl), "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring class_name = env->NewStringUTF("com.zygisksim.HookEntry");
        jclass hook_class = static_cast<jclass>(
            env->CallObjectMethod(dex_cl, load_class, class_name));

        if (hook_class == nullptr) {
            LOGE("Failed to load HookEntry class from DEX");
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
            return;
        }

        LOGI("HookEntry class loaded successfully");

        // ----------------------------------------------------------
        // Step 4: Call HookEntry.init(logDir, pineLibPath, configJson)
        //         Wrapped so any Java exception is caught and cleared,
        //         preventing a crash in the host app.
        // ----------------------------------------------------------
        char log_dir[512];
        if (app_data_dir[0] != '\0') {
            snprintf(log_dir, sizeof(log_dir), "%s/cache/zygisksim_logs", app_data_dir);
        } else {
            snprintf(log_dir, sizeof(log_dir), "/data/adb/modules/zygisksim/logs");
        }

        jmethodID init_method = env->GetStaticMethodID(
            hook_class, "init",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

        if (init_method == nullptr) {
            LOGE("Failed to find HookEntry.init method");
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
            if (config_data) { free(config_data); config_data = nullptr; }
            return;
        }

        jstring j_log_dir = env->NewStringUTF(log_dir);
        jstring j_pine_path = env->NewStringUTF(pine_path);
        jstring j_config = env->NewStringUTF(
            config_data != nullptr ? (const char *)config_data : "");

        env->CallStaticVoidMethod(hook_class, init_method,
            j_log_dir, j_pine_path, j_config);

        if (env->ExceptionCheck()) {
            LOGE("Exception during HookEntry.init() — clearing to prevent app crash");
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
    uint8_t *pine_data = nullptr;
    uint32_t pine_size = 0;
    uint8_t *config_data = nullptr;
    uint32_t config_size = 0;
    char package_name[256] = {};
    char app_data_dir[512] = {};
};

REGISTER_ZYGISK_MODULE(ZygiskSIMModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
