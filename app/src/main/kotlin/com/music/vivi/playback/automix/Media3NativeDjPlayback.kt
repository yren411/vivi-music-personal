// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.music.vivi.playback.effects.AudioEffectSessions
import com.music.vivi.playback.effects.AudioOutputLifecycle
import java.util.WeakHashMap

/** Caller-owned real Media3 players, sources and queue. This adapter does not resolve URLs, request
 * audio focus, construct a service, or install another audio sink/effects chain. Supply the existing
 * configured player factory/release hooks. Isolated instrumentation uses generated local media.
 * Caller MUST exclude legacy controllers and forward focus, noisy/route and user seek/skip events. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3NativeDjPlayback(
    outgoingPlayer: ExoPlayer,
    private val outgoingTrack: PlanningTrack,
    private val incomingTrack: PlanningTrack,
    private val incomingItem: MediaItem,
    private val currentQueue: () -> NativeDjQueue,
    private val createIncoming: () -> ExoPlayer,
    private val commitPromotion: (NativeDjQueue, ExoPlayer) -> Boolean,
    private val releasePlayer: (ExoPlayer) -> Unit = {it.release()},
    private val userVolume: () -> Float,
    private val focusGain: () -> Float = {1f},
    /** true only after the caller excluded its legacy controller/fade timer. Never infer this. */
    private val exclusiveControlAvailable: () -> Boolean,
    private val outputs: NativeDjOutputHooks = NativeDjOutputHooks.NONE,
    /** Caller records the real outgoing cold start before constructing/executing the attempt.
     * A prediction only; executor still checks incoming positions against the original plan. */
    private val startTiming: () -> NativeDjStartupObservation? = {null},
) : NativeDjHost, AutoCloseable {
    companion object {
        // Ownership across caller loopers is synchronized; player operations stay on their own looper.
        private val leases=WeakHashMap<ExoPlayer,Media3NativeDjPlayback>()
    }
    private val looper=outgoingPlayer.applicationLooper
    private val handler=Handler(looper)
    private var claimed=false
    private var originalPauseAtEnd=false
    private var incomingDeck:Deck?=null
    private var closed=false
    private var executor:NativeDjTransitionExecutor?=null
    private val outgoingDeck=Deck(outgoingPlayer,outgoingTrack)
    override val outgoing:NativeDjDeck get()=outgoingDeck
    private fun thread() {check(Looper.myLooper()==looper) {"Use the player application looper"}}
    override fun queue():NativeDjQueue {thread();return currentQueue()}
    override fun startupObservation():NativeDjStartupObservation? {thread();return startTiming()}
    override fun claim():Boolean {
        thread()
        if(closed || claimed || !exclusiveControlAvailable())return false
        val master=userVolume();val focus=focusGain()
        require(master.isFinite() && master in 0f..1f && focus.isFinite() && focus in 0f..1f)
        // Refuse a stale/competing gain baseline rather than jumping to unity on acquisition.
        if(kotlin.math.abs(outgoingDeck.player.volume-master*focus)>.0001f)return false
        require(outgoingDeck.player.mediaItemCount==1) {"Experimental session requires an isolated single-item outgoing deck"}
        synchronized(leases) {if(leases.containsKey(outgoingDeck.player))return false;leases[outgoingDeck.player]=this}
        originalPauseAtEnd=outgoingDeck.player.pauseAtEndOfMediaItems
        claimed=true
        outgoingDeck.player.setPauseAtEndOfMediaItems(true)
        outputs.attach(outgoingDeck.player,AudioEffectSessions.Role.PRIMARY)
        return true
    }
    override fun prepare(plan:NativeTransitionPlan):NativeDjDeck {
        thread();check(claimed && incomingDeck==null)
        require(incomingItem.mediaId==incomingTrack.source.recordingId)
        val player=createIncoming()
        require(player!==outgoingDeck.player)
        if(player.applicationLooper!=looper) {
            Handler(player.applicationLooper).post {player.release()}
            throw IllegalArgumentException("Incoming factory must use outgoing application looper")
        }
        synchronized(leases) {check(!leases.containsKey(player)) {"Incoming player is already owned"};leases[player]=this}
        val deck=Deck(player,incomingTrack);incomingDeck=deck
        try {
            player.volume=0f;player.playWhenReady=false
            player.setSeekParameters(SeekParameters.EXACT)
            player.repeatMode=Player.REPEAT_MODE_OFF;player.shuffleModeEnabled=false
            player.setPauseAtEndOfMediaItems(true)
            outputs.attach(player,AudioEffectSessions.Role.PREPARED)
            player.setMediaItem(incomingItem,plan.candidate.incomingCueUs/1000)
            player.prepare()
            return deck
        }catch(error:Exception){deck.release();throw error}
    }
    override fun promote(expected:NativeDjQueue,incoming:NativeDjDeck):Boolean {
        thread()
        val deck=incoming as? Media3NativeDjPlayback.Deck ?: return false
        if(!claimed || deck!==incomingDeck || currentQueue()!=expected)return false
        if(!commitPromotion(expected,deck.player))return false
        outputs.promote(deck.player)
        deck.player.setPauseAtEndOfMediaItems(false)
        return true
    }
    override fun restore() {
        thread()
        if(!claimed)return
        try {if(!outgoingDeck.released)outgoingDeck.player.setPauseAtEndOfMediaItems(originalPauseAtEnd)}
        finally {
            synchronized(leases) {
                if(leases[outgoingDeck.player]===this)leases.remove(outgoingDeck.player)
                incomingDeck?.player?.let {if(leases[it]===this)leases.remove(it)}
            }
            claimed=false
        }
    }
    /** One bounded Handler callback; no coroutine/thread per fade. Endpoints use current master. */
    fun execute(plan:NativeTransitionPlan, outgoingLevel:NativeDjLevel?=null,incomingLevel:NativeDjLevel?=null,
                config:NativeDjExecutionConfig=NativeDjExecutionConfig()):NativeDjTransitionExecutor {
        thread();check(!closed && executor==null)
        val result=NativeDjTransitionExecutor(this,config=config);executor=result
        result.start(plan,outgoingLevel,incomingLevel)
        if(!result.terminal)handler.post(tick)
        return result
    }
    private val tick=object:Runnable {
        override fun run() {
            val e=executor ?: return
            if(closed || e.terminal)return
            e.tick()
            if(!e.terminal)handler.postDelayed(this,if(e.status.stage==NativeDjExecutionStage.PAUSED)250 else 10)
        }
    }
    /** Caller forwards controls before changing players/queue. Generation is checked every tick. */
    fun pauseForUserOrFocus(reason:NativeDjExecutionReason?=null) {thread();executor?.pause(reason)}
    fun resumeAfterUserOrFocus() {thread();executor?.resume();handler.removeCallbacks(tick);if(executor?.terminal==false)handler.post(tick)}
    fun invalidate(reason:NativeDjExecutionReason) {thread();executor?.cancel(reason);handler.removeCallbacks(tick)}
    fun refreshUserVolume() {thread();if(!claimed || closed)return;outgoingDeck.refresh();incomingDeck?.refresh()}
    override fun close() {
        thread();if(closed)return
        executor?.close();handler.removeCallbacks(tick);closed=true
        // The surviving primary player remains caller-owned. Never stop it on successful handoff.
        restore()
    }
    private inner class Deck(val player:ExoPlayer,val track:PlanningTrack):NativeDjDeck {
        var released=false;private var relativeGain=1.0
        override fun snapshot():NativeDjDeckState {
            thread()
            val itemMatches=!released && player.currentMediaItem?.mediaId==track.source.recordingId
            return NativeDjDeckState(if(itemMatches)track.source else AnalysisSourceIdentity("invalid","invalid"),
                if(released || player.duration==C.TIME_UNSET)0 else player.duration*1000,
                if(released)0 else player.currentPosition*1000,
                !released && player.playbackState==Player.STATE_READY && player.playerError==null,
                !released && player.isPlaying,released || player.playbackState==Player.STATE_ENDED,
                released || player.playerError!=null,!released && player.isCurrentMediaItemSeekable,
                if(released)1.0 else if(player.playbackParameters.pitch==1f)player.playbackParameters.speed.toDouble() else Double.NaN)
        }
        override fun gain(value:Double) {thread();require(value.isFinite() && value in 0.0..1.0);relativeGain=value;refresh()}
        fun refresh() {
            if(released)return
            val master=userVolume();val focus=focusGain()
            require(master.isFinite() && master in 0f..1f && focus.isFinite() && focus in 0f..1f)
            player.volume=(relativeGain*master*focus).toFloat()
        }
        override fun play(){thread();check(!released);player.play()}
        override fun pause(){thread();if(!released)player.pause()}
        override fun release(){thread();if(released)return;released=true
            try {releasePlayer(player)} finally {
                synchronized(leases) {if(leases[player]===this@Media3NativeDjPlayback)leases.remove(player)}
                outputs.release(player)
            } }
    }
}

/** Existing MusicService already observes effects and must supply NONE to avoid duplicate chains.
 * Isolated callers can reuse the same public AudioEffectSessions/AudioOutputLifecycle machinery. */
interface NativeDjOutputHooks {
    fun attach(player:ExoPlayer,role:AudioEffectSessions.Role)
    fun promote(player:ExoPlayer)
    fun release(player:ExoPlayer)
    companion object {val NONE=object:NativeDjOutputHooks {
        override fun attach(player:ExoPlayer,role:AudioEffectSessions.Role){}
        override fun promote(player:ExoPlayer){}
        override fun release(player:ExoPlayer){}
    }}
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeDjAudioOutputs(context:Context,private val observe:(AudioEffectSessions.Event)->Unit = {}):NativeDjOutputHooks,AutoCloseable {
    private val context=context.applicationContext
    private val sessions=AudioEffectSessions {event ->
        observe(event)
        if(event.kind==AudioEffectSessions.Kind.OPEN || event.kind==AudioEffectSessions.Kind.CLOSE) {
            context.sendBroadcast(Intent(if(event.kind==AudioEffectSessions.Kind.OPEN)
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION,event.sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME,context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE,AudioEffect.CONTENT_TYPE_MUSIC))
        }
    }
    private data class Output(val id:String,val lifecycle:AudioOutputLifecycle,val listener:Listener)
    private val outputs=mutableMapOf<ExoPlayer,Output>()
    private var sequence=0
    private class Listener(val lifecycle:AudioOutputLifecycle,val player:ExoPlayer):Player.Listener,AnalyticsListener {
        override fun onAudioSessionIdChanged(audioSessionId:Int){lifecycle.sessionChanged(audioSessionId)}
        override fun onAudioTrackInitialized(eventTime:AnalyticsListener.EventTime,audioTrackConfig:AudioSink.AudioTrackConfig){lifecycle.trackInitialized(player.audioSessionId)}
        override fun onAudioTrackReleased(eventTime:AnalyticsListener.EventTime,audioTrackConfig:AudioSink.AudioTrackConfig){lifecycle.trackReleased()}
    }
    override fun attach(player:ExoPlayer,role:AudioEffectSessions.Role) {
        if(outputs.containsKey(player))return
        val id="experimental-deck-${++sequence}";sessions.register(id,role)
        val lifecycle=AudioOutputLifecycle(sessions,id);val listener=Listener(lifecycle,player)
        outputs[player]=Output(id,lifecycle,listener);player.addListener(listener);player.addAnalyticsListener(listener)
        lifecycle.sessionChanged(player.audioSessionId)
        // Attach before prepare for new outputs; if attaching an existing player observe its real
        // active AudioTrack via the caller's hooks instead. READY alone never fabricates an OPEN.
    }
    override fun promote(player:ExoPlayer){outputs[player]?.let {sessions.promote(it.id)}}
    override fun release(player:ExoPlayer){outputs.remove(player)?.let {player.removeListener(it.listener);player.removeAnalyticsListener(it.listener);it.lifecycle.dispose()}}
    override fun close(){outputs.keys.toList().forEach(::release)}
}
