package com.example.thinkv2.relations

import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.MainActivity
import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.ReminderRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class RelationsDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun notes()=MainActivity::class.java.getDeclaredField("notes").apply { isAccessible=true }.get(ui.activity) as NotesModel
    private fun model()=MainActivity::class.java.getDeclaredField("relations").apply { isAccessible=true }.get(ui.activity) as RelationsModel
    private fun waitReady(m: RelationsModel=model())=ui.waitUntil(10000) { !m.state.busy && (m.state.page!=null || m.state.error!=null) }
    private fun store(block: (NoteRepository,RelationRepository,AndroidSql)->Unit) { AndroidSql(context).use { db ->val n=NoteRepository(db);n.initialize();block(n,RelationRepository(db),db) } }
    private fun enter(id: String) {
        ui.waitUntil(10000) { !notes().state.loading && !notes().state.busy }
        ui.runOnIdle { notes().openIncoming(id,true) }
        ui.waitUntil(10000) { !notes().state.busy && notes().state.editor?.note?.id==id }
        ui.onNodeWithText("相关笔记").performScrollTo().performClick();waitReady();assertNull(model().state.error)
    }
    private fun scroll(text: String) { ui.onNodeWithTag("relations-list").performScrollToNode(hasText(text,substring=true)) }
    private fun action(tag: String) { ui.onNodeWithTag("relations-list").performScrollToNode(hasTestTag(tag));ui.onNodeWithTag(tag).performClick() }
    private fun choose(id: String,title: String,confirm: Boolean=true) {
        scroll("选择另一笔记建立关系");ui.onNodeWithText("选择另一笔记建立关系").performClick();waitReady()
        action("relation-add-$id")
        ui.onNodeWithText(if(confirm) "确认建立关系" else "取消").performClick();waitReady()
    }
    private fun save(name: String,value: JSONObject) { context.getFileStreamPath("relations-$name.json").writeText(value.toString(2)) }
    private fun screenshot(name: String) { ui.waitForIdle();instrumentation.uiAutomation.takeScreenshot()?.let { b ->context.getFileStreamPath("relations-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() } }
    @Test fun realUiPairsSameTitlesExactNavigationRenameTrashRestoreAndRemove() {
        lateinit var a: Note;lateinit var b: Note;lateinit var c: Note
        store { n,_,_ ->a=n.save(Editing(Note().bodyChanged("来源合成正文").titleChanged("来源笔记")));b=n.save(Editing(Note().bodyChanged("同名甲正文").titleChanged("相同标题")));c=n.save(Editing(Note().bodyChanged("同名乙正文").titleChanged("相同标题"))) }
        enter(a.id);assertEquals(0,model().state.page!!.total)
        choose(b.id,b.title,false);store { _,_,db ->assertTrue(db.query("SELECT * FROM note_relations").isEmpty()) }
        ui.runOnIdle { model().mode(false) };waitReady();choose(b.id,b.title)
        val bEdge=model().state.page!!.rows.single().edgeId
        choose(c.id,c.title);assertEquals(2,model().state.page!!.total)
        store { _,r,db ->assertEquals(2,db.query("SELECT * FROM note_relations").size);assertEquals(bEdge,r.page(b.id).rows.single().edgeId)
            try { r.create(b.id,a.id);fail() } catch(_: IllegalStateException) {} }
        screenshot("same-titles")
        action("relation-open-${b.id}")
        ui.waitUntil(10000) { notes().state.editor?.note?.id==b.id && !notes().state.busy };assertEquals("同名甲正文",notes().state.editor!!.note.body)
        ui.runOnIdle { notes().back() };ui.waitUntil(10000) { notes().state.editor==null && !notes().state.busy }
        store { n,_,_ ->val category=n.createCategory("关系原分类");n.persistDraft(n.open(b.id)!!.let { it.copy(note=it.note.titleChanged("修改后的关系标题").categorized(category)) });n.renameCategory(category,"关系改名分类") }
        enter(a.id);val changed=model().state.page!!.rows.single { it.note.id==b.id };assertEquals("修改后的关系标题",changed.note.title);assertEquals("关系改名分类",changed.note.category)
        store { n,_,_ ->n.softDelete(n.open(b.id)!!) }
        ui.runOnIdle { model().load() };waitReady();assertEquals(listOf(c.id),model().state.page!!.rows.map { it.note.id })
        store { n,_,db ->assertEquals(2,db.query("SELECT * FROM note_relations").size);n.restore(n.trash().single().note) }
        ui.runOnIdle { model().load() };waitReady();assertEquals(bEdge,model().state.page!!.rows.single { it.note.id==b.id }.edgeId)
        lateinit var before: List<List<List<String>>>
        store { _,_,db ->before=listOf("notes","drafts").map { db.query("SELECT * FROM $it ORDER BY id") } }
        action("relation-remove-${c.id}");ui.onNodeWithText("确认移除关系").performClick();waitReady()
        assertEquals(1,model().state.page!!.total)
        store { _,r,db ->assertEquals(before,listOf("notes","drafts").map { db.query("SELECT * FROM $it ORDER BY id") });assertEquals(0,r.page(c.id).total)
            save("ui-database",JSONObject().put("notes",JSONArray(db.query("SELECT id,title,body,category,deleted_at FROM notes ORDER BY id"))).put("drafts",JSONArray(db.query("SELECT id,title,body,category,active FROM drafts ORDER BY id"))).put("relations",JSONArray(db.query("SELECT * FROM note_relations ORDER BY id")))) }
        ui.activityRule.scenario.recreate();assertEquals(bEdge,model().state.page!!.rows.single().edgeId)
        save("ui-result",JSONObject().put("sourceId",a.id).put("targetId",b.id).put("edgeId",bEdge).put("sameTitleExactNavigation",true).put("trashRestoreSameEdge",true).put("removeKeptAllNoteRows",true).put("recreateKeptIdentity",true))
    }
    @Test fun actualAndroidTransactionFailureShowsErrorThenRetryCommits() {
        val db=AndroidSql(context,"relations-failure.db");val notes=NoteRepository(db);notes.initialize()
        val a=notes.save(Editing(Note().bodyChanged("失败场景甲")));val b=notes.save(Editing(Note().bodyChanged("失败场景乙")))
        val fail=AtomicBoolean(true)
        val wrapped=object: Sql by db { override fun execute(statement: String,args: List<String>) { db.execute(statement,args);if(fail.get() && statement.startsWith("INSERT INTO note_relations")) error("synthetic_insert_failure") } }
        val owner=ViewModelStore()
        val m=ViewModelProvider(owner,object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T = RelationsModel(context,ReminderRuntime.get(context)) { wrapped } as T
        })[RelationsModel::class.java]
        try {
            ui.runOnIdle { ui.activity.setContent { RelationsScreen(m,back={},open={}) };m.open(a.id) };waitReady(m)
            ui.onNodeWithTag("relations-list").performScrollToNode(hasText("选择另一笔记建立关系"));ui.onNodeWithText("选择另一笔记建立关系").performClick();waitReady(m)
            action("relation-add-${b.id}");ui.onNodeWithText("确认建立关系").performClick();waitReady(m)
            assertTrue(m.state.error!!.contains("未完成"));assertNull(m.state.message);assertTrue(db.query("SELECT * FROM note_relations").isEmpty());assertEquals(2,db.query("SELECT * FROM notes").size)
            fail.set(false);ui.runOnIdle { m.load() };waitReady(m)
            action("relation-add-${b.id}");ui.onNodeWithText("确认建立关系").performClick();waitReady(m)
            assertNull(m.state.error);assertEquals(1,m.state.page!!.total);assertEquals(2,db.query("SELECT * FROM notes").size)
            save("rollback",JSONObject().put("writeThenExceptionRolledBack",true).put("errorInsteadOfSuccess",true).put("retryCommitted",true).put("noteCount",2))
        } finally { ui.runOnIdle { owner.clear() } }
    }
    private fun shell(command: String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun accessibility(node: AccessibilityNodeInfo?,description: String): AccessibilityNodeInfo? {
        if(node==null) return null
        if(node.contentDescription?.toString()==description) return node
        for(i in 0 until node.childCount) accessibility(node.getChild(i),description)?.let { return it }
        return null
    }
    @Test fun thousandNotesFiveThousandEdgesBoundedPagesLargeFontAndAccessibilityFocus() {
        val ids=(0 until 1000).map { UUID(0,(it+1).toLong()).toString() }
        store { _,_,db ->
            db.begin();try {
                for(i in ids.indices) {
                    val title=if(i in 1..2) "规模同名笔记" else "规模笔记%04d".format(i);val body="规模正文编号%04d".format(i)
                    db.execute("INSERT INTO notes (id,body,title,manual,category,created,updated,revision,category_id,category_source,title_fold,body_fold,deleted_at) VALUES (?,?,?,1,'',1,1,1,'','manual',?,?,0)",listOf(ids[i],body,title,title,body))
                }
                val pairs=linkedSetOf<Pair<Int,Int>>();for(i in 1..999) pairs+=0 to i
                var distance=1
                while(pairs.size<5000) { for(i in 1..999) { val j=(i+distance)%1000;if(i!=j) pairs+=minOf(i,j) to maxOf(i,j);if(pairs.size==5000) break };distance++ }
                pairs.forEachIndexed { i,(a,b) ->db.execute("INSERT INTO note_relations VALUES (?,?,?,'manual',?)",listOf(UUID(1,(i+1).toLong()).toString(),ids[a],ids[b],(i+1).toString())) }
                db.commit()
            } catch(e: Exception) { db.rollback();throw e }
        }
        val times=mutableListOf<Long>();lateinit var first: RelationPage;lateinit var second: RelationPage
        store { _,r,db ->
            repeat(10) { val start=SystemClock.elapsedRealtimeNanos();val page=r.page(ids[0]);times+=(SystemClock.elapsedRealtimeNanos()-start)/1000;assertEquals(999,page.total);assertEquals(50,page.rows.size) }
            first=r.page(ids[0]);second=r.page(ids[0],offset=50);assertEquals(100,(first.rows+second.rows).map { it.note.id }.distinct().size)
            val candidates=r.page(ids[1],true,"规模");assertTrue(candidates.candidates.size<=50 && candidates.total>50)
            assertEquals(1000,db.query("SELECT count(*) FROM notes").single().single().toInt());assertEquals(5000,db.query("SELECT count(*) FROM note_relations").single().single().toInt())
            save("scale-database",JSONObject().put("notes",JSONArray(db.query("SELECT id,title FROM notes ORDER BY id"))).put("relations",JSONArray(db.query("SELECT * FROM note_relations ORDER BY id"))))
        }
        val start=SystemClock.elapsedRealtime();enter(ids[0]);ui.waitForIdle();val uiMs=SystemClock.elapsedRealtime()-start
        assertEquals(50,model().state.page!!.rows.size)
        scroll("下一页");ui.onNodeWithText("下一页").performClick();waitReady()
        assertEquals(50,model().state.page!!.offset);assertEquals(second.rows.map { it.note.id },model().state.page!!.rows.map { it.note.id })
        scroll("上一页");ui.onNodeWithText("上一页").performClick();waitReady();assertEquals(0,model().state.page!!.offset)
        val changedTarget=first.rows.first()
        fun rejectedThenRefresh() {
            scroll("下一页");ui.onNodeWithText("下一页").performClick();waitReady()
            assertTrue(model().state.error!!.contains("旧分页已失效"));assertNull(model().state.page)
            scroll("刷新");ui.onNodeWithText("刷新").performClick();waitReady();assertNull(model().state.error)
        }
        store { _,r,_ ->r.remove(ids[0],changedTarget) };rejectedThenRefresh();assertEquals(998,model().state.page!!.total)
        store { _,r,_ ->r.create(ids[0],changedTarget.note.id) };rejectedThenRefresh();assertEquals(999,model().state.page!!.total)
        store { n,_,_ ->n.save(n.open(changedTarget.note.id)!!.let { it.copy(note=it.note.titleChanged("规模修改后的标题")) }) };rejectedThenRefresh()
        first=model().state.page!!
        store { _,r,db ->
            second=r.page(ids[0],offset=50);assertEquals(100,(first.rows+second.rows).map { it.note.id }.distinct().size)
            save("scale-database",JSONObject().put("notes",JSONArray(db.query("SELECT id,title FROM notes ORDER BY id"))).put("relations",JSONArray(db.query("SELECT * FROM note_relations ORDER BY id"))))
        }
        val automation=instrumentation.uiAutomation;val info=automation.serviceInfo;val originalFlags=info.flags
        try {
            info.flags=info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;automation.serviceInfo=info
            ui.waitUntil(10000) { context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled }
            shell("settings put system font_scale 1.5");ui.waitUntil(10000) { ui.activity.resources.configuration.fontScale>=1.49f }
            val target=first.rows.first().note;val label="打开相关笔记 ${target.contextLabel()}"
            ui.onNodeWithTag("relations-list").performScrollToNode(hasContentDescription(label));ui.waitForIdle();screenshot("large-font")
            val cached=accessibility(automation.rootInActiveWindow,label)
            automation.waitForIdle(250,3000);automation.clearCache()
            val node=accessibility(automation.rootInActiveWindow,label) ?: error("accessible_button_missing")
            save("accessibility-node",JSONObject().put("cached",cached?.toString()).put("fresh",node.toString()).put("parent",node.parent?.toString()))
            assertTrue("button clickable",node.isClickable)
            assertTrue("accessibility focus action",node.actionList.any { it.id==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS })
            assertTrue("accessibility focus request",node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            ui.waitUntil(5000) { node.refresh();node.isAccessibilityFocused }
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            ui.waitUntil(10000) { !notes().state.busy && notes().state.editor?.note?.id==target.id }
            assertEquals(target.title,notes().state.editor!!.note.title)
            val sorted=times.sorted();save("scale",JSONObject().put("noteCount",1000).put("edgeCount",5000).put("sourceDegree",999).put("pageLimit",50)
                .put("firstPageIds",JSONArray(first.rows.map { it.note.id })).put("secondPageIds",JSONArray(second.rows.map { it.note.id }))
                .put("queryMicroseconds",JSONArray(times)).put("medianQueryUs",sorted[sorted.size/2]).put("maxQueryUs",sorted.last()).put("uiOpenMs",uiMs)
                .put("pagingChangesRequireRefresh",JSONArray(listOf("delete","create","title"))).put("fontScale",1.5).put("keyboardInputFocusable",node.isFocusable).put("accessibilityFocus",true).put("accessibilityClickOpenedId",target.id).put("talkbackSpeechTested",false))
        } finally { shell("settings put system font_scale 1.0");info.flags=originalFlags;automation.serviceInfo=info }
    }
}
