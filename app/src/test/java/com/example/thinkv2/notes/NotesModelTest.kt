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
    @Test fun voiceCursorAndTwoConsecutiveInsertionsPreserveFields() = scenario { model,ui,_ ->
        runBlocking(ui) {
            model.newNote();model.body("前后");model.title("手动标题");model.category("保留分类")
            val ticket=model.voiceAnchor(1)!!
            val next=model.insertVoice(ticket,"第一段")!!
            assertEquals("前第一段后",model.state.editor!!.note.body)
            assertEquals(4,model.state.externalCursor)
            assertEquals(1,next.cursor)
            model.insertVoice(model.voiceAnchor(model.state.externalCursor)!!,"第二段")!!
            assertEquals("前第一段第二段后",model.state.editor!!.note.body)
            assertEquals("手动标题",model.state.editor!!.note.title)
            assertEquals("保留分类",model.state.editor!!.note.category)
            assertEquals(-1L,model.state.editor!!.baseRevision)
        }
    }
    @Test fun voiceLateResultAndCorrectionCannotOverwriteNewEdits() = scenario { model,ui,_ ->
        runBlocking(ui) {
            model.newNote();model.body("原文")
            val ticket=model.voiceAnchor()!!;model.body("更新原文")
            assertNull(model.insertVoice(ticket,"迟到"));assertEquals("更新原文",model.state.editor!!.note.body)
            val correction=model.insertVoice(model.voiceAnchor()!!,"立明")!!
            val corrected=model.correctVoice(correction,"立明","李明同学")!!
            assertEquals("更新原文李明同学",model.state.editor!!.note.body)
            assertEquals(8,model.state.externalCursor)
            model.body("继续编辑");assertNull(model.correctVoice(corrected,"李明同学","李明"))
            assertEquals("继续编辑",model.state.editor!!.note.body)
        }
    }
    @Test fun voicePreviousEditorSessionAndBlankCannotCreateNote() = scenario { model,ui,_ ->
        val ticket=runBlocking(ui) { model.newNote();model.voiceAnchor()!! }
        runBlocking(ui) { assertNull(model.insertVoice(ticket,"  "));model.back() }
        await(ui,model) { it.editor==null && !it.loading }
        runBlocking(ui) { model.newNote();assertNull(model.insertVoice(ticket,"另一会话"));assertTrue(model.state.editor!!.note.body.isEmpty()) }
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
        sql.execute("CREATE TRIGGER fail_note BEFORE UPDATE ON notes BEGIN SELECT RAISE(ABORT,'synthetic'); END")
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
        runBlocking(ui) { model.search("");model.filter(model.state.categories.single { it.name=="读书" }.id) }
        assertEquals(id,await(ui,model) { !it.loading }.notes.single().id)
    }

    @Test fun lifecycleRetainsUnsavedDraftAndReportsMissingCategoryOnRestore() = scenario { model,ui,_ ->
        val id=saved(model,ui)
        runBlocking(ui) { model.open(id) };await(ui,model) { it.editor!=null && !it.busy }
        runBlocking(ui) { model.body("回收站保留的草稿");model.moveToTrash() }
        val trashed=await(ui,model) { !it.loading && !it.busy && it.editor==null }
        assertTrue(trashed.notes.isEmpty());assertEquals(1,trashed.trash.size);assertTrue(trashed.trash.single().hasDraft)
        val category=trashed.categories.single { it.name=="生活" }
        runBlocking(ui) { model.navigate(NotesPage.CATEGORIES) };await(ui,model) { !it.loading }
        runBlocking(ui) { model.deleteCategory(category) };await(ui,model) { !it.busy && !it.loading }
        runBlocking(ui) { model.navigate(NotesPage.TRASH) };val bin=await(ui,model) { !it.loading }
        runBlocking(ui) { model.restore(bin.trash.single().note) }
        val restored=await(ui,model) { !it.busy && !it.loading }
        assertTrue(restored.notice!!.contains("原分类已不存在"));assertEquals(id,restored.notes.single().id)
        runBlocking(ui) { model.navigate(NotesPage.HOME) };await(ui,model) { !it.loading }
        runBlocking(ui) { model.open(id) }
        val editor=await(ui,model) { it.editor!=null && !it.busy }.editor!!
        assertEquals("回收站保留的草稿",editor.note.body);assertEquals("",editor.note.categoryId)
    }
    @Test fun failedTrashAndRestoreKeepVisibleStateUntilRetry() = scenario { model,ui,sql ->
        val id=saved(model,ui)
        runBlocking(ui) { model.open(id) };await(ui,model) { it.editor!=null && !it.busy }
        sql.execute("CREATE TRIGGER reject_trash BEFORE UPDATE OF deleted_at ON notes WHEN NEW.deleted_at>0 BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        runBlocking(ui) { model.moveToTrash() }
        assertNotNull(await(ui,model) { !it.busy && it.error!=null }.editor)
        sql.execute("DROP TRIGGER reject_trash")
        runBlocking(ui) { model.moveToTrash() };val trash=await(ui,model) { it.editor==null && !it.busy && !it.loading }.trash.single()
        sql.execute("CREATE TRIGGER reject_restore BEFORE UPDATE OF deleted_at ON notes WHEN NEW.deleted_at=0 BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        runBlocking(ui) { model.restore(trash.note) }
        val failed=await(ui,model) { !it.busy && it.error!=null };assertEquals(trash,failed.trash.single());assertTrue(failed.notes.isEmpty())
        sql.execute("DROP TRIGGER reject_restore")
        runBlocking(ui) { model.restore(trash.note) }
        val success=await(ui,model) { !it.busy && !it.loading && it.error==null }
        assertTrue(success.trash.isEmpty());assertEquals(id,success.notes.single().id)
    }
}
