package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compressed Opus PTS and decoded PCM endpoints are not interchangeable.
 * Keep real preroll/delay-support packets; capture still trims using actual output PTS.
 * No codec timestamp, sample or coverage interval is changed by this policy.
 */
internal data class OpusAnalysisWindow(
    val codecDelayUs: Long?,
    val declaredSeekPrerollUs: Long?,
    val appliedSeekPrerollUs: Long,
    val seekTargetUs: Long?,
    val inputBoundaryUs: Long,
) {
    companion object {
        // Opus seeks require at least 80 ms of decoder convergence (RFC 7845 §4.6).
        private const val MIN_PREROLL_US = 80_000L
        // Bound analysis support to the largest OpusHead 16-bit pre-skip at 48 kHz.
        // Larger/malformed declarations fail safely; no unbounded seeks/read-ahead.
        internal const val MAX_SUPPORT_NS = 65_535L * 1_000_000_000 / 48_000

        fun create(startUs: Long, endUs: Long, codecDelay: ByteBuffer?, seekPreroll: ByteBuffer?): OpusAnalysisWindow {
            require(startUs >= 0 && endUs > startUs)
            val delayUs = nanoseconds(codecDelay)?.let(::ceilMicroseconds)
            val declaredPrerollUs = nanoseconds(seekPreroll)?.let(::ceilMicroseconds)
            val prerollUs = maxOf(MIN_PREROLL_US, declaredPrerollUs ?: 0)
            val target = (startUs - prerollUs).coerceAtLeast(0).takeIf { it > 0 }
            val supportUs = delayUs ?: 0
            if (endUs > Long.MAX_VALUE - supportUs) fail()
            return OpusAnalysisWindow(delayUs, declaredPrerollUs, prerollUs, target, endUs + supportUs)
        }

        private fun nanoseconds(buffer: ByteBuffer?): Long? {
            if (buffer == null) return null // Unknown is not an invented codec delay.
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            if (view.remaining() != Long.SIZE_BYTES) fail()
            return view.long.also { if (it !in 0..MAX_SUPPORT_NS) fail() }
        }
        private fun ceilMicroseconds(ns: Long) = (ns + 999) / 1000
        private fun fail(): Nothing = throw PcmPreparationException(DecoderFailureSubtype.UNSUPPORTED_OPUS_TIMING)
    }
}
