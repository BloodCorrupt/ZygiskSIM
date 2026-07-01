/*
 * ZygiskSIM - Zygisk module to spoof eSIM support system-wide
 *
 * Architecture:
 *   1. Companion process (root) reads classes.dex from module directory
 *   2. preAppSpecialize: fetch DEX data via companion socket (only for target processes)
 *   3. postAppSpecialize: load DEX via InMemoryDexClassLoader, call HookEntry.init()
 */

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
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

/**
 * List of package names that should receive the eSIM spoofing hooks.
 * Add any eSIM app that needs to see the device as eSIM-capable.
 */
static const char *TARGET_PACKAGES[] = {
    "travel.eskimo.esim",           // Eskimo eSIM
    "com.airalo.android",           // Airalo eSIM
    "com.trustroam",                // Trustroam eSIM
    "com.nomad.app",                // Nomad eSIM
    "com.holafly.android",          // Holafly eSIM
    "com.samsung.android.euicc",    // Samsung eSIM manager
    "com.android.phone",            // System phone/telephony process
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
// Companion process (runs as root)
// =====================================================================

/**
 * Read the DEX file from the module directory and send it to the module
 * process via the Unix socket.
 */
static void companion_handler(int fd) {
    const char *dex_path = "/data/adb/modules/zygisksim/classes.dex";
    int dex_fd = open(dex_path, O_RDONLY);

    if (dex_fd < 0) {
        LOGE("Companion: cannot open %s", dex_path);
        uint32_t zero = 0;
        write(fd, &zero, sizeof(zero));
        return;
    }

    // Get file size
    struct stat st;
    fstat(dex_fd, &st);
    uint32_t size = static_cast<uint32_t>(st.st_size);

    LOGI("Companion: sending DEX (%u bytes)", size);

    // Send size
    write(fd, &size, sizeof(size));

    // Send data in chunks
    uint8_t buf[4096];
    uint32_t remaining = size;
    while (remaining > 0) {
        ssize_t n = read(dex_fd, buf, (remaining < sizeof(buf)) ? remaining : sizeof(buf));
        if (n <= 0) break;
        write(fd, buf, static_cast<size_t>(n));
        remaining -= static_cast<uint32_t>(n);
    }

    close(dex_fd);
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
            // Not a target process — request to unload our library from this process
            LOGD("Skipping non-target process: %s", raw_name ? raw_name : "(null)");
            if (raw_name) env->ReleaseStringUTFChars(args->nice_name, raw_name);
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        LOGI("Target process detected: %s — loading DEX payload", raw_name);

        // Save package name for postAppSpecialize
        strncpy(package_name, raw_name, sizeof(package_name) - 1);
        package_name[sizeof(package_name) - 1] = '\0';
        env->ReleaseStringUTFChars(args->nice_name, raw_name);

        // Save app_data_dir for log path
        if (args->app_data_dir) {
            const char *dir = env->GetStringUTFChars(args->app_data_dir, nullptr);
            if (dir) {
                strncpy(app_data_dir, dir, sizeof(app_data_dir) - 1);
                app_data_dir[sizeof(app_data_dir) - 1] = '\0';
                env->ReleaseStringUTFChars(args->app_data_dir, dir);
            }
        }

        // Connect to root companion to fetch the DEX payload
        int companion_fd = api->connectCompanion();
        if (companion_fd < 0) {
            LOGE("Failed to connect to companion process for %s", package_name);
            return;
        }

        // Read DEX size
        uint32_t size = 0;
        if (read(companion_fd, &size, sizeof(size)) != sizeof(size) || size == 0) {
            LOGE("Failed to read DEX size from companion (size=%u)", size);
            close(companion_fd);
            return;
        }

        // Allocate and read DEX data
        dex_data = (uint8_t *)malloc(size);
        dex_size = size;

        uint32_t received = 0;
        while (received < size) {
            ssize_t n = read(companion_fd, dex_data + received, size - received);
            if (n <= 0) break;
            received += static_cast<uint32_t>(n);
        }

        close(companion_fd);

        if (received != size) {
            LOGE("Incomplete DEX read: got %u of %u bytes", received, size);
            free(dex_data);
            dex_data = nullptr;
            dex_size = 0;
        } else {
            LOGI("DEX payload loaded: %u bytes for %s", size, package_name);
        }
    }

    void postAppSpecialize([[maybe_unused]] const zygisk::AppSpecializeArgs *args) override {
        if (dex_data == nullptr || dex_size == 0) {
            return;
        }

        LOGI("Loading DEX and initializing hooks for %s...", package_name);

        // 1. Create a direct ByteBuffer from the DEX data
        jobject dex_buffer = env->NewDirectByteBuffer(dex_data, dex_size);
        if (dex_buffer == nullptr) {
            LOGE("Failed to create ByteBuffer for DEX data");
            return;
        }

        // 2. Get the system ClassLoader as parent
        jclass cl_class = env->FindClass("java/lang/ClassLoader");
        jmethodID get_system_cl = env->GetStaticMethodID(
            cl_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        jobject system_cl = env->CallStaticObjectMethod(cl_class, get_system_cl);

        // 3. Load DEX via InMemoryDexClassLoader (API 26+)
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
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            return;
        }

        // 4. Load our HookEntry class from the DEX
        jmethodID load_class = env->GetMethodID(
            env->GetObjectClass(dex_cl), "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring class_name = env->NewStringUTF("com.zygisksim.HookEntry");
        jclass hook_class = static_cast<jclass>(
            env->CallObjectMethod(dex_cl, load_class, class_name));

        if (hook_class == nullptr) {
            LOGE("Failed to load HookEntry class from DEX");
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            return;
        }

        LOGI("HookEntry class loaded successfully for %s", package_name);

        // 5. Build a writable log directory path
        //    Use the app's own cache dir so we don't hit SELinux denials
        char log_dir[512];
        if (app_data_dir[0] != '\0') {
            snprintf(log_dir, sizeof(log_dir), "%s/cache/zygisksim_logs", app_data_dir);
        } else {
            snprintf(log_dir, sizeof(log_dir), "/data/adb/modules/zygisksim/logs");
        }

        // 6. Call HookEntry.init(logDir) to set up hooks
        jmethodID init_method = env->GetStaticMethodID(
            hook_class, "init", "(Ljava/lang/String;)V");
        jstring j_log_dir = env->NewStringUTF(log_dir);

        env->CallStaticVoidMethod(hook_class, init_method, j_log_dir);

        if (env->ExceptionCheck()) {
            LOGE("Exception during HookEntry.init() for %s", package_name);
            env->ExceptionDescribe();
            env->ExceptionClear();
        } else {
            LOGI("Hook initialization complete for %s", package_name);
        }

        // Note: we do NOT free dex_data because InMemoryDexClassLoader
        // holds a reference to the ByteBuffer backed by this memory.
        // The memory must remain valid for the lifetime of the process.
    }

private:
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
    uint8_t *dex_data = nullptr;
    uint32_t dex_size = 0;
    char package_name[256] = {};
    char app_data_dir[512] = {};
};

REGISTER_ZYGISK_MODULE(ZygiskSIMModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
