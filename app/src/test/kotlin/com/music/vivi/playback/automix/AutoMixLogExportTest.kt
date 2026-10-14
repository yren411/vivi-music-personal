package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.io.IOException

class AutoMixLogExportTest {
    private class Store(val failWrite: Boolean=false,val failPublish: Boolean=false): MixLogDestination {
        var serial=0
        val files=mutableMapOf<String,ByteArray>()
        val order=mutableListOf<String>()
        override fun create(filename: String): String { val id="content://test/${++serial}";files[id]=byteArrayOf();order+="create";return id }
        override fun write(document: String,bytes: ByteArray) { order+="write";if(failWrite)throw IOException("private URL must not be exported");files[document]=bytes }
        override fun publish(document: String) { order+="publish";if(failPublish)throw IOException("provider error") }
        override fun remove(document: String) { order+="delete";files.remove(document) }
    }
    @Test fun successWritesBeforePublishingAndNeverOverwritesAnExistingDocument() {
        val store=Store()
        val first=MixLogWriter.save(store,MixLogWriter.filename(),"first")
        val second=MixLogWriter.save(store,MixLogWriter.filename(),"second")
        assertNotEquals(first,second);assertEquals("first",store.files[first]!!.toString(Charsets.UTF_8))
        assertEquals("second",store.files[second]!!.toString(Charsets.UTF_8))
        assertEquals(listOf("create","write","publish","create","write","publish"),store.order)
    }
    @Test fun failuresAndCancellationDeletePartialReportsAndNeverReturnSuccess() {
        for(store in listOf(Store(failWrite=true),Store(failPublish=true))) {
            try { MixLogWriter.save(store,"test.txt","report");fail("claimed success") } catch(_: IOException) {}
            assertTrue(store.files.isEmpty());assertEquals("delete",store.order.last())
        }
        val store=Store();var checks=0
        try {
            MixLogWriter.save(store,"cancel.txt","report") { if(++checks==3)throw java.util.concurrent.CancellationException() }
            fail("published canceled export")
        } catch(_: java.util.concurrent.CancellationException) {}
        assertFalse(store.order.contains("publish"));assertTrue(store.files.isEmpty())
    }
    @Test fun filenameIsSafeUniqueAndOneHeldGestureCannotStartDuplicateExports() {
        val time=LocalDateTime.of(2026,10,14,12,34,56)
        val name=MixLogWriter.filename(time)
        assertTrue(name.matches(Regex("tinium-automix-log-2026-10-14-12-34-56-[a-f0-9]{8}\\.txt")))
        assertNotEquals(name,MixLogWriter.filename(time))
        val gate=MixLogExportGate();assertTrue(gate.begin())
        repeat(20) { assertFalse(gate.begin()) }
        gate.finish();assertTrue(gate.begin())
    }
    @Test fun reportPreservesRecentHistoryAndFailuresButContainsNoMediaSecrets() {
        val recorder=AutoMixRecorder()
        val id=recorder.begin("https://private.example/song?token=PRIVATE_TOKEN","cookie=PRIVATE_COOKIE")
        recorder.record(id,MixEvent.FAILURE,failure=AutoMixFailure.SOURCE_NOT_SEEKABLE,stage=MixStage.SCAN,errorType=MixErrorType.IO)
        recorder.record(id,MixEvent.PLAN,tier=AutoMixTier.C,technique=MixTechnique.SAFE_FADE)
        recorder.record(id,MixEvent.START,tier=AutoMixTier.C)
        val report=AutoMixRecorder.render(recorder.snapshot(),MixReportHeader("6.0.8-beta.3",80,"16","SM-S938",false,AutoMixPerformance.EFFICIENT))
        for(secret in listOf("https://","private.example","PRIVATE_TOKEN","PRIVATE_COOKIE","cookie=")) assertFalse(report.contains(secret))
        assertTrue(report.contains("SOURCE_NOT_SEEKABLE"));assertTrue(report.contains("stage=SCAN"))
        assertTrue(report.contains("Started tier C: 1"));assertTrue(report.contains("AutoMix enabled: false"))
    }
    @Test fun recorderBoundsEventsAndPairsAndStartsANewClearlyLabeledSession() {
        val recorder=AutoMixRecorder(maxAttempts=3,maxEvents=5)
        repeat(20) { val pair=recorder.begin("out-$it","in-$it");repeat(20) { recorder.record(pair,MixEvent.WINDOW_START) } }
        val history=recorder.snapshot()
        assertEquals(3,history.attempts.size);assertTrue(history.attempts.all { it.records.size==5 })
        assertNotEquals(history.session,AutoMixRecorder().snapshot().session)
        assertTrue(history.attempts.none { it.outgoingHash.contains("out-") })
    }
    @Test fun concurrentRecordingDoesNotLoseEventsAndPlansDoNotCountAsActualMixes() {
        val recorder=AutoMixRecorder(maxEvents=128);val pair=recorder.begin("out","in")
        val jobs=(0..3).map { Thread { repeat(20) { recorder.record(pair,MixEvent.PLAN,tier=AutoMixTier.A) } }.apply { start() } }
        jobs.forEach { it.join() }
        assertEquals(80,recorder.snapshot().attempts.single().records.size)
        val report=AutoMixRecorder.render(recorder.snapshot(),MixReportHeader("test",77,"test","fixture",true,AutoMixPerformance.BALANCED))
        assertTrue(report.contains("Started tier A: 0"))
    }
    @Test fun legacyFailuresMapToDistinctStableCategories() {
        assertEquals(AutoMixFailure.SOURCE_NOT_SEEKABLE,AutoMixFailure.from("source-not-range-seekable"))
        assertEquals(AutoMixFailure.TRACK_DURATION_UNKNOWN,AutoMixFailure.from("unknown-or-unsupported-duration"))
        assertEquals(AutoMixFailure.ANALYSIS_BYTE_BUDGET,AutoMixFailure.from("analysis-byte-budget-exhausted"))
        assertEquals(AutoMixFailure.ANALYSIS_TIMEOUT,AutoMixFailure.from("ANALYSIS_TIMEOUT"))
        assertEquals(AutoMixFailure.PREPARATION_FAILED,AutoMixFailure.from("deck-preparation-failed"))
        assertEquals(AutoMixFailure.DECODER_FAILED,AutoMixFailure.from("incoming-decoder-failed"))
        assertEquals(AutoMixFailure.TRANSITION_DEADLINE_MISSED,AutoMixFailure.from("missed-intelligent-boundary"))
    }
}
