// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional, explicitly selected owner evaluation. Not part of the 14-test generated suite.
 * Uses only two fixed private files, never uploads/exports media or filenames. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeDjLocalListeningInstrumentedTest {
    @Test fun explicitlyRequestedPrivatePair() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in local listening requires phase32Listen=true",args.getString("phase32Listen")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val a=File(context.filesDir,"phase32-out.audio");val b=File(context.filesDir,"phase32-in.audio")
        require(a.isFile && b.isFile) {"Install the two permitted local media files in the isolated test package first"}
        val startMs=args.getString("phase32OutgoingStartMs")?.toLongOrNull() ?: 0L
        require(startMs in 0..86_400_000)
        // Playback volume is conservative; system volume remains entirely under the owner's control.
        NativeDjAndroidHarness(localOutgoing=a,localIncoming=b,outgoingStartUs=startMs*1000).use {h ->
            h.start();h.until("local evaluation completion",60_000){h.executor.terminal}
            h.main {assertEquals(h.failure(),NativeDjExecutionStage.COMPLETED,h.executor.status.stage)}
        }
    }
}
