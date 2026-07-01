/*
 * ZygiskSIM - Zygisk module to spoof eSIM support system-wide
 *
 * Architecture:
 *   1. Companion process (root) reads classes.dex from module directory
 *   2. preAppSpecialize: fetch DEX data via companion socket
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
        // Connect to root companion to fetch the DEX payload
        int companion_fd = api->connectCompanion();
        if (companion_fd < 0) {
            LOGE("Failed to connect to companion process");
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
        // Using malloc instead of new[] to avoid C++ runtime issues with APP_STL=none/c++_static
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
            LOGI("DEX payload loaded: %u bytes", size);
        }
    }

    void postAppSpecialize([[maybe_unused]] const zygisk::AppSpecializeArgs *args) override {
        if (dex_data == nullptr || dex_size == 0) {
            LOGD("No DEX payload, skipping hooks");
            return;
        }

        LOGI("Loading DEX and initializing hooks...");

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

        LOGI("HookEntry class loaded successfully");

        // 5. Call HookEntry.init(logDir) to set up hooks
        jmethodID init_method = env->GetStaticMethodID(
            hook_class, "init", "(Ljava/lang/String;)V");
        jstring log_dir = env->NewStringUTF("/data/adb/modules/zygisksim/logs");

        env->CallStaticVoidMethod(hook_class, init_method, log_dir);

        if (env->ExceptionCheck()) {
            LOGE("Exception during HookEntry.init()");
            env->ExceptionDescribe();
            env->ExceptionClear();
        } else {
            LOGI("Hook initialization complete");
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
};

REGISTER_ZYGISK_MODULE(ZygiskSIMModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
