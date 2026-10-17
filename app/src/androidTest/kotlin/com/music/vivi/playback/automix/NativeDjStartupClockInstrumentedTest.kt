// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Explicit device diagnostic, separate from the 14 execution acceptance tests.
 * Observe an incoming output for two seconds instead of discarding it at the first drift
 * rejection. This intentionally does not run/approve a transition: incoming stays muted,
 * no promotion, timing correction, seek, speed change or production registration occurs.
 * Passing proves bounded observation/cleanup, NOT successful synchronization. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeDjStartupClockInstrumentedTest {
    @Test fun observeColdStartWithoutChangingTheExecutionDriftLimit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // Fixed test-only private report, overwritten on each explicit probe. No audio/identities.
        File(context.filesDir,"phase32-startup-clock.txt").bufferedWriter().use {report ->
            report.appendLine("Phase 3.2 cold-start observation; schema=1; synchronization is NOT asserted")
            for(extended in listOf(false,true)) observe(extended) {line ->
                report.appendLine(line);Log.i("TiniumDjStartup",line)
            }
        }
    }

    private fun observe(extended:Boolean,emitLine:(String)->Unit) {
        NativeDjAndroidHarness(extended=extended).use { h ->
            val label=if(extended)"extended" else "standard"
            val samples=ArrayList<Sample>(128)
            val callbacks=ArrayList<String>(32)
            var dispatchUs=0L
            var commandOutgoingUs=0L
            var nextSampleUs=0L
            lateinit var deck:NativeDjDeck
            var outListener:AnalyticsListener?=null
            var inListener:AnalyticsListener?=null
            fun nowUs()=SystemClock.elapsedRealtimeNanos()/1000
            fun emit(message:String) {
                emitLine("NATIVE_DJ_STARTUP $label $message")
            }
            fun listener(role:String)=object:AnalyticsListener {
                private fun event(message:String) {
                    if(callbacks.size<32)callbacks+="$role sinceCommandUs=${if(dispatchUs==0L)-1 else nowUs()-dispatchUs} $message"
                }
                override fun onAudioPositionAdvancing(eventTime:AnalyticsListener.EventTime,playoutStartSystemTimeMs:Long) {
                    // Media3 reports this start using currentTimeMillis, not a track position.
                    // Never subtract it from monotonic times or call it an acoustic measurement.
                    event("positionAdvancing reportedWallStartMs=$playoutStartSystemTimeMs")
                }
                override fun onAudioTrackInitialized(eventTime:AnalyticsListener.EventTime,config:AudioSink.AudioTrackConfig) {
                    event("trackInitialized rate=${config.sampleRate} encoding=${config.encoding} channelsMask=${config.channelConfig} bufferBytes=${config.bufferSize} offload=${config.offload}")
                }
                override fun onAudioUnderrun(eventTime:AnalyticsListener.EventTime,bufferSize:Int,bufferSizeMs:Long,elapsedSinceLastFeedMs:Long) {
                    event("underrun bufferBytes=$bufferSize bufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs")
                }
            }
            try {
                h.main {
                    assertTrue(h.playback.claim())
                    outListener=listener("outgoing").also {h.outgoing.addAnalyticsListener(it)}
                    deck=h.playback.prepare(h.plan)
                    inListener=listener("incoming").also {h.incoming!!.addAnalyticsListener(it)}
                    deck.gain(0.0)
                }
                h.until("probe incoming prepared",3000) {
                    val b=deck.snapshot()
                    b.ready && !b.playing && abs(b.positionUs-h.plan.candidate.incomingCueUs)<=15_000
                }
                h.until("probe outgoing anchor",10_000) {h.outgoing.currentPosition*1000>=h.plan.candidate.outgoingCueUs}
                h.main {
                    commandOutgoingUs=h.outgoing.currentPosition*1000
                    assertTrue("Probe dispatch late",commandOutgoingUs-h.plan.candidate.outgoingCueUs<=40_000)
                    dispatchUs=nowUs();deck.play()
                    emit("begin plannedStartUs=${h.plan.candidate.outgoingCueUs} cueUs=${h.plan.candidate.incomingCueUs} commandOutgoingUs=$commandOutgoingUs limitUs=120000 incomingGain=0 observationOnly=true")
                }
                h.until("probe two seconds of startup observations",4000) {
                    val elapsed=nowUs()-dispatchUs
                    if(elapsed>=nextSampleUs && samples.size<128) {
                        val a=h.outgoing.currentPosition*1000
                        val b=deck.snapshot()
                        assertFalse("Probe incoming player failed",b.failed)
                        samples+=Sample(elapsed,a-h.plan.candidate.outgoingCueUs,
                            b.positionUs-h.plan.candidate.incomingCueUs,b.playing,b.ready)
                        nextSampleUs=elapsed+20_000
                    }
                    elapsed>=2_000_000
                }
                h.main {
                    assertTrue("Probe did not observe advancing incoming PCM",samples.any {it.inElapsedUs>300_000 && it.playing})
                    assertTrue("Probe must not promote",h.promotions==0)
                    assertEquals(0f,h.incoming!!.volume,0f)
                }
            } finally {
                h.main {
                    // Fixed schema, bounded entries, no identity/title/source/exception dump.
                    for(sample in samples)emit("sample $sample")
                    for(callback in callbacks)emit("event $callback")
                    val tail=samples.filter {it.sinceCommandUs>=1_000_000}
                    emit("summary count=${samples.size} firstAdvanceUs=${samples.firstOrNull {it.inElapsedUs>0}?.sinceCommandUs} firstOffsetUs=${samples.firstOrNull {it.inElapsedUs>0}?.offsetUs} tailMinOffsetUs=${tail.minOfOrNull {it.offsetUs}} tailMaxOffsetUs=${tail.maxOfOrNull {it.offsetUs}} maxAbsOffsetUs=${samples.maxOfOrNull {abs(it.offsetUs)}}")
                    outListener?.let {h.outgoing.removeAnalyticsListener(it)}
                    inListener?.let {h.incoming?.removeAnalyticsListener(it)}
                }
            }
        }
    }

    private data class Sample(val sinceCommandUs:Long,val outElapsedUs:Long,val inElapsedUs:Long,
                              val playing:Boolean,val ready:Boolean) {
        val offsetUs get()=outElapsedUs-inElapsedUs
        override fun toString()="sinceCommandUs=$sinceCommandUs outElapsedUs=$outElapsedUs inElapsedUs=$inElapsedUs offsetUs=$offsetUs playing=$playing ready=$ready"
    }
}
