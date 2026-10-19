// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Test-only export. The service recorder pump may not yet contain the executor
 * snapshot that ended observation. Preserve that snapshot first, independently
 * of history, without waiting for another callback or changing playback. */
internal object NativeStartupDiagnosticText {
    const val MAX_CHARACTERS = 96_000

    fun render(observed: NativeDjExecutionReport?, history: String): String {
        val header = "NATIVE_SERVICE_STARTUP_DIAGNOSTIC diagnosticSchema=2 observationOnly=true acousticSyncUnknown=true outcome=${observed?.status}\n"
        // Fixed-size numeric/enum DTOs, no source identities, URLs or platform error strings.
        // Retain the final events while bounding this test file independently of the recorder.
        val snapshot = observed?.copy(measurements = observed.measurements.copy(
            events = observed.measurements.events.takeLast(16)))
        val latest = "LATEST_EXECUTOR_SNAPSHOT historyMayLagLatest=true\n$snapshot\n"
        val prefix = (header + latest).take(MAX_CHARACTERS)
        return prefix + history.takeLast(MAX_CHARACTERS - prefix.length)
    }
}
