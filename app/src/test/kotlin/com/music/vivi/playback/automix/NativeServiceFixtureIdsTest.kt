// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class NativeServiceFixtureIdsTest {
    @Test fun runsHaveSeparateRecordingAndCacheKeysWhileEachQueueSlotRemainsStable() {
        val a=NativeServiceFixtureIds("run-a");val b=NativeServiceFixtureIds("run-b")
        assertEquals(a[0],a[0]);assertEquals(3,(0..2).map{a[it]}.toSet().size)
        assertTrue((0..2).map{a[it]}.toSet().intersect((0..2).map{b[it]}.toSet()).isEmpty())
        assertNotEquals("phase33-generated-0",a[0])
    }
    @Test fun cachedWavBytesCannotMasqueradeAsCodecBytesInAnotherRun() {
        // CacheDataSource uses the custom key. A different file URI alone cannot invalidate it.
        val wav=NativeServiceFixtureIds("wav-run");val codec=NativeServiceFixtureIds("codec-run")
        val cache=mutableMapOf(wav[0] to "40s/22050/mono/WAV")
        assertNull(cache[codec[0]])
        cache[codec[0]]="30s/48000/stereo/AAC"
        assertEquals("40s/22050/mono/WAV",cache[wav[0]])
        assertEquals("30s/48000/stereo/AAC",cache[codec[0]])
    }
    @Test fun cleanupRemovesOnlyTheCurrentRunsResources() {
        val ids=NativeServiceFixtureIds("owned");val unrelated=NativeServiceFixtureIds("other")
        val cache=((0..2).map{ids[it]}+(0..2).map{unrelated[it]}+"authorized-song").associateWith{Any()}.toMutableMap()
        val removed=mutableListOf<String>()
        ids.removeCachedResources {removed+=it;cache.remove(it)}
        assertEquals((0..2).map{ids[it]},removed)
        assertEquals(((0..2).map{unrelated[it]}+"authorized-song").toSet(),cache.keys)
    }
}
