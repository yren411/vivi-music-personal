package com.music.vivi.playback.automix

/** Shared service budget across independently typed legacy/native caches. Waiting is interruptible
 * on analysis workers only; no nested future waits and no additional scheduler. */
class AnalysisConcurrencyBudget(mode:AutoMixPerformance=AutoMixPerformance.BALANCED) {
    private val lock=java.util.concurrent.locks.ReentrantLock()
    private val changed=lock.newCondition()
    private var limit=mode.workers
    private var active=0
    fun configure(mode:AutoMixPerformance) {lock.lock();try {limit=mode.workers;changed.signalAll()}finally {lock.unlock()}}
    fun <T> run(work:()->T):T {
        lock.lockInterruptibly()
        try {while(active>=limit)changed.await();active++}finally {lock.unlock()}
        try {return work()}finally {lock.lock();try {active--;changed.signalAll()}finally {lock.unlock()}}
    }
}
