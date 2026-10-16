package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class DecoderSeekPolicyTest {
    @Test fun correctedInitialReadPreservesPrimingThatAnExplicitZeroSeekWouldDiscard() {
        // Packet geometry from the committed FFmpeg AAC fixture: a negative-PTS priming
        // packet precedes the first audible packet. This models extractor positioning,
        // not Android MediaCodec execution (which must be checked on the phone).
        val packetPts = listOf(-21_333L, 0L, 21_333L, 42_666L)
        fun firstPacket(mode: AnalysisPreprocessing): Long {
            val seek = DecoderSeekPolicy.targetUs(mode, 0)
            return if (seek == null) packetPts.first() else packetPts.last { it <= seek }
        }
        assertNull(DecoderSeekPolicy.targetUs(AnalysisPreprocessing.BANDLIMITED, 0))
        assertEquals(-21_333L, firstPacket(AnalysisPreprocessing.BANDLIMITED))
        assertEquals(0L, firstPacket(AnalysisPreprocessing.LEGACY))
    }

    @Test fun legacyAndNonzeroSeekPositionsRemainUnchanged() {
        for (start in listOf(0L, 1000L, 3_000_000L, 180_000_000L)) {
            assertEquals(start, DecoderSeekPolicy.targetUs(AnalysisPreprocessing.LEGACY, start))
            if (start > 0) assertEquals(start, DecoderSeekPolicy.targetUs(AnalysisPreprocessing.BANDLIMITED, start))
        }
        assertEquals(AnalysisPreprocessing.LEGACY, AnalysisPreprocessing.PRODUCTION)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeSeekRequestsRemainInvalid() {
        DecoderSeekPolicy.targetUs(AnalysisPreprocessing.BANDLIMITED, -1)
    }
}
