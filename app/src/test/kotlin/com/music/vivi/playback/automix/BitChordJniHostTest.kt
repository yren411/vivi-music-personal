// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Genuine host JNI, explicitly reported as skipped when the optional host library is absent. */
class BitChordJniHostTest {
    private lateinit var loader: () -> Unit
    private lateinit var bridge: NativeDspBridge
    @Before fun library() {
        val path=System.getProperty("tinium.hostNativeLibrary")
        assumeTrue("Host JNI not configured: use -PautomixNativeHostLibrary=<absolute shared library>",!path.isNullOrBlank())
        loader=BitChordNativeBindings::loadPackagedLibrary
        bridge=JniBitChordBridge(loader)
        NativeJniChecks.loading(bridge)
    }
    @Test fun libraryLoads()=NativeJniChecks.loading(bridge)
    @Test fun sevenTempos()=NativeJniChecks.tempos(bridge)
    @Test fun absoluteTimestamps()=NativeJniChecks.timestamps(bridge)
    @Test fun silenceIsNotReliableRhythm()=NativeJniChecks.silence(bridge)
    @Test fun rawJniRejectsMalformedInput()=NativeJniChecks.rawInputs()
    @Test fun cancellationInsideDsp()=NativeJniChecks.cancelDuringDsp(loader)
    @Test fun deadlineInsideDsp()=NativeJniChecks.deadlineDuringDsp()
    @Test fun fourWorkers()=NativeJniChecks.concurrent(bridge)
    @Test fun cancellationDoesNotAffectOtherJob()=NativeJniChecks.cancellationIsolation(loader)
    @Test fun repeatedCalls()=NativeJniChecks.repeated(bridge)
}
