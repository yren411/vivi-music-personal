// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativePlaybackSourcePinTest {
    @Test fun pinsOnlyTheIncomingRenditionAndResumesNormalResolutionAfterPromotion() {
        val source=Any();val pin=NativePlaybackSourcePin("incoming",source)
        assertSame(source,pin.sourceFor("incoming"));assertNull(pin.sourceFor("future"))
        pin.release()
        assertNull(pin.sourceFor("incoming"));assertNull(pin.sourceFor("future"))
        pin.release();assertNull(pin.sourceFor("incoming"))
    }
    @Test fun releaseIsVisibleToALoaderWithoutSharingPlayerStateOrPrivateStrings() {
        val pin=NativePlaybackSourcePin("private-id","https://private?token=secret")
        val released=CountDownLatch(1);val observed=CountDownLatch(1)
        val loader=Thread {if(released.await(3,TimeUnit.SECONDS) && pin.sourceFor("private-id")==null)observed.countDown()}
        loader.start()
        try {pin.release();released.countDown();assertTrue(observed.await(3,TimeUnit.SECONDS))}
        finally {released.countDown();loader.join(3000)}
        assertEquals("NativePlaybackSourcePin(redacted)",pin.toString())
    }
}
