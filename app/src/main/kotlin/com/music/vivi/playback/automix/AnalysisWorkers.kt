package com.music.vivi.playback.automix

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** One service-owned, bounded executor. No per-song executors, ML pools or unbounded queues. */
class AnalysisWorkers<T>(mode: AutoMixPerformance = AutoMixPerformance.BALANCED) : AutoCloseable {
    private val lock = Any()
    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(mode.workers, mode.workers, 1, TimeUnit.SECONDS,
        ArrayBlockingQueue(4), ThreadFactory { task ->
            Thread(task, "Tinium-AutoMix-${sequence.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val running = mutableMapOf<String, FutureTask<T>>()
    private val cache = object : LinkedHashMap<String, T>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>) = size > 64
    }
    var mode = mode
        private set

    fun submit(key: String, cacheable: Boolean = true, work: () -> T): Future<T> = synchronized(lock) {
        if (cacheable) cache[key]?.let { return@synchronized CompletableFuture.completedFuture(it) }
        running[key]?.let { return@synchronized it }
        lateinit var task: FutureTask<T>
        task = object : FutureTask<T>(Callable { work() }) {
            override fun done() = synchronized(lock) {
                if (running[key] === this) running.remove(key)
                if (cacheable && !isCancelled) try { cache[key] = get() } catch (_: Exception) { /* failed analyses are not cached */ }
            }
        }
        running[key] = task
        try { executor.execute(task) } catch (_: RejectedExecutionException) {
            running.remove(key)
            task.cancel(false)
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
        running.values.toList().forEach { it.cancel(true) }
        running.clear()
        executor.purge()
    }

    override fun close() { cancelAll(); executor.shutdownNow(); synchronized(lock) { cache.clear() } }
    internal val queuedCount get() = executor.queue.size
}
