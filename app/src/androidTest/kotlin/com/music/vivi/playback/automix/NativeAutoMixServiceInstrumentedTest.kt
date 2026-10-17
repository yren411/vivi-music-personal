// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import android.content.*
import android.os.*
import android.media.AudioManager
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.music.vivi.constants.*
import com.music.vivi.playback.MusicService
import com.music.vivi.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Binds the actual Hilt MusicService. No substitute player factory, analyzer, planner or executor.
 * Run only in the existing isolated .decodercheck debug application. All audio is generated locally. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeAutoMixServiceInstrumentedTest {
    @Volatile private var testMethod="unknown"
    @get:Rule val progress:TestWatcher=object:TestWatcher() {
        override fun starting(description:Description) {testMethod=description.methodName;event("START")}
        override fun failed(error:Throwable,description:Description) {event("FAILED type=${error.javaClass.simpleName}")}
        override fun finished(description:Description) {event("FINISHED")}
        private fun event(value:String) {android.util.Log.i("TiniumNativeDj","NATIVE_SERVICE_TEST test=$testMethod event=$value")}
    }
    // Includes the longest 150s progression assertion, setup and bounded cleanup.
    @get:Rule val testDeadline:Timeout=Timeout.seconds(240)

    @Test fun legacyDefaultDoesNotConstructNativePipeline()=withService(native=false){h ->
        h.main {val s=h.service.nativeServiceInspection();assertEquals(AutoMixEngineMode.LEGACY,s.mode);assertFalse(s.nativeSelected);assertNull(s.execution)}
        h.disableAutoMix();h.await("legacy disable clears status",3000){h.service.autoMixStatus.value.phase==AutoMixPhase.DISABLED}
    }
    @Test fun explicitOptInIsReversible()=withService(native=false){h ->
        h.mode(AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL);h.await("opt in",5000){h.service.nativeServiceInspection().nativeSelected}
        h.mode(AutoMixEngineMode.LEGACY);h.await("legacy rollback",5000){val s=h.service.nativeServiceInspection();!s.nativeSelected && s.legacyEnabled}
    }
    @Test fun serviceExecutesLongNativeOverlapAndPromotesQueueExactlyOnce()=withService {h ->
        h.play();h.mixing()
        h.await("real effect session ownership",3000){val s=h.service.nativeServiceInspection()
            s.incomingSessionId!=null && s.incomingSessionId!!>0 && s.incomingSessionId in s.activeSessions && h.service.player.audioSessionId in s.activeSessions}
        h.main {
            val s=h.service.nativeServiceInspection();assertFalse(s.legacyEnabled);assertEquals(2,s.outputs)
            assertTrue(h.report(),s.incomingPlaying==true);assertTrue(s.execution!!.measurements.plannedOverlapUs>=29_000_000)
        }
        h.await("native completion",50_000){h.completed()==1}
        h.main {
            assertEquals(h.items[1].mediaId,h.service.player.currentMediaItem?.mediaId)
            assertEquals(1,h.service.player.currentMediaItemIndex);assertEquals(h.items.size,h.service.player.mediaItemCount)
            assertTrue(h.service.player.isPlaying);assertEquals(.12f,h.service.player.volume,.0001f)
            assertEquals(1,h.service.nativeServiceInspection().outputs)
            val report=h.service.autoMixRecorder.snapshot().attempts.flatMap {it.records}.mapNotNull {it.native?.execution}.last {it.status.stage==NativeDjExecutionStage.COMPLETED}
            assertEquals(1,report.measurements.promotions)
            assertTrue(report.measurements.concurrentProgressUs>=report.measurements.plannedOverlapUs-150_000)
        }
        var position=0L;h.main {position=h.service.player.currentPosition}
        h.await("no restart after promotion",3000){h.service.player.currentPosition>position+300}
        assertEquals(1,h.completed())
    }
    @Test fun actualAacAndFlacSourcesUseThePinnedServiceNativePipeline()=withService(codec=true){h ->
        h.play();h.mixing()
        h.await("codec pair advancing",3000){h.service.nativeServiceInspection().incomingPlaying==true}
        h.main {val e=requireNotNull(h.service.nativeServiceInspection().execution)
            assertEquals(PcmPreprocessing.BANDLIMITED,e.preprocessing)
            assertEquals(0L,e.measurements.startupLeadUs)
            assertTrue(h.service.player.isPlaying);assertFalse(h.service.nativeServiceInspection().legacyEnabled)}
    }
    @Test fun pauseResumeVolumeAndInjectedFocusCallbacksReachBothRealDecks()=withService {h ->
        h.play();h.mixing()
        h.await("audible midpoint",25_000){val m=h.service.nativeServiceInspection().execution!!.measurements
            h.service.player.currentPosition*1000>=m.plannedStartUs+m.plannedOverlapUs/3}
        h.main {h.service.player.pause()}
        h.await("both paused",3000){h.service.nativeServiceInspection().execution?.status?.stage==NativeDjExecutionStage.PAUSED}
        h.main {assertFalse(h.service.nativeServiceInspection().incomingPlaying==true);h.service.playerVolume.value=.08f;h.service.player.play()}
        h.await("both resumed",3000){h.service.nativeServiceInspection().incomingPlaying==true}
        h.main {val s=h.service.nativeServiceInspection();assertTrue(s.primaryVolume<=.0801f);assertTrue(s.incomingVolume!! in .0001f.. .0801f)}
        // Inject the real private service callback, not OS arbitration; avoids a test-only production focus requester.
        h.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        h.await("focus pause",3000){h.service.nativeServiceInspection().execution?.status?.stage==NativeDjExecutionStage.PAUSED}
        h.focus(AudioManager.AUDIOFOCUS_GAIN)
        h.await("focus resume",4000){h.service.nativeServiceInspection().incomingPlaying==true}
    }
    @Test fun replacingNextTrackInvalidatesTheOldPair()=withService {h ->
        h.play()
        h.await("planning begins",5000){h.service.autoMixRecorder.snapshot().attempts.isNotEmpty()}
        h.main {h.service.player.removeMediaItem(1)}
        h.await("new queue identity",5000){h.service.player.nextMediaItemIndex==1 && h.service.player.getMediaItemAt(1).mediaId==h.items[2].mediaId}
        h.main {assertEquals(0,h.completed());assertTrue(h.service.nativeServiceInspection().outputs<=2)}
        assertTrue(h.report().contains("CANCELED") || h.report().contains("QUEUE_CHANGED"))
    }
    @Test fun disablingNativeDuringOverlapRestoresLegacyOwnership()=withService {h ->
        h.play();h.mixing();val original=AtomicReference<Any>();h.main {original.set(h.service.player)}
        h.mode(AutoMixEngineMode.LEGACY)
        h.await("mode rollback",5000){val s=h.service.nativeServiceInspection();!s.nativeSelected && s.legacyEnabled && s.outputs==1}
        h.main {assertSame(original.get(),h.service.player);assertTrue(h.service.player.isPlaying);assertEquals(.12f,h.service.player.volume,.0001f)}
    }
    @Test fun nonRhythmicIncomingFallsBackWithoutStoppingPlayback()=withService(silentIncoming=true){h ->
        h.play();h.await("native rejection",15_000){h.service.autoMixRecorder.snapshot().attempts.any {a->a.records.any {it.native?.event==NativeServiceEvent.FALLBACK}}}
        h.main {assertTrue(h.service.player.isPlaying);assertTrue(h.service.nativeServiceInspection().legacyEnabled);assertEquals(h.items[0].mediaId,h.service.player.currentMediaItem?.mediaId)}
        assertTrue(h.report().contains("FALLBACK"));assertEquals(0,h.completed())
    }
    @Test fun routeInvalidationReleasesIncomingWithoutMutingPrimary()=withService {h ->
        h.play();h.mixing();h.main {h.service.setPreferredAudioDevice(null)}
        h.await("standby released",3000){h.service.nativeServiceInspection().outputs==1}
        h.main {assertTrue(h.service.player.isPlaying);assertEquals(.12f,h.service.player.volume,.0001f);assertEquals(h.items[0].mediaId,h.service.player.currentMediaItem?.mediaId)}
    }
    @Test fun injectedUnexpectedEndDoesNotDoubleAdvanceOrLeaveTheQueuePaused()=withService {h ->
        h.play();h.mixing()
        h.main {h.service.onPlayWhenReadyChanged(false,Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)}
        h.await("normal queue continues after unexpected end",5000){h.service.player.currentMediaItem?.mediaId==h.items[1].mediaId && h.service.player.isPlaying}
        h.main {assertEquals(1,h.service.player.currentMediaItemIndex);assertEquals(1,h.service.nativeServiceInspection().outputs)
            assertEquals(.12f,h.service.player.volume,.0001f)}
    }
    @Test fun successiveAttemptsPreserveQueueAndReleaseOldPlayers()=withService(longIncoming=true){h ->
        h.play();h.await("successive native attempts and handoffs",150_000){
            val attempts=h.service.autoMixRecorder.snapshot().attempts.count {a->a.records.any {it.native?.event==NativeServiceEvent.PAIR}}
            attempts>=2 && h.completed()>=1 && h.service.player.currentMediaItem?.mediaId==h.items[2].mediaId
        }
        h.main {assertTrue(h.completed()>=1);assertEquals(h.items[2].mediaId,h.service.player.currentMediaItem?.mediaId)
            assertEquals(2,h.service.player.currentMediaItemIndex);assertEquals(1,h.service.nativeServiceInspection().outputs)
            assertTrue(h.service.player.isPlaying);assertEquals(.12f,h.service.player.volume,.0001f)}
    }
    private fun withService(native:Boolean=true,silentIncoming:Boolean=false,longIncoming:Boolean=false,codec:Boolean=false,block:(Harness)->Unit) {
        Harness(testMethod,native,silentIncoming,longIncoming,codec).use {h ->
            try {block(h)}catch(error:Throwable){h.captureFailure(error);throw error}
        }
    }
    private class Harness(private val testMethod:String,native:Boolean,silentIncoming:Boolean,longIncoming:Boolean,codec:Boolean):AutoCloseable {
        private val instrumentation=InstrumentationRegistry.getInstrumentation()
        private val context=instrumentation.targetContext.also {
            check(it.packageName.endsWith(".decodercheck")){"Use -PautomixDecoderTests=true; normal Tinium must not be touched"}
        }
        private val handler=Handler(Looper.getMainLooper())
        private val runStartedMs=SystemClock.elapsedRealtime()
        @Volatile private var phase="created"
        private val previous=preferences("snapshot preferences"){context.dataStore.data.first()}
        private val files=mutableListOf<File>()
        private var bound=false
        private var selectedMode=if(native)AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL else AutoMixEngineMode.LEGACY
        lateinit var service:MusicService
        val items:List<MediaItem>
        private val connected=CountDownLatch(1)
        private val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName?,binder:IBinder?) {
                check(binder is MusicService.MusicBinder){"Expected actual MusicService binder"};service=binder.service;connected.countDown()
            }
            override fun onServiceDisconnected(name:ComponentName?) {}
        }
        init {try {
            check(context.packageName.endsWith(".decodercheck")){"Use -PautomixDecoderTests=true; normal Tinium must not be touched"}
            preferences("configure preferences") {context.dataStore.edit {
                it[AutoMixEnabledKey]=true;it[AutoMixEngineModeKey]=(if(native)AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL else AutoMixEngineMode.LEGACY).name
                it[AutoMixPerformanceKey]=AutoMixPerformance.PERFORMANCE.name;it[PersistentQueueKey]=false
                it[SkipSilenceKey]=false;it[AudioOffload]=false;it[RepeatModeKey]=Player.REPEAT_MODE_OFF
            }}
            items=(0..2).map {index ->
                val seconds=if(codec)30 else if(index>0 && longIncoming)100 else 40
                val asset=if(codec)if(index==0)"rhythm-128-48000.m4a" else "rhythm-128-44100.flac" else null
                val pcm=if(asset!=null)FloatArray(0) else if(index==1 && silentIncoming)FloatArray(seconds*22050) else NativeAnalysisFixtures.groove(seconds=seconds,phase=if(index==0).137 else .333)
                val file=File.createTempFile("phase33-",if(asset==null)".wav" else "."+asset.substringAfterLast('.'),context.cacheDir);files+=file
                if(asset!=null)instrumentation.context.assets.open("automix/$asset").use {input->file.outputStream().use {input.copyTo(it)}}
                else file.outputStream().buffered(65536).use {out ->
                    fun le(v:Int,n:Int){repeat(n){out.write(v ushr(8*it) and 255)}}
                    out.write("RIFF".toByteArray());le(36+pcm.size*2,4);out.write("WAVEfmt ".toByteArray());le(16,4);le(1,2);le(1,2)
                    le(22050,4);le(44100,4);le(2,2);le(16,2);out.write("data".toByteArray());le(pcm.size*2,4)
                    for(value in pcm)le((value*32767).toInt(),2)
                }
                MediaItem.Builder().setMediaId("phase33-generated-$index").setUri(Uri.fromFile(file)).setCustomCacheKey("phase33-generated-$index")
                    .setTag(com.music.vivi.models.MediaMetadata("phase33-generated-$index","Generated fixture",emptyList(),seconds)).build()
            }
            main {bound=context.bindService(Intent(context,MusicService::class.java),connection,Context.BIND_AUTO_CREATE);check(bound)}
            check(connected.await(15,TimeUnit.SECONDS)){"MusicService did not bind"}
            await("service initialized",10_000){service.isPlayerReady.value && service.nativeServiceInspection().mode==selectedMode}
        }catch(error:Throwable){captureFailure(error);try {close()}catch(cleanup:Throwable){error.addSuppressed(cleanup)};throw error}}
        private fun stage(value:String) {
            phase=value
            val text="NATIVE_SERVICE_STAGE test=$testMethod phase=$value runStartedMs=$runStartedMs elapsedMs=${SystemClock.elapsedRealtime()}"
            android.util.Log.i("TiniumNativeDj",text)
            // Test-thread only; bounded last-stage file survives unavailable/truncated logcat.
            try {File(context.filesDir,"phase33-service-progress.txt").writeText(text)}
            catch(_:java.io.IOException){android.util.Log.w("TiniumNativeDj","NATIVE_SERVICE_PROGRESS_WRITE_FAILED")}
        }
        private fun stacks():String {
            val text=Thread.getAllStackTraces().entries.filter {
                it.key===Looper.getMainLooper().thread || it.key.name.startsWith("ExoPlayer") || it.key.name.startsWith("Tinium-AutoMix")
            }.sortedBy {if(it.key===Looper.getMainLooper().thread)0 else 1}.take(8).joinToString("\n") {entry ->
                "thread=${entry.key.name} state=${entry.key.state}\n"+entry.value.take(18).joinToString("\n") {"  at $it"}
            }
            try {File(context.filesDir,"phase33-service-threads.txt").writeText("test=$testMethod phase=$phase\n$text")}
            catch(_:java.io.IOException){android.util.Log.w("TiniumNativeDj","NATIVE_SERVICE_STACK_WRITE_FAILED")}
            return text
        }
        fun captureFailure(error:Throwable) {
            val text="NATIVE_SERVICE_FAILURE test=$testMethod phase=$phase runStartedMs=$runStartedMs type=${error.javaClass.simpleName}\n${stacks()}"
            try {File(context.filesDir,"phase33-service-failure.txt").writeText(text)}
            catch(_:java.io.IOException){android.util.Log.w("TiniumNativeDj","NATIVE_SERVICE_FAILURE_WRITE_FAILED")}
        }
        private fun <T> preferences(label:String,block:suspend ()->T):T {
            stage(label)
            return try {runBlocking {withTimeout(10_000){block()}}}
            catch(error:TimeoutCancellationException){throw AssertionError("NATIVE_SERVICE_ANDROID PREFERENCE_TIMEOUT test=$testMethod phase=$phase\n${stacks()}",error)}
        }
        fun main(block:()->Unit) {
            val label=phase
            BoundedTestCall.run("$testMethod/$label",7000,{handler.post(it);Unit},{handler.removeCallbacks(it)},::stacks,block)
        }
        fun disableAutoMix()=preferences("disable AutoMix"){context.dataStore.edit {it[AutoMixEnabledKey]=false}}
        fun mode(value:AutoMixEngineMode)=preferences("select ${value.name}"){context.dataStore.edit {it[AutoMixEngineModeKey]=value.name};selectedMode=value}
        fun play() {
            main {service.playerVolume.value=.12f;service.player.setMediaItems(items);service.player.prepare()}
            await("primary ready",10_000){service.player.playbackState==Player.STATE_READY}
            main {service.player.play()}
        }
        fun mixing()=await("native mixing",20_000){service.nativeServiceInspection().execution?.status?.stage==NativeDjExecutionStage.MIXING}
        fun completed()=service.autoMixRecorder.snapshot().attempts.count {a->a.records.any {it.native?.execution?.status?.stage==NativeDjExecutionStage.COMPLETED}}
        fun report()=AutoMixRecorder.render(service.autoMixRecorder.snapshot(),MixReportHeader(com.music.vivi.BuildConfig.VERSION_NAME,com.music.vivi.BuildConfig.VERSION_CODE.toLong(),
            android.os.Build.VERSION.RELEASE,android.os.Build.MODEL,true,AutoMixPerformance.PERFORMANCE,engineMode=selectedMode))
        fun focus(value:Int)=main {MusicService::class.java.getDeclaredMethod("handleAudioFocusChange",Int::class.javaPrimitiveType).apply {isAccessible=true}.invoke(service,value)}
        fun await(label:String,timeoutMs:Long,condition:()->Boolean) {
            stage("await $label")
            val finished=CountDownLatch(1);val error=AtomicReference<Throwable?>()
            val deadline=SystemClock.uptimeMillis()+timeoutMs
            var nextProgress=SystemClock.uptimeMillis()+5000
            val check=object:Runnable {override fun run(){try {
                if(condition()){finished.countDown();return}
                if(SystemClock.uptimeMillis()>=nextProgress) {
                    val s=service.nativeServiceInspection()
                    android.util.Log.i("TiniumNativeDj","NATIVE_SERVICE_PROGRESS test=$testMethod phase=$phase stage=${s.execution?.status} outputs=${s.outputs} positionMs=${service.player.currentPosition}")
                    nextProgress=SystemClock.uptimeMillis()+5000
                }
                if(SystemClock.uptimeMillis()>=deadline)throw AssertionError("NATIVE_SERVICE_ANDROID $label\n"+report())
                handler.postDelayed(this,10)
            }catch(t:Throwable){error.set(t);finished.countDown()}}}
            handler.post(check)
            try {if(!finished.await(timeoutMs+3000,TimeUnit.MILLISECONDS))throw AssertionError("NATIVE_SERVICE_ANDROID MAIN_LOOPER_TIMEOUT test=$testMethod phase=$phase\n${stacks()}")
                error.get()?.let {throw it}
                stage("completed $label")}
            finally {handler.removeCallbacks(check)}
        }
        override fun close() {
            var failure:Throwable?=null
            fun cleanup(label:String,action:()->Unit) {
                stage(label)
                try {action()}catch(error:Throwable) {if(failure==null)failure=error else failure!!.addSuppressed(error)}
            }
            if(::service.isInitialized) {
                cleanup("save diagnostic report") {
                    val text=report()
                    File(context.filesDir,"phase33-service-last.txt").writeText(text)
                    android.util.Log.i("TiniumNativeDj","NATIVE_SERVICE_ANDROID\n"+text)
                }
                cleanup("disable experiment") {preferences("disable experiment preferences"){context.dataStore.edit {it[AutoMixEnabledKey]=false}}}
                cleanup("stop players") {main {service.player.pause();service.player.stop()}}
            }
            if(bound)cleanup("unbind service") {main {
                context.unbindService(connection);context.stopService(Intent(context,MusicService::class.java));bound=false
            }}
            // A service can keep scheduling work; global queue idleness is not a lifecycle signal.
            // This bounded barrier drains callbacks already posted by the explicit stop/unbind.
            cleanup("cleanup main-thread barrier") {main {}}
            cleanup("restore preferences") {preferences("restore preference snapshot"){context.dataStore.updateData {previous}}}
            files.forEach {it.delete()}
            stage(if(failure==null)"cleanup finished" else "cleanup failed")
            failure?.let {throw it}
        }
    }
}
