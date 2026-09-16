package com.example.thinkv2.voice

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.notes.*
import com.example.thinkv2.ui.theme.ThinkV2Theme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Scripted decoder + non-speech RAM input: state/persistence evidence only, never ASR accuracy evidence. */
class VoiceCommandStateTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val raw=AtomicReference("保存当前草稿")
    private var block: CountDownLatch?=null
    private var entered: CountDownLatch?=null
    private fun capture(): VoiceCapture {
        lateinit var capture: VoiceCapture
        capture=VoiceCapture({ object: PcmSource {
            var count=0
            override fun start() {}
            override fun read(buffer: ShortArray): Int {
                if(count>=6400) { capture.release();return 0 }
                buffer.fill(1200);count+=buffer.size;return buffer.size
            }
            override fun close() {}
        } });return capture
    }
    private fun scenario(test: (NotesModel,VoiceModel,String)->Unit) {
        val name="voice-command-state.db";context.deleteDatabase(name)
        ins.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val store=ViewModelStore();lateinit var n: NotesModel;lateinit var v: VoiceModel
        ui.runOnUiThread {
            val provider=ViewModelProvider(store,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T =
                    (if(modelClass==NotesModel::class.java) NotesModel({ NoteRepository(AndroidSql(context,name)) }) else VoiceModel(context,testDecoder={ object: VoiceDecoder {
                        val text=raw.get();override fun accept(samples: ShortArray,count: Int) {}
                        override fun finish(): String { entered?.countDown();check(block?.await(10,TimeUnit.SECONDS)!=false);return text }
                        override fun close() {}
                    } },createCapture={ capture() })) as T
            })
            n=provider[NotesModel::class.java];v=provider[VoiceModel::class.java]
        }
        try {
            ui.setContent { ThinkV2Theme { NotesScreen(n,voice=v) } }
            ui.waitUntil(15000) { !n.state.loading };ui.runOnIdle { n.newNote();n.body("既有正文");n.title("保留手动标题");n.category("保留分类") }
            ui.waitUntil(20000) { v.state.phase==VoicePhase.IDLE && n.state.draftState==DraftState.SAVED }
            test(n,v,name)
        } finally { block?.countDown();ui.runOnUiThread { store.clear() } }
    }
    private fun speak(n: NotesModel,v: VoiceModel,text: String,commands: Boolean=true): VoiceCommandPreview? {
        raw.set(text)
        ui.runOnIdle { if(commands) v.enterCommands(n);v.start(n,null) }
        ui.waitUntil(10000) { v.state.phase==VoicePhase.IDLE };return v.state.command
    }
    private fun data(name: String): List<List<List<String>>> = AndroidSql(context,name).use { db -> listOf("notes","drafts","categories","calendar_imports","ai_acceptances","note_relations","correction_vocabulary").map { db.query("SELECT * FROM $it ORDER BY 1") } }
    private fun result(name: String,value: JSONObject) { context.getFileStreamPath("voice-controls-$name.json").writeText(value.put("recognitionEvidence","scripted decoder; not real ASR").toString(2)) }
    @Test fun ordinaryTextEmptySaveDismissCancelAndConfirmedActionsPreserveDurableFields()=scenario { n,v,name ->
        val id=n.state.editor!!.note.id
        assertNull(speak(n,v,"保存当前草稿",false));assertEquals("既有正文保存当前草稿",n.state.editor!!.note.body);assertEquals(-1L,n.state.editor!!.baseRevision)
        ui.runOnIdle { n.body("") };assertNull(speak(n,v,"保存当前草稿"));assertTrue(v.state.message.contains("正文为空"))
        ui.runOnIdle { v.cancel();n.body("非空正文仍未正式保存") };ui.waitUntil(10000) { n.state.draftState==DraftState.SAVED }
        val before=data(name);assertNotNull(speak(n,v,"保存当前草稿"));assertEquals(before,data(name))
        ui.onNodeWithText("不执行此命令").performClick();assertEquals(before,data(name));assertEquals(id,n.state.editor!!.note.id)
        val cancel=speak(n,v,"取消本次输入")!!;ui.onNodeWithText("确认取消本次输入").performClick();assertEquals(before,data(name));assertFalse(v.state.commandMode)
        ui.runOnIdle { v.confirmCommand(n,cancel) };assertEquals(before,data(name))
        val create=speak(n,v,"新建笔记")!!;ui.onNodeWithText("确认新建笔记").performClick()
        ui.waitUntil(10000) { !n.state.busy && n.state.editor?.note?.id!=id };assertTrue(n.state.editor!!.note.body.isEmpty())
        AndroidSql(context,name).use { db ->assertEquals(listOf("非空正文仍未正式保存","保留手动标题","保留分类"),db.query("SELECT body,title,category FROM drafts WHERE id=?",listOf(id)).single()) }
        ui.runOnIdle { v.confirmCommand(n,create);n.body("本次正式保存正文");n.title("新手动标题") }
        val save=speak(n,v,"保存当前草稿")!!
        ui.onNodeWithText("确认保存为笔记").performClick();ui.runOnIdle { v.confirmCommand(n,save) }
        ui.waitUntil(10000) { n.state.editor==null && !n.state.loading && !n.state.busy }
        AndroidSql(context,name).use { db ->assertEquals(listOf(listOf("本次正式保存正文","新手动标题")),db.query("SELECT body,title FROM notes"));assertEquals(1,db.query("SELECT id FROM drafts WHERE id=? AND active=1",listOf(id)).size) }
        result("actions",JSONObject().put("ordinaryCommandLikeTextOnly",true).put("emptySaveRejected",true).put("dismissNoWrites",true).put("cancelPreservesExisting",true).put("newPreservesPriorDraft",true).put("confirmedSaveExactlyOnce",true))
    }
    @Test fun unknownEditingRepeatedCaptureAndLateCompletionNeverExecuteStaleIntent()=scenario { n,v,name ->
        val initial=data(name);assertNull(speak(n,v,"请新建笔记"));assertEquals(initial,data(name));assertTrue(v.state.message.contains("未匹配"))
        val old=speak(n,v,"保存当前草稿")!!
        val replacement=speak(n,v,"取消本次输入")!!;ui.runOnIdle { v.confirmCommand(n,old) };assertSame(replacement,v.state.command)
        ui.runOnIdle { n.body("等待确认时手工编辑") };ui.waitUntil(5000) { v.state.command==null };ui.runOnIdle { v.confirmCommand(n,replacement) };assertEquals("等待确认时手工编辑",n.state.editor!!.note.body)
        block=CountDownLatch(1);entered=CountDownLatch(1);raw.set("新建笔记")
        ui.runOnIdle { v.enterCommands(n);v.start(n,null) };assertTrue(entered!!.await(5,TimeUnit.SECONDS));val id=n.state.editor!!.note.id
        ui.runOnIdle { n.body("处理过程中手工编辑") };ui.waitUntil(5000) { !v.state.commandMode };block!!.countDown();ui.waitUntil(5000) { v.state.phase==VoicePhase.IDLE }
        assertNull(v.state.command);assertEquals(id,n.state.editor!!.note.id);assertEquals("处理过程中手工编辑",n.state.editor!!.note.body)
        block=null;entered=null
        val stale=speak(n,v,"新建笔记")!!;ui.runOnIdle { n.back() };ui.waitUntil(10000) { n.state.editor==null && !n.state.loading };ui.runOnIdle { n.newNote();n.body("另一笔记");v.confirmCommand(n,stale) };assertEquals("另一笔记",n.state.editor!!.note.body)
        result("stale",JSONObject().put("unknownNoWrites",true).put("repeatedIntentInvalidatesPrevious",true).put("editInvalidatesConfirmation",true).put("lateResultDiscarded",true).put("differentEditorProtected",true))
    }
    @Test fun failedDraftAndFormalWritesKeepCurrentEditorAndRetryNeedsNewConfirmation()=scenario { n,v,name ->
        val id=n.state.editor!!.note.id;val body=n.state.editor!!.note.body
        AndroidSql(context,name).use { it.execute("CREATE TRIGGER command_fail_draft BEFORE INSERT ON drafts BEGIN SELECT RAISE(ABORT,'synthetic_failure'); END") }
        val create=speak(n,v,"新建笔记")!!;ui.onNodeWithText("确认新建笔记").performClick();ui.waitUntil(10000) { !n.state.busy && n.state.error!=null }
        assertEquals(id,n.state.editor!!.note.id);assertEquals(body,n.state.editor!!.note.body);assertNull(v.state.command)
        ui.runOnIdle { v.confirmCommand(n,create) };assertEquals(id,n.state.editor!!.note.id)
        AndroidSql(context,name).use { it.execute("DROP TRIGGER command_fail_draft");it.execute("CREATE TRIGGER command_fail_save BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT,'synthetic_failure'); END") }
        assertNotNull(speak(n,v,"保存当前草稿"));ui.onNodeWithText("确认保存为笔记").performClick();ui.waitUntil(10000) { !n.state.busy && n.state.error!=null }
        assertEquals(id,n.state.editor!!.note.id);assertEquals(body,n.state.editor!!.note.body)
        AndroidSql(context,name).use { assertTrue(it.query("SELECT * FROM notes").isEmpty());it.execute("DROP TRIGGER command_fail_save") }
        assertNotNull(speak(n,v,"保存当前草稿"));ui.onNodeWithText("确认保存为笔记").performClick();ui.waitUntil(10000) { n.state.editor==null && !n.state.busy }
        AndroidSql(context,name).use { assertEquals(body,it.query("SELECT body FROM notes WHERE id=?",listOf(id)).single().single()) }
        result("write-failure",JSONObject().put("newDraftFailureNoSwitch",true).put("formalSaveFailureNoSuccess",true).put("editableBodyRetained",true).put("oldConfirmationConsumed",true).put("freshConfirmationRetryCommits",true))
    }

    @Test fun commandEntryExitAndCancellationRetainEarlierCorrectionSuggestions()=scenario { n,v,_ ->
        assertNull(speak(n,v,"李明",false));val body=n.state.editor!!.note.body
        val correction=Correction("李明","李明同学")
        ui.runOnIdle { v.saveMappings(listOf(correction)) };ui.waitUntil(10000) { correction in v.state.suggestions }
        ui.onNodeWithText("进入命令模式").performScrollTo().performClick();assertTrue(v.state.commandMode)
        ui.onNodeWithText("退出命令模式").performScrollTo().performClick();assertFalse(v.state.commandMode);assertEquals(listOf(correction),v.state.suggestions)
        assertNotNull(speak(n,v,"取消本次输入"));ui.onNodeWithText("确认取消本次输入").performClick();assertEquals(body,n.state.editor!!.note.body);assertEquals(listOf(correction),v.state.suggestions)
        assertNotNull(speak(n,v,"保存当前草稿"));ui.runOnIdle { v.permissionDenied() };assertNull(v.state.command);assertFalse(v.state.commandMode);assertEquals(body,n.state.editor!!.note.body)
        ui.runOnIdle { v.correct(n,correction) };assertEquals(body.replace("李明","李明同学"),n.state.editor!!.note.body)
        result("correction-preservation",JSONObject().put("explicitModeEntryExit",true).put("priorCorrectionPreserved",true).put("cancelDoesNotErasePreviousInput",true).put("permissionCallbackInvalidatesPending",true))
    }

    @Test fun commandNewPreservesExistingReminderCalendarAiRelationsAndBackupData()=scenario { n,v,name ->
        val id=n.state.editor!!.note.id;ui.runOnIdle { n.save() };ui.waitUntil(10000) { n.state.editor==null && !n.state.busy && !n.state.loading }
        ui.runOnIdle { n.open(id) };ui.waitUntil(10000) { n.state.editor!=null && !n.state.busy }
        lateinit var before: ByteArray
        AndroidSql(context,name).use { db ->
            val repository=NoteRepository(db);val other=repository.save(Editing(Note(id="linked-original").bodyChanged("另一个已有笔记")))
            com.example.thinkv2.relations.RelationRepository(db).create(id,other.id,1)
            com.example.thinkv2.reminders.ReminderRepository(db).save(id,-1,com.example.thinkv2.reminders.ReminderRule(com.example.thinkv2.reminders.Repeat.DAILY,com.example.thinkv2.reminders.LocalDay.parse("2030-01-01"),9,0),true,"")
            val event=com.example.thinkv2.calendar.CalendarEvent(com.example.thinkv2.calendar.CalendarRef(mapOf("_id" to "3")),mapOf("_id" to "4","title" to "合成原始来源"),emptyList())
            db.execute("INSERT INTO calendar_imports VALUES (?,?,?,?,?,?)",listOf(event.key,event.fingerprint,id,event.payload,event.originalText(),"a".repeat(64)))
            db.execute("INSERT INTO ai_acceptances VALUES ('source-ai','source-request',?,'title','https://example.invalid/chat/completions','synthetic',?,'合成建议','保留手动标题',1,1)",listOf(id,"b".repeat(64)))
            before=com.example.thinkv2.backup.BackupRepository(db).export().payload
        }
        val command=speak(n,v,"新建笔记")!!;ui.onNodeWithText("确认新建笔记").performClick();ui.waitUntil(10000) { !n.state.busy && n.state.editor?.note?.id?.let { it!=id }==true }
        ui.runOnIdle { v.confirmCommand(n,command) }
        AndroidSql(context,name).use { db ->assertArrayEquals(before,com.example.thinkv2.backup.BackupRepository(db).export().payload);assertEquals("1",db.query("SELECT requested FROM reminders WHERE note_id=?",listOf(id)).single().single()) }
        result("existing-feature-preservation",JSONObject().put("backupPayloadExactBeforeAfter",true).put("existingReminderStillEnabled",true).put("calendarAiRelationsUnchanged",true).put("schemaAndBackupUnchanged",true))
    }
}
