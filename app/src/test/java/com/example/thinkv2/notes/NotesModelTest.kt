package com.example.thinkv2.notes

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NotesModelTest {
    @get:Rule val temp=TemporaryFolder()
    private fun scenario(test: (NotesModel, CoroutineDispatcher, PythonSql)->Unit) {
        val ui=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val worker=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val sql=PythonSql(temp.newFile())
        val closed=CountDownLatch(1)
        val wrapped=object: Sql by sql { override fun close() { sql.close();closed.countDown() } }
        val model=runBlocking(ui) { NotesModel({ NoteRepository(wrapped) },ui,worker) }
        try { await(ui,model) { !it.loading }; test(model,ui,sql) }
        finally { runBlocking(ui) { model.shutdown() }; assertTrue(closed.await(5,TimeUnit.SECONDS));ui.close() }
    }
    private fun await(ui: CoroutineDispatcher,m: NotesModel,predicate: (NotesState)->Boolean): NotesState {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(System.nanoTime()<deadline) {
            val state=runBlocking(ui) { m.state }
            if(predicate(state)) return state
            Thread.sleep(10)
        }
        error("state_timeout")
    }
    private fun saved(model: NotesModel,ui: CoroutineDispatcher): String {
        runBlocking(ui) { model.newNote();model.body("合成原始文字");model.category("生活");model.save() }
        return await(ui,model) { !it.loading && !it.busy && it.editor==null }.notes.single().id
    }
    @Test fun realViewModelReopenUnchangedSaveAndReturn() = scenario { model,ui,_ ->
        val id=saved(model,ui)
        repeat(2) { round ->
            runBlocking(ui) { model.open(id) };await(ui,model) { it.editor!=null && !it.busy }
            runBlocking(ui) { if(round==0) model.save() else model.back() }
            val end=await(ui,model) { it.editor==null && !it.loading && !it.busy }
            assertNull(end.error);assertEquals(1,end.notes.size);assertTrue(end.drafts.isEmpty())
        }
    }
    @Test fun discardThenReopenEditDoesNotHitOldTombstone() = scenario { model,ui,_ ->
        val id=saved(model,ui)
        runBlocking(ui) { model.open(id) };await(ui,model) { it.editor!=null && !it.busy }
        runBlocking(ui) { repeat(5) { model.body("放弃$it") };model.discard() }
        await(ui,model) { it.editor==null && !it.loading }
        runBlocking(ui) { model.open(id) };await(ui,model) { it.editor!=null && !it.busy }
        runBlocking(ui) { model.body("这次保留");model.save() }
        val result=await(ui,model) { it.editor==null && !it.loading }
        assertEquals("这次保留",result.notes.single().body)
    }
    @Test fun autosaveAndBackKeepDraftSeparateFromFormal() = scenario { model,ui,_ ->
        runBlocking(ui) { model.newNote();model.body("尚未正式保存");model.category("草稿分类") }
        val draft=await(ui,model) { it.draftState==DraftState.SAVED }.editor!!
        runBlocking(ui) { model.back() }
        val home=await(ui,model) { it.editor==null && !it.loading }
        assertTrue(home.notes.isEmpty());assertEquals(draft.note,home.drafts.single())
        runBlocking(ui) { model.open(draft.note.id) }
        assertEquals(draft,await(ui,model) { it.editor!=null && !it.busy }.editor)
    }
    @Test fun formalFailureRetainsEditorAndRetryReallySaves() = scenario { model,ui,sql ->
        val id=saved(model,ui)
        runBlocking(ui) { model.open(id) };await(ui,model) { it.editor!=null && !it.busy }
        sql.execute("CREATE TRIGGER fail_note BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        runBlocking(ui) { model.body("更新失败后重试");model.save() }
        val failure=await(ui,model) { !it.busy && it.error!=null }
        assertNotNull(failure.editor);assertEquals(DraftState.ERROR,failure.draftState)
        assertEquals("合成原始文字",sql.query("SELECT body FROM notes WHERE id=?",listOf(id)).single().single())
        sql.execute("DROP TRIGGER fail_note")
        runBlocking(ui) { model.save() }
        assertEquals("更新失败后重试",await(ui,model) { it.editor==null && !it.loading }.notes.single().body)
    }
    @Test fun searchRefreshesAfterEditingAndCategoryCorrection() = scenario { model,ui,_ ->
        val id=saved(model,ui)
        runBlocking(ui) { model.search("原始") };await(ui,model) { !it.loading }
        runBlocking(ui) { model.open(id) };await(ui,model) { it.editor!=null && !it.busy }
        runBlocking(ui) { model.body("完全更新");model.category("读书");model.save() }
        val result=await(ui,model) { it.editor==null && !it.loading }
        assertEquals("原始",result.query);assertTrue(result.notes.isEmpty())
        runBlocking(ui) { model.search("");model.filter("读书") }
        assertEquals(id,await(ui,model) { !it.loading }.notes.single().id)
    }
}
