package com.example.thinkv2.notes

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NoteRepositoryTest {
    @get:Rule val temp=TemporaryFolder()
    private fun editing(body: String="合成正文",title: String?=null): Editing {
        var n=Note().bodyChanged(body)
        if(title!=null) n=n.titleChanged(title)
        return Editing(n)
    }
    private fun fails(action: ()->Unit) {
        try { action(); fail("must reject") } catch(_: IllegalStateException) { }
    }
    @Test fun titleOwnershipAndUnicode() {
        val original=Note().bodyChanged("  第一行。第二句")
        assertEquals("第一行",original.title)
        val manual=original.titleChanged("自选标题").bodyChanged("全新正文")
        assertEquals("自选标题",manual.title)
        assertEquals("全新正文",manual.titleChanged(" ").title)
        assertEquals("😀".repeat(24)+"…",automaticTitle("😀".repeat(25)))
        val excerpt=matchingExcerpt("😀".repeat(180)+"找到关键词\n原始片段","关键词")
        assertTrue(excerpt.contains("关键词"));assertTrue(excerpt.startsWith("…"))
    }
    @Test fun emptyAndDurableDraftThenSameIdEdit() {
        val file=temp.newFile(); val e=editing().let { it.copy(note=it.note.categoryChanged("生活")) }
        NoteRepository(PythonSql(file)).use { r -> r.initialize(); assertEquals(0,r.search("").total); r.persistDraft(e) }
        NoteRepository(PythonSql(file)).use { r ->
            r.initialize(); assertEquals(e,r.open(e.note.id)); assertEquals(0,r.search("").total)
            r.save(e,100); assertTrue(r.drafts().isEmpty())
        }
        NoteRepository(PythonSql(file)).use { r ->
            r.initialize(); val open=r.open(e.note.id)!!
            assertEquals("生活",open.note.category)
            r.save(open.copy(note=open.note.bodyChanged("更新内容").categoryChanged("读书")),200)
            assertEquals(1,r.search("").total); assertEquals("更新内容",r.find(e.note.id)!!.body)
        }
    }
    @Test fun emptyBodyCannotBecomeFormal() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize(); val e=editing("  \n")
            r.persistDraft(e)
            try { r.save(e); fail() } catch(_: IllegalArgumentException) { }
            assertEquals(0,r.search("").total); assertEquals(e,r.open(e.note.id))
        }
    }
    @Test fun saveRetryAndUnchangedOpenHaveNoPhantomDraft() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize(); val e=editing(); val saved=r.save(e,123)
            assertEquals(saved,r.save(e,999))
            val open=r.open(saved.id)!!; r.persistDraft(open)
            assertEquals(saved,r.save(open,888)); assertTrue(r.drafts().isEmpty())
            r.discard(open); assertEquals(saved,r.find(saved.id))
        }
    }
    @Test fun lateWritesCannotReviveSavedOrDiscardedDrafts() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize(); val e=editing(); r.persistDraft(e); r.save(e)
            fails { r.persistDraft(e) }; assertTrue(r.drafts().isEmpty())
            val other=editing("另一个"); r.persistDraft(other); r.discard(other)
            fails { r.persistDraft(other) }; assertTrue(r.drafts().isEmpty())
            assertNull(r.find(other.note.id))
        }
    }
    @Test fun conflictingEditorsCannotOverwriteNewVersion() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize(); val saved=r.save(editing()); val old=r.open(saved.id)!!
            r.save(old.copy(note=old.note.bodyChanged("新版本")))
            fails { r.save(old.copy(note=old.note.bodyChanged("旧编辑器"))) }
            assertEquals("新版本",r.find(saved.id)!!.body)
        }
    }
    @Test fun realSqlFailureRollsBackFormalAndKeepsCommittedDraft() {
        val sql=PythonSql(temp.newFile())
        NoteRepository(sql).use { r ->
            r.initialize(); val saved=r.save(editing("原文","固定标题"))
            val next=r.open(saved.id)!!.let { it.copy(note=it.note.bodyChanged("新版")) }
            r.persistDraft(next)
            sql.execute("CREATE TRIGGER fail_save BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            fails { r.save(next) }
            assertEquals(saved,r.find(saved.id)); assertEquals(next,r.open(saved.id))
            assertEquals(1,r.search("原文").total); assertEquals(0,r.search("新版").total)
            sql.execute("DROP TRIGGER fail_save"); r.save(next)
            assertEquals("新版",r.find(saved.id)!!.body)
        }
    }
    @Test fun tombstoneFailureRollsBackNoteWrite() {
        val sql=PythonSql(temp.newFile())
        NoteRepository(sql).use { r ->
            r.initialize(); val e=editing();r.persistDraft(e)
            sql.execute("CREATE TRIGGER fail_draft BEFORE INSERT ON drafts WHEN NEW.active=0 BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            fails { r.save(e) }; assertNull(r.find(e.note.id));assertEquals(e,r.open(e.note.id))
        }
    }
    @Test fun literalChineseAndRankingAndEditIndex() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize()
            val body=r.save(editing("中文 关键词 100% _","其他"),300)
            val title=r.save(editing("更多内容","中文片段"),200)
            val exact=r.save(editing("内容","中文"),100)
            assertEquals(listOf(exact.id,title.id,body.id),r.search("中文").notes.map { it.id })
            assertEquals(listOf(body.id),r.search("中文 关键词").notes.map { it.id })
            assertEquals(1,r.search("100%").total);assertEquals(1,r.search("_").total)
            val e=r.open(body.id)!!;r.save(e.copy(note=e.note.bodyChanged("已替换")))
            assertEquals(0,r.search("关键词").total)
            assertEquals(0,r.search("' OR 1=1 --").total)
        }
    }
    @Test fun categoryCorrectionAndPaging() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize()
            repeat(105) { i -> r.save(editing("合成$i").let { it.copy(note=it.note.categoryChanged("读书")) },i.toLong()) }
            val first=r.search("");assertEquals(105,first.total);assertEquals(100,first.notes.size)
            assertEquals(5,r.search("",offset=100).notes.size)
            val e=r.open(first.notes.first().id)!!;r.save(e.copy(note=e.note.categoryChanged("")))
            assertEquals(1,r.search("",category="").total);assertEquals(104,r.search("",category="读书").total)
        }
    }
    @Test fun unknownDatabaseIsPreserved() {
        val sql=PythonSql(temp.newFile());sql.execute("CREATE TABLE precious(value TEXT)")
        NoteRepository(sql).use { r ->
            fails { r.initialize() }
            assertEquals(listOf(listOf("precious")),sql.query("SELECT name FROM sqlite_master WHERE type='table'"))
        }
    }
    @Test fun oldDraftCannotOverwriteNewerDraft() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize();val old=editing(); val fresh=old.copy(note=old.note.bodyChanged("新草稿"))
            r.persistDraft(fresh); fails { r.persistDraft(old) };fails { r.discard(old) };assertEquals(fresh,r.open(old.note.id))
        }
    }
    @Test fun saveRevertedContentClearsDraftWithoutChangingFormal() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize();val formal=r.save(editing("原文"))
            val e=r.open(formal.id)!!
            val temporary=e.copy(note=e.note.bodyChanged("暂时修改"));r.persistDraft(temporary)
            val reverted=e.copy(note=temporary.note.bodyChanged("原文"))
            r.persistDraft(reverted); assertFalse(r.drafts().isEmpty())
            assertEquals(formal,r.save(reverted));assertTrue(r.drafts().isEmpty())
            fails { r.persistDraft(e.copy(note=e.note.bodyChanged("迟到草稿"))) }
            val reopened=r.open(formal.id)!!
            r.save(reopened.copy(note=reopened.note.bodyChanged("再次修改")))
            assertEquals("再次修改",r.find(formal.id)!!.body)
        }
    }
    @Test fun thousandNoteSearchBenchmark() {
        NoteRepository(PythonSql(temp.newFile())).use { r ->
            r.initialize();repeat(1000) { i -> r.save(editing("合成测试记录$i，午后阅读","书单$i")) }
            val times=LongArray(40) { val start=System.nanoTime();r.search("阅读");System.nanoTime()-start }.sorted()
            val p95=times[37]/1_000_000.0
            println("HOST_SQLITE_1000_NOTES_P95_MS=$p95; excludes Android and Compose")
            assertTrue("host reference <500ms",p95<500)
        }
    }
}
