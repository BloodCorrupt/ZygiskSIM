/*
 * ZygiskSIM - Zygisk module to spoof eSIM support system-wide
 *
 * Architecture:
 *   1. Companion process (root) reads classes.dex AND libpine.so (for ARM)
 *   2. preAppSpecialize: fetch payloads via companion socket
 *   3. postAppSpecialize:
 *      - Loads Java classes.dex into app via InMemoryDexClassLoader
 *      - Registers native ART method hooking helpers via JNI
 *      - Calls HookEntry.init() to install direct ART method hooks,
 *        dynamic proxies, ServiceManager mocks, and Pine (on ARM)
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

#include "zygisk.hpp"
#include "dobby/include/dobby.h"

#define LOG_TAG "ZygiskSIM"
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
// Global Spoof Configuration
// =====================================================================

static char g_spoof_eid[64] = "89049032005008882600033827513789";
static char g_log_dir[512] = "/data/adb/modules/zygisksim/logs";

static void parse_native_config(const char *cfg_str) {
    if (cfg_str == nullptr || strlen(cfg_str) == 0) return;
    const char *eid_key = "\"eid\":";
    const char *p = strstr(cfg_str, eid_key);
    if (p) {
        p += strlen(eid_key);
        while (*p == ' ' || *p == '\"') p++;
        size_t idx = 0;
        while (*p && *p != '\"' && *p != ',' && *p != '}' && idx < sizeof(g_spoof_eid) - 1) {
            g_spoof_eid[idx++] = *p++;
        }
        g_spoof_eid[idx] = '\0';
        LOGI("Native config parsed EID: %s", g_spoof_eid);
    }
}

// =====================================================================
// Activation Code Logging & System Clipboard Helper
// =====================================================================

static void log_activation_code_native(JNIEnv *env, const char *code) {
    if (code == nullptr) return;

    LOGI("========================================");
    LOGI("eSIM DOWNLOAD INTERCEPTED (Direct ART Hook)");
    LOGI("  Activation Code: %s", code);
    LOGI("========================================");

    // 1. Write to esim_log.txt file
    if (g_log_dir[0] != '\0') {
        mkdir(g_log_dir, 0777);
        char log_path[512];
        snprintf(log_path, sizeof(log_path), "%s/esim_log.txt", g_log_dir);
        FILE *f = fopen(log_path, "a");
        if (f) {
            time_t now = time(nullptr);
            char tbuf[64];
            strftime(tbuf, sizeof(tbuf), "%Y-%m-%d %H:%M:%S", localtime(&now));
            fprintf(f, "[%s] eSIM DOWNLOAD INTERCEPTED\n  Activation Code: %s\n", tbuf, code);
            fclose(f);
            chmod(log_path, 0666);
        }
    }

    // 2. Copy activation code to Android System Clipboard
    if (env != nullptr) {
        jclass at_cls = env->FindClass("android/app/ActivityThread");
        if (at_cls != nullptr) {
            jmethodID cur_app_m = env->GetStaticMethodID(at_cls, "currentApplication", "()Landroid/app/Application;");
            if (cur_app_m != nullptr) {
                jobject app = env->CallStaticObjectMethod(at_cls, cur_app_m);
                if (app != nullptr) {
                    jclass ctx_cls = env->FindClass("android/content/Context");
                    jfieldID clip_srv_fid = env->GetStaticFieldID(ctx_cls, "CLIPBOARD_SERVICE", "Ljava/lang/String;");
                    if (clip_srv_fid != nullptr) {
                        jstring clip_srv_str = (jstring)env->GetStaticObjectField(ctx_cls, clip_srv_fid);
                        jmethodID get_srv_m = env->GetMethodID(ctx_cls, "getSystemService", "(Ljava/lang/String;)Ljava/lang/Object;");
                        if (get_srv_m != nullptr) {
                            jobject clip_mgr = env->CallObjectMethod(app, get_srv_m, clip_srv_str);
                            if (clip_mgr != nullptr) {
                                jclass clip_mgr_cls = env->GetObjectClass(clip_mgr);
                                jclass clip_data_cls = env->FindClass("android/content/ClipData");
                                jmethodID new_plain_text_m = env->GetStaticMethodID(
                                    clip_data_cls, "newPlainText", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;");
                                if (new_plain_text_m != nullptr) {
                                    jstring label = env->NewStringUTF("Encoded eSIM activation code");
                                    jstring text = env->NewStringUTF(code);
                                    jobject clip_data = env->CallStaticObjectMethod(clip_data_cls, new_plain_text_m, label, text);
                                    jmethodID set_clip_m = env->GetMethodID(clip_mgr_cls, "setPrimaryClip", "(Landroid/content/ClipData;)V");
                                    if (set_clip_m != nullptr && clip_data != nullptr) {
                                        env->CallVoidMethod(clip_mgr, set_clip_m, clip_data);
                                        LOGI("Activation code successfully copied to system clipboard");
                                    }
                                    env->DeleteLocalRef(label);
                                    env->DeleteLocalRef(text);
                                    if (clip_data) env->DeleteLocalRef(clip_data);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
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

    // Unprotect memory pages
    if (mprotect((void*)PAGE_START(target_art), PAGE_SIZE * 2, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("native_hook_method: mprotect target failed: %s", strerror(errno));
    }
    if (mprotect((void*)PAGE_START(hook_art), PAGE_SIZE * 2, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("native_hook_method: mprotect hook failed: %s", strerror(errno));
    }

    // Read hook JNI entry point and quick compiled code entry point
    void *hook_jni = *(void**)((char*)hook_art + g_offset_jni);
    void *hook_quick = *(void**)((char*)hook_art + g_offset_quick_code);

    // Read target current flags
    uint32_t target_flags = *(uint32_t*)((char*)target_art + g_offset_access_flags);

    // Write hook entry points to target ArtMethod
    *(void**)((char*)target_art + g_offset_jni) = hook_jni;
    *(void**)((char*)target_art + g_offset_quick_code) = hook_quick;

    // Set kAccNative (0x0100) and kAccPublic (0x0001), clear interpreter/JIT optimization flags
    uint32_t new_flags = (target_flags | 0x0100 /* kAccNative */ | 0x0001 /* kAccPublic */)
                         & ~0x0002 /* ~kAccPrivate */
                         & ~0x0004 /* ~kAccProtected */
                         & ~0x01000000 /* ~kAccCompileDontBother */
                         & ~0x40000000 /* ~kAccFastInterpreterToInterpreterInvoke */
                         & ~0x00080000 /* ~kAccFastNative */
                         & ~0x00100000 /* ~kAccCriticalNative */;
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
// Native Hook Implementations (Direct JNI execution, Zero Bytecode)
// =====================================================================

static jboolean JNICALL hook_native_isEnabled(JNIEnv *, [[maybe_unused]] jobject thiz) {
    LOGI("Spoofed EuiccManager.isEnabled() -> true (Direct ART Hook)");
    return JNI_TRUE;
}

static jstring JNICALL hook_native_getEid(JNIEnv *env, [[maybe_unused]] jobject thiz) {
    LOGI("Spoofed EuiccManager.getEid() -> %s (Direct ART Hook)", g_spoof_eid);
    return env->NewStringUTF(g_spoof_eid);
}

static jobject JNICALL hook_native_getEuiccInfo(JNIEnv *env, [[maybe_unused]] jobject thiz) {
    LOGI("Spoofed EuiccManager.getEuiccInfo() (Direct ART Hook)");
    jclass info_cls = env->FindClass("android/telephony/euicc/EuiccInfo");
    if (info_cls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(info_cls, "<init>", "(Ljava/lang/String;)V");
    if (ctor == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jstring ver = env->NewStringUTF("1.0");
    jobject res = env->NewObject(info_cls, ctor, ver);
    env->DeleteLocalRef(ver);
    return res;
}

static jobject JNICALL hook_native_forActivationCode(
    JNIEnv *env, [[maybe_unused]] jclass clazz, jstring code) {
    if (code != nullptr) {
        const char *code_str = env->GetStringUTFChars(code, nullptr);
        if (code_str != nullptr) {
            log_activation_code_native(env, code_str);
            env->ReleaseStringUTFChars(code, code_str);
        }
    }
    jclass sub_cls = env->FindClass("android/telephony/euicc/DownloadableSubscription");
    if (sub_cls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(sub_cls, "<init>", "(Ljava/lang/String;)V");
    if (ctor != nullptr) {
        return env->NewObject(sub_cls, ctor, code);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    return nullptr;
}

static void JNICALL hook_native_downloadSubscription(
    JNIEnv *env, [[maybe_unused]] jobject thiz, jobject sub, [[maybe_unused]] jboolean switch_after_download, jobject callback_intent) {
    LOGI("========================================");
    LOGI("Intercepted EuiccManager.downloadSubscription() (Direct ART Hook)");
    LOGI("========================================");

    if (sub != nullptr) {
        jclass sub_cls = env->GetObjectClass(sub);
        if (sub_cls != nullptr) {
            jmethodID get_code = env->GetMethodID(sub_cls, "getEncodedActivationCode", "()Ljava/lang/String;");
            if (get_code != nullptr) {
                jstring code_jstr = (jstring)env->CallObjectMethod(sub, get_code);
                if (code_jstr != nullptr) {
                    const char *code_str = env->GetStringUTFChars(code_jstr, nullptr);
                    if (code_str != nullptr) {
                        log_activation_code_native(env, code_str);
                        env->ReleaseStringUTFChars(code_jstr, code_str);
                    }
                }
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    if (callback_intent != nullptr) {
        jclass pi_cls = env->GetObjectClass(callback_intent);
        if (pi_cls != nullptr) {
            jclass intent_cls = env->FindClass("android/content/Intent");
            jmethodID intent_ctor = env->GetMethodID(intent_cls, "<init>", "()V");
            jobject intent_obj = env->NewObject(intent_cls, intent_ctor);

            jclass at_cls = env->FindClass("android/app/ActivityThread");
            jmethodID cur_app_m = env->GetStaticMethodID(at_cls, "currentApplication", "()Landroid/app/Application;");
            jobject app_ctx = env->CallStaticObjectMethod(at_cls, cur_app_m);

            jmethodID send_m = env->GetMethodID(pi_cls, "send", "(Landroid/content/Context;ILandroid/content/Intent;)V");
            if (send_m != nullptr && app_ctx != nullptr) {
                env->CallVoidMethod(callback_intent, send_m, app_ctx, 0, intent_obj);
                LOGI("Triggered success callback intent for subscription download");
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
}

static jint JNICALL hook_native_getCardIdForDefaultEuicc(JNIEnv *, [[maybe_unused]] jobject thiz) {
    LOGI("Spoofed TelephonyManager.getCardIdForDefaultEuicc() -> 1 (Direct ART Hook)");
    return 1;
}

static const JNINativeMethod G_HOOK_ENTRY_METHODS[] = {
    { "nativeHookMethod", "(Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;)Z", (void*)native_hook_method },
    { "nativeProbeOffsets", "(Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;)V", (void*)native_probe_offsets },
    { "hook_native_isEnabled", "(Ljava/lang/Object;)Z", (void*)hook_native_isEnabled },
    { "hook_native_getEid", "(Ljava/lang/Object;)Ljava/lang/String;", (void*)hook_native_getEid },
    { "hook_native_getEuiccInfo", "(Ljava/lang/Object;)Ljava/lang/Object;", (void*)hook_native_getEuiccInfo },
    { "hook_native_forActivationCode", "(Ljava/lang/String;)Ljava/lang/Object;", (void*)hook_native_forActivationCode },
    { "hook_native_downloadSubscription", "(Ljava/lang/Object;Ljava/lang/Object;ZLjava/lang/Object;)V", (void*)hook_native_downloadSubscription },
    { "hook_native_getCardIdForDefaultEuicc", "(Ljava/lang/Object;)I", (void*)hook_native_getCardIdForDefaultEuicc },
};

// =====================================================================
// Helper: read/write bytes from/to fd
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
    send_file(fd, "/data/adb/modules/zygisksim/classes.dex");

    // 2. Check engine configuration (set during installation by customize.sh)
    bool use_pine = true;
    int engine_fd = open("/data/adb/modules/zygisksim/engine", O_RDONLY);
    if (engine_fd >= 0) {
        char buf[32] = {};
        read(engine_fd, buf, sizeof(buf) - 1);
        close(engine_fd);
        if (strstr(buf, "dobby") != nullptr) {
            use_pine = false;
        }
    }
#if defined(__i386__) || defined(__x86_64__)
    use_pine = false; // Always use Dobby / Direct ART hook on x86/x86_64 emulators
#endif

    // Send libpine.so only if Pine engine is active and on ARM
    if (use_pine) {
#if defined(__aarch64__)
        send_file(fd, "/data/adb/modules/zygisksim/system/lib64/libpine.so");
#elif defined(__arm__)
        send_file(fd, "/data/adb/modules/zygisksim/system/lib/libpine.so");
#else
        uint32_t zero = 0;
        write(fd, &zero, sizeof(zero));
#endif
    } else {
        uint32_t zero = 0;
        write(fd, &zero, sizeof(zero));
    }

    // 3. Send config.json (optional)
    send_file(fd, "/data/adb/modules/zygisksim/config.json");
}

// =====================================================================
// Zygisk module implementation
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

        // --- Read Pine library payload (ARM only) ---
        uint32_t pine_sz = 0;
        if (read_exact(companion_fd, &pine_sz, sizeof(pine_sz)) && pine_sz > 0) {
            pine_data = (uint8_t *)malloc(pine_sz);
            pine_size = pine_sz;
            if (!read_exact(companion_fd, pine_data, pine_sz)) {
                LOGE("Incomplete Pine library read");
                free(pine_data); pine_data = nullptr; pine_size = 0;
            } else {
                LOGI("Pine library received: %u bytes", pine_sz);
            }
        } else {
            LOGI("Pine library omitted for this architecture");
        }

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

        LOGI("Initializing hooks for target app %s...", package_name);

        // ----------------------------------------------------------
        // Step 1: Write libpine.so to app's cache directory (if ARM/Pine)
        // ----------------------------------------------------------
        char pine_path[512] = {};
        if (pine_data != nullptr && pine_size > 0 && app_data_dir[0] != '\0') {
            mkdir(app_data_dir, 0755);
            char cache_dir[512];
            snprintf(cache_dir, sizeof(cache_dir), "%s/cache", app_data_dir);
            mkdir(cache_dir, 0755);

            snprintf(pine_path, sizeof(pine_path), "%s/cache/libpine_zygisksim.so", app_data_dir);

            struct stat st;
            if (stat(pine_path, &st) == 0 && st.st_size == (off_t)pine_size) {
                LOGI("Pine library already exists, skipping write");
            } else {
                char tmp_path[512];
                snprintf(tmp_path, sizeof(tmp_path), "%s/cache/libpine_zygisksim.so.%d.tmp", app_data_dir, getpid());
                int pine_fd = open(tmp_path, O_WRONLY | O_CREAT | O_TRUNC, 0755);
                if (pine_fd >= 0) {
                    if (write_all(pine_fd, pine_data, pine_size)) {
                        close(pine_fd);
                        rename(tmp_path, pine_path);
                        LOGI("Wrote libpine.so to %s (%u bytes)", pine_path, pine_size);
                    } else {
                        close(pine_fd);
                        unlink(tmp_path);
                    }
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

        // ----------------------------------------------------------
        // Step 3: Load HookEntry class and register native ART hooks
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

        // ----------------------------------------------------------
        // Step 4: Call HookEntry.init(logDir, pineLibPath, configJson)
        // ----------------------------------------------------------
        char log_dir[512];
        if (app_data_dir[0] != '\0') {
            snprintf(log_dir, sizeof(log_dir), "%s/cache/zygisksim_logs", app_data_dir);
        } else {
            snprintf(log_dir, sizeof(log_dir), "/data/adb/modules/zygisksim/logs");
        }
        strncpy(g_log_dir, log_dir, sizeof(g_log_dir) - 1);

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
            LOGE("Exception during HookEntry.init() — clearing to prevent crash");
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
