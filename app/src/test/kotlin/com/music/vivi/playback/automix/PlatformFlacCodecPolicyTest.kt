package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class PlatformFlacCodecPolicyTest {
    @Test fun vendorRegistryOrderCannotChooseTheKnownDifferentVendorPath() {
        assertEquals("c2.android.flac.decoder",PlatformFlacCodecPolicy.select(listOf("c2.sec.flac.decoder","OMX.google.flac.decoder","c2.android.flac.decoder")))
    }
    @Test fun olderPlatformSoftwareCodecIsSupported() {
        assertEquals("OMX.google.flac.decoder",PlatformFlacCodecPolicy.select(listOf("c2.sec.flac.decoder","OMX.google.flac.decoder")))
    }
    @Test fun missingPlatformCodecIsUnavailableRatherThanSilentlyUsingVendorOrAnotherFormat() {
        for(names in listOf(emptyList(),listOf("c2.sec.flac.decoder"),listOf("c2.android.aac.decoder")))
            assertNull(PlatformFlacCodecPolicy.select(names))
    }
}
