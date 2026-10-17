// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class NativeServiceHistoryScopeTest {
    @Test fun completedFallbackAndPlanningEventsFromAnEarlierTestCannotSatisfyNewWaits() {
        val recorder=AutoMixRecorder()
        val old=recorder.begin("old-out","old-in")
        recorder.native(old,NativeServiceEvent.PAIR)
        recorder.native(old,NativeServiceEvent.FALLBACK,reason="LOW_BEAT_CONFIDENCE")
        val (host,executor)=completedExecution()
        recorder.native(old,NativeServiceEvent.EXECUTION,execution=executor.report())
        assertEquals(1,host.promotions)
        val scope=NativeServiceHistoryScope(recorder.snapshot())
        assertTrue(scope.since(recorder.snapshot()).attempts.isEmpty())
        val current=recorder.begin("new-out","new-in")
        recorder.native(current,NativeServiceEvent.PAIR)
        val attempts=scope.since(recorder.snapshot()).attempts
        assertEquals(listOf(current),attempts.map {it.number})
        assertFalse(attempts.any {a->a.records.any {it.native?.event==NativeServiceEvent.FALLBACK}})
        assertFalse(attempts.any {a->a.records.any {it.native?.execution?.status?.stage==NativeDjExecutionStage.COMPLETED}})
        recorder.native(current,NativeServiceEvent.EXECUTION,execution=executor.report())
        assertEquals(1,scope.since(recorder.snapshot()).attempts.count {a->a.records.any {it.native?.execution?.status?.stage==NativeDjExecutionStage.COMPLETED}})
        assertEquals(2,recorder.snapshot().attempts.size) // Production diagnostics are not erased.
    }
    @Test fun lateOldRecordsAndLruEvictionCannotBecomeCurrentEvidence() {
        val recorder=AutoMixRecorder(maxAttempts=2)
        val old=recorder.begin("a","b");val scope=NativeServiceHistoryScope(recorder.snapshot())
        recorder.native(old,NativeServiceEvent.FALLBACK,reason="QUEUE_CHANGED")
        assertTrue(scope.since(recorder.snapshot()).attempts.isEmpty())
        val first=recorder.begin("b","c");val second=recorder.begin("c","d")
        assertEquals(listOf(first,second),scope.since(recorder.snapshot()).attempts.map {it.number})
    }
    @Test fun aNewServiceSessionCannotReuseAnOldHistoryBoundary() {
        val recorder=AutoMixRecorder();val scope=NativeServiceHistoryScope(recorder.snapshot())
        assertThrows(IllegalStateException::class.java){scope.since(AutoMixRecorder().snapshot())}
    }
    private fun completedExecution():Pair<VirtualNativeDjHost,NativeDjTransitionExecutor> {
        val plan=mathematicalExecutionPlan();val host=VirtualNativeDjHost(plan)
        val executor=NativeDjTransitionExecutor(host,{host.now});executor.start(plan);host.toStart(executor);host.finish(executor)
        assertEquals(NativeDjExecutionStage.COMPLETED,executor.status.stage)
        return host to executor
    }
}
