// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import java.util.concurrent.CancellationException

/** JNI wire protocol 1. Arrays are bounded and copied; no source/URL/title or PCM in output.
 * Scalars: inputFrames/rate, DSP frames/rate, input/DSP seconds, BPM, interval, first beat,
 * raw confidence, RMS, peak, elapsed/resample/DSP microseconds, omitted events, validation us.
 * This transport is never a planning representation. */
internal class NativeDspPacket(
    val schema: Int,
    val status: String,
    val scalars: DoubleArray,
    val beatsSeconds: DoubleArray,
    val heuristicDownbeatsSeconds: DoubleArray,
)

/** Structured internal diagnostics, not calibrated probabilities or a public logging subsystem. */
data class NativeAnalysisDiagnostic(
    val status: String,
    val internalPath: String,
    val inputFrames: Int,
    val analysisFrames: Int,
    val analysisRateHz: Int,
    val rawBpm: Double,
    val rawConfidence: Double,
    val elapsedUs: Double,
    val resamplingUs: Double,
    val dspUs: Double,
    val validationUs: Double,
    val omittedBoundaryEvents: Int,
    /** Unscored hypotheses only, not measured probabilities of half/double time. */
    val unverifiedMetricalHypotheses: List<Double>,
)

internal class NativeCallControl(
    private val control: AnalysisControl,
    private val observer: ((Int) -> Unit)? = null,
) {
    /** Called synchronously on the worker by JNI, including INSIDE native DSP loops.
     * Stage 0=input validation/copy, 1=resampling, 2=tempo. No handle/thread survives the call. */
    fun poll(stage: Int): Int = try {
        observer?.invoke(stage)
        control.checkpoint()
        0
    } catch (_: CancellationException) { 1
    } catch (_: InterruptedException) { 1
    } catch (error: AnalysisUnavailable) {
        if (AutoMixFailure.of(error) == AutoMixFailure.ANALYSIS_TIMEOUT) 2 else 3
    } catch (_: Exception) { 3 }
}

internal object BitChordNativeBindings {
    // Load from the SAME declaring classloader as the native methods, including instrumentation.
    fun loadPackagedLibrary() { System.loadLibrary("tinium_automix") }
    external fun implementationVersion(): String
    external fun analyze(samples: FloatArray?, rate: Int, channels: Int, originUs: Long,
                         audibleStartSeconds: Double, budgetNanos: Long,
                         control: NativeCallControl): NativeDspPacket?
}

internal class NativeBridgeFailure(val code: String) : Exception(code)
internal fun interface NativeDspBridge {
    fun analyze(window: DecodedWindow, control: AnalysisControl): NativeDspPacket
}

/** Constructing this bridge never loads a library. Legacy startup has no native dependency. */
internal class JniBitChordBridge(
    private val loadLibrary: () -> Unit = BitChordNativeBindings::loadPackagedLibrary,
    private val observer: ((Int) -> Unit)? = null,
) : NativeDspBridge {
    @Volatile private var loadOutcome: String? = null
    private fun ensureLoaded() {
        if (loadOutcome == null) synchronized(this) {
            if (loadOutcome == null) loadOutcome = try {
                loadLibrary()
                if (BitChordNativeBindings.implementationVersion() == BitChordNativeAnalysisEngine.VERSION) "OK"
                else "NATIVE_VERSION_MISMATCH"
            } catch (_: LinkageError) { "NATIVE_LIBRARY_UNAVAILABLE"
            } catch (_: SecurityException) { "NATIVE_LIBRARY_UNAVAILABLE" }
        }
        if (loadOutcome != "OK") throw NativeBridgeFailure(requireNotNull(loadOutcome))
    }
    override fun analyze(window: DecodedWindow, control: AnalysisControl): NativeDspPacket {
        control.checkpoint()
        ensureLoaded()
        control.checkpoint()
        val now = System.nanoTime()
        val budget = if (control.deadlineNanos == Long.MAX_VALUE) 10_000_000_000L
            else (control.deadlineNanos - now).coerceIn(1L, 10_000_000_000L)
        return BitChordNativeBindings.analyze(window.samples, window.format.sampleRateHz!!, 1,
            window.sampleTimelineStartUs, 0.0, budget, NativeCallControl(control, observer))
            ?: throw NativeBridgeFailure("NATIVE_NULL_PACKET")
    }
}
