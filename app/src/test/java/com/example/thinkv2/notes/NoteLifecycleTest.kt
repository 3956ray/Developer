package com.example.thinkv2.notes

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteLifecycleTest {
    @get:Rule val temp=TemporaryFolder()
    private fun fails(action: ()->Unit) { try { action();fail("must reject") } catch(_: IllegalStateException) { } }
    private fun record(r: NoteRepository,category: Category?=null): Note = r.save(Editing(
        Note().bodyChanged("正式合成正文").titleChanged("人工标题").categorized(category)))
    private fun legacy(db: Sql) {
        val fields="id TEXT PRIMARY KEY,body TEXT NOT NULL,title TEXT NOT NULL,manual INTEGER NOT NULL,category TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,revision INTEGER NOT NULL"
        db.execute("CREATE TABLE notes ($fields,title_fold TEXT NOT NULL,body_fold TEXT NOT NULL)")
        db.execute("CREATE INDEX notes_recent ON notes(updated DESC,id)")
        db.execute("CREATE TABLE drafts ($fields,base_revision INTEGER NOT NULL,active INTEGER NOT NULL)")
        db.execute("INSERT INTO notes VALUES ('legacy','原始正文','自选标题',1,'原分类',100,200,2,'自选标题','原始正文')")
        db.execute("INSERT INTO drafts VALUES ('legacy','不同草稿正文','草稿标题',1,' 仅草稿分类 ',100,201,5,2,1)")
        db.execute("INSERT INTO drafts VALUES ('draft-only','新笔记草稿','首句',0,'孤立草稿分类',300,300,1,-1,1)")
        db.execute("PRAGMA user_version=1")
    }
    @Test fun migrationPreservesExactLegacyNotesAndDraftOnlyCategories() {
        val file=temp.newFile();PythonSql(file).use(::legacy)
        var ids=emptyMap<String,String>()
        NoteRepository(PythonSql(file)).use { r ->
            r.initialize();val formal=r.find("legacy")!!;val draft=r.open("legacy")!!
            assertEquals("原始正文",formal.body);assertEquals("自选标题",formal.title);assertTrue(formal.manualTitle)
            assertEquals(2L,formal.revision);assertEquals(100L,formal.created);assertEquals(200L,formal.updated)
            assertEquals("不同草稿正文",draft.note.body);assertEquals(" 仅草稿分类 ",draft.note.category)
            assertEquals(5L,draft.note.revision);assertEquals(2L,draft.baseRevision)
            assertEquals("孤立草稿分类",r.open("draft-only")!!.note.category)
            assertTrue(r.open("draft-only")!!.note.categoryId.isNotEmpty())
            ids=r.categories().associate { it.name to it.id };assertEquals(3,ids.size)
            assertEquals(ids["原分类"],formal.categoryId);assertEquals("manual",formal.categorySource)
        }
        NoteRepository(PythonSql(file)).use { r ->r.initialize();r.initialize();assertEquals(ids,r.categories().associate { it.name to it.id });assertEquals(2,r.drafts().size) }
    }
    @Test fun migrationFailureRollsBackSchemaVersionAndOriginalRows() {
        val sql=PythonSql(temp.newFile());legacy(sql)
        val original=sql.query("SELECT * FROM notes");val drafts=sql.query("SELECT * FROM drafts")
        sql.execute("CREATE TRIGGER reject_migration BEFORE UPDATE ON drafts BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        NoteRepository(sql).use { r ->
            fails { r.initialize() }
            assertEquals(listOf(listOf("1")),sql.query("PRAGMA user_version"))
            assertEquals(original,sql.query("SELECT * FROM notes"));assertEquals(drafts,sql.query("SELECT * FROM drafts"))
            assertFalse(sql.query("PRAGMA table_info(notes)").any { it[1]=="category_id" })
            assertTrue(sql.query("SELECT name FROM sqlite_master WHERE name='categories'").isEmpty())
            sql.execute("DROP TRIGGER reject_migration");r.initialize();assertNotNull(r.find("legacy"))
        }
    }
    @Test fun categoryIdsRenameAndManualProvenanceSurviveReopen() {
        val file=temp.newFile();var id="";var categoryId=""
        NoteRepository(PythonSql(file)).use { r ->
            r.initialize();val cat=r.createCategory("读书");categoryId=cat.id
            val automatic=Note().bodyChanged("合成").categorized(cat).copy(categorySource="auto")
            val saved=r.save(Editing(automatic));id=saved.id
            assertEquals("auto",saved.categorySource)
            val e=r.open(id)!!;r.save(e.copy(note=e.note.categorized(cat)))
            r.renameCategory(cat,"阅读")
            assertEquals(categoryId,r.find(id)!!.categoryId);assertEquals("manual",r.find(id)!!.categorySource)
            assertEquals("阅读",r.find(id)!!.category)
        }
        NoteRepository(PythonSql(file)).use { r ->r.initialize();assertEquals(categoryId,r.categories().single().id);assertEquals("阅读",r.find(id)!!.category) }
    }
    @Test fun deletingFormalCategoryDoesNotChangeDifferentDraftCategory() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize();val a=r.createCategory("正式分类");val b=r.createCategory("草稿分类")
            val formal=record(r,a);val old=r.open(formal.id)!!
            val draft=old.copy(note=old.note.bodyChanged("草稿正文仍保留").categorized(b));r.persistDraft(draft)
            r.deleteCategory(a)
            val current=r.find(formal.id)!!;val kept=r.open(formal.id)!!
            assertEquals("",current.categoryId);assertEquals(formal.body,current.body)
            assertEquals(b.id,kept.note.categoryId);assertEquals(draft.note.body,kept.note.body)
            assertEquals(current.revision,kept.baseRevision)
            fails { r.save(old.copy(note=old.note.bodyChanged("迟到写入"))) }
            r.save(kept);assertEquals(b.id,r.find(formal.id)!!.categoryId)
        }
    }
    @Test fun deletingDraftCategoryDoesNotChangeFormalCategoryOrBody() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize();val a=r.createCategory("甲");val b=r.createCategory("乙")
            val formal=record(r,a);val e=r.open(formal.id)!!
            r.persistDraft(e.copy(note=e.note.bodyChanged("保留的草稿").categorized(b)))
            r.deleteCategory(b)
            assertEquals(a.id,r.find(formal.id)!!.categoryId);assertEquals(formal.body,r.find(formal.id)!!.body)
            assertEquals("",r.open(formal.id)!!.note.categoryId);assertEquals("保留的草稿",r.open(formal.id)!!.note.body)
        }
    }
    @Test fun renamedCategoryInvalidatesOldEditorsButKeepsDraftUsable() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize();val cat=r.createCategory("原名");val formal=record(r,cat);val old=r.open(formal.id)!!
            r.persistDraft(old.copy(note=old.note.bodyChanged("草稿")))
            r.renameCategory(cat,"新名")
            fails { r.persistDraft(old.copy(note=old.note.bodyChanged("旧对象"))) }
            val fresh=r.open(formal.id)!!;assertEquals("草稿",fresh.note.body);assertEquals("新名",fresh.note.category)
            r.save(fresh);assertEquals("草稿",r.find(formal.id)!!.body)
        }
    }
    @Test fun softDeleteRetainsLatestDraftAndRestoreOriginalIdWithMissingCategoryNotice() {
        val file=temp.newFile();var id="";var formalBody=""
        NoteRepository(PythonSql(file)).use { r ->
            r.initialize();val cat=r.createCategory("稍后删除的分类");val formal=record(r,cat);id=formal.id;formalBody=formal.body
            val e=r.open(id)!!;val draft=e.copy(note=e.note.bodyChanged("还未正式保存的内容"))
            r.softDelete(draft,1000)
            assertNull(r.find(id));assertNull(r.open(id));assertTrue(r.drafts().isEmpty());assertEquals(0,r.search("正式").total)
            val trash=r.trash().single();assertEquals(id,trash.note.id);assertTrue(trash.hasDraft);assertEquals(formal.body,trash.note.body)
            fails { r.save(draft) };fails { r.persistDraft(draft) }
            r.deleteCategory(cat);assertTrue(r.trash().single().categoryMissing)
        }
        NoteRepository(PythonSql(file)).use { r ->
            r.initialize();val result=r.restore(r.trash().single().note)
            assertTrue(result.categoryMissing);assertEquals(id,result.note.id);assertEquals(formalBody,result.note.body)
            assertEquals("",result.note.categoryId);assertEquals("还未正式保存的内容",r.open(id)!!.note.body)
            assertEquals("",r.open(id)!!.note.categoryId);assertTrue(r.trash().isEmpty());assertEquals(1,r.search("正式").total)
            val latest=r.open(id)!!;r.save(latest);assertEquals(1,r.search("还未").total)
        }
    }
    @Test fun missingDraftCategoryAlsoProducesRestoreNotice() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize();val a=r.createCategory("保留");val b=r.createCategory("删除");val n=record(r,a)
            val e=r.open(n.id)!!;r.softDelete(e.copy(note=e.note.bodyChanged("草稿内容").categorized(b)))
            r.deleteCategory(b);val result=r.restore(r.trash().single().note)
            assertTrue(result.categoryMissing);assertEquals(a.id,result.note.categoryId);assertEquals("",r.open(n.id)!!.note.categoryId)
        }
    }
    @Test fun failedSoftDeleteAndRestoreAreAtomic() {
        val sql=PythonSql(temp.newFile())
        NoteRepository(sql).use { r ->
            r.initialize();val n=record(r);val e=r.open(n.id)!!;val latest=e.copy(note=e.note.bodyChanged("最新草稿"))
            r.persistDraft(latest)
            sql.execute("CREATE TRIGGER reject_trash BEFORE UPDATE OF deleted_at ON notes WHEN NEW.deleted_at>0 BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            fails { r.softDelete(latest) };assertEquals(n,r.find(n.id));assertEquals(latest,r.open(n.id));assertTrue(r.trash().isEmpty())
            sql.execute("DROP TRIGGER reject_trash");r.softDelete(latest)
            val trashed=r.trash().single()
            sql.execute("CREATE TRIGGER reject_restore BEFORE INSERT ON drafts BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            fails { r.restore(trashed.note) };assertEquals(trashed,r.trash().single());assertNull(r.find(n.id))
            sql.execute("DROP TRIGGER reject_restore");r.restore(trashed.note);assertEquals(latest.note.body,r.open(n.id)!!.note.body)
            fails { r.restore(trashed.note) };assertEquals(1,r.search("").total)
        }
    }
    @Test fun categoryMutationFailureRollsBackEverything() {
        val sql=PythonSql(temp.newFile())
        NoteRepository(sql).use { r ->
            r.initialize();val c=r.createCategory("原分类");val n=record(r,c);val e=r.open(n.id)!!
            r.persistDraft(e.copy(note=e.note.bodyChanged("草稿")))
            val previous=r.open(n.id)
            sql.execute("CREATE TRIGGER reject_category BEFORE INSERT ON drafts BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            fails { r.deleteCategory(c) };assertEquals(c,r.categories().single());assertEquals(n,r.find(n.id));assertEquals(previous,r.open(n.id))
            fails { r.renameCategory(c,"新分类") };assertEquals(c,r.categories().single())
        }
    }
    @Test fun duplicateNamesAndStaleCategoryConfirmationAreRejected() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize();val c=r.createCategory("唯一");fails { r.createCategory("唯一") }
            r.renameCategory(c,"新名");fails { r.deleteCategory(c) };assertEquals("新名",r.categories().single().name)
        }
    }
}
