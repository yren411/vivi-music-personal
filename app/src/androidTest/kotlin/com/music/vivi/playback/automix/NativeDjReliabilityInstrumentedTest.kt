// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

/** Actual JNI/planner/Media3 plus actual MusicService AudioTrack routing. Notifications are
 * explicitly injected into the service's real route observer; this does NOT automate Samsung's
 * recorder, physically replace a Bluetooth device or measure acoustic beat alignment.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeDjReliabilityInstrumentedTest {
    @get:Rule val deadline:Timeout=Timeout.seconds(240)
    private fun record(value:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".decodercheck"))
        val line="NATIVE_RELIABILITY_ANDROID $value"
        android.util.Log.i("TiniumNativeDj",line)
        // One bounded latest-test report, never PCM or source paths/identities.
        File(context.filesDir,"phase332-reliability-last.txt").writeText(line.take(24_000))
    }
    private fun service(name:String,body:(NativeAutoMixServiceInstrumentedTest.Harness)->Unit) =
        NativeAutoMixServiceInstrumentedTest.Harness("phase332-$name",true,false,false,false).use(body)

    @Test fun repeatedRealTransitionsRetainThe120msGuardAndExactlyOncePromotion() {
        repeat(2) {index ->NativeDjAndroidHarness().use {h ->
            h.start();h.until("phase332 completion",40_000){h.executor.terminal}
            h.main {
                val m=h.executor.measurements();record("repeat=$index report=${h.executor.report()}")
                assertEquals(h.failure(),NativeDjExecutionStage.COMPLETED,h.executor.status.stage)
                assertTrue(m.maximumObservedDriftUs<=120_000);assertNotNull(m.lastClock)
                assertTrue(m.maximumQuerySpanUs>=0);assertEquals(1,h.promotions)
                assertEquals(1,h.released.count {it===h.outgoing});assertFalse(h.released.contains(h.incoming))
            }
        }}
    }
    @Test fun freshCompatiblePredictionDoesNotAlterOriginalCuesOrSafetyLimits()=NativeDjAndroidHarness().use {h ->
        h.start();h.mixing();h.until("progress",3000){h.executor.measurements().estimatedStartOffsetUs!=null}
        h.main {
            val m=h.executor.measurements();record("fresh=${h.executor.report()}")
            assertEquals(h.plan.candidate.outgoingCueUs,m.plannedStartUs)
            assertEquals(h.plan.candidate.incomingCueUs,m.plannedIncomingCueUs)
            assertTrue(m.startupLeadUs in 0..250_000)
            if(m.predictionState!=NativeDjPredictionState.AVAILABLE)assertEquals(0L,m.startupLeadUs)
            assertTrue(m.maximumObservedDriftUs<=120_000)
            assertTrue(m.commandOutgoingUs!! in (m.plannedStartUs-m.startupLeadUs)..(m.plannedStartUs-m.startupLeadUs+40_000))
        }
    }
    @Test fun noPredictionRetainsHonestSuccessOrSafeExcessiveDriftFallback()=NativeDjAndroidHarness().use {h ->
        h.main {
            h.playback.close()
            h.playback=h.adapter(usePrediction=false)
        }
        h.start();h.until("no-prediction terminal",40_000){h.executor.terminal}
        h.main {
            val m=h.executor.measurements();record("noPrediction=${h.executor.report()}")
            assertEquals(0L,m.startupLeadUs)
            if(h.executor.status.stage==NativeDjExecutionStage.COMPLETED) {
                assertTrue(m.maximumObservedDriftUs<=120_000);assertEquals(1,h.promotions)
            } else {
                assertEquals(NativeDjExecutionStage.FALLBACK,h.executor.status.stage)
                assertTrue(h.executor.status.reason in setOf(NativeDjExecutionReason.EXCESSIVE_DRIFT,NativeDjExecutionReason.CLOCK_OBSERVATION_UNCERTAIN))
                assertTrue(m.maximumObservedDriftUs>120_000);assertEquals(0,h.promotions)
                assertTrue(h.outgoing.isPlaying);assertEquals(h.volume,h.outgoing.volume,.0001f)
            }
        }
    }
    @Test fun bothRealQueryOrdersExposeTheirOwnMonotonicBrackets()=NativeDjAndroidHarness().use {h ->
        h.start();h.mixing();h.until("incoming progress",3000){h.executor.measurements().estimatedStartOffsetUs!=null}
        h.main {
            fun query(p:androidx.media3.exoplayer.ExoPlayer):NativeDjClockObservation {
                val before=System.nanoTime()/1000
                val state=NativeDjDeckState(h.plan.outgoing.source,p.duration*1000,p.currentPosition*1000,
                    p.playbackState==androidx.media3.common.Player.STATE_READY,p.isPlaying)
                return NativeDjClockObservation(state,before,System.nanoTime()/1000)
            }
            val a=query(h.outgoing);val b=query(h.incoming!!)
            val reverseB=query(h.incoming!!);val reverseA=query(h.outgoing)
            val first=NativeDjClockPair(a,b).offset(h.plan.candidate.outgoingCueUs,h.plan.candidate.incomingCueUs)
            val second=NativeDjClockPair(reverseA,reverseB).offset(h.plan.candidate.outgoingCueUs,h.plan.candidate.incomingCueUs)
            assertTrue(first.outgoingAfterUs<=first.incomingBeforeUs)
            assertTrue(second.incomingAfterUs<=second.outgoingBeforeUs)
            assertEquals(first.rawOffsetUs+(b.midpointUs-a.midpointUs),first.commonInstantOffsetUs)
            assertEquals(second.rawOffsetUs+(reverseB.midpointUs-reverseA.midpointUs),second.commonInstantOffsetUs)
            record("orders normal=$first reverse=$second; player observations only")
        }
    }
    @Test fun actualServicePlanPreservesTheSongAndPromotesItsRealOutputOnce()=service("placement") {h ->
        h.play();h.await("preserving service plan",20_000){h.service.autoMixStatus.value.nativePlan!=null}
        var original:NativeRouteEndpoint?=null
        h.main {
            val p=requireNotNull(h.service.autoMixStatus.value.nativePlan);val placement=requireNotNull(p.candidate.placement)
            assertEquals(NativePlacementPolicy.PRESERVE_OUTGOING,p.placementPolicy)
            assertTrue(placement.remainingUs<=placement.allowedLossUs);assertTrue(placement.preservedFraction>=.95)
            original=h.service.nativeRouteSnapshot().outgoing;assertNotNull(original)
        }
        h.mixing();h.await("late-cue completion",50_000){h.completed()==1}
        h.main {
            val s=h.service.nativeServiceInspection()
            assertEquals(h.items[1].mediaId,h.service.player.currentMediaItem?.mediaId)
            assertEquals(1,s.outputs);assertTrue(h.service.player.isPlaying)
            assertEquals(.12f,h.service.player.volume,.0001f)
            assertNotEquals(original,h.service.nativeRouteSnapshot().outgoing)
            val status=h.service.autoMixStatus.value
            status.nativePlan?.let {assertEquals(h.items[1].mediaId,it.outgoing.source.recordingId)}
            record("service=${h.report()}")
        }
    }
    @Test fun repeatedCaptureAndDeviceNotificationsRetainTheActualStableServicePlan()=service("capture") {h ->
        h.play();h.await("native plan",20_000){h.service.autoMixStatus.value.nativePlan!=null}
        h.main {
            val before=requireNotNull(h.service.autoMixStatus.value.nativePlan)
            val route=h.service.nativeRouteSnapshot().outgoing;assertNotNull(route)
            val attempts=h.history().attempts.size
            repeat(30) {
                h.service.observeNativeRoute(NativeRouteNotification.DEVICES_ADDED,true)
                h.service.observeNativeRoute(NativeRouteNotification.DEVICES_REMOVED,true)
            }
            assertEquals(route,h.service.nativeRouteSnapshot().outgoing)
            assertEquals(before,h.service.autoMixStatus.value.nativePlan)
            assertEquals(attempts,h.history().attempts.size)
            assertFalse(h.history().attempts.flatMap {it.records}.any {it.native?.event==NativeServiceEvent.CANCELED && it.native.reason=="ROUTE_CHANGED"})
            record("captureNotifications actualOutputUnchanged=true planRetained=true; Samsung recorder not automated")
        }
    }
    @Test fun standbyAndSessionNotificationsDoNotBecomePhysicalOutputChanges()=service("session") {h ->
        h.play();h.await("prepared second deck",20_000){h.service.nativeServiceInspection().outputs==2}
        h.main {
            val p=requireNotNull(h.service.autoMixStatus.value.nativePlan);val route=h.service.nativeRouteSnapshot().outgoing
            h.service.observeNativeRoute(NativeRouteNotification.SESSION)
            h.service.observeNativeRoute(NativeRouteNotification.TRACK_CREATED)
            assertEquals(route,h.service.nativeRouteSnapshot().outgoing)
            assertEquals(p,h.service.autoMixStatus.value.nativePlan)
            assertEquals(2,h.service.nativeServiceInspection().outputs)
        }
    }
    @Test fun focusPauseAndResumeRemainServiceOwnedWithoutPhysicalRouteCancellation()=service("focus") {h ->
        h.play();h.mixing();h.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        h.await("paused",3000){h.service.nativeServiceInspection().execution?.status?.stage==NativeDjExecutionStage.PAUSED}
        h.focus(AudioManager.AUDIOFOCUS_GAIN)
        h.await("resumed",3000){h.service.nativeServiceInspection().execution?.status?.stage==NativeDjExecutionStage.MIXING}
        h.main {assertTrue(h.service.player.isPlaying);assertTrue(h.service.nativeServiceInspection().incomingPlaying==true)}
    }
    @Test fun serviceSeekCancelsTheOldGenerationAndClearsItsMarker()=service("seek") {h ->
        h.play();h.mixing()
        var oldGeneration=0L
        h.main {oldGeneration=h.service.autoMixStatus.value.nativePlan!!.generation;h.service.player.seekTo(1000)}
        h.await("old generation released",5000){h.service.nativeServiceInspection().outputs==1}
        h.main {
            val status=h.service.autoMixStatus.value
            assertTrue(status.nativePlan==null || status.nativePlan!!.generation!=oldGeneration)
            assertEquals(0,h.completed());assertTrue(h.service.player.playWhenReady)
        }
    }
    @Test fun serviceModeRollbackReleasesTheSecondaryOutputAndRestoresVolume()=service("rollback") {h ->
        h.play();h.mixing();h.mode(AutoMixEngineMode.LEGACY)
        h.await("legacy owns output",5000){val s=h.service.nativeServiceInspection();!s.nativeSelected && s.legacyEnabled && s.outputs==1}
        h.main {assertTrue(h.service.player.isPlaying);assertEquals(.12f,h.service.player.volume,.0001f);assertNull(h.service.autoMixStatus.value.nativePlan)}
    }
}
