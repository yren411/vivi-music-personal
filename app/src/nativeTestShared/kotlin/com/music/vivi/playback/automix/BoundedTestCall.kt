// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Test-only bridge. Never wait for global application idleness or leave a timed-out queued
 * mutation able to run in a later test. An already-running platform call cannot be interrupted
 * safely; report it without pretending this helper can recover a blocked Android main thread. */
internal object BoundedTestCall {
    fun run(label:String,timeoutMs:Long,post:(Runnable)->Unit,remove:(Runnable)->Unit,
        diagnostic:()->String={""},block:()->Unit) {
        require(timeoutMs>0)
        val task=FutureTask {block();Unit}
        post(task)
        try {task.get(timeoutMs,TimeUnit.MILLISECONDS)}
        catch(error:TimeoutException) {
            task.cancel(false);remove(task)
            throw AssertionError("NATIVE_SERVICE_ANDROID MAIN_CALL_TIMEOUT phase=$label\n${diagnostic()}",error)
        }catch(error:InterruptedException) {
            task.cancel(false);remove(task);Thread.currentThread().interrupt();throw error
        }catch(error:ExecutionException) {throw error.cause ?: error}
    }
}
