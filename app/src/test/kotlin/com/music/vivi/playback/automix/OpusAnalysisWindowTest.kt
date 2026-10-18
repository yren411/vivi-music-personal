package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class OpusAnalysisWindowTest {
    private fun ns(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
    private fun policy(start: Long = 2_000_000, end: Long = 7_000_000) =
        OpusAnalysisWindow.create(start, end, ns(6_500_000), ns(80_000_000))
    private val identity = AnalysisSourceIdentity("generated", "opus-48000")
    private val format = SourcePcmFormat(PcmFormat(48000, 1, PcmEncoding.PCM16), SourceFormatBasis.DECODER_OUTPUT)
    private fun capture() = DecodeRequest(AudioIntervalUs(2_000_000, 7_000_000), identity).let {
        CorrectedPcmWindowCapture(it, it.interval, AudioDuration(40_008_000, DurationBasis.FIXTURE))
    }
    private fun pcm(startFrame: Long, frames: Int) = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder()).apply {
        repeat(frames) { putShort(((startFrame + it) % 32749).toShort()) }; flip()
    }

    /** Replay the owner's measured packet grid and total output frame clock, not a claim
     * to emulate Android/libopus. First-buffer 312-frame loss is inferred from the total:
     * 250*960 - 239688 = 312. Android execution remains a separate required check.
     */
    private fun replay(window: OpusAnalysisWindow?): DecodedWindow {
        val capture = capture()
        val firstPacketUs = if (window == null) 2_001_000L else 1_921_000L
        val boundary = window?.inputBoundaryUs ?: 7_000_000L
        var frames = 0L
        var packetPts = firstPacketUs
        while (packetPts < boundary) {
            val count = if (frames == 0L) 648 else 960
            val pts = firstPacketUs + frames * 1_000_000 / 48000
            capture.accept(pcm(firstPacketUs * 48000 / 1_000_000 + frames, count), pts, format, AnalysisControl())
            frames += count
            packetPts += 20_000
        }
        return capture.finish(true, AnalysisControl())
    }

    @Test fun observedPacketCutoffReproducesBothMissingHeadAndTailExactly() {
        val before = replay(null)
        assertEquals(2_001_000L, before.actualStartUs)
        assertEquals(6_994_500L, before.actualEndUs)
        assertEquals(239_688L, before.coverage.acceptedDecodedFrames)
        assertEquals(110_106, before.frameCount)
        assertEquals(CoverageStatus.PARTIAL, before.coverageStatus)
        assertEquals(0, before.preparation!!.gapCount)
    }
    @Test fun realPrerollAndDelaySupportReachTheUnchangedIntervalWithoutInventingFrames() {
        val window = policy()
        assertEquals(1_920_000L, window.seekTargetUs)
        assertEquals(7_006_500L, window.inputBoundaryUs)
        // The 7001000 packet was previously discarded. It contains the endpoint PCM.
        assertTrue(7_001_000L < window.inputBoundaryUs)
        assertFalse(7_021_000L < window.inputBoundaryUs)
        val after = replay(window)
        assertEquals(AudioIntervalUs(2_000_000, 7_000_000), after.coverage.intervals!!.single())
        assertEquals(CoverageStatus.FULL, after.coverageStatus)
        assertEquals(240_000L, after.coverage.acceptedDecodedFrames)
        assertEquals(110_250, after.frameCount)
        assertEquals(0, after.preparation!!.gapCount)
        assertEquals(0L, after.preparation.droppedOverlapFrames)
        assertEquals(0L, after.preparation.synthesizedTimelineFrames)
        assertEquals(0L, after.repeatedOutputFrames)
        val reference = capture()
        reference.accept(pcm(96_000, 240_000), 2_000_000, format, AnalysisControl())
        assertArrayEquals(reference.finish(false, AnalysisControl()).samples, after.samples, 0f)
    }
    @Test fun trueMissingSourceFramesStillFailStrictCoverage() {
        val capture = capture()
        capture.accept(pcm(96_000, 960), 2_000_000, format, AnalysisControl())
        try {
            capture.accept(pcm(96_961, 960), 2_020_021, format, AnalysisControl())
            fail("One missing source frame must not be bridged by Opus support packets")
        } catch (error: PcmPreparationException) {
            assertEquals(DecoderFailureSubtype.SOURCE_GAP, error.subtype)
        }
        assertEquals(0L, capture.preparation.synthesizedTimelineFrames)
    }
    @Test fun supportNeverSeeksPastTheStartAndNeverRewritesRequestedEndpoints() {
        assertNull(policy(0, 32_000_000).seekTargetUs)
        assertNull(policy(50_000, 5_000_000).seekTargetUs)
        assertEquals(33_920_000L, policy(34_000_000, 39_000_000).seekTargetUs)
        val unknown = OpusAnalysisWindow.create(2_000_000, 7_000_000, null, null)
        assertNull(unknown.codecDelayUs); assertNull(unknown.declaredSeekPrerollUs)
        assertEquals(80_000L, unknown.appliedSeekPrerollUs)
        assertEquals(7_000_000L, unknown.inputBoundaryUs)
        assertEquals(7_000_001L, OpusAnalysisWindow.create(0, 7_000_000, ns(1), ns(0)).inputBoundaryUs)
    }
    @Test fun malformedExcessiveAndOverflowingTimingIsRejectedWithoutMutatingCodecData() {
        val delay = ns(6_500_000); val preroll = ns(80_000_000)
        OpusAnalysisWindow.create(0, 5_000_000, delay, preroll)
        assertEquals(0, delay.position()); assertEquals(0, preroll.position())
        for (bad in listOf(ns(-1), ns(OpusAnalysisWindow.MAX_SUPPORT_NS + 1), ByteBuffer.allocate(7), ByteBuffer.allocate(9))) {
            for (isDelay in listOf(true, false)) try {
                OpusAnalysisWindow.create(0, 5_000_000, if (isDelay) bad else delay, if (isDelay) preroll else bad)
                fail("Malformed timing must not expand the decode indefinitely")
            } catch (error: PcmPreparationException) {
                assertEquals(DecoderFailureSubtype.UNSUPPORTED_OPUS_TIMING, error.subtype)
            }
        }
        try { policy(0, Long.MAX_VALUE); fail("Overflow") }
        catch (error: PcmPreparationException) { assertEquals(DecoderFailureSubtype.UNSUPPORTED_OPUS_TIMING, error.subtype) }
    }
}
