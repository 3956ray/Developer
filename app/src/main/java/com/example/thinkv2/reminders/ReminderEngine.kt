package com.example.thinkv2.reminders

import java.util.TimeZone

interface ReminderPort {
    fun blockedReason(): String?
    fun schedule(plan: ReminderPlan,occurrence: Occurrence)
    fun cancelAlarm(id: String)
    fun cancelNotification(id: String)
    fun post(plan: ReminderPlan)
    fun postSummary()
}

/** Called only by the application's shared serial worker. SQLite and OS acknowledgements stay separate. */
class ReminderEngine(private val repository: ReminderRepository,private val port: ReminderPort,
    private val now: ()->Long = System::currentTimeMillis,private val zone: ()->TimeZone = TimeZone::getDefault) {
    fun save(noteId: String,expected: Long,rule: ReminderRule,enabled: Boolean): ReminderPlan {
        val time=now();rule.validate()
        require(!enabled || rule.nextAfter(time,zone())!=null) { "future_occurrence_required" }
        val saved=repository.save(noteId,expected,rule,enabled,rule.latestDue(time,zone())?.key.orEmpty())
        try {
            port.cancelAlarm(saved.id);port.cancelNotification(saved.id)
            reconcileOne(saved,"EDIT")
        } catch(_: Exception) { runCatching { repository.state(saved,"ERROR",problem="os_operation_failed") } }
        return repository.get(saved.id)!!
    }
    /** Returns false on a retryable DB/OS error; blocked permissions are not retried in a loop. */
    fun reconcile(reason: String): Boolean {
        var success=true
        repository.recoverUncertain()
        repository.recordReconcile(reason,now())
        for(plan in repository.all()) {
            try { reconcileOne(plan,reason) }
            catch(_: Exception) {
                success=false
                runCatching { repository.state(plan,if(plan.requested) "ERROR" else "CANCEL_PENDING",problem="os_operation_failed") }
            }
        }
        return flushSummary() && success
    }
    fun cancelForNote(noteId: String) {
        val p=repository.forNote(noteId) ?: return
        try { reconcileOne(p,"NOTE_DELETED") }
        catch(_: Exception) { runCatching { repository.state(p,"CANCEL_PENDING",problem="cancel_failed") } }
    }
    private fun reconcileOne(original: ReminderPlan,reason: String) {
        var p=repository.get(original.id) ?: return
        if(!p.requested || !repository.noteActive(p.noteId)) {
            port.cancelAlarm(p.id)
            if(p.status in listOf("CANCEL_PENDING","DISABLED","ERROR") || !repository.noteActive(p.noteId)) {
                port.cancelNotification(p.id)
                repository.finishOnce(p,"DISABLED")
            }
            return
        }
        val time=now();val timeZone=zone()
        val due=p.rule.latestDue(time,timeZone)
        if(due!=null && due.key>p.lastKey) {
            repository.missed(p,due,time,if(port.blockedReason()!=null) "permission_blocked" else "reconcile_skipped_$reason")
            p=repository.get(p.id)!!
        }
        val next=p.rule.nextAfter(time,timeZone,p.lastKey)
        if(next==null) {
            port.cancelAlarm(p.id);repository.finishOnce(p,repository.terminalStatus(p));return
        }
        val blocked=port.blockedReason()
        if(blocked!=null) {
            port.cancelAlarm(p.id)
            repository.state(p,"BLOCKED",next,blocked);return
        }
        // Persist intent first. A successful set() without a successful DB ack is still PENDING.
        repository.state(p,"PENDING",next)
        port.cancelAlarm(p.id)
        port.schedule(p,next)
        repository.state(p,"SCHEDULED",next)
    }
    fun fire(id: String,revision: Long,key: String,scheduledAt: Long) {
        val p=repository.get(id) ?: return
        if(!p.requested || p.revision!=revision || p.nextKey!=key || p.nextAt!=scheduledAt || key<=p.lastKey || !repository.noteActive(p.noteId)) return
        val time=now();val timeZone=zone()
        val actual=p.rule.occurrence(LocalDay.parse(key.take(10)),timeZone)
        if(time<scheduledAt || actual.at!=scheduledAt) { reconcileOne(p,"TIME_CHANGED");return }
        val latest=p.rule.latestDue(time,timeZone)
        if(port.blockedReason()!=null || (p.rule.repeat!=Repeat.ONCE && latest!=null && latest.key>key)) {
            repository.missed(p,latest ?: Occurrence(key,scheduledAt),time,if(port.blockedReason()!=null) "permission_blocked" else "multiple_late_instances")
            reconcileOne(p,"ALARM_CONTINUE");flushSummary();return
        }
        if(!repository.reserve(p,Occurrence(key,scheduledAt),time)) return
        var posted=false
        try {
            port.post(p)
            posted=true
        } catch(_: Exception) { /* The ledger distinguishes failure from the crash window. */ }
        repository.outcome(p,key,if(posted) "POSTED" else "POST_FAILED",time)
        if(p.rule.repeat==Repeat.ONCE) repository.finishOnce(p,if(posted) "COMPLETED" else "POST_FAILED")
        else reconcileOne(p,"ALARM_CONTINUE")
        flushSummary()
    }
    private fun flushSummary(): Boolean {
        if(port.blockedReason()!=null || !repository.claimSummary()) return true
        return try { port.postSummary();repository.finishSummary(true);true }
        catch(_: Exception) { runCatching { repository.finishSummary(false) };false }
    }
}

fun reminderStatus(plan: ReminderPlan): String = when(plan.status) {
    "SCHEDULED" -> "系统已接受调度（可能延迟）"
    "BLOCKED" -> "提醒未启用：通知权限或频道关闭"
    "PENDING" -> "规则已保存，系统调度尚未确认"
    "CANCEL_PENDING" -> "已请求关闭，系统撤销待重试"
    "DISABLED" -> "已关闭"
    "ERROR" -> "规则已保存，系统操作失败，请重试"
    "COMPLETED" -> "通知已提交系统，是否阅读未知"
    "POST_FAILED" -> "本次通知提交失败，请检查设置并重设"
    "MISSED" -> "本次已错过，请重新设置未来时间"
    "UNKNOWN" -> "本次送达状态未确认，可重新设置未来时间"
    else -> "状态未确认，请重试对账"
}
fun eventStatus(event: ReminderEvent): String = when(event.outcome) {
    "POSTED" -> "已提交系统通知，是否阅读未知"
    "MISSED" -> "过期实例已跳过（合并记录）"
    "POST_FAILED" -> "通知调用失败，未确认送达"
    else -> "送达状态未确认，未自动重发此实例"
}
fun eventReason(event: ReminderEvent): String = when {
    event.reason=="permission_blocked" -> "检查时通知权限或频道关闭"
    event.reason=="process_interrupted" -> "提交窗口内进程中断，不能确认系统是否收到"
    event.reason=="notify_returned" -> "系统通知调用正常返回，不代表用户已读"
    event.reason=="notify_failed" -> "系统通知调用异常，可检查设置并人工重设"
    event.reason=="multiple_late_instances" -> "延迟跨越多个周期，已合并跳过，避免补发风暴"
    else -> "对账时已过期且无提交记录，跳过该实例；后续周期继续"
}
