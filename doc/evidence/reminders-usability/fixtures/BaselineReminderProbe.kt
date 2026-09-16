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

    @Test fun baselineLargeFontErrorAndIme() {
        ready()
        val note=serial { NoteRepository(AndroidSql(context)).use { r ->r.initialize();r.save(Editing(Note().bodyChanged("提醒甲：合成验证正文。").titleChanged("相同标题"))) } }
        ui.runOnIdle { model().open(note.id);notes().showReminders() };ready()
        snapshot("form-top")
        ui.onNodeWithText("每周（可多选）").performScrollTo().performClick()
        ui.onNodeWithText("保存提醒规则").performScrollTo().performClick()
        snapshot("validation-error")
        ui.onNodeWithText("开始日期 YYYY-MM-DD").performScrollTo().performClick()
        ui.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
        snapshot("error-with-ime")
        context.getFileStreamPath("$prefix-compose.txt").writeText(ui.onAllNodes(isRoot()).printToString())
    }
}
