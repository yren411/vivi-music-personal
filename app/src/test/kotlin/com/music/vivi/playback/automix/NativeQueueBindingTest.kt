// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix
import org.junit.Assert.*
import org.junit.Test

class NativeQueueBindingTest {
    private val source=NativeQueuedSource(AnalysisSourceIdentity("same-recording","rendition"),"https://private?token=secret","same-recording","audio/flac")
    private val pair=NativeQueueBinding(source,source.copy(uri="file:///private/original"),0,false)
    @Test fun replacingSourceConfigurationInvalidatesAPairEvenWithUnchangedRecordingIds() {
        assertNotEquals(pair,pair.copy(incoming=pair.incoming.copy(uri="file:///private/replacement")))
        assertNotEquals(pair,pair.copy(incoming=pair.incoming.copy(cacheKey="different-cache")))
        assertNotEquals(pair,pair.copy(incoming=pair.incoming.copy(mimeType="audio/mp4")))
        assertNotEquals(pair,pair.copy(repeatMode=1));assertNotEquals(pair,pair.copy(shuffle=true))
    }
    @Test fun unchangedActivePairIsStableAndDoesNotExposePrivateSourceConfiguration() {
        assertEquals(pair,pair.copy());assertEquals("NativeQueueBinding(redacted)",pair.toString())
        assertEquals("NativeQueuedSource(redacted)",source.toString())
    }
}
