// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class PlanningSignalObservationTest {
    @Test fun quietPrefixAndTransientEventsUseActualSampleOrigin() {
        val pcm=NativePlanningFixtures.pcm(seconds=24,leadingSeconds=18.0)
        val a=NativeAnalysisFixtures.window(pcm)
        val observation=PlanningSignalObservation.measure(a,AnalysisControl())
        assertEquals(18_000_000L,observation.leadingQuietEndUs)
        assertTrue(observation.riseEventsUs.first()>=18_000_000)
        assertTrue(observation.complete)
        val shifted=PlanningSignalObservation.measure(NativeAnalysisFixtures.window(pcm,12_345_678),AnalysisControl())
        assertNull(shifted.leadingQuietEndUs)
        assertEquals(observation.riseEventsUs.map {it+12_345_678},shifted.riseEventsUs)
    }
    @Test fun silenceAndStationaryMaterialDoNotSupplyManyRhythmAccents() {
        for(pattern in listOf("silence","chord","noise","sweep")) {
            val observation=PlanningSignalObservation.measure(NativeAnalysisFixtures.window(NativePlanningFixtures.pcm(pattern=pattern)),AnalysisControl())
            assertTrue("$pattern: ${observation.riseEventsUs.size}",observation.riseEventsUs.size<6)
        }
    }
    @Test fun ordinaryRhythmsAndDifferentAmplitudesRetainTransientEvidence() {
        for(pattern in listOf("kick","backbeat","hats")) for(level in listOf(.1,1.0)) {
            val observation=PlanningSignalObservation.measure(NativeAnalysisFixtures.window(NativePlanningFixtures.pcm(pattern=pattern,amplitude=level)),AnalysisControl())
            assertTrue("$pattern $level",observation.riseEventsUs.size>=50)
            assertTrue(observation.riseEventsUs.size<=512)
        }
    }
    @Test fun malformedPcmAndWrongPreprocessingAreNotAudited() {
        val base=NativeAnalysisFixtures.window()
        for(w in listOf(base.copy(samples=floatArrayOf()),base.copy(preprocessingVersion=PcmPreprocessing.LEGACY),
            base.copy(format=PcmFormat(44100,1,PcmEncoding.FLOAT32)),base.copy(samples=base.samples.copyOf().also {it[100]=Float.NaN})))
            assertThrows(IllegalArgumentException::class.java) {PlanningSignalObservation.measure(w,AnalysisControl())}
    }
    @Test fun longConversionHonorsCancellationAndDeadline() {
        val calls=AtomicInteger()
        val control=AnalysisControl(cancellation=AnalysisCancellation {if(calls.incrementAndGet()==3)throw CancellationException()})
        assertThrows(CancellationException::class.java) {PlanningSignalObservation.measure(NativeAnalysisFixtures.window(),control)}
        assertEquals(3,calls.get())
        assertThrows(AnalysisUnavailable::class.java) {PlanningSignalObservation.measure(NativeAnalysisFixtures.window(),AnalysisControl(0))}
    }
}
