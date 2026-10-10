package com.shilapi.xcertplay.hud

/**
 * Turn-by-turn guidance for instrument clusters behind FCE/Coagent head units (T7/S311 platform)
 * that have a "CAN navigation" view.
 *
 * The stock head-unit service only tells the cluster that navigation started or stopped and drops
 * every guidance update, so that view stays empty. The vehicle HAL accepts the guidance properties
 * anyway and forwards them to the MCU and on to the cluster: status 2 unlocks the view, the icon
 * property selects a glyph and the distance property is shown in metres (the cluster formats
 * kilometres itself). Verified on a vehicle with this head unit.
 */
internal object FceClusterProperties {
    /** 2 = navigating, 1 = cruise, 0 = map closed. The stock app sends 1 then 0 at route end. */
    const val NAVI_STATUS = 0x214000a5
    /** Maneuver glyph, see [FceClusterIcons]. Reaches the cluster unchanged. */
    const val ICON = 0x21400099
    /**
     * Second icon set, only meaningful with icon 255. Its low 4 bits reach the cluster and a
     * leftover non-zero value blanks U-turns and roundabout exits, so it is held at 0.
     */
    const val ICON_SET_2 = 0x2140009a
    /** Distance to the next maneuver in metres (17-bit signal). */
    const val DISTANCE = 0x21400098
}

/** Cluster glyph codes (AutoNavi TBT table), right-hand-traffic variants. */
internal object FceClusterIcons {
    const val NONE = 0
    const val DESTINATION = 3
    const val STRAIGHT = 16
    const val SLIGHT_RIGHT = 17
    const val SLIGHT_LEFT = 18
    const val LEFT = 19
    const val RIGHT = 20
    const val SHARP_LEFT = 21
    const val SHARP_RIGHT = 22
    const val U_TURN_LEFT = 23
    const val U_TURN_RIGHT = 24
    /** Roundabout showing exit 1; exits 1–10 are consecutive codes. */
    const val ROUNDABOUT_EXIT_1 = 42
    const val ROUNDABOUT_EXIT_MAX = 10
    const val ROUNDABOUT_EXIT = 52
    const val ROUNDABOUT_ENTER = 53
}

internal data class FceClusterGuidance(val icon: Int, val distanceMeters: Int)

internal fun interface FcePropertyWriter {
    /** Returns false when the value did not reach the vehicle HAL. */
    fun setInt(propertyId: Int, value: Int): Boolean
}

/** Sends guidance to the cluster, writing only what changed. Not thread-safe: call from one thread. */
internal class FceClusterOutput(private val writer: FcePropertyWriter) {
    var active = false
        private set
    private var lastIcon: Int? = null
    private var lastDistance: Int? = null

    fun update(guidance: FceClusterGuidance) {
        if (!active) {
            if (!writer.setInt(FceClusterProperties.NAVI_STATUS, STATUS_NAVIGATING)) return
            active = true
            lastIcon = null
            lastDistance = null
        }
        if (guidance.icon != lastIcon &&
            writer.setInt(FceClusterProperties.ICON_SET_2, 0) &&
            writer.setInt(FceClusterProperties.ICON, guidance.icon)
        ) {
            lastIcon = guidance.icon
        }
        val distance = guidance.distanceMeters.coerceIn(0, MAX_DISTANCE_METERS)
        if (distance != lastDistance && writer.setInt(FceClusterProperties.DISTANCE, distance)) {
            lastDistance = distance
        }
    }

    /** The factory end-of-navigation sequence; the cluster keeps the last values otherwise. */
    fun stop() {
        if (!active) return
        writer.setInt(FceClusterProperties.ICON, FceClusterIcons.NONE)
        writer.setInt(FceClusterProperties.ICON_SET_2, 0)
        writer.setInt(FceClusterProperties.DISTANCE, 0)
        writer.setInt(FceClusterProperties.NAVI_STATUS, STATUS_CRUISE)
        writer.setInt(FceClusterProperties.NAVI_STATUS, STATUS_CLOSED)
        active = false
        lastIcon = null
        lastDistance = null
    }

    companion object {
        private const val STATUS_NAVIGATING = 2
        private const val STATUS_CRUISE = 1
        private const val STATUS_CLOSED = 0

        /** The MCU packs distance into 17 bits. */
        const val MAX_DISTANCE_METERS = 131_071
    }
}

/**
 * Apple iAP2 RouteGuidanceManeuverType -> cluster glyph.
 *
 * Only the right-hand-traffic roundabout glyphs are known, so left-hand driving changes only the
 * U-turn direction. Roundabout exits above 10 and unknown types fall back to the plain roundabout
 * or no arrow: a wrong arrow at a real turn is worse than none, and the distance stays.
 */
internal object FceManeuverCodes {
    /** [drivingSide] 1 is Apple's left-hand driving. */
    fun icon(appleType: Int, drivingSide: Int): Int {
        val leftHand = drivingSide == 1
        if (appleType in 28..46) {
            val exit = appleType - 27
            return if (exit <= FceClusterIcons.ROUNDABOUT_EXIT_MAX) FceClusterIcons.ROUNDABOUT_EXIT_1 + exit - 1
                else FceClusterIcons.ROUNDABOUT_ENTER
        }
        return when (appleType) {
            1, 20 -> FceClusterIcons.LEFT
            2, 21 -> FceClusterIcons.RIGHT
            47 -> FceClusterIcons.SHARP_LEFT
            48 -> FceClusterIcons.SHARP_RIGHT
            13, 22, 49, 52 -> FceClusterIcons.SLIGHT_LEFT
            14, 23, 50, 53 -> FceClusterIcons.SLIGHT_RIGHT
            4, 18, 19, 26 -> if (leftHand) FceClusterIcons.U_TURN_RIGHT else FceClusterIcons.U_TURN_LEFT
            6 -> FceClusterIcons.ROUNDABOUT_ENTER
            7 -> FceClusterIcons.ROUNDABOUT_EXIT
            10, 12, 24, 25, 27 -> FceClusterIcons.DESTINATION
            3, 5, 8, 9, 11, 15, 16, 17, 51 -> FceClusterIcons.STRAIGHT
            else -> FceClusterIcons.NONE
        }
    }

    /**
     * Distance as the driver reads it on the iPhone: whole 10 m below 1 km, whole 100 m above, so
     * the cluster's own kilometre formatting shows "1.5 km" rather than the exact 1,530 m.
     */
    fun distance(meters: Int): Int {
        val m = meters.coerceAtLeast(0)
        val step = if (m < 1000) 10 else 100
        return ((m + step / 2) / step) * step
    }
}
