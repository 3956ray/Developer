package com.example.thinkv2.relations

import com.example.thinkv2.notes.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class RelationTest {
    @get:Rule val temp=TemporaryFolder()
    private fun scenario(block: (PythonSql,NoteRepository,RelationRepository)->Unit) { PythonSql(temp.newFile()).use { db ->val n=NoteRepository(db);n.initialize();block(db,n,RelationRepository(db)) } }
    private fun create(n: NoteRepository,title: String="相同标题")=n.save(Editing(Note().bodyChanged("合成正文 $title").titleChanged(title)))
    private fun fails(block: ()->Unit) { try { block();fail("must reject") } catch(_: Exception) {} }
    @Test fun undirectedStableIdentityRejectsSelfDuplicateMissingAndTrash()=scenario { db,n,r ->
        val a=create(n);val b=create(n);val id=r.create(a.id,b.id,100)
        assertEquals(id,r.page(a.id).rows.single().edgeId);assertEquals(id,r.page(b.id).rows.single().edgeId)
        assertEquals(b.id,r.page(a.id).rows.single().note.id);assertEquals(a.id,r.page(b.id).rows.single().note.id)
        fails { r.create(b.id,a.id) };fails { r.create(a.id,a.id) };fails { r.create(a.id,"missing") }
        n.softDelete(n.open(b.id)!!);fails { r.create(a.id,b.id) };assertEquals(1,db.query("SELECT * FROM note_relations").size)
        fails { db.execute("INSERT INTO note_relations VALUES (?,?,?,'manual',1)",listOf(UUID.randomUUID().toString(),"missing-a","missing-b")) }
    }
    @Test fun deletionHidesButRestorationKeepsEdgeAndRemovalNeverDeletesNotes()=scenario { db,n,r ->
        val a=create(n,"甲");val b=create(n,"乙");val id=r.create(a.id,b.id)
        n.softDelete(n.open(b.id)!!);assertEquals(0,r.page(a.id).total)
        n.softDelete(n.open(a.id)!!);n.restore(n.trash().single { it.note.id==b.id }.note);assertEquals(0,r.page(b.id).total)
        n.restore(n.trash().single { it.note.id==a.id }.note);assertEquals(id,r.page(a.id).rows.single().edgeId)
        val before=listOf("notes","drafts").map { db.query("SELECT * FROM $it ORDER BY id") }
        r.remove(a.id,r.page(a.id).rows.single());assertEquals(0,r.page(a.id).total)
        assertEquals(before,listOf("notes","drafts").map { db.query("SELECT * FROM $it ORDER BY id") })
    }
    @Test fun currentDraftTitleCategoryAndLiteralSearchStayInOneStore()=scenario { _,n,r ->
        val a=create(n,"来源");val b=create(n,"旧标题");val c=n.createCategory("原分类")
        r.create(a.id,b.id)
        n.persistDraft(n.open(b.id)!!.let { it.copy(note=it.note.titleChanged("新标题 100%").categorized(c).bodyChanged("搜索_原文")) })
        n.renameCategory(c,"改名分类")
        val row=r.page(a.id).rows.single().note;assertEquals("新标题 100%",row.title);assertEquals("改名分类",row.category)
        assertEquals(1,r.page(a.id,query="100%").total);assertEquals(0,r.page(a.id,query="不存在%").total)
        assertEquals(1,r.page(a.id,query="搜索_").total);assertEquals(0,r.page(a.id,true).total)
    }
    @Test fun fiftyRowPagesHaveExactCountsNoDuplicatesAndCandidatesExcludeCurrentLinks()=scenario { _,n,r ->
        val a=create(n,"来源");val others=(1..55).map { create(n,"目标$it") };others.forEachIndexed { i,b ->r.create(a.id,b.id,(i+1).toLong()) }
        val first=r.page(a.id);val second=r.page(a.id,offset=50)
        assertEquals(55,first.total);assertEquals(50,first.rows.size);assertEquals(5,second.rows.size)
        assertEquals(55,(first.rows+second.rows).map { it.note.id }.distinct().size)
        assertTrue(r.page(a.id,true).candidates.isEmpty());fails { r.page(a.id,query="x".repeat(129)) };fails { r.page(a.id,offset=-1) }
    }
    @Test fun writeFailureRollsBackEdgeAndLeavesAllNoteRowsUnchanged()=scenario { db,n,r ->
        val a=create(n);val b=create(n);val before=db.query("SELECT * FROM notes ORDER BY id")
        val failInsert=object: Sql by db { override fun execute(statement: String,args: List<String>) { db.execute(statement,args);if(statement.startsWith("INSERT INTO note_relations")) error("synthetic_failure") } }
        fails { RelationRepository(failInsert).create(a.id,b.id) };assertTrue(db.query("SELECT * FROM note_relations").isEmpty());assertEquals(before,db.query("SELECT * FROM notes ORDER BY id"))
        r.create(a.id,b.id);val row=r.page(a.id).rows.single()
        val failDelete=object: Sql by db { override fun execute(statement: String,args: List<String>) { db.execute(statement,args);if(statement.startsWith("DELETE FROM note_relations")) error("synthetic_failure") } }
        fails { RelationRepository(failDelete).remove(a.id,row) };assertEquals(row.edgeId,r.page(a.id).rows.single().edgeId);assertEquals(before,db.query("SELECT * FROM notes ORDER BY id"))
    }
    @Test fun staleRemovalCannotDeleteRecreatedRelationWithNewEdgeId()=scenario { _,n,r ->
        val a=create(n);val b=create(n);r.create(a.id,b.id);val old=r.page(a.id).rows.single();r.remove(a.id,old)
        val replacement=r.create(a.id,b.id);fails { r.remove(a.id,old) };assertEquals(replacement,r.page(a.id).rows.single().edgeId)
    }
    @Test fun schemaSixMigrationPreservesPriorDataAndFailureRollsBack()=scenario { db,n,_ ->
        val a=create(n,"迁移原文");n.persistDraft(n.open(a.id)!!.let { it.copy(note=it.note.bodyChanged("迁移草稿")) })
        val event=com.example.thinkv2.calendar.CalendarEvent(com.example.thinkv2.calendar.CalendarRef(mapOf("_id" to "7")),mapOf("_id" to "8","title" to "合成标题"),emptyList())
        db.execute("INSERT INTO calendar_imports VALUES (?,?,?,?,?,?)",listOf(event.key,event.fingerprint,a.id,event.payload,event.originalText(),"a".repeat(64)))
        n.acceptAi(n.open(a.id)!!,com.example.thinkv2.ai.AiAcceptance("synthetic-request","https://example.invalid/chat/completions","synthetic","a".repeat(64),"title","AI原建议","用户最后标题",emptyList()))
        val tables=listOf("notes","drafts","categories","reminders","calendar_imports","ai_acceptances","backup_origins","backup_imports")
        val before=tables.map { db.query("SELECT * FROM $it ORDER BY 1") }
        db.execute("DROP TABLE correction_vocabulary");db.execute("DROP TABLE note_relations");db.execute("PRAGMA user_version=6")
        val fail=object: Sql by db { override fun execute(statement: String,args: List<String>) { db.execute(statement,args);if(statement.startsWith("CREATE INDEX relations_to")) error("synthetic_migration_failure") } }
        fails { NoteRepository(fail).initialize() };assertEquals("6",db.query("PRAGMA user_version").single().single());assertTrue(db.query("SELECT name FROM sqlite_master WHERE name='note_relations'").isEmpty())
        n.initialize();assertEquals("8",db.query("PRAGMA user_version").single().single());assertEquals(before,tables.map { db.query("SELECT * FROM $it ORDER BY 1") })
        val exported=com.example.thinkv2.backup.BackupRepository(db).export();assertTrue(exported.payload.toString(Charsets.UTF_8).contains("\"relations\":[]"))
    }
    @Test fun pagingTokenRejectsOwnAndOtherConnectionChangesBeforeContinuing() {
        val file=temp.newFile()
        PythonSql(file).use { db ->
            val n=NoteRepository(db);n.initialize();val r=RelationRepository(db)
            val a=create(n,"源");val b=create(n,"目标");r.create(a.id,b.id)
            val before=r.page(a.id)
            n.persistDraft(n.open(b.id)!!.let { it.copy(note=it.note.titleChanged("变化标题")) })
            fails { r.page(a.id,offset=50,expectedToken=before.token) }
            val next=r.page(a.id)
            PythonSql(file).use { other ->val note=NoteRepository(other);note.initialize();note.save(note.open(b.id)!!.let { it.copy(note=it.note.bodyChanged("跨连接修改")) }) }
            fails { r.page(a.id,offset=50,expectedToken=next.token) }
            assertEquals("变化标题",r.page(a.id).rows.single().note.title)
        }
    }

}
