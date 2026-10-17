// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.test.platform.app.InstrumentationRegistry
import com.music.vivi.playback.effects.AudioEffectSessions
import com.music.vivi.extensions.setOffloadEnabled
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real JNI + actual Media3 playback. Media-clock observations only: no acoustic capture. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeDjExecutionInstrumentedTest {
    @Test fun nativePlanExecutesExtendedOverlapAndPromotesExactlyOnce()=withPair(extended=true) { h ->
        h.start();h.until("completed",45_000){h.executor.terminal}
        h.main {
            assertEquals(h.failure(),NativeDjExecutionStage.COMPLETED,h.executor.status.stage)
            val m=h.executor.measurements()
            assertTrue(m.plannedOverlapUs>=29_000_000)
            assertTrue(m.concurrentProgressUs>=m.plannedOverlapUs-150_000)
            assertTrue(abs(m.estimatedStartOffsetUs!!)<=120_000)
            assertEquals(1,h.promotions);assertSame(h.incoming,h.primary);assertTrue(h.incoming!!.isPlaying)
            assertTrue(h.incoming!!.currentPosition*1000>h.plan.candidate.incomingCueUs)
            assertEquals(h.volume,h.incoming!!.volume,.0001f)
            assertEquals(1,h.released.count {it===h.outgoing})
            val session=h.incoming!!.audioSessionId
            val promotion=h.sessionEvents.indexOfLast {it.kind==AudioEffectSessions.Kind.PROMOTE && it.sessionId==session}
            assertTrue(promotion>=0)
            assertFalse(h.sessionEvents.drop(promotion+1).any {it.kind==AudioEffectSessions.Kind.CLOSE && it.sessionId==session})
            if(h.outgoingSessionAtRelease!=session)assertTrue(h.sessionEvents.drop(promotion+1).any {it.kind==AudioEffectSessions.Kind.CLOSE && it.sessionId==h.outgoingSessionAtRelease})
            assertTrue(h.outgoingAtReleaseUs!!<h.plan.outgoing.durationUs)
            h.executor.tick();assertEquals(1,h.promotions)
        }
    }
    @Test fun aacAndFlacCorrectedDecodeNativePlanReachesRealPlayback()=withPair(codec=true){h ->
        h.start();h.mixing();h.until("both advance",5000){h.incoming!!.currentPosition*1000>h.plan.candidate.incomingCueUs+500_000}
        h.main {assertTrue(h.outgoing.isPlaying && h.incoming!!.isPlaying);assertTrue(h.incoming!!.volume>0);assertTrue(h.outgoing.volume>0)}
    }
    @Test fun incomingIsPreparedPausedAtTheSelectedCue()=withPair {h ->
        h.start();h.until("armed",3000){h.executor.status.stage==NativeDjExecutionStage.ARMED}
        h.main {assertFalse(h.incoming!!.playWhenReady);assertTrue(abs(h.incoming!!.currentPosition*1000-h.plan.candidate.incomingCueUs)<=15_000);assertEquals(0f,h.incoming!!.volume,0f)}
    }
    @Test fun pauseAndResumeFreezeBothMediaClocksAndEnvelope()=withPair {h ->
        h.start();h.mixing();h.until("incoming advances",3000){h.incoming!!.currentPosition*1000>h.plan.candidate.incomingCueUs+300_000}
        h.main {h.playback.pauseForUserOrFocus()}
        h.until("paused",2000){!h.outgoing.isPlaying && !h.incoming!!.isPlaying}
        var a=0L;var b=0L;var g=0f
        h.main {a=h.outgoing.currentPosition;b=h.incoming!!.currentPosition;g=h.incoming!!.volume}
        h.observeFor(400)
        h.main {assertEquals(a,h.outgoing.currentPosition);assertEquals(b,h.incoming!!.currentPosition);assertEquals(g,h.incoming!!.volume,0f);h.playback.resumeAfterUserOrFocus()}
        h.until("resumed",3000){h.outgoing.isPlaying && h.incoming!!.isPlaying}
    }
    @Test fun cancellationDuringPreparationPreservesOutgoing()=withPair {h ->
        h.start();h.main {h.playback.invalidate(NativeDjExecutionReason.CANCELED);assertEquals(0,h.promotions);assertTrue(h.outgoing.playWhenReady);assertEquals(h.volume,h.outgoing.volume,.0001f);assertEquals(1,h.released.size)}
    }
    @Test fun cancelDuringOverlapReleasesOnlyIncoming()=withPair {h ->
        h.start();h.mixing();h.main {h.playback.invalidate(NativeDjExecutionReason.USER_SKIP);assertTrue(h.outgoing.isPlaying);assertEquals(0,h.promotions);assertTrue(h.released.contains(h.incoming));assertFalse(h.released.contains(h.outgoing))}
    }
    @Test fun queueGenerationChangeCannotPromoteStaleIncoming()=withPair {h ->
        h.start();h.mixing();h.main {h.generation++};h.until("invalidated",2000){h.executor.terminal}
        h.main {assertEquals(NativeDjExecutionReason.QUEUE_CHANGED,h.executor.status.reason);assertEquals(0,h.promotions);assertTrue(h.outgoing.isPlaying)}
    }
    @Test fun userVolumeIsAppliedOnceAndNewValueSurvivesCancellation()=withPair {h ->
        h.start();h.mixing();h.main {h.volume=.073f;h.playback.refreshUserVolume();assertTrue(h.outgoing.volume<=h.volume);assertTrue(h.incoming!!.volume<=h.volume);h.playback.invalidate(NativeDjExecutionReason.USER_SEEK);assertEquals(.073f,h.outgoing.volume,.00001f)}
    }
    @Test fun quietTailDoesNotMakeIncomingNearlySilent()=withPair {h ->
        val quiet=h.outLevel.copy(activeRms=.0001)
        assertEquals(1.0,NativeDjGain.content(h.plan,quiet,h.inLevel).incoming,0.0)
        h.start(outLevel=quiet);h.mixing()
        h.until("middle overlap",20_000){h.outgoing.currentPosition*1000>=h.plan.candidate.outgoingCueUs+h.plan.candidate.overlapUs/2}
        h.main {assertTrue(h.failure(),h.incoming!!.volume>=h.volume*.45);assertTrue(h.outgoing.volume>=h.volume*.35)}
    }
    @Test fun realAudioSessionsOpenForBothAndCancelClosesOnlyIncoming()=withPair {h ->
        h.start();h.mixing();h.until("real output sessions",4000){val a=h.outgoing.audioSessionId;val b=h.incoming!!.audioSessionId
            a>0 && b>0 && h.sessionEvents.filter {it.kind==AudioEffectSessions.Kind.OPEN}.map {it.sessionId}.toSet().containsAll(setOf(a,b))}
        h.main {
            val a=h.outgoing.audioSessionId;val b=h.incoming!!.audioSessionId
            assertTrue(a>0 && b>0)
            assertTrue(h.sessionEvents.any {it.kind==AudioEffectSessions.Kind.OPEN && it.sessionId==a})
            assertTrue(h.sessionEvents.any {it.kind==AudioEffectSessions.Kind.OPEN && it.sessionId==b})
            h.playback.invalidate(NativeDjExecutionReason.CANCELED)
            assertFalse(h.sessionEvents.any {it.kind==AudioEffectSessions.Kind.CLOSE && it.sessionId==a})
            if(a!=b)assertTrue(h.sessionEvents.any {it.kind==AudioEffectSessions.Kind.CLOSE && it.sessionId==b})
        }
    }
    @Test fun routeAndFocusEventsUseExplicitPauseAndCancellationPolicies()=withPair {h ->
        h.start();h.mixing();h.main {h.playback.pauseForUserOrFocus(NativeDjExecutionReason.FOCUS_LOST);assertEquals(NativeDjExecutionStage.PAUSED,h.executor.status.stage);h.playback.invalidate(NativeDjExecutionReason.ROUTE_CHANGED);assertEquals(NativeDjExecutionReason.ROUTE_CHANGED,h.executor.status.reason);assertFalse(h.outgoing.playWhenReady);assertEquals(0,h.promotions)}
    }
    @Test fun unsupportedPlanNeverCreatesIncomingPlayer()=withPair {h ->
        h.start(h.plan.copy(requiredFutureCapability="unsupported"));h.main {assertEquals(NativeDjExecutionReason.UNSUPPORTED_CAPABILITY,h.executor.status.reason);assertNull(h.incoming);assertTrue(h.outgoing.isPlaying)}
    }
    @Test fun gainBaselineAndOwnershipGuardsPreventCompetingControllers()=withPair {h ->
        h.main {
            h.outgoing.volume=.03f
            val mismatch=h.adapter()
            try {assertEquals(NativeDjExecutionReason.OWNERSHIP_BUSY,mismatch.execute(h.plan).status.reason);assertEquals(.03f,h.outgoing.volume,0f)}
            finally {mismatch.close();h.outgoing.volume=h.volume}
        }
        h.start();h.main {
            val second=h.adapter();try {val e=second.execute(h.plan);assertEquals(NativeDjExecutionReason.OWNERSHIP_BUSY,e.status.reason)}finally {second.close()}
            assertTrue(h.outgoing.pauseAtEndOfMediaItems)
        }
    }
    @Test fun legacyDefaultsRemainUntouched() {
        assertEquals(AnalysisPreprocessing.LEGACY,AndroidWindowDecoder().preprocessing)
        assertEquals("nearest-time-mono-22050-v1",LegacyKotlinAnalysisEngine().let {AndroidWindowDecoder().preprocessing.version})
    }

    private fun withPair(codec:Boolean=false,extended:Boolean=false,block:(NativeDjAndroidHarness)->Unit) {
        NativeDjAndroidHarness(codec,extended).use(block)
    }
}

/** Internal-only evaluation fixture. Caller owns source files, focus policy and current primary.
 * Main-looper polling has a deadline and removes callbacks on every exit; never sleeps UI/audio threads. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NativeDjAndroidHarness(codec:Boolean=false,private val extended:Boolean=false,localOutgoing:File?=null,localIncoming:File?=null,
                                     outgoingStartUs:Long=0):AutoCloseable {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val files=mutableListOf<File>()
    val sessionEvents=mutableListOf<AudioEffectSessions.Event>()
    lateinit var outgoing:ExoPlayer
    var incoming:ExoPlayer?=null
    var primary:ExoPlayer?=null
    lateinit var playback:Media3NativeDjPlayback
    lateinit var executor:NativeDjTransitionExecutor
    lateinit var plan:NativeTransitionPlan
    lateinit var outLevel:NativeDjLevel
    lateinit var inLevel:NativeDjLevel
    var generation=1L
    var promotions=0
    var volume=.12f
    val released=mutableListOf<ExoPlayer>()
    var outgoingAtReleaseUs:Long?=null
    var outgoingSessionAtRelease:Int?=null
    private lateinit var outputs:NativeDjAudioOutputs
    private lateinit var incomingItem:MediaItem
    init {
        try {
            val a=localOutgoing ?: fixture(if(codec)"rhythm-128-48000.m4a" else null,"out")
            val b=localIncoming ?: fixture(if(codec)"rhythm-128-44100.flac" else null,"in")
            fun analyze(file:File,id:String,start:Long):Pair<PlanningWindowEvidence,DecodedWindow> {
                val source=AnalysisSource(Uri.fromFile(file),id,file.length(),"phase32-local-rendition-v1",DefaultDataSource.Factory(context),true)
                val control=AnalysisControl(System.nanoTime()+30_000_000_000)
                val decoded=AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED).decode(source,
                    DecodeRequest(AudioIntervalUs(start,start+if(codec)24_000_000 else 32_000_000),AnalysisSourceIdentity(id,source.identity)),control)
                assertTrue("Controlled decode outcome="+(decoded as? DecodeResult.Failed)?.failure,decoded is DecodeResult.Decoded)
                val window=(decoded as DecodeResult.Decoded).window
                val native=BitChordNativeAnalysisEngine().analyze(window,AnalysisDetail.DETAIL,control)
                return PlanningWindowEvidence(native,PlanningSignalObservation.measure(window,control)) to window
            }
            val (x,aw)=analyze(a,"execution-out",outgoingStartUs);val (y,bw)=analyze(b,"execution-in",0)
            outLevel=NativeDjLevel.measure(aw);inLevel=NativeDjLevel.measure(bw)
            val am=(x.analysis as AnalysisResult.Completed).metadata;val bm=(y.analysis as AnalysisResult.Completed).metadata
            val req=NativePlanningRequest(PlanningTrack(am.source,am.duration.durationUs!!,
                AudioIntervalUs(outgoingStartUs+if(extended)1_000_000 else 4_000_000,aw.sampleInterval!!.endUs)),
                PlanningTrack(bm.source,bm.duration.durationUs!!,bw.sampleInterval!!),outgoingStartUs+if(extended)0 else 2_000_000,if(extended)250_000 else 1_000_000,1,"phase32-instrumentation-session")
            val result=NativeAwareAutoMixPlanner().plan(req,x,y)
            assertTrue("Native planner outcome=$result",result is NativePlanningOutcome.Selected)
            plan=(result as NativePlanningOutcome.Selected).plan
            main {
                outputs=NativeDjAudioOutputs(context){sessionEvents+=it}
                outgoing=newPlayer();primary=outgoing;outputs.attach(outgoing,AudioEffectSessions.Role.PRIMARY)
                outgoing.setMediaItem(MediaItem.Builder().setMediaId(plan.outgoing.source.recordingId).setUri(Uri.fromFile(a)).build(),outgoingStartUs/1000)
                outgoing.volume=volume;outgoing.prepare()
                incomingItem=MediaItem.Builder().setMediaId(plan.incoming.source.recordingId).setUri(Uri.fromFile(b)).build()
                playback=adapter()
            }
            until("outgoing prepared",10_000){outgoing.playbackState==androidx.media3.common.Player.STATE_READY}
            main {outgoing.play()}
            until("outgoing advancing",3000){outgoing.isPlaying && outgoing.currentPosition*1000>outgoingStartUs+30_000}
        }catch(error:Throwable){close();throw error}
    }
    fun adapter()=Media3NativeDjPlayback(outgoing,plan.outgoing,plan.incoming,incomingItem,
        {NativeDjQueue(generation,plan.outgoing.source,plan.incoming.source)},
        {newPlayer().also {incoming=it}},
        {q,p ->if(q.generation!=generation)false else {assertEquals(0,promotions);assertTrue(p===incoming);primary=p;promotions++;generation++;true}},
        {p ->if(p===outgoing){outgoingAtReleaseUs=p.currentPosition*1000;outgoingSessionAtRelease=p.audioSessionId};check(!released.contains(p));released+=p;p.release()},
        {volume},exclusiveControlAvailable={true},outputs=outputs)
    private fun newPlayer()=ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),false).setHandleAudioBecomingNoisy(true).build().also {it.setOffloadEnabled(false)}
    fun start(p:NativeTransitionPlan=plan,outLevel:NativeDjLevel=this.outLevel)=main {executor=playback.execute(p,outLevel,inLevel)}
    fun mixing()=until("mixing",10_000){executor.status.stage==NativeDjExecutionStage.MIXING && outgoing.isPlaying && incoming?.isPlaying==true}
    fun failure()="NATIVE_DJ_ANDROID "+executor.report().let {it.copy(measurements=it.measurements.copy(events=it.measurements.events.takeLast(6)))}
    fun main(block:()->Unit) {
        val error=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {try{block()}catch(t:Throwable){error.set(t)}}
        error.get()?.let {throw it}
    }
    fun until(label:String,timeoutMs:Long,condition:()->Boolean) {
        val done=CountDownLatch(1);val handler=Handler(Looper.getMainLooper());val error=AtomicReference<Throwable?>()
        val deadline=SystemClock.uptimeMillis()+timeoutMs
        val task=object:Runnable {
            override fun run(){try {
                if(condition()){done.countDown();return}
                if(::executor.isInitialized && executor.terminal)throw AssertionError("$label; "+failure())
                if(SystemClock.uptimeMillis()>=deadline)throw AssertionError("Timed out: $label; "+if(::executor.isInitialized)failure() else "outgoing state=${outgoing.playbackState}")
                handler.postDelayed(this,10)
            }catch(t:Throwable){error.set(t);done.countDown()}}
        }
        handler.post(task)
        try{assertTrue("Main looper timeout: $label",done.await(timeoutMs+2000,TimeUnit.MILLISECONDS));error.get()?.let {throw it}}
        finally{handler.removeCallbacks(task)}
    }
    fun observeFor(ms:Long){val end=SystemClock.uptimeMillis()+ms;until("observation interval",ms+2000){SystemClock.uptimeMillis()>=end}}
    private fun fixture(asset:String?,id:String):File {
        val f=File.createTempFile("phase32-",if(asset==null)".wav" else "."+asset.substringAfterLast('.'),context.cacheDir);files+=f
        if(asset!=null)instrumentation.context.assets.open("automix/$asset").use {input->f.outputStream().use {input.copyTo(it)}}
        else {
            val base=NativePlanningFixtures.pcm(seconds=32,phase=if(id=="out").137 else .333)
            // Legitimate generated source extends beyond the bounded 32s analysis, allowing a
            // ~30s overlap and continued incoming playback. No decoder coverage is invented.
            val pcm=if(extended)FloatArray(40*22050){base[it%base.size]} else base
            f.outputStream().buffered(65536).use {r ->
                fun le(v:Int,n:Int){repeat(n){i->r.write(v ushr(8*i) and 255)}}
                r.write("RIFF".toByteArray(Charsets.US_ASCII));le(36+pcm.size*2,4);r.write("WAVEfmt ".toByteArray(Charsets.US_ASCII));le(16,4);le(1,2);le(1,2);le(22050,4);le(44100,4);le(2,2);le(16,2);r.write("data".toByteArray(Charsets.US_ASCII));le(pcm.size*2,4)
                for(x in pcm)le((x.coerceIn(-1f,1f)*32767).toInt(),2)
            }
        }
        return f
    }
    override fun close() {
        main {
            if(::executor.isInitialized)Log.i("TiniumNativeDj",failure())
            if(::playback.isInitialized)playback.close()
            if(::outgoing.isInitialized && !released.contains(outgoing)){released+=outgoing;outgoing.release()}
            incoming?.takeIf {!released.contains(it)}?.let {released+=it;it.release()}
            if(::outputs.isInitialized)outputs.close()
        }
        files.forEach {check(it.delete())}
    }
}
