package com.example.thinkv2.voice

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One producer and decoder consumer. At most 20 queued 100ms chunks (64KB PCM).
 * Buffer overload is an explicit error; no silent frame dropping. No audio file APIs. */
interface PcmSource : AutoCloseable { fun start();fun read(buffer: ShortArray): Int }

class VoiceCapture(private val open: ()->PcmSource,private val now: ()->Long={ System.nanoTime()/1000000 }) {
    private val cancelled=AtomicBoolean(false)
    private val released=AtomicBoolean(false)
    private val done=AtomicBoolean(false)
    @Volatile private var audibleSamples=0
    private val queue=ArrayBlockingQueue<ShortArray>(20)
    @Volatile private var problem: String?=null
    @Volatile var samples=0
        private set
    fun release() { released.set(true) }
    fun cancel() { cancelled.set(true);released.set(true) }
    val isCancelled get()=cancelled.get()
    fun run(decoder: VoiceDecoder,onCaptured: ()->Unit): String {
        val producer=Thread({ try { capture() } finally { try { runCatching(onCaptured) } finally { done.set(true) } } },"voice-memory-capture")
        producer.start()
        try {
            while(!done.get() || queue.isNotEmpty()) {
                if(cancelled.get()) return ""
                val chunk=queue.poll(100,TimeUnit.MILLISECONDS) ?: continue
                try { decoder.accept(chunk,chunk.size) } finally { chunk.fill(0) }
            }
            problem?.let { throw VoiceProblem(it) }
            if(cancelled.get() || audibleSamples<3200) return ""
            return decoder.finish()
        } finally {
            released.set(true)
            producer.join()
            while(true) { val chunk=queue.poll() ?: break;chunk.fill(0) }
        }
    }
    private fun capture() {
        var source: PcmSource?=null
        val buffer=ShortArray(VoiceBudget.CHUNK);val budget=VoiceBudget()
        try {
            if(released.get()) return
            source=open()
            if(released.get()) return
            source.start()
            val started=now()
            while(!released.get() && !budget.full && now()-started<90000L) {
                val n=source.read(buffer)
                if(n<0 || n>buffer.size) throw VoiceProblem("microphone")
                if(n==0) { Thread.sleep(10);continue }
                val count=budget.take(n);samples=budget.samples
                var energy=0L;for(i in 0 until count) energy+=buffer[i].toLong()*buffer[i]
                if(energy>count*100L*100L) audibleSamples+=count
                val chunk=buffer.copyOf(count);buffer.fill(0)
                if(!queue.offer(chunk)) { chunk.fill(0);throw VoiceProblem("overload") }
            }
        } catch(e: Exception) { problem=if(e is VoiceProblem) e.code else "microphone" }
        finally {
            buffer.fill(0)
            try { source?.close() } catch(_: Exception) { problem="cleanup" }
        }
    }
}
class VoiceProblem(val code: String): Exception("voice_failed")
