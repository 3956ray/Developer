package com.example.thinkv2.backup

import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class BackupRepositoryTest {
    @get:Rule val temp=TemporaryFolder()
    private inner class Store: AutoCloseable {
        val sql=PythonSql(temp.newFile());val notes=NoteRepository(sql).apply { initialize() };val backup=BackupRepository(sql)
        override fun close() { notes.close() }
    }
    private fun candidate(export: BackupExport)=BackupCodec.read(ByteArrayOutputStream().also { BackupCodec.write(export,it) }.toByteArray().inputStream())
    private fun seed(s: Store): String {
        val c=s.notes.createCategory("分类");val n=s.notes.save(Editing(Note(id="same-record").bodyChanged("正式内容").titleChanged("人工标题").categorized(c)))
        s.notes.persistDraft(s.notes.open(n.id)!!.let { it.copy(note=it.note.bodyChanged("尚未保存的不同草稿")) })
        s.notes.persistDraft(Editing(Note(id="only-draft").bodyChanged("独有草稿").categorized(c)))
        ReminderRepository(s.sql).save(n.id,-1,ReminderRule(Repeat.WEEKLY,LocalDay.parse("2026-09-16"),9,0,17),true,"")
        return n.id
    }
    private fun digest(s: Store)=BackupCodec.sha(StrictJson.encode(listOf("notes","drafts","categories","reminders","reminder_events","backup_origins","backup_imports").map { s.sql.query("SELECT * FROM $it ORDER BY 1") }))
    private fun reject(code: String,block: ()->Unit) { try { block();fail("must reject") } catch(e: BackupFailure) { assertEquals(code,e.code) } }
    @Test fun exportEmptyRestorePreservesEveryFieldExceptReminderDisabled()=Store().use { source ->Store().use { target ->
        val id=seed(source);val before=digest(source);val c=candidate(source.backup.export());val preview=target.backup.preview(c)
        assertEquals(before,digest(source));assertEquals(0,target.notes.search("").total)
        val result=target.backup.apply(preview);assertEquals(2,result.importedIds.size)
        val expected=c.data.copy(reminders=c.data.reminders.map { it.copy(enabled=false) })
        assertEquals(expected,target.backup.data().copy(receipts=emptyList()));assertEquals("尚未保存的不同草稿",target.notes.open(id)!!.note.body)
        assertEquals("DISABLED",ReminderRepository(target.sql).forNote(id)!!.status)
        assertTrue(target.sql.query("SELECT * FROM reminder_events").isEmpty())
    } }
    @Test fun ownExportOfTrashAndInactiveCategoryAndDiscardWatermarksIsValid()=Store().use { source ->Store().use { target ->
        val id=seed(source);val cat=source.notes.categories().single()
        source.notes.softDelete(source.notes.open(id)!!);source.notes.deleteCategory(cat)
        source.notes.discard(source.notes.open("only-draft")!!)
        val c=candidate(source.backup.export());target.backup.apply(target.backup.preview(c))
        assertEquals(c.data,target.backup.data().copy(receipts=emptyList()));assertEquals("尚未保存的不同草稿",target.notes.trash().single().let { target.notes.restore(it.note);target.notes.open(id)!!.note.body })
        assertFalse(ReminderRepository(target.sql).forNote(id)!!.requested)
    } }
    @Test fun conflictsCopyWholeRecordIncludingDraftReminderAndCategoryMapping()=Store().use { source ->Store().use { target ->
        val id=seed(source);val first=candidate(source.backup.export());target.backup.apply(target.backup.preview(first))
        val old=target.notes.find(id);val oldDraft=target.notes.open(id);val oldReminder=ReminderRepository(target.sql).forNote(id)
        val category=source.notes.categories().single();source.notes.renameCategory(category,"备份新分类")
        val changed=candidate(source.backup.export());val preview=target.backup.preview(changed)
        assertEquals(2,preview.records.count { it.action==ImportAction.COPY })
        val applied=target.backup.apply(preview);assertEquals(2,applied.copied)
        assertEquals(old,target.notes.find(id));assertEquals(oldDraft,target.notes.open(id));assertEquals(oldReminder,ReminderRepository(target.sql).forNote(id))
        val copied=preview.records.single { it.sourceId==id }.targetId!!
        assertNotEquals(id,copied);assertEquals("尚未保存的不同草稿",target.notes.open(copied)!!.note.body)
        assertEquals("备份新分类",target.notes.find(copied)!!.category)
        assertNotEquals(old!!.categoryId,target.notes.find(copied)!!.categoryId)
        assertFalse(ReminderRepository(target.sql).forNote(copied)!!.requested)
        assertNotEquals(oldReminder!!.id,ReminderRepository(target.sql).forNote(copied)!!.id)
        assertTrue(target.backup.data().origins.any { it.recordId==copied && it.sourceId==id })
    } }
    @Test fun formalSameButDifferentDraftOrRuleIsConflict()=Store().use { source ->Store().use { target ->
        seed(source);val c=candidate(source.backup.export());target.backup.apply(target.backup.preview(c))
        // Only enablement differs after import; the formal note is exactly equal.
        val other=c.copy(exportId="another-export")
        assertEquals(ImportAction.COPY,target.backup.preview(other).records.single { it.sourceId=="same-record" }.action)
        val e=target.notes.open("same-record")!!;target.notes.persistDraft(e.copy(note=e.note.bodyChanged("本机草稿不同")))
        assertEquals(ImportAction.COPY,target.backup.preview(other,ConflictPolicy.COPY).records.single { it.sourceId=="same-record" }.action)
    } }
    @Test fun skipConflictKeepsLocalAndSkipsDependenciesWithoutNameMerge()=Store().use { source ->Store().use { target ->
        seed(source);target.notes.createCategory("分类")
        val c=candidate(source.backup.export());val preview=target.backup.preview(c,ConflictPolicy.SKIP)
        assertEquals(1,preview.categories.count { it.action==ImportAction.SKIP });assertTrue(preview.records.all { it.action==ImportAction.SKIP })
        target.backup.apply(preview);assertEquals(0,target.notes.search("").total);assertEquals(1,target.notes.categories().size)
        assertTrue(target.backup.apply(target.backup.preview(c)).alreadyImported)
    } }
    @Test fun categoryNameCollisionCopiesWithoutMergingAndKeepsUnicodeValid()=Store().use { source ->Store().use { target ->
        val name="a".repeat(49)+"🙂后缀";val c=source.notes.createCategory(name);source.notes.save(Editing(Note().bodyChanged("内容").categorized(c)))
        val local=target.notes.createCategory(name);val file=candidate(source.backup.export());val preview=target.backup.preview(file)
        target.backup.apply(preview);assertEquals(2,target.notes.categories().size);assertEquals(local,target.notes.categories().single { it.id==local.id })
        assertNotEquals(local.id,target.notes.search("").notes.single().categoryId)
        candidate(target.backup.export()) // The copied name must not split a surrogate pair.
        Unit
    } }
    @Test fun previewIsReadOnlyAndStaleOnEveryRelevantMutation() {
        val changes=listOf<(Store)->Unit>(
            { s ->val e=s.notes.open("same-record")!!;s.notes.persistDraft(e.copy(note=e.note.bodyChanged("变化草稿"))) },
            { s ->s.notes.renameCategory(s.notes.categories().single(),"变化分类") },
            { s ->s.notes.softDelete(s.notes.open("same-record")!!) },
            { s ->val r=ReminderRepository(s.sql);val p=r.forNote("same-record")!!;r.save(p.noteId,p.revision,p.rule,false,"") })
        for(change in changes) Store().use { source ->Store().use { target ->
            seed(source);val c=candidate(source.backup.export());target.backup.apply(target.backup.preview(c))
            val next=c.copy(exportId="new-export");val before=digest(target);val preview=target.backup.preview(next);assertEquals(before,digest(target))
            change(target);val changed=digest(target);reject("stale_preview") { target.backup.apply(preview) };assertEquals(changed,digest(target))
        } }
    }
    @Test fun applyFailureRollsBackNotesCategoriesOriginsAndReceipt()=Store().use { source ->Store().use { target ->
        seed(source);val preview=target.backup.preview(candidate(source.backup.export()));val before=digest(target)
        target.sql.execute("CREATE TRIGGER fail_import BEFORE INSERT ON reminders BEGIN SELECT RAISE(ABORT,'synthetic disk failure'); END")
        try { target.backup.apply(preview);fail("must fail") } catch(_: IllegalStateException) {}
        assertEquals(before,digest(target));target.sql.execute("DROP TRIGGER fail_import")
        target.backup.apply(preview);assertEquals(1,target.notes.search("").total)
    } }
    @Test fun repeatedExportIdIsIdempotentAndDifferentHashIsRejected()=Store().use { source ->Store().use { target ->
        seed(source);val c=candidate(source.backup.export());target.backup.apply(target.backup.preview(c));val before=digest(target)
        assertTrue(target.backup.preview(c).alreadyImported);assertTrue(target.backup.apply(target.backup.preview(c)).alreadyImported)
        assertEquals(before,digest(target));reject("export_id_reused") { target.backup.preview(c.copy(hash="0".repeat(64))) };assertEquals(before,digest(target))
    } }
    @Test fun sameWholeContentSkipsButRetainsExistingEnabledReminder()=Store().use { source ->
        seed(source);val c=candidate(source.backup.export());val p=source.backup.preview(c)
        assertTrue(p.records.all { it.action==ImportAction.SAME });val old=ReminderRepository(source.sql).forNote("same-record")!!
        source.backup.apply(p);assertEquals(old,ReminderRepository(source.sql).forNote("same-record"));assertTrue(old.requested)
    }
}
