package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class SharedAnalysisLeaseTest {
    @Test fun canceledObserverCannotCancelNewerConsumerAndOnlyOneJobExecutes() {
        AnalysisWorkers<Any>().use {w ->
            val started=CountDownLatch(1);val release=CountDownLatch(1);val value=Any()
            val old=w.acquire("same",true,true){started.countDown();check(release.await(3,TimeUnit.SECONDS));value}
            assertTrue(started.await(3,TimeUnit.SECONDS))
            val next=w.acquire("same",true,true){error("duplicate")}
            assertTrue(old.cancel(true));assertTrue(old.isCancelled);assertFalse(next.isCancelled)
            release.countDown();assertSame(value,next.get(3,TimeUnit.SECONDS))
            w.cancelAll();assertSame(value,w.acquire("same"){error("cache lost")}.get(3,TimeUnit.SECONDS))
            assertEquals(1L,w.snapshot().executions);assertEquals(1L,w.snapshot().coalesced)
        }
    }
    @Test fun lastDetachedImmutableJobCanBeRejoinedBeforeItsOriginalDeadline() {
        AnalysisWorkers<Int>().use {w ->
            val started=CountDownLatch(1);val release=CountDownLatch(1)
            val old=w.acquire("same",true,true){started.countDown();check(release.await(3,TimeUnit.SECONDS));7}
            assertTrue(started.await(3,TimeUnit.SECONDS));old.cancel(true)
            val next=w.acquire("same",true,true){error("duplicate")};release.countDown()
            assertEquals(7,next.get(3,TimeUnit.SECONDS));assertEquals(1L,w.snapshot().executions)
        }
    }
    @Test fun cancelingBlockedConsumerWakesItsGetWithoutInterruptingOtherConsumers() {
        AnalysisWorkers<Int>().use {w ->
            val release=CountDownLatch(1);val waiter=Executors.newSingleThreadExecutor()
            try {
                val a=w.acquire("same",true,true){check(release.await(3,TimeUnit.SECONDS));4}
                val b=w.acquire("same",true,true){error("duplicate")}
                val entered=CountDownLatch(1)
                val waiting=waiter.submit<Boolean>{entered.countDown();try {a.get();false}catch(_:CancellationException){true}}
                assertTrue(entered.await(3,TimeUnit.SECONDS));a.cancel(true)
                assertTrue(waiting.get(3,TimeUnit.SECONDS));assertFalse(b.isDone)
                release.countDown();assertEquals(4,b.get(3,TimeUnit.SECONDS))
            } finally {release.countDown();waiter.shutdownNow()}
        }
    }
    @Test fun optionalOrMutableLastObserverCancellationInterruptsUnderlyingWork() {
        for(cacheable in listOf(false,true))AnalysisWorkers<Int>().use {w ->
            val began=CountDownLatch(1);val stopped=CountDownLatch(1)
            val a=w.acquire("work",cacheable,false){began.countDown();try {CountDownLatch(1).await();0}finally {stopped.countDown()}}
            assertTrue(began.await(3,TimeUnit.SECONDS));a.cancel(true)
            assertTrue(stopped.await(3,TimeUnit.SECONDS));assertFalse(w.hasCached("work"))
        }
    }
    @Test fun cancelAllStillStopsRetainedWorkAndCannotPublishCanceledSuccess() {
        AnalysisWorkers<Int>().use {w ->
            val began=CountDownLatch(1);val stopped=CountDownLatch(1)
            w.acquire("work",true,true){began.countDown();try {CountDownLatch(1).await();0}finally {stopped.countDown()}}
            assertTrue(began.await(3,TimeUnit.SECONDS));w.cancelAll()
            assertTrue(stopped.await(3,TimeUnit.SECONDS));assertFalse(w.hasCached("work"))
            assertEquals(2,w.acquire("work"){2}.get(3,TimeUnit.SECONDS))
        }
    }
    @Test fun failedSharedJobIsNotCachedAndNextRequestReallyExecutes() {
        AnalysisWorkers<Int>().use {w ->
            try {w.acquire("work"){throw AnalysisUnavailable("SOURCE_GAP")}.get(3,TimeUnit.SECONDS);fail()}catch(_:ExecutionException){}
            assertFalse(w.hasCached("work"));assertEquals(3,w.acquire("work"){3}.get(3,TimeUnit.SECONDS))
            assertEquals(2L,w.snapshot().executions)
        }
    }
    @Test fun evictionCannotMutateACompletedConsumersResult() {
        AnalysisWorkers<Any>().use {w ->
            val value=Any();val first=w.acquire("old"){value};assertSame(value,first.get(3,TimeUnit.SECONDS))
            repeat(70) {w.acquire("new$it"){Any()}.get(3,TimeUnit.SECONDS)}
            assertFalse(w.hasCached("old"));assertSame(value,first.get());assertEquals(64,w.snapshot().cachedResults)
        }
    }
    @Test fun legacyDirectConsumerCannotBeCanceledBySharedSubscriber() {
        AnalysisWorkers<Int>().use {w ->
            val start=CountDownLatch(1);val release=CountDownLatch(1)
            val direct=w.submit("same"){start.countDown();check(release.await(3,TimeUnit.SECONDS));9}
            assertTrue(start.await(3,TimeUnit.SECONDS));val subscriber=w.acquire("same"){error("duplicate")}
            subscriber.cancel(true);assertFalse(direct.isCancelled);release.countDown();assertEquals(9,direct.get(3,TimeUnit.SECONDS))
        }
    }
    @Test fun originalDeadlineExpiresRetainedJobsBehindBusyBudgetAndCannotBeExtendedByNewObserver() {
        val budget=AnalysisConcurrencyBudget(AutoMixPerformance.EFFICIENT)
        val blocker=Executors.newSingleThreadExecutor();val occupied=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val busy=blocker.submit {budget.run {occupied.countDown();check(release.await(3,TimeUnit.SECONDS))}}
            assertTrue(occupied.await(3,TimeUnit.SECONDS))
            AnalysisWorkers<Int>(budget=budget).use {workers ->
                val calls=AtomicInteger()
                val old=workers.acquire("waiting",true,true,deadlineNanos=10){calls.incrementAndGet();1}
                old.cancel(true)
                val newer=workers.acquire("waiting",true,true,deadlineNanos=100){error("duplicate")}
                workers.cancelExpired(11)
                try {newer.get(3,TimeUnit.SECONDS);fail("expired source job completed")}
                catch(error:ExecutionException){assertEquals(AutoMixFailure.ANALYSIS_TIMEOUT,AutoMixFailure.of(error))}
                assertEquals(0,calls.get());assertFalse(workers.hasCached("waiting"))
            }
            release.countDown();busy.get(3,TimeUnit.SECONDS)
        }finally {release.countDown();blocker.shutdownNow()}
    }

}
