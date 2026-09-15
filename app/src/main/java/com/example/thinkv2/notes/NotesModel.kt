package com.example.thinkv2.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import java.util.concurrent.Executors

enum class DraftState { UNSAVED, SAVING, SAVED, FORMAL, ERROR }
data class NotesState(
    val notes: List<Note> = emptyList(), val drafts: List<Note> = emptyList(),
    val categories: List<String> = emptyList(), val query: String = "", val category: String? = null,
    val total: Int = 0, val loading: Boolean = true, val busy: Boolean = false,
    val editor: Editing? = null, val draftState: DraftState = DraftState.UNSAVED,
    val error: String? = null,
)

/** UI state runs on main; one worker owns all database operations. */
class NotesModel(
    private val createRepository: () -> NoteRepository,
    ui: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val worker: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
) : ViewModel() {
    var state by mutableStateOf(NotesState())
        private set
    private val scope = CoroutineScope(SupervisorJob()+ui)
    private var repository: NoteRepository? = null
    private var timer: Job? = null
    private var searchToken = 0L
    private var editorToken = 0L
    private fun store(): NoteRepository = repository ?: createRepository().also {
        try { it.initialize(); repository=it } catch(e: Exception) { it.close(); throw e }
    }
    init { refresh() }
    fun refresh(more: Boolean = false) {
        if(state.editor != null || state.busy || (more && state.loading)) return
        val token=++searchToken
        val query=state.query; val category=state.category
        val prior=if(more) state.notes else emptyList()
        state=state.copy(loading=true,error=null)
        scope.launch {
            try {
                val result=withContext(worker) {
                    val db=store()
                    Triple(db.search(query,category,prior.size),db.drafts(),db.categories())
                }
                if(token==searchToken && state.editor==null) state=state.copy(
                    notes=prior+result.first.notes,total=result.first.total,drafts=result.second,
                    categories=result.third,loading=false)
            } catch(e: Exception) {
                if(token==searchToken) state=state.copy(loading=false,notes=emptyList(),error=
                    if(e is IllegalArgumentException) "搜索最多128个字、8组关键词。" else "无法读取本机笔记，请重试。")
            }
        }
    }
    fun search(text: String) { if(state.busy || state.editor!=null) return; state=state.copy(query=text); refresh() }
    fun filter(category: String?) { if(state.busy || state.editor!=null) return; state=state.copy(category=category); refresh() }
    fun newNote() {
        if(state.loading || state.busy || state.error!=null) return
        searchToken++; editorToken++
        state=state.copy(editor=Editing(Note()),draftState=DraftState.UNSAVED,error=null)
    }
    fun open(id: String) {
        if(state.busy || state.editor!=null) return
        searchToken++; val token=++editorToken
        state=state.copy(busy=true,error=null)
        scope.launch {
            try {
                val (editing,hasDraft)=withContext(worker) {
                    val db=store()
                    Pair(db.open(id) ?: error("missing_note"),db.drafts().any { it.id==id })
                }
                if(token==editorToken) state=state.copy(editor=editing,busy=false,loading=false,
                    draftState=if(hasDraft) DraftState.SAVED else DraftState.FORMAL)
            } catch(_: Exception) { if(token==editorToken) state=state.copy(busy=false,error="无法打开笔记，请返回重试。") }
        }
    }
    fun body(text: String) = edit { it.bodyChanged(text) }
    fun title(text: String) = edit { it.titleChanged(text) }
    fun category(text: String) = edit { it.categoryChanged(text) }
    private fun edit(change: (Note)->Note) {
        val old=state.editor ?: return
        if(state.busy) return
        state=state.copy(editor=old.copy(note=change(old.note)),draftState=DraftState.UNSAVED,error=null)
        timer?.cancel()
        timer=scope.launch { delay(500); persist() }
    }
    fun persist() {
        val editing=state.editor ?: return
        if(state.busy) return
        val token=editorToken
        state=state.copy(draftState=DraftState.SAVING)
        // Separate from debounce: an already dispatched write finishes in order.
        scope.launch {
            try {
                withContext(worker) { store().persistDraft(editing) }
                if(token==editorToken && state.editor==editing && !state.busy)
                    state=state.copy(draftState=DraftState.SAVED,error=null)
            } catch(_: Exception) {
                if(token==editorToken && state.editor==editing && !state.busy)
                    state=state.copy(draftState=DraftState.ERROR,error="草稿未保存，请重试。")
            }
        }
    }
    fun save() = finish(Action.SAVE)
    fun back() = finish(Action.KEEP)
    fun discard() = finish(Action.DISCARD)
    private enum class Action { SAVE, KEEP, DISCARD }
    private fun finish(action: Action) {
        val editing=state.editor ?: return
        if(state.busy || (action==Action.SAVE && editing.note.body.isBlank())) return
        timer?.cancel()
        state=state.copy(busy=true,error=null)
        scope.launch {
            try {
                withContext(worker) {
                    when(action) {
                        Action.SAVE -> { store().persistDraft(editing); store().save(editing) }
                        Action.KEEP -> store().persistDraft(editing)
                        Action.DISCARD -> store().discard(editing)
                    }
                }
                editorToken++
                state=state.copy(editor=null,busy=false)
                refresh()
            } catch(_: Exception) {
                state=state.copy(busy=false,draftState=DraftState.ERROR,
                    error="操作未完成，当前编辑仍保留。请重试。")
            }
        }
    }
    internal fun shutdown() {
        timer?.cancel(); scope.cancel()
        CoroutineScope(worker).launch {
            repository?.close()
            (worker as? ExecutorCoroutineDispatcher)?.close()
        }
    }
    override fun onCleared() { shutdown() }
}
