// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Six actual Android tests; existing 14 native and four decoder tests are unchanged. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeAwarePlannerInstrumentedTest {
    @Test fun genuineNativeEvidencePlansSevenCompatiblePairs() {
        val engine=BitChordNativeAnalysisEngine()
        for(bpm in listOf(90.0,107.0,120.0,128.0,140.0,150.0,174.0)) NativePlannerJniChecks.compatiblePair(engine,bpm)
    }
    @Test fun stationaryNoiseSweepAndSilenceDoNotBecomeSupportedPlans()=
        NativePlannerJniChecks.nonRhythmicMaterialIsRejected(BitChordNativeAnalysisEngine())
    @Test fun measuredLeadingSilenceSupportsLaterIncomingCue()=
        NativePlannerJniChecks.laterCueRequiresRealQuietPrefix(BitChordNativeAnalysisEngine())

    private fun source(asset:String, id:String, block:(AnalysisSource)->Unit) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val file=File.createTempFile("planner-fixture-","."+asset.substringAfterLast('.'),instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("automix/$asset").use { input->file.outputStream().use {input.copyTo(it)} }
            block(AnalysisSource(Uri.fromFile(file),id,file.length(),"generated-planner-codec-v1",
                DefaultDataSource.Factory(instrumentation.targetContext),true))
        } finally {assertTrue(file.delete())}
    }
    @Test fun correctedLocalAacAndFlacDecodeIntoANativePlan() {
        source("rhythm-128-48000.m4a","out"){a ->source("rhythm-128-44100.flac","in"){b ->
            val analysis=AndroidNativeAnalysis.createPlanningAnalysis()
            val outId=AnalysisSourceIdentity(a.cacheKey,a.identity);val inId=AnalysisSourceIdentity(b.cacheKey,b.identity)
            val control=AnalysisControl(System.nanoTime()+30_000_000_000)
            // Start at zero to retain the verified AAC priming fix. Cue constraints, not a
            // fabricated timestamp shift, select an outgoing position at least four seconds in.
            val x=analysis.analyze(a,DecodeRequest(AudioIntervalUs(0,24_000_000),outId),AnalysisDetail.DETAIL,control)
            val y=analysis.analyze(b,DecodeRequest(AudioIntervalUs(0,24_000_000),inId),AnalysisDetail.DETAIL,control)
            val request=NativePlanningRequest(PlanningTrack(outId,30_000_000,AudioIntervalUs(4_000_000,24_000_000)),
                PlanningTrack(inId,30_000_000,AudioIntervalUs(0,24_000_000)),3_000_000,1_000_000,1,"android-codec-fixture")
            val actual=NativeAwareAutoMixPlanner().plan(request,x,y,control)
            assertTrue("$actual",actual is NativePlanningOutcome.Selected)
            val c=(actual as NativePlanningOutcome.Selected).plan.candidate
            assertTrue(c.outgoingCueUs>=4_000_000)
            assertTrue(c.outgoingCueUs+c.overlapUs<=24_000_000)
            assertTrue(c.incomingCueUs+c.overlapUs<=24_000_000)
            assertTrue(c.alignment.maxResidualUs<=70_000)
        }}
    }
    @Test fun cancellationRejectsNativePlanPublication() {
        val engine=BitChordNativeAnalysisEngine()
        val a=NativePlanningFixtures.analyze(engine,NativePlanningFixtures.pcm(),150_000_000,"out")
        val b=NativePlanningFixtures.analyze(engine,NativePlanningFixtures.pcm(),0,"in")
        var checkpoints=0
        val control=AnalysisControl(cancellation=AnalysisCancellation {if(++checkpoints>40)throw java.util.concurrent.CancellationException()})
        val result=NativeAwareAutoMixPlanner().plan(NativePlanningFixtures.request(a,b),a,b,control)
        assertEquals(PlanningRejection.CANCELED,(result as NativePlanningOutcome.Fallback).reason)
        assertTrue(result.diagnostics.candidateCount>0)
    }
    @Test fun productionStillUsesLegacyAndNativeCannotCrossLegacyBridge() {
        assertEquals(AnalysisPreprocessing.LEGACY,AndroidWindowDecoder().preprocessing)
        val result=BitChordNativeAnalysisEngine().analyze(NativeAnalysisFixtures.window(),AnalysisDetail.DETAIL,AnalysisControl())
        assertTrue(result is AnalysisResult.Completed)
        assertThrows(IllegalStateException::class.java) {LegacyPlanningFeatures.require(result)}
    }
}
