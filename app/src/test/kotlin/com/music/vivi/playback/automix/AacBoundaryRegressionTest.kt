package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class AacBoundaryRegressionTest {
    private fun capture(rate:Int,seconds:Int=1)=CorrectedPcmWindowCapture(
        DecodeRequest(AudioIntervalUs(0,seconds*1_000_000L),AnalysisSourceIdentity("generated","aac")),
        AudioIntervalUs(0,seconds*1_000_000L),AudioDuration(seconds*1_000_000L,DurationBasis.FIXTURE))
    private fun format(rate:Int,ch:Int=2,enc:PcmEncoding=PcmEncoding.PCM16)=
        SourcePcmFormat(PcmFormat(rate,ch,enc),SourceFormatBasis.DECODER_OUTPUT)
    private fun pcm(start:Int,n:Int,ch:Int=2,enc:PcmEncoding=PcmEncoding.PCM16)=
        ByteBuffer.allocate(n*ch*if(enc==PcmEncoding.PCM16)2 else 4).order(ByteOrder.nativeOrder()).apply {
            repeat(n){i -> repeat(ch){c -> val v=(10000*sin((start+i)*.037)+c*2000).toInt().toShort()
                if(enc==PcmEncoding.PCM16)putShort(v) else putFloat(v/32768f)}};flip()
        }
    @Test fun unadjustedCodecTailRetentionSignatureStillRejectsTheExactPair35Gap() {
        val c=capture(44100,32)
        c.accept(pcm(0,1010),0,format(44100),AnalysisControl())
        assertEquals(1010L,c.acceptedFrames)
        assertEquals(listOf(AudioIntervalUs(0,22902)),c.coverage.intervals)
        try {c.accept(pcm(1010,1024),23219,format(44100),AnalysisControl());fail("Unexplained gap must not pass")}
        catch(e:PcmPreparationException){assertEquals(DecoderFailureSubtype.SOURCE_GAP,e.subtype)}
        assertEquals(14.0,c.preparation.lastBoundary!!.deltaFrames,.06)
        assertEquals(0L,c.preparation.synthesizedTimelineFrames)
    }
    @Test fun ownedTailRetentionEmitsOldFramesWithTheirOriginalPtsNotTheNextPacketsPts() {
        val c=capture(44100);val tail=AacAnalysisTailTrim(14,44100);val events=mutableListOf<Pair<Int,Long>>()
        val emit={b:ByteBuffer,p:Long,f:SourcePcmFormat -> events+=b.remaining()/4 to p;c.accept(b,p,f,AnalysisControl())}
        tail.accept(pcm(0,1024),0,format(44100),AnalysisControl(),emit)
        assertEquals(1010L,c.acceptedFrames)
        tail.accept(pcm(1024,1024),23219,format(44100),AnalysisControl(),emit)
        assertEquals(listOf(1010 to 0L,14 to 22902L,1010 to 23219L),events)
        assertEquals(2034L,c.acceptedFrames);assertEquals(0,c.preparation.gapCount)
        tail.finish(false,AnalysisControl(),emit);assertEquals(2048L,c.acceptedFrames)
    }
    @Test fun tailManagedPcmIsSampleExactAgainstContiguousReferenceAcrossRatesChannelsAndEncodings() {
        for(rate in listOf(44100,48000))for(ch in listOf(1,2))for(enc in listOf(PcmEncoding.PCM16,PcmEncoding.FLOAT32)) {
            val corrected=capture(rate);val reference=capture(rate);val tail=AacAnalysisTailTrim(14,rate)
            var frame=0
            while(frame<rate){val n=minOf(1024,rate-frame)
                tail.accept(pcm(frame,n,ch,enc),frame*1_000_000L/rate,format(rate,ch,enc),AnalysisControl()) {b,p,f->corrected.accept(b,p,f,AnalysisControl())}
                frame+=n
            }
            tail.finish(false,AnalysisControl()){b,p,f->corrected.accept(b,p,f,AnalysisControl())}
            reference.accept(pcm(0,rate,ch,enc),0,format(rate,ch,enc),AnalysisControl())
            val a=corrected.finish(false,AnalysisControl());val b=reference.finish(false,AnalysisControl())
            assertArrayEquals(b.samples,a.samples,0f);assertEquals(b.coverage,a.coverage)
            val maximumError=a.samples.indices.maxOf {abs(a.samples[it]-b.samples[it])}
            println("AAC_TAIL_PARITY rate=$rate channels=$ch encoding=$enc accepted=${a.coverage.acceptedDecodedFrames} output=${a.frameCount} maxAbsError=$maximumError coverage=${a.coverageStatus}")
            assertEquals(rate.toLong(),a.preparation!!.acceptedSourceFrames)
            assertEquals(22050,a.frameCount);assertEquals(CoverageStatus.FULL,a.coverageStatus)
            assertEquals(0,a.preparation.gapCount);assertEquals(0L,a.preparation.droppedOverlapFrames)
            assertEquals(0L,a.preparation.synthesizedTimelineFrames);assertEquals(0L,a.repeatedOutputFrames)
        }
    }
    @Test fun onlyNaturalEosDiscardsDeclaredPaddingAndCannotCreateFullCoverage() {
        for(natural in listOf(false,true)) {
            val c=capture(44100);val tail=AacAnalysisTailTrim(14,44100)
            tail.accept(pcm(0,44100),0,format(44100),AnalysisControl()){b,p,f->c.accept(b,p,f,AnalysisControl())}
            tail.finish(natural,AnalysisControl()){b,p,f->c.accept(b,p,f,AnalysisControl())}
            assertEquals(if(natural)44086L else 44100L,c.acceptedFrames)
            assertEquals(if(natural)CoverageStatus.PARTIAL else CoverageStatus.FULL,c.coverage.status(AudioIntervalUs(0,1_000_000)))
            assertEquals(if(natural)14L else 0L,tail.discardedAtNaturalEos)
            assertEquals(0,tail.retainedFrames)
        }
    }
    @Test fun genuineGapsRegressionsAndOverlapsAreNotHiddenByTheTailQueue() {
        for(delta in listOf(23L,318L,10000L,-50000L)) {
            val c=capture(44100);val tail=AacAnalysisTailTrim(14,44100)
            tail.accept(pcm(0,1024),0,format(44100),AnalysisControl()){b,p,f->c.accept(b,p,f,AnalysisControl())}
            try {tail.accept(pcm(1024,1024),23219+delta,format(44100),AnalysisControl()){b,p,f->c.accept(b,p,f,AnalysisControl())};fail("delta=$delta")}
            catch(e:PcmPreparationException){assertEquals(if(delta>0)DecoderFailureSubtype.SOURCE_GAP else DecoderFailureSubtype.TIMESTAMP_REGRESSION,e.subtype)}
            assertEquals(0L,c.preparation.synthesizedTimelineFrames)
        }
    }
    @Test fun veryShortBuffersAndOffsetViewsPreserveEveryByteAndTimestamp() {
        val tail=AacAnalysisTailTrim(14,44100);val result=ArrayList<Short>()
        val emit={b:ByteBuffer,_:Long,_:SourcePcmFormat->b.order(ByteOrder.nativeOrder());while(b.hasRemaining())result+=b.short}
        repeat(80){i -> val b=ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putInt(-1).putShort(i.toShort()).putShort((-i).toShort()).apply{flip();position(4)}
            tail.accept(b,i*1_000_000L/44100,format(44100),AnalysisControl(),emit)
            assertTrue(tail.retainedFrames<=14)
        }
        tail.finish(false,AnalysisControl(),emit)
        assertEquals((0 until 80).flatMap {listOf(it.toShort(),(-it).toShort())},result)
    }
    @Test fun trimSelectionIsCorrectedAacOnlyAndNeverAltersFrontDelayOrOtherFormats() {
        assertEquals(0,AacAnalysisTrimPolicy.codecPadding(true,"audio/mp4a-latm",14))
        for(mime in listOf("audio/opus","audio/flac","audio/raw"))assertEquals(14,AacAnalysisTrimPolicy.codecPadding(true,mime,14))
        assertEquals(14,AacAnalysisTrimPolicy.codecPadding(false,"audio/mp4a-latm",14))
        assertNull(AacAnalysisTrimPolicy.codecPadding(true,"audio/mp4a-latm",null))
        assertEquals(0,AacAnalysisTrimPolicy.codecPadding(true,"audio/mp4a-latm",0))
    }
    @Test fun cancellationDeadlinesMalformedFormatsAndBoundsRemainControlled() {
        val token=AnalysisCancellationToken().apply{cancel()}
        try {AacAnalysisTailTrim(14,44100).accept(pcm(0,1024),0,format(44100),AnalysisControl(cancellation=token)){_,_,_->fail()};fail()}
        catch(_:CancellationException){}
        try {AacAnalysisTailTrim(14,44100).accept(pcm(0,1024),0,format(44100),AnalysisControl(0)){_,_,_->fail()};fail()}
        catch(e:AnalysisUnavailable){assertEquals("ANALYSIS_TIMEOUT",e.reason)}
        try {AacAnalysisTailTrim(8193,44100);fail()}catch(_:IllegalArgumentException){}
        try {AacAnalysisTailTrim(14,44100).accept(ByteBuffer.allocate(3),0,format(44100),AnalysisControl()){_,_,_->fail()};fail()}
        catch(e:PcmPreparationException){assertEquals(DecoderFailureSubtype.MALFORMED_PCM_BUFFER,e.subtype)}
    }
}
