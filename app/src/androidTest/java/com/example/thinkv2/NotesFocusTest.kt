package com.example.thinkv2

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.notes.NotesModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotesFocusTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardFocusAndDialogReturn() {
        val ins=InstrumentationRegistry.getInstrumentation();val a=ins.uiAutomation
        val notes=MainActivity::class.java.getDeclaredField("notes").apply { isAccessible=true }.get(ui.activity) as NotesModel
        ui.waitUntil(10000) { !notes.state.loading }
        ui.onNodeWithTag("notes-list").performScrollToNode(hasText("新增文字"))
        ui.waitUntil(5000) { ui.activity.hasWindowFocus() }
        // Establish the start node explicitly: Android may restore a previous window's input focus.
        var requestAccepted=false
        ui.onNodeWithText("新增文字").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { requestAccepted=it() }
        ui.onNodeWithText("新增文字").assertIsFocused()
        ins.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB);ui.waitForIdle()
        ui.onNodeWithText("搜索标题或正文").assertIsFocused()
        a.waitForIdle(250,5000);a.clearCache()
        fun inputFocus(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.isFocused)return n
            repeat(n.childCount) { inputFocus(n.getChild(it))?.let { return it } };return null
        }
        val root=a.rootInActiveWindow
        val keyboard=inputFocus(root)
        val result=JSONObject().put("initialFocusRequestAccepted",requestAccepted).put("rootFindFocusResult",root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.toString()).put("keyboardFocusNode",keyboard?.toString())
        a.takeScreenshot()!!.let { b ->ins.targetContext.getFileStreamPath("accessibility-keyboard-focus.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
        assertNotNull("actual Tab gives input focus",keyboard);assertTrue("focus is exposed on actual node",keyboard!!.isFocused)
        ui.onNodeWithText("新增文字").performClick();ui.onNodeWithText("正文").performTextInput("焦点验证合成正文")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithText("放弃这次编辑").performScrollTo()
        ui.onNodeWithText("放弃这次编辑").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { assertTrue(it()) }
        val info=a.serviceInfo;val old=info.flags;info.flags=old or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;a.serviceInfo=info
        fun find(n: AccessibilityNodeInfo?,label: String): AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.text?.toString()==label || n.contentDescription?.toString()==label) {
                var p: AccessibilityNodeInfo=n
                while(!p.isClickable) { val parent=p.parent ?: break;p=parent }
                if(p.isClickable)return p
            }
            repeat(n.childCount) { find(n.getChild(it),label)?.let { return it } };return null
        }
        try {
            ui.waitForIdle();a.clearCache();val opener=find(a.rootInActiveWindow,"放弃这次编辑")!!
            assertTrue(opener.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(opener.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            ui.onNodeWithText("放弃这次编辑？").assertIsDisplayed();ui.waitForIdle();a.clearCache()
            val cancel=find(a.rootInActiveWindow,"取消")!!;cancel.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ui.waitUntil(5000) { ui.onAllNodesWithText("放弃这次编辑？").fetchSemanticsNodes().isEmpty() }
            SystemClock.sleep(400);a.clearCache()
            val returned=a.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            result.put("afterDismissAccessibilityFocus",returned?.toString()).put("draftBody",notes.state.editor!!.note.body)
            // Frameworks may clear accessibility focus on dialog removal; record separately from TalkBack restoration.
            val available=find(a.rootInActiveWindow,"放弃这次编辑")!!
            assertTrue(available.isVisibleToUser && available.isClickable)
            assertEquals("焦点验证合成正文",notes.state.editor!!.note.body)
            result.put("openerFocusedAfterDismiss",available.isAccessibilityFocused)
            ui.onNodeWithText("放弃这次编辑").assertIsFocused()
            result.put("keyboardFocusRestoredToOpener",true).put("talkbackTest",false)
        } finally { info.flags=old;a.serviceInfo=info;ins.targetContext.getFileStreamPath("accessibility-keyboard-dialog-focus.json").writeText(result.toString(2)) }
    }
}
