package com.example.thinkv2.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import com.example.thinkv2.voice.VoiceAnchor
import com.example.thinkv2.voice.VoiceText

enum class NotesPage { HOME, CATEGORIES, TRASH, REMINDERS, BACKUP }

enum class DraftState { UNSAVED, SAVING, SAVED, FORMAL, ERROR }
data class NotesState(
    val notes: List<Note> = emptyList(), val drafts: List<Note> = emptyList(),
    val categories: List<Category> = emptyList(), val query: String = "", val category: String? = null,
    val total: Int = 0, val loading: Boolean = true, val busy: Boolean = false,
    val editor: Editing? = null, val externalCursor: Int? = null, val draftState: DraftState = DraftState.UNSAVED,
    val error: String? = null, val notice: String? = null,
    val backupCopies: Set<String> = emptySet(),
    val page: NotesPage = NotesPage.HOME, val trash: List<TrashItem> = emptyList(),
)

/** UI state runs on main; one worker owns all database operations. */
class NotesModel(
    private val createRepository: () -> NoteRepository,
    ui: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val worker: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    private val closeWorkerOnShutdown: Boolean = true,
    private val onTrashed: (String)->Unit = {},
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
                    Snapshot(db.search(query,category,prior.size),db.drafts(),db.categories(),db.trash(),db.backupCopies())
                }
                if(token==searchToken && state.editor==null) state=state.copy(
                    notes=prior+result.search.notes,total=result.search.total,drafts=result.drafts,
                    categories=result.categories,trash=result.trash,backupCopies=result.copies,loading=false)
            } catch(e: Exception) {
                if(token==searchToken) state=state.copy(loading=false,notes=emptyList(),error=
                    if(e is IllegalArgumentException) "搜索最多128个字、8组关键词。" else "无法读取本机笔记，请重试。")
            }
        }
    }
    private data class Snapshot(val search: SearchPage,val drafts: List<Note>,val categories: List<Category>,val trash: List<TrashItem>,val copies: Set<String>)
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
                val (editing,hasDraft,copies)=withContext(worker) {
                    val db=store()
                    Triple(db.open(id) ?: error(if(db.isTrashed(id)) "trashed_note" else "missing_note"),db.drafts().any { it.id==id },db.backupCopies())
                }
                if(token==editorToken) state=state.copy(editor=editing,busy=false,loading=false,backupCopies=copies,
                    draftState=if(hasDraft) DraftState.SAVED else DraftState.FORMAL)
            } catch(e: Exception) { if(token==editorToken) state=state.copy(busy=false,loading=false,error=when(e.message) {
                "trashed_note" -> "这条笔记已在回收站，可返回首页后到回收站恢复。"
                "missing_note" -> "这条笔记已不存在。"
                else -> "无法打开笔记，请返回重试。"
            }) }
        }
    }
    fun voiceAnchor(cursor: Int?=null): VoiceAnchor? {
        val n=state.editor?.note ?: return null
        if(state.busy || state.page!=NotesPage.HOME) return null
        return VoiceAnchor(editorToken,n.id,n.revision,VoiceText.cursor(n.body,cursor ?: n.body.length))
    }
    fun matchesVoice(anchor: VoiceAnchor): Boolean {
        val n=state.editor?.note ?: return false
        return !state.busy && state.page==NotesPage.HOME && editorToken==anchor.editorToken && n.id==anchor.noteId && n.revision==anchor.revision
    }
    fun insertVoice(anchor: VoiceAnchor,text: String): VoiceAnchor? {
        if(!matchesVoice(anchor) || text.isBlank()) return null
        body(VoiceText.insert(state.editor!!.note.body,anchor.cursor,text))
        state=state.copy(externalCursor=anchor.cursor+text.length)
        return voiceAnchor(anchor.cursor)
    }
    fun correctVoice(anchor: VoiceAnchor,original: String,replacement: String): VoiceAnchor? {
        if(!matchesVoice(anchor)) return null
        val body=state.editor!!.note.body;val end=anchor.cursor+original.length
        if(end>body.length || body.substring(anchor.cursor,end)!=original) return null
        this.body(body.substring(0,anchor.cursor)+replacement+body.substring(end))
        state=state.copy(externalCursor=anchor.cursor+replacement.length)
        return voiceAnchor(anchor.cursor)
    }
    fun body(text: String) = edit { it.bodyChanged(text) }
    fun title(text: String) = edit { it.titleChanged(text) }
    fun category(text: String) = edit { it.categoryChanged(text) }
    fun selectCategory(category: Category?) = edit { it.categorized(category) }
    private fun edit(change: (Note)->Note) {
        val old=state.editor ?: return
        if(state.busy) return
        state=state.copy(editor=old.copy(note=change(old.note)),externalCursor=null,draftState=DraftState.UNSAVED,error=null)
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
                val saved=withContext(worker) { store().persistDraft(editing) }
                if(token==editorToken && state.editor==editing && !state.busy)
                    state=state.copy(editor=saved,draftState=DraftState.SAVED,error=null)
            } catch(_: Exception) {
                if(token==editorToken && state.editor==editing && !state.busy)
                    state=state.copy(draftState=DraftState.ERROR,error="草稿未保存，请重试。")
            }
        }
    }
    fun save() = finish(Action.SAVE)
    fun back() = finish(Action.KEEP)
    fun discard() = finish(Action.DISCARD)
    fun moveToTrash() = finish(Action.TRASH)
    private enum class Action { SAVE, KEEP, DISCARD, TRASH }
    private fun finish(action: Action,nextId: String?=null) {
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
                        Action.TRASH -> { store().softDelete(editing);runCatching { onTrashed(editing.note.id) } }
                    }
                }
                editorToken++
                state=state.copy(editor=null,busy=false,notice=if(action==Action.TRASH) "已移入回收站，可随时恢复。" else null)
                if(nextId!=null) open(nextId) else refresh()
            } catch(_: Exception) {
                state=state.copy(busy=false,draftState=DraftState.ERROR,
                    error="操作未完成，当前编辑仍保留。请重试。")
            }
        }
    }
    private var initialLinkHandled=false
    fun openIncoming(id: String,newIntent: Boolean=false) {
        if(!newIntent && initialLinkHandled) return
        if(state.busy) { scope.launch { delay(100);openIncoming(id,newIntent) };return }
        initialLinkHandled=true
        state=state.copy(page=NotesPage.HOME)
        if(state.editor?.note?.id==id) return
        if(state.editor!=null) finish(Action.KEEP,id) else open(id)
    }
    fun showReminders() { if(!state.busy) state=state.copy(page=NotesPage.REMINDERS) }
    fun closeReminders() { state=state.copy(page=NotesPage.HOME);if(state.editor==null) refresh() }
    fun navigate(page: NotesPage) {
        if(state.busy || state.editor!=null) return
        state=state.copy(page=page,error=null,notice=null)
        refresh()
    }
    fun createCategory(name: String) {
        if(state.busy) return
        val editing=state.editor
        timer?.cancel();state=state.copy(busy=true,error=null,notice=null)
        scope.launch {
            try {
                val result=withContext(worker) {
                    val db=store();val draft=editing?.let { db.persistDraft(it) }
                    Triple(db.createCategory(name),db.categories(),draft)
                }
                state=state.copy(busy=false,categories=result.second,editor=result.third ?: state.editor,notice="分类已创建。")
                if(editing!=null) { selectCategory(result.first);persist() } else refresh()
            } catch(_: Exception) {
                state=state.copy(busy=false,error="分类未创建，请检查名称是否为空、重复或超过80字后重试。")
                // The current draft remains in the editor; no false category success is shown.
            }
        }
    }
    private fun lifecycle(action: NoteRepository.()->String) {
        if(state.busy || state.editor!=null) return
        searchToken++;state=state.copy(busy=true,error=null,notice=null)
        scope.launch {
            try {
                val notice=withContext(worker) { store().action() }
                state=state.copy(busy=false,category=null,notice=notice)
                refresh()
            } catch(_: Exception) {
                state=state.copy(busy=false,error="操作未完成，原有内容仍保留。请重试；若状态已改变，请返回重新打开。")
            }
        }
    }
    fun renameCategory(category: Category,name: String) = lifecycle {
        renameCategory(category,name);"分类已改名，笔记内容保持不变。"
    }
    fun deleteCategory(category: Category) = lifecycle {
        deleteCategory(category);"分类已删除，相关笔记和草稿已移到未分类；回收站内容仍保留。"
    }
    fun restore(note: Note) = lifecycle {
        val result=restore(note)
        (if(result.categoryMissing) "笔记已恢复；原分类已不存在，相关内容已移至未分类。" else "笔记已恢复，原有内容和草稿均保留。") +
            if(result.reminderDisabled) "提醒保持关闭，需重新设置才能启用。" else ""
    }
    internal fun shutdown() {
        timer?.cancel(); scope.cancel()
        CoroutineScope(worker).launch {
            repository?.close()
            if(closeWorkerOnShutdown) (worker as? ExecutorCoroutineDispatcher)?.close()
        }
    }
    override fun onCleared() { shutdown() }
}
