package com.example.thinkv2.backup

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.MainActivity
import com.example.thinkv2.notes.*
import com.example.thinkv2.ai.*
import com.example.thinkv2.voice.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackupRestoreStateDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Suppress("UNCHECKED_CAST") private fun <T> model(name: String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(ui.activity) as T
    @Test fun actualRestoreButtonDisablesInMemoryAiAndReloadsVocabularyAndRejectsStalePreview() {
        val notes=model<NotesModel>("notes");val ai=model<AiModel>("ai");val voice=model<VoiceModel>("voice");val backups=model<BackupModel>("backups")
        ui.waitUntil(10000) { !notes.state.loading && ai.state.loaded }
        ui.runOnIdle { ai.saveConfiguration("https://example.invalid/v1/chat/completions","synthetic","synthetic-private-credential-never-export") }
        ui.waitUntil(10000) { !ai.state.saving };ui.runOnIdle { ai.enable(true) };ui.waitUntil(10000) { !ai.state.saving };assertTrue(ai.state.enabled)
        val correction=Correction("导入原词","导入正确词")
        val data=BackupData(listOf(BackupNote(Note(id="state-note",body="状态合成正文",title="状态合成标题",created=1,updated=1),0)),emptyList(),emptyList(),emptyList(),vocabulary=listOf(correction))
        val file=context.getFileStreamPath("state.thinkbackup.json");file.writeBytes(ByteArrayOutputStream().also { BackupCodec.write(BackupCodec.prepare(data),it) }.toByteArray())
        ui.runOnIdle { notes.navigate(NotesPage.BACKUP);backups.importPicked(Uri.fromFile(file)) }
        ui.waitUntil(10000) { backups.state.preview!=null && !backups.state.busy }
        AndroidSql(context).use { db -> VocabularyRepository(db).replace(emptyList(),listOf(Correction("本机旧词","本机新词"))) }
        ui.onNodeWithTag("backup-list").performScrollToNode(hasText("确认合并恢复"));ui.onNodeWithText("确认合并恢复").performClick()
        ui.waitUntil(10000) { !backups.state.busy && backups.state.error!=null };assertNull(backups.state.preview);assertTrue(ai.state.enabled)
        ui.onNodeWithTag("backup-list").performScrollToNode(hasText("重新预览已选文件"));ui.onNodeWithText("重新预览已选文件").performClick()
        ui.waitUntil(10000) { !backups.state.busy && backups.state.preview!=null }
        ui.onNodeWithTag("backup-list").performScrollToNode(hasText("确认合并恢复"));ui.onNodeWithText("确认合并恢复").performClick()
        ui.waitUntil(10000) { !backups.state.busy && backups.state.preview==null && correction in voice.state.mappings }
        assertNull(backups.state.error);assertFalse(ai.state.enabled);assertFalse(ai.state.consent);assertNull(ai.state.proposal)
        assertEquals(listOf(Correction("本机旧词","本机新词"),correction),voice.state.mappings)
        AndroidSql(context).use { db ->
            assertNotNull(NoteRepository(db).find("state-note"))
            val payload=BackupRepository(db).export().payload.toString(Charsets.UTF_8)
            assertFalse(payload.contains("synthetic-private-credential-never-export"));assertFalse(payload.contains("example.invalid"))
        }
        context.getFileStreamPath("backup-v2-state.json").writeText("{\"stalePreviewRejected\":true,\"actualRestoreButton\":true,\"aiEnabledBefore\":true,\"aiDisabledAfter\":true,\"consentCleared\":true,\"vocabularyReloaded\":true,\"credentialsExcluded\":true}")
    }
}
