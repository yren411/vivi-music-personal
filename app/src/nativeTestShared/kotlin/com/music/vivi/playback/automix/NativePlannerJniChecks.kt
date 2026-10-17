// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*

/** Real DSP -> actual evidence -> actual planner, shared by Android and optional host JNI. */
internal object NativePlannerJniChecks {
    fun compatiblePair(engine:AnalysisEngine, bpm:Double=128.0):NativePlanningOutcome.Selected {
        val a=NativePlanningFixtures.analyze(engine,NativePlanningFixtures.pcm(bpm),150_000_000,"out")
        val b=NativePlanningFixtures.analyze(engine,NativePlanningFixtures.pcm(bpm,phase=.333),0,"in")
        val result=NativeAwareAutoMixPlanner().plan(NativePlanningFixtures.request(a,b),a,b)
        assertTrue("$result",result is NativePlanningOutcome.Selected)
        result as NativePlanningOutcome.Selected
        assertEquals(NativePlanReadiness.TIMING_AND_TRANSIENT_SUPPORTED,result.plan.readiness)
        val c=result.plan.candidate
        assertTrue(c.overlapUs>=6_000_000)
        assertTrue(c.outgoingCueUs<160_000_000)
        assertTrue(c.incomingCueUs in 0..1_000_000)
        assertTrue(c.alignment.maxResidualUs<=70_000)
        assertTrue(c.alignment.p95ResidualUs<=40_000)
        assertEquals(1.0,result.plan.playbackRate,0.0)
        assertEquals(BitChordNativeAnalysisEngine.PROFILE,result.plan.outgoingProfile)
        return result
    }
    fun nonRhythmicMaterialIsRejected(engine:AnalysisEngine) {
        val a=NativePlanningFixtures.analyze(engine,NativePlanningFixtures.pcm(),150_000_000,"out")
        for(pattern in listOf("silence","chord","noise","sweep")) {
            val b=NativePlanningFixtures.analyze(engine,NativePlanningFixtures.pcm(pattern=pattern),0,"in")
            val result=NativeAwareAutoMixPlanner().plan(NativePlanningFixtures.request(a,b),a,b)
            assertTrue("$pattern: $result",result is NativePlanningOutcome.Fallback)
            println("NATIVE_PLANNER_NEGATIVE pattern=$pattern reason=${(result as NativePlanningOutcome.Fallback).reason}")
            // Identical false grids pass tempo/alignment checks. Their actual PCM must still
            // fail the transient audit; BPM disagreement alone is not a false-positive test.
            val negativeOut=NativePlanningFixtures.analyze(engine,NativePlanningFixtures.pcm(pattern=pattern),150_000_000,"out")
            val same=NativeAwareAutoMixPlanner().plan(NativePlanningFixtures.request(negativeOut,b),negativeOut,b)
            assertTrue("same-$pattern: $same",same is NativePlanningOutcome.Fallback)
            assertTrue((same as NativePlanningOutcome.Fallback).reason in setOf(PlanningRejection.SIGNAL_NOT_CORROBORATED,
                PlanningRejection.MISSING_BEATS,PlanningRejection.CUE_NOT_PRESERVING_INTRO,PlanningRejection.INSUFFICIENT_BEATS))
        }
    }
    fun laterCueRequiresRealQuietPrefix(engine:AnalysisEngine) {
        val a=NativePlanningFixtures.analyze(engine,NativePlanningFixtures.pcm(),150_000_000,"out")
        val b=NativePlanningFixtures.analyze(engine,NativePlanningFixtures.pcm(leadingSeconds=18.0),0,"in")
        val result=NativeAwareAutoMixPlanner().plan(NativePlanningFixtures.request(a,b),a,b)
        assertTrue("$result",result is NativePlanningOutcome.Selected)
        val cue=(result as NativePlanningOutcome.Selected).plan.candidate.incomingCueUs
        assertTrue("$cue",cue in 17_930_000..19_000_000)
        val noProof=NativeAwareAutoMixPlanner().plan(NativePlanningFixtures.request(a,b),a,b.copy(signal=null))
        // A time-zero grid on silent audio alone is explicitly unverified, never silence proof.
        if(noProof is NativePlanningOutcome.Selected) {
            assertTrue(noProof.plan.candidate.incomingCueUs<=1_000_000)
            assertEquals(NativePlanReadiness.TIMING_ONLY_SIGNAL_UNVERIFIED,noProof.plan.readiness)
        }
    }
}
