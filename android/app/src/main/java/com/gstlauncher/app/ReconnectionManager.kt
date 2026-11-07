package com.gstlauncher.app

import android.os.Handler
import android.os.Looper

class ReconnectionManager(
    private val onReconnectTrigger: () -> Unit,
    private val onStatusUpdate: (statusMessage: String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isReconnecting = false
    private var attempt = 0

    // Rate-controlled exponential backoff delays in seconds: 1, 2, 4, 8, max 10
    private val backoffDelaysSec = listOf(1, 2, 4, 8, 10)

    private val reconnectRunnable = Runnable {
        if (!isReconnecting) return@Runnable
        onStatusUpdate("Reconnecting now (Attempt #$attempt)...")
        onReconnectTrigger()
    }

    fun scheduleReconnect() {
        if (isReconnecting) {
            handler.removeCallbacks(reconnectRunnable)
        }
        isReconnecting = true
        attempt++

        val delayIndex = (attempt - 1).coerceAtMost(backoffDelaysSec.lastIndex)
        val delaySec = backoffDelaysSec[delayIndex]

        onStatusUpdate("Connection lost. Reconnecting in ${delaySec}s (Attempt #$attempt)...")
        handler.postDelayed(reconnectRunnable, delaySec * 1000L)
    }

    fun cancel() {
        isReconnecting = false
        attempt = 0
        handler.removeCallbacks(reconnectRunnable)
    }

    fun resetAttempts() {
        attempt = 0
        isReconnecting = false
        handler.removeCallbacks(reconnectRunnable)
    }

    fun isActive(): Boolean = isReconnecting
}
