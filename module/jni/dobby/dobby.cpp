#include "dobby.h"
#include <unistd.h>
#include <sys/mman.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#define LOG_TAG "ZygiskSIM-Dobby"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef PAGE_SIZE
#define PAGE_SIZE 4096
#endif

#define PAGE_START(addr) ((uintptr_t)(addr) & ~(PAGE_SIZE - 1))

void *DobbySymbolResolver(const char *image_name, const char *symbol_name) {
    if (symbol_name == nullptr) return nullptr;
    void *handle = RTLD_DEFAULT;
    if (image_name != nullptr) {
        handle = dlopen(image_name, RTLD_NOW);
    }
    void *sym = dlsym(handle, symbol_name);
    if (!sym && image_name != nullptr) {
        sym = dlsym(RTLD_DEFAULT, symbol_name);
    }
    return sym;
}

int DobbyHook(void *function_address, void *replace_call, void **origin_call) {
    if (!function_address || !replace_call) {
        LOGE("DobbyHook: invalid arguments");
        return -1;
    }

    uintptr_t target = (uintptr_t)function_address;
    uintptr_t replace = (uintptr_t)replace_call;
    size_t page_start = PAGE_START(target);
    size_t hook_len = 0;

#if defined(__x86_64__)
    // x86_64 absolute indirect jump: FF 25 00 00 00 00 [8-byte target] (14 bytes)
    hook_len = 14;
#elif defined(__i386__)
    // x86 relative jump: E9 [4-byte rel32] (5 bytes)
    hook_len = 5;
#elif defined(__aarch64__)
    // arm64: LDR X16, #8; BR X16; [8-byte target] (16 bytes)
    hook_len = 16;
#elif defined(__arm__)
    // arm32: LDR PC, [PC, #-4]; [4-byte target] (8 bytes)
    hook_len = 8;
#else
    LOGE("DobbyHook: unsupported architecture");
    return -1;
#endif

    // Allocate trampoline for original call if origin_call pointer is requested
    if (origin_call != nullptr) {
        void *trampoline = mmap(nullptr, PAGE_SIZE, PROT_READ | PROT_WRITE | PROT_EXEC,
                                MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
        if (trampoline == MAP_FAILED) {
            LOGE("DobbyHook: failed to allocate trampoline memory");
            return -1;
        }

        uint8_t *tramp_ptr = (uint8_t *)trampoline;
        // Copy original instructions
        memcpy(tramp_ptr, (void *)target, hook_len);
        tramp_ptr += hook_len;

        // Jump back to (target + hook_len)
        uintptr_t return_addr = target + hook_len;
#if defined(__x86_64__)
        tramp_ptr[0] = 0xFF;
        tramp_ptr[1] = 0x25;
        tramp_ptr[2] = 0x00;
        tramp_ptr[3] = 0x00;
        tramp_ptr[4] = 0x00;
        tramp_ptr[5] = 0x00;
        memcpy(tramp_ptr + 6, &return_addr, 8);
#elif defined(__i386__)
        tramp_ptr[0] = 0xE9;
        int32_t rel = (int32_t)(return_addr - ((uintptr_t)tramp_ptr + 5));
        memcpy(tramp_ptr + 1, &rel, 4);
#elif defined(__aarch64__)
        uint32_t ldr_br[] = { 0x58000050, 0xd61f0200 }; // LDR X16, #8; BR X16
        memcpy(tramp_ptr, ldr_br, 8);
        memcpy(tramp_ptr + 8, &return_addr, 8);
#elif defined(__arm__)
        uint32_t ldr_pc = 0xe51ff004; // LDR PC, [PC, #-4]
        memcpy(tramp_ptr, &ldr_pc, 4);
        memcpy(tramp_ptr + 4, &return_addr, 4);
#endif

        *origin_call = trampoline;
    }

    // Unprotect target memory page
    if (mprotect((void *)page_start, PAGE_SIZE * 2, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("DobbyHook: mprotect RWX failed");
        return -1;
    }

    // Write jump to replacement
    uint8_t *target_ptr = (uint8_t *)target;
#if defined(__x86_64__)
    target_ptr[0] = 0xFF;
    target_ptr[1] = 0x25;
    target_ptr[2] = 0x00;
    target_ptr[3] = 0x00;
    target_ptr[4] = 0x00;
    target_ptr[5] = 0x00;
    memcpy(target_ptr + 6, &replace, 8);
#elif defined(__i386__)
    target_ptr[0] = 0xE9;
    int32_t rel = (int32_t)(replace - ((uintptr_t)target_ptr + 5));
    memcpy(target_ptr + 1, &rel, 4);
#elif defined(__aarch64__)
    uint32_t ldr_br[] = { 0x58000050, 0xd61f0200 };
    memcpy(target_ptr, ldr_br, 8);
    memcpy(target_ptr + 8, &replace, 8);
#elif defined(__arm__)
    uint32_t ldr_pc = 0xe51ff004;
    memcpy(target_ptr, &ldr_pc, 4);
    memcpy(target_ptr + 4, &replace, 4);
#endif

    // Flush instruction cache on ARM
#if defined(__arm__) || defined(__aarch64__)
    __builtin___clear_cache((char *)target, (char *)(target + hook_len));
#endif

    // Restore page protection
    mprotect((void *)page_start, PAGE_SIZE * 2, PROT_READ | PROT_EXEC);

    LOGD("DobbyHook: successfully hooked %p -> %p", (void *)target, (void *)replace);
    return 0;
}

int DobbyDestroy(void *function_address) {
    (void)function_address;
    return 0;
}
