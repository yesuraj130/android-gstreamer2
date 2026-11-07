package com.gstlauncher.app

import android.content.Context
import android.view.Surface

class GstNativeBridge private constructor() {

    interface PipelineListener {
        fun onStateChanged(state: String)
        fun onError(errorMessage: String)
        fun onEos()
    }

    interface RtspListener {
        fun onRtspStatusChanged(isRunning: Boolean, message: String)
    }

    private var pipelineListener: PipelineListener? = null
    private var rtspListener: RtspListener? = null

    companion object {
        val instance: GstNativeBridge by lazy { GstNativeBridge() }

        init {
            try {
                System.loadLibrary("gstreamer_android")
            } catch (e: UnsatisfiedLinkError) {
                // If compiled with bundled static GStreamer, this may be linked into gst_pipeline_runner
            }
            try {
                System.loadLibrary("gst_pipeline_runner")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    fun init(context: Context) {
        nativeInit(context)
    }

    fun setPipelineListener(listener: PipelineListener?) {
        this.pipelineListener = listener
    }

    fun setRtspListener(listener: RtspListener?) {
        this.rtspListener = listener
    }

    fun playPipeline(pipeline: String): Boolean {
        return nativePlayPipeline(pipeline)
    }

    fun stopPipeline() {
        nativeStopPipeline()
    }

    fun setSurface(surface: Surface?) {
        nativeSetSurface(surface)
    }

    fun startRtspServer(port: Int, mountPoint: String, pipelineDesc: String): Boolean {
        return nativeStartRtspServer(port, mountPoint, pipelineDesc)
    }

    fun stopRtspServer() {
        nativeStopRtspServer()
    }

    // Callbacks invoked from C++ JNI
    fun onNativeStateChanged(state: String) {
        pipelineListener?.onStateChanged(state)
    }

    fun onNativeError(errorMessage: String) {
        pipelineListener?.onError(errorMessage)
    }

    fun onNativeEos() {
        pipelineListener?.onEos()
    }

    fun onNativeRtspStatus(isRunning: Boolean, message: String) {
        rtspListener?.onRtspStatusChanged(isRunning, message)
    }

    // Native declarations
    private external fun nativeInit(context: Context)
    private external fun nativePlayPipeline(pipeline: String): Boolean
    private external fun nativeStopPipeline()
    private external fun nativeSetSurface(surface: Surface?)
    private external fun nativeStartRtspServer(port: Int, mountPoint: String, pipelineDesc: String): Boolean
    private external fun nativeStopRtspServer()
}
