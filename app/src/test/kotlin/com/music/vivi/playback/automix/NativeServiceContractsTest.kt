// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeServiceContractsTest {
    @Test fun legacyAndNativeTypedWorkersShareTheSelectedConcurrencyLimit() {
        for(mode in AutoMixPerformance.entries) {
            val budget=AnalysisConcurrencyBudget(mode);val active=AtomicInteger();val maximum=AtomicInteger()
            val entered=CountDownLatch(mode.workers);val release=CountDownLatch(1)
            AnalysisWorkers<Int>(AutoMixPerformance.PERFORMANCE,budget).use {a ->
                AnalysisWorkers<String>(AutoMixPerformance.PERFORMANCE,budget).use {b ->
                    fun work(){val count=active.incrementAndGet();maximum.accumulateAndGet(count,::maxOf);entered.countDown();try {assertTrue(release.await(5,TimeUnit.SECONDS))}finally {active.decrementAndGet()}}
                    val first=(0..3).map {i->a.submit("a$i"){work();i}}
                    val second=(0..3).map {i->b.submit("b$i"){work();"$i"}}
                    try {assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals(mode.workers,maximum.get())}finally {release.countDown()}
                    first.forEach {it.get(5,TimeUnit.SECONDS)};second.forEach {it.get(5,TimeUnit.SECONDS)}
                    assertEquals(mode.workers,maximum.get());assertEquals(0,active.get())
                }
            }
        }
    }
    @Test fun nullableWorkIsNotRepeatedByBudgetWrapper() {
        val calls=AtomicInteger()
        AnalysisWorkers<String?>(budget=AnalysisConcurrencyBudget()).use {workers ->
            assertNull(workers.submit("nullable"){calls.incrementAndGet();null}.get(3,TimeUnit.SECONDS))
            assertEquals(1,calls.get())
        }
    }
    @Test fun cancellationWhileWaitingForSharedBudgetNeverStartsWork() {
        val budget=AnalysisConcurrencyBudget(AutoMixPerformance.EFFICIENT);val began=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        AnalysisWorkers<Int>(AutoMixPerformance.PERFORMANCE,budget).use {a ->
            AnalysisWorkers<Int>(AutoMixPerformance.PERFORMANCE,budget).use {b ->
                val first=a.submit("hold"){began.countDown();release.await();1}
                assertTrue(began.await(2,TimeUnit.SECONDS))
                val obsolete=b.submit("obsolete"){calls.incrementAndGet()};obsolete.cancel(true);release.countDown()
                assertEquals(1,first.get(2,TimeUnit.SECONDS));assertTrue(obsolete.isCancelled);assertEquals(0,calls.get())
            }
        }
    }
    @Test fun nativeTimelineMarkerUsesNativeCueWithoutLegacyEvidenceConversion() {
        val p=mathematicalExecutionPlan()
        val state=AutoMixStatus(AutoMixPhase.READY,p.outgoing.source.recordingId,p.incoming.source.recordingId,
            p.outgoing.durationUs/1000,nativeMode=true,nativePlan=p)
        assertEquals((p.candidate.outgoingCueUs/1000.0/(p.outgoing.durationUs/1000)).toFloat(),
            state.markerFraction(state.outgoingId,state.incomingId,p.outgoing.durationUs/1000)!!,.00001f)
        assertNull(state.plan)
    }
    @Test fun canceledOrReplacedNativePlansCannotLeaveAVisibleMarker() {
        val p=mathematicalExecutionPlan();val state=AutoMixStatus(AutoMixPhase.READY,"out","in",180_000,nativeMode=true,nativePlan=p)
        assertNull(state.copy(phase=AutoMixPhase.UNAVAILABLE).markerFraction("out","in",180_000))
        assertNull(state.markerFraction("out","different-next",180_000));assertNull(state.markerFraction("out","in",0))
        assertNull(state.copy(nativeMode=false).markerFraction("out","in",180_000))
    }
    @Test fun waitingNativeSourcesDoNotHideTheActualLegacyFallbackState() {
        val waiting=AutoMixStatus(AutoMixPhase.WAITING_SOURCE,nativeMode=true,reason="WAITING_RESOLVED_PAIR")
        val plan=AutoMixPlan(100_000,0,2500)
        for(phase in listOf(AutoMixPhase.ANALYZING_CURRENT,AutoMixPhase.READY,AutoMixPhase.FALLBACK_PLAYING,AutoMixPhase.MIXING)) {
            val legacy=AutoMixStatus(phase,"out","in",120_000,plan)
            val shown=nativeServiceVisibleStatus(waiting,legacy,true)
            assertEquals(phase,shown.phase);assertSame(plan,shown.plan);assertFalse(shown.nativeMode)
            assertEquals("WAITING_RESOLVED_PAIR",shown.nativeFallback)
        }
        assertSame(waiting,nativeServiceVisibleStatus(waiting,null,true))
        assertSame(waiting,nativeServiceVisibleStatus(waiting,AutoMixStatus(),false))
    }
    @Test fun recorderRetainsOnlyOnePlayerSamplePerNativeEvent() {
        val h=VirtualNativeDjHost(mathematicalExecutionPlan());val e=NativeDjTransitionExecutor(h,{h.now});e.start(h.plan);h.finish(e)
        assertTrue(e.report().measurements.events.size>10)
        val recorder=AutoMixRecorder();val id=recorder.begin("out","in");recorder.native(id,NativeServiceEvent.EXECUTION,execution=e.report())
        assertEquals(1,recorder.snapshot().attempts.single().records.single().native!!.execution!!.measurements.events.size)
    }
}
