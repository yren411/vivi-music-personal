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
    @Test fun reportedFlacSubFrameOffsetDoesNotDropAFrameAndManufactureASourceGap() {
        val c=capture(end=30_000_000)
        val total=30*44100
        var offset=0
        while(offset<total) {
            val frames=minOf(4608,total-offset)
            // Device: 1,156,608 frames accepted; next 4,608-frame buffer ended at
            // 26,331,422us, hence its PTS was 26,226,933us. Expected=26,226,938.7755us.
            val pts=if(offset==1_156_608)26_226_933L else offset*1_000_000L/44100
            c.add(frames,pts,44100,enc=PcmEncoding.PCM16)
            offset+=frames
        }
        val w=c.finish(true,AnalysisControl())
        assertEquals(CoverageStatus.FULL,w.coverageStatus)
        assertEquals(1_323_000L,w.coverage.acceptedDecodedFrames)
        assertEquals(661500,w.frameCount);assertEquals(AudioIntervalUs(0,30_000_000),w.sampleInterval)
        assertEquals(0L,w.preparation!!.droppedOverlapFrames);assertEquals(0,w.preparation.gapCount)
        assertEquals(6L,w.preparation.maximumJitterUs)
        assertEquals(0L,w.preparation.synthesizedTimelineFrames);assertEquals(0L,w.repeatedOutputFrames)
    }
    @Test fun subFrameOffsetsUseTheNearestSourceFrameWithoutDroppingAudioAcrossRates() {
        for(rate in listOf(8000,11025,16000,22050,32000,44100,48000,96000,192000))for(sign in listOf(-1,1)) {
            val jitter=kotlin.math.floor(0.4*1_000_000/rate).toLong()*sign
            val c=capture(end=2_000_000);c.add(rate,0,rate);c.add(rate,1_000_000+jitter,rate)
            assertEquals(2L*rate,c.acceptedFrames)
            assertEquals(CoverageStatus.FULL,c.coverage.status(AudioIntervalUs(0,2_000_000)))
            assertEquals(0L,c.preparation.droppedOverlapFrames);assertEquals(0,c.preparation.gapCount)
            assertEquals(kotlin.math.abs(jitter),c.preparation.maximumJitterUs)
        }
    }
    @Test fun aWholeMissingFrameStillFailsEvenWithTimestampJitterAcrossRates() {
        for(rate in listOf(8000,11025,16000,22050,32000,44100,48000,96000,192000))for(sign in listOf(-1,1)) {
            val step=1_000_000.0/rate
            val pts=1_000_000+kotlin.math.round(step).toLong()+kotlin.math.floor(0.4*step).toLong()*sign
            val c=capture(end=2_000_000);c.add(rate,0,rate)
            failed(DecoderFailureSubtype.SOURCE_GAP){c.add(rate,pts,rate)}
            assertEquals(rate.toLong(),c.acceptedFrames)
            assertEquals(listOf(AudioIntervalUs(0,1_000_000)),c.coverage.intervals)
            assertEquals(0L,c.preparation.synthesizedTimelineFrames)
        }
    }
    @Test fun aWholeDuplicateFrameIsTrimmedExactlyOnceDespiteSubFrameJitter() {
        for(rate in listOf(8000,11025,16000,22050,32000,44100,48000,96000,192000))for(sign in listOf(-1,1)) {
            val step=1_000_000.0/rate
            val pts=1_000_000-kotlin.math.round(step).toLong()+kotlin.math.floor(0.4*step).toLong()*sign
            val c=capture(end=2_000_000);c.add(rate,0,rate);c.add(rate+1,pts,rate)
            assertEquals(2L*rate,c.acceptedFrames)
            assertEquals(1L,c.preparation.droppedOverlapFrames)
            assertEquals(CoverageStatus.FULL,c.coverage.status(AudioIntervalUs(0,2_000_000)))
            assertEquals(0,c.preparation.gapCount)
        }
    }
    @Test fun exactlyHalfFrameOffsetsAreAmbiguousAndCannotCreateCoverage() {
        for(sign in listOf(-1,1)) {
            val c=capture(end=2_000_000);c.add(10000,0,10000)
            failed(if(sign>0)DecoderFailureSubtype.SOURCE_GAP else DecoderFailureSubtype.TIMESTAMP_REGRESSION) {
                c.add(10000,1_000_000+sign*50L,10000)
            }
            assertEquals(10000L,c.acceptedFrames)
            assertEquals(listOf(AudioIntervalUs(0,1_000_000)),c.coverage.intervals)
        }
    }
    @Test fun subFrameTimestampQuantizationDoesNotChangeAnyResampledPcmValues() {
        val exact=capture();val jittered=capture()
        for(block in 0 until 8) {
            val data=ByteBuffer.allocate(2000).order(ByteOrder.nativeOrder()).apply {
                repeat(1000){putShort((12000*kotlin.math.sin((block*1000+it)*0.07)).toInt().toShort())};flip()
            }
            val pts=block*125_000L
            exact.accept(data,pts,format(8000,1,PcmEncoding.PCM16),AnalysisControl())
            jittered.accept(data,pts+if(block==0)0 else if(block%2==0)35 else -35,
                format(8000,1,PcmEncoding.PCM16),AnalysisControl())
        }
        val a=exact.finish(true,AnalysisControl());val b=jittered.finish(true,AnalysisControl())
        assertArrayEquals(a.samples,b.samples,0f)
        assertEquals(a.sampleInterval,b.sampleInterval);assertEquals(a.coverage,b.coverage)
        assertEquals(0L,b.preparation!!.droppedOverlapFrames);assertEquals(0L,b.repeatedOutputFrames)
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
    @Test fun lateFirstPtsCanReachRequestedEndWithoutCodecEosAndStillHavePartialCoverage() {
        val c=capture(3_000_000,7_000_000,9_000_000)
        // Replay the reported Samsung seek geometry: first PTS 3.008s and 192512
        // decoded frames, of which only 191616 intersect the 7s clamped endpoint.
        // This is a generated PCM contract replay, not another MediaCodec execution.
        c.add(192512,3_008_000,48000)
        assertTrue(c.reachedEnd)
        val w=c.finish(false,AnalysisControl())
        assertNull(w.invalidReason())
        assertEquals(false,w.endOfStreamReached)
        assertEquals(listOf(AudioIntervalUs(3_008_000,7_000_000)),w.coverage.intervals)
        assertEquals(CoverageStatus.PARTIAL,w.coverage.status(w.decodeInterval))
        assertEquals(3_008_000L,w.sampleTimelineStartUs)
        assertEquals(191616L,w.coverage.acceptedDecodedFrames)
        assertEquals(88023,w.frameCount)
        assertEquals(AudioIntervalUs(3_008_000,6_999_972),w.sampleInterval)
        assertEquals(0L,w.repeatedOutputFrames)
        assertEquals(0L,w.preparation!!.synthesizedTimelineFrames)
    }
    @Test fun reportedAacPrimingLossStaysPartialAndIsNeverRepairedByInventingSourceFrames() {
        // Replay the device's buffer geometry, not its audio or a MediaCodec execution.
        val c=capture(end=24_000_000)
        c.add(1_150_976,21_333,48000,2,PcmEncoding.PCM16)
        val w=c.finish(true,AnalysisControl())
        assertEquals(listOf(AudioIntervalUs(21_333,23_999_999)),w.coverage.intervals)
        assertEquals(1_150_976L,w.coverage.acceptedDecodedFrames)
        assertEquals(CoverageStatus.PARTIAL,w.coverage.status(w.decodeInterval))
        assertEquals(22_000L,w.sampleTimelineStartUs)
        assertEquals(AudioIntervalUs(22_000,23_999_959),w.sampleInterval)
        assertEquals(528714,w.frameCount)
        assertEquals(0L,w.preparation!!.synthesizedTimelineFrames)
        assertEquals(0L,w.repeatedOutputFrames)
    }
    @Test fun fullAacRateClockAfterPreservedPrimingCoversTheExactRequestedEndpoint() {
        val c=capture(end=24_000_000)
        c.add(1_152_000,0,48000,2,PcmEncoding.PCM16)
        val w=c.finish(false,AnalysisControl())
        assertEquals(CoverageStatus.FULL,w.coverage.status(w.decodeInterval))
        assertEquals(AudioIntervalUs(0,24_000_000),w.sampleInterval)
        assertEquals(1_152_000L,w.coverage.acceptedDecodedFrames)
        assertEquals(529200,w.frameCount)
        assertEquals(0L,w.preparation!!.synthesizedTimelineFrames)
        assertEquals(0L,w.repeatedOutputFrames)
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
