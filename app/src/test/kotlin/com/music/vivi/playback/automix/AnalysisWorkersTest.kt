package com.music.vivi.playback.automix

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AnalysisWorkersTest {
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
            assertEquals(0,planned.get()); assertTrue(pool.queuedCount<=4)
            pool.cancelAll()
            assertTrue(decoding.isCancelled); assertTrue(planning.isCancelled)
            assertEquals(0,pool.queuedCount)
            val thread=pool.submitTransient { Thread.currentThread().name }.get(2,TimeUnit.SECONDS)
            assertTrue(thread.startsWith("Tinium-AutoMix-"))
        }
    }
    @Test fun planningQueueCannotGrowBeyondTheSameFourWaitingJobs() {
        AnalysisWorkers<Int>(AutoMixPerformance.EFFICIENT).use { pool ->
            val gate=CountDownLatch(1)
            val entered=CountDownLatch(1)
            pool.submit("occupied") { entered.countDown(); gate.await(); 1 }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val jobs=(0..12).map { pool.submitTransient { it.toString() } }
            assertTrue(pool.queuedCount<=4); assertTrue(jobs.any { it.isCancelled })
            pool.cancelAll(); assertTrue(jobs.all { it.isCancelled })
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
            assertTrue(pool.queuedCount<=4)
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
            assertTrue(pool.queuedCount<=4)
            assertTrue(jobs.any { it.isCancelled })
            pool.cancelAll()
            assertEquals(0,pool.queuedCount)
            assertTrue(jobs.all { it.isCancelled })
        }
    }
}
