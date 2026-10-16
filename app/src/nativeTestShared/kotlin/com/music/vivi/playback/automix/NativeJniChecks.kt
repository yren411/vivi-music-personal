// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

/** Shared real-JNI checks executed on host AND, separately, on Android when available. */
internal object NativeJniChecks {
    fun completed(result: AnalysisResult): AnalysisResult.Completed {
        assertTrue("Native result: $result",result is AnalysisResult.Completed)
        return result as AnalysisResult.Completed
    }
    fun loading(bridge: NativeDspBridge) {
        val r=completed(BitChordNativeAnalysisEngine(bridge).analyze(NativeAnalysisFixtures.window(),AnalysisDetail.SCAN,AnalysisControl()))
        assertEquals(BitChordNativeAnalysisEngine.BACKEND,r.backend)
        assertEquals(PcmPreprocessing.BANDLIMITED,r.metadata.preprocessingVersion)
        assertEquals(11025,r.metadata.nativeDiagnostic!!.analysisRateHz)
    }
    fun tempos(bridge: NativeDspBridge) {
        for (bpm in listOf(90.0,107.0,120.0,128.0,140.0,150.0,174.0)) {
            val window=NativeAnalysisFixtures.window(NativeAnalysisFixtures.groove(bpm))
            val began=System.nanoTime()
            val r=completed(BitChordNativeAnalysisEngine(bridge).analyze(window,AnalysisDetail.DETAIL,AnalysisControl()))
            val roundTripUs=(System.nanoTime()-began)/1000
            assertEquals("bpm=$bpm; native=${r.metadata.nativeDiagnostic}",bpm,r.evidence.tempoBpm.value!!,1.0)
            val beats=r.evidence.beatsUs.value!!.filter { it in 1_000_000 until 29_000_000 }
            val truth=generateSequence(.137) { it+60/bpm }.takeWhile { it<30 }.map { (it*22050).roundToLong()*1_000_000/22050 }
                .filter { it in 1_000_000 until 29_000_000 }.toList()
            // Strict one-to-one 30ms matching, no octave correction or best-phase rotation.
            var i=0;var j=0;var matches=0
            while(i<truth.size && j<beats.size) {
                val error=beats[j]-truth[i]
                if(error < -30_000)j++ else if(error > 30_000)i++ else {matches++;i++;j++}
            }
            assertTrue("$bpm beat precision",matches.toDouble()/beats.size>.95)
            assertTrue("$bpm beat recall",matches.toDouble()/truth.size>.95)
            val diagnostic=requireNotNull(r.metadata.nativeDiagnostic)
            println("NATIVE_JNI_METRIC bpm=$bpm measured=${r.evidence.tempoBpm.value} matches=$matches expected=${truth.size} detected=${beats.size} rawScore=${diagnostic.rawConfidence} elapsedUs=${diagnostic.elapsedUs} engineRoundTripUs=$roundTripUs")
        }
    }
    fun timestamps(bridge: NativeDspBridge) {
        val a=NativeAnalysisFixtures.window(NativeAnalysisFixtures.groove(seconds=8),origin=12_345_678)
        val b=a.copy(sampleTimelineStartUs=a.sampleTimelineStartUs+180_000_000,
            requested=AudioIntervalUs(a.requested.startUs+180_000_000,a.requested.endUs+180_000_000),
            decodeInterval=AudioIntervalUs(a.decodeInterval.startUs+180_000_000,a.decodeInterval.endUs+180_000_000),
            coverage=AudioCoverage(a.coverage.intervals!!.map { AudioIntervalUs(it.startUs+180_000_000,it.endUs+180_000_000) },a.coverage.acceptedDecodedFrames))
        val engine=BitChordNativeAnalysisEngine(bridge)
        val x=completed(engine.analyze(a,AnalysisDetail.SCAN,AnalysisControl()))
        val y=completed(engine.analyze(b,AnalysisDetail.SCAN,AnalysisControl()))
        val original=requireNotNull(x.evidence.beatsUs.value)
        assertEquals(original.map { it+180_000_000 },y.evidence.beatsUs.value)
        assertTrue(original.all { it>=a.sampleTimelineStartUs && it<a.sampleInterval!!.endUs })
        val large=NativeAnalysisFixtures.window(a.samples,Long.MAX_VALUE-8_000_000)
        val result=completed(engine.analyze(large,AnalysisDetail.SCAN,AnalysisControl()))
        assertEquals(original.map { Math.addExact(it-a.sampleTimelineStartUs,large.sampleTimelineStartUs) },result.evidence.beatsUs.value)
    }
    fun silence(bridge: NativeDspBridge) {
        val r=completed(BitChordNativeAnalysisEngine(bridge).analyze(NativeAnalysisFixtures.window(FloatArray(22050*8)),AnalysisDetail.SCAN,AnalysisControl()))
        assertEquals("NO_EVIDENCE",r.metadata.nativeDiagnostic!!.status)
        assertEquals(EvidenceNature.UNAVAILABLE,r.evidence.beatsUs.nature)
        assertEquals(EvidenceNature.UNAVAILABLE,r.evidence.tempoBpm.nature)
        assertEquals(0.0,r.evidence.rms.value!!,0.0)
        val chord=FloatArray(22050*8) { i -> val t=i/22050.0
            (.05*(sin(2*PI*261.625565*t)+sin(2*PI*329.627557*t)+sin(2*PI*391.995436*t))).toFloat() }
        val uncertain=completed(BitChordNativeAnalysisEngine(bridge).analyze(NativeAnalysisFixtures.window(chord),AnalysisDetail.SCAN,AnalysisControl()))
        assertEquals(EvidenceMethod.BITCHORD_UNCALIBRATED_CONFIDENCE,uncertain.evidence.gridConfidence.method)
        assertEquals(EvidenceNature.UNAVAILABLE,uncertain.evidence.key.nature)
        println("NATIVE_JNI_NEGATIVE stationaryChord rawBpm=${uncertain.metadata.nativeDiagnostic!!.rawBpm} rawScore=${uncertain.metadata.nativeDiagnostic.rawConfidence}; not a calibrated probability")
        for(level in listOf(.005f,.1f,.7f)) {
            val dc=completed(BitChordNativeAnalysisEngine(bridge).analyze(NativeAnalysisFixtures.window(FloatArray(22050*8){level}),AnalysisDetail.SCAN,AnalysisControl()))
            assertEquals(EvidenceMethod.BITCHORD_UNCALIBRATED_CONFIDENCE,dc.evidence.gridConfidence.method)
            println("NATIVE_JNI_NEGATIVE dc=$level rawScore=${dc.metadata.nativeDiagnostic!!.rawConfidence} rmsMinusPeak=${dc.evidence.rms.value!!-dc.evidence.peak.value!!}")
        }
    }
    fun rawInputs() {
        for (input in listOf<FloatArray?>(null,floatArrayOf(),floatArrayOf(Float.NaN),floatArrayOf(Float.POSITIVE_INFINITY))) {
            val r=BitChordNativeBindings.analyze(input,22050,1,0,0.0,1_000_000_000,NativeCallControl(AnalysisControl()))
            assertEquals("INVALID_INPUT",r!!.status)
        }
        for ((rate,channels) in listOf(0 to 1,96001 to 1,22050 to 2)) {
            assertEquals("INVALID_INPUT",BitChordNativeBindings.analyze(FloatArray(100),rate,channels,0,0.0,1_000_000_000,NativeCallControl(AnalysisControl()))!!.status)
        }
        for ((origin,hint,budget) in listOf(Triple(-1L,0.0,1_000_000_000L),Triple(Long.MAX_VALUE,0.0,1_000_000_000L),
            Triple(0L,Double.NaN,1_000_000_000L),Triple(0L,0.0,0L)))
            assertEquals("INVALID_INPUT",BitChordNativeBindings.analyze(FloatArray(100),22050,1,origin,hint,budget,NativeCallControl(AnalysisControl()))!!.status)
        assertEquals("INVALID_INPUT",BitChordNativeBindings.analyze(FloatArray(22050*32+1),22050,1,0,0.0,1_000_000_000,NativeCallControl(AnalysisControl()))!!.status)
    }
    fun cancelDuringDsp(loader: () -> Unit) {
        val token=AnalysisCancellationToken();var tempoChecks=0;var cancelAt=0L
        val bridge=JniBitChordBridge(loader) { stage -> if(stage==2 && ++tempoChecks==100) {cancelAt=System.nanoTime();token.cancel()} }
        val engine=BitChordNativeAnalysisEngine(bridge)
        val w=NativeAnalysisFixtures.window(NativeAnalysisFixtures.groove(seconds=32))
        val start=System.nanoTime()
        val r=engine.analyze(w,AnalysisDetail.SCAN,AnalysisControl(cancellation=token)) as AnalysisResult.Failed
        assertTrue(tempoChecks>=100);assertEquals(AutoMixFailure.ANALYSIS_CANCELED,r.failure.category)
        println("NATIVE_JNI_CANCEL totalUs=${(System.nanoTime()-start)/1000} latencyUs=${(System.nanoTime()-cancelAt)/1000} tempoCheckpoints=$tempoChecks")
        // The canceled call did not poison the library or a separate token.
        completed(BitChordNativeAnalysisEngine(JniBitChordBridge(loader)).analyze(w,AnalysisDetail.SCAN,AnalysisControl()))
    }
    fun deadlineDuringDsp() {
        var tempoChecks=0
        val poller=NativeCallControl(AnalysisControl()) { stage ->
            if(stage==2 && ++tempoChecks==100)Thread.sleep(2100) // test-only bounded hold, no busy wait
        }
        val packet=BitChordNativeBindings.analyze(NativeAnalysisFixtures.groove(seconds=32,rate=11025),11025,1,0,0.0,2_000_000_000,poller)!!
        assertTrue(tempoChecks>=100);assertEquals("DEADLINE",packet.status)
        assertTrue(packet.beatsSeconds.isEmpty())
    }
    fun concurrent(bridge: NativeDspBridge) {
        AnalysisWorkers<AnalysisResult>(AutoMixPerformance.PERFORMANCE).use { workers ->
            val windows=(0..3).map { NativeAnalysisFixtures.window(NativeAnalysisFixtures.groove(seconds=16,phase=.137+it*.03)) }
            val engine=BitChordNativeAnalysisEngine(bridge)
            val expected=windows.map { completed(engine.analyze(it,AnalysisDetail.SCAN,AnalysisControl())).evidence }
            val jobs=windows.map { w -> workers.submitTransient { engine.analyze(w,AnalysisDetail.SCAN,AnalysisControl()) } }
            jobs.forEachIndexed { i,job -> assertEquals(expected[i],completed(job.get(20,TimeUnit.SECONDS)).evidence) }
        }
        // Real worker interruption/shutdown while DSP is active, not just token cancellation.
        val entered=CountDownLatch(1);val exited=CountDownLatch(1);val checks=AtomicInteger()
        val workers=AnalysisWorkers<AnalysisResult>(AutoMixPerformance.EFFICIENT)
        try {
            val engine=BitChordNativeAnalysisEngine(JniBitChordBridge({ /* already loaded by bridge */ }) { stage ->
                if(stage==2 && checks.incrementAndGet()==100) {entered.countDown();CountDownLatch(1).await(5,TimeUnit.SECONDS)}
            })
            val window=NativeAnalysisFixtures.window(NativeAnalysisFixtures.groove(seconds=16))
            val task=workers.submitTransient { try {engine.analyze(window,AnalysisDetail.SCAN,AnalysisControl())} finally {exited.countDown()} }
            assertTrue(entered.await(20,TimeUnit.SECONDS));workers.close()
            assertThrows(CancellationException::class.java) {task.get()}
            assertTrue(exited.await(20,TimeUnit.SECONDS))
        } finally {workers.close()}
    }
    fun cancellationIsolation(loader: () -> Unit) {
        AnalysisWorkers<AnalysisResult>(AutoMixPerformance.BALANCED).use { workers ->
            val token=AnalysisCancellationToken();val count=AtomicInteger()
            val cancelEngine=BitChordNativeAnalysisEngine(JniBitChordBridge(loader) { stage -> if(stage==2 && count.incrementAndGet()==100)token.cancel() })
            val keepEngine=BitChordNativeAnalysisEngine(JniBitChordBridge(loader))
            val w=NativeAnalysisFixtures.window(NativeAnalysisFixtures.groove(seconds=16))
            val canceled=workers.submitTransient { cancelEngine.analyze(w,AnalysisDetail.SCAN,AnalysisControl(cancellation=token)) }
            val kept=workers.submitTransient { keepEngine.analyze(w,AnalysisDetail.SCAN,AnalysisControl()) }
            assertEquals(AutoMixFailure.ANALYSIS_CANCELED,(canceled.get(20,TimeUnit.SECONDS) as AnalysisResult.Failed).failure.category)
            completed(kept.get(20,TimeUnit.SECONDS))
        }
    }
    fun repeated(bridge: NativeDspBridge) {
        val engine=BitChordNativeAnalysisEngine(bridge);val w=NativeAnalysisFixtures.window()
        val first=completed(engine.analyze(w,AnalysisDetail.SCAN,AnalysisControl())).evidence
        repeat(8) { assertEquals(first,completed(engine.analyze(w,AnalysisDetail.SCAN,AnalysisControl())).evidence) }
    }
}
