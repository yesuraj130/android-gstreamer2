#include "gst_pipeline_runner.h"
#include <dlfcn.h>

static gboolean bus_callback(GstBus* bus, GstMessage* message, gpointer user_data) {
    auto* runner = static_cast<GstPipelineRunner*>(user_data);
    if (runner) {
        runner->handleBusMessage(message);
    }
    return TRUE;
}

GstPipelineRunner& GstPipelineRunner::getInstance() {
    static GstPipelineRunner instance;
    return instance;
}

GstPipelineRunner::GstPipelineRunner() {
}

GstPipelineRunner::~GstPipelineRunner() {
    stopPipeline();
    stopRtspServer();
    if (mNativeWindow) {
        ANativeWindow_release(mNativeWindow);
        mNativeWindow = nullptr;
    }
}

void GstPipelineRunner::init(JNIEnv* env, jobject context) {
    if (mGstInitialized) return;

    env->GetJavaVM(&mJavaVM);

    // Dynamically query and invoke GStreamer Android initialization from libgstreamer_android.so if present
    void* handle = dlopen("libgstreamer_android.so", RTLD_NOLOAD);
    if (!handle) {
        handle = dlopen("libgstreamer_android.so", RTLD_NOW);
    }
    if (handle) {
        typedef void (*GstNativeInitFn)(JNIEnv*, jclass, jobject);
        typedef void (*GstAndroidInitFn)(JNIEnv*, jobject);
        auto nativeInit = reinterpret_cast<GstNativeInitFn>(dlsym(handle, "Java_org_freedesktop_gstreamer_GStreamer_nativeInit"));
        if (nativeInit) {
            LOGI("Calling Java_org_freedesktop_gstreamer_GStreamer_nativeInit...");
            nativeInit(env, nullptr, context);
        } else {
            auto androidInit = reinterpret_cast<GstAndroidInitFn>(dlsym(handle, "gst_android_init"));
            if (androidInit) {
                LOGI("Calling gst_android_init...");
                androidInit(env, context);
            }
        }
    }

    // Official GStreamer core initialization
    GError* error = nullptr;
    if (!gst_init_check(nullptr, nullptr, &error)) {
        LOGE("Failed to initialize GStreamer: %s", error ? error->message : "unknown error");
        if (error) g_error_free(error);
        return;
    }

    mGstInitialized = true;
    LOGI("GStreamer initialized successfully. Version: %s", gst_version_string());
}

void GstPipelineRunner::setJniBridge(JNIEnv* env, jobject bridgeObj) {
    if (mBridgeObject) {
        env->DeleteGlobalRef(mBridgeObject);
        mBridgeObject = nullptr;
    }

    mBridgeObject = env->NewGlobalRef(bridgeObj);
    jclass clazz = env->GetObjectClass(bridgeObj);

    mMethodOnStateChanged = env->GetMethodID(clazz, "onNativeStateChanged", "(Ljava/lang/String;)V");
    mMethodOnError = env->GetMethodID(clazz, "onNativeError", "(Ljava/lang/String;)V");
    mMethodOnEos = env->GetMethodID(clazz, "onNativeEos", "()V");
    mMethodOnRtspStatus = env->GetMethodID(clazz, "onNativeRtspStatus", "(ZLjava/lang/String;)V");
}

void GstPipelineRunner::setSurface(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);

    if (mNativeWindow) {
        ANativeWindow_release(mNativeWindow);
        mNativeWindow = nullptr;
    }

    mNativeWindow = window;

    if (mPipeline && mNativeWindow) {
        GstElement* video_sink = gst_bin_get_by_interface(GST_BIN(mPipeline), GST_TYPE_VIDEO_OVERLAY);
        if (video_sink) {
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(video_sink), (guintptr)mNativeWindow);
            gst_object_unref(video_sink);
        } else if (GST_IS_VIDEO_OVERLAY(mPipeline)) {
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(mPipeline), (guintptr)mNativeWindow);
        }
    }
}

bool GstPipelineRunner::playPipeline(const std::string& pipelineDesc) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);

    stopPipeline();

    LOGI("Parsing pipeline: %s", pipelineDesc.c_str());

    GError* error = nullptr;
    mPipeline = gst_parse_launch(pipelineDesc.c_str(), &error);

    if (error || !mPipeline) {
        std::string err = error ? error->message : "Unable to construct pipeline";
        LOGE("gst_parse_launch error: %s", err.c_str());
        notifyKotlinError(err.c_str());
        if (error) g_error_free(error);
        return false;
    }

    if (mNativeWindow) {
        GstElement* video_sink = gst_bin_get_by_interface(GST_BIN(mPipeline), GST_TYPE_VIDEO_OVERLAY);
        if (video_sink) {
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(video_sink), (guintptr)mNativeWindow);
            gst_object_unref(video_sink);
        } else if (GST_IS_VIDEO_OVERLAY(mPipeline)) {
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(mPipeline), (guintptr)mNativeWindow);
        }
    }

    GstBus* bus = gst_pipeline_get_bus(GST_PIPELINE(mPipeline));
    mBusWatchId = gst_bus_add_watch(bus, bus_callback, this);
    gst_object_unref(bus);

    GstStateChangeReturn ret = gst_element_set_state(mPipeline, GST_STATE_PLAYING);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        LOGE("Failed to set pipeline to PLAYING state");
        notifyKotlinError("State change to PLAYING failed");
        stopPipeline();
        return false;
    }

    notifyKotlinState("PLAYING");
    return true;
}

void GstPipelineRunner::stopPipeline() {
    if (mPipeline) {
        gst_element_set_state(mPipeline, GST_STATE_NULL);
        if (mBusWatchId != 0) {
            g_source_remove(mBusWatchId);
            mBusWatchId = 0;
        }
        gst_object_unref(mPipeline);
        mPipeline = nullptr;
        notifyKotlinState("STOPPED");
    }
}

void GstPipelineRunner::handleBusMessage(GstMessage* msg) {
    switch (GST_MESSAGE_TYPE(msg)) {
        case GST_MESSAGE_ERROR: {
            GError* err = nullptr;
            gchar* debug_info = nullptr;
            gst_message_parse_error(msg, &err, &debug_info);
            std::string errMsg = err ? err->message : "Unknown GStreamer bus error";
            LOGE("Bus ERROR: %s (debug: %s)", errMsg.c_str(), debug_info ? debug_info : "none");
            notifyKotlinError(errMsg.c_str());
            if (err) g_error_free(err);
            if (debug_info) g_free(debug_info);
            break;
        }
        case GST_MESSAGE_EOS: {
            LOGI("Bus: End of Stream received");
            notifyKotlinEos();
            break;
        }
        case GST_MESSAGE_STATE_CHANGED: {
            if (GST_MESSAGE_SRC(msg) == GST_OBJECT(mPipeline)) {
                GstState oldState, newState, pending;
                gst_message_parse_state_changed(msg, &oldState, &newState, &pending);
                const gchar* name = gst_element_state_get_name(newState);
                LOGI("Pipeline state changed to: %s", name);
                notifyKotlinState(name);
            }
            break;
        }
        default:
            break;
    }
}

bool GstPipelineRunner::startRtspServer(int port, const std::string& mountPoint, const std::string& pipelineDesc) {
    std::lock_guard<std::mutex> lock(mRtspMutex);
    stopRtspServer();

    mRtspServer = gst_rtsp_server_new();
    std::string portStr = std::to_string(port);
    gst_rtsp_server_set_service(mRtspServer, portStr.c_str());

    GstRTSPMountPoints* mounts = gst_rtsp_server_get_mount_points(mRtspServer);
    GstRTSPMediaFactory* factory = gst_rtsp_media_factory_new();
    gst_rtsp_media_factory_set_launch(factory, pipelineDesc.c_str());
    gst_rtsp_media_factory_set_shared(factory, TRUE);

    gst_rtsp_mount_points_add_factory(mounts, mountPoint.c_str(), factory);
    g_object_unref(mounts);

    mRtspMainLoop = g_main_loop_new(nullptr, FALSE);
    mRtspServerSourceId = gst_rtsp_server_attach(mRtspServer, nullptr);

    if (mRtspServerSourceId == 0) {
        LOGE("Failed to attach RTSP server on port %d", port);
        notifyRtspStatus(false, "Failed to bind RTSP server");
        g_main_loop_unref(mRtspMainLoop);
        mRtspMainLoop = nullptr;
        g_object_unref(mRtspServer);
        mRtspServer = nullptr;
        return false;
    }

    mRtspRunning = true;
    mRtspThread = std::thread([this, port, mountPoint]() {
        LOGI("RTSP Server running on port %d mount %s", port, mountPoint.c_str());
        notifyRtspStatus(true, "RTSP Server Active");
        g_main_loop_run(mRtspMainLoop);
        LOGI("RTSP Server thread exited");
    });

    return true;
}

void GstPipelineRunner::stopRtspServer() {
    std::lock_guard<std::mutex> lock(mRtspMutex);
    if (mRtspRunning) {
        mRtspRunning = false;
        if (mRtspMainLoop) {
            g_main_loop_quit(mRtspMainLoop);
        }
        if (mRtspThread.joinable()) {
            mRtspThread.join();
        }
        if (mRtspServerSourceId != 0) {
            g_source_remove(mRtspServerSourceId);
            mRtspServerSourceId = 0;
        }
        if (mRtspMainLoop) {
            g_main_loop_unref(mRtspMainLoop);
            mRtspMainLoop = nullptr;
        }
        if (mRtspServer) {
            g_object_unref(mRtspServer);
            mRtspServer = nullptr;
        }
        notifyRtspStatus(false, "RTSP Server Stopped");
    }
}

void GstPipelineRunner::notifyKotlinState(const char* stateName) {
    if (!mJavaVM || !mBridgeObject || !mMethodOnStateChanged) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        mJavaVM->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    jstring jstr = env->NewStringUTF(stateName);
    env->CallVoidMethod(mBridgeObject, mMethodOnStateChanged, jstr);
    env->DeleteLocalRef(jstr);
    if (attached) mJavaVM->DetachCurrentThread();
}

void GstPipelineRunner::notifyKotlinError(const char* errorMessage) {
    if (!mJavaVM || !mBridgeObject || !mMethodOnError) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        mJavaVM->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    jstring jstr = env->NewStringUTF(errorMessage);
    env->CallVoidMethod(mBridgeObject, mMethodOnError, jstr);
    env->DeleteLocalRef(jstr);
    if (attached) mJavaVM->DetachCurrentThread();
}

void GstPipelineRunner::notifyKotlinEos() {
    if (!mJavaVM || !mBridgeObject || !mMethodOnEos) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        mJavaVM->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    env->CallVoidMethod(mBridgeObject, mMethodOnEos);
    if (attached) mJavaVM->DetachCurrentThread();
}

void GstPipelineRunner::notifyRtspStatus(bool running, const char* message) {
    if (!mJavaVM || !mBridgeObject || !mMethodOnRtspStatus) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        mJavaVM->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    jstring jstr = env->NewStringUTF(message);
    env->CallVoidMethod(mBridgeObject, mMethodOnRtspStatus, (jboolean)running, jstr);
    env->DeleteLocalRef(jstr);
    if (attached) mJavaVM->DetachCurrentThread();
}

// JNI exports
extern "C" {

JNIEXPORT void JNICALL
Java_com_gstlauncher_app_GstNativeBridge_nativeInit(JNIEnv* env, jobject thiz, jobject context) {
    GstPipelineRunner::getInstance().init(env, context);
    GstPipelineRunner::getInstance().setJniBridge(env, thiz);
}

JNIEXPORT jboolean JNICALL
Java_com_gstlauncher_app_GstNativeBridge_nativePlayPipeline(JNIEnv* env, jobject thiz, jstring pipeline) {
    const char* str = env->GetStringUTFChars(pipeline, nullptr);
    std::string pipelineStr(str);
    env->ReleaseStringUTFChars(pipeline, str);
    return (jboolean)GstPipelineRunner::getInstance().playPipeline(pipelineStr);
}

JNIEXPORT void JNICALL
Java_com_gstlauncher_app_GstNativeBridge_nativeStopPipeline(JNIEnv* env, jobject thiz) {
    GstPipelineRunner::getInstance().stopPipeline();
}

JNIEXPORT void JNICALL
Java_com_gstlauncher_app_GstNativeBridge_nativeSetSurface(JNIEnv* env, jobject thiz, jobject surface) {
    ANativeWindow* window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
    }
    GstPipelineRunner::getInstance().setSurface(window);
}

JNIEXPORT jboolean JNICALL
Java_com_gstlauncher_app_GstNativeBridge_nativeStartRtspServer(
    JNIEnv* env, jobject thiz, jint port, jstring mount_point, jstring pipeline_desc) {
    const char* mount = env->GetStringUTFChars(mount_point, nullptr);
    const char* desc = env->GetStringUTFChars(pipeline_desc, nullptr);
    bool success = GstPipelineRunner::getInstance().startRtspServer(port, std::string(mount), std::string(desc));
    env->ReleaseStringUTFChars(mount_point, mount);
    env->ReleaseStringUTFChars(pipeline_desc, desc);
    return (jboolean)success;
}

JNIEXPORT void JNICALL
Java_com_gstlauncher_app_GstNativeBridge_nativeStopRtspServer(JNIEnv* env, jobject thiz) {
    GstPipelineRunner::getInstance().stopRtspServer();
}

} // extern "C"
