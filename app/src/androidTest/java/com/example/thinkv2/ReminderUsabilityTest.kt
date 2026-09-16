package com.example.thinkv2

import android.content.res.Configuration
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic emulator only; platform nodes do not prove TalkBack navigation. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ReminderUsabilityTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val prefix get()="reminders-"+InstrumentationRegistry.getArguments().getString("case","manual")
    private val runtime get()=ReminderRuntime.get(context)
    private fun model()=MainActivity::class.java.getDeclaredField("reminders").apply { isAccessible=true }.get(ui.activity) as ReminderModel
    private fun notes()=MainActivity::class.java.getDeclaredField("notes").apply { isAccessible=true }.get(ui.activity) as NotesModel
    private fun <T> serial(block: ()->T): T=runBlocking(runtime.dispatcher) { block() }
    private fun ready() { ui.waitUntil(15000) { !notes().state.loading && !notes().state.busy && !model().state.busy } }
    private fun snapshot(name: String) {
        ui.waitForIdle();ins.uiAutomation.waitForIdle(200,5000);ins.uiAutomation.clearCache()
        val nodes=JSONArray()
        fun visit(n: AccessibilityNodeInfo?,depth: Int) {
            if(n==null)return
            val r=Rect();n.getBoundsInScreen(r)
            nodes.put(JSONObject().put("depth",depth).put("text",n.text?.toString()).put("description",n.contentDescription?.toString())
                .put("class",n.className?.toString()).put("roleDescription",androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(n).roleDescription?.toString()).put("stateDescription",n.stateDescription?.toString()).put("bounds",r.toShortString()).put("visible",n.isVisibleToUser)
                .put("clickable",n.isClickable).put("focusable",n.isFocusable).put("focused",n.isFocused)
                .put("accessibilityFocused",n.isAccessibilityFocused).put("checked",n.isChecked).put("checkable",n.isCheckable).put("selected",n.isSelected).put("actions",JSONArray(n.actionList.map { it.id })))
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

    private val checks=JSONArray()
    private fun reveal(m: SemanticsMatcher) {
        if(model().state.form!=null) ui.onNode(m).performScrollTo()
        else ui.onNodeWithTag("reminders-list").performScrollToNode(m)
        ui.onNode(m).assertIsDisplayed()
    }
    private fun click(text: String) { reveal(hasText(text));ui.onNodeWithText(text).performClick();ready() }
    private fun tagged(tag: String) { reveal(hasTestTag(tag));ui.onNodeWithTag(tag).performClick();ready();if(model().state.form!=null) ui.onNodeWithText("设置笔记提醒").assertIsDisplayed() }
    private fun labelFor(tag: String): String=ui.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
    private fun platform(label: String): AccessibilityNodeInfo {
        ui.waitForIdle();ins.uiAutomation.waitForIdle(200,5000);ins.uiAutomation.clearCache()
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.text?.toString()==label || n.contentDescription?.toString()==label) {
                var p: AccessibilityNodeInfo=n;while(!p.isClickable) { p=p.parent ?: break };if(p.isClickable)return p
            }
            repeat(n.childCount) { find(n.getChild(it))?.let { return it } };return null
        }
        var n: AccessibilityNodeInfo?=null
        ui.waitUntil(5000) { ins.uiAutomation.clearCache();n=find(ins.uiAutomation.rootInActiveWindow);n!=null }
        return n ?: error("missing platform node $label")
    }
    private fun target(label: String,role: String="android.widget.Button"): AccessibilityNodeInfo {
        val n=platform(label);val r=Rect();n.getBoundsInScreen(r);val density=ui.activity.resources.displayMetrics.density
        snapshot("target-${checks.length()}")
        assertTrue("$label width ${r.width()/density}",r.width()/density>=55f)
        assertTrue("$label height ${r.height()/density}",r.height()/density>=55f)
        if(role=="android.widget.Switch") {
            // Compose 1.10.4 deliberately exposes Switch via roleDescription, not a Switch class.
            assertEquals("switch",androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(n).roleDescription.toString().lowercase())
        } else assertEquals(role,n.className.toString())
        assertTrue(n.isEnabled && n.isVisibleToUser)
        checks.put(JSONObject().put("label",label).put("role",n.className).put("roleDescription",androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(n).roleDescription?.toString()).put("bounds",r.toShortString()).put("widthDp",r.width()/density).put("heightDp",r.height()/density))
        return n
    }
    private var dismissedBanners=0
    private fun ime() {
        ui.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
        if(InstrumentationRegistry.getArguments().getString("normalIme")!="true") return
        val a=ins.uiAutomation;val info=a.serviceInfo;val old=info.flags
        info.flags=old or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;a.serviceInfo=info
        try {
            a.waitForIdle(300,5000);a.clearCache()
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if(n==null)return null
                if(n.text?.toString()=="OK" && n.packageName?.toString()=="com.google.android.inputmethod.latin") {
                    var p: AccessibilityNodeInfo=n;while(!p.isClickable) { p=p.parent ?: break };return p.takeIf { it.isClickable }
                }
                repeat(n.childCount) { find(n.getChild(it))?.let { return it } };return null
            }
            val button=a.windows.asSequence().mapNotNull { find(it.root) }.firstOrNull()
            if(button!=null) {
                snapshot("keyboard-banner-before-$dismissedBanners")
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));dismissedBanners++
                ui.waitForIdle();a.waitForIdle(300,5000)
            }
        } finally { info.flags=old;a.serviceInfo=info }
    }
    private fun editVisible(label: String,value: String,name: String) {
        reveal(hasText(label));ui.onNodeWithText(label).performTouchInput { click(center) };ime()
        reveal(hasText(label))
        val field=ui.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val viewport=ui.onNodeWithTag("reminder-form").fetchSemanticsNode().boundsInRoot
        assertTrue("editable field entirely visible above IME",field.top>=viewport.top && field.bottom<=viewport.bottom)
        ui.onNodeWithText(label).performTouchInput { click(center) };ui.onNodeWithText(label).assertIsFocused().performTextReplacement(value)
        ui.waitForIdle()
        val after=ui.onNodeWithText(label).fetchSemanticsNode()
        val afterViewport=ui.onNodeWithTag("reminder-form").fetchSemanticsNode().boundsInRoot
        snapshot(name)
        assertTrue("field remains fully visible after correcting input clears error",after.positionInRoot.y>=afterViewport.top && after.positionInRoot.y+after.size.height<=afterViewport.bottom)
        checks.put(JSONObject().put("input",label).put("bounds",field.toString()).put("viewport",viewport.toString()).put("pointerTap","center of measured field").put("focused",true))
    }
    private fun closeIme() { androidx.test.espresso.Espresso.closeSoftKeyboard();ui.waitForIdle() }
    private fun openFromNote(note: Note) {
        ui.onNodeWithTag("notes-list").performScrollToNode(hasTestTag("note-${note.id}"));ui.onNodeWithTag("note-${note.id}").performClick();ready()
        ui.onNodeWithText("设置笔记提醒").performScrollTo().performClick();ready()
        assertEquals(note.id,model().state.form!!.noteId)
    }
    private fun home() {
        ui.onNodeWithText("返回笔记").performClick();ready()
        if(notes().state.editor!=null) { ui.onNodeWithText("返回 · 保留草稿").performClick();ready() }
    }
    @Test fun realReminderRoutesCorrectionPersistenceHistoryAndPlatformNodes() {
        ready();val cfg=ui.activity.resources.configuration
        assertEquals(320,cfg.screenWidthDp)
        val day=LocalDay.from(System.currentTimeMillis(),java.util.TimeZone.getDefault()).plus(7)
        val pair=serial { NoteRepository(AndroidSql(context)).use { r ->r.initialize();Pair(
            r.save(Editing(Note().bodyChanged("甲：花园工具准备，只属于第一条笔记。").titleChanged("相同标题"))),
            r.save(Editing(Note().bodyChanged("乙：读书计划，只属于第二条笔记。").titleChanged("相同标题")))) } }
        ui.runOnIdle { notes().refresh() };ready()
        ui.onNodeWithTag("notes-list").performScrollToNode(hasText("提醒计划与记录"));ui.onNodeWithText("提醒计划与记录").performClick();ready()
        reveal(hasText("还没有提醒。打开一条已保存笔记，选择“设置笔记提醒”。"));snapshot("empty-list")
        reveal(hasText("允许通知"));target("允许通知");reveal(hasText("打开系统通知设置"));target("打开系统通知设置")
        home();openFromNote(pair.first)
        reveal(hasText("启用此提醒"));ui.onNodeWithText("启用此提醒").assertIsOn()
        val sw=target("启用此提醒","android.widget.Switch");assertTrue(sw.isCheckable && sw.isChecked)
        click("每周（可多选）");ui.onNodeWithText("每周（可多选）").assertIsSelected();assertTrue(platform("每周（可多选）").isChecked)
        click("保存提醒规则");assertNotNull(model().state.error);snapshot("weekly-validation-error")
        val error=model().state.error!!;reveal(hasText(error));ui.onNodeWithText(error).assertIsDisplayed()
        editVisible("开始日期","not-a-date","invalid-date-ime");closeIme();click("保存提醒规则");assertNotNull(model().state.error)
        editVisible("开始日期",day.toString(),"correct-date-ime")
        ui.onNodeWithText("开始日期").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Tab) }
        ui.onNodeWithText("当地时间").assertIsFocused();snapshot("keyboard-tab-time")
        editVisible("当地时间","29:99","invalid-time-ime");closeIme();click("保存提醒规则");assertNotNull(model().state.error)
        editVisible("当地时间","10:35","correct-time-ime");closeIme()
        click("每周一");ui.onNodeWithText("每周一").assertIsSelected();snapshot("monday-selected");assertTrue(platform("每周一").isChecked);click("每周五");assertTrue(platform("每周五").isChecked);click("每周五");assertFalse(platform("每周五").isChecked);click("每周五");reveal(hasText("每周一"));ui.onNodeWithText("每周一").assertIsSelected();snapshot("weekly-selected")
        val pending=model().state.form!!
        ui.activityRule.scenario.recreate();ready();assertEquals(pending,model().state.form)
        ui.runOnIdle { ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE }
        reveal(hasText("保存提醒规则"));snapshot("landscape-save");assertEquals(pending,model().state.form)
        ui.runOnIdle { ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT }
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);ready();assertEquals(pending,model().state.form)
        assertNull(serial { runtime.repository.forNote(pair.first.id) })
        reveal(hasText("保存提醒规则"));target("保存提醒规则").performAction(AccessibilityNodeInfo.ACTION_CLICK);ui.waitUntil(10000) { model().state.form==null && !model().state.busy };ready()
        var first=serial { runtime.repository.forNote(pair.first.id)!! }
        assertEquals(ReminderRule(Repeat.WEEKLY,day,10,35,17),first.rule);assertEquals("BLOCKED",first.status);assertTrue(first.requested)
        reveal(hasTestTag("reminder-edit-${pair.first.id}"));snapshot("saved-blocked");tagged("reminder-edit-${pair.first.id}")
        reveal(hasText("每周五"));ui.onNodeWithText("每周五").assertIsSelected()
        click("每天");editVisible("当地时间","11:45","unsaved-change-ime");closeIme()
        ui.onNodeWithText("返回提醒列表").performClick();ready();assertEquals(first,serial { runtime.repository.forNote(pair.first.id)!! })
        tagged("reminder-edit-${pair.first.id}");click("每天");click("启用此提醒");ui.onNodeWithText("启用此提醒").assertIsOff();assertFalse(platform("启用此提醒").isChecked);snapshot("disabled-switch")
        click("保存提醒规则");first=serial { runtime.repository.forNote(pair.first.id)!! };assertEquals("DISABLED",first.status);assertFalse(first.requested);assertEquals(Repeat.DAILY,first.rule.repeat);assertEquals(2L,first.revision)
        tagged("reminder-edit-${pair.first.id}");click("仅一次");click("保存提醒规则");first=serial { runtime.repository.forNote(pair.first.id)!! };assertEquals(Repeat.ONCE,first.rule.repeat);assertEquals("DISABLED",first.status)
        home();openFromNote(pair.second);click("每天");editVisible("开始日期",day.toString(),"second-date-ime");closeIme();click("保存提醒规则")
        val second=serial { runtime.repository.forNote(pair.second.id)!! };assertEquals(Repeat.DAILY,second.rule.repeat);assertEquals("BLOCKED",second.status)
        assertNotEquals(model().state.labels[pair.first.id],model().state.labels[pair.second.id])
        val tag="reminder-open-${pair.second.id}";reveal(hasTestTag(tag));val label=labelFor(tag);assertTrue(label.contains("乙：读书"));assertFalse(label.contains(pair.second.id));snapshot("same-title-plans")
        target(label).performAction(AccessibilityNodeInfo.ACTION_CLICK);ready();assertEquals(pair.second.id,notes().state.editor!!.note.id)
        ui.onNodeWithText("返回 · 保留草稿").performClick();ready();ui.onNodeWithTag("notes-list").performScrollToNode(hasText("提醒计划与记录"));ui.onNodeWithText("提醒计划与记录").performClick();ready()
        // Durable synthetic history fixtures exercise pagination, not natural recurring delivery.
        serial { repeat(52) { i -> val occurrence=second.rule.occurrence(day.plus(i),java.util.TimeZone.getDefault());runtime.repository.missed(runtime.repository.get(second.id)!!,occurrence,occurrence.at,"permission_blocked") } }
        ui.runOnIdle { model().refresh() };ui.waitUntil(10000) { model().state.eventCount==52 };assertEquals(50,model().state.events.size)
        val oldEvent=model().state.events.last();val eventTag="reminder-event-open-${oldEvent.reminderId}-${oldEvent.revision}-${oldEvent.key}"
        reveal(hasTestTag(eventTag));snapshot("history");target(labelFor(eventTag)).performAction(AccessibilityNodeInfo.ACTION_CLICK);ready();assertEquals(pair.second.id,notes().state.editor!!.note.id)
        ui.onNodeWithText("返回 · 保留草稿").performClick();ready();ui.onNodeWithTag("notes-list").performScrollToNode(hasText("提醒计划与记录"));ui.onNodeWithText("提醒计划与记录").performClick();ready()
        reveal(hasText("加载更多记录"));target("加载更多记录").performAction(AccessibilityNodeInfo.ACTION_CLICK);ui.waitUntil(10000) { model().state.events.size==52 }
        val last=model().state.events.last();reveal(hasTestTag("reminder-event-${last.reminderId}-${last.revision}-${last.key}"));snapshot("history-last-page")
        val resetTag="reminder-event-edit-${last.reminderId}-${last.revision}-${last.key}";tagged(resetTag);assertEquals(pair.second.id,model().state.form!!.noteId)
        androidx.test.espresso.Espresso.pressBack();ready();assertNull(model().state.form)
        assertEquals(2,serial { runtime.repository.all().size });assertEquals(first,serial { runtime.repository.get(first.id)!! })
        assertEquals(second.revision,serial { runtime.repository.get(second.id)!!.revision })
        AndroidSql(context).use { db ->
            val data=JSONObject();listOf("notes","reminders","reminder_events").forEach { table ->data.put(table,JSONArray(db.query("SELECT * FROM $table ORDER BY 1").map { JSONArray(it) })) }
            context.getFileStreamPath("$prefix-database.json").writeText(data.toString(2))
        }
        context.getFileStreamPath("$prefix-result.json").writeText(JSONObject().put("allAssertionsPassed",true).put("fontScale",cfg.fontScale).put("widthDp",cfg.screenWidthDp).put("firstNoteId",pair.first.id).put("secondNoteId",pair.second.id).put("checks",checks).put("historyCount",52).put("naturalRecurrenceTested",false).put("talkbackTested",false).put("normalImeRequested",InstrumentationRegistry.getArguments().getString("normalIme")=="true").put("dismissedBanners",dismissedBanners).toString(2))
    }
}
