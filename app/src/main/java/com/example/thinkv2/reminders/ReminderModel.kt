package com.example.thinkv2.reminders

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import java.util.TimeZone

data class ReminderForm(val noteId: String,val revision: Long=-1,val repeat: Repeat=Repeat.ONCE,
    val date: String=LocalDay.from(System.currentTimeMillis()+86400000L,TimeZone.getDefault()).toString(),
    val time: String="09:00",val weekdays: Int=0,val enabled: Boolean=true)
data class ReminderState(val plans: List<ReminderPlan> = emptyList(),val labels: Map<String,String> = emptyMap(),
    val events: List<ReminderEvent> = emptyList(),val eventCount: Int=0,val form: ReminderForm?=null,
    val busy: Boolean=false,val error: String?=null,val notice: String?=null,val background: String="")
class ReminderModel(private val runtime: ReminderRuntime): ViewModel() {
    var state by mutableStateOf(ReminderState());private set
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var limit=50
    init { scope.launch { runtime.changes.collect { refresh() } } }
    fun refresh() {
        scope.launch {
            try {
                val loaded=withContext(runtime.dispatcher) {
                    val r=runtime.repository;val plans=r.all()
                    Triple(plans,plans.associate { it.noteId to r.noteLabel(it.noteId) },Pair(r.events(limit),r.eventCount()))
                }
                state=state.copy(plans=loaded.first,labels=loaded.second,events=loaded.third.first,eventCount=loaded.third.second,background=runtime.port.backgroundState())
            } catch(_: Exception) { state=state.copy(error="无法读取提醒，请重试。") }
        }
    }
    fun open(noteId: String?) {
        if(state.busy) return
        state=state.copy(form=null,error=null,notice=null)
        if(noteId==null) { refresh();return }
        state=state.copy(busy=true)
        scope.launch {
            try {
                val p=withContext(runtime.dispatcher) {
                    check(runtime.repository.noteActive(noteId));runtime.repository.forNote(noteId)
                }
                state=state.copy(busy=false,form=if(p==null) ReminderForm(noteId) else ReminderForm(noteId,p.revision,p.rule.repeat,p.rule.start.toString(),"%02d:%02d".format(java.util.Locale.ROOT,p.rule.hour,p.rule.minute),p.rule.weekdays,p.requested))
            } catch(_: Exception) { state=state.copy(busy=false,error="笔记不存在或已在回收站，无法设置提醒。") }
        }
    }
    fun edit(change: (ReminderForm)->ReminderForm) { if(!state.busy) state.form?.let { state=state.copy(form=change(it),error=null,notice=null) } }
    fun backForm() { if(!state.busy) state=state.copy(form=null,error=null,notice=null) }
    fun save() {
        val f=state.form ?: return
        if(state.busy) return
        val rule=try {
            require(f.time.matches(Regex("[0-9]{2}:[0-9]{2}")))
            val parts=f.time.split(':');ReminderRule(f.repeat,LocalDay.parse(f.date),parts[0].toInt(),parts[1].toInt(),f.weekdays).also { it.validate() }
        } catch(_: Exception) { state=state.copy(error="请填写有效日期 YYYY-MM-DD、时间 HH:mm；每周至少选择一天。");return }
        state=state.copy(busy=true,error=null)
        scope.launch {
            try {
                val p=withContext(runtime.dispatcher) { runtime.engine.save(f.noteId,f.revision,rule,f.enabled).also { runtime.changed() } }
                state=state.copy(busy=false,form=null,notice="规则已保存。${reminderStatus(p)}")
                if(p.status in listOf("ERROR","PENDING","CANCEL_PENDING")) runtime.reconcile("SAVE_RETRY")
                refresh()
            } catch(e: Exception) { state=state.copy(busy=false,error=if(e is IllegalArgumentException) "启用提醒需要未来的时间，请修改日期或时刻。" else "保存未完成，可能规则已变化；请返回重新打开，原笔记仍保留。") }
        }
    }
    fun retry() { state=state.copy(error=null);runtime.reconcile("USER_RETRY") }
    fun more() { limit+=50;refresh() }
    override fun onCleared() { scope.cancel() }
}
