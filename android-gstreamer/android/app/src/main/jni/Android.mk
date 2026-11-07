LOCAL_PATH := $(call my-dir)

# ----------------------------------------------------------------------
# Build Native JNI Bridge Library
# ----------------------------------------------------------------------
include $(CLEAR_VARS)
LOCAL_MODULE := gst_pipeline_runner
LOCAL_SRC_FILES := gst_pipeline_runner.cpp
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)

# ----------------------------------------------------------------------
# GStreamer Android SDK Integration
# ----------------------------------------------------------------------
ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined!)
endif

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/armv7
else ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/arm64
else ifeq ($(TARGET_ARCH_ABI),x86)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/x86
else ifeq ($(TARGET_ARCH_ABI),x86_64)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/x86_64
else
    $(error Target arch ABI not supported: $(TARGET_ARCH_ABI))
endif

GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT)/share/gst-android/ndk-build
include $(GSTREAMER_NDK_BUILD_PATH)/plugins.mk

# Include core, playback, codecs (good, bad, ugly), net (RTSP/RTP), and sys (MediaCodec)
GSTREAMER_PLUGINS := $(GSTREAMER_PLUGINS_CORE) \
                     $(GSTREAMER_PLUGINS_PLAYBACK) \
                     $(GSTREAMER_PLUGINS_CODECS) \
                     $(GSTREAMER_PLUGINS_CODECS_RESTRICTED) \
                     $(GSTREAMER_PLUGINS_NET) \
                     $(GSTREAMER_PLUGINS_SYS)

GSTREAMER_EXTRA_DEPS := gstreamer-video-1.0 \
                        gstreamer-rtsp-1.0 \
                        gstreamer-rtsp-server-1.0 \
                        gstreamer-app-1.0 \
                        glib-2.0 \
                        gobject-2.0

include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk
