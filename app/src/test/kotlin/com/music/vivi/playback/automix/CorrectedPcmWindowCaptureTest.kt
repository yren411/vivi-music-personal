package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CorrectedPcmWindowCaptureTest {
    private val identity=AnalysisSourceIdentity("fixture","rendition")
    private fun capture(start:Long=0,end:Long=1_000_000,requestedEnd:Long=end)=CorrectedPcmWindowCapture(
        DecodeRequest(AudioIntervalUs(start,requestedEnd),identity),AudioIntervalUs(start,end),AudioDuration(end,DurationBasis.FIXTURE))
    private fun format(rate:Int=8000,ch:Int=1,enc:PcmEncoding=PcmEncoding.FLOAT32,basis:SourceFormatBasis=SourceFormatBasis.DECODER_OUTPUT)=
        SourcePcmFormat(PcmFormat(rate,ch,enc),basis)
    private fun buffer(frames:Int,rate:Int=8000,ch:Int=1,enc:PcmEncoding=PcmEncoding.FLOAT32)=
        ByteBuffer.allocate(frames*ch*if(enc==PcmEncoding.PCM16)2 else 4).order(ByteOrder.nativeOrder()).apply {
            repeat(frames) { repeat(ch) { channel -> val value=if(channel==0)0.5f else -0.25f
                if(enc==PcmEncoding.PCM16)putShort((value*32768).toInt().toShort()) else putFloat(value) } };flip()
        }
    private fun CorrectedPcmWindowCapture.add(frames:Int,pts:Long,rate:Int=8000,ch:Int=1,enc:PcmEncoding=PcmEncoding.FLOAT32)=
        accept(buffer(frames,rate,ch,enc),pts,format(rate,ch,enc),AnalysisControl())
    private fun failed(expected:DecoderFailureSubtype,body:()->Unit) {
        try { body();fail("expected " + expected) }catch(e:PcmPreparationException){assertEquals(expected,e.subtype)}
    }
    @Test fun exactNonzeroPtsAndSourceFramesRemainSeparateFromResampledFrames() {
        val c=capture(12_000_000,13_000_000);c.add(48000,12_000_000,48000)
        val w=c.finish(true,AnalysisControl())
        assertNull(w.invalidReason());assertEquals(22050,w.frameCount);assertEquals(48000L,w.coverage.acceptedDecodedFrames)
        assertEquals(AudioIntervalUs(12_000_000,13_000_000),w.sampleInterval)
        assertEquals(CoverageStatus.FULL,w.coverageStatus);assertEquals(0L,w.repeatedOutputFrames)
        assertEquals(PcmPreprocessing.BANDLIMITED,w.preprocessingVersion)
        assertTrue(w.preparation!!.edgeSupportedOutputFrames>0);assertEquals(0L,w.preparation.synthesizedTimelineFrames)
    }
    @Test fun rationalBufferClockAvoidsAccumulatedPtsRoundingDrift() {
        val c=capture();var offset=0
        while(offset<48000){val size=minOf(257,48000-offset);c.add(size,offset*1_000_000L/48000,48000);offset+=size}
        val w=c.finish(false,AnalysisControl())
        assertEquals(CoverageStatus.FULL,w.coverageStatus);assertEquals(22050,w.frameCount)
        assertTrue(w.preparation!!.jitterAdjustedBuffers>0);assertTrue(w.preparation.maximumJitterUs<=1)
    }
    @Test fun frameClockTrimsAcrossSourceRatesNonzeroOriginsAndNegativePrimingPts() {
        for(rate in listOf(8000,11025,16000,22050,32000,44100,48000,96000)) {
            for(start in listOf(0L,12345000L)) {
                val c=capture(start,start+1000000)
                val sourceOrigin=start-50000
                var offset=0
                val sourceFrames=(rate*1.1).toInt()+512
                while(offset<sourceFrames) {
                    val frames=minOf(257,sourceFrames-offset)
                    c.add(frames,sourceOrigin+offset*1000000L/rate,rate)
                    offset+=frames
                }
                val w=c.finish(true,AnalysisControl())
                assertNull(w.invalidReason());assertEquals(CoverageStatus.FULL,w.coverageStatus)
                assertEquals(start,w.sampleTimelineStartUs);assertEquals(start,w.actualStartUs)
                assertEquals(start+1000000,w.actualEndUs);assertEquals(22050,w.frameCount)
                assertEquals(0L,w.preparation!!.synthesizedTimelineFrames)
            }
        }
    }
    @Test fun integerMicrosecondJitterDoesNotHideAMissingSourceFrame() {
        val contiguous=capture();contiguous.add(22050,0,44100);contiguous.add(22050,500001,44100)
        val full=contiguous.finish(false,AnalysisControl())
        assertEquals(CoverageStatus.FULL,full.coverageStatus)
        assertEquals(1L,full.preparation!!.maximumJitterUs)
        val gap=capture();gap.add(22050,0,44100)
        failed(DecoderFailureSubtype.SOURCE_GAP){gap.add(22050,500023,44100)}
        assertEquals(listOf(AudioIntervalUs(0,500000)),gap.coverage.intervals)
    }
    @Test fun duplicateAndOverlappingBuffersDoNotCreateCoverageOrRepeatedOutput() {
        val c=capture(end=2_000_000);c.add(8000,0);c.add(8000,0);c.add(8000,500000);c.add(4000,1500000)
        val w=c.finish(true,AnalysisControl())
        assertEquals(16000L,w.coverage.acceptedDecodedFrames);assertEquals(12000L,w.preparation!!.droppedOverlapFrames)
        assertEquals(CoverageStatus.FULL,w.coverageStatus);assertEquals(44100,w.frameCount)
    }
    @Test fun actualGapIsRetainedAsPartialCoverageAndNeverMaterializedAsSilenceOrRepetition() {
        val c=capture();c.add(3000,0)
        failed(DecoderFailureSubtype.SOURCE_GAP){c.add(3000,500000)}
        assertEquals(CoverageStatus.PARTIAL,c.coverage.status(AudioIntervalUs(0,1000000)))
        assertEquals(listOf(AudioIntervalUs(0,375000)),c.coverage.intervals)
        assertEquals(1,c.preparation.gapCount);assertEquals(0L,c.preparation.synthesizedTimelineFrames)
    }
    @Test fun timestampRegressionsAreDistinguishedFromDuplicates() {
        val c=capture(end=2_000_000);c.add(4000,0);c.add(4000,500000)
        failed(DecoderFailureSubtype.TIMESTAMP_REGRESSION){c.add(4000,250000)}
    }
    @Test fun precedingSyncAndPrimingAreTrimmedWithFramePrecision() {
        val c=capture(500000,1500000);c.add(16000,0)
        val w=c.finish(true,AnalysisControl())
        assertEquals(8000L,w.coverage.acceptedDecodedFrames);assertEquals(500000L,w.actualStartUs)
        assertEquals(1500000L,w.actualEndUs);assertEquals(22050,w.frameCount)
    }
    @Test fun partialEosIsRepresentedHonestlyEvenWhenPcmBufferIsFullyMaterialized() {
        val c=capture();c.add(6400,0)
        val w=c.finish(true,AnalysisControl())
        assertEquals(17640,w.frameCount);assertEquals(800000L,w.actualEndUs)
        assertEquals(CoverageStatus.PARTIAL,w.coverageStatus);assertEquals(true,w.endOfStreamReached)
        assertNull(w.invalidReason())
    }
    @Test fun lateFirstPtsHasActualAlignedOriginRatherThanInventedLeadingAudio() {
        val c=capture();c.add(7992,1000)
        val w=c.finish(true,AnalysisControl())
        assertEquals(1000L,w.sampleTimelineStartUs);assertEquals(1000L,w.actualStartUs)
        assertEquals(CoverageStatus.PARTIAL,w.coverageStatus);assertNull(w.invalidReason())
    }
    @Test fun endpointClampRetainsOriginalRequestAndExtractorDuration() {
        val c=capture(end=1000000,requestedEnd=1100000);c.add(8000,0)
        val w=c.finish(true,AnalysisControl())
        assertEquals(CoverageStatus.PARTIAL,w.coverageStatus)
        assertEquals(CoverageStatus.FULL,w.coverage.status(w.decodeInterval))
        assertEquals(1000000L,w.duration.durationUs);assertEquals(DurationBasis.FIXTURE,w.duration.basis)
    }
    @Test fun missingCodecFormatIsUnknownCoverageNotFabricated() {
        val c=capture();c.accept(buffer(8000),0,format(basis=SourceFormatBasis.CONTAINER_WITH_ASSUMED_PCM16),AnalysisControl())
        val w=c.finish(true,AnalysisControl());assertEquals(CoverageStatus.UNKNOWN,w.coverageStatus)
        assertNull(w.coverage.intervals);assertEquals(8000L,w.coverage.acceptedDecodedFrames)
    }
    @Test fun pcm16AndFloatStereoUseArithmeticMeanWithoutWrongChannelOrRateMetadata() {
        for(enc in listOf(PcmEncoding.PCM16,PcmEncoding.FLOAT32)) {
            val c=capture();c.add(44100,0,44100,2,enc)
            val w=c.finish(true,AnalysisControl());assertEquals(PcmFormat(22050,1,PcmEncoding.FLOAT32),w.format)
            assertEquals(format(44100,2,enc),w.sourceFormats.single())
            assertTrue(w.samples.all { kotlin.math.abs(it-0.125f)<0.00001 })
        }
    }
    @Test fun malformedBuffersInvalidFormatsAndNonfiniteSamplesHaveDistinctFailures() {
        failed(DecoderFailureSubtype.MALFORMED_PCM_BUFFER){capture().accept(ByteBuffer.allocate(3),0,format(),AnalysisControl())}
        failed(DecoderFailureSubtype.UNSUPPORTED_PCM){capture().accept(buffer(1),0,format(0),AnalysisControl())}
        failed(DecoderFailureSubtype.UNSUPPORTED_PCM){capture().accept(buffer(1),0,format(enc=PcmEncoding.UNKNOWN),AnalysisControl())}
        val nan=ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).apply { putFloat(Float.NaN);flip() }
        failed(DecoderFailureSubtype.NONFINITE_PCM){capture().accept(nan,0,format(),AnalysisControl())}
    }
    @Test fun midWindowFormatChangesAreExplicitlyUnsupportedUntilIndependentlyValidated() {
        val c=capture();c.add(4000,0)
        failed(DecoderFailureSubtype.FORMAT_CHANGED){c.add(22050,500000,44100)}
    }
    @Test fun emptyEosAndCancellationAreSafe() {
        val c=capture();c.accept(ByteBuffer.allocate(0),0,format(),AnalysisControl())
        failed(DecoderFailureSubtype.EMPTY_OUTPUT){c.finish(true,AnalysisControl())}
        var n=0;val control=AnalysisControl(cancellation=AnalysisCancellation{if(++n>2)throw java.util.concurrent.CancellationException()})
        try { capture().accept(buffer(8000),0,format(),control);fail() }catch(_:java.util.concurrent.CancellationException){}
        try { capture().accept(buffer(1),0,format(),AnalysisControl(System.nanoTime()-1));fail() }
        catch(e:AnalysisUnavailable){assertEquals("ANALYSIS_TIMEOUT",e.reason)}
    }
}
