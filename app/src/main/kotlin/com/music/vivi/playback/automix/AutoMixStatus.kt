package com.music.vivi.playback.automix

/** Public diagnostics contain track IDs and timings, never media URLs or auth information. */
enum class AutoMixPhase {
    DISABLED, WAITING_NEXT, WAITING_SOURCE, ANALYZING_CURRENT, ANALYZING_NEXT, REFINING,
    PLANNED, PREPARING, READY, MIXING, FALLBACK_PLANNED, FALLBACK_PLAYING, RECOVERING, COMPLETED, UNAVAILABLE
}
data class AutoMixStatus(
    val phase: AutoMixPhase = AutoMixPhase.DISABLED,
    val outgoingId: String? = null,
    val incomingId: String? = null,
    val outgoingDurationMs: Long? = null,
    val plan: AutoMixPlan? = null,
    val reason: String? = null,
    val actualStartMs: Long? = null,
) {
    val validPlan get() = plan != null && phase in setOf(AutoMixPhase.PLANNED, AutoMixPhase.PREPARING, AutoMixPhase.READY)
    val intelligent get() = plan?.technique in setOf(MixTechnique.BEAT_BLEND, MixTechnique.BASS_HANDOFF) && (plan?.phaseConfidence ?: 0.0) >= 0.4
    fun markerFraction(currentId: String?, nextId: String?, durationMs: Long): Float? {
        if (!validPlan || !intelligent || currentId != outgoingId || nextId != incomingId || durationMs <= 0 ||
            outgoingDurationMs == null || kotlin.math.abs(durationMs-outgoingDurationMs)>1000) return null
        val start=plan!!.outgoingStartMs
        if(start !in 1 until durationMs) return null
        return (start.toDouble()/durationMs).toFloat().coerceIn(0f,1f)
    }
}
/** Only our own controlled, non-sensitive reason codes are ever exposed. */
class AnalysisUnavailable(val reason: String) : java.io.IOException(reason)
