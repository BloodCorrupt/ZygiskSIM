/*
 * ZygiskSIM - Zygisk module to spoof eSIM support system-wide
 *
 * Architecture:
 *   1. Companion process (root) reads classes.dex from module directory
 *   2. preAppSpecialize: fetch DEX data via companion socket
 *   3. postAppSpecialize: load DEX via InMemoryDexClassLoader, call HookEntry.init()
 *   4. HookEntry performs ArtMethod swaps to hook EuiccManager methods
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
// ArtMethod swap utilities
// =====================================================================

static size_t art_method_size = 0;

/**
 * Calculate ArtMethod struct size at runtime by measuring distance
 * between two adjacent static methods in the hook class.
 */
static void calculateArtMethodSize(JNIEnv *env, jclass hookClass) {
    jmethodID m1 = env->GetStaticMethodID(hookClass, "sizeHelper1", "()V");
    jmethodID m2 = env->GetStaticMethodID(hookClass, "sizeHelper2", "()V");

    if (m1 == nullptr || m2 == nullptr) {
        LOGE("Failed to find size helper methods, using default ArtMethod size");
        art_method_size = (sizeof(void*) == 8) ? 40 : 28;
        return;
    }

    art_method_size = (size_t)((uintptr_t)m2 - (uintptr_t)m1);

    // Sanity check
    if (art_method_size < 16 || art_method_size > 512) {
        LOGE("ArtMethod size %zu seems invalid, using default", art_method_size);
        art_method_size = (sizeof(void*) == 8) ? 40 : 28;
    } else {
        LOGI("Detected ArtMethod size: %zu bytes", art_method_size);
    }
}

/**
 * Swap ArtMethod data: copies hook's ArtMethod into target's ArtMethod slot.
 *
 * After the swap, calling the target method will execute the hook's code.
 * This is the core technique used by SandHook, Epic, and other ART hooking frameworks.
 *
 * Parameters:
 *   target  - java.lang.reflect.Method to be hooked
 *   hook    - java.lang.reflect.Method with replacement implementation
 */
static void nativeHookMethod(JNIEnv *env, [[maybe_unused]] jclass clazz,
                              jobject targetMethod, jobject hookMethod) {
    if (art_method_size == 0) {
        LOGE("ArtMethod size not calculated, cannot hook");
        return;
    }

    jmethodID target = env->FromReflectedMethod(targetMethod);
    jmethodID hook = env->FromReflectedMethod(hookMethod);

    if (target == nullptr || hook == nullptr) {
        LOGE("Failed to get ArtMethod pointers");
        return;
    }

    LOGD("Swapping ArtMethod: target=%p hook=%p size=%zu", target, hook, art_method_size);

    // Full ArtMethod memcpy: copy hook's method data into target's slot
    // After this, calls to the target method dispatch to hook's bytecode
    memcpy(reinterpret_cast<void*>(target),
           reinterpret_cast<void*>(hook),
           art_method_size);
}

// JNI native methods to register on the loaded HookEntry class
static JNINativeMethod hookEntryMethods[] = {
    {"nativeHookMethod",
     "(Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;)V",
     reinterpret_cast<void*>(nativeHookMethod)},
};

// =====================================================================
// Companion process (runs as root)
// =====================================================================

/**
 * Read the DEX file from the module directory and send it to the module
 * process via the Unix socket.
 *
 * Protocol: [uint32_t size][uint8_t data[size]]
 * If the file doesn't exist, sends size=0.
 */
static void companion_handler(int fd) {
    // Read the module directory path from the client (unused for now, use fixed path)
    // Open DEX file
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
        // Get app process name for logging
        const char *process = env->GetStringUTFChars(args->nice_name, nullptr);
        LOGD("preAppSpecialize: %s", process ? process : "unknown");
        if (process) env->ReleaseStringUTFChars(args->nice_name, process);

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
        dex_data = new uint8_t[size];
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
            delete[] dex_data;
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

        // 5. Calculate ArtMethod size using helper methods in HookEntry
        calculateArtMethodSize(env, hook_class);

        // 6. Register native methods on HookEntry
        jint reg_result = env->RegisterNatives(
            hook_class, hookEntryMethods,
            sizeof(hookEntryMethods) / sizeof(hookEntryMethods[0]));

        if (reg_result != JNI_OK) {
            LOGE("Failed to register native methods (result=%d)", reg_result);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            return;
        }

        // 7. Call HookEntry.init(logDir) to set up hooks
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
