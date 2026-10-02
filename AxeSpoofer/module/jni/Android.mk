LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := axespoofer
LOCAL_SRC_FILES := main.cpp
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -std=c++17 -Wall -Wextra -O3 -fvisibility=hidden -fvisibility-inlines-hidden
include $(BUILD_SHARED_LIBRARY)
