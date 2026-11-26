// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.test.platform.app.InstrumentationRegistry
import com.music.vivi.playback.MusicService
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Real generated codecs/JNI and MusicService. Injected route events are not Samsung capture tests. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AdaptiveNativeAnalysisInstrumentedTest {
    @get:Rule val timeout:Timeout=Timeout.seconds(120)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext.also {check(it.packageName.endsWith(".decodercheck"))}
    private fun source(asset:String,block:(AnalysisSource,File)->Unit) {
        val file=File.createTempFile("phase334-","."+asset.substringAfterLast('.'),context.cacheDir)
        try {
            instrumentation.context.assets.open("automix/$asset").use {input->file.outputStream().use {input.copyTo(it)}}
            val id="generated334-"+UUID.randomUUID()
            block(AnalysisSource(Uri.fromFile(file),id,file.length(),id,DefaultDataSource.Factory(context),true),file)
        }finally {assertTrue(file.delete())}
    }
    private fun analyze(s:AnalysisSource,start:Long,end:Long):PlanningWindowEvidence {
        val control=AnalysisControl(System.nanoTime()+60_000_000_000,decoderDiagnostics=DecoderDiagnosticSink {
            File(context.filesDir,"phase334-decoder-last.txt").writeText("ADAPTIVE_NATIVE_ANDROID "+it.reportFields())
        })
        return AndroidNativeAnalysis.createPlanningAnalysis().analyze(s,DecodeRequest(AudioIntervalUs(start,end),AnalysisSourceIdentity(s.cacheKey,s.identity)),AnalysisDetail.DETAIL,control)
            .also {println("ADAPTIVE_NATIVE_ANDROID region=${it.region} result=${it.analysis}")}
    }
    private fun completed(e:PlanningWindowEvidence):AnalysisResult.Completed {
        assertTrue("ADAPTIVE_NATIVE_ANDROID ${e.analysis} ${e.region}",e.analysis is AnalysisResult.Completed)
        return (e.analysis as AnalysisResult.Completed).also {
            assertEquals(CoverageStatus.FULL,it.metadata.coverageStatus)
            assertEquals(PcmPreprocessing.BANDLIMITED,it.metadata.preprocessingVersion)
            assertEquals(0L,it.metadata.repeatedOutputFrames)
            assertEquals(0L,it.metadata.preparation!!.synthesizedTimelineFrames)
        }
    }
    @Test fun emptyEditSelectsVerifiedLaterAudioAndRunsRealBitChordWithAbsoluteBeats()=source("boundary-empty-edit6042-44100-stereo.m4a") {s,_ ->
        val e=analyze(s,0,32_000_000);val r=completed(e)
        assertEquals(AudioIntervalUs(0,32_000_000),e.region!!.intent)
        assertEquals(AudioIntervalUs(138000,32_000_000),r.metadata.requested)
        assertEquals(138000L,r.metadata.sampleInterval!!.startUs)
        assertTrue(e.region.adapted);assertEquals(2,e.region.decodeExecutions)
        assertTrue(r.evidence.beatsUs.value!!.isNotEmpty())
        assertTrue(r.evidence.beatsUs.value!!.all {it in r.metadata.sampleInterval.startUs until r.metadata.sampleInterval.endUs})
        assertEquals("OK_UNCALIBRATED_EVIDENCE",r.metadata.nativeDiagnostic!!.status)
    }
    @Test fun roundedEndSelectsEarlierSupportedEndpointWithoutExtendingAudio()=source("boundary-rounded-end-44100-stereo.m4a") {s,_ ->
        val e=analyze(s,123_063_000,155_063_000);val r=completed(e)
        assertEquals(AudioIntervalUs(123_063_000,155_062_000),r.metadata.requested)
        assertEquals(155_063_000L,r.metadata.duration.durationUs)
        assertTrue(r.metadata.sampleInterval!!.endUs<=155_062_000)
        assertTrue(e.region!!.adapted);assertEquals(2,e.region.decodeExecutions)
    }
    @Test fun adaptedAacMatchesIndependentForwardCodecOnItsOwnExactInterval()=source("boundary-rounded-end-44100-stereo.m4a") {s,file ->
        val e=analyze(s,123_063_000,155_063_000);val result=completed(e)
        val req=DecodeRequest(result.metadata.requested,result.metadata.source)
        val c=AnalysisControl(System.nanoTime()+60_000_000_000)
        val reference=AacBoundaryCodecReference.decode(file,req,c)
        assertNull("ADAPTIVE_FORWARD_REFERENCE $reference",reference.failure)
        val selected=(AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED).decode(s,req,c) as DecodeResult.Decoded).window
        assertArrayEquals(reference.window!!.samples,selected.samples,0f)
        assertEquals(reference.window.sampleInterval,selected.sampleInterval)
    }
    @Test fun alreadyFullAacSeekKeepsV8PrerollAndDoesNotDecodeAgain()=source("boundary-padding14-44100-stereo.m4a") {s,_ ->
        val e=analyze(s,3_063_000,35_063_000);val r=completed(e)
        assertEquals(1,e.region!!.decodeExecutions);assertFalse(e.region.adapted)
        assertEquals(705600,r.metadata.nativeDiagnostic!!.inputFrames)
        assertTrue(r.metadata.decoderDiagnostic!!.aacBoundary!!.prerollInputPackets>=2)
    }
    @Test fun unsupportedLargeLeadingGapStillRejectsFullNativeEvidence()=source("adaptive-empty-edit30s-44100-stereo.m4a") {s,_ ->
        val e=analyze(s,0,32_000_000)
        assertTrue(e.analysis is AnalysisResult.Failed)
        assertEquals("PARTIAL_SOURCE_COVERAGE",(e.analysis as AnalysisResult.Failed).failure.code)
        assertEquals(1,e.region!!.decodeExecutions)
    }
    @Test fun actualMissingCompressedPacketRemainsSourceGapRatherThanAdaptableEdge()=source("boundary-padding14-44100-stereo.m4a") {s,file ->
        val ref=AacBoundaryCodecReference.decode(file,DecodeRequest(AudioIntervalUs(0,8_000_000),AnalysisSourceIdentity(s.cacheKey,s.identity)),
            AnalysisControl(System.nanoTime()+30_000_000_000),dropPacket=true)
        assertEquals(DecoderFailureSubtype.SOURCE_GAP,ref.failure)
        assertTrue(ref.acceptedFrames>0)
    }
    @Test fun opusAndFlacKeepTheirExistingSupportAndFullNativeCoverage() {
        for(asset in listOf("rhythm-128-48000-40s.webm","rhythm-128-44100.flac"))source(asset) {s,_ ->
            val e=analyze(s,2_000_000,10_000_000);completed(e)
            assertFalse(e.region!!.adapted);assertEquals(1,e.region.decodeExecutions)
        }
    }
    private fun service(name:String,block:(NativeAutoMixServiceInstrumentedTest.Harness)->Unit) {
        NativeAutoMixServiceInstrumentedTest.Harness("phase334-$name",true,false,true,false).use {h ->
            try {h.play();h.await("native plan",20_000){h.service.autoMixStatus.value.nativePlan!=null};block(h)}
            catch(t:Throwable){h.captureFailure(t);throw t}
        }
    }
    @Test fun realServiceSeekReplansFromCompletedEvidenceWithoutAnotherDecode()=service("seek-reuse") {h ->
        h.await("optional work stopped",5000){h.service.nativeServiceInspection().analysisReuse!!.workers.active==0}
        var before:NativeReuseSnapshot?=null;var plans=0
        h.main {before=h.service.nativeServiceInspection().analysisReuse;plans=h.history().attempts.sumOf {a->a.records.count {it.native?.event==NativeServiceEvent.PLAN}};h.service.player.seekTo(5000)}
        h.await("replanned from same source",10_000){h.history().attempts.sumOf {a->a.records.count {it.native?.event==NativeServiceEvent.PLAN}}>plans}
        h.main {
            val after=h.service.nativeServiceInspection().analysisReuse!!
            assertEquals(before!!.workers.executions,after.workers.executions)
            assertEquals(before!!.actualDecoderCalls,after.actualDecoderCalls)
            assertTrue(after.workers.cacheHits>=before!!.workers.cacheHits+2)
            assertTrue(after.seekReplans>before!!.seekReplans)
            assertNotNull(h.service.autoMixStatus.value.nativePlan)
        }
    }
    @Test fun unchangedCaptureAndSessionNotificationsRetainRealServicePlanAndEvidence()=service("capture-events") {h ->
        h.main {
            val plan=h.service.autoMixStatus.value.nativePlan!!
            val before=h.service.nativeServiceInspection().analysisReuse!!
            repeat(5) {
                h.service.observeNativeRoute(NativeRouteNotification.DEVICES_ADDED,captureOnly=true)
                h.service.observeNativeRoute(NativeRouteNotification.SESSION)
                h.service.observeNativeRoute(NativeRouteNotification.POLL)
            }
            assertEquals(plan,h.service.autoMixStatus.value.nativePlan)
            val after=h.service.nativeServiceInspection().analysisReuse!!
            assertEquals(before.routeReplans,after.routeReplans)
            assertEquals(before.workers.executions,after.workers.executions)
        }
    }
    @Test fun injectedMaterialInvalidationCancelsScheduleButReusesMusicEvidence()=service("route-invalidate") {h ->
        h.await("analysis idle",5000){h.service.nativeServiceInspection().analysisReuse!!.workers.active==0}
        var before:NativeReuseSnapshot?=null;var plans=0
        h.main {
            before=h.service.nativeServiceInspection().analysisReuse
            plans=h.history().attempts.sumOf {a->a.records.count {it.native?.event==NativeServiceEvent.PLAN}}
            // Classified-event injection, not a physical speaker/Bluetooth switch.
            MusicService::class.java.getDeclaredMethod("invalidateNative",NativeDjExecutionReason::class.java)
                .apply {isAccessible=true}.invoke(h.service,NativeDjExecutionReason.ROUTE_CHANGED)
            assertNull(h.service.autoMixStatus.value.nativePlan)
        }
        h.await("fresh route schedule from cached evidence",10_000){h.history().attempts.sumOf {a->a.records.count {it.native?.event==NativeServiceEvent.PLAN}}>plans}
        h.main {val after=h.service.nativeServiceInspection().analysisReuse!!
            assertEquals(before!!.workers.executions,after.workers.executions)
            assertEquals(before!!.actualDecoderCalls,after.actualDecoderCalls)
            assertEquals(before!!.routeReplans+1,after.routeReplans)
            assertEquals(1,h.service.nativeServiceInspection().outputs)}
    }
    @Test fun seekDuringRealMixReleasesStandbyAndPreservesReusableEvidence()=service("active-seek") {h ->
        h.mixing()
        var hits=0L
        h.main {
            assertEquals(2,h.service.nativeServiceInspection().outputs)
            hits=h.service.nativeServiceInspection().analysisReuse!!.workers.cacheHits
            h.service.player.seekTo(0)
        }
        h.await("standby released after seek",5000){h.service.nativeServiceInspection().outputs==1}
        h.await("source evidence reused after active seek",10_000){h.service.nativeServiceInspection().analysisReuse!!.workers.cacheHits>=hits+2}
        h.main {
            assertEquals(h.items[0].mediaId,h.service.player.currentMediaItem!!.mediaId)
            assertEquals(0,h.completed());assertTrue(h.service.player.isPlaying)
            assertTrue(h.service.player.volume>0f);assertTrue(h.service.nativeServiceInspection().nativeSelected)
        }
    }
    @Test fun smallPresentationGapCanReachActualServiceNativePlanning() {
        val fixtures=listOf("boundary-exact-48000-mono.m4a" to 36,"boundary-empty-edit6042-44100-stereo.m4a" to 36,"boundary-exact-48000-mono.m4a" to 36)
        NativeAutoMixServiceInstrumentedTest.Harness("phase334-adapted-service",true,false,false,true,fixtures).use {h ->
            h.play()
            h.await("two valid native evidence results",20_000){h.history().attempts.any {a->a.records.count {it.native?.event==NativeServiceEvent.EVIDENCE}>=2}}
            h.main {
                val records=h.history().attempts.flatMap {it.records}
                val incoming=records.last {it.native?.event==NativeServiceEvent.EVIDENCE && it.role==MixRole.INCOMING}
                assertNull(incoming.failure);assertEquals(AudioIntervalUs(138000,32_000_000),incoming.native!!.region!!.selected)
                assertTrue(incoming.native.region.adapted)
                assertFalse(records.any {it.native?.event==NativeServiceEvent.FALLBACK && it.native.reason=="PARTIAL_SOURCE_COVERAGE"})
                assertTrue(h.service.player.isPlaying)
            }
            // A planner rejection is valid musical evidence, not a decoder failure.
            h.await("planner evaluated adapted evidence",5000){h.history().attempts.any {a->a.records.any {it.native?.event in setOf(NativeServiceEvent.PLAN,NativeServiceEvent.FALLBACK)}}}
        }
    }
}
