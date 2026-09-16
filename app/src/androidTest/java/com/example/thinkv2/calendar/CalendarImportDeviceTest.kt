package com.example.thinkv2.calendar

import android.Manifest
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.MainActivity
import com.example.thinkv2.notes.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Only the separately permissioned fixture APK writes synthetic source calendars. */
class CalendarImportDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun shell(command: String): String {
        val fds=instrumentation.uiAutomation.executeShellCommandRwe(command)
        fds[1].close()
        val stderr=java.util.concurrent.FutureTask {
            ParcelFileDescriptor.AutoCloseInputStream(fds[2]).bufferedReader().use { it.readText() }
        }
        Thread(stderr,"synthetic-fixture-stderr").start()
        val stdout=ParcelFileDescriptor.AutoCloseInputStream(fds[0]).bufferedReader().use { it.readText() }
        val error=stderr.get()
        check(error.isBlank()) { "fixture_stderr: ${error.take(4000)}" }
        return stdout
    }
    private fun fixture(method: String,arg: String=""): JSONObject {
        val callId=java.util.UUID.randomUUID().toString()
        val response=shell("content call --uri content://com.example.thinkv2.test.calendar.fixture --method $method --extra call_id:s:$callId"+(if(arg.isNotEmpty()) " --arg $arg" else ""))
        check(response.contains("synthetic result saved $callId")) { "fixture_call_failed: ${response.take(2000)}" }
        val envelope=JSONObject(shell("run-as com.example.thinkv2.test cat files/calendar-fixture-result.json"))
        check(envelope.getString("callId")==callId && envelope.getString("method")==method) { "stale_fixture_result" }
        return envelope.getJSONObject("result")
    }
    private fun save(name: String,text: String) { context.getFileStreamPath("calendar-$name.json").writeText(text) }
    private fun model()=MainActivity::class.java.getDeclaredField("calendarImport").apply { isAccessible=true }.get(ui.activity) as CalendarImportModel
    private fun store(block: (NoteRepository,AndroidSql)->Unit) { AndroidSql(context).use { db ->val notes=NoteRepository(db);notes.initialize();block(notes,db) } }
    private fun waitIdle()=ui.waitUntil(15000) { !model().state.busy }
    private fun visible(text: String) { ui.onNodeWithTag("calendar-list").performScrollToNode(hasText(text,substring=true)) }
    private fun enter() {
        ui.waitUntil(10000) { ui.onAllNodesWithText("从日历导入").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("从日历导入").performClick()
        visible("读取可选日历");ui.onNodeWithText("读取可选日历").performClick();waitIdle()
        visible("合成测试日历");ui.onNode(hasText("合成测试日历",substring=true) and hasClickAction()).performClick()
        visible("开始日期 YYYY-MM-DD");ui.onNodeWithText("开始日期 YYYY-MM-DD").performTextReplacement("2026-09-01")
        visible("结束日期 YYYY-MM-DD（含当天）");ui.onNodeWithText("结束日期 YYYY-MM-DD（含当天）").performTextReplacement("2026-09-07");Espresso.closeSoftKeyboard()
    }
    private fun preview() { visible("读取事件预览");ui.onNodeWithText("读取事件预览").performClick();waitIdle();assertNull(model().state.error) }
    private fun confirm() {
        ui.onNodeWithTag("calendar-list").performScrollToNode(hasContentDescription("确认已核对来源身份"))
        ui.onNodeWithContentDescription("确认已核对来源身份").performClick()
        visible("确认导入所选事件");ui.onNodeWithText("确认导入所选事件").performClick();waitIdle()
    }
    @Test fun actualProviderUiFaithfulImportCancelIdempotenceAndSourceUnchanged() {
        val requested=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(requested.contains(Manifest.permission.READ_CALENDAR));assertFalse(requested.contains(Manifest.permission.WRITE_CALENDAR));assertFalse(requested.contains(Manifest.permission.INTERNET))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.READ_CALENDAR)
        val seed=fixture("seed");assertNotEquals(context.applicationInfo.uid,seed.getInt("fixtureUid"));assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR));val before=fixture("snapshot").toString();save("source-before",before)
        store { n,_ ->n.createCategory("日历合成分类") }
        enter();preview()
        val p=model().state.preview!!;assertEquals(10,p.rows.size);assertEquals(9,p.rows.count { it.event.importable })
        assertEquals(1,p.rows.count { it.event.repeating && !it.event.exception });assertEquals(2,p.rows.count { it.event.exception })
        assertTrue(p.rows.single { it.event.title=="范围前开始的每日系列" }.event.fields["dtstart"]!!.toLong()<p.source.range.begin)
        assertEquals("2",p.rows.single { it.event.title=="合成取消例外" }.event.fields["eventStatus"])
        visible("取消预览");ui.onNodeWithText("取消预览").performClick();store { n,_ ->assertEquals(0,n.search("").total) }
        preview();visible("选择分类：日历合成分类");ui.onNodeWithText("选择分类：日历合成分类").performClick();confirm();assertNull(model().state.error)
        store { notes,db ->
            assertEquals(9,notes.search("").total);assertEquals(9,db.query("SELECT * FROM calendar_imports").size);assertTrue(db.query("SELECT * FROM reminders").isEmpty())
            for(row in p.rows.filter { it.event.importable }) {
                val map=db.query("SELECT note_id,original_payload,original_text FROM calendar_imports WHERE source_key=? AND fingerprint=?",listOf(row.event.key,row.event.fingerprint)).single()
                val n=notes.find(map[0])!!;assertEquals("日历合成分类",n.category);assertEquals(row.event.title,n.title);assertEquals(row.event.noteBody(),n.body);assertEquals(row.event.payload,map[1]);assertEquals(row.event.originalText(),map[2])
            }
            save("local-after",canonical(listOf("notes","drafts","categories","calendar_imports","reminders").associateWith { db.query("SELECT * FROM $it ORDER BY 1") }))
        }
        val after=fixture("snapshot").toString();assertEquals(before,after);save("source-after",after)
        val chinese=p.rows.single { it.event.title=="合成中文事件" }.event
        lateinit var oldId: String
        store { _,db ->oldId=db.query("SELECT note_id FROM calendar_imports WHERE source_key=?",listOf(chinese.key)).single().single() }
        ui.onNodeWithText("返回笔记").performClick()
        ui.onNodeWithTag("notes-list").performScrollToNode(hasText("合成中文事件"));ui.onNodeWithText("合成中文事件").performClick()
        ui.onNodeWithText("查看日历原始快照").performScrollTo().performClick();ui.onNodeWithText("关闭").performClick()
        ui.onNodeWithText("正文").performScrollTo().performTextReplacement("本机未正式保存的正文")
        ui.onNodeWithText("手动标题").performScrollTo().performTextReplacement("本机人工标题")
        ui.onNodeWithText("返回 · 保留草稿").performClick()
        val editedSource=fixture("edit").toString();save("source-edited-before-copy",editedSource)
        lateinit var oldRows: List<List<List<String>>>
        store { _,db ->oldRows=listOf("notes","drafts","calendar_imports").map { table ->db.query("SELECT * FROM $table WHERE ${if(table=="calendar_imports") "note_id" else "id"}=?",listOf(oldId)) } }
        enter();preview()
        assertEquals(1,model().state.preview!!.rows.count { it.action==CalendarAction.CHANGED });assertTrue(model().state.preview!!.rows.single { it.event.key==chinese.key }.localChanged)
        assertTrue(model().state.selected.isEmpty())
        ui.onNodeWithTag("calendar-list").performScrollToNode(hasContentDescription("选择事件 ${chinese.id}"));ui.onNodeWithContentDescription("选择事件 ${chinese.id}").performClick();confirm();assertNull(model().state.error)
        store { n,db ->
            assertEquals(10,n.search("").total)
            assertEquals(oldRows,listOf("notes","drafts","calendar_imports").map { table ->db.query("SELECT * FROM $table WHERE ${if(table=="calendar_imports") "note_id" else "id"}=?",listOf(oldId)) })
            assertEquals("本机未正式保存的正文",n.open(oldId)!!.note.body);assertEquals("本机人工标题",n.open(oldId)!!.note.title)
            save("local-after-copy",canonical(listOf("notes","drafts","categories","calendar_imports","reminders").associateWith { db.query("SELECT * FROM $it ORDER BY 1") }))
        }
        val sourceAfterCopy=fixture("snapshot").toString();assertEquals(editedSource,sourceAfterCopy);save("source-edited-after-copy",sourceAfterCopy)
        ui.onNodeWithText("返回笔记").performClick();ui.activityRule.scenario.recreate();enter()
        preview();assertEquals(9,model().state.preview!!.rows.count { it.action==CalendarAction.SAME })
        store { n,_ ->assertEquals(10,n.search("").total) }
        save("ui-result",JSONObject().put("sourceRowsUnchanged",true).put("initialImported",9).put("afterExplicitCopy",10).put("alreadyImportedAfterRecreate",9).put("localEditAndDraftPreserved",true).put("calendarId",seed.getLong("calendarId")).toString(2))
    }
    @Test fun actualSourceEditDeletionAndLocalChangeRejectStalePreview() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.READ_CALENDAR)
        fixture("seed");enter();preview();fixture("edit");confirm()
        assertTrue(model().state.error!!.contains("来源已变化"));store { n,_ ->assertEquals(0,n.search("").total) }
        preview();fixture("delete");confirm();assertTrue(model().state.error!!.contains("来源已变化"));store { n,_ ->assertEquals(0,n.search("").total) }
        preview();store { n,_ ->n.createCategory("预览后新增分类") };confirm();assertTrue(model().state.error!!.contains("本机笔记"));store { n,_ ->assertEquals(0,n.search("").total) }
        preview();confirm();assertNull(model().state.error);store { n,_ ->assertEquals(8,n.search("").total) }
        save("stale-result",JSONObject().put("sourceEditRejected",true).put("sourceDeleteRejected",true).put("localChangeRejected",true).put("retryImported",8).toString())
    }
    @Test fun androidLargeSourceFailsClosedAndAcceptedRowsRemainReadableAfterReopen() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.READ_CALENDAR)
        fixture("seed");val source=AndroidCalendarSource(context);val calendar=source.calendars().single()
        val range=CalendarRange("2026-09-01","2026-09-07","Asia/Shanghai")
        val tooLarge=fixture("large").toString()
        store { n,_ ->assertEquals(0,n.search("").total) }
        try { source.read(calendar,range);fail("oversized source accepted") }
        catch(e: CalendarFailure) { assertEquals("event_limit",e.code) }
        assertEquals(tooLarge,fixture("snapshot").toString())
        store { n,db ->assertEquals(0,n.search("").total);assertTrue(db.query("SELECT * FROM calendar_imports").isEmpty()) }
        val acceptedSource=fixture("within").toString();val snapshot=source.read(calendar,range)
        val largeEvent=snapshot.events.single { it.title=="合成中文事件" }
        assertEquals("界".repeat(30000),largeEvent.description)
        store { _,db ->
            val repo=CalendarRepository(db);val preview=repo.preview(snapshot)
            assertEquals(9,repo.apply(preview,snapshot.events.filter { it.importable }.map { it.key }.toSet(),emptySet(),"",true,source).noteIds.size)
        }
        store { n,db ->
            val repo=CalendarRepository(db);assertEquals(9,repo.preview(source.read(calendar,range)).rows.count { it.action==CalendarAction.SAME })
            val mapping=db.query("SELECT note_id,original_payload FROM calendar_imports WHERE source_key=?",listOf(largeEvent.key)).single()
            assertEquals(largeEvent.payload,mapping[1]);assertEquals(largeEvent.originalText(),n.calendarOriginal(mapping[0]))
            assertEquals(largeEvent.noteBody(),n.open(mapping[0])!!.note.body)
        }
        assertEquals(acceptedSource,fixture("snapshot").toString())
        save("android-size-boundary",JSONObject().put("rejectedDescriptionUtf8Bytes",900000).put("acceptedDescriptionUtf8Bytes",90000)
            .put("payloadLimitBytes",CalendarEvent.MAX_PAYLOAD_BYTES).put("reopenedAllRowsAndOriginals",true).put("sourceUnchanged",true).toString())
    }

}
