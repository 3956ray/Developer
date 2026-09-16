package com.example.thinkv2.voice

import android.Manifest
import android.net.LocalServerSocket
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextRange
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.notes.*
import com.example.thinkv2.ui.theme.ThinkV2Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean

class VoiceUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    /** Test-only constructor dependency supplies non-private RAM PCM to the real controller/engine. */
    @Test fun realChineseDraftCursorCorrectionCancelAndLateEdit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clips=LocalServerSocket("thinkv2-voice-ui").use { server -> server.accept().use { socket ->
            socket.soTimeout=60000;val input=DataInputStream(socket.inputStream)
            List(2) { val count=input.readInt();require(count in 1..480000);ByteArray(count*2).also(input::readFully) }
        } }
        val consumed=AtomicBoolean(false);var sequence=0
        var notes: NotesModel?=null;var voice: VoiceModel?=null
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
            ui.runOnUiThread {
                notes=NotesModel({ NoteRepository(AndroidSql(context,"voice-ui-synthetic.db")) })
                voice=VoiceModel(context) {
                    consumed.set(false);val pcm=clips[minOf(sequence++,1)]
                    VoiceCapture({ object: PcmSource {
                        var position=0;var began=0L
                        override fun start() { began=SystemClock.elapsedRealtime() }
                        override fun read(buffer: ShortArray): Int {
                            val count=minOf(buffer.size,pcm.size/2-position)
                            if(count==0) { consumed.set(true);Thread.sleep(10);return 0 }
                            val wait=began+(position+count)*1000L/16000-SystemClock.elapsedRealtime();if(wait>0) Thread.sleep(wait)
                            for(i in 0 until count) buffer[i]=((pcm[(position+i)*2].toInt() and 255) or (pcm[(position+i)*2+1].toInt() shl 8)).toShort()
                            position+=count;return count
                        }
                        override fun close() {}
                    } })
                }
            }
            val n=notes!!;val v=voice!!
            ui.setContent { ThinkV2Theme { NotesScreen(n,voice=v) } }
            ui.waitUntil(15000) { !n.state.loading }
            ui.onNodeWithText("新增文字").performClick()
            ui.onNodeWithText("正文").performTextInput("前后")
            ui.onNodeWithText("自动标题").performTextReplacement("保留手动标题")
            ui.onNodeWithText("正文").performScrollTo().performClick().performTextInputSelection(TextRange(1))
            ui.waitUntil(15000) { v.state.phase==VoicePhase.IDLE }
            fun start() { ui.onNodeWithText("按住说话").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() } }
            fun finish() {
                ui.waitUntil(30000) { consumed.get() }
                ui.onNodeWithText("录音中 · 松手处理").performSemanticsAction(SemanticsActions.OnClick) { it() }
                ui.waitUntil(15000) { v.state.phase==VoicePhase.IDLE }
            }
            start();finish()
            val first=ui.runOnIdle { n.state.editor!!.note.body }
            assertTrue(first.startsWith("前明天"));assertTrue(first.endsWith("水杯后"));assertEquals("保留手动标题",n.state.editor!!.note.title)
            val insertion=first.removePrefix("前").removeSuffix("后")
            start();finish()
            val secondBody=ui.runOnIdle { n.state.editor!!.note.body }
            assertTrue(secondBody.startsWith("前$insertion"));assertTrue(secondBody.endsWith("后"))
            val second=secondBody.removePrefix("前$insertion").removeSuffix("后")
            assertTrue(second.startsWith("李明"));assertTrue(second.contains("青岛"))
            assertEquals(-1L,n.state.editor!!.baseRevision)
            // Suggestions alter only the latest inserted span after explicit confirmation.
            ui.runOnIdle { v.saveMappings(listOf(Correction("李明","李明同学"))) }
            ui.waitUntil(5000) { v.state.suggestions.isNotEmpty() }
            ui.runOnIdle { v.correct(n,Correction("李明","李明同学")) }
            assertEquals("前$insertion${second.replace("李明","李明同学")}后",n.state.editor!!.note.body)
            val preserved=n.state.editor!!.note.body
            start();ui.waitUntil(5000) { v.state.phase==VoicePhase.RECORDING }
            ui.onNodeWithText("取消本次语音").performScrollTo().performClick()
            ui.waitUntil(5000) { v.state.phase==VoicePhase.IDLE };assertEquals(preserved,n.state.editor!!.note.body)
            start();ui.waitUntil(5000) { v.state.phase==VoicePhase.RECORDING }
            ui.runOnIdle { n.body(preserved+"新的手工编辑") }
            ui.waitUntil(5000) { v.state.phase==VoicePhase.IDLE }
            assertEquals(preserved+"新的手工编辑",n.state.editor!!.note.body)
            // Leaving the editor disposes controls and cancels its session, preserving draft only.
            start();ui.runOnIdle { n.back() }
            ui.waitUntil(5000) { !n.state.busy && !n.state.loading && n.state.editor==null && v.state.phase==VoicePhase.IDLE }
            assertTrue(n.state.notes.isEmpty());assertEquals(1,n.state.drafts.size)
        } finally {
            clips.forEach { it.fill(0) }
            ui.runOnUiThread {
                voice?.cancel()
                // Same ViewModel lifecycle cleanup without exposing a production test endpoint.
                val store=androidx.lifecycle.ViewModelStore()
                val provider=androidx.lifecycle.ViewModelProvider(store,object: androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return (if(modelClass==VoiceModel::class.java) voice!! else notes!!) as T
                    }
                })
                if(voice!=null) provider[VoiceModel::class.java]
                if(notes!=null) provider[NotesModel::class.java]
                store.clear()
            }
        }
    }
}
