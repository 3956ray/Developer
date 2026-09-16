package com.example.thinkv2.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceCaptureTest {
    private class Decoder: VoiceDecoder {
        var frames=0;var finished=false;var fail=false
        override fun accept(samples: ShortArray,count: Int) { if(fail) error("synthetic");frames+=count }
        override fun finish(): String { finished=true;return "synthetic decoder output" }
        override fun close() {}
    }
    @Test(timeout=3000) fun cleanupExceptionStillNotifiesAndTerminates() {
        var notified=false
        val capture=VoiceCapture({ object: PcmSource {
            override fun start() { error("start failure") }
            override fun read(buffer: ShortArray)=0
            override fun close() { error("release failure") }
        } })
        try { capture.run(Decoder()) { notified=true };fail() } catch(e: VoiceProblem) { assertEquals("cleanup",e.code) }
        assertTrue(notified)
    }
    @Test(timeout=3000) fun cancelBeforeWorkerNeverOpensMicrophone() {
        var opened=false;val capture=VoiceCapture({ opened=true;error("must not open") })
        capture.cancel();assertEquals("",capture.run(Decoder()) {});assertFalse(opened)
    }
    @Test(timeout=3000) fun releaseDuringOpenNeverStartsAndClosesResource() {
        var started=false;var closed=false;lateinit var capture: VoiceCapture
        capture=VoiceCapture({ capture.release();object: PcmSource {
            override fun start() { started=true }
            override fun read(buffer: ShortArray)=0
            override fun close() { closed=true }
        } })
        assertEquals("",capture.run(Decoder()) {});assertFalse(started);assertTrue(closed)
    }
    @Test(timeout=3000) fun decoderFailureStopsProducerAndWipesOwnedSamples() {
        var released=false;lateinit var bufferReference: ShortArray
        val capture=VoiceCapture({ object: PcmSource {
            override fun start() {}
            override fun read(buffer: ShortArray): Int { bufferReference=buffer;buffer.fill(900);Thread.sleep(2);return buffer.size }
            override fun close() { released=true }
        } })
        try { capture.run(Decoder().also { it.fail=true }) {};fail() } catch(_: IllegalStateException) {}
        assertTrue(released);assertTrue(bufferReference.all { it==0.toShort() })
    }
    @Test(timeout=3000) fun permissionLossIsFailureAndCloses() {
        var closed=false;val capture=VoiceCapture({ object: PcmSource {
            override fun start() {}
            override fun read(buffer: ShortArray): Int=throw VoiceProblem("permission")
            override fun close() { closed=true }
        } })
        try { capture.run(Decoder()) {};fail() } catch(e: VoiceProblem) { assertEquals("permission",e.code) }
        assertTrue(closed)
    }
    @Test(timeout=5000) fun exactNinetySecondsStopsAtSampleBoundary() {
        val decoder=Decoder();var closed=false
        val capture=VoiceCapture({ object: PcmSource {
            override fun start() {}
            override fun read(buffer: ShortArray): Int { buffer.fill(1000);Thread.sleep(1);return buffer.size }
            override fun close() { closed=true }
        } })
        assertEquals("synthetic decoder output",capture.run(decoder) {})
        assertEquals(1440000,decoder.frames);assertEquals(1440000,capture.samples);assertTrue(closed)
    }
    @Test(timeout=3000) fun silentInputNeverUsesDecoderText() {
        val decoder=Decoder();var tick=0L
        val capture=VoiceCapture({ object: PcmSource {
            override fun start() {}
            override fun read(buffer: ShortArray): Int { buffer.fill(0);return buffer.size }
            override fun close() {}
        } },{ tick+=30000;tick })
        assertEquals("",capture.run(decoder) {});assertFalse(decoder.finished)
    }
    @Test(timeout=3000) fun completionWaitsForProcessingNotificationAndCallbackFailureStillEnds() {
        val entered=CountDownLatch(1);val allow=CountDownLatch(1);val finished=CountDownLatch(1)
        val capture=VoiceCapture({ object: PcmSource {
            override fun start() { throw VoiceProblem("synthetic") }
            override fun read(buffer: ShortArray)=0
            override fun close() {}
        } })
        val thread=Thread {
            try { capture.run(Decoder()) { entered.countDown();allow.await();error("notification failure") } } catch(_: VoiceProblem) {} finally { finished.countDown() }
        }
        thread.start();assertTrue(entered.await(1,TimeUnit.SECONDS));assertFalse(finished.await(50,TimeUnit.MILLISECONDS))
        allow.countDown();assertTrue(finished.await(1,TimeUnit.SECONDS));thread.join()
    }
    @Test fun budgetAndUnicodeAndAmbiguousSuggestions() {
        val budget=VoiceBudget();assertEquals(VoiceBudget.LIMIT-1,budget.take(VoiceBudget.LIMIT-1));assertEquals(1,budget.take(20));assertTrue(budget.full);assertEquals(0,budget.take(1))
        assertEquals("a中😀b",VoiceText.insert("a😀b",2,"中"))
        val rows=listOf(Correction("立明","李明"),Correction("立明","黎明"));VoiceText.validate(rows)
        assertEquals(rows,VoiceText.suggestions("立明去了公园",rows))
    }
}
