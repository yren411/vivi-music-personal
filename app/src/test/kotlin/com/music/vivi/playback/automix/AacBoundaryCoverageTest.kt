package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** PTS/frame mathematics only; encoded fixture and Android tests are separate evidence. */
class AacBoundaryCoverageTest {
    private val id=AnalysisSourceIdentity("generated-boundary","aac-recipe")
    private fun capture(start:Long,end:Long)=CorrectedPcmWindowCapture(DecodeRequest(AudioIntervalUs(start,end),id),
        AudioIntervalUs(start,end),AudioDuration(180_000_000,DurationBasis.FIXTURE))
    private fun pcm(first:Long,n:Int,ch:Int=2)=ByteBuffer.allocate(n*ch*2).order(ByteOrder.nativeOrder()).apply {
        repeat(n) {i->repeat(ch) {channel->putShort((10000*sin((first+i)*.001)+channel*2000).toInt().toShort())}};flip()
    }
    private fun feed(c:CorrectedPcmWindowCapture,first:Long,last:Long,rate:Int=44100,ch:Int=2) {
        var frame=first
        while(frame<last && !c.reachedEnd) {
            val n=minOf(1024L,last-frame).toInt()
            c.accept(pcm(frame,n,ch),frame*1_000_000L/rate,
                SourcePcmFormat(PcmFormat(rate,ch,PcmEncoding.PCM16),SourceFormatBasis.DECODER_OUTPUT),AnalysisControl())
            frame+=n
        }
    }
    private fun previousPacket(timeUs:Long,rate:Int=44100)=timeUs*rate/1_000_000/1024*1024
    private fun oldAndNew(start:Long,end:Long):Pair<DecodedWindow,DecodedWindow> {
        val old=capture(start,end);val fixed=capture(start,end)
        val selected=previousPacket(start)
        assertEquals(123_042_539L,selected*1_000_000/44100)
        // A freshly configured codec consumes the declared front delay; no PCM is synthesized.
        feed(old,selected+1024,6678L*1024)
        val policy=AacAnalysisWindow.create(start,44100,1024)
        feed(fixed,previousPacket(requireNotNull(policy.seekTargetUs))+1024,6678L*1024)
        return old.finish(true,AnalysisControl()) to fixed.finish(true,AnalysisControl())
    }
    @Test fun reportedNonzeroSeekLoses2759UsBeforeRepairAndRealPrerollRecoversIt() {
        val (old,fixed)=oldAndNew(123_063_000,155_000_000)
        assertEquals(123_065_759L,old.actualStartUs)
        assertEquals(CoverageStatus.PARTIAL,old.coverageStatus)
        assertEquals(123_063_000L,fixed.actualStartUs)
        assertEquals(155_000_000L,fixed.actualEndUs)
        assertEquals(CoverageStatus.FULL,fixed.coverageStatus)
        assertEquals(0L,fixed.preparation!!.synthesizedTimelineFrames)
        assertTrue(fixed.preparation.preRequestDecodedFrames>=2048)
        assertEquals(0L,fixed.preparation.droppedOverlapFrames)
        println("AAC333_SEEK before=${old.coverage} after=${fixed.coverage} accepted=${fixed.coverage.acceptedDecodedFrames}")
    }
    @Test fun reportedRoundedNaturalEndpointStillLacksRealSamplesAfterSeekRepair() {
        val (old,fixed)=oldAndNew(123_063_000,155_063_000)
        assertEquals(123_065_759L,old.actualStartUs)
        assertEquals(123_063_000L,fixed.actualStartUs)
        assertEquals(155_062_856L,fixed.actualEndUs)
        assertEquals(CoverageStatus.PARTIAL,fixed.coverageStatus)
        assertEquals(144L,155_063_000-requireNotNull(fixed.actualEndUs))
        assertEquals(0L,fixed.repeatedOutputFrames)
        assertEquals(0L,fixed.preparation!!.synthesizedTimelineFrames)
        println("AAC333_EOS actual=${fixed.coverage} expectedEnd=155063000 missingUs=144")
    }
    @Test fun positive137006UsOriginRetainsExactObservedPartialSignature() {
        val c=capture(0,32_000_000);var frames=0
        val format=SourcePcmFormat(PcmFormat(44100,2,PcmEncoding.PCM16),SourceFormatBasis.DECODER_OUTPUT)
        while(frames<1_405_952 && !c.reachedEnd) {
            c.accept(pcm(frames.toLong(),1024),137006+frames*1_000_000L/44100,format,AnalysisControl());frames+=1024
        }
        val w=c.finish(false,AnalysisControl())
        assertEquals(AudioIntervalUs(137006,32_000_000),w.coverage.intervals!!.single())
        assertEquals(1_405_159L,w.coverage.acceptedDecodedFrames)
        assertEquals(702557,w.frameCount)
        assertEquals(CoverageStatus.PARTIAL,w.coverageStatus)
        assertEquals(0,w.preparation!!.gapCount);assertEquals(0L,w.preparation.synthesizedTimelineFrames)
        assertNull(AacAnalysisWindow.create(0,44100,0).seekTargetUs)
    }
    @Test fun prerollIsRateDerivedBoundedAndPreservesInitialNegativePackets() {
        for(rate in listOf(8000,44100,48000,96000,192000))for(delay in listOf(null,0,1024,8192)) {
            val p=AacAnalysisWindow.create(123_063_000,rate,delay)
            assertTrue(p.supportUs<=1_280_000)
            assertTrue(requireNotNull(p.seekTargetUs)<123_063_000)
            assertTrue(p.supportUs*rate>=((delay ?: 0)+2048)*1_000_000L)
            assertNull(AacAnalysisWindow.create(0,rate,delay).seekTargetUs)
            assertNull(AacAnalysisWindow.create(1000,rate,delay).seekTargetUs)
        }
        assertEquals(122_993_340L,AacAnalysisWindow.create(123_063_000,44100,1024).seekTargetUs)
    }
    @Test fun malformedDelayAndRateCannotCauseUnboundedPreroll() {
        for((rate,delay) in listOf(44100 to -1,44100 to 8193,0 to 1024,192001 to 0)) {
            val e=assertThrows(PcmPreparationException::class.java){AacAnalysisWindow.create(123_063_000,rate,delay)}
            assertEquals(DecoderFailureSubtype.UNSUPPORTED_AAC_TIMING,e.subtype)
        }
    }
    @Test fun nonconstantMonoAndStereoSeekPcmMatchesForwardFrameOrderAcrossRates() {
        for(rate in listOf(44100,48000))for(ch in listOf(1,2)) {
            val start=3_063_000L;val end=8_000_000L
            val reference=capture(start,end);feed(reference,0,9L*rate,rate,ch)
            val p=AacAnalysisWindow.create(start,rate,1024)
            val seek=capture(start,end);feed(seek,previousPacket(requireNotNull(p.seekTargetUs),rate)+1024,9L*rate,rate,ch)
            val a=reference.finish(false,AnalysisControl());val b=seek.finish(false,AnalysisControl())
            assertEquals(CoverageStatus.FULL,b.coverageStatus);assertEquals(a.frameCount,b.frameCount)
            val maxError=a.samples.indices.maxOf {abs(a.samples[it]-b.samples[it])}
            // Distinct integer-µs codec anchors can differ by <1 µs; this bound is <7 PCM16 LSB.
            assertTrue("rate=$rate channels=$ch maxError=$maxError",maxError<.0002f)
            assertEquals(0L,b.preparation!!.droppedOverlapFrames)
            println("AAC333_FRAME_PARITY rate=$rate channels=$ch maxError=$maxError")
        }
    }
    @Test fun anActualMissingAccessUnitStillFailsAfterPreroll() {
        val c=capture(0,1_000_000);feed(c,0,2048)
        val e=assertThrows(PcmPreparationException::class.java){feed(c,3072,4096)}
        assertEquals(DecoderFailureSubtype.SOURCE_GAP,e.subtype)
        assertEquals(2048L,c.acceptedFrames);assertEquals(1,c.preparation.gapCount)
    }
    @Test fun duplicateAccessUnitIsDroppedWithoutAdditionalCoverage() {
        val c=capture(0,1_000_000);feed(c,0,2048);feed(c,1024,2048);feed(c,2048,44100)
        val w=c.finish(false,AnalysisControl())
        assertEquals(44100L,w.coverage.acceptedDecodedFrames)
        assertEquals(1024L,w.preparation!!.droppedOverlapFrames)
        assertEquals(0L,w.repeatedOutputFrames)
    }
    @Test fun codecOwnedFrontTrimIsNotAppliedTwiceToInitialPcm() {
        val c=capture(0,1_000_000);feed(c,0,44100)
        val w=c.finish(true,AnalysisControl())
        assertEquals(CoverageStatus.FULL,w.coverageStatus);assertEquals(44100L,w.coverage.acceptedDecodedFrames)
        assertEquals(0L,w.preparation!!.preRequestDecodedFrames)
        assertNull(AacAnalysisWindow.create(0,44100,1024).seekTargetUs)
    }
    @Test fun v7EvidenceCannotBeReusedByCurrentNativeProfile() {
        assertEquals("sinc64-blackman-opus-support-mono-22050-v7",PcmPreprocessing.BANDLIMITED_V7)
        assertNotEquals(PcmPreprocessing.BANDLIMITED_V7,BitChordNativeAnalysisEngine.PROFILE.preprocessingVersion)
        val w=NativeAnalysisFixtures.window().copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V7)
        val result=BitChordNativeAnalysisEngine(object:NativeDspBridge {
            override fun analyze(window:DecodedWindow,control:AnalysisControl):NativeDspPacket = error("Old PCM must not reach JNI")
        }).analyze(w,AnalysisDetail.DETAIL,AnalysisControl())
        assertTrue(result is AnalysisResult.Failed)
        assertEquals("NATIVE_REQUIRES_CORRECTED_PCM",(result as AnalysisResult.Failed).failure.code)
        assertEquals(AnalysisPreprocessing.LEGACY,AnalysisPreprocessing.PRODUCTION)
    }
    @Test fun v7AndV8CacheAndInflightWorkRemainSeparateAfterCancellation() {
        val profile=BitChordNativeAnalysisEngine.PROFILE
        val oldKey=profile.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V7).cacheKey(id,0,4000,AnalysisDetail.SCAN)
        val key=profile.cacheKey(id,0,4000,AnalysisDetail.SCAN)
        assertNotEquals(oldKey,key)
        val entered=java.util.concurrent.CountDownLatch(2);val release=java.util.concurrent.CountDownLatch(1)
        val oldValue=Any();val value=Any()
        AnalysisWorkers<Any>(AutoMixPerformance.BALANCED).use {workers ->
            fun work(v:Any):Any {entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return v}
            val old=workers.submit(oldKey){work(oldValue)};val current=workers.submit(key){work(value)}
            try {assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));assertNotSame(old,current)}
            finally {release.countDown()}
            assertSame(oldValue,old.get(5,java.util.concurrent.TimeUnit.SECONDS))
            assertSame(value,current.get(5,java.util.concurrent.TimeUnit.SECONDS));workers.cancelAll()
            assertSame(oldValue,workers.submit(oldKey){error("Old cache lost")}.get())
            assertSame(value,workers.submit(key){error("Current cache lost")}.get())
        }
    }
}
