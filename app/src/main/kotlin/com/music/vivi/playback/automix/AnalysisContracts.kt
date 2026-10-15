package com.music.vivi.playback.automix

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** All intervals are absolute source-timeline microseconds, start inclusive/end exclusive. */
data class AudioIntervalUs(val startUs: Long, val endUs: Long) {
    val valid: Boolean get() = startUs >= 0 && endUs > startUs
    fun contains(other: AudioIntervalUs) = startUs <= other.startUs && endUs >= other.endUs
}

enum class PcmEncoding { FLOAT32, PCM16, UNKNOWN }
data class PcmFormat(val sampleRateHz: Int?, val channels: Int?, val encoding: PcmEncoding)
enum class SourceFormatBasis { DECODER_OUTPUT, CONTAINER_WITH_ASSUMED_PCM16 }
data class SourcePcmFormat(val format: PcmFormat, val basis: SourceFormatBasis)
enum class DurationBasis { EXTRACTOR, FIXTURE, UNKNOWN }
data class AudioDuration(val durationUs: Long?, val basis: DurationBasis)

data class AnalysisSourceIdentity(val recordingId: String, val renditionKey: String) {
    // Rendition keys can contain private resolver information; never print them.
    override fun toString() = "AnalysisSourceIdentity(redacted)"
}

enum class CoverageStatus { FULL, PARTIAL, UNKNOWN }
data class AudioCoverage(
    /** null means not measured; empty means measured and no accepted source audio. */
    val intervals: List<AudioIntervalUs>?,
    /** Source frames accepted by interval trim, before downsampling/dropped duplicate PTS. */
    val acceptedDecodedFrames: Long?,
) {
    fun status(requested: AudioIntervalUs): CoverageStatus {
        val spans = intervals ?: return CoverageStatus.UNKNOWN
        var edge = requested.startUs
        for (span in spans) {
            if (span.endUs <= edge) continue
            if (span.startUs > edge) return CoverageStatus.PARTIAL
            edge = span.endUs
            if (edge >= requested.endUs) return CoverageStatus.FULL
        }
        return CoverageStatus.PARTIAL
    }
}

object PcmPreprocessing {
    const val LEGACY = "nearest-time-mono-22050-v1"
    const val IDENTITY = "identity-float-v1"
}

/**
 * Exclusive ownership of samples transfers from decoder to its analysis job. Backends borrow
 * read-only; do not retain/mutate PCM or put it in the feature cache. Metadata is immutable.
 * sampleTimelineStartUs anchors the MATERIALIZED buffer, not the first actual decoded PTS.
 * coverage never counts sample repetitions as additional source audio.
 */
data class DecodedWindow(
    val samples: FloatArray,
    val format: PcmFormat,
    val requested: AudioIntervalUs,
    val decodeInterval: AudioIntervalUs,
    val sampleTimelineStartUs: Long,
    val coverage: AudioCoverage,
    val sourceFormats: List<SourcePcmFormat>,
    val duration: AudioDuration,
    val endOfStreamReached: Boolean?,
    val identity: AnalysisSourceIdentity,
    val preprocessingVersion: String,
    val repeatedOutputFrames: Long?,
) {
    val frameCount: Int? get() = format.channels?.takeIf { it > 0 }?.let { samples.size / it }
    val actualStartUs: Long? get() = coverage.intervals?.minOfOrNull { it.startUs }
    val actualEndUs: Long? get() = coverage.intervals?.maxOfOrNull { it.endUs }
    val coverageStatus: CoverageStatus get() = coverage.status(requested)
    val sampleInterval: AudioIntervalUs? get() {
        val rate = format.sampleRateHz?.takeIf { it > 0 } ?: return null
        val frames = frameCount ?: return null
        val durationUs = frames.toLong() * 1_000_000 / rate
        if (sampleTimelineStartUs > Long.MAX_VALUE - durationUs) return null
        return AudioIntervalUs(sampleTimelineStartUs, sampleTimelineStartUs + durationUs)
    }

    /** Structural validation only; no new beat/coverage/loudness acceptance thresholds. */
    fun invalidReason(): String? = when {
        !requested.valid || !decodeInterval.valid || !requested.contains(decodeInterval) -> "INVALID_INTERVAL"
        sampleTimelineStartUs < 0 || sampleTimelineStartUs != decodeInterval.startUs -> "INVALID_SAMPLE_ORIGIN"
        format.sampleRateHz == null || format.channels == null -> "MISSING_PCM_FORMAT"
        format.sampleRateHz !in 8_000..192_000 || format.channels !in 1..8 -> "UNSUPPORTED_PCM_FORMAT"
        format.encoding != PcmEncoding.FLOAT32 -> "UNSUPPORTED_PCM_ENCODING"
        samples.isEmpty() -> "EMPTY_PCM"
        samples.size % format.channels != 0 -> "INCOMPLETE_PCM_FRAME"
        sampleInterval == null || sampleInterval?.endUs?.let { it > decodeInterval.endUs || it <= sampleTimelineStartUs } == true -> "INVALID_PCM_DURATION"
        preprocessingVersion.isBlank() -> "MISSING_PREPROCESSING_VERSION"
        duration.durationUs != null && duration.durationUs <= 0 -> "INVALID_DURATION"
        coverage.acceptedDecodedFrames != null && coverage.acceptedDecodedFrames < 0 -> "INVALID_FRAME_COUNT"
        repeatedOutputFrames != null && repeatedOutputFrames !in 0..frameCount!!.toLong() -> "INVALID_REPETITION_COUNT"
        coverage.intervals?.any { !it.valid || !decodeInterval.contains(it) } == true -> "INVALID_COVERAGE"
        coverage.intervals?.zipWithNext()?.any { (a, b) -> b.startUs < a.endUs } == true -> "UNORDERED_COVERAGE"
        else -> null
    }

    override fun toString() = "DecodedWindow(format=$format, frames=$frameCount, coverage=$coverageStatus)"
}

fun interface AnalysisCancellation {
    fun throwIfCancelled()
    companion object { val NONE = AnalysisCancellation {} }
}

/** Future JNI can poll this token; Phase 2.1 creates no native handles or threads. */
class AnalysisCancellationToken : AnalysisCancellation {
    private val canceled = AtomicBoolean()
    fun cancel() { canceled.set(true) }
    override fun throwIfCancelled() {
        if (canceled.get()) throw CancellationException("AutoMix analysis canceled")
    }
}

data class AnalysisControl(
    val deadlineNanos: Long = Long.MAX_VALUE,
    val cancellation: AnalysisCancellation = AnalysisCancellation.NONE,
) {
    fun checkpoint() = checkAnalysisCancellation(deadlineNanos, cancellation)
}

data class DecodeRequest(val interval: AudioIntervalUs, val identity: AnalysisSourceIdentity)
enum class AnalysisFailureStage { DECODING, INPUT_VALIDATION, ANALYSIS, RESULT_VALIDATION }

/** Controlled subtype only; original exceptions stay internal for legacy retry compatibility. */
data class AnalysisFailure(
    val stage: AnalysisFailureStage,
    val category: AutoMixFailure,
    val code: String,
    internal val original: Exception? = null,
) {
    override fun toString() = "AnalysisFailure(stage=$stage, category=$category, code=$code)"
    internal fun throwLegacy(): Nothing { throw original ?: AnalysisUnavailable(category.name) }
}

sealed interface DecodeResult {
    data class Decoded(val window: DecodedWindow) : DecodeResult
    data class Failed(val failure: AnalysisFailure) : DecodeResult
}

/** Generic source handle keeps this JVM boundary independent of Android/source providers. */
fun interface AnalysisWindowDecoder<S> {
    fun decode(source: S, request: DecodeRequest, control: AnalysisControl): DecodeResult
}

