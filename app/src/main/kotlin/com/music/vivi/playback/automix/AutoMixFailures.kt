package com.music.vivi.playback.automix

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/** Stable export/UI categories; never based on an exception's potentially sensitive message. */
enum class AutoMixFailure {
    SOURCE_UNRESOLVED, SOURCE_NOT_SEEKABLE, SOURCE_DENIED, SOURCE_IO_FAILURE, SOURCE_CHANGED, TRACK_DURATION_UNKNOWN,
    DECODER_UNAVAILABLE, DECODER_FAILED, UNSUPPORTED_FORMAT, ENCRYPTED_SOURCE,
    ANALYSIS_TIMEOUT, ANALYSIS_CANCELED, ANALYSIS_BYTE_BUDGET, INSUFFICIENT_AUDIO_DATA,
    LOW_BEAT_CONFIDENCE, INCOMPATIBLE_TEMPO, INCOMPATIBLE_KEY, VOCAL_COLLISION,
    NO_SUITABLE_TRANSITION, IMPORTANT_INTRO, SPECTRAL_COLLISION, UNSTABLE_ENERGY, REFINEMENT_FAILED, PREPARATION_FAILED,
    TRANSITION_DEADLINE_MISSED, QUEUE_CHANGED, SEEK, TRACK_DURATION_MISMATCH, WORKER_QUEUE_FULL;
    companion object {
        fun from(reason: String): AutoMixFailure = entries.firstOrNull { it.name==reason } ?: when {
            reason.contains("source-unresolved") -> SOURCE_UNRESOLVED
            reason.contains("source-not-range") -> SOURCE_NOT_SEEKABLE
            reason.contains("source-rendition") -> SOURCE_CHANGED
            reason.contains("software-decoder") -> DECODER_UNAVAILABLE
            reason.contains("no-audio-track") -> UNSUPPORTED_FORMAT
            reason.contains("unsupported-decoded") -> UNSUPPORTED_FORMAT
            reason.contains("encrypted") -> ENCRYPTED_SOURCE
            reason.contains("byte-budget") -> ANALYSIS_BYTE_BUDGET
            reason.contains("unknown-or-unsupported-duration") -> TRACK_DURATION_UNKNOWN
            reason.contains("duration") -> TRACK_DURATION_MISMATCH
            reason.contains("truncated") || reason.contains("insufficient-audio") -> INSUFFICIENT_AUDIO_DATA
            reason.contains("timeout") || reason.contains("deadline") -> ANALYSIS_TIMEOUT
            reason.contains("canceled") -> ANALYSIS_CANCELED
            reason.contains("refinement") || reason.contains("refined") -> REFINEMENT_FAILED
            reason.contains("incoming-decoder") -> DECODER_FAILED
            reason.contains("deck") || reason.contains("preparation") -> PREPARATION_FAILED
            reason.contains("missed") -> TRANSITION_DEADLINE_MISSED
            reason.contains("tempo") -> INCOMPATIBLE_TEMPO
            reason.contains("vocal") -> VOCAL_COLLISION
            reason.contains("confidence") -> LOW_BEAT_CONFIDENCE
            else -> NO_SUITABLE_TRANSITION
        }
        fun of(error: Throwable): AutoMixFailure {
            val e=if(error is ExecutionException) error.cause ?: error else error
            return when(e) {
                is AnalysisUnavailable -> from(e.reason)
                is CancellationException, is InterruptedException -> ANALYSIS_CANCELED
                is java.io.IOException -> SOURCE_IO_FAILURE
                is IllegalArgumentException -> UNSUPPORTED_FORMAT
                else -> DECODER_FAILED
            }
        }
    }
}
