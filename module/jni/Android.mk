LOCAL_PATH := $(call my-dir)

# --- Dobby Prebuilt ---
include $(CLEAR_VARS)
LOCAL_MODULE := dobby-prebuilt
LOCAL_SRC_FILES := dobby/lib/$(TARGET_ARCH_ABI)/libdobby.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/dobby/include
include $(PREBUILT_STATIC_LIBRARY)

# --- LSplant Prebuilt ---
include $(CLEAR_VARS)
LOCAL_MODULE := lsplant-prebuilt
LOCAL_SRC_FILES := lsplant/lib/$(TARGET_ARCH_ABI)/liblsplant.so
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/lsplant/include
include $(PREBUILT_SHARED_LIBRARY)

# --- ZygiskSIM Module ---
include $(CLEAR_VARS)
LOCAL_MODULE := zygisksim
LOCAL_SRC_FILES := main.cpp elf_util.cpp
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -std=c++17 -Wall -Wextra -fvisibility=hidden
LOCAL_STATIC_LIBRARIES := dobby-prebuilt
LOCAL_SHARED_LIBRARIES := lsplant-prebuilt
include $(BUILD_SHARED_LIBRARY)
