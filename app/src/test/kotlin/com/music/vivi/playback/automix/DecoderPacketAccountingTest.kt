package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class DecoderPacketAccountingTest {
    private val format=PcmFormat(44100,1,PcmEncoding.PCM16)
    @Test fun inputAndOutputCountersRemainIndependentAndZeroSizedEosIsNotAudio() {
        val p=DecoderPacketAccounting("audio/raw","c2.android.raw.decoder",format)
        p.input(9216,0);p.input(1008,104489);p.inputEnd(-1)
        p.output(9216,0,false);p.output(0,0,true)
        val r=p.snapshot()
        assertEquals(2L,r.inputBuffers);assertEquals(10224L,r.inputBytes);assertEquals(104489L,r.lastInputPtsUs)
        assertEquals(1008,r.lastInputBytes);assertEquals(-1L,r.extractorEndSampleTimeUs)
        assertEquals(1L,r.outputBuffers);assertEquals(9216L,r.outputBytes)
        assertEquals(0,r.outputEosBytes);assertEquals(0L,r.outputEosPtsUs)
        assertEquals(format,r.extractorFormat)
    }
    @Test fun snapshotsRemainImmutableAndMissingMetricsRemainUnknown() {
        val p=DecoderPacketAccounting(null,null,null);val empty=p.snapshot()
        assertNull(empty.firstInputPtsUs);assertNull(empty.outputEosBytes);assertNull(empty.extractorEndSampleTimeUs)
        p.input(8,-21333);p.output(16,-21333,false)
        val before=p.snapshot();p.input(24,100000);p.output(24,100000,true)
        assertEquals(0L,empty.inputBytes);assertEquals(8L,before.inputBytes);assertEquals(16L,before.outputBytes)
        assertEquals(-21333L,before.firstInputPtsUs);assertNull(before.outputEosBytes)
        assertEquals(32L,p.snapshot().inputBytes);assertEquals(40L,p.snapshot().outputBytes)
    }
    @Test fun trimAndSeekEvidenceIsMeasuredImmutableAndUnknownUntilObserved() {
        val p=DecoderPacketAccounting("audio/mp4a-latm","c2.android.aac.decoder",format)
        val empty=p.snapshot();assertNull(empty.encoderPaddingFrames);assertNull(empty.extractorAfterSeekUs)
        p.trim(1024,14,0);p.seek(null,-23219,-23219);p.input(100,-23219);p.tail(14,0)
        val r=p.snapshot();p.tail(0,14)
        assertEquals(14,r.encoderPaddingFrames);assertEquals(0,r.codecPaddingFrames)
        assertEquals(-23219L,r.firstInputPtsUs);assertEquals(-23219L,r.extractorAfterSeekUs)
        assertEquals(14,r.heldAacTailFrames);assertEquals(0L,r.discardedAacTailFrames)
        assertTrue(r.reportFields().contains("codecTimelineSchema=2"))
    }
    @Test fun publicFieldsRejectPrivateUrlsAndCodecMessagesRatherThanEchoingThem() {
        val hostile=DecoderPacketAccounting("audio/raw?token=SECRET","https://private/SECRET",format).snapshot().reportFields()
        for(s in listOf("SECRET","https://","private","token="))assertFalse(hostile.contains(s))
        assertTrue(hostile.contains("extractorMime=null codecName=null"))
        val safe=DecoderPacketAccounting("audio/flac","c2.android.flac.decoder",format).snapshot().reportFields()
        assertTrue(safe.contains("extractorMime=audio/flac"));assertTrue(safe.contains("codecName=c2.android.flac.decoder"))
        assertTrue(safe.contains("codecProgressSchema=1"));assertTrue(safe.length<1500)
    }
    @Test fun opusSupportEvidenceIsNumericImmutableAndUnknownForOtherFormats() {
        val p=DecoderPacketAccounting("audio/opus","c2.android.opus.decoder",PcmFormat(48000,1,PcmEncoding.PCM16))
        val before=p.snapshot();assertNull(before.opusCodecDelayUs);assertNull(before.inputBoundaryUs)
        fun ns(n:Long)=java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.nativeOrder()).putLong(n).apply{flip()}
        p.opusWindow(OpusAnalysisWindow.create(2_000_000,7_000_000,ns(6_500_000),ns(80_000_000)))
        val snapshot=p.snapshot();p.opusWindow(null)
        assertEquals(6500L,snapshot.opusCodecDelayUs);assertEquals(80000L,snapshot.opusDeclaredPrerollUs)
        assertEquals(80000L,snapshot.opusAppliedPrerollUs);assertEquals(7_006_500L,snapshot.inputBoundaryUs)
        assertNull(before.opusCodecDelayUs);assertNull(p.snapshot().opusCodecDelayUs)
        assertTrue(snapshot.reportFields().contains("opusWindowSchema=1"))
    }
}
