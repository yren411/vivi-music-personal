// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class NativeStartupDiagnosticTextTest {
    @Test fun terminalSnapshotSurvivesExportBeforeServiceRecorderPump() {
        val h = VirtualNativeDjHost(mathematicalExecutionPlan())
        val outputs = NativeDjOutputTiming(NativeDjOutputTimestamp(1234, 5_000_000, 22050),
            NativeDjOutputTimestamp(567, 5_000_001, 22050), 5001)
        val host = object : NativeDjHost by h {
            override fun outputTiming() = outputs
        }
        val executor = NativeDjTransitionExecutor(host, { h.now })
        executor.start(h.plan)
        h.toStart(executor)
        val staleHistory = "Service history stage=MIXING maxDriftUs=0 lastClock=null incoming=null"
        // Both decks advance, but genuinely diverge by 130 ms: guard must still reject.
        h.now += 200_000; h.a.position += 200_000; h.b.position += 70_000
        executor.tick()
        val observed = executor.report()
        assertEquals(NativeDjExecutionReason.EXCESSIVE_DRIFT, observed.status.reason)
        assertEquals(130_000L, observed.measurements.maximumObservedDriftUs)
        assertTrue(h.b.released)
        val text = NativeStartupDiagnosticText.render(observed, staleHistory)
        assertTrue(text.contains("diagnosticSchema=2"))
        assertTrue(text.contains("maximumObservedDriftUs=130000"))
        assertTrue(text.contains("rawOffsetUs=130000"))
        assertTrue(text.contains("incoming=NativeDjOutputTimestamp(framePosition=567"))
        assertTrue(text.contains("commandMonotonicUs="))
        assertTrue(text.indexOf("LATEST_EXECUTOR_SNAPSHOT") < text.indexOf(staleHistory))
        assertTrue(text.endsWith(staleHistory))
        assertEquals(0, h.promotions)
        assertTrue(h.a.playing)
    }

    @Test fun oversizedHistoryCannotEvictSnapshotAndMissingOutcomeStaysExplicit() {
        val h = VirtualNativeDjHost(mathematicalExecutionPlan())
        val executor = NativeDjTransitionExecutor(h, { h.now })
        executor.start(h.plan); h.toStart(executor)
        executor.cancel(NativeDjExecutionReason.USER_SKIP)
        val text = NativeStartupDiagnosticText.render(executor.report(), "x".repeat(120_000))
        assertEquals(NativeStartupDiagnosticText.MAX_CHARACTERS, text.length)
        assertTrue(text.contains("stage=CANCELED, reason=USER_SKIP"))
        assertTrue(text.contains("LATEST_EXECUTOR_SNAPSHOT"))
        val missing = NativeStartupDiagnosticText.render(null, "history")
        assertTrue(missing.contains("outcome=null"))
        assertTrue(missing.contains("historyMayLagLatest=true\nnull\n"))
    }
}
