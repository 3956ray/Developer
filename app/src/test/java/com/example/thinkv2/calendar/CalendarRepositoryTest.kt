package com.example.thinkv2.calendar

import com.example.thinkv2.notes.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CalendarRepositoryTest {
    @get:Rule val temp=TemporaryFolder()
    private val calendar=CalendarRef(mapOf("_id" to "7","account_name" to "synthetic","account_type" to "LOCAL","calendar_displayName" to "合成日历","visible" to "1"))
    private val range=CalendarRange("2026-09-01","2026-09-30","Asia/Shanghai")
    private fun event(id: String="1",description: String="中文原文\n第二行",extra: Map<String,String?> = emptyMap())=CalendarEvent(calendar,
        mapOf("_id" to id,"calendar_id" to "7","title" to "原始完整标题","description" to description,"dtstart" to "1788220800000","dtend" to "1788224400000","allDay" to "0","eventTimezone" to "Asia/Shanghai")+extra,
        listOf(mapOf("_id" to "80","event_id" to id,"minutes" to "10","method" to "1")))
    private inner class Source(var snapshot: CalendarSnapshot): CalendarSource {
        var allowed=true;var reads=0;var onRead: (Int)->Unit={}
        override fun permitted()=allowed
        override fun calendars()=listOf(calendar)
        override fun read(calendar: CalendarRef,range: CalendarRange): CalendarSnapshot { reads++;onRead(reads);if(!allowed) throw SecurityException();return snapshot }
    }
    private class Store(file: File): AutoCloseable {
        val sql=PythonSql(file);val notes=NoteRepository(sql).apply { initialize() };val importer=CalendarRepository(sql)
        override fun close()=notes.close()
    }
    private fun digest(s: Store)=calendarHash(listOf("notes","drafts","categories","calendar_imports","reminders").map { s.sql.query("SELECT * FROM $it ORDER BY 1") })
    private fun reject(code: String,block: ()->Unit) { try { block();fail("must reject") } catch(e: CalendarFailure) { assertEquals(code,e.code) } }
    @Test fun faithfulTitleOnlyCategoryOriginalsAndRestartIdempotence() {
        val file=temp.newFile();val event=event(description="");val source=Source(CalendarSnapshot(calendar,range,listOf(event)));lateinit var preview: CalendarPreview;lateinit var id: String
        Store(file).use { s ->
            val category=s.notes.createCategory("家事");preview=s.importer.preview(source.snapshot)
            id=s.importer.apply(preview,setOf(event.key),emptySet(),category.id,true,source).noteIds.single()
            val n=s.notes.find(id)!!;assertEquals(event.title,n.title);assertTrue(n.manualTitle);assertEquals(category.id,n.categoryId)
            assertTrue(n.body.contains(event.title));assertEquals(event.originalText(),s.notes.calendarOriginal(id))
            assertEquals(event.payload,s.sql.query("SELECT original_payload FROM calendar_imports").single().single())
            assertTrue(s.sql.query("SELECT * FROM reminders").isEmpty())
            assertEquals(1,s.importer.apply(preview,setOf(event.key),emptySet(),category.id,true,source).alreadyImported)
        }
        Store(file).use { s ->assertEquals(CalendarAction.SAME,s.importer.preview(source.snapshot).rows.single().action);assertEquals(1,s.notes.search("").total);assertNotNull(s.notes.calendarOriginal(id)) }
    }
    @Test fun sourceEditsCreateOnlyExplicitCopyAndNeverOverwriteEditedTrashOrDraft()=Store(temp.newFile()).use { s ->
        val old=event();val source=Source(CalendarSnapshot(calendar,range,listOf(old)))
        val id=s.importer.apply(s.importer.preview(source.snapshot),setOf(old.key),emptySet(),"",true,source).noteIds.single()
        val original=s.notes.calendarOriginal(id)
        val local=s.notes.open(id)!!.let { it.copy(note=it.note.bodyChanged("本机修改").titleChanged("人工标题")) };s.notes.persistDraft(local);s.notes.softDelete(local)
        source.snapshot=CalendarSnapshot(calendar,range,listOf(event(description="源新内容")))
        val preview=s.importer.preview(source.snapshot);assertEquals(CalendarAction.CHANGED,preview.rows.single().action);assertTrue(preview.rows.single().localChanged)
        val before=digest(s);reject("copy_confirmation") { s.importer.apply(preview,setOf(old.key),emptySet(),"",true,source) };assertEquals(before,digest(s))
        val originalRows=listOf("notes","drafts","calendar_imports").map { table ->s.sql.query("SELECT * FROM $table WHERE ${if(table=="calendar_imports") "note_id" else "id"}=?",listOf(id)) }
        val copy=s.importer.apply(preview,setOf(old.key),setOf(old.key),"",true,source).noteIds.single()
        assertNotEquals(id,copy);assertEquals(original,s.notes.calendarOriginal(id));assertTrue(s.notes.isTrashed(id));assertEquals(listOf("本机修改","人工标题"),s.sql.query("SELECT body,title FROM drafts WHERE id=? AND active=1",listOf(id)).single())
        assertEquals(originalRows,listOf("notes","drafts","calendar_imports").map { table ->s.sql.query("SELECT * FROM $table WHERE ${if(table=="calendar_imports") "note_id" else "id"}=?",listOf(id)) })
        assertEquals(2,s.sql.query("SELECT * FROM calendar_imports").size)
        s.notes.restore(s.notes.trash().single().note);val reopened=s.notes.open(id)!!.note;assertEquals("本机修改",reopened.body);assertEquals("人工标题",reopened.title)
    }
    @Test fun sameTitleDateDifferentIdsAndAccountReconstructionAreNotDeduplicatedByText()=Store(temp.newFile()).use { s ->
        val a=event();val b=event("2");val source=Source(CalendarSnapshot(calendar,range,listOf(a,b)))
        val preview=s.importer.preview(source.snapshot);assertEquals(2,preview.rows.count { it.action==CalendarAction.NEW })
        reject("identity_confirmation") { s.importer.apply(preview,setOf(a.key,b.key),emptySet(),"",false,source) }
        s.importer.apply(preview,setOf(a.key,b.key),emptySet(),"",true,source)
        val rebuilt=calendar.copy(fields=calendar.fields+("_id" to "99"));val next=a.copy(calendar=rebuilt)
        assertEquals(CalendarAction.NEW,s.importer.preview(CalendarSnapshot(rebuilt,range,listOf(next))).rows.single().action)
        assertEquals(2,s.notes.search("").total)
    }
    @Test fun sourceDeletionChangeAndPermissionLossBeforeCommitRollBackEveryRow()=Store(temp.newFile()).use { s ->
        val a=event();val source=Source(CalendarSnapshot(calendar,range,listOf(a)));val preview=s.importer.preview(source.snapshot);val before=digest(s)
        source.snapshot=source.snapshot.copy(events=emptyList())
        reject("source_changed") { s.importer.apply(preview,setOf(a.key),emptySet(),"",true,source) };assertEquals(before,digest(s))
        source.snapshot=preview.source;source.reads=0;source.onRead={ if(it==2) source.snapshot=source.snapshot.copy(events=listOf(event(description="提交中源改动"))) }
        reject("source_changed") { s.importer.apply(preview,setOf(a.key),emptySet(),"",true,source) };assertEquals(before,digest(s))
        source.snapshot=preview.source;source.reads=0;source.onRead={ if(it==2) source.allowed=false }
        try { s.importer.apply(preview,setOf(a.key),emptySet(),"",true,source);fail() } catch(_: SecurityException) {}
        assertEquals(before,digest(s))
    }
    @Test fun cancellationDuringFinalSourceReadRollsBackNotesAndMappings()=Store(temp.newFile()).use { s ->
        val a=event();val source=Source(CalendarSnapshot(calendar,range,listOf(a)))
        val preview=s.importer.preview(source.snapshot);val before=digest(s);var current=true
        source.onRead={ if(it==2) current=false }
        reject("cancelled") { s.importer.apply(preview,setOf(a.key),emptySet(),"",true,source) { current } }
        assertEquals(2,source.reads);assertEquals(before,digest(s));assertEquals(0,s.notes.search("").total)
    }
    @Test fun localChangesInvalidatePreviewAndBatchSqlFailureRollsBackMappingsAndNotes()=Store(temp.newFile()).use { s ->
        val a=event();val b=event("2");val source=Source(CalendarSnapshot(calendar,range,listOf(a,b)));val preview=s.importer.preview(source.snapshot)
        s.notes.createCategory("新分类")
        reject("local_changed") { s.importer.apply(preview,setOf(a.key),emptySet(),"",true,source) }
        val next=s.importer.preview(source.snapshot);val before=digest(s);var writes=0
        val faulty=object: Sql by s.sql { override fun execute(statement: String,args: List<String>) { if(statement.startsWith("INSERT INTO calendar_imports") && ++writes==2) error("synthetic_disk_full");s.sql.execute(statement,args) } }
        try { CalendarRepository(faulty).apply(next,setOf(a.key,b.key),emptySet(),"",true,source);fail() } catch(e: IllegalStateException) { assertEquals("synthetic_disk_full",e.message) }
        assertEquals(before,digest(s));assertEquals(0,s.notes.search("").total)
    }
    @Test fun endTimezoneUsesItsOwnLocalTimeAndPreservesRawFields() {
        val e=event(extra=mapOf("eventEndTimezone" to "America/Los_Angeles"))
        assertTrue(e.originalText().contains("2026-09-01 08:00:00 [Asia/Shanghai]"))
        assertTrue(e.originalText().contains("2026-08-31 18:00:00 [America/Los_Angeles]"))
        assertEquals("America/Los_Angeles",e.fields["eventEndTimezone"])
        assertFalse(e.noteBody().contains("源字段原样快照"))
    }
    @Test fun schemaFourMigrationPreservesAllExistingTablesAndOriginalFieldNulls()=Store(temp.newFile()).use { s ->
        val note=s.notes.save(Editing(Note().bodyChanged("迁移前正式正文")));s.notes.persistDraft(s.notes.open(note.id)!!.let { it.copy(note=it.note.bodyChanged("迁移前草稿")) })
        val before=listOf("notes","drafts","categories","reminders","backup_imports","backup_origins").associateWith { s.sql.query("SELECT * FROM $it ORDER BY 1") }
        s.sql.execute("DROP TABLE calendar_imports");s.sql.execute("DROP TABLE ai_acceptances");s.sql.execute("DROP TABLE note_relations");s.sql.execute("PRAGMA user_version=4");s.notes.initialize()
        before.forEach { (table,rows) ->assertEquals(rows,s.sql.query("SELECT * FROM $table ORDER BY 1")) };assertEquals("7",s.sql.query("PRAGMA user_version").single().single())
        val e=event(extra=mapOf("rrule" to "FREQ=WEEKLY;BYDAY=MO,WE","originalInstanceTime" to null,"eventEndTimezone" to "UTC"));assertTrue(e.unconverted);assertTrue(e.payload.contains("\"originalInstanceTime\":null"))
        try { CalendarRange("2026-01-01","2027-02-01","UTC");fail() } catch(_: Exception) {}
    }
}
