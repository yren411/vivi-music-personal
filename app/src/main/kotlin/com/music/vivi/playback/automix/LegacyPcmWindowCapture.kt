package com.music.vivi.playback.automix

/**
 * The old decoder's nearest-time writer, unchanged numerically. Metadata observes source PTS;
 * it does not correct gaps, seek endpoints, resampling or the half-second acceptance threshold.
 */
internal class LegacyPcmWindowCapture(
    private val request: DecodeRequest,
    private val decodeInterval: AudioIntervalUs,
    private val duration: AudioDuration,
    private val targetRate: Int = 22_050,
) {
    private val samples = FloatArray(((decodeInterval.endUs - decodeInterval.startUs) / 1000 * targetRate / 1000).toInt())
    var written: Int = 0
        private set
    val capacity get() = samples.size
    private var repeated = 0L
    private var accepted = 0L
    private val spans = mutableListOf<AudioIntervalUs>()
    private var spanStart = -1L
    private var spanEnd = -1L
    private val formats = linkedSetOf<SourcePcmFormat>()
    private var coverageUnknown = false
    private fun retainSpan() {
        if (spans.size < 256) spans += AudioIntervalUs(spanStart, spanEnd)
        else coverageUnknown = true // Metadata bound only: PCM writing/acceptance is unchanged.
    }

    fun accept(mono: Float, timeUs: Long, sourceFormat: SourcePcmFormat) {
        if (timeUs < decodeInterval.startUs || timeUs >= decodeInterval.endUs) return
        val rate = requireNotNull(sourceFormat.format.sampleRateHz)
        accepted++
        formats.add(sourceFormat)
        if (sourceFormat.basis != SourceFormatBasis.DECODER_OUTPUT) coverageUnknown = true
        val end = minOf(decodeInterval.endUs, timeUs + (1_000_000L + rate - 1) / rate)
        if (spanStart < 0) { spanStart = timeUs; spanEnd = end }
        else if (timeUs <= spanEnd + 1 && end >= spanStart) {
            spanStart = minOf(spanStart, timeUs); spanEnd = maxOf(spanEnd, end)
        } else {
            retainSpan()
            spanStart = timeUs; spanEnd = end
        }
        val index = ((timeUs - decodeInterval.startUs) * targetRate / 1_000_000).toInt()
        if (index in written until samples.size) {
            repeated += index - written
            while (written <= index) samples[written++] = mono
        }
    }

    fun finish(endOfStreamReached: Boolean): DecodedWindow {
        val all = (spans + if (spanStart >= 0) listOf(AudioIntervalUs(spanStart, spanEnd)) else emptyList())
            .sortedBy { it.startUs }
        val merged = mutableListOf<AudioIntervalUs>()
        for (span in all) {
            val previous = merged.lastOrNull()
            if (previous != null && span.startUs <= previous.endUs) {
                merged[merged.lastIndex] = AudioIntervalUs(previous.startUs, maxOf(previous.endUs, span.endUs))
            } else merged += span
        }
        return DecodedWindow(samples.copyOf(written), PcmFormat(targetRate, 1, PcmEncoding.FLOAT32),
            request.interval, decodeInterval, decodeInterval.startUs,
            AudioCoverage(if (coverageUnknown) null else merged.toList(), if (coverageUnknown) null else accepted), formats.toList(), duration, endOfStreamReached,
            request.identity, PcmPreprocessing.LEGACY, repeated)
    }
}
