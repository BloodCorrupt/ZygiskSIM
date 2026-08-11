#include "elf_util.h"
#include <dobby.h>
#include <android/log.h>

#define LOG_TAG "ZygiskSIM-Elf"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

void* resolve_art_symbol(std::string_view symbol) {
    std::string sym(symbol);
    void* addr = DobbySymbolResolver("libart.so", sym.c_str());
    if (!addr) {
        LOGE("Failed to resolve symbol %s in libart.so", sym.c_str());
    }
    return addr;
}

