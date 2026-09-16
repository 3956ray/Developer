package com.example.thinkv2.voice

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.MainActivity
import com.example.thinkv2.notes.NotesModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VoiceLifecycleTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun model(): VoiceModel=MainActivity::class.java.getDeclaredField("voice").apply { isAccessible=true }.get(ui.activity) as VoiceModel
    private fun notes(): NotesModel=MainActivity::class.java.getDeclaredField("notes").apply { isAccessible=true }.get(ui.activity) as NotesModel
    @Test fun actualMicrophoneBackgroundAndCancelKeepExistingDraft() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val n=notes();val v=model()
        ui.waitUntil(10000) { !n.state.loading }
        ui.onNodeWithText("新增文字").performClick()
        ui.onNodeWithText("正文").performTextInput("后台中断必须保留的合成草稿。")
        ui.waitUntil(15000) { v.state.phase==VoicePhase.IDLE }
        // Separate injection calls retain the down pointer while real wall time elapses.
        ui.onNodeWithText("按住说话").performScrollTo().performTouchInput { down(center) }
        Thread.sleep(700)
        assertEquals(VoicePhase.RECORDING,v.state.phase)
        ui.onNodeWithText("录音中 · 松手处理").performTouchInput { up() }
        ui.waitUntil(5000) { v.state.phase==VoicePhase.IDLE }
        assertTrue(v.state.message.contains("未识别到有效语音"))
        assertEquals("后台中断必须保留的合成草稿。",n.state.editor!!.note.body)
        ui.runOnIdle { v.start(n,null) };Thread.sleep(300)
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.waitUntil(5000) { v.state.phase==VoicePhase.IDLE }
        assertEquals("后台中断必须保留的合成草稿。",n.state.editor!!.note.body)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.runOnIdle { v.start(n,null) }
        Thread.sleep(300)
        ui.runOnIdle { v.cancel() }
        ui.waitUntil(5000) { v.state.phase==VoicePhase.IDLE }
        assertEquals("后台中断必须保留的合成草稿。",n.state.editor!!.note.body)
        assertEquals(-1L,n.state.editor!!.baseRevision)
    }
    @Test fun deniedPlatformPermissionDoesNotChangeText() {
        // Run this method separately after pm clear so runtime permission starts denied.
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val n=notes();val v=model()
        ui.waitUntil(10000) { !n.state.loading }
        ui.onNodeWithText("新增文字").performClick()
        ui.onNodeWithText("正文").performTextInput("拒绝权限后的合成文字。")
        ui.waitUntil(15000) { v.state.phase==VoicePhase.IDLE }
        ui.runOnIdle { v.start(n,null) }
        ui.waitUntil(5000) { v.state.phase==VoicePhase.IDLE }
        assertTrue(v.state.message.contains("权限不可用"))
        assertEquals("拒绝权限后的合成文字。",n.state.editor!!.note.body)
    }
}
