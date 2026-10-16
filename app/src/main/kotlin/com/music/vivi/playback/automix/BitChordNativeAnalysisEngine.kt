// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Internally selected only. Never admits native evidence through LegacyPlanningFeatures. */
class BitChordNativeAnalysisEngine internal constructor(private val bridge: NativeDspBridge) : AnalysisEngine {
    constructor() : this(JniBitChordBridge())
    override val backend = BACKEND

    override fun analyze(window: DecodedWindow, detail: AnalysisDetail, control: AnalysisControl): AnalysisResult {
        fun fail(code: String, category: AutoMixFailure, stage: AnalysisFailureStage = AnalysisFailureStage.ANALYSIS) =
            AnalysisResult.Failed(backend, AnalysisFailure(stage, category, code))
        val result = try {
            control.checkpoint()
            val invalid = inputFailure(window)
            if (invalid != null) return fail(invalid, if (invalid.contains("COVERAGE") || invalid == "EMPTY_PCM")
                AutoMixFailure.INSUFFICIENT_AUDIO_DATA else AutoMixFailure.UNSUPPORTED_FORMAT, AnalysisFailureStage.INPUT_VALIDATION)
            val packet = bridge.analyze(window, control)
            control.checkpoint()
            when (packet.status) {
                "CANCELED" -> fail("NATIVE_CANCELED", AutoMixFailure.ANALYSIS_CANCELED)
                "DEADLINE" -> fail("NATIVE_DEADLINE", AutoMixFailure.ANALYSIS_TIMEOUT)
                "ALLOCATION_FAILURE" -> fail("NATIVE_ALLOCATION_FAILURE", AutoMixFailure.NATIVE_FAILED)
                "INTERNAL_ERROR" -> fail("NATIVE_INTERNAL_ERROR", AutoMixFailure.NATIVE_FAILED)
                "INVALID_INPUT" -> fail("NATIVE_INVALID_INPUT", AutoMixFailure.UNSUPPORTED_FORMAT, AnalysisFailureStage.INPUT_VALIDATION)
                "INVALID_NATIVE_OUTPUT" -> fail("NATIVE_INVALID_OUTPUT", AutoMixFailure.NATIVE_INVALID_OUTPUT, AnalysisFailureStage.RESULT_VALIDATION)
                "OK_UNCALIBRATED_EVIDENCE", "NO_EVIDENCE" -> map(packet, window, detail, control)
                else -> fail("NATIVE_UNKNOWN_STATUS", AutoMixFailure.NATIVE_INVALID_OUTPUT, AnalysisFailureStage.RESULT_VALIDATION)
            }
        } catch (error: NativeBridgeFailure) {
            fail(if (error.code in setOf("NATIVE_LIBRARY_UNAVAILABLE", "NATIVE_VERSION_MISMATCH")) error.code else "NATIVE_NULL_PACKET",
                if (error.code in setOf("NATIVE_LIBRARY_UNAVAILABLE", "NATIVE_VERSION_MISMATCH")) AutoMixFailure.NATIVE_UNAVAILABLE else AutoMixFailure.NATIVE_FAILED)
        } catch (_: LinkageError) { fail("NATIVE_LINKAGE_FAILURE", AutoMixFailure.NATIVE_UNAVAILABLE)
        } catch (_: OutOfMemoryError) { fail("NATIVE_ALLOCATION_FAILURE", AutoMixFailure.NATIVE_FAILED)
        } catch (_: CancellationException) { fail("NATIVE_CANCELED", AutoMixFailure.ANALYSIS_CANCELED)
        } catch (_: InterruptedException) { fail("NATIVE_CANCELED", AutoMixFailure.ANALYSIS_CANCELED)
        } catch (error: AnalysisUnavailable) {
            fail(if (AutoMixFailure.of(error) == AutoMixFailure.ANALYSIS_TIMEOUT) "NATIVE_DEADLINE" else "NATIVE_ANALYSIS_UNAVAILABLE", AutoMixFailure.of(error))
        } catch (_: IllegalArgumentException) { fail("NATIVE_INVALID_OUTPUT", AutoMixFailure.NATIVE_INVALID_OUTPUT, AnalysisFailureStage.RESULT_VALIDATION)
        } catch (_: ArithmeticException) { fail("NATIVE_TIMESTAMP_OVERFLOW", AutoMixFailure.NATIVE_INVALID_OUTPUT, AnalysisFailureStage.RESULT_VALIDATION)
        } catch (_: Exception) { fail("NATIVE_INTERNAL_ERROR", AutoMixFailure.NATIVE_FAILED) }
        // Cancellation/deadline wins even if loading or another technical failure raced it.
        return try {
            control.checkpoint()
            result
        } catch (_: CancellationException) { fail("NATIVE_CANCELED", AutoMixFailure.ANALYSIS_CANCELED)
        } catch (_: InterruptedException) { fail("NATIVE_CANCELED", AutoMixFailure.ANALYSIS_CANCELED)
        } catch (error: AnalysisUnavailable) {
            fail(if (AutoMixFailure.of(error) == AutoMixFailure.ANALYSIS_TIMEOUT) "NATIVE_DEADLINE" else "NATIVE_CONTROL_FAILED", AutoMixFailure.of(error))
        } catch (_: Exception) { fail("NATIVE_CONTROL_FAILED", AutoMixFailure.NATIVE_FAILED) }
    }

    private fun inputFailure(w: DecodedWindow): String? {
        w.invalidReason()?.let { return it }
        if (w.preprocessingVersion != PcmPreprocessing.BANDLIMITED) return "NATIVE_REQUIRES_CORRECTED_PCM"
        if (w.format != PcmFormat(22050, 1, PcmEncoding.FLOAT32)) return "NATIVE_REQUIRES_CANONICAL_PCM"
        if (w.samples.size > 22050 * 32) return "NATIVE_INPUT_TOO_LARGE"
        if (w.coverage.status(w.decodeInterval) != CoverageStatus.FULL ||
            w.coverage.status(requireNotNull(w.sampleInterval)) != CoverageStatus.FULL ||
            (w.coverage.acceptedDecodedFrames ?: 0) <= 0) return "NATIVE_INCOMPLETE_SOURCE_COVERAGE"
        if (w.repeatedOutputFrames != 0L || w.preparation?.let { it.gapCount != 0 || it.synthesizedTimelineFrames != 0L } == true)
            return "NATIVE_SYNTHETIC_SOURCE_COVERAGE"
        if (w.sampleTimelineStartUs > Long.MAX_VALUE - ceil(w.samples.size * 1_000_000.0 / 22050).toLong())
            return "NATIVE_TIMESTAMP_OVERFLOW"
        // Native API also checks finite samples inside bounded cancellation-aware loops.
        return null
    }

    private fun map(p: NativeDspPacket, w: DecodedWindow, detail: AnalysisDetail, control: AnalysisControl): AnalysisResult {
        require(p.schema == 1 && p.scalars.size == 17 && p.beatsSeconds.size <= 512 && p.heuristicDownbeatsSeconds.size <= 512)
        val v = p.scalars
        require(v.all { it.isFinite() })
        require(v[0] == w.samples.size.toDouble() && v[1] == 22050.0 && v[2] == (w.samples.size / 2).toDouble() && v[3] == 11025.0)
        require(abs(v[4] - w.samples.size / 22050.0) < 1e-10 && abs(v[5] - v[2] / 11025.0) < 1e-10)
        // Summing hundreds of thousands of squares can put a DC RMS a few ulps above
        // its peak. Preserve both measured values; this is NOT a confidence threshold.
        require(v[9] in 0.0..1.0 && v[10] >= 0 && v[11] >= 0 && v[10] <= v[11] * (1 + 1e-9) &&
            (12..14).all { v[it] >= 0 } && v[16] >= 0)
        require(v[15] in 0.0..1024.0 && v[15] == v[15].roundToLong().toDouble())
        fun times(values: DoubleArray): List<Long> {
            require(values.all { it.isFinite() && it >= 0 && it < v[5] })
            require(values.asList().zipWithNext().all { (a,b) -> b > a })
            val converted = values.map { relativeBeatUs(it, requireNotNull(w.sampleInterval)) }
            require(converted.zipWithNext().all { (a,b) -> b > a })
            return converted
        }
        val beats = times(p.beatsSeconds)
        val downbeats = times(p.heuristicDownbeatsSeconds)
        require(downbeats.all { it in beats })
        if (v[6] == 0.0) require(beats.isEmpty() && downbeats.isEmpty() && v[7] == 0.0)
        else require(v[6] in 60.0..220.0 && beats.isNotEmpty() && abs(v[7] - 60 / v[6]) < 1e-9 &&
            abs(v[8] - p.beatsSeconds.first()) < 1e-9)
        require(v[8] >= 0 && v[8] < v[5])
        if (p.status == "OK_UNCALIBRATED_EVIDENCE") require(v[6] > 0)
        else require(v[6] == 0.0 || v[11] == 0.0) // Upstream's exact-silence guard preserves raw false grid.
        val diagnostic = NativeAnalysisDiagnostic(p.status, "upstream-sinc11025", w.samples.size, v[2].toInt(), 11025,
            v[6], v[9], v[12], v[13], v[14], v[16], v[15].toInt(),
            if (v[6] > 0) listOf(v[6]/2, v[6], v[6]*2).filter { it in 60.0..220.0 } else emptyList())
        fun <T> absent() = MusicalValue<T>(null, EvidenceNature.UNAVAILABLE, EvidenceMethod.NOT_AVAILABLE)
        fun <T> inferred(value: T, method: EvidenceMethod = EvidenceMethod.BITCHORD_TEMPO_TRACKER) =
            MusicalValue(value, EvidenceNature.INFERRED, method)
        val usable = p.status == "OK_UNCALIBRATED_EVIDENCE"
        val evidence = MusicalEvidence(
            if (usable) inferred(v[6]) else absent(), if (usable) inferred(beats) else absent(),
            if (usable && downbeats.isNotEmpty()) inferred(downbeats, EvidenceMethod.BITCHORD_FOUR_BEAT_BASS_ACCENT) else absent(),
            if (usable) inferred(v[9], EvidenceMethod.BITCHORD_UNCALIBRATED_CONFIDENCE) else absent(),
            absent(), absent(), absent(),
            MusicalValue(v[10], EvidenceNature.MEASURED, EvidenceMethod.SUPPLIED_PCM_STATISTIC),
            MusicalValue(v[11], EvidenceNature.MEASURED, EvidenceMethod.SUPPLIED_PCM_STATISTIC),
            absent(), absent(), absent(), absent(),
            "bitchord-fe95bb60: uncalibrated tempo/phase/separation heuristic, NOT a probability or legacy confidence; " +
                "four-beat bass-accent downbeats are heuristic; half/double-time hypotheses unverified",
        )
        control.checkpoint()
        return AnalysisResultValidation.validate(AnalysisResult.Completed(AnalysisMetadata(backend,
            w.preprocessingVersion, w.requested, w.sampleInterval, w.coverage, w.coverageStatus, w.identity, detail,
            w.duration, w.sourceFormats, w.repeatedOutputFrames, w.endOfStreamReached, w.preparation, w.decoderDiagnostic,
            diagnostic), evidence), w, backend, detail)
    }

    companion object {
        const val VERSION = "fe95bb60-controlled-v1-jni1-evidence1-internal11025"
        val BACKEND = AnalysisBackend("bitchord-native-dsp", VERSION)
        val PROFILE = AnalysisProfile(BACKEND, PcmPreprocessing.BANDLIMITED)
        internal fun relativeBeatUs(seconds: Double, interval: AudioIntervalUs): Long {
            require(interval.valid && seconds.isFinite() && seconds >= 0 && seconds < 32.0)
            val absolute = Math.addExact(interval.startUs, (seconds * 1_000_000).roundToLong())
            require(absolute >= interval.startUs && absolute < interval.endUs)
            return absolute
        }
    }
}
