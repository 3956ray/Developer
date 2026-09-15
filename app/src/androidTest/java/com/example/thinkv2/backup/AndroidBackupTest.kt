package com.example.thinkv2.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AndroidBackupTest {
    @Test fun downloadsActualNameCompatibilityProbe() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext;val resolver=context.contentResolver
        val authority="com.android.providers.downloads.documents"
        val parent=android.provider.DocumentsContract.buildDocumentUri(authority,"downloads")
        val children=android.provider.DocumentsContract.buildChildDocumentsUri(authority,"downloads")
        val stem="saf-probe-"+java.util.UUID.randomUUID().toString().take(8)
        val observations=mutableListOf<String>()
        fun name(uri: android.net.Uri): String=resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "<empty cursor>" } ?: "<null cursor>"
        fun ours(): List<Pair<android.net.Uri,String>> {
            val found=mutableListOf<Pair<android.net.Uri,String>>()
            resolver.query(children,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)!!.use {
                while(it.moveToNext()) {
                    val n=it.getString(1)
                    if(n.startsWith(stem) || n.startsWith("未完成-$stem")) found+=android.provider.DocumentsContract.buildDocumentUri(authority,it.getString(0)) to n
                }
            };return found
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        try {
            var handle=android.provider.DocumentsContract.createDocument(resolver,parent,"application/json","$stem.thinkbackup.json")!!
            observations+="created_uri=$handle";observations+="created_name="+name(handle)
            handle=android.provider.DocumentsContract.renameDocument(resolver,handle,"$stem.thinkbackup.json.partial")!!
            observations+="suffix_return_uri=$handle";observations+="suffix_return_name="+name(handle)
            val actual=ours().single();observations+="suffix_actual_uri=${actual.first}";observations+="suffix_actual_name=${actual.second}"
            handle=android.provider.DocumentsContract.renameDocument(resolver,actual.first,"未完成-$stem.thinkbackup.json")!!
            val marked=name(handle);observations+="prefix_return_uri=$handle";observations+="prefix_return_name=$marked"
            assertTrue(marked.startsWith("未完成-"))
        } finally {
            try {
                for(row in ours()) android.provider.DocumentsContract.deleteDocument(resolver,row.first)
                observations+="remaining_probe_files="+ours().size
            } finally {
                context.getFileStreamPath("saf-name-probe.txt").writeText(observations.joinToString("\n")+"\n")
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
    }
    @Test fun platformSnapshotRestoreAndSyntheticUiFixture() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // Fresh isolated emulator only. Retain fixture for real system file picker verification.
        val sourceSql=AndroidSql(context);val source=NoteRepository(sourceSql);source.initialize()
        val c=source.createCategory("备份合成分类")
        val n=source.save(Editing(Note(id="backup-note").bodyChanged("备份合成正文。必须逐字段恢复。").titleChanged("备份合成标题").categorized(c)))
        source.persistDraft(source.open(n.id)!!.let { it.copy(note=it.note.bodyChanged("备份合成未保存草稿。")) })
        source.persistDraft(Editing(Note(id="backup-only-draft").bodyChanged("独有草稿合成内容。")))
        source.discard(Editing(Note(id="backup-discarded").bodyChanged("已放弃的合成草稿")))
        val dead=source.createCategory("备份墓碑分类")
        val t=source.save(Editing(Note(id="backup-trash").bodyChanged("回收站合成内容。").categorized(dead)))
        source.softDelete(source.open(t.id)!!.let { it.copy(note=it.note.bodyChanged("回收站合成草稿。")) });source.deleteCategory(dead)
        ReminderRepository(sourceSql).save(n.id,-1,ReminderRule(Repeat.WEEKLY,LocalDay.parse("2030-01-01"),9,0,17),true,"")
        val backup=BackupRepository(sourceSql);val before=backup.data();val export=backup.export()
        val bytes=ByteArrayOutputStream().also { BackupCodec.write(export,it) }.toByteArray()
        val candidate=BackupCodec.read(bytes.inputStream());assertEquals(before,candidate.data)
        val targetName="backup-isolated-target.db";context.deleteDatabase(targetName)
        AndroidSql(context,targetName).use { sql ->
            NoteRepository(sql).initialize();val target=BackupRepository(sql);val preview=target.preview(candidate)
            assertFalse(preview.records.single { it.sourceId=="backup-discarded" }.visible)
            val result=target.apply(preview);assertEquals(4,result.importedIds.size)
            assertEquals(before.copy(reminders=before.reminders.map { it.copy(enabled=false) }),target.data())
            assertTrue(target.apply(target.preview(candidate)).alreadyImported)
            assertEquals("DISABLED",ReminderRepository(sql).forNote(n.id)!!.status)
            assertEquals("备份合成未保存草稿。",NoteRepository(sql).open(n.id)!!.note.body)
        }
        assertEquals(before,backup.data());source.close();context.deleteDatabase(targetName)
    }
}
