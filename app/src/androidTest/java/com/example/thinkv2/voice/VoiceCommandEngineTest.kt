package com.example.thinkv2.voice

import android.Manifest
import android.net.LocalServerSocket
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.notes.*
import com.example.thinkv2.ui.theme.ThinkV2Theme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.DataInputStream
import java.security.MessageDigest

/** Actual admitted Vosk, command controller and Compose confirmations; synthetic PCM is RAM-only. */
class VoiceCommandEngineTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    @Test fun realEngineCommandsAndOrdinaryDictationThroughActualConfirmationUi() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val identities=JSONArray();val clips=mutableListOf<ByteArray>()
        LocalServerSocket("thinkv2-voice-commands-ui").use { server -> server.accept().use { socket ->
            socket.soTimeout=120000;val input=DataInputStream(socket.inputStream);assertEquals(4,input.readInt())
            repeat(4) { index ->
                val bytes=input.readInt();require(bytes in 1..8192);val header=ByteArray(bytes);input.readFully(header)
                val identity=JSONObject(String(header,Charsets.UTF_8));assertEquals(listOf("CMD01","CMD02","CMD03","NEG01")[index],identity.getString("id"))
                val count=input.readInt();require(count in 1..480000);val pcm=ByteArray(count*2);input.readFully(pcm);clips+=pcm
                val sha=MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it.toInt() and 255) }
                identities.put(identity.put("samples",count).put("pcmSha256",sha))
            }
        } }
        val store=ViewModelStore();val results=JSONArray();var selected=0
        val name="voice-controls-real.db";context.deleteDatabase(name)
        lateinit var notes: NotesModel;lateinit var voice: VoiceModel
        ins.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        try {
            ui.runOnUiThread {
                val provider=ViewModelProvider(store,object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T =
                        (if(modelClass==NotesModel::class.java) NotesModel({ NoteRepository(AndroidSql(context,name)) }) else VoiceModel(context,createCapture={
                            val pcm=clips[selected];lateinit var capture: VoiceCapture
                            capture=VoiceCapture({ object: PcmSource {
                                var position=0;var start=0L
                                override fun start() { start=SystemClock.elapsedRealtime() }
                                override fun read(buffer: ShortArray): Int {
                                    val count=minOf(buffer.size,pcm.size/2-position)
                                    if(count==0) { capture.release();return 0 }
                                    val delay=start+(position+count)*1000L/VoiceBudget.RATE-SystemClock.elapsedRealtime();if(delay>0) Thread.sleep(delay)
                                    for(i in 0 until count) buffer[i]=((pcm[(position+i)*2].toInt() and 255) or (pcm[(position+i)*2+1].toInt() shl 8)).toShort()
                                    position+=count;if(position==pcm.size/2) capture.release();return count
                                }
                                override fun close() {}
                            } });capture
                        })) as T
                })
                notes=provider[NotesModel::class.java];voice=provider[VoiceModel::class.java]
            }
            ui.setContent { ThinkV2Theme { NotesScreen(notes,voice=voice) } }
            ui.waitUntil(15000) { !notes.state.loading };ui.runOnIdle { notes.newNote();notes.body("真实命令前的合成草稿");notes.title("保留原人工标题") }
            ui.waitUntil(20000) { voice.state.phase==VoicePhase.IDLE && notes.state.draftState==DraftState.SAVED }
            fun speak(index: Int,commands: Boolean=true): VoiceCommandPreview? {
                selected=index
                if(commands) ui.onNodeWithText("进入命令模式").performScrollTo().performClick()
                ui.runOnIdle { voice.start(notes,null) }
                ui.waitUntil(30000) { voice.state.phase==VoicePhase.IDLE }
                results.put(JSONObject().put("id",identities.getJSONObject(index).getString("id")).put("mode",if(commands) "command" else "dictation")
                    .put("recognized",voice.state.command?.recognized ?: voice.state.message).put("intent",voice.state.command?.intent?.name ?: JSONObject.NULL))
                return voice.state.command
            }
            fun image(name: String) { ui.onNodeWithText("确认语音命令").assertIsDisplayed();ui.waitForIdle();ins.uiAutomation.waitForIdle(300,5000);ins.uiAutomation.takeScreenshot()?.let { bitmap ->context.getFileStreamPath("voice-controls-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() } }
            val old=notes.state.editor!!.note
            assertEquals(VoiceCommand.NEW,speak(0)!!.intent);assertEquals(old,notes.state.editor!!.note);image("real-new-confirm")
            ui.onNodeWithText("确认新建笔记").performClick();ui.waitUntil(10000) { !notes.state.busy && notes.state.editor?.note?.id?.let { it!=old.id }==true }
            AndroidSql(context,name).use { db ->assertEquals(old.body,db.query("SELECT body FROM drafts WHERE id=?",listOf(old.id)).single().single()) }
            ui.runOnIdle { notes.body("真实语音命令保存的合成正文");notes.title("实际确认保存的手动标题") }
            val savedId=notes.state.editor!!.note.id
            assertEquals(VoiceCommand.SAVE,speak(1)!!.intent);AndroidSql(context,name).use { assertTrue(it.query("SELECT * FROM notes").isEmpty()) };image("real-save-confirm")
            ui.onNodeWithText("确认保存为笔记").performClick();ui.waitUntil(10000) { notes.state.editor==null && !notes.state.loading && !notes.state.busy }
            ui.runOnIdle { notes.open(savedId) };ui.waitUntil(10000) { notes.state.editor!=null && !notes.state.busy }
            val before=notes.state.editor!!.note
            assertEquals(VoiceCommand.CANCEL,speak(2)!!.intent);image("real-cancel-confirm");ui.onNodeWithText("确认取消本次输入").performClick();assertEquals(before,notes.state.editor!!.note)
            assertNull(speak(3));assertTrue(voice.state.message.contains("未匹配"));assertEquals(before,notes.state.editor!!.note)
            ui.onNodeWithText("退出命令模式").performScrollTo().performClick()
            assertNull(speak(1,false));assertEquals(before.body+"保存 当前 草稿",notes.state.editor!!.note.body)
            results.put(JSONObject().put("ordinaryInsertedText",notes.state.editor!!.note.body.removePrefix(before.body)))
            ui.runOnIdle { notes.back() };ui.waitUntil(10000) { !notes.state.busy && notes.state.editor==null }
            AndroidSql(context,name).use { db ->
                assertEquals(listOf(listOf(before.body,before.title)),db.query("SELECT body,title FROM notes"))
                assertEquals(before.body+"保存 当前 草稿",db.query("SELECT body FROM drafts WHERE id=?",listOf(savedId)).single().single())
                val tables=listOf("notes","drafts","categories","calendar_imports","ai_acceptances","note_relations","correction_vocabulary")
                val snapshot=JSONObject();tables.forEach { table ->snapshot.put(table,JSONArray(db.query("SELECT * FROM $table ORDER BY 1").map { JSONArray(it) })) }
                context.getFileStreamPath("voice-controls-real-database.json").writeText(snapshot.toString(2))
            }
            context.getFileStreamPath("voice-controls-real-engine-ui.json").writeText(JSONObject().put("runtime","admitted Vosk; no grammar/correction").put("inputPath","explicit synthetic PCM in RAM, wall-clock paced product VoiceCapture; not physical microphone").put("identities",identities).put("results",results).put("threeConfirmedActions",true).put("unknownNoAction",true).put("ordinaryCommandLikeSpeechOnlyText",true).toString(2))
        } finally { clips.forEach { it.fill(0) };ui.runOnUiThread { store.clear() } }
    }
}
