package com.music.vivi.playback.automix

import kotlin.math.*

/** Shared, generated music benchmark. No copyrighted recordings or platform decoders. */
internal object AnalysisPcmFixtures {
    data class Fixture(val name: String, val samples: FloatArray, val rate: Int,
                       val beatsUs: List<Long>, val durationUs: Long, val bpm: Double?)
    private class Noise(seed: Long = 411) {
        private var state = seed
        fun next(): Double {
            state = (state * 1664525 + 1013904223) and 0xffffffffL
            return state.toDouble() / 4294967296.0 * 2 - 1
        }
    }

    /** Phase 1 amplitudes/envelopes/phase; specified LCG replaces NumPy's PCG64 noise only. */
    fun groove(bpm: Double, seconds: Int = 30, phaseSeconds: Double = 0.137,
               leadingSilenceSeconds: Double = 0.0, rate: Int = 22050,
               offbeatAccents: Boolean = false): Fixture {
        val phase = leadingSilenceSeconds + phaseSeconds
        val samples = FloatArray(rate * seconds) { i ->
            val t = i.toDouble() / rate
            if (t < leadingSilenceSeconds) 0f else
                (0.05 * (sin(2 * PI * 130.8128 * t) + sin(2 * PI * 164.8138 * t) +
                    sin(2 * PI * 195.9977 * t))).toFloat()
        }
        val noise = Noise()
        val beats = mutableListOf<Long>()
        var beat = 0
        while (phase + beat * 60 / bpm < seconds) {
            val onset = phase + beat * 60 / bpm
            val start = (onset * rate).roundToInt()
            beats += (onset * 1_000_000).roundToLong()
            for (i in 0 until minOf((0.15 * rate).roundToInt(), samples.size - start)) {
                val t = i.toDouble() / rate
                val kick = (if (beat % 4 == 0) 0.65 else 0.45) * sin(2 * PI * 70 * t) * exp(-32 * t)
                val snare = if (beat % 2 == 1) 0.18 * noise.next() * exp(-45 * t) else 0.0
                samples[start + i] = (samples[start + i] + kick + snare).toFloat()
            }
            beat++
        }
        var eighth = 0
        while (phase + eighth * 30 / bpm < seconds) {
            val start = ((phase + eighth * 30 / bpm) * rate).roundToInt()
            for (i in 0 until minOf((0.025 * rate).roundToInt(), samples.size - start)) {
                val accent = if (offbeatAccents && eighth % 2 == 1) 2.0 else 1.0
                samples[start + i] = (samples[start + i] + accent * 0.08 * noise.next() *
                    exp(-160 * i.toDouble() / rate)).toFloat()
            }
            eighth++
        }
        return Fixture("groove-$bpm-$seconds-phase-$phase", samples, rate, beats, seconds * 1_000_000L, bpm)
    }

    fun silence(seconds: Int = 8, rate: Int = 22050) =
        Fixture("silence", FloatArray(rate * seconds), rate, emptyList(), seconds * 1_000_000L, null)

    fun chord(seconds: Int = 30, rate: Int = 22050): Fixture {
        val samples = FloatArray(rate * seconds) { i ->
            val t = i.toDouble() / rate
            (0.05 * (sin(2 * PI * 130.8128 * t) + sin(2 * PI * 164.8138 * t) +
                sin(2 * PI * 195.9977 * t))).toFloat()
        }
        return Fixture("sustained-chord", samples, rate, emptyList(), seconds * 1_000_000L, null)
    }

    fun noise(seconds: Int = 8, rate: Int = 22050): Fixture {
        val rng = Noise()
        return Fixture("non-rhythmic-noise", FloatArray(rate * seconds) { (rng.next() * 0.15).toFloat() },
            rate, emptyList(), seconds * 1_000_000L, null)
    }

    fun window(fixture: Fixture, startUs: Long = 0, durationUs: Long? = fixture.durationUs): DecodedWindow {
        val interval = AudioIntervalUs(startUs, startUs + fixture.durationUs)
        return DecodedWindow(fixture.samples, PcmFormat(fixture.rate, 1, PcmEncoding.FLOAT32),
            interval, interval, startUs, AudioCoverage(listOf(interval), fixture.samples.size.toLong()),
            listOf(SourcePcmFormat(PcmFormat(fixture.rate, 1, PcmEncoding.FLOAT32), SourceFormatBasis.DECODER_OUTPUT)),
            AudioDuration(durationUs, if (durationUs == null) DurationBasis.UNKNOWN else DurationBasis.FIXTURE),
            false, AnalysisSourceIdentity("fixture", fixture.name), PcmPreprocessing.IDENTITY, 0)
    }
}

internal object BeatBenchmark {
    enum class TempoAmbiguity { NONE, HALF_TIME, DOUBLE_TIME, OTHER }
    data class Scores(val precision: Double, val recall: Double, val f1: Double,
                      val matches: Int, val medianErrorMs: Double?, val p95ErrorMs: Double?)
    data class Metrics(val tempoErrorPercent: Double?, val ambiguity: TempoAmbiguity,
                       val at70: Scores, val at30: Scores, val accumulatedDriftMs: Double?)

    /** Earliest feasible monotonic one-to-one matches; no phase rotation or octave rescaling. */
    fun match(referenceUs: List<Long>, predictedUs: List<Long>, toleranceUs: Long): Scores {
        require(toleranceUs >= 0)
        val ref = referenceUs.sorted()
        val pred = predictedUs.sorted()
        val errors = mutableListOf<Double>()
        var i = 0; var j = 0
        while (i < ref.size && j < pred.size) {
            val delta = pred[j] - ref[i]
            when {
                delta < -toleranceUs -> j++
                delta > toleranceUs -> i++
                else -> { errors += abs(delta) / 1000.0; i++; j++ }
            }
        }
        val precision = if (pred.isEmpty()) 0.0 else errors.size.toDouble() / pred.size
        val recall = if (ref.isEmpty()) 0.0 else errors.size.toDouble() / ref.size
        val sorted = errors.sorted()
        val median = if (sorted.isEmpty()) null else
            if (sorted.size % 2 == 1) sorted[sorted.size / 2] else
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        return Scores(precision, recall, if (precision + recall == 0.0) 0.0 else
            2 * precision * recall / (precision + recall), errors.size, median,
            sorted.takeIf { it.isNotEmpty() }?.get((ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)))
    }

    fun evaluate(fixture: AnalysisPcmFixtures.Fixture, features: TrackFeatures): Metrics {
        val predictions = features.beatsMs.map { it * 1000 }
        val reference = fixture.beatsUs
        fun interior(times: List<Long>) = times.filter { it > 1_000_000 && it < fixture.durationUs - 1_000_000 }
        val ratio = fixture.bpm?.let { features.bpm / it }
        val ambiguity = when {
            ratio == null -> TempoAmbiguity.OTHER
            abs(ratio - 1) <= 0.01 -> TempoAmbiguity.NONE
            abs(ratio - 0.5) <= 0.005 -> TempoAmbiguity.HALF_TIME
            abs(ratio - 2) <= 0.02 -> TempoAmbiguity.DOUBLE_TIME
            else -> TempoAmbiguity.OTHER
        }
        // Index-paired drift without realignment. Wrong tempo/missing beats intentionally
        // make this large; inspect F1/counts before interpreting it as pure local phase drift.
        val count = minOf(reference.size, predictions.size)
        val drift = if (count < 2) null else
            ((predictions[count - 1] - reference[count - 1]) - (predictions.first() - reference.first())) / 1000.0
        return Metrics(fixture.bpm?.let { abs(features.bpm - it) / it * 100 }, ambiguity,
            match(interior(reference), interior(predictions), 70_000),
            match(interior(reference), interior(predictions), 30_000), drift)
    }
}

/** Published accuracy gates for future backends; not relaxed to fit current legacy results. */
internal object FutureAnalysisAccuracyTargets {
    fun failures(metrics: BeatBenchmark.Metrics): List<String> = buildList {
        if (metrics.tempoErrorPercent == null || metrics.tempoErrorPercent > 1.0) add("TEMPO_ERROR_GT_1_PERCENT")
        if (metrics.at70.f1 < 0.95) add("BEAT_F1_70MS_LT_0_95")
        if (metrics.at30.f1 < 0.95) add("BEAT_F1_30MS_LT_0_95")
        if (metrics.at70.p95ErrorMs == null || metrics.at70.p95ErrorMs > 30) add("P95_ERROR_GT_30MS")
        if (metrics.accumulatedDriftMs == null || abs(metrics.accumulatedDriftMs) > 70) add("DRIFT_GT_70MS")
    }
}

