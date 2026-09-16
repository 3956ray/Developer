package com.example.thinkv2

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Bounded navigation with the already installed service. No speech audio is recorded. */
class TalkBackServiceTest {
    @Test fun installedServiceGestureNavigation() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val a=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String)=ParcelFileDescriptor.AutoCloseInputStream(a.executeShellCommand(command)).bufferedReader().use { it.readText() }.trim()
        val oldServices=shell("settings get secure enabled_accessibility_services")
        val oldEnabled=shell("settings get secure accessibility_enabled")
        val service="com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        val steps=JSONArray();val result=JSONObject().put("previousServices",oldServices).put("previousEnabled",oldEnabled).put("steps",steps).put("speechAudioRecorded",false)
        fun save(name: String) {
            context.getFileStreamPath("accessibility-talkback-$name.json").writeText(result.toString(2))
            a.takeScreenshot()?.let { b ->context.getFileStreamPath("accessibility-talkback-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
        }
        fun waitFor(timeout: Long=8000,condition: ()->Boolean): Boolean { val until=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<until) { if(condition())return true;SystemClock.sleep(100) };return false }
        fun text(n: AccessibilityNodeInfo?): String { if(n==null)return "";return listOfNotNull(n.text?.toString(),n.contentDescription?.toString()).joinToString(" ")+ (0 until n.childCount).joinToString(" ") { text(n.getChild(it)) } }
        fun find(n: AccessibilityNodeInfo?,label: String): AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.text?.toString()==label || n.contentDescription?.toString()==label)return n
            repeat(n.childCount) { find(n.getChild(it),label)?.let { return it } };return null
        }
        try {
            val installed=shell("cmd package query-services --brief -a android.accessibilityservice.AccessibilityService")
            assertTrue(installed.contains("com.google.android.marvin.talkback/.TalkBackService"));result.put("installedServices",installed)
            shell("settings put secure enabled_accessibility_services $service");shell("settings put secure accessibility_enabled 1")
            assertTrue(waitFor { shell("dumpsys accessibility").contains("TalkBackService") })
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(waitFor { a.rootInActiveWindow!=null });SystemClock.sleep(1800)
            // Dismiss only observed first-run tutorial controls, without changing service preferences.
            repeat(3) {
                a.clearCache();val root=a.rootInActiveWindow;val visible=text(root)
                steps.put(JSONObject().put("setupWindow",visible.take(4000)))
                if(visible.contains("Welcome to TalkBack") || visible.contains("TalkBack tutorial") || visible.contains("欢迎使用TalkBack")) {
                    val close=find(root,"Close") ?: find(root,"CLOSE") ?: find(root,"关闭")
                    close?.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(600)
                }
            }
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(waitFor { text(a.rootInActiveWindow).contains("新增文字") });save("enabled")
            result.put("serviceStateBeforeNavigation",shell("dumpsys accessibility"))
            result.put("talkbackVersion",context.packageManager.getPackageInfo("com.google.android.marvin.talkback",0).versionName)
            var found=false;var modifier=57
            // Official default (Alt) and enhanced (Action/Meta) keymaps; do not change service preferences.
            for(key in listOf(57,117)) {
                modifier=key;shell("input keycombination $key 113 21");SystemClock.sleep(400)
                for(index in 0 until 5) {
                    a.clearCache();val focused=a.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    val label=text(focused);steps.put(JSONObject().put("input","TalkBack keymap $key, first/next item").put("focus",label).put("node",focused?.toString()))
                    if(label.contains("新增文字")) { found=true;break }
                    shell("input keycombination $key 22");SystemClock.sleep(400)
                }
                if(found)break
            }
            save("keyboard-focus");assertTrue("TalkBack keyboard navigation should focus new-note button",found)
            shell("input keycombination $modifier ${if(modifier==57) 66 else 62}")
            assertTrue("TalkBack keyboard activation should open editor",waitFor { text(a.rootInActiveWindow).contains("返回 · 保留草稿") })
            result.put("usedModifierKeycode",modifier)
            result.put("keyboardNavigationFocusedNewNote",true).put("talkbackKeyboardActivationOpenedEditor",true).put("serviceState",shell("dumpsys accessibility"))
            save("editor")
        } finally {
            if(oldServices=="null")shell("settings delete secure enabled_accessibility_services") else shell("settings put secure enabled_accessibility_services $oldServices")
            if(oldEnabled=="null")shell("settings delete secure accessibility_enabled") else shell("settings put secure accessibility_enabled $oldEnabled")
            result.put("restoredServices",shell("settings get secure enabled_accessibility_services")).put("restoredEnabled",shell("settings get secure accessibility_enabled"))
            context.getFileStreamPath("accessibility-talkback-result.json").writeText(result.toString(2))
        }
    }
}
