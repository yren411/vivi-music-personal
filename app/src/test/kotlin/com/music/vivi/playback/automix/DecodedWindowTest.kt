package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class DecodedWindowTest {
    private val identity = AnalysisSourceIdentity("recording", "rendition")
    private val sourceFormat = SourcePcmFormat(PcmFormat(8000, 2, PcmEncoding.PCM16), SourceFormatBasis.DECODER_OUTPUT)
    private fun capture(start: Long = 0, end: Long = 1000) = LegacyPcmWindowCapture(
        DecodeRequest(AudioIntervalUs(start, end), identity), AudioIntervalUs(start, end),
        AudioDuration(10_000_000, DurationBasis.EXTRACTOR), 8000)

    @Test fun accuratePcmAndSourceFormatsFramesTimestampsAndEosRemainDistinct() {
        val c = capture(1_000_000, 1_001_000)
        repeat(8) { c.accept(it / 10f, 1_000_000 + it * 125L, sourceFormat) }
        val w = c.finish(true)
        assertArrayEquals(FloatArray(8) { it / 10f }, w.samples, 0f)
        assertEquals(PcmFormat(8000, 1, PcmEncoding.FLOAT32), w.format)
        assertEquals(listOf(sourceFormat), w.sourceFormats)
        assertEquals(8, w.frameCount)
        assertEquals(8L, w.coverage.acceptedDecodedFrames)
        assertEquals(1_000_000L, w.actualStartUs)
        assertEquals(1_001_000L, w.actualEndUs)
        assertEquals(CoverageStatus.FULL, w.coverageStatus)
        assertEquals(0L, w.repeatedOutputFrames)
        assertEquals(true, w.endOfStreamReached)
        assertNull(w.invalidReason())
    }

    @Test fun gapsRetainOriginalRepeatedSamplesButAreNeverReportedAsDecodedCoverage() {
        val c = capture()
        c.accept(0.1f, 0, sourceFormat)
        c.accept(0.2f, 500, sourceFormat)
        val w = c.finish(false)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.2f, 0.2f, 0.2f), w.samples, 0f)
        assertEquals(3L, w.repeatedOutputFrames)
        assertEquals(2L, w.coverage.acceptedDecodedFrames)
        assertEquals(listOf(AudioIntervalUs(0, 125), AudioIntervalUs(500, 625)), w.coverage.intervals)
        assertEquals(CoverageStatus.PARTIAL, w.coverageStatus)
        assertEquals(625L, w.actualEndUs)
        assertEquals(false, w.endOfStreamReached)
    }

    @Test fun trimmedLeadingSourceFramesDoNotMoveLegacyBufferOriginToActualPts() {
        val c = capture()
        c.accept(1f, -125, sourceFormat)
        c.accept(0.5f, 250, sourceFormat)
        c.accept(0.8f, 1000, sourceFormat)
        val w = c.finish(false)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), w.samples, 0f)
        assertEquals(0L, w.sampleTimelineStartUs)
        assertEquals(250L, w.actualStartUs)
        assertEquals(2L, w.repeatedOutputFrames)
        assertEquals(1L, w.coverage.acceptedDecodedFrames)
        assertEquals(CoverageStatus.PARTIAL, w.coverageStatus)
    }

    @Test fun downsamplingAndDuplicatePtsKeepOldNearestTimeSamplesExactly() {
        val c = capture()
        val highRate = sourceFormat.copy(format = PcmFormat(16000, 1, PcmEncoding.FLOAT32))
        listOf(0L, 62L, 125L, 187L, 250L, 312L, 375L).forEachIndexed { i, t -> c.accept(i.toFloat(), t, highRate) }
        c.accept(99f, 0, highRate) // old monotonic writer ignores a repeated earlier PTS
        val w = c.finish(true)
        assertArrayEquals(floatArrayOf(0f, 2f, 4f, 6f), w.samples, 0f)
        assertEquals(8L, w.coverage.acceptedDecodedFrames)
        assertEquals(0L, w.repeatedOutputFrames)
        assertEquals(listOf(AudioIntervalUs(0, 438)), w.coverage.intervals)
    }

    @Test fun unverifiedFormatTimingAndTooFragmentedCoverageAreExplicitlyUnknown() {
        val c = capture()
        c.accept(0.5f, 0, sourceFormat.copy(basis = SourceFormatBasis.CONTAINER_WITH_ASSUMED_PCM16))
        val unknown = c.finish(false)
        assertEquals(CoverageStatus.UNKNOWN, unknown.coverageStatus)
        assertNull(unknown.actualStartUs)
        assertNull(unknown.coverage.acceptedDecodedFrames)
        val fragmented = capture(0, 300_000)
        repeat(270) { fragmented.accept(0.1f, it * 1000L, sourceFormat) }
        val bounded = fragmented.finish(false)
        assertEquals(CoverageStatus.UNKNOWN, bounded.coverageStatus)
        assertNull(bounded.coverage.intervals)
        assertTrue(bounded.samples.isNotEmpty())
    }

    @Test fun malformedContractsAndUnknownMetadataAreValidatedWithoutInventedCoverage() {
        val w = AnalysisPcmFixtures.window(AnalysisPcmFixtures.silence(4))
        val bad = listOf(
            w.copy(format = w.format.copy(sampleRateHz = null)) to "MISSING_PCM_FORMAT",
            w.copy(format = w.format.copy(channels = null)) to "MISSING_PCM_FORMAT",
            w.copy(format = w.format.copy(channels = 0)) to "UNSUPPORTED_PCM_FORMAT",
            w.copy(format = w.format.copy(encoding = PcmEncoding.UNKNOWN)) to "UNSUPPORTED_PCM_ENCODING",
            w.copy(format = w.format.copy(encoding = PcmEncoding.PCM16)) to "UNSUPPORTED_PCM_ENCODING",
            w.copy(samples = floatArrayOf(1f, 2f, 3f), format = w.format.copy(channels = 2)) to "INCOMPLETE_PCM_FRAME",
            w.copy(samples = FloatArray(0)) to "EMPTY_PCM",
            w.copy(requested = AudioIntervalUs(-1, 4_000_000)) to "INVALID_INTERVAL",
            w.copy(decodeInterval = AudioIntervalUs(0, -1)) to "INVALID_INTERVAL",
            w.copy(sampleTimelineStartUs = 1) to "INVALID_SAMPLE_ORIGIN",
            w.copy(coverage = AudioCoverage(listOf(AudioIntervalUs(0, 5_000_000)), 1)) to "INVALID_COVERAGE",
            w.copy(coverage = AudioCoverage(listOf(AudioIntervalUs(2_000_000, 3_000_000),
                AudioIntervalUs(0, 1_000_000)), 1)) to "UNORDERED_COVERAGE",
            w.copy(coverage = AudioCoverage(null, -1)) to "INVALID_FRAME_COUNT",
            w.copy(duration = AudioDuration(-1, DurationBasis.EXTRACTOR)) to "INVALID_DURATION",
            w.copy(repeatedOutputFrames = w.samples.size + 1L) to "INVALID_REPETITION_COUNT",
            w.copy(samples = FloatArray(w.samples.size + 100)) to "INVALID_PCM_DURATION",
        )
        for ((window, reason) in bad) assertEquals(reason, window.invalidReason())
        val missing = w.copy(coverage = AudioCoverage(null, null), sourceFormats = emptyList(),
            duration = AudioDuration(null, DurationBasis.UNKNOWN), endOfStreamReached = null,
            repeatedOutputFrames = null)
        assertNull(missing.invalidReason())
        assertEquals(CoverageStatus.UNKNOWN, missing.coverageStatus)
        assertNull(missing.actualStartUs); assertNull(missing.actualEndUs)
    }

    @Test fun decoderClampedIntervalDoesNotPretendToCoverEntireOriginalRequest() {
        val c = LegacyPcmWindowCapture(DecodeRequest(AudioIntervalUs(0, 2000), identity),
            AudioIntervalUs(0, 1000), AudioDuration(1000, DurationBasis.EXTRACTOR), 8000)
        repeat(8) { c.accept(0.1f, it * 125L, sourceFormat) }
        val w = c.finish(true)
        assertEquals(CoverageStatus.PARTIAL, w.coverageStatus)
        assertEquals(CoverageStatus.FULL, w.coverage.status(w.decodeInterval))
        assertNull(w.invalidReason())
    }

    @Test fun stereoFloatFramesAreRepresentedButNotSilentlyDownmixedByLegacyBackend() {
        val mono = AnalysisPcmFixtures.window(AnalysisPcmFixtures.silence(4))
        val stereo = mono.copy(samples = FloatArray(mono.samples.size * 2),
            format = PcmFormat(22050, 2, PcmEncoding.FLOAT32))
        assertEquals(mono.frameCount, stereo.frameCount)
        assertEquals(mono.sampleInterval, stereo.sampleInterval)
        assertNull(stereo.invalidReason())
        val result = LegacyKotlinAnalysisEngine().analyze(stereo, AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Failed
        assertEquals("LEGACY_REQUIRES_MONO_MILLISECOND_ORIGIN", result.failure.code)
    }

    @Test fun overflowingTimelineIsInvalidInsteadOfWrappingToNegativeTime() {
        val w = AnalysisPcmFixtures.window(AnalysisPcmFixtures.silence(4)).copy(
            requested = AudioIntervalUs(Long.MAX_VALUE - 100, Long.MAX_VALUE),
            decodeInterval = AudioIntervalUs(Long.MAX_VALUE - 100, Long.MAX_VALUE),
            sampleTimelineStartUs = Long.MAX_VALUE - 100,
            coverage = AudioCoverage(null, null))
        assertNull(w.sampleInterval)
        assertEquals("INVALID_PCM_DURATION", w.invalidReason())
    }

    @Test fun descriptionsNeverDumpPcmOrPrivateRenditionInformation() {
        val w = AnalysisPcmFixtures.window(AnalysisPcmFixtures.silence(4)).copy(
            identity = AnalysisSourceIdentity("private-title", "https://private.invalid/?token=secret"))
        assertFalse(w.toString().contains("private"))
        assertFalse(w.identity.toString().contains("secret"))
        val error = AnalysisFailure(AnalysisFailureStage.DECODING, AutoMixFailure.SOURCE_IO_FAILURE,
            "SOURCE_IO_FAILURE", java.io.IOException("https://private.invalid?token=secret"))
        assertFalse(error.toString().contains("secret"))
    }
}

