// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Explicit bounded observation, separate from the 69 acceptance tests. A passed
 * diagnostic can report FALLBACK; it never means synchronization succeeded. Real
 * MusicService/JNI/planner/executor, no extra calibration player or adjusted limits. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeServiceStartupDiagnosticInstrumentedTest {
    @get:Rule val timeout:Timeout=Timeout.seconds(90)
    @Test fun recordActualServiceStartupAndItsHonestOutcome() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".decodercheck"))
        NativeAutoMixServiceInstrumentedTest.Harness("phase332-startup-diagnostic",true,false,false,false).use {h ->
            var observed:NativeDjExecutionReport?=null
            var nextObservationUs=0L
            try {
                h.play()
                h.await("bounded startup outcome",25_000) {
                    val nowUs=System.nanoTime()/1000
                    if(nowUs<nextObservationUs)return@await false
                    nextObservationUs=nowUs+100_000 // diagnostic timestamp reads <=10Hz, <=25s
                    val report=h.service.nativeServiceInspection().execution ?: h.history().attempts.asReversed()
                        .flatMap {it.records.asReversed()}.firstNotNullOfOrNull {it.native?.execution}
                    report?.also {observed=it}?.let {r ->
                        r.status.stage in setOf(NativeDjExecutionStage.FALLBACK,NativeDjExecutionStage.FAILED,NativeDjExecutionStage.CANCELED) ||
                            r.status.stage==NativeDjExecutionStage.MIXING && r.measurements.concurrentProgressUs>=2_000_000
                    }==true
                }
                h.main {
                    val r=requireNotNull(observed)
                    assertEquals(0,r.measurements.promotions)
                    assertTrue(h.service.player.isPlaying)
                    if(r.status.stage==NativeDjExecutionStage.MIXING)assertTrue(r.measurements.maximumObservedDriftUs<=120_000)
                    if(r.status.reason==NativeDjExecutionReason.EXCESSIVE_DRIFT)assertTrue(r.measurements.maximumObservedDriftUs>120_000)
                }
            } finally {
                val header="NATIVE_SERVICE_STARTUP_DIAGNOSTIC observationOnly=true acousticSyncUnknown=true outcome=${observed?.status}\n"
                File(context.filesDir,"phase332-service-startup.txt").writeText(header+h.report().takeLast(96_000-header.length))
            }
        }
    }
}
