LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := zygisksim
LOCAL_SRC_FILES := main.cpp
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -std=c++17 -Wall -Wextra -fvisibility=hidden
include $(BUILD_SHARED_LIBRARY)
