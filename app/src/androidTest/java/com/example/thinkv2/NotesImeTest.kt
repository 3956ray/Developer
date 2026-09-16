package com.example.thinkv2

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.notes.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** One targeted 2.0/light/320dp IME check after dismissing the system keyboard's temporary banner. */
class NotesImeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun normalImeScrollableChineseBodyAndFurtherEditing() {
        val ins=InstrumentationRegistry.getInstrumentation();val a=ins.uiAutomation;val context=ins.targetContext
        val notes=MainActivity::class.java.getDeclaredField("notes").apply { isAccessible=true }.get(ui.activity) as NotesModel
        ui.waitUntil(15000) { !notes.state.loading }
        val cfg=ui.activity.resources.configuration
        assertEquals(2.0f,cfg.fontScale,0.01f);assertEquals(320,cfg.screenWidthDp)
        ui.onNodeWithText("新增文字").performClick()
        val body=(1..8).joinToString("\n") { "第${it}段合成正文：今天整理花园，明天继续阅读和记录，长文字可以滚动查看。" }
        ui.onNodeWithText("正文").performScrollTo().performClick().performTextInput(body)
        val info=a.serviceInfo;val old=info.flags;info.flags=old or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;a.serviceInfo=info
        fun shot(name: String) { ui.waitForIdle();a.waitForIdle(300,5000);a.takeScreenshot()!!.let { b ->context.getFileStreamPath("accessibility-normal-ime-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() } }
        fun find(n: AccessibilityNodeInfo?,value: String): AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.text?.toString()==value) { var p: AccessibilityNodeInfo=n;while(!p.isClickable) { val parent=p.parent ?: break;p=parent };if(p.isClickable)return p }
            repeat(n.childCount) { find(n.getChild(it),value)?.let { return it } };return null
        }
        try {
            shot("before-banner-dismiss")
            a.clearCache();val banner=a.windows.asSequence().mapNotNull { find(it.root,"OK") }.firstOrNull()
            if(banner!=null) assertTrue(banner.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            ui.waitForIdle();a.waitForIdle(300,5000)
            ui.onNodeWithText("正文").assertTextContains(body)
            shot("after-banner-dismiss")
            val scroll=ui.onNode(hasScrollAction())
            fun fieldRect(): androidx.compose.ui.geometry.Rect {
                val n=ui.onNodeWithText("正文").fetchSemanticsNode();val p=n.positionInRoot
                return androidx.compose.ui.geometry.Rect(p.x,p.y,p.x+n.size.width,p.y+n.size.height)
            }
            fun alignAndTapEnd(expectedLength: Int): JSONObject {
                val viewport=scroll.fetchSemanticsNode().boundsInRoot
                val margin=32*ui.activity.resources.displayMetrics.density
                repeat(20) {
                    val delta=fieldRect().bottom-(viewport.bottom-margin)
                    if(kotlin.math.abs(delta)>2f) scroll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { action ->assertTrue(action(0f,delta.coerceIn(-viewport.height/2,viewport.height/2))) }
                    ui.waitForIdle()
                }
                a.clearCache();val ime=a.windows.first { it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                val keyboard=android.graphics.Rect();ime.getBoundsInScreen(keyboard)
                val field=fieldRect();val point=androidx.compose.ui.geometry.Offset(field.right-24*ui.activity.resources.displayMetrics.density,field.bottom-8*ui.activity.resources.displayMetrics.density)
                assertTrue("end of the field is above IME",field.bottom<keyboard.top)
                assertTrue("last text line and tap point are in scroll viewport",field.bottom-64*ui.activity.resources.displayMetrics.density>viewport.top && point.y<viewport.bottom)
                ui.onRoot().performTouchInput { click(point) }
                ui.waitForIdle()
                val selection=ui.onNodeWithText("正文").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
                assertEquals("visible pointer tap places caret at true text end",expectedLength,selection.end)
                ui.onNodeWithText("正文").assertIsFocused()
                return JSONObject().put("unclippedFieldBounds",field.toString()).put("viewport",viewport.toString()).put("imeBounds",keyboard.toShortString()).put("actualTap",point.toString()).put("selectionEnd",selection.end).put("textLength",expectedLength)
            }
            val beforeEdit=alignAndTapEnd(body.length)
            shot("visible-end-before-edit")
            ui.onNodeWithText("正文").performTextInput("\n末尾继续输入验证。")
            val expected=body+"\n末尾继续输入验证。"
            assertEquals(expected,notes.state.editor!!.note.body)
            val afterEdit=alignAndTapEnd(expected.length)
            shot("visible-end-after-edit")
            val id=notes.state.editor!!.note.id
            Espresso.pressBack();assertNotNull(notes.state.editor)
            ui.onNodeWithText("返回 · 保留草稿").performClick();ui.waitUntil(10000) { notes.state.editor==null && !notes.state.busy }
            AndroidSql(context).use { db ->assertEquals(expected,db.query("SELECT body FROM drafts WHERE id=?",listOf(id)).single().single()) }
            context.getFileStreamPath("accessibility-normal-ime-result.json").writeText(JSONObject().put("fontScale",cfg.fontScale).put("widthDp",cfg.screenWidthDp)
                .put("keyboardBannerFoundAndDismissed",banner!=null).put("beforeEditingVisibleEnd",beforeEdit).put("afterEditingVisibleEnd",afterEdit).put("furtherEditingDurable",true).put("id",id).put("expectedBody",expected).toString(2))
        } finally { info.flags=old;a.serviceInfo=info }
    }
}
