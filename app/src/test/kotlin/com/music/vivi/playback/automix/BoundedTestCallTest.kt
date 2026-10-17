// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BoundedTestCallTest {
    @Test fun successfulCallRunsExactlyOnce() {
        var calls=0
        BoundedTestCall.run("success",1000,{it.run()},{fail("completed task removed")}){calls++}
        assertEquals(1,calls)
    }
    @Test fun originalFailureIsPreserved() {
        val error=IllegalStateException("fixture")
        try {BoundedTestCall.run("failure",1000,{it.run()},{}){throw error};fail()}
        catch(actual:IllegalStateException){assertSame(error,actual)}
    }
    @Test fun timeoutRemovesAndCancelsQueuedMutationInsteadOfRunningItLater() {
        var queued:Runnable?=null;var removed:Runnable?=null;var calls=0
        try {BoundedTestCall.run("queue",10,{queued=it},{removed=it},{"main stack unavailable"}){calls++};fail()}
        catch(error:AssertionError){assertTrue(error.message!!.contains("MAIN_CALL_TIMEOUT phase=queue"));assertTrue(error.message!!.contains("main stack unavailable"))}
        assertSame(queued,removed);queued!!.run();assertEquals(0,calls)
    }
    @Test fun timeoutDoesNotInterruptAlreadyRunningPlatformCode() {
        val began=CountDownLatch(1);val finish=CountDownLatch(1);val interrupted=AtomicBoolean()
        var worker:Thread?=null
        try {
            try {BoundedTestCall.run("running",10,{task ->
                worker=Thread {task.run()}.also {it.start()};assertTrue(began.await(3,TimeUnit.SECONDS))
            },{}){began.countDown();try {finish.await()}catch(_:InterruptedException){interrupted.set(true)}};fail()}
            catch(error:AssertionError){assertTrue(error.message!!.contains("MAIN_CALL_TIMEOUT phase=running"))}
            assertFalse(interrupted.get())
        }finally {finish.countDown();worker?.join(3000)}
        assertFalse(worker!!.isAlive);assertFalse(interrupted.get())
    }
    @Test fun interruptedWaitCancelsQueuedWorkAndPreservesInterrupt() {
        var queued:Runnable?=null;var calls=0
        try {
            Thread.currentThread().interrupt()
            try {BoundedTestCall.run("interrupted",1000,{queued=it},{}){calls++};fail()}
            catch(_:InterruptedException){assertTrue(Thread.currentThread().isInterrupted)}
        }finally {Thread.interrupted()}
        queued!!.run();assertEquals(0,calls)
    }
}
