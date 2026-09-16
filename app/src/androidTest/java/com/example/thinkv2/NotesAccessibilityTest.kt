package com.example.thinkv2

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.notes.*
import com.example.thinkv2.relations.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic emulator only. Platform node checks are not a TalkBack speech test. */
class NotesAccessibilityTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val prefix get()="accessibility-"+InstrumentationRegistry.getArguments().getString("case","manual")
    private val events=JSONArray()
    private fun notes()=MainActivity::class.java.getDeclaredField("notes").apply { isAccessible=true }.get(ui.activity) as NotesModel
    private fun relations()=MainActivity::class.java.getDeclaredField("relations").apply { isAccessible=true }.get(ui.activity) as RelationsModel
    private fun ready() { ui.waitUntil(15000) { !notes().state.loading && !notes().state.busy };assertNull(notes().state.error) }
    private fun relationReady() { ui.waitUntil(15000) { !relations().state.busy && relations().state.page!=null };assertNull(relations().state.error) }
    private fun reveal(m: SemanticsMatcher) {
        when {
            notes().state.page==NotesPage.RELATIONS -> ui.onNodeWithTag("relations-list").performScrollToNode(m)
            notes().state.editor!=null -> { if(ui.onAllNodes(m and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) ui.onNode(m).performScrollTo() else ui.onNode(m).assertIsDisplayed() }
            notes().state.page==NotesPage.HOME -> ui.onNodeWithTag("notes-list").performScrollToNode(m)
            else -> ui.onNodeWithTag("lifecycle-list").performScrollToNode(m)
        }
    }
    private fun click(text: String) { reveal(hasText(text));ui.onNodeWithText(text).assertIsDisplayed().performClick();ui.waitForIdle() }
    private fun tag(tag: String) { reveal(hasTestTag(tag));ui.onNodeWithTag(tag).assertIsDisplayed().performClick();ui.waitForIdle() }
    private fun snapshot(name: String) {
        ui.waitForIdle();ins.uiAutomation.waitForIdle(200,5000);ins.uiAutomation.clearCache()
        val nodes=JSONArray()
        fun visit(n: AccessibilityNodeInfo?,depth: Int) {
            if(n==null)return
            val r=Rect();n.getBoundsInScreen(r)
            nodes.put(JSONObject().put("depth",depth).put("text",n.text?.toString()).put("description",n.contentDescription?.toString())
                .put("class",n.className?.toString()).put("bounds",r.toShortString()).put("visible",n.isVisibleToUser)
                .put("clickable",n.isClickable).put("focusable",n.isFocusable).put("focused",n.isFocused)
                .put("accessibilityFocused",n.isAccessibilityFocused).put("actions",JSONArray(n.actionList.map { it.id })))
            repeat(n.childCount) { visit(n.getChild(it),depth+1) }
        }
        visit(ins.uiAutomation.rootInActiveWindow,0)
        val cfg=ui.activity.resources.configuration
        val value=JSONObject().put("screen",name).put("fontScale",cfg.fontScale).put("widthDp",cfg.screenWidthDp)
            .put("heightDp",cfg.screenHeightDp).put("dark",cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)
            .put("nodes",nodes).put("talkbackClaim",false)
        context.getFileStreamPath("$prefix-$name.json").writeText(value.toString(2))
        ins.uiAutomation.takeScreenshot()!!.let { b ->context.getFileStreamPath("$prefix-$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun platformButton(label: String): AccessibilityNodeInfo {
        ui.waitForIdle();ins.uiAutomation.waitForIdle(200,5000);ins.uiAutomation.clearCache()
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.contentDescription?.toString()==label || n.text?.toString()==label) {
                var p: AccessibilityNodeInfo=n
                while(!p.isClickable) { val parent=p.parent ?: break;p=parent }
                if(p.isClickable)return p
            }
            repeat(n.childCount) { find(n.getChild(it))?.let { return it } };return null
        }
        var found: AccessibilityNodeInfo?=null
        ui.waitUntil(5000) { ins.uiAutomation.clearCache();found=find(ins.uiAutomation.rootInActiveWindow);found!=null }
        return found ?: error("missing platform button $label")
    }
    private fun checkButton(label: String,focus: Boolean=false): AccessibilityNodeInfo {
        val n=platformButton(label);val r=Rect();n.getBoundsInScreen(r);val density=ui.activity.resources.displayMetrics.density
        if(r.height()/density<55f || r.width()/density<55f) { snapshot("small-target");context.getFileStreamPath("$prefix-small-target-detail.json").writeText(JSONObject().put("label",label).put("node",n.toString()).put("parent",n.parent?.toString()).toString(2)) }
        assertTrue("$label height ${r.height()/density}",r.height()/density>=55f)
        assertTrue("$label width ${r.width()/density}",r.width()/density>=55f)
        assertTrue(n.isEnabled && n.isClickable && n.isVisibleToUser)
        assertEquals("coherent button role", "android.widget.Button", n.className.toString())
        if(focus) { assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS));ui.waitUntil(5000) { n.refresh();n.isAccessibilityFocused } }
        events.put(JSONObject().put("label",label).put("bounds",r.toShortString()).put("heightDp",r.height()/density).put("widthDp",r.width()/density).put("platformFocusChecked",focus))
        return n
    }
    @Test fun realRoutesLargeFontsThemesDurableEditingAndPlatformActions() {
        ready()
        reveal(hasText("还没有笔记，写下第一个想法吧。"));snapshot("empty-home")
        click("管理分类");ready();reveal(hasText("还没有分类。未分类也能保存笔记。"));snapshot("empty-categories");click("返回笔记");ready()
        click("回收站（0）");ready();reveal(hasText("回收站为空"));snapshot("empty-trash");click("返回笔记");ready()
        val automation=ins.uiAutomation;val info=automation.serviceInfo;val flags=info.flags
        info.flags=flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;automation.serviceInfo=info
        try {
            ui.waitUntil(5000) { context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled }
            lateinit var source: Note;lateinit var other: Note;lateinit var same: Note
            AndroidSql(context).use { db ->val repo=NoteRepository(db);repo.initialize()
                val category=repo.createCategory("家庭计划与生活事项")
                source=repo.save(Editing(Note().bodyChanged("甲：周末整理花园，带上工具。保留这条真实合成正文。").titleChanged("相同标题").categorized(category)))
                other=repo.save(Editing(Note().bodyChanged("乙：下周阅读计划，只属于第二条记录。").titleChanged("相同标题").categorized(category)))
                same=repo.save(Editing(Note().bodyChanged("丙：返回与搜索验收记录。").titleChanged("相同标题")))
            }
            ui.runOnIdle { notes().refresh() };ready()
            reveal(hasText("新增文字"));snapshot("home-top");checkButton("新增文字",true)
            reveal(hasTestTag("note-${source.id}"));snapshot("home-real-list");tag("note-${source.id}");ready();assertEquals(source.id,notes().state.editor!!.note.id)
            reveal(hasText("正文"));ui.onNodeWithText("正文").performClick();ui.onNodeWithText("正文").performTextReplacement("甲：经过键盘、旋转与后台仍保留的草稿正文。")
            snapshot("editor-ime");Espresso.closeSoftKeyboard()
            reveal(hasText("手动标题"));ui.onNodeWithText("手动标题").performTextReplacement("手动标题保留")
            Espresso.closeSoftKeyboard();click("分类：家庭计划与生活事项");snapshot("category-picker")
            ui.onNodeWithText("未分类",useUnmergedTree=false).performClick()
            click("分类：未分类");ui.onNodeWithText("家庭计划与生活事项").performClick()
            ui.waitUntil(10000) { notes().state.draftState==DraftState.SAVED }
            val draft=notes().state.editor!!.note
            ui.activityRule.scenario.recreate();ready();assertEquals(draft,notes().state.editor!!.note)
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);ready();assertEquals(draft,notes().state.editor!!.note)
            // Actual requested orientation causes activity/configuration recreation; no fake state reset.
            ui.runOnUiThread { ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE };ready()
            reveal(hasText("正文"));snapshot("editor-landscape");assertEquals(draft,notes().state.editor!!.note)
            ui.runOnUiThread { ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT };ready()
            Espresso.pressBack();ready();assertNull(notes().state.editor)
            AndroidSql(context).use { db ->assertEquals(listOf(listOf(draft.body,draft.title,draft.categoryId)),db.query("SELECT body,title,category_id FROM drafts WHERE id=?",listOf(source.id))) }
            reveal(hasText("搜索标题或正文"));ui.onNodeWithText("搜索标题或正文").performClick();ui.onNodeWithText("搜索标题或正文").performTextInput("阅读计划");snapshot("search-ime");Espresso.closeSoftKeyboard();ready()
            assertEquals(listOf(other.id),notes().state.notes.map { it.id });tag("note-${other.id}");ready();assertEquals(other.id,notes().state.editor!!.note.id)
            click("返回 · 保留草稿");ready();reveal(hasText("清空"));ui.onNodeWithText("清空").performClick();ready()
            click("新增文字");ready();reveal(hasText("正文"));ui.onNodeWithText("正文").performTextInput("新增的合成笔记");Espresso.closeSoftKeyboard();checkButton("保存");ui.onNodeWithText("保存").performClick();ready();assertEquals(4,notes().state.total)
            click("管理分类");ready();click("新建分类");ui.onNodeWithText("分类名称").performTextInput("临时分类");Espresso.closeSoftKeyboard();ui.onNodeWithText("保存分类").performClick();ready()
            reveal(hasContentDescription("删除分类临时分类"));ui.onNodeWithContentDescription("删除分类临时分类").performClick();ui.onNodeWithText("确认删除分类").performClick();ready()
            reveal(hasContentDescription("改名分类家庭计划与生活事项"));ui.onNodeWithContentDescription("改名分类家庭计划与生活事项").performClick()
            ui.onNodeWithText("分类名称").performTextReplacement("已改名的家庭分类");snapshot("category-form-ime");Espresso.closeSoftKeyboard();checkButton("保存分类");ui.onNodeWithText("保存分类").performClick();ready()
            reveal(hasContentDescription("删除分类已改名的家庭分类"));snapshot("categories");ui.onNodeWithContentDescription("删除分类已改名的家庭分类").performClick();snapshot("delete-category-confirm");checkButton("确认删除分类");ui.onNodeWithText("取消").performClick()
            click("返回笔记");ready();tag("note-${source.id}");ready();click("相关笔记");relationReady();click("选择另一笔记建立关系");relationReady()
            reveal(hasTestTag("relation-add-${other.id}"));snapshot("relation-candidates");tag("relation-add-${other.id}");snapshot("relation-add-confirm");checkButton("确认建立关系");ui.onNodeWithText("确认建立关系").performClick();relationReady()
            val row=relations().state.page!!.rows.single();assertEquals(other.id,row.note.id)
            val label="打开相关笔记 ${row.note.contextLabel()}";reveal(hasTestTag("relation-open-${other.id}"));snapshot("relation-list")
            val node=checkButton(label,true);assertFalse(node.contentDescription.toString().contains(other.id));assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));ready();assertEquals(other.id,notes().state.editor!!.note.id)
            click("相关笔记");relationReady();reveal(hasTestTag("relation-remove-${source.id}"));tag("relation-remove-${source.id}");snapshot("relation-remove-confirm");checkButton("确认移除关系");ui.onNodeWithText("取消").performClick()
            // Dialog dismiss restores a usable route; next platform focus/click returns to the exact source.
            val backRow=relations().state.page!!.rows.single();reveal(hasTestTag("relation-open-${source.id}"));checkButton("打开相关笔记 ${backRow.note.contextLabel()}",true).performAction(AccessibilityNodeInfo.ACTION_CLICK);ready();assertEquals(source.id,notes().state.editor!!.note.id)
            click("移入回收站");snapshot("trash-confirm");checkButton("确认");ui.onNodeWithText("取消").performClick()
            click("移入回收站");ui.onNodeWithText("确认").performClick();ready();click("回收站（1）");ready();reveal(hasTestTag("restore-${source.id}"));snapshot("trash");tag("restore-${source.id}");ready()
            click("返回笔记");ready();tag("note-${source.id}");ready();assertEquals(draft.body,notes().state.editor!!.note.body);assertEquals(draft.title,notes().state.editor!!.note.title);assertEquals("已改名的家庭分类",notes().state.editor!!.note.category)
            click("放弃这次编辑");snapshot("discard-confirm");checkButton("取消");ui.onNodeWithText("取消").performClick();assertEquals(draft.body,notes().state.editor!!.note.body)
            checkButton("保存");ui.onNodeWithText("保存").performClick();ready()
            AndroidSql(context).use { db ->
                assertEquals(4,db.query("SELECT id FROM notes WHERE deleted_at=0").size)
                assertEquals(1,db.query("SELECT id FROM note_relations").size)
                assertEquals(listOf(listOf(draft.body,draft.title,"已改名的家庭分类")),db.query("SELECT body,title,category FROM notes WHERE id=?",listOf(source.id)))
                val snapshot=JSONObject();listOf("notes","drafts","categories","note_relations").forEach { snapshot.put(it,JSONArray(db.query("SELECT * FROM $it ORDER BY 1").map { r->JSONArray(r) })) }
                context.getFileStreamPath("$prefix-database.json").writeText(snapshot.toString(2))
            }
            reveal(hasText("搜索标题或正文"));ui.onNodeWithText("搜索标题或正文").performTextReplacement("无匹配合成关键词");Espresso.closeSoftKeyboard();ready()
            assertEquals(0,notes().state.total);reveal(hasText("没有匹配的笔记。试试其他关键词或分类。"));snapshot("no-search-results")
            val services=context.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList.map { it.id }
            context.getFileStreamPath("$prefix-result.json").writeText(JSONObject().put("allAssertionsPassed",true).put("sourceId",source.id).put("sameTitleOtherId",other.id).put("platformChecks",events).put("installedServices",JSONArray(services)).put("talkbackSpeechTested",false).toString(2))
        } finally { info.flags=flags;automation.serviceInfo=info }
    }
}
