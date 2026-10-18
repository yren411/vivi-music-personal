package com.music.vivi.playback.automix

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.lang.ref.WeakReference

/** Passive wrapper around Media3's unchanged DEFAULT provider. Never changes audio data,
 * attributes, preferred devices, focus, play state, gain or session IDs. All state is main-looper owned.
 * One weak AudioTrack reference/listener per live processor; replacement/removal detaches the old listener.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AndroidNativeOutputRoutes(private val handler:Handler,private val live:(AutoMixAudioProcessor)->Boolean,
    private val changed:(NativeRouteNotification)->Unit):AutoCloseable {
    private data class Entry(val token:Long,val track:WeakReference<AudioTrack>,val listener:AudioRouting.OnRoutingChangedListener,val rate:Int)
    private val entries=mutableMapOf<AutoMixAudioProcessor,Entry>()
    private var sequence=0L
    private var closed=false
    fun provider(owner:AutoMixAudioProcessor)=DefaultAudioSink.AudioTrackProvider {config,attributes,session ->
        DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config,attributes,session).also {track ->
            handler.post {
                if(!closed && live(owner)) {
                    remove(owner)
                    val listener=AudioRouting.OnRoutingChangedListener {routing ->
                        if(entries[owner]?.track?.get()===routing)changed(NativeRouteNotification.TRACK_ROUTING)
                    }
                    entries[owner]=Entry(++sequence,WeakReference(track),listener,config.sampleRate)
                    try {
                        track.addOnRoutingChangedListener(listener,handler)
                        changed(NativeRouteNotification.TRACK_CREATED)
                    } catch(_:IllegalStateException) {
                        // Concurrent platform release: UNKNOWN route, not a service crash.
                        entries.remove(owner);changed(NativeRouteNotification.TRACK_RELEASED)
                    }
                }
            }
        }
    }
    fun endpoint(owner:AutoMixAudioProcessor?):NativeRouteEndpoint? {
        val entry=entries[owner] ?: return null
        val track=entry.track.get() ?: return null
        if(track.state!=AudioTrack.STATE_INITIALIZED)return null
        val device=try {track.routedDevice} catch(_:IllegalStateException){null} ?: return null
        return NativeRouteEndpoint(entry.token,device.id,when(device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> NativeRouteCategory.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_LINE_ANALOG -> NativeRouteCategory.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,AudioDeviceInfo.TYPE_BLE_BROADCAST -> NativeRouteCategory.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_ACCESSORY,AudioDeviceInfo.TYPE_USB_HEADSET -> NativeRouteCategory.USB
            AudioDeviceInfo.TYPE_HDMI,AudioDeviceInfo.TYPE_HDMI_ARC,AudioDeviceInfo.TYPE_HDMI_EARC -> NativeRouteCategory.HDMI
            else -> NativeRouteCategory.OTHER
        })
    }
    fun remove(owner:AutoMixAudioProcessor) {
        entries.remove(owner)?.let {e ->e.track.get()?.removeOnRoutingChangedListener(e.listener)}
    }
    fun instance(owner:AutoMixAudioProcessor?):Long? = entries[owner]?.takeIf {it.track.get()?.state==AudioTrack.STATE_INITIALIZED}?.token
    fun timestamp(owner:AutoMixAudioProcessor?):NativeDjOutputTimestamp? {
        val entry=entries[owner] ?: return null
        val track=entry.track.get() ?: return null
        if(track.state!=AudioTrack.STATE_INITIALIZED)return null
        val stamp=android.media.AudioTimestamp()
        // The playback thread can release a track between state inspection and the query.
        val available=try {track.getTimestamp(stamp)} catch(_:IllegalStateException){false}
        return if(available && stamp.framePosition>=0 && stamp.nanoTime>=0)
            NativeDjOutputTimestamp(stamp.framePosition,stamp.nanoTime,entry.rate) else null
    }
    override fun close(){closed=true;entries.keys.toList().forEach(::remove)}
}
