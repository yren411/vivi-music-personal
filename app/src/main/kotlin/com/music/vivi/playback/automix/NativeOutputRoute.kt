package com.music.vivi.playback.automix

enum class NativeRouteCategory { SPEAKER, WIRED, BLUETOOTH, USB, HDMI, OTHER, UNKNOWN }
enum class NativeRouteNotification { DEVICES_ADDED, DEVICES_REMOVED, TRACK_ROUTING, TRACK_CREATED, TRACK_RELEASED, SESSION, FOCUS, POLL, PREFERRED_OUTPUT }
/** Ephemeral equality key only; never exported or stringified. */
data class NativeRouteEndpoint(val instance:Long,val deviceId:Int,val category:NativeRouteCategory) {
    override fun toString()="NativeRouteEndpoint(redacted)"
}
data class NativeRouteSnapshot(val outgoing:NativeRouteEndpoint?,val incoming:NativeRouteEndpoint?,val incomingRequired:Boolean,
    val paused:Boolean=false,val outgoingInstance:Long?=outgoing?.instance)
data class NativeRouteDecision(val source:NativeRouteNotification,val previous:NativeRouteCategory,val current:NativeRouteCategory,
    val outgoingChanged:Boolean,val incomingChanged:Boolean,val material:Boolean,val known:Boolean,
    val captureOnly:Boolean=false,val sessionChanged:Boolean=false,val focusChanged:Boolean=false,
    val outgoingTrackReplaced:Boolean=false,val incomingTrackReplaced:Boolean=false,val observationDeferred:Boolean=false)

/** Uses actual per-track routing, not the list of connected devices. Unknown state cancels
 * once and blocks new native attempts until an actual outgoing route becomes observable. */
internal class NativeOutputRouteTracker {
    private var previous:NativeRouteSnapshot?=null
    private var observationKnown=false
    val ready get()=observationKnown
    fun reset(){previous=null;observationKnown=false}
    fun observe(snapshot:NativeRouteSnapshot,source:NativeRouteNotification,captureOnly:Boolean=false):NativeRouteDecision {
        val old=previous
        val known=snapshot.outgoing!=null && (!snapshot.incomingRequired || snapshot.incoming!=null && snapshot.incoming.deviceId==snapshot.outgoing.deviceId)
        observationKnown=known
        // AudioTrack.getRoutedDevice may be null while paused. This is not a measured
        // physical route replacement. Retain the comparison baseline, report UNKNOWN,
        // and block NEW attempts until a fresh route is observed after resume. Real
        // output-device events or track replacement still invalidate conservatively.
        val deferred=snapshot.paused && snapshot.outgoing==null && old?.outgoing!=null &&
            snapshot.outgoingInstance==old.outgoingInstance &&
            (captureOnly || source !in setOf(NativeRouteNotification.DEVICES_ADDED,NativeRouteNotification.DEVICES_REMOVED,NativeRouteNotification.PREFERRED_OUTPUT))
        if(deferred)return NativeRouteDecision(source,old!!.outgoing!!.category,NativeRouteCategory.UNKNOWN,
            false,false,false,false,captureOnly,source==NativeRouteNotification.SESSION,source==NativeRouteNotification.FOCUS,
            observationDeferred=true)
        val outgoingChanged=old!=null && (old.outgoing!=snapshot.outgoing || old.outgoingInstance!=snapshot.outgoingInstance)
        val incomingChanged=old!=null && old.incomingRequired && snapshot.incomingRequired && old.incoming!=snapshot.incoming
        val mismatch=snapshot.incomingRequired && snapshot.incoming?.deviceId!=snapshot.outgoing?.deviceId
        val equivalent=old!=null && old.outgoing==snapshot.outgoing && old.outgoingInstance==snapshot.outgoingInstance && old.incomingRequired==snapshot.incomingRequired &&
            (!snapshot.incomingRequired || old.incoming==snapshot.incoming)
        val material=old!=null && !equivalent && (outgoingChanged || incomingChanged || !known || mismatch)
        previous=snapshot
        return NativeRouteDecision(source,old?.outgoing?.category ?: NativeRouteCategory.UNKNOWN,
            snapshot.outgoing?.category ?: NativeRouteCategory.UNKNOWN,outgoingChanged,incomingChanged,material,known,captureOnly,
            source==NativeRouteNotification.SESSION,source==NativeRouteNotification.FOCUS,
            old?.outgoingInstance!=null && snapshot.outgoingInstance!=null && old.outgoingInstance!=snapshot.outgoingInstance,
            old?.incoming!=null && snapshot.incoming!=null && old.incoming.instance!=snapshot.incoming.instance)
    }
}
