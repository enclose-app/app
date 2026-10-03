package io.app.enclose.watchlink

/**
 * What the watch shows about the walk on the phone.
 *
 * Deliberately coarse. Every change to this value is a Data Layer write and a
 * Bluetooth wake on both devices, so figures are rounded to what a glance at a
 * wrist can use ([DISTANCE_STEP_M], [TO_START_STEP_M]) and anything that moves
 * every second — elapsed time — is not sent at all: the watch counts it up from
 * [startedAtEpochMs] itself.
 */
data class WatchStatus(
    val phase: Phase = Phase.IDLE,
    /** Wall-clock start of the walk; null until the first fix lands. */
    val startedAtEpochMs: Long? = null,
    val distanceMeters: Int = 0,
    /** How far the walker is from the start; null before the first fix. */
    val toStartMeters: Int? = null,
    /** Walked far enough and left the start zone: getting back closes the loop. */
    val canCloseLoop: Boolean = false,
    /** Stopping now would claim. The one state in which the watch offers Stop. */
    val readyToClose: Boolean = false,
    /** Movement is being rejected as not human-powered (a car, a bus). */
    val blocked: Boolean = false,
    /** Nothing is reaching the walk: the phone's recorder isn't running. */
    val notRecording: Boolean = false,
    /** Area of the loop just closed, while it waits to be claimed on the phone. */
    val closedAreaSqMeters: Int? = null,
) {
    enum class Phase {
        /** No walk. */
        IDLE,

        /** A walk is being recorded. */
        WALKING,

        /** A loop closed and is waiting for a name and a claim on the phone. */
        CLOSED,
    }

    /**
     * Whether a Stop from the watch may act. The watch asks this of the status
     * it was last sent, and the phone asks it again of its own live state before
     * doing anything, because the two can differ by a few seconds of walking.
     *
     * Only a closable loop: Stop on a walk that isn't ready throws the walk
     * away, and that decision stays on the phone, behind its confirmation.
     */
    val canStopFromWatch: Boolean get() = phase == Phase.WALKING && readyToClose

    /**
     * Flat key → value fields for a Data Layer item. Only Int, Long, Boolean and
     * String appear, so each side maps them onto `DataMap` with no knowledge of
     * what they mean. Absent nullables are omitted rather than encoded.
     */
    fun toFields(): Map<String, Any> = buildMap {
        put(K_VERSION, VERSION)
        put(K_PHASE, phase.name)
        startedAtEpochMs?.let { put(K_STARTED_AT, it) }
        put(K_DISTANCE, distanceMeters)
        toStartMeters?.let { put(K_TO_START, it) }
        put(K_CAN_CLOSE, canCloseLoop)
        put(K_READY, readyToClose)
        put(K_BLOCKED, blocked)
        put(K_NOT_RECORDING, notRecording)
        closedAreaSqMeters?.let { put(K_CLOSED_AREA, it) }
    }

    companion object {
        /** Distance is sent in 10 m steps: finer is noise at a glance. */
        const val DISTANCE_STEP_M = 10

        /** Distance to start in 5 m steps: the closing radius is tens of metres. */
        const val TO_START_STEP_M = 5

        /**
         * Bumped when a field changes meaning. A watch app older than the phone
         * app (they update separately) shows "update" rather than misreading.
         */
        const val VERSION = 1

        /**
         * Reads [toFields] back. [get] is the Data Layer lookup. Anything
         * missing or of the wrong type is treated as unreadable (null), never
         * guessed at: the watch says it can't read the phone rather than
         * showing a walk that isn't there.
         */
        fun fromFields(get: (String) -> Any?): WatchStatus? {
            if (get(K_VERSION) != VERSION) return null
            val phase = (get(K_PHASE) as? String)
                ?.let { name -> Phase.entries.firstOrNull { it.name == name } }
                ?: return null
            return WatchStatus(
                phase = phase,
                startedAtEpochMs = get(K_STARTED_AT) as? Long,
                distanceMeters = get(K_DISTANCE) as? Int ?: return null,
                toStartMeters = get(K_TO_START) as? Int,
                canCloseLoop = get(K_CAN_CLOSE) as? Boolean ?: return null,
                readyToClose = get(K_READY) as? Boolean ?: return null,
                blocked = get(K_BLOCKED) as? Boolean ?: return null,
                notRecording = get(K_NOT_RECORDING) as? Boolean ?: return null,
                closedAreaSqMeters = get(K_CLOSED_AREA) as? Int,
            )
        }

        /** Rounds [meters] to the nearest multiple of [step]. */
        fun round(meters: Double, step: Int): Int =
            (Math.round(meters / step) * step).toInt()

        private const val K_VERSION = "v"
        private const val K_PHASE = "phase"
        private const val K_STARTED_AT = "startedAt"
        private const val K_DISTANCE = "distance"
        private const val K_TO_START = "toStart"
        private const val K_CAN_CLOSE = "canClose"
        private const val K_READY = "ready"
        private const val K_BLOCKED = "blocked"
        private const val K_NOT_RECORDING = "notRecording"
        private const val K_CLOSED_AREA = "closedArea"
    }
}
