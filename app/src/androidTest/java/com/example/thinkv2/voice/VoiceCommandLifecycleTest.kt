package com.example.thinkv2.voice

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.MainActivity
import com.example.thinkv2.notes.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VoiceCommandLifecycleTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    @Suppress("UNCHECKED_CAST") private fun <T> model(name: String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(ui.activity) as T
    private fun ready(): Pair<NotesModel,VoiceModel> {
        val n=model<NotesModel>("notes");val v=model<VoiceModel>("voice")
        ui.waitUntil(10000) { !n.state.loading };ui.runOnIdle { n.newNote();n.body("命令模式生命周期必须保留的草稿") }
        ui.waitUntil(20000) { v.state.phase==VoicePhase.IDLE && n.state.draftState==DraftState.SAVED }
        ui.onNodeWithText("进入命令模式").performScrollTo().performClick();assertTrue(v.state.commandMode)
        return n to v
    }
    @Test fun realMicrophoneCommandCaptureBackgroundAndExplicitExitKeepDraft() {
        ins.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val (n,v)=ready();val before=n.state.editor!!.note
        ui.runOnIdle { v.start(n,null) };Thread.sleep(300);assertTrue(v.active)
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.waitUntil(8000) { v.state.phase==VoicePhase.IDLE };assertFalse(v.state.commandMode);assertNull(v.state.command)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.runOnIdle { v.enterCommands(n);v.start(n,null) };Thread.sleep(250)
        ui.onNodeWithText("退出命令模式").performScrollTo().performClick();ui.waitUntil(8000) { v.state.phase==VoicePhase.IDLE }
        assertEquals(before,n.state.editor!!.note);assertFalse(v.state.commandMode)
        AndroidSql(context).use { db ->assertEquals(before.body,db.query("SELECT body FROM drafts WHERE id=?",listOf(before.id)).single().single());assertTrue(db.query("SELECT * FROM notes").isEmpty()) }
        context.getFileStreamPath("voice-controls-lifecycle.json").writeText("{\"realAndroidMicrophone\":true,\"backgroundInvalidatesCommandMode\":true,\"explicitExitCancelsCapture\":true,\"durableDraftUnchanged\":true,\"recognitionClaim\":\"silence/lifecycle only\"}")
    }
    @Test fun deniedActualMicrophonePermissionCannotProduceCommandOrChangeText() {
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val (n,v)=ready();val before=n.state.editor!!.note
        ui.runOnIdle { v.start(n,null) };ui.waitUntil(8000) { v.state.phase==VoicePhase.IDLE }
        assertNull(v.state.command);assertTrue(v.state.message.contains("权限不可用"));assertFalse(v.state.commandMode);assertEquals(before,n.state.editor!!.note)
        context.getFileStreamPath("voice-controls-permission.json").writeText("{\"actualPermissionDenied\":true,\"noCommand\":true,\"existingTextUnchanged\":true}")
    }
}
