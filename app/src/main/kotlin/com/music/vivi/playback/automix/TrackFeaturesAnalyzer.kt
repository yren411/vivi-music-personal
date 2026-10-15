package com.music.vivi.playback.automix

import kotlin.math.*

/** Fixed-quality PCM DSP for all worker modes. No catalog BPM or UI-thread computation. */
class TrackFeaturesAnalyzer {
    fun analyze(samples: FloatArray, sampleRate: Int, startMs: Long, durationMs: Long,
                deadlineNanos: Long = Long.MAX_VALUE, detail: AnalysisDetail = AnalysisDetail.DETAIL,
                cancellation: AnalysisCancellation = AnalysisCancellation.NONE): TrackFeatures {
        require(sampleRate in 8_000..192_000 && samples.size >= sampleRate * 4)
        require(samples.all { it.isFinite() })
        val hop = sampleRate / 100 // 10ms attack envelope; independent of analysis-worker count.
        val levels = DoubleArray(samples.size / hop)
        var peak = 0.0
        var sum = 0.0
        for (frame in levels.indices) {
            checkAnalysisCancellation(deadlineNanos, cancellation)
            var power = 0.0
            for (i in frame * hop until (frame + 1) * hop) {
                val v = samples[i].toDouble()
                power += v * v
                peak = max(peak, abs(v))
            }
            sum += power
            levels[frame] = sqrt(power / hop)
        }
        // Spectral positive flux measures transient changes even in compressed music whose
        // RMS barely moves. Stationary tones must not create a confident grid from RMS beating.
        val attacks = DoubleArray(levels.size)
        val accents = DoubleArray(levels.size)
        val onsetReal = DoubleArray(1024)
        val onsetImaginary = DoubleArray(1024)
        val previousSpectrum = DoubleArray(512)
        val onsetHop = sampleRate / 50 // 20ms, identical for every performance mode and scan/detail.
        for (offset in 0..samples.size - 1024 step onsetHop) {
            checkAnalysisCancellation(deadlineNanos, cancellation)
            for (i in onsetReal.indices) {
                onsetReal[i] = samples[offset + i] * (0.5 - 0.5 * cos(2 * PI * i / 1023))
                onsetImaginary[i] = 0.0
            }
            fft(onsetReal, onsetImaginary)
            var flux = 0.0; var total = 0.0; var bass = 0.0
            for (bin in 2 until 400) {
                val magnitude = hypot(onsetReal[bin], onsetImaginary[bin])
                flux += max(0.0, magnitude - previousSpectrum[bin])
                total += magnitude
                if (bin.toDouble() * sampleRate / 1024 < 200) bass += magnitude
                previousSpectrum[bin] = magnitude
            }
            val index = ((offset + 512) / hop).coerceAtMost(attacks.lastIndex)
            if (offset > 0) attacks[index] = flux / total.coerceAtLeast(1e-6)
            accents[index] = bass
        }
        val attackPower = attacks.sumOf { it * it }
        var bestLag = 0
        var bestCorrelation = 0.0
        for (lag in 33..100) { // 60..182 BPM. Octave ambiguity remains confidence-gated.
            checkAnalysisCancellation(deadlineNanos, cancellation)
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
        // A quiet audible intro without drums is not a failed beat grid. Require a
        // sustained train of three measured onsets at the grid edges, not merely RMS.
        val strongest=attacks.maxOrNull() ?: 0.0
        val grid=if(bestLag>0) (phase until attacks.size step bestLag).toList() else emptyList()
        val supported=grid.map { tick -> (max(0,tick-5)..min(attacks.lastIndex,tick+5)).maxOf { attacks[it] }>=max(0.035,strongest*0.12) }
        val trains=(0 until (grid.size-2).coerceAtLeast(0)).filter { supported[it] && supported[it+1] && supported[it+2] }
        val beatTicks=if(trains.isEmpty()) emptyList() else grid.subList(trains.first(),trains.last()+3)
        val beats = beatTicks.map { startMs + it * 10L }
        val captured = beats.indices.sumOf { index ->
            val tick = beatTicks[index]
            (max(0, tick - 3)..min(attacks.lastIndex, tick + 3)).sumOf { attacks[it] }
        }
        val phaseQuality = if (bestLag > 0 && attacks.sum() > 1e-8)
            ((captured / attacks.sum() - 7.0 / bestLag) / 0.65).coerceIn(0.0, 1.0) else 0.0
        val beatStrengths = beats.indices.map { index ->
            val tick = beatTicks[index]
            (max(0,tick-5)..min(attacks.lastIndex,tick+5)).maxOf { attacks[it] }
        }
        val stability = if (strongest > 0) beatStrengths.count { it >= strongest * 0.12 }.toDouble() / beats.size.coerceAtLeast(1) else 0.0
        val confidence = if (attackPower > 1e-8 && beats.size >= 6 && strongest >= 0.035)
            (bestCorrelation * sqrt(phaseQuality) * sqrt(stability)).coerceIn(0.0, 1.0) else 0.0
        // A bar estimate requires REPEATED measured low-frequency accent dominance. Uniform
        // beats never authorize downbeats; no arbitrary index modulo is labelled a phrase.
        val beatAccents = beats.indices.map { index ->
            val tick = beatTicks[index]
            (max(0,tick-5)..min(accents.lastIndex,tick+5)).maxOf { accents[it] }
        }
        val groups = (0..3).map { group -> beatAccents.filterIndexed { i,_ -> i%4==group }.average().takeIf { it.isFinite() } ?: 0.0 }
        val order = groups.indices.sortedByDescending { groups[it] }
        val contrast = (groups[order[0]] - groups[order[1]]) / groups[order[0]].coerceAtLeast(1e-6)
        val bars = beatAccents.chunked(4).filter { it.size==4 }
        val repeated = if (bars.isEmpty()) 0.0 else bars.count { it.indices.maxBy { i -> it[i] }==order[0] }.toDouble()/bars.size
        val downbeatConfidence = if (bars.size>=4 && contrast>=0.2 && repeated>=0.75 && confidence>=0.6)
            (contrast*repeated).coerceIn(0.0,1.0) else 0.0
        val downbeats = if (downbeatConfidence>=0.25) beats.filterIndexed { i,_ -> i%4==order[0] } else emptyList()

        val chroma = DoubleArray(12)
        val energy = mutableListOf<EnergyPoint>()
        val fftSize = 4096
        val stride = sampleRate / if (detail == AnalysisDetail.SCAN) 2 else 10
        val real = DoubleArray(fftSize)
        val imaginary = DoubleArray(fftSize)
        for (offset in 0..samples.size - fftSize step stride) {
            checkAnalysisCancellation(deadlineNanos, cancellation)
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
            var logSum = 0.0
            var bins = 0
            var fullPower = 0.0; var bassPower = 0.0; var occupied = 0
            for (bin in 1 until fftSize / 2) {
                val hz = bin.toDouble() * sampleRate / fftSize
                val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                if(hz in 40.0..8000.0) {
                    fullPower+=magnitude*magnitude
                    if(hz<200) bassPower+=magnitude*magnitude
                    if(magnitude>0.02) occupied++
                }
                if (hz in 120.0..4000.0) {
                    val midi = (69 + 12 * log2(hz / 440.0)).roundToInt()
                    chroma[((midi % 12) + 12) % 12] += magnitude
                    total += magnitude
                    logSum += ln(magnitude.coerceAtLeast(1e-9)); bins++
                    largest = max(largest, magnitude)
                    if (hz in 300.0..3000.0) voiceBand += magnitude
                }
            }
            // A deliberately conservative voice-band/tonality proxy, NOT vocal separation.
            // Sustained instruments can score high; the planner then chooses a shorter blend.
            val flatness = if(bins>0 && total>1e-6) (exp(logSum/bins)/(total/bins)).coerceIn(0.0,1.0) else 1.0
            // Broadband percussion is less voice-like than sustained harmonic mid-band energy.
            val risk = if (total > 1e-6) ((voiceBand / total) * (1 - largest / total) * (1-flatness)).coerceIn(0.0, 1.0) else 0.0
            val first = offset / hop
            // A 500ms energy bin excludes the NEXT frame. Including frame 50 sampled
            // the following kick into a silence bin and invented a cue 500ms early.
            if(first+49>levels.lastIndex) break
            val last = first + 49
            val localRms = sqrt((first..last).sumOf { levels[it] * levels[it] } / (last-first+1))
            val bassFraction=if(fullPower>1e-8) (bassPower/fullPower).coerceIn(0.0,1.0) else 0.0
            val percussion=((first..last).sumOf { attacks[it] }/(50*strongest.coerceAtLeast(0.035)*0.04)).coerceIn(0.0,1.0)
            val density=(occupied/300.0).coerceIn(0.0,1.0)
            // A bass-dominant transient must not become "vocals" merely because the old
            // voice proxy's denominator excluded its fundamental. Still a proxy, not stems.
            val voiced=risk*(1-bassFraction*0.75)
            energy += EnergyPoint(startMs + offset * 1000L / sampleRate + 250, localRms, voiced,
                bassFraction,density,percussion)
        }
        val length = samples.size * 1000L / sampleRate
        val section = RhythmSection(startMs, startMs+length, bpm, confidence, phaseQuality, stability,
            detail==AnalysisDetail.DETAIL, downbeats, downbeatConfidence)
        return TrackFeatures(durationMs, startMs, length, bpm, confidence,
            beats, sqrt(sum / (levels.size * hop)), peak, estimateKey(chroma), energy,
            phaseQuality, stability, listOf(section), detectSectionBoundaries(energy, confidence))
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
