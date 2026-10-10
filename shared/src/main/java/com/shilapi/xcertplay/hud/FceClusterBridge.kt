package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Publishes CarPlay arrows and distance to the instrument cluster of FCE head units through
 * [FceCarPropertyWriter], independently of the BYD outputs. Follows the navigation output switch
 * ([BydOutputSettings.enabled]).
 */
internal object FceClusterBridge {
    private const val TAG = "DiPlay-FCE-Cluster"

    private val lock = Any()
    private val route = BydHudRouteState()
    private var output = FceClusterOutput(FceCarPropertyWriter())
    private var context: Context? = null
    private var tickStarted = false
    private var guidanceLogged = false

    fun available(): Boolean = FceCarPropertyWriter.isAvailable()

    fun initialize(appContext: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        context = appContext.applicationContext
        Log.i(TAG, "FCE cluster output ready")
        if (!tickStarted) {
            tickStarted = true
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-fce-cluster").apply { isDaemon = true }
            }.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        if (context == null) return@synchronized
        when (route.accept(frame.messageId, frame.payload)) {
            BydHudRouteChange.GUIDANCE -> sendCurrentLocked()
            BydHudRouteChange.CLEAR -> output.stop()
            BydHudRouteChange.NONE -> Unit
        }
    }

    fun clear() = synchronized(lock) {
        route.clear() // The tick must not restore guidance after cleanup.
        output.stop()
    }

    private fun tick() = synchronized(lock) {
        // Guidance can expire without a frame (a list that stays empty), and the switch can be
        // turned off mid-route, so check every second.
        if (output.active && (route.currentApple() == null || !enabledLocked())) output.stop()
    }

    private fun enabledLocked(): Boolean = context?.let(BydOutputSettings::enabled) == true

    private fun sendCurrentLocked() {
        if (!enabledLocked()) return output.stop()
        val apple = route.currentApple() ?: return output.stop()
        val guidance = FceClusterGuidance(
            FceManeuverCodes.icon(apple.type, apple.drivingSide),
            FceManeuverCodes.distance(apple.distanceMeters),
        )
        output.update(guidance)
        if (!guidanceLogged && output.active) {
            guidanceLogged = true
            Log.i(TAG, "cluster guidance sent $guidance (apple type ${apple.type})")
        }
    }

    /** Tests replace the Binder writer and reset state. */
    internal fun resetForTest(writer: FcePropertyWriter, appContext: Context?) = synchronized(lock) {
        route.clear()
        output = FceClusterOutput(writer)
        context = appContext
        guidanceLogged = false
    }
}
