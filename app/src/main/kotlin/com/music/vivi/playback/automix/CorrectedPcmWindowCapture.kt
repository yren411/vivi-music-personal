package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * One bounded contiguous source region. Buffer PTS anchors a rational source frame clock.
 * Sub-half-frame PTS quantization maps to the uniquely nearest source-frame clock.
 * Exact half-frame ties are rejected. Adjustments are counted; whole-frame
 * overlaps are trimmed; real gaps/regressions remain failures, never filled with fake audio.
 */
internal class CorrectedPcmWindowCapture(
    private val request: DecodeRequest,
    private val interval: AudioIntervalUs,
    private val duration: AudioDuration,
) {
    init { require(interval.valid && request.interval.contains(interval) && interval.endUs - interval.startUs <= 32_000_000) }
    private var pcm = FloatArray(0)
    private var size = 0
    private var sourceRate = 0
    private var firstUs = 0.0
    private var previousBufferPts: Long? = null
    private var accepted = 0L
    private var dropped = 0L
    private var jitterBuffers = 0L
    private var maxJitter = 0L
    private var gaps = 0
    private var unknown = false
    private var edgeFrames = 0L
    private val formats = linkedSetOf<SourcePcmFormat>()
    var reachedEnd = false
        private set
    val acceptedFrames get() = accepted
    val sourceFormats get() = formats.toList()
    val preparation get() = PcmPreparationMetadata(accepted, dropped, jitterBuffers, maxJitter, gaps, edgeFrames)
    val coverage: AudioCoverage get() {
        val end = min(interval.endUs, floor(firstUs + size.toDouble() * 1_000_000 / sourceRate.coerceAtLeast(1) + 1e-6).toLong())
        val start = max(interval.startUs, floor(firstUs).toLong())
        return AudioCoverage(if (unknown) null else if (size == 0 || end <= start) emptyList()
            else listOf(AudioIntervalUs(start, end)), accepted)
    }

    fun accept(buffer: ByteBuffer, ptsUs: Long, format: SourcePcmFormat, control: AnalysisControl) {
        control.checkpoint()
        val rate = format.format.sampleRateHz ?: fail(DecoderFailureSubtype.UNSUPPORTED_PCM)
        val channels = format.format.channels ?: fail(DecoderFailureSubtype.UNSUPPORTED_PCM)
        val bytes = when (format.format.encoding) {
            PcmEncoding.PCM16 -> 2
            PcmEncoding.FLOAT32 -> 4
            else -> fail(DecoderFailureSubtype.UNSUPPORTED_PCM)
        }
        if (rate !in 8_000..192_000 || channels !in 1..8) fail(DecoderFailureSubtype.UNSUPPORTED_PCM)
        if (buffer.remaining() % (bytes * channels) != 0) fail(DecoderFailureSubtype.MALFORMED_PCM_BUFFER)
        val frames = buffer.remaining() / (bytes * channels)
        if (frames == 0) return // zero-sized codec EOS has no source timestamp evidence
        if (sourceRate != 0 && formats.last().format != format.format) fail(DecoderFailureSubtype.FORMAT_CHANGED)
        formats.add(format)
        if (format.basis != SourceFormatBasis.DECODER_OUTPUT) unknown = true
        if (sourceRate == 0) {
            sourceRate = rate
            // Single bounded mono source allocation; no per-frame objects or full-track PCM.
            pcm = FloatArray(ceil((interval.endUs - interval.startUs).toDouble() * rate / 1_000_000).toInt() + 2)
        }
        val step = 1_000_000.0 / rate
        val tolerance = step / 2 // Unique nearest-frame cell; never enough to hide one missing frame.
        val expected = firstUs + size * step
        if (size > 0 && ptsUs < (previousBufferPts ?: ptsUs) - tolerance) fail(DecoderFailureSubtype.TIMESTAMP_REGRESSION)
        previousBufferPts = ptsUs
        var adjustedPts = ptsUs.toDouble()
        if (size > 0 && abs(adjustedPts - expected) < tolerance) {
            val delta = abs(adjustedPts - expected)
            recordJitter(delta)
            adjustedPts = expected
        }
        var from = max(0, floor((interval.startUs - adjustedPts) / step).toInt())
        if (size > 0 && adjustedPts + from * step < expected - tolerance) {
            val overlap = ceil((expected - adjustedPts - tolerance) / step).toInt().coerceAtLeast(0)
            dropped += min(frames, overlap).coerceAtLeast(from) - from
            from = max(from, overlap)
        }
        val until = min(frames, ceil((interval.endUs - adjustedPts) / step - 1e-9).toInt()).coerceAtLeast(0)
        if (from >= until) {
            if (adjustedPts >= interval.endUs) reachedEnd = true
            return
        }
        var start = adjustedPts + from * step
        if (size > 0) {
            if (start >= expected + tolerance) { gaps++; fail(DecoderFailureSubtype.SOURCE_GAP) }
            if (abs(start - expected) < tolerance) {
                recordJitter(abs(start - expected))
                start = expected
            }
            else fail(DecoderFailureSubtype.TIMESTAMP_REGRESSION)
        } else firstUs = start
        val input = buffer.duplicate().order(ByteOrder.nativeOrder())
        input.position(buffer.position() + from * bytes * channels)
        for (frame in from until until) {
            if (frame % 512 == 0) control.checkpoint()
            var mono = 0.0
            repeat(channels) {
                val value = if (bytes == 4) input.float.toDouble() else input.short / 32768.0
                if (!value.isFinite()) fail(DecoderFailureSubtype.NONFINITE_PCM)
                mono += value
            }
            if (size >= pcm.size) fail(DecoderFailureSubtype.MALFORMED_PCM_BUFFER)
            pcm[size++] = (mono / channels).toFloat()
            // Only source frames intersecting the requested interval are accepted, no duplicate padding.
            accepted++
        }
        if (firstUs + size * step >= interval.endUs - 1e-6) reachedEnd = true
    }

    fun finish(eos: Boolean, control: AnalysisControl): DecodedWindow {
        control.checkpoint()
        if (size == 0) fail(DecoderFailureSubtype.EMPTY_OUTPUT)
        // Millisecond origin preserves the current legacy engine's API without mislabelling PTS.
        val origin = ceil(max(interval.startUs.toDouble(), firstUs) / 1000).toLong() * 1000
        val end = min(interval.endUs.toDouble(), firstUs + size.toDouble() * 1_000_000 / sourceRate)
        val count = floor((end - origin) * 22_050 / 1_000_000 + 1e-8).toInt().coerceAtLeast(0)
        if (count == 0) fail(DecoderFailureSubtype.EMPTY_OUTPUT)
        val converter = BandlimitedResampler(sourceRate, control = control)
        val result = converter.resample(pcm, size, (origin - firstUs) * sourceRate / 1_000_000, count, control)
        edgeFrames = result.edgeSupportedFrames
        return DecodedWindow(result.samples, PcmFormat(22_050, 1, PcmEncoding.FLOAT32), request.interval,
            interval, origin, coverage, sourceFormats, duration, eos, request.identity,
            PcmPreprocessing.BANDLIMITED, 0, preparation)
    }

    private fun recordJitter(delta: Double) {
        if (delta > 0.001) { jitterBuffers++; maxJitter = max(maxJitter, ceil(delta).toLong()) }
    }

    private fun fail(subtype: DecoderFailureSubtype): Nothing = throw PcmPreparationException(subtype)
}
