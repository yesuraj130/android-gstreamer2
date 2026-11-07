package com.gstlauncher.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gstlauncher.app.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback,
    GstNativeBridge.PipelineListener, GstNativeBridge.RtspListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var reconnectionManager: ReconnectionManager

    private var activeSlot = 0
    private var isPlaying = false
    private var isRtspServerRunning = false

    companion object {
        private const val PREFS_NAME = "GstLauncherPrefs"
        private const val KEY_SLOT_1 = "pipeline_slot_1"
        private const val KEY_SLOT_2 = "pipeline_slot_2"
        private const val KEY_SLOT_3 = "pipeline_slot_3"
        private const val KEY_RTSP_PIPELINE = "pipeline_rtsp"
        private const val KEY_LAST_SLOT = "last_played_slot"
        private const val KEY_AUTO_START = "pref_auto_start_on_boot"

        private const val DEFAULT_SLOT_1 =
            "rtspsrc location=rtsp://192.168.1.100:8554/live latency=0 drop-on-latency=true ! " +
            "rtph264depay ! h264parse ! amcviddec ! autovideosink sync=false"

        private const val DEFAULT_SLOT_2 =
            "udpsrc port=5000 caps=\"application/x-rtp, media=(string)video, encoding-name=(string)H264\" ! " +
            "rtph264depay ! h264parse ! amcviddec ! autovideosink sync=false"

        private const val DEFAULT_SLOT_3 =
            "ahc2src ! video/x-raw, width=1280, height=720, framerate=30/1 ! autovideosink sync=false"

        private const val DEFAULT_RTSP =
            "( ahc2src ! video/x-raw, width=1280, height=720, framerate=30/1 ! videoconvert ! amcvidenc ! rtph264pay name=pay0 pt=96 )"

        private const val CAMERA_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Initialize Native GStreamer Engine
        GstNativeBridge.instance.init(applicationContext)
        GstNativeBridge.instance.setPipelineListener(this)
        GstNativeBridge.instance.setRtspListener(this)

        // SurfaceView setup
        binding.surfaceView.holder.addCallback(this)

        // Initialize Reconnection Supervisor
        reconnectionManager = ReconnectionManager(
            onReconnectTrigger = {
                runOnUiThread {
                    if (activeSlot in 1..3) {
                        playSlot(activeSlot, isAutoReconnect = true)
                    }
                }
            },
            onStatusUpdate = { message ->
                runOnUiThread {
                    binding.tvLiveStatus.text = message
                    binding.statusIndicatorDot.setBackgroundColor(Color.parseColor("#FFFFD600"))
                }
            }
        )

        loadSavedPipelines()
        setupListeners()
        updateRtspServerUrlDisplay()

        // Auto play last played configuration
        val lastSlot = prefs.getInt(KEY_LAST_SLOT, 1)
        activeSlot = lastSlot

        binding.root.post {
            playSlot(lastSlot, isAutoReconnect = false)
        }
    }

    private fun loadSavedPipelines() {
        binding.etPipelineSlot1.setText(prefs.getString(KEY_SLOT_1, DEFAULT_SLOT_1))
        binding.etPipelineSlot2.setText(prefs.getString(KEY_SLOT_2, DEFAULT_SLOT_2))
        binding.etPipelineSlot3.setText(prefs.getString(KEY_SLOT_3, DEFAULT_SLOT_3))
        binding.etRtspPipeline.setText(prefs.getString(KEY_RTSP_PIPELINE, DEFAULT_RTSP))
        binding.switchAutoStart.isChecked = prefs.getBoolean(KEY_AUTO_START, true)
    }

    private fun savePipelines() {
        prefs.edit()
            .putString(KEY_SLOT_1, binding.etPipelineSlot1.text.toString().trim())
            .putString(KEY_SLOT_2, binding.etPipelineSlot2.text.toString().trim())
            .putString(KEY_SLOT_3, binding.etPipelineSlot3.text.toString().trim())
            .putString(KEY_RTSP_PIPELINE, binding.etRtspPipeline.text.toString().trim())
            .putInt(KEY_LAST_SLOT, activeSlot)
            .putBoolean(KEY_AUTO_START, binding.switchAutoStart.isChecked)
            .apply()
    }

    private fun setupListeners() {
        binding.btnPlaySlot1.setOnClickListener {
            checkCameraPermissionIfNeeded(binding.etPipelineSlot1.text.toString()) {
                playSlot(1, isAutoReconnect = false)
            }
        }

        binding.btnPlaySlot2.setOnClickListener {
            checkCameraPermissionIfNeeded(binding.etPipelineSlot2.text.toString()) {
                playSlot(2, isAutoReconnect = false)
            }
        }

        binding.btnPlaySlot3.setOnClickListener {
            checkCameraPermissionIfNeeded(binding.etPipelineSlot3.text.toString()) {
                playSlot(3, isAutoReconnect = false)
            }
        }

        binding.btnGlobalStop.setOnClickListener {
            stopPlayback()
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_START, isChecked).apply()
        }

        binding.btnToggleRtspServer.setOnClickListener {
            if (isRtspServerRunning) {
                GstNativeBridge.instance.stopRtspServer()
            } else {
                checkCameraPermissionIfNeeded(binding.etRtspPipeline.text.toString()) {
                    startRtspServer()
                }
            }
        }
    }

    private fun playSlot(slot: Int, isAutoReconnect: Boolean) {
        if (!isAutoReconnect) {
            reconnectionManager.resetAttempts()
        }

        savePipelines()
        activeSlot = slot

        val pipelineStr = when (slot) {
            1 -> binding.etPipelineSlot1.text.toString().trim()
            2 -> binding.etPipelineSlot2.text.toString().trim()
            3 -> binding.etPipelineSlot3.text.toString().trim()
            else -> ""
        }

        if (pipelineStr.isEmpty()) {
            Toast.makeText(this, "Pipeline slot $slot is empty", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvLiveStatus.text = "Starting Pipeline $slot..."
        binding.statusIndicatorDot.setBackgroundColor(Color.parseColor("#FFFFD600"))

        val success = GstNativeBridge.instance.playPipeline(pipelineStr)
        if (!success) {
            binding.tvLiveStatus.text = "Launch failed for Slot $slot"
            binding.statusIndicatorDot.setBackgroundColor(Color.parseColor("#FFFF5252"))
            reconnectionManager.scheduleReconnect()
        } else {
            isPlaying = true
            updateButtonStates()
        }
    }

    private fun stopPlayback() {
        reconnectionManager.cancel()
        GstNativeBridge.instance.stopPipeline()
        isPlaying = false
        binding.tvLiveStatus.text = "Status: Stopped"
        binding.statusIndicatorDot.setBackgroundColor(Color.parseColor("#FFFF5252"))
        updateButtonStates()
    }

    private fun startRtspServer() {
        val launchStr = binding.etRtspPipeline.text.toString().trim()
        savePipelines()
        binding.btnToggleRtspServer.isEnabled = false
        val success = GstNativeBridge.instance.startRtspServer(8554, "/live", launchStr)
        if (!success) {
            binding.btnToggleRtspServer.isEnabled = true
            Toast.makeText(this, "Failed to start RTSP server", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateButtonStates() {
        binding.btnPlaySlot1.text = if (isPlaying && activeSlot == 1) "Active" else getString(R.string.play)
        binding.btnPlaySlot2.text = if (isPlaying && activeSlot == 2) "Active" else getString(R.string.play)
        binding.btnPlaySlot3.text = if (isPlaying && activeSlot == 3) "Active" else getString(R.string.play)
    }

    private fun updateRtspServerUrlDisplay() {
        val ip = getDeviceIpAddress()
        binding.tvRtspServerUrl.text = "Broadcast URL: rtsp://$ip:8554/live"
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun checkCameraPermissionIfNeeded(pipeline: String, onGranted: () -> Unit) {
        if (pipeline.contains("ahc2src") || pipeline.contains("camera")) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
                return
            }
        }
        onGranted()
    }

    // SurfaceHolder callbacks
    override fun surfaceCreated(holder: SurfaceHolder) {
        GstNativeBridge.instance.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        GstNativeBridge.instance.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        GstNativeBridge.instance.setSurface(null)
    }

    // GStreamer Pipeline Callbacks
    override fun onStateChanged(state: String) {
        runOnUiThread {
            if (state == "PLAYING") {
                isPlaying = true
                reconnectionManager.resetAttempts()
                binding.tvLiveStatus.text = "Playing: Slot $activeSlot (Low Latency)"
                binding.statusIndicatorDot.setBackgroundColor(Color.parseColor("#FF00E676"))
            } else if (state == "STOPPED" || state == "NULL") {
                if (!reconnectionManager.isActive()) {
                    binding.tvLiveStatus.text = "Status: Stopped"
                    binding.statusIndicatorDot.setBackgroundColor(Color.parseColor("#FFFF5252"))
                }
            }
            updateButtonStates()
        }
    }

    override fun onError(errorMessage: String) {
        runOnUiThread {
            isPlaying = false
            updateButtonStates()
            binding.tvLiveStatus.text = "Error: $errorMessage"
            binding.statusIndicatorDot.setBackgroundColor(Color.parseColor("#FFFF5252"))
            reconnectionManager.scheduleReconnect()
        }
    }

    override fun onEos() {
        runOnUiThread {
            binding.tvLiveStatus.text = "End of Stream reached. Reconnecting..."
            binding.statusIndicatorDot.setBackgroundColor(Color.parseColor("#FFFFD600"))
            reconnectionManager.scheduleReconnect()
        }
    }

    // GStreamer RTSP Server Callbacks
    override fun onRtspStatusChanged(isRunning: Boolean, message: String) {
        runOnUiThread {
            isRtspServerRunning = isRunning
            binding.btnToggleRtspServer.isEnabled = true
            if (isRunning) {
                binding.btnToggleRtspServer.text = getString(R.string.stop_rtsp)
                binding.btnToggleRtspServer.setBackgroundColor(Color.parseColor("#FFFF5252"))
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            } else {
                binding.btnToggleRtspServer.text = getString(R.string.start_rtsp)
                binding.btnToggleRtspServer.setBackgroundColor(Color.parseColor("#FF2979FF"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectionManager.cancel()
        GstNativeBridge.instance.stopPipeline()
        GstNativeBridge.instance.stopRtspServer()
    }
}
