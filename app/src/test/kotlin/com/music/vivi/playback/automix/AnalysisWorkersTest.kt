package com.music.vivi.playback.automix

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AnalysisWorkersTest {
    /** Force Future.get() to return while done() is waiting for the worker lock.
     * Reflection is confined to this scheduling test: no production callback or sleep is needed.
     */
    private fun withCompletionCallbackBlocked(pool: AnalysisWorkers<*>, test: () -> Unit) {
        val field = AnalysisWorkers::class.java.getDeclaredField("lock").apply { isAccessible = true }
        synchronized(field.get(pool)) { test() }
    }

    @Test fun invalidationRetainsCompletedSuccessBeforeItsCompletionCallbackCanRun() {
        for (mode in AutoMixPerformance.entries) AnalysisWorkers<Any>(mode).use { pool ->
            withCompletionCallbackBlocked(pool) {
                val result = Any()
                val calls = AtomicInteger()
                val first = pool.submit("immutable") { calls.incrementAndGet(); result }
                assertSame(result, first.get(2, TimeUnit.SECONDS))
                pool.cancelAll()
                assertTrue(pool.hasCached("immutable"))
                assertSame(result, pool.submit("immutable") { calls.incrementAndGet(); Any() }
                    .get(2, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
            }
        }
    }

    @Test fun invalidationNeverCachesMutableOrFailedWorkEvenBeforeCompletionCallback() {
        AnalysisWorkers<Any>(AutoMixPerformance.PERFORMANCE).use { pool ->
            withCompletionCallbackBlocked(pool) {
                val mutable = pool.submit("mutable", false) { Any() }.get(2, TimeUnit.SECONDS)
                val failed = pool.submit("failed") { throw AnalysisUnavailable("DECODER_FAILED") }
                try { failed.get(2, TimeUnit.SECONDS); fail("Expected analysis failure") }
                catch (expected: ExecutionException) { assertTrue(expected.cause is AnalysisUnavailable) }
                pool.cancelAll()
                assertFalse(pool.hasCached("mutable"))
                assertFalse(pool.hasCached("failed"))
                assertNotSame(mutable, pool.submit("mutable", false) { Any() }.get(2, TimeUnit.SECONDS))
                val recovered = Any()
                assertSame(recovered, pool.submit("failed") { recovered }.get(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun delayedCompletionCallbackCannotRepopulateCacheAfterClose() {
        AnalysisWorkers<Thread>().use { pool ->
            lateinit var worker: Thread
            withCompletionCallbackBlocked(pool) {
                worker = pool.submit("completed") { Thread.currentThread() }.get(2, TimeUnit.SECONDS)
                pool.close()
                assertFalse(pool.hasCached("completed"))
            }
            worker.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("Worker must finish its callback and exit", worker.isAlive)
            assertFalse(pool.hasCached("completed"))
        }
    }

    @Test fun uncertainOrMutableSourcesAreDeduplicatedButNeverReusedFromCache() {
        AnalysisWorkers<Int>().use { pool ->
            assertEquals(1, pool.submit("mutable source", false) { 1 }.get(2, TimeUnit.SECONDS))
            assertEquals(2, pool.submit("mutable source", false) { 2 }.get(2, TimeUnit.SECONDS))
        }
    }
    @Test fun planningJobsShareTheAnalysisLimitAndAreCanceledWithObsoleteWork() {
        AnalysisWorkers<Int>(AutoMixPerformance.EFFICIENT).use { pool ->
            val entered=CountDownLatch(1)
            val block=CountDownLatch(1)
            val decoding=pool.submit("decode") { entered.countDown(); block.await(); 1 }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val planned=AtomicInteger()
            val planning=pool.submitTransient { planned.incrementAndGet(); "decision" }
            assertEquals(0,planned.get()); assertTrue(pool.queuedCount<=8)
            pool.cancelAll()
            assertTrue(decoding.isCancelled); assertTrue(planning.isCancelled)
            assertEquals(0,pool.queuedCount)
            val thread=pool.submitTransient { Thread.currentThread().name }.get(2,TimeUnit.SECONDS)
            assertTrue(thread.startsWith("Tinium-AutoMix-"))
        }
    }
    @Test fun planningQueueCannotGrowBeyondTheSameEightWaitingJobs() {
        AnalysisWorkers<Int>(AutoMixPerformance.EFFICIENT).use { pool ->
            val gate=CountDownLatch(1)
            val entered=CountDownLatch(1)
            pool.submit("occupied") { entered.countDown(); gate.await(); 1 }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val jobs=(0..12).map { pool.submitTransient { it.toString() } }
            assertTrue(pool.queuedCount<=8); assertTrue(jobs.any { it.isDone && !it.isCancelled && runCatching { it.get() }.exceptionOrNull()?.let(AutoMixFailure::of)==AutoMixFailure.WORKER_QUEUE_FULL })
            pool.cancelAll(); assertTrue(jobs.all { it.isCancelled || (it.isDone && runCatching { it.get() }.exceptionOrNull()?.let(AutoMixFailure::of)==AutoMixFailure.WORKER_QUEUE_FULL) })
        }
    }
    @Test fun modesBoundActualConcurrentWork() {
        for (mode in AutoMixPerformance.entries) AnalysisWorkers<Int>(mode).use { pool ->
            val started=CountDownLatch(mode.workers)
            val release=CountDownLatch(1)
            val active=AtomicInteger()
            val maximum=AtomicInteger()
            val jobs=(0 until mode.workers+3).map { index -> pool.submit("$index") {
                val now=active.incrementAndGet(); maximum.updateAndGet { maxOf(it,now) }; started.countDown()
                try { release.await(2,TimeUnit.SECONDS); index } finally { active.decrementAndGet() }
            } }
            assertTrue(started.await(2,TimeUnit.SECONDS))
            assertEquals(mode.workers,maximum.get())
            assertTrue(pool.queuedCount<=8)
            release.countDown()
            jobs.forEach { if (!it.isCancelled) it.get(3,TimeUnit.SECONDS) }
            assertTrue(maximum.get()<=mode.workers)
        }
    }
    @Test fun duplicateAndCachedAudioIsAnalyzedOnce() {
        AnalysisWorkers<Int>().use { pool ->
            val calls=AtomicInteger()
            val gate=CountDownLatch(1)
            val a=pool.submit("same rendition") { calls.incrementAndGet(); gate.await(); 7 }
            val b=pool.submit("same rendition") { error("duplicate analysis") }
            assertSame(a,b); gate.countDown()
            assertEquals(7,a.get(2,TimeUnit.SECONDS))
            assertEquals(7,pool.submit("same rendition") { calls.incrementAndGet(); 9 }.get(2,TimeUnit.SECONDS))
            assertEquals(1,calls.get())
        }
    }
    @Test fun changingModeCancelsObsoleteWorkAndAppliesNewLimit() {
        AnalysisWorkers<Int>(AutoMixPerformance.PERFORMANCE).use { pool ->
            val started=CountDownLatch(4)
            val stopped=CountDownLatch(4)
            val jobs=(0..3).map { i -> pool.submit("old$i") {
                started.countDown()
                try { CountDownLatch(1).await(); 1 } finally { stopped.countDown() }
            } }
            assertTrue(started.await(2,TimeUnit.SECONDS))
            pool.configure(AutoMixPerformance.EFFICIENT)
            assertTrue(stopped.await(2,TimeUnit.SECONDS))
            assertTrue(jobs.all { it.isCancelled })
            assertEquals(AutoMixPerformance.EFFICIENT,pool.mode)
            assertEquals(3,pool.submit("fresh") { 3 }.get(2,TimeUnit.SECONDS))
        }
    }
    @Test fun queueIsBoundedAndCancellationRemovesQueuedWork() {
        AnalysisWorkers<Int>(AutoMixPerformance.EFFICIENT).use { pool ->
            val jobs=(0..20).map { i -> pool.submit("$i") { CountDownLatch(1).await(); 1 } }
            assertTrue(pool.queuedCount<=8)
            assertTrue(jobs.any { it.isDone && !it.isCancelled && runCatching { it.get() }.exceptionOrNull()?.let(AutoMixFailure::of)==AutoMixFailure.WORKER_QUEUE_FULL })
            pool.cancelAll()
            assertEquals(0,pool.queuedCount)
            assertTrue(jobs.all { it.isCancelled || it.isDone })
        }
    }
    @Test fun cancellationSnapshotsAQueueWhileWorkersDrainItWithoutIteratorRaces() {
        AnalysisWorkers<Int>(AutoMixPerformance.EFFICIENT).use { pool ->
            repeat(500) { turn ->
                pool.submit("race-$turn-a",false) { 1 }
                pool.submit("race-$turn-b",false) { 2 }
                pool.cancelAll()
            }
            assertEquals(0,pool.queuedCount)
        }
    }

}
