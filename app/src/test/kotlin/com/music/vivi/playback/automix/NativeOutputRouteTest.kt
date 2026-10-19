package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class NativeOutputRouteTest {
    private val speaker=NativeRouteEndpoint(1,10,NativeRouteCategory.SPEAKER)
    private val headphones=NativeRouteEndpoint(1,20,NativeRouteCategory.BLUETOOTH)
    private fun state(a:NativeRouteEndpoint?=speaker,b:NativeRouteEndpoint?=null,required:Boolean=false)=NativeRouteSnapshot(a,b,required)
    @Test fun identicalNotificationsIncludingCaptureRetainTheSamePlan() {
        val tracker=NativeOutputRouteTracker();assertFalse(tracker.observe(state(),NativeRouteNotification.POLL).material)
        repeat(100){for(event in NativeRouteNotification.entries)assertFalse(tracker.observe(state(),event,captureOnly=true).material)}
        assertTrue(tracker.ready)
    }
    @Test fun actualDeviceReplacementCancelsEvenWithinTheSameCategory() {
        for(next in listOf(headphones,speaker.copy(deviceId=11))) {
            val tracker=NativeOutputRouteTracker();tracker.observe(state(),NativeRouteNotification.POLL)
            val d=tracker.observe(state(next),NativeRouteNotification.TRACK_ROUTING)
            assertTrue(d.material);assertTrue(d.outgoingChanged)
            assertFalse(tracker.observe(state(next),NativeRouteNotification.DEVICES_ADDED).material)
        }
    }
    @Test fun standbyCreationAndSessionEventsAreNotPhysicalRouteChanges() {
        val tracker=NativeOutputRouteTracker();tracker.observe(state(),NativeRouteNotification.POLL)
        assertFalse(tracker.observe(state(b=speaker.copy(instance=2)),NativeRouteNotification.TRACK_CREATED).material)
        assertFalse(tracker.observe(state(b=speaker.copy(instance=2)),NativeRouteNotification.SESSION).material)
        assertFalse(tracker.observe(state(b=speaker.copy(instance=2),required=true),NativeRouteNotification.POLL).material)
        assertTrue(tracker.ready)
    }
    @Test fun activeIncomingRouteMismatchAndOutputReplacementRequireCancellation() {
        val tracker=NativeOutputRouteTracker();tracker.observe(state(),NativeRouteNotification.POLL)
        assertTrue(tracker.observe(state(b=headphones,required=true),NativeRouteNotification.TRACK_ROUTING).material)
        assertFalse(tracker.ready)
        val t=NativeOutputRouteTracker();t.observe(state(),NativeRouteNotification.POLL)
        assertTrue(t.observe(state(speaker.copy(instance=2)),NativeRouteNotification.TRACK_CREATED).material)
    }
    @Test fun unknownOutputCancelsOnceAndBlocksReanalysisUntilKnown() {
        val tracker=NativeOutputRouteTracker();tracker.observe(state(),NativeRouteNotification.POLL)
        assertTrue(tracker.observe(state(null),NativeRouteNotification.TRACK_RELEASED).material)
        repeat(100){assertFalse(tracker.observe(state(null),NativeRouteNotification.DEVICES_ADDED).material);assertFalse(tracker.ready)}
        tracker.observe(state(headphones),NativeRouteNotification.TRACK_ROUTING);assertTrue(tracker.ready)
    }
    @Test fun captureNotificationDoesNotSuppressARealConcurrentRouteChange() {
        val tracker=NativeOutputRouteTracker();tracker.observe(state(),NativeRouteNotification.POLL)
        assertTrue(tracker.observe(state(headphones),NativeRouteNotification.DEVICES_ADDED,captureOnly=true).material)
    }
    @Test fun promotionResetsIdentityWithoutMistakingItForExternalRouting() {
        val tracker=NativeOutputRouteTracker();tracker.observe(state(),NativeRouteNotification.POLL);tracker.reset()
        assertFalse(tracker.observe(state(speaker.copy(instance=2)),NativeRouteNotification.POLL).material)
        assertTrue(tracker.ready)
    }
    @Test fun deviceIdentifiersNeverAppearInExportedDecision() {
        val tracker=NativeOutputRouteTracker();tracker.observe(state(),NativeRouteNotification.POLL)
        val report=tracker.observe(state(headphones.copy(deviceId=973897)),NativeRouteNotification.FOCUS).toString()
        assertFalse(report.contains("973897"));assertTrue(report.contains("focusChanged=true"))
        assertEquals("NativeRouteEndpoint(redacted)",headphones.toString())
    }
    @Test fun pausedTrackMayReportUnknownWithoutPretendingItsLastRouteIsCurrent() {
        val tracker=NativeOutputRouteTracker();tracker.observe(state(),NativeRouteNotification.POLL)
        val paused=state(null).copy(paused=true,outgoingInstance=speaker.instance)
        for(event in listOf(NativeRouteNotification.POLL,NativeRouteNotification.FOCUS,NativeRouteNotification.SESSION,NativeRouteNotification.TRACK_ROUTING)) {
            val d=tracker.observe(paused,event)
            assertFalse(d.material);assertFalse(d.known);assertTrue(d.observationDeferred);assertFalse(tracker.ready)
        }
        assertFalse(tracker.observe(state(),NativeRouteNotification.POLL).material);assertTrue(tracker.ready)
    }
    @Test fun pauseDoesNotSuppressRealDeviceEventsOrPrimaryTrackReplacement() {
        for(replaced in listOf(false,true)) {
            val tracker=NativeOutputRouteTracker();tracker.observe(state(),NativeRouteNotification.POLL)
            val paused=state(null).copy(paused=true,outgoingInstance=if(replaced)2 else 1)
            val d=tracker.observe(paused,if(replaced)NativeRouteNotification.TRACK_CREATED else NativeRouteNotification.DEVICES_REMOVED)
            assertTrue(d.material);assertFalse(d.observationDeferred);assertFalse(tracker.ready)
        }
    }
}
