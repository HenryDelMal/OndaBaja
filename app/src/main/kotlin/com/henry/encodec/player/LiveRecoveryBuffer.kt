package com.henry.encodec.player

/** Resume quickly after an underrun while larger prefetching continues. */
internal class LiveRecoveryBuffer(segmentDurationMs: Long) {
    val preferredDepth = 2
    val maximumWaitMs = (segmentDurationMs / 4).coerceIn(250L, 1_000L)
    private var firstReadyAtMs: Long? = null

    fun ready(depth: Int, nowMs: Long): Boolean {
        if (depth <= 0) {
            firstReadyAtMs = null
            return false
        }
        if (depth >= preferredDepth) return true
        val firstReady = firstReadyAtMs ?: nowMs.also { firstReadyAtMs = it }
        return nowMs - firstReady >= maximumWaitMs
    }
}
