package com.example.thinkv2.backup

import com.example.thinkv2.notes.*
import com.example.thinkv2.voice.*
import com.example.thinkv2.calendar.*
import com.example.thinkv2.relations.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class BackupCompatibilityTest {
    @get:Rule val temp=TemporaryFolder()
    private inner class Store: AutoCloseable {
        val db=PythonSql(temp.newFile());val notes=NoteRepository(db).apply { initialize() };val backup=BackupRepository(db)
        override fun close()=db.close()
    }
    private val event=CalendarEvent(CalendarRef(mapOf("_id" to "7","account_name" to "synthetic-local","account_type" to "LOCAL","calendar_displayName" to "合成日历")),mapOf("_id" to "11","title" to "来源标题","description" to "来源描述","dtstart" to "1790000000000","eventTimezone" to "Asia/Taipei"),emptyList())
    private val snapshot=CalendarSnapshot(event.calendar,CalendarRange("2026-09-01","2026-09-30","Asia/Taipei"),listOf(event))
    private val source=object: CalendarSource { override fun permitted()=true;override fun calendars()=listOf(event.calendar);override fun read(calendar: CalendarRef,range: CalendarRange)=snapshot }
    private fun candidate(s: Store)=BackupCodec.read(ByteArrayOutputStream().also { BackupCodec.write(s.backup.export(),it) }.toByteArray().inputStream())
    private fun seed(s: Store): String {
        val cal=CalendarRepository(s.db);val id=cal.apply(cal.preview(snapshot),setOf(event.key),emptySet(),"",true,source).noteIds.single()
        val category=s.notes.createCategory("同名分类");val other=s.notes.save(Editing(Note(id="other").bodyChanged("另一个端点").categorized(category)))
        s.notes.persistDraft(s.notes.open(id)!!.let { it.copy(note=it.note.bodyChanged("本机编辑日历草稿")) })
        s.db.execute("INSERT INTO ai_acceptances VALUES ('accepted','request',?,'title','https://example.invalid/v1/chat/completions','synthetic-model',?,'原建议','已接受标题',1,1)",listOf(id,"a".repeat(64)))
        RelationRepository(s.db).create(id,other.id,1)
        s.notes.softDelete(s.notes.open(other.id)!!)
        VocabularyRepository(s.db).replace(emptyList(),listOf(Correction("纳瓦","纳瓦尔"),Correction("纳瓦","另一个改法")))
        return id
    }
    private val tables=listOf("notes","drafts","categories","reminders","reminder_events","backup_imports","backup_origins","calendar_imports","ai_acceptances","note_relations","correction_vocabulary")
    private fun rows(s: Store)=tables.map { s.db.query("SELECT * FROM $it ORDER BY 1,2") }
    private fun reject(code: String,body: ()->Unit) { try { body();fail("must reject") } catch(e: BackupFailure) { assertEquals(code,e.code) } }
    @Test fun fullRoundTripTrashEdgesProvenanceReceiptsAndSubsequentCalendarImport()=Store().use { s ->Store().use { t ->
        val id=seed(s);val c=candidate(s);val old=rows(s);assertEquals(2,c.schemaVersion)
        t.backup.apply(t.backup.preview(c));assertEquals(c.data,t.backup.data().copy(receipts=emptyList()));assertEquals(old,rows(s))
        val calendar=CalendarRepository(t.db);val p=calendar.preview(snapshot);assertEquals(CalendarAction.SAME,p.rows.single().action)
        assertTrue(p.rows.single().localChanged);val before=rows(t)
        assertEquals(1,calendar.apply(p,setOf(event.key),emptySet(),"",true,source).alreadyImported);assertEquals(before,rows(t))
        assertEquals(id,t.backup.data().calendar.single().noteId)
        Store().use { third -> val next=candidate(t);third.backup.apply(third.backup.preview(next));assertTrue(third.backup.preview(c).alreadyImported) }
    } }
    @Test fun conflictCopiesMapCalendarAiRelationsWithoutChangingOriginals()=Store().use { s ->Store().use { t ->
        val id=seed(s);val c=candidate(s);t.backup.apply(t.backup.preview(c))
        t.notes.persistDraft(t.notes.open(id)!!.let { it.copy(note=it.note.bodyChanged("本机独有保留")) })
        val original=t.db.query("SELECT * FROM drafts WHERE id=?",listOf(id));val originalCalendar=t.db.query("SELECT * FROM calendar_imports")
        val p=t.backup.preview(c.copy(exportId="different-export"));val target=p.records.single { it.sourceId==id }.targetId!!;assertNotEquals(id,target)
        t.backup.apply(p);assertEquals(original,t.db.query("SELECT * FROM drafts WHERE id=?",listOf(id)))
        assertEquals(originalCalendar.single(),t.db.query("SELECT * FROM calendar_imports WHERE note_id=?",listOf(id)).single())
        assertEquals(2,t.backup.data().calendar.size);assertEquals(2,t.backup.data().ai.size);assertEquals(2,t.backup.data().relations.size)
        assertTrue(t.backup.data().relations.any { it.a==target || it.b==target });assertTrue(t.backup.data().ai.any { it.recordId==target })
        assertEquals(CalendarAction.SAME,CalendarRepository(t.db).preview(snapshot).rows.single().action)
        val after=rows(t);assertTrue(t.backup.apply(p).alreadyImported);assertEquals(after,rows(t));candidate(t)
        Unit
    } }
    @Test fun failureAfterEachNewDurableWriteRollsBackIncludingTriggerAndVocabulary()=Store().use { s ->
        seed(s);val c=candidate(s)
        for(prefix in listOf("INSERT INTO calendar_imports","INSERT INTO ai_acceptances","INSERT INTO note_relations","INSERT INTO correction_vocabulary","INSERT INTO backup_imports")) Store().use { t ->
            val before=rows(t);val trigger=t.db.query("SELECT sql FROM sqlite_master WHERE name='relation_active_endpoints'")
            val fail=object: Sql by t.db { override fun execute(statement: String,args: List<String>) { t.db.execute(statement,args);if(statement.startsWith(prefix)) error("synthetic_failure") } }
            val backup=BackupRepository(fail);val p=backup.preview(c)
            try { backup.apply(p);fail("must fail") } catch(_: IllegalStateException) {}
            assertEquals(before,rows(t));assertEquals(trigger,t.db.query("SELECT sql FROM sqlite_master WHERE name='relation_active_endpoints'"))
            t.backup.apply(p);assertEquals(1,t.backup.data().calendar.size)
        }
    }
    @Test fun previewInvalidatedByVocabularySourcesEdgesAndReceipts()=Store().use { s ->
        seed(s);val c=candidate(s)
        val changes=listOf<(Store)->Unit>(
            { VocabularyRepository(it.db).replace(emptyList(),listOf(Correction("旧","新"))) },
            { it.db.execute("INSERT INTO backup_imports VALUES ('another',?,1)",listOf("b".repeat(64))) })
        for(change in changes) Store().use { t -> val p=t.backup.preview(c);change(t);val before=rows(t);reject("stale_preview") { t.backup.apply(p) };assertEquals(before,rows(t)) }
        Store().use { t -> t.backup.apply(t.backup.preview(c));val p=t.backup.preview(c.copy(exportId="new"));t.db.execute("DELETE FROM note_relations");reject("stale_preview") { t.backup.apply(p) } }
        Store().use { t -> t.backup.apply(t.backup.preview(c));val p=t.backup.preview(c.copy(exportId="new"));t.db.execute("UPDATE ai_acceptances SET chosen='changed'");reject("stale_preview") { t.backup.apply(p) } }
    }
    @Test fun vocabularyUnionOrderAndOverflowAreExplicitAndStaleEditorCannotOverwriteRestore()=Store().use { s ->Store().use { t ->
        seed(s);val local=listOf(Correction("纳瓦","本机改法"));VocabularyRepository(t.db).replace(emptyList(),local)
        val p=t.backup.preview(candidate(s));t.backup.apply(p);assertEquals(local+s.backup.data().vocabulary,t.backup.data().vocabulary)
        try { VocabularyRepository(t.db).replace(local,emptyList());fail("must reject") } catch(_: IllegalStateException) {}
        VocabularyRepository(t.db).replace(t.backup.data().vocabulary,(0 until 200).map { Correction("from$it","to$it") })
        val before=rows(t);reject("vocabulary_merge_limit") { t.backup.preview(candidate(s)) };assertEquals(before,rows(t))
    } }
    @Test fun schema7VocabularyMigrationFailureAndRetryPreserveLegacyExactly()=Store().use { s ->
        seed(s);s.db.execute("DROP TABLE correction_vocabulary");s.db.execute("PRAGMA user_version=7")
        val before=s.db.query("SELECT * FROM calendar_imports");val legacy=listOf(Correction("旧词","新词"))
        val adapter=object: Sql by s.db { override fun legacyVocabulary()=legacy }
        val failing=object: Sql by adapter { override fun execute(statement: String,args: List<String>) { adapter.execute(statement,args);if(statement=="DROP TABLE ai_acceptances_v7") error("synthetic_migration") } }
        try { NoteRepository(failing).initialize();fail("must fail") } catch(_: IllegalStateException) {}
        assertEquals("7",s.db.query("PRAGMA user_version").single().single());assertEquals(before,s.db.query("SELECT * FROM calendar_imports"))
        assertTrue(s.db.query("SELECT name FROM sqlite_master WHERE name='correction_vocabulary'").isEmpty())
        NoteRepository(adapter).initialize();assertEquals(legacy,VocabularyRepository(s.db).rows());assertEquals(before,s.db.query("SELECT * FROM calendar_imports"))
        VocabularyRepository(s.db).replace(legacy,emptyList());NoteRepository(adapter).initialize();assertTrue(VocabularyRepository(s.db).rows().isEmpty())
    }
    @Test fun v1ExplicitReadAndV2UnknownFieldsAndInvalidReferencesRejected()=Store().use { s ->
        val fixture=java.io.File("../doc/evidence/backup/exported.thinkbackup.json").takeIf { it.exists() } ?: java.io.File("doc/evidence/backup/exported.thinkbackup.json")
        val old=BackupCodec.read(fixture.inputStream());assertEquals(1,old.schemaVersion);assertTrue(old.data.vocabulary.isEmpty())
        s.backup.apply(s.backup.preview(old));assertTrue(s.notes.search("").total>0)
        val v2=ByteArrayOutputStream().also { BackupCodec.write(s.backup.export(),it) }.toByteArray()
        reject("unsupported_version") { LegacyV1Codec.read(v2.inputStream()) }
        assertEquals(old.data,LegacyV1Codec.read(fixture.inputStream()).data)
        val full=candidate(s).data
        reject("dangling_relation") { BackupCodec.prepare(full.copy(relations=listOf(BackupRelation("edge","absent-a","absent-b","manual",1)))) }
        reject("invalid_ai") { BackupCodec.prepare(full.copy(ai=listOf(BackupAi("a","req",full.notes.first().note.id,"title","https://user:secret@example.invalid/api","m","a".repeat(64),"p","c",1,1)))) }
    }

    @Test fun importedReceiptsPreserveSkipDecisionsAndHashCollisionsFailBeforeAnyWrites()=Store().use { s ->Store().use { t ->
        seed(s);val original=candidate(s)
        t.notes.save(Editing(original.data.notes.first().note.copy(body="本机冲突正文")))
        t.backup.apply(t.backup.preview(original,ConflictPolicy.SKIP))
        val skipped=t.backup.data();assertEquals("本机冲突正文",t.notes.find(original.data.notes.first().note.id)!!.body)
        Store().use { third ->
            third.backup.apply(third.backup.preview(candidate(t)));val before=rows(third)
            assertTrue(third.backup.preview(original).alreadyImported);assertTrue(third.backup.apply(third.backup.preview(original)).alreadyImported)
            assertEquals(before,rows(third)) // Processed with SKIP, not a claim every source record was restored.
        }
        val before=rows(t)
        reject("export_id_reused") { t.backup.preview(original.copy(hash="0".repeat(64))) };assertEquals(before,rows(t))
        val other=original.copy(exportId="other-file",data=original.data.copy(receipts=listOf(BackupReceipt(original.exportId,"0".repeat(64),1))))
        reject("export_id_reused") { t.backup.preview(other) };assertEquals(before,rows(t))
        reject("export_id_reused") { s.backup.preview(original.copy(data=original.data.copy(receipts=listOf(BackupReceipt(original.exportId,original.hash,1))))) }
        assertEquals(skipped,t.backup.data())
    } }
}
