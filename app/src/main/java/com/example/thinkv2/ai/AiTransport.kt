package com.example.thinkv2.ai

import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** No redirect, retry, alternate provider, custom trust manager or credential-bearing diagnostics. */
class AiCall internal constructor(private val open: (URL)->HttpsURLConnection = { it.openConnection() as HttpsURLConnection },private val deadlineMs: Long=AiProtocol.TIMEOUT_MS) {
    private val cancelled=AtomicBoolean(false)
    @Volatile private var connection: HttpsURLConnection?=null
    @Volatile private var timedOut=false
    fun cancel() { if(cancelled.compareAndSet(false,true)) connection?.let { c -> Thread({ c.disconnect() },"ai-request-cancel").start() } }
    private fun active() { if(cancelled.get()) throw AiFailure(if(timedOut) "timeout" else "cancelled") }
    fun execute(config: AiConfig,payload: ByteArray): ByteArray {
        aiCheck(config.enabled,"disabled");aiCheck(payload.size<=AiProtocol.REQUEST_BYTES,"input_limit");active()
        val timer=Executors.newSingleThreadScheduledExecutor()
        val deadline=timer.schedule({ timedOut=true;cancel() },deadlineMs,TimeUnit.MILLISECONDS)
        try {
            val c=open(URL(config.endpoint));connection=c;active()
            c.instanceFollowRedirects=false;c.useCaches=false;c.connectTimeout=AiProtocol.CONNECT_MS;c.readTimeout=AiProtocol.READ_MS
            c.requestMethod="POST";c.doOutput=true;c.setFixedLengthStreamingMode(payload.size)
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("Accept","application/json")
            c.setRequestProperty("Authorization","Bearer ${config.credential}")
            active();c.outputStream.use { it.write(payload) };active()
            val status=c.responseCode;active()
            aiCheck(c.url.toExternalForm()==config.endpoint,"redirect")
            when(status) {
                in 300..399 -> throw AiFailure("redirect")
                401,403 -> throw AiFailure("credentials")
                429 -> throw AiFailure("rate_limit")
                in 500..599 -> throw AiFailure("service")
                200 -> Unit
                else -> throw AiFailure("http")
            }
            aiCheck(c.contentType?.substringBefore(';')?.trim()?.equals("application/json",true)==true,"invalid_response")
            aiCheck(c.contentLengthLong<=AiProtocol.RESPONSE_BYTES,"response_limit")
            val out=ByteArrayOutputStream()
            c.inputStream.use { input -> val buffer=ByteArray(4096);while(true) { active();val n=input.read(buffer);if(n<0) break;aiCheck(out.size()+n<=AiProtocol.RESPONSE_BYTES,"response_limit");out.write(buffer,0,n) } }
            active();return out.toByteArray()
        } catch(e: AiFailure) { throw e }
        catch(_: SocketTimeoutException) { throw AiFailure("timeout") }
        catch(_: Exception) { active();throw AiFailure("network") }
        finally { deadline.cancel(false);timer.shutdownNow();connection?.disconnect();connection=null }
    }
}
