package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AutoMixAnalysisTest {
    private fun pulseAudio(bpm: Double, rate: Int = 22050, seconds: Int = 12): FloatArray =
        FloatArray(rate * seconds) { i ->
            val t = i.toDouble() / rate
            val beat = t % (60 / bpm)
            val pulse = if (beat < 0.06) exp(-beat * 65) else 0.0
            (0.65 * pulse * sin(2 * PI * 180 * t) + 0.025 * sin(2 * PI * 440 * t)).toFloat()
        }

    @Test fun detectsActual120BpmPcmAndAbsoluteBeats() {
        val f = TrackFeaturesAnalyzer().analyze(pulseAudio(120.0), 22050, 48000, 60000)
        assertEquals(120.0, f.bpm, 2.0)
        assertTrue(f.beatConfidence > 0.65)
        assertTrue(f.beatsMs.size >= 20)
        assertTrue(f.beatsMs.first() >= 48000)
        assertTrue(f.beatsMs.all { abs(((it - 48000) % 500).toDouble()) <= 30 })
        assertTrue(f.rms > 0 && f.peak > f.rms)
        assertTrue(f.energy.isNotEmpty())
    }

    @Test fun analysisQualityDoesNotDependOnNativeSampleRate() {
        for (rate in listOf(22050,44100,48000)) {
            val f = TrackFeaturesAnalyzer().analyze(pulseAudio(100.0, rate), rate, 0, 60000)
            assertEquals(100.0, f.bpm, 2.0)
            assertTrue(f.beatConfidence > 0.65)
        }
    }

    @Test fun silentAudioDoesNotInventTempoOrKey() {
        val f = TrackFeaturesAnalyzer().analyze(FloatArray(22050 * 8), 22050, 0, 60000)
        assertEquals(0.0, f.beatConfidence, 0.0)
        assertNull(f.key)
        assertEquals(0.0, f.peak, 0.0)
    }

    @Test fun chromaTracksAMajorChordWithoutRequiringAFalseConfidentKey() {
        val pcm = FloatArray(22050 * 8) { i ->
            val t = i / 22050.0
            (0.18 * (sin(2*PI*261.63*t) + sin(2*PI*329.63*t) + sin(2*PI*392*t))).toFloat()
        }
        val f = TrackFeaturesAnalyzer().analyze(pcm, 22050, 0, 60000)
        assertNotNull(f.key)
        assertTrue(f.key!!.confidence in 0.0..1.0)
        assertEquals(0.0, f.beatConfidence, 0.1) // sustained tones do not justify beat matching
    }

    @Test fun deadlineStopsDspWork() {
        try {
            TrackFeaturesAnalyzer().analyze(pulseAudio(120.0), 22050, 0, 60000, System.nanoTime() - 1)
            fail("Expired analysis continued")
        } catch (_: java.util.concurrent.CancellationException) { }
    }
}
