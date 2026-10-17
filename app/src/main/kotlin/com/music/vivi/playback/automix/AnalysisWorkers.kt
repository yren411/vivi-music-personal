package com.music.vivi.playback.automix

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** One service-owned, bounded executor. No per-song executors, ML pools or unbounded queues. */
class AnalysisWorkers<T>(mode: AutoMixPerformance = AutoMixPerformance.BALANCED,
    private val budget:AnalysisConcurrencyBudget?=null) : AutoCloseable {
    private val lock = Any()
    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(mode.workers, mode.workers, 1, TimeUnit.SECONDS,
        ArrayBlockingQueue(8), ThreadFactory { task ->
            Thread(task, "Tinium-AutoMix-${sequence.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val running = mutableMapOf<String, AnalysisTask>()
    private val transient = mutableSetOf<FutureTask<*>>()
    private val cache = object : LinkedHashMap<String, T>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>) = size > 64
    }
    var mode = mode
        private set

    private inner class AnalysisTask(val key: String, val cacheable: Boolean, work: () -> T) :
        FutureTask<T>(Callable { if(budget==null)work() else budget.run(work) }) {
        // Called only under lock, after completion (or an unsuccessful cancellation).
        fun cacheCompletedResult() {
            if (cacheable && isDone && !isCancelled) try { cache[key] = get() }
            catch (_: Exception) { /* failed analyses are not cached */ }
        }

        override fun done() = synchronized(lock) {
            if (running[key] === this) {
                cacheCompletedResult()
                running.remove(key)
            }
        }
    }

    fun hasCached(key: String): Boolean = synchronized(lock) { cache.containsKey(key) }

    fun submit(key: String, cacheable: Boolean = true, work: () -> T): Future<T> = synchronized(lock) {
        if (cacheable) cache[key]?.let { return@synchronized CompletableFuture.completedFuture(it) }
        running[key]?.let { existing ->
            if (!existing.isDone) return@synchronized existing
            // get() may return before FutureTask.done removes this entry. Reuse a finished
            // success only for immutable/cacheable sources, never as mutable in-flight work.
            if (cacheable && !existing.isCancelled && runCatching { existing.get() }.isSuccess)
                return@synchronized existing
            running.remove(key)
        }
        val task = AnalysisTask(key, cacheable, work)
        running[key] = task
        try { executor.execute(task) } catch (_: RejectedExecutionException) {
            running.remove(key)
            task.cancel(false)
            return@synchronized CompletableFuture<T>().apply { completeExceptionally(AnalysisUnavailable("WORKER_QUEUE_FULL")) }
        }
        task
    }

    /** Small aggregation/planning jobs share the SAME bounded pool, without feature-cache casts. */
    fun <R> submitTransient(work: () -> R): Future<R> = synchronized(lock) {
        val task=object : FutureTask<R>(Callable { if(budget==null)work() else budget.run(work) }) {
            override fun done() = synchronized(lock) { transient.remove(this); Unit }
        }
        transient.add(task)
        try { executor.execute(task) } catch (_: RejectedExecutionException) {
            task.cancel(false)
            return@synchronized CompletableFuture<R>().apply { completeExceptionally(AnalysisUnavailable("WORKER_QUEUE_FULL")) }
        }
        task
    }

    fun configure(newMode: AutoMixPerformance) = synchronized(lock) {
        if (mode == newMode) return@synchronized
        cancelAll()
        if (newMode.workers > executor.maximumPoolSize) {
            executor.maximumPoolSize = newMode.workers
            executor.corePoolSize = newMode.workers
        } else {
            executor.corePoolSize = newMode.workers
            executor.maximumPoolSize = newMode.workers
        }
        mode = newMode
    }

    fun cancelAll() = synchronized(lock) {
        // Cancel waiting work before interrupting workers: freeing a worker first could let
        // an obsolete queued planner begin between those operations.
        executor.queue.toTypedArray().forEach { (it as? Future<*>)?.cancel(false) }
        executor.purge()
        running.values.toList().forEach {
            it.cancel(true)
            // FutureTask publishes its result and wakes get() before invoking done().
            // If completion won cancellation, preserve that success before clearing the
            // map; its callback may still be waiting for this lock. Canceled/failed and
            // non-cacheable tasks never publish. A delayed done() cannot publish again.
            it.cacheCompletedResult()
        }
        running.clear()
        transient.toList().forEach { it.cancel(true) }
        transient.clear()
        executor.purge()
    }

    override fun close() { cancelAll(); executor.shutdownNow(); synchronized(lock) { cache.clear() } }
    internal val queuedCount get() = executor.queue.size
}
