package com.example.thinkv2.calendar

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.*
import kotlinx.coroutines.*
import java.util.TimeZone

data class CalendarImportState(
    val calendars: List<CalendarRef> = emptyList(),val chosen: CalendarRef?=null,
    val first: String=LocalDay.from(System.currentTimeMillis(),TimeZone.getDefault()).toString(),
    val last: String=LocalDay.from(System.currentTimeMillis(),TimeZone.getDefault()).plus(30).toString(),
    val zone: String=TimeZone.getDefault().id,val categories: List<Category> = emptyList(),val categoryId: String="",
    val preview: CalendarPreview?=null,val selected: Set<String> = emptySet(),val copies: Set<String> = emptySet(),
    val identityConfirmed: Boolean=false,val busy: Boolean=false,val committing: Boolean=false,
    val message: String?=null,val error: String?=null)
class CalendarImportModel(context: Context,private val runtime: ReminderRuntime,
    private val source: CalendarSource=AndroidCalendarSource(context)): ViewModel() {
    private val app=context.applicationContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var sql: AndroidSql?=null
    @Volatile private var generation=0L
    var state by mutableStateOf(CalendarImportState());private set
    private fun database(): AndroidSql=sql ?: AndroidSql(app).also { NoteRepository(it).initialize();sql=it }
    fun permitted()=source.permitted()
    fun denied() { cancel();state=state.copy(error="未获读取日历权限；没有导入，仍可使用文字笔记。") }
    fun calendars() {
        if(state.busy) return
        val token=++generation;state=state.copy(busy=true,error=null,preview=null,selected=emptySet(),identityConfirmed=false)
        scope.launch {
            try {
                val result=withContext(runtime.dispatcher) { source.calendars() to NoteRepository(database()).categories() }
                if(token==generation) state=state.copy(busy=false,calendars=result.first,categories=result.second,chosen=null,message=if(result.first.isEmpty()) "提供者未返回可读日历，请在系统中确认来源。" else null)
            } catch(e: Exception) { fail(token,e) }
        }
    }
    fun choose(calendar: CalendarRef) { if(!state.busy) state=state.copy(chosen=calendar,preview=null,selected=emptySet(),identityConfirmed=false,error=null) }
    fun dates(first: String=state.first,last: String=state.last) { if(!state.busy) state=state.copy(first=first,last=last,preview=null,selected=emptySet(),identityConfirmed=false) }
    fun category(id: String) { if(!state.busy) state=state.copy(categoryId=id) }
    fun acknowledge(value: Boolean) { if(!state.busy) state=state.copy(identityConfirmed=value) }
    fun select(row: CalendarRow,value: Boolean) {
        if(state.busy || row.action !in setOf(CalendarAction.NEW,CalendarAction.CHANGED)) return
        val key=row.event.key
        state=state.copy(selected=if(value) state.selected+key else state.selected-key,
            copies=if(value && row.action==CalendarAction.CHANGED) state.copies+key else state.copies-key)
    }
    fun preview() {
        val chosen=state.chosen ?: return
        if(state.busy) return
        val range=try { CalendarRange(state.first,state.last,state.zone) } catch(_: Exception) { state=state.copy(error="请输入YYYY-MM-DD日期，结束不早于开始，最多366天。");return }
        val token=++generation;state=state.copy(busy=true,error=null,message=null,preview=null,identityConfirmed=false)
        scope.launch {
            try {
                val result=withContext(runtime.dispatcher) {
                    val snapshot=source.read(chosen,range)
                    CalendarRepository(database()).preview(snapshot) to NoteRepository(database()).categories()
                }
                if(token==generation) state=state.copy(busy=false,preview=result.first,categories=result.second,
                    selected=result.first.rows.filter { it.action==CalendarAction.NEW }.map { it.event.key }.toSet(),copies=emptySet())
            } catch(e: Exception) { fail(token,e) }
        }
    }
    fun cancel() { if(!state.committing) { generation++;state=state.copy(busy=false,preview=null,selected=emptySet(),copies=emptySet(),identityConfirmed=false,message="已取消预览，没有导入。",error=null) } }
    fun leave() { generation++;state=CalendarImportState() }
    fun apply(changed: ()->Unit) {
        val current=state;val preview=current.preview ?: return
        if(current.busy) return
        val token=++generation;state=state.copy(busy=true,committing=true,error=null,message=null)
        scope.launch {
            try {
                val applied=withContext(runtime.dispatcher) {
                    CalendarRepository(database()).apply(preview,current.selected,current.copies,current.categoryId,current.identityConfirmed,source) { token==generation }
                }
                if(token==generation) { state=state.copy(busy=false,committing=false,preview=null,selected=emptySet(),identityConfirmed=false,
                    message="已提交导入：新增 ${applied.noteIds.size} 条，已有 ${applied.alreadyImported} 条未重复。源日历未修改，本机提醒未开启。");changed() }
            } catch(e: Exception) { fail(token,e) }
        }
    }
    private fun fail(token: Long,error: Exception) {
        if(token!=generation) return
        val text=when {
            error is SecurityException -> "日历读取权限不可用，没有导入；请授权后重新预览。"
            error is CalendarFailure -> when(error.code) {
                "source_changed","source_missing" -> "来源已变化或删除，旧预览失效，没有导入；请重新预览。"
                "local_changed" -> "本机笔记、草稿、分类或导入状态已变化，没有导入；请重新预览。"
                "local_limit" -> "本机数据超过本次导入检查上限（每表10000行、文字32MiB），没有导入，原有笔记仍保留。"
                "event_limit" -> "单个源事件完整快照超过128KiB，未截断也未导入；请跳过该日期范围或在来源应用核对内容。"
                "source_limit" -> "内容超过本次保护上限，没有导入。请缩小范围（最多300个源事件、5000个实例、4MiB原文）。"
                "hidden_calendar" -> "该源日历未设为可显示，系统可能不提供完整实例。未读取导入预览；请先在原日历应用确认显示设置。"
                "identity_confirmation" -> "请先核对来源身份及可能重复的风险。"
                "selection_required","selection_invalid","copy_confirmation" -> "请选择要导入的事件；源变更需要明确选择新副本。"
                "cancelled" -> "导入已取消，本次没有提交。"
                else -> "日历提供者暂不可用，没有导入；请重新读取。"
            }
            else -> "读取或提交失败，本次未完成导入。请检查提供者与本机空间后重试。"
        }
        state=state.copy(busy=false,committing=false,preview=null,selected=emptySet(),identityConfirmed=false,error=text)
    }
    override fun onCleared() { generation++;scope.cancel();runtime.scope.launch { sql?.close() } }
}
