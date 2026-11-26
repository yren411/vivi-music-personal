package com.music.vivi.playback.automix

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class AnalysisWorkerSnapshot(val executions:Long,val cacheHits:Long,val coalesced:Long,val cancellations:Long,
    val cachedResults:Int,val active:Int,val queued:Int)

/** One service-owned, bounded executor. No per-song executors, ML pools or unbounded queues. */
class AnalysisWorkers<T>(mode: AutoMixPerformance = AutoMixPerformance.BALANCED,
    private val budget:AnalysisConcurrencyBudget?=null) : AutoCloseable {
    private val lock = Any()
    private val sequence = AtomicInteger()
    private val executions=AtomicLong();private val hits=AtomicLong();private val coalesced=AtomicLong();private val cancellations=AtomicLong()
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

    private inner class AnalysisTask(val key: String, val cacheable: Boolean, val deadlineNanos:Long, work: () -> T) :
        FutureTask<T>(Callable { executions.incrementAndGet();if(budget==null)work() else budget.run(work) }) {
        val leases=mutableSetOf<CompletableFuture<T>>()
        var directObserver=false
        var retainWhenUnobserved=false
        // Called only under lock, after completion (or an unsuccessful cancellation).
        fun cacheCompletedResult() {
            if (cacheable && isDone && !isCancelled) try { cache[key] = get() }
            catch (_: Exception) { /* failed analyses are not cached */ }
        }

        fun publish(lease:CompletableFuture<T>) {
            try {lease.complete(get())}catch(error:Exception) {
                if(isCancelled)lease.cancel(false) else lease.completeExceptionally(error.cause ?: error)
            }
        }
        override fun done() = synchronized(lock) {
            leases.toList().forEach(::publish);leases.clear()
            if (running[key] === this) {
                cacheCompletedResult()
                if(isCancelled)cancellations.incrementAndGet()
                running.remove(key)
            }
        }
    }

    fun hasCached(key: String): Boolean = synchronized(lock) { cache.containsKey(key) }

    fun submit(key: String, cacheable: Boolean = true, work: () -> T): Future<T> = synchronized(lock) {
        val result=submitLocked(key,cacheable,work=work)
        running[key]?.takeIf {it===result}?.directObserver=true
        result
    }

    /** Consumer-local cancellation. A departing observer cannot cancel another consumer.
     * Retained immutable work is still bounded by its original control deadline and pool shutdown. */
    fun acquire(key:String,cacheable:Boolean=true,retainOnCancel:Boolean=false,deadlineNanos:Long=Long.MAX_VALUE,work:()->T):Future<T> = synchronized(lock) {
        val result=submitLocked(key,cacheable,deadlineNanos,work)
        val task=running[key]?.takeIf {it===result} ?: return@synchronized result
        task.retainWhenUnobserved=task.retainWhenUnobserved || (cacheable && retainOnCancel)
        val lease=object:CompletableFuture<T>() {
            override fun cancel(mayInterruptIfRunning:Boolean):Boolean = synchronized(lock) {
                if(isDone)return@synchronized false
                val changed=super.cancel(mayInterruptIfRunning)
                if(changed) {
                    task.leases.remove(this)
                    if(task.leases.isEmpty() && !task.directObserver && !task.retainWhenUnobserved && !task.isDone)
                        task.cancel(mayInterruptIfRunning)
                }
                changed
            }
        }
        if(task.isDone)task.publish(lease) else task.leases.add(lease)
        lease
    }

    private fun submitLocked(key:String,cacheable:Boolean,deadlineNanos:Long=Long.MAX_VALUE,work:()->T):Future<T> {
        if (cacheable) cache[key]?.let {hits.incrementAndGet();return CompletableFuture.completedFuture(it) }
        running[key]?.let { existing ->
            if (!existing.isDone) {coalesced.incrementAndGet();return existing}
            // get() may return before FutureTask.done removes this entry. Reuse a finished
            // success only for immutable/cacheable sources, never as mutable in-flight work.
            if (cacheable && !existing.isCancelled && runCatching { existing.get() }.isSuccess) {
                hits.incrementAndGet();return existing
            }
            running.remove(key)
        }
        val task = AnalysisTask(key, cacheable, deadlineNanos, work)
        running[key] = task
        try { executor.execute(task) } catch (_: RejectedExecutionException) {
            running.remove(key)
            task.cancel(false)
            return CompletableFuture<T>().apply { completeExceptionally(AnalysisUnavailable("WORKER_QUEUE_FULL")) }
        }
        return task
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

    /** Existing service tick expires retained jobs even while they wait for the shared budget.
     * First submitter's deadline is immutable; a new observer cannot extend/shorten it. */
    fun cancelExpired(nowNanos:Long=System.nanoTime())=synchronized(lock) {
        running.values.filter {!it.isDone && it.deadlineNanos<=nowNanos}.forEach {task ->
            task.leases.toList().forEach {it.completeExceptionally(AnalysisUnavailable("ANALYSIS_TIMEOUT"))}
            task.cancel(true)
        }
        executor.purge()
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
    fun snapshot():AnalysisWorkerSnapshot=synchronized(lock) {
        AnalysisWorkerSnapshot(executions.get(),hits.get(),coalesced.get(),cancellations.get(),cache.size,executor.activeCount,executor.queue.size)
    }
    internal val queuedCount get() = executor.queue.size
}
