// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix
import org.junit.Assert.*
import org.junit.Test

class NativeRenditionRegistryTest {
    @Test fun modeOrQualityEpochRejectsLateResolverPublication() {
        val sources=NativeRenditionRegistry<String>();val old=sources.epoch
        assertTrue(sources.remember("song","old-rendition",old));sources.clear()
        assertFalse(sources.remember("song","late-old-url",old));assertNull(sources["song"])
        assertTrue(sources.remember("song","new-rendition",sources.epoch));assertEquals("new-rendition",sources["song"])
    }
    @Test fun evictionIsBoundedAndRetainsRecentlyUsedCurrentSources() {
        val sources=NativeRenditionRegistry<Int>(2);val epoch=sources.epoch
        sources.remember("current",1,epoch);sources.remember("older",2,epoch);assertEquals(1,sources["current"])
        sources.remember("next",3,epoch);assertNull(sources["older"]);assertEquals(1,sources["current"]);assertEquals(3,sources["next"])
    }
    @Test fun expiredRemovalRejectsOldResolutionWithoutDiscardingOtherValidHandles() {
        val sources=NativeRenditionRegistry<String>();val old=sources.epoch
        sources.remember("current","https://private?secret=token",old);sources.remember("next","expired",old)
        sources.remove("next");assertFalse(sources.remember("next","stale",old));assertNull(sources["next"])
        assertNotNull(sources["current"]);assertNull(sources[null]);assertEquals("NativeRenditionRegistry(redacted)",sources.toString())
    }
}
