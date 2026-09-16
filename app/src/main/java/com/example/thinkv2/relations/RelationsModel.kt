package com.example.thinkv2.relations

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.ReminderRuntime
import kotlinx.coroutines.*

data class RelationsState(val sourceId: String="",val choosing: Boolean=false,val query: String="",val page: RelationPage?=null,val busy: Boolean=false,val error: String?=null,val message: String?=null)
class RelationsModel(context: Context,private val runtime: ReminderRuntime,private val createDatabase: ()->Sql = { AndroidSql(context.applicationContext) }): ViewModel() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var sql: Sql?=null;private var generation=0L
    var state by mutableStateOf(RelationsState());private set
    private fun repo(): RelationRepository { val db=sql ?: createDatabase().also { NoteRepository(it).initialize();sql=it };return RelationRepository(db) }
    fun open(id: String) { state=RelationsState(sourceId=id);load(0) }
    fun mode(choosing: Boolean) { if(state.busy) return;state=state.copy(choosing=choosing,query="",message=null);load(0) }
    fun search(query: String) { if(state.busy) return;state=state.copy(query=query,message=null);load(0) }
    fun load(offset: Int=0) {
        val before=state;if(before.sourceId.isEmpty()) return
        val token=++generation;state=state.copy(busy=true,error=null,page=null)
        scope.launch { try {
            val result=withContext(runtime.dispatcher) { repo().page(before.sourceId,before.choosing,before.query,offset,before.page?.token) }
            if(token==generation) state=state.copy(busy=false,page=result)
        } catch(e: Exception) { fail(token,e) } }
    }
    fun create(target: RelatedNote) { val source=state.sourceId;mutate(true) { create(source,target.id) } }
    fun remove(row: RelationRow) { val source=state.sourceId;mutate { remove(source,row) } }
    private fun mutate(showLinks: Boolean=false,action: RelationRepository.()->Unit) {
        if(state.busy) return
        val token=++generation;state=state.copy(busy=true,error=null,message=null)
        scope.launch { try {
            withContext(runtime.dispatcher) { repo().action() }
            if(token==generation) { state=state.copy(busy=false,choosing=if(showLinks) false else state.choosing,query=if(showLinks) "" else state.query,message="关系操作已提交；笔记内容保持不变。");load(0) }
        } catch(e: Exception) { fail(token,e) } }
    }
    private fun fail(token: Long,e: Exception) {
        if(token!=generation) return
        state=state.copy(busy=false,page=null,error=when(e.message) {
            "relation_endpoint_unavailable" -> "笔记不存在或已在回收站，无法操作关系；原有关系仍保留。"
            "relation_duplicate" -> "这两条笔记已经相关，没有新增重复关系。请刷新列表。"
            "relation_query_limit" -> "搜索最多128字、8组关键词，请缩短后重试。"
            "relation_page_changed" -> "列表已变化，旧分页已失效；请刷新后从第一页继续，避免重复或漏看。"
            "relation_changed" -> "关系已变化，没有移除其他关系，请刷新。"
            else -> "关系操作未完成，请重试；笔记内容仍保留。"
        })
    }
    override fun onCleared() { generation++;scope.cancel();runtime.scope.launch { sql?.close() } }
}
