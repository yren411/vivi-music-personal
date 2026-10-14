package com.music.vivi.playback.automix

import kotlin.math.*

/** Fixed-quality PCM DSP for all worker modes. No catalog BPM or UI-thread computation. */
class TrackFeaturesAnalyzer {
    fun analyze(samples: FloatArray, sampleRate: Int, startMs: Long, durationMs: Long,
                deadlineNanos: Long = Long.MAX_VALUE): TrackFeatures {
        require(sampleRate in 8_000..192_000 && samples.size >= sampleRate * 4)
        require(samples.all { it.isFinite() })
        val hop = sampleRate / 100 // 10ms attack envelope; independent of analysis-worker count.
        val levels = DoubleArray(samples.size / hop)
        var peak = 0.0
        var sum = 0.0
        for (frame in levels.indices) {
            checkAnalysisCancellation(deadlineNanos)
            var power = 0.0
            for (i in frame * hop until (frame + 1) * hop) {
                val v = samples[i].toDouble()
                power += v * v
                peak = max(peak, abs(v))
            }
            sum += power
            levels[frame] = sqrt(power / hop)
        }
        // Reject low-frequency aliases from beating sustained tones: average 50ms of energy
        // before measuring attacks. This is short compared with a musical beat, not a quality mode.
        val smoothed = DoubleArray(levels.size) { i -> (max(0, i - 4)..i).map { levels[it] }.average() }
        val attacks = DoubleArray(levels.size) { i -> if (i == 0) 0.0 else max(0.0, smoothed[i] - smoothed[i - 1]) }
        val attackPower = attacks.sumOf { it * it }
        var bestLag = 0
        var bestCorrelation = 0.0
        for (lag in 33..100) { // 60..182 BPM. Octave ambiguity remains confidence-gated.
            checkAnalysisCancellation(deadlineNanos)
            var cross = 0.0
            var a = 0.0
            var b = 0.0
            for (i in lag until attacks.size) {
                cross += attacks[i] * attacks[i - lag]
                a += attacks[i] * attacks[i]
                b += attacks[i - lag] * attacks[i - lag]
            }
            val correlation = if (a * b > 1e-15) cross / sqrt(a * b) else 0.0
            if (correlation > bestCorrelation + 0.025) {
                bestCorrelation = correlation
                bestLag = lag
            }
        }
        val bpm = if (bestLag > 0) 6000.0 / bestLag else 0.0
        var phase = 0
        if (bestLag > 0) {
            phase = (0 until bestLag).maxBy { p -> (p until attacks.size step bestLag).sumOf { attacks[it] } }
        }
        val beats = if (bestLag > 0) (phase until attacks.size step bestLag).map { startMs + it * 10L } else emptyList()
        val meanLevel = smoothed.average().coerceAtLeast(1e-6)
        val variation = sqrt(smoothed.sumOf { (it - meanLevel).pow(2) } / smoothed.size) / meanLevel
        val transientEvidence = (attacks.maxOrNull() ?: 0.0) >= meanLevel * 0.25 && variation >= 0.15
        val confidence = if (attackPower > 1e-8 && beats.size >= 6 && transientEvidence) bestCorrelation.coerceIn(0.0, 1.0) else 0.0

        val chroma = DoubleArray(12)
        val energy = mutableListOf<EnergyPoint>()
        val fftSize = 4096
        val stride = sampleRate / 10
        val real = DoubleArray(fftSize)
        val imaginary = DoubleArray(fftSize)
        for (offset in 0..samples.size - fftSize step stride) {
            checkAnalysisCancellation(deadlineNanos)
            var power = 0.0
            for (i in real.indices) {
                val v = samples[offset + i].toDouble()
                power += v * v
                real[i] = v * (0.5 - 0.5 * cos(2 * PI * i / (fftSize - 1)))
                imaginary[i] = 0.0
            }
            fft(real, imaginary)
            var total = 0.0
            var voiceBand = 0.0
            var largest = 0.0
            for (bin in 1 until fftSize / 2) {
                val hz = bin.toDouble() * sampleRate / fftSize
                val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                if (hz in 120.0..4000.0) {
                    val midi = (69 + 12 * log2(hz / 440.0)).roundToInt()
                    chroma[((midi % 12) + 12) % 12] += magnitude
                    total += magnitude
                    largest = max(largest, magnitude)
                    if (hz in 300.0..3000.0) voiceBand += magnitude
                }
            }
            // A deliberately conservative voice-band/tonality proxy, NOT vocal separation.
            // Sustained instruments can score high; the planner then chooses a shorter blend.
            val risk = if (total > 1e-6) ((voiceBand / total) * (1 - largest / total)).coerceIn(0.0, 1.0) else 0.0
            energy += EnergyPoint(startMs + offset * 1000L / sampleRate, sqrt(power / fftSize), risk)
        }
        return TrackFeatures(durationMs, startMs, samples.size * 1000L / sampleRate, bpm, confidence,
            beats, sqrt(sum / (levels.size * hop)), peak, estimateKey(chroma), energy)
    }

    private fun estimateKey(chroma: DoubleArray): MusicalKey? {
        val total = chroma.sum()
        if (total < 1e-5 || chroma.count { it > total * 0.04 } < 3) return null
        // Published Krumhansl–Kessler pitch-class profiles; numerical music-research data.
        val major = doubleArrayOf(6.35,2.23,3.48,2.33,4.38,4.09,2.52,5.19,2.39,3.66,2.29,2.88)
        val minor = doubleArrayOf(6.33,2.68,3.52,5.38,2.60,3.53,2.54,4.75,3.98,2.69,3.34,3.17)
        val mean = total / 12
        val centered = DoubleArray(12) { chroma[it] - mean }
        val scores = mutableListOf<MusicalKey>()
        for (isMinor in listOf(false, true)) for (tonic in 0..11) {
            val profile = if (isMinor) minor else major
            val profileMean = profile.average()
            val p = DoubleArray(12) { profile[(it - tonic + 12) % 12] - profileMean }
            val denom = sqrt(centered.sumOf { it * it } * p.sumOf { it * it })
            val score = if (denom > 0) centered.indices.sumOf { centered[it] * p[it] } / denom else 0.0
            scores += MusicalKey(tonic, isMinor, score)
        }
        val sorted = scores.sortedByDescending { it.confidence }
        val best = sorted[0]
        val certainty = ((best.confidence - sorted[1].confidence) * 3).coerceIn(0.0, 1.0)
        return best.copy(confidence = certainty)
    }

    /** Iterative radix-2 transform; independently written, no native/ML dependency. */
    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until real.size) {
            var bit = real.size shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val v = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = v
            }
        }
        var length = 2
        while (length <= real.size) {
            val angle = -2 * PI / length
            val wr = cos(angle)
            val wi = sin(angle)
            for (base in real.indices step length) {
                var ur = 1.0
                var ui = 0.0
                for (k in 0 until length / 2) {
                    val a = base + k
                    val b = a + length / 2
                    val vr = real[b] * ur - imaginary[b] * ui
                    val vi = real[b] * ui + imaginary[b] * ur
                    real[b] = real[a] - vr; imaginary[b] = imaginary[a] - vi
                    real[a] += vr; imaginary[a] += vi
                    val next = ur * wr - ui * wi
                    ui = ur * wi + ui * wr; ur = next
                }
            }
            length *= 2
        }
    }
}
