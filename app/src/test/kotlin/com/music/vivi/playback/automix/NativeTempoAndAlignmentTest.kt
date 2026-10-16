// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class NativeTempoAndAlignmentTest {
    @Test fun hypothesesDescribeRatiosInsteadOfRelabelingMeasurements() {
        val h=NativeTempoCompatibility.hypotheses(87.0,174.0)
        assertEquals(3,h.size)
        val paired=h.single {it.relation==MetricalRelation.INCOMING_DOUBLE_PULSE}
        assertEquals(87.0,paired.measuredOutgoingBpm,0.0)
        assertEquals(174.0,paired.measuredIncomingBpm,0.0)
        assertEquals(87.0,paired.effectiveIncomingBpm,0.0)
        assertEquals(0.0,paired.relativeDifference,0.0)
        assertEquals(0.0,paired.periodDifferenceUs,0.0)
    }
    @Test fun periodAndHypotheticalRateHaveDocumentedUnits() {
        val h=NativeTempoCompatibility.hypotheses(120.0,121.0).first()
        assertEquals(1.0,h.absoluteDifferenceBpm,0.0)
        assertEquals(1/120.0,h.relativeDifference,1e-12)
        assertEquals(60_000_000/121.0-500_000,h.periodDifferenceUs,1e-9)
        assertEquals(120/121.0,h.hypotheticalIncomingRate,0.0)
    }
    @Test fun malformedTempoDoesNotCreateCandidates() {
        for(v in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,-1.0,59.0,221.0))
            assertThrows(IllegalArgumentException::class.java) {NativeTempoCompatibility.hypotheses(v,120.0)}
    }
    @Test fun largeAbsoluteOriginsDoNotLoseMicrosecondPrecision() {
        val origin=Long.MAX_VALUE-32_000_000
        val a=List(20) {origin+it*500_000}
        val b=List(20) {137L+it*500_000}
        val match=NativeBeatAlignment.evaluate(a,b,0,0,16,NativeTempoCompatibility.hypotheses(120.0,120.0).first())
        assertEquals(0,match.maxResidualUs)
        assertEquals(0,match.signedEndDriftUs)
        assertEquals(17,match.matchedPulses)
    }
    @Test fun missingPulseIsNotHiddenByNearestNeighborRotation() {
        val a=List(20) {it*500_000L}
        val b=a.filterIndexed {i,_ ->i!=5}
        val match=NativeBeatAlignment.evaluate(a,b,0,0,16,NativeTempoCompatibility.hypotheses(120.0,120.0).first())
        assertEquals(500_000,match.signedEndDriftUs)
        assertEquals(500_000,match.maxResidualUs)
        assertTrue(match.medianResidualUs>0)
    }
    @Test fun fullOverlapDriftIsMeasuredNotJustInitialPhase() {
        val a=List(65) {it*500_000L}
        val b=List(65) {it*501_000L}
        val match=NativeBeatAlignment.evaluate(a,b,0,0,64,NativeTempoCompatibility.hypotheses(120.0,60_000_000/501000.0).first())
        assertEquals(0,match.initialPhaseOffsetUs)
        assertEquals(64_000,match.signedEndDriftUs)
        assertEquals(32_000,match.medianResidualUs)
        assertTrue(abs(match.predictedTempoDriftUs-64_000)<=1)
    }
    @Test fun unsupportedIndicesAndArithmeticOverflowAreRejected() {
        val h=NativeTempoCompatibility.hypotheses(120.0,120.0).first()
        assertThrows(IllegalArgumentException::class.java) {NativeBeatAlignment.evaluate(listOf(0),listOf(0),0,0,64,h)}
        assertThrows(ArithmeticException::class.java) {NativeBeatAlignment.evaluate(listOf(Long.MIN_VALUE,Long.MAX_VALUE),listOf(0,1),0,0,1,h)}
    }
}
