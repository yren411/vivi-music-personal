package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class DecoderDiagnosticsContractTest {
    @Test fun eosBufferWithoutPcmDoesNotInterpretOffsetAsAudio() {
        for(offset in listOf(0,128,65536)) {
            val empty=DecoderPcmBuffers.readable(null,offset,0,true)
            assertEquals(0,empty.remaining())
            assertEquals(0,DecoderPcmBuffers.readable(null,offset,0,false).remaining())
        }
    }
    @Test fun codecBufferBoundsHaveControlledFailuresAndValidSliceKeepsBytes() {
        for(bounds in listOf(-1 to 4,0 to -1,6 to 4,Int.MAX_VALUE to 4)) {
            try { DecoderPcmBuffers.readable(ByteBuffer.allocate(8),bounds.first,bounds.second,true);fail() }
            catch(e:PcmPreparationException){assertEquals(DecoderFailureSubtype.MALFORMED_PCM_BUFFER,e.subtype)}
        }
        val buffer=ByteBuffer.wrap(byteArrayOf(1,2,3,4,5,6))
        val actual=DecoderPcmBuffers.readable(buffer,2,3,true)
        assertEquals(3,actual.remaining());assertEquals(3,actual.get().toInt());assertEquals(5,actual.limit())
    }
    @Test fun shortRequestAndTruncatedOutputAreNotTheSameDiagnosticCause() {
        assertEquals(DecoderFailureSubtype.REQUEST_TOO_SHORT,DecoderFailureClassification.subtype(AnalysisUnavailable("insufficient-audio")))
        assertEquals(DecoderFailureSubtype.TRUNCATED_WINDOW,DecoderFailureClassification.subtype(AnalysisUnavailable("truncated-audio-window")))
        assertEquals(AutoMixFailure.INSUFFICIENT_AUDIO_DATA,AutoMixFailure.of(AnalysisUnavailable("insufficient-audio")))
        assertEquals(AutoMixFailure.INSUFFICIENT_AUDIO_DATA,AutoMixFailure.of(AnalysisUnavailable("truncated-audio-window")))
    }
    @Test fun sourceBudgetCancellationAndCodecSelectionHaveControlledSubtypes() {
        for((reason,expected) in listOf("source-not-range-seekable" to DecoderFailureSubtype.RANGE_UNSUPPORTED,
            "software-decoder-unavailable" to DecoderFailureSubtype.SOFTWARE_CODEC_UNAVAILABLE,
            "analysis-byte-budget-exhausted" to DecoderFailureSubtype.BYTE_BUDGET,
            "ANALYSIS_TIMEOUT" to DecoderFailureSubtype.DEADLINE,
            "unknown-or-unsupported-duration" to DecoderFailureSubtype.DURATION_UNAVAILABLE)) {
            assertEquals(expected,DecoderFailureClassification.subtype(AnalysisUnavailable(reason)))
        }
        assertEquals(DecoderFailureSubtype.CANCELED,DecoderFailureClassification.subtype(java.util.concurrent.CancellationException()))
        assertEquals(DecoderFailureSubtype.SOURCE_ACCESS,DecoderFailureClassification.subtype(java.io.IOException("https://SECRET")))
        assertEquals(DecoderFailureSubtype.UNCLASSIFIED,DecoderFailureClassification.subtype(IllegalStateException("cookie=SECRET")))
    }
    @Test fun preprocessingDiagnosticCannotDisagreeWithPcmIdentity() {
        val window=AnalysisPcmFixtures.window(AnalysisPcmFixtures.silence(4))
        val diagnostic=DecoderDiagnostic(AnalysisPreprocessing.BANDLIMITED,DecoderStage.COMPLETE,DecoderFailureSubtype.NONE,null,window.requested)
        assertEquals("INCONSISTENT_DECODER_PREPROCESSING",window.copy(decoderDiagnostic=diagnostic).invalidReason())
    }
}
