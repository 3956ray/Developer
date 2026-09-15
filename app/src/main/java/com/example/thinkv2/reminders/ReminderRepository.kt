package com.example.thinkv2.reminders

import com.example.thinkv2.notes.Sql
import java.util.UUID

class ReminderRepository(private val db: Sql) {
    private val columns="id,note_id,revision,kind,start_day,hour,minute,weekdays,requested,status,next_key,next_at,last_key,problem"
    private fun plan(r: List<String>)=ReminderPlan(r[0],r[1],r[2].toLong(),ReminderRule(Repeat.valueOf(r[3]),LocalDay.parse(r[4]),r[5].toInt(),r[6].toInt(),r[7].toInt()),r[8]=="1",r[9],r[10],r[11].toLong(),r[12],r[13])
    fun all()=db.query("SELECT $columns FROM reminders ORDER BY note_id").map(::plan)
    fun get(id: String)=db.query("SELECT $columns FROM reminders WHERE id=?",listOf(id)).firstOrNull()?.let(::plan)
    fun forNote(id: String)=db.query("SELECT $columns FROM reminders WHERE note_id=?",listOf(id)).firstOrNull()?.let(::plan)
    fun noteActive(id: String)=db.query("SELECT id FROM notes WHERE id=? AND deleted_at=0",listOf(id)).isNotEmpty()
    fun noteLabel(id: String)=db.query("SELECT title,deleted_at FROM notes WHERE id=?",listOf(id)).firstOrNull()
        ?.let { if(it[1]=="0") it[0] else "笔记已在回收站" } ?: "笔记不存在"
    fun save(noteId: String,expected: Long,rule: ReminderRule,requested: Boolean,lastKey: String): ReminderPlan=transaction {
        rule.validate();check(noteActive(noteId)) { "note_not_active" }
        val old=forNote(noteId);check((old?.revision ?: -1)==expected) { "stale_rule" }
        val id=old?.id ?: UUID.randomUUID().toString();val revision=(old?.revision ?: 0)+1
        val values=listOf(noteId,revision.toString(),rule.repeat.name,rule.start.toString(),rule.hour.toString(),rule.minute.toString(),rule.weekdays.toString(),
            if(requested) "1" else "0",if(requested) "PENDING" else "CANCEL_PENDING","","0",lastKey,"")
        if(old==null) db.execute("INSERT INTO reminders ($columns) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",listOf(id)+values)
        else db.execute("UPDATE reminders SET note_id=?,revision=?,kind=?,start_day=?,hour=?,minute=?,weekdays=?,requested=?,status=?,next_key=?,next_at=?,last_key=?,problem=? WHERE id=?",values+id)
        if(!requested) db.execute("UPDATE reminder_events SET reported=4 WHERE reminder_id=? AND reported=0",listOf(id))
        get(id)!!
    }
    fun state(p: ReminderPlan,status: String,next: Occurrence?=null,problem: String="") {
        db.execute("UPDATE reminders SET status=?,next_key=?,next_at=?,problem=? WHERE id=? AND revision=?",
            listOf(status,next?.key.orEmpty(),(next?.at ?: 0).toString(),problem,p.id,p.revision.toString()))
    }
    fun finishOnce(p: ReminderPlan,status: String) {
        db.execute("UPDATE reminders SET requested=0,status=?,next_key='',next_at=0 WHERE id=? AND revision=?",listOf(status,p.id,p.revision.toString()))
    }
    fun reserve(p: ReminderPlan,occurrence: Occurrence,now: Long): Boolean=transaction {
        val current=get(p.id) ?: return@transaction false
        if(current.revision!=p.revision || !current.requested || !noteActive(current.noteId) || occurrence.key<=current.lastKey || current.nextKey!=occurrence.key) return@transaction false
        db.execute("INSERT INTO reminder_events VALUES (?,?,?,?,?,'RESERVED',0,'dispatch_started')",listOf(p.id,p.revision.toString(),occurrence.key,occurrence.at.toString(),now.toString()))
        db.execute("UPDATE reminders SET last_key=?,next_key='',next_at=0,status='PENDING' WHERE id=? AND revision=?",listOf(occurrence.key,p.id,p.revision.toString()))
        true
    }
    fun outcome(p: ReminderPlan,key: String,outcome: String,now: Long) {
        db.execute("UPDATE reminder_events SET outcome=?,checked_at=?,reported=?,reason=? WHERE reminder_id=? AND revision=? AND instance=? AND outcome='RESERVED'",
            listOf(outcome,now.toString(),if(outcome=="POSTED") "2" else "0",if(outcome=="POSTED") "notify_returned" else "notify_failed",p.id,p.revision.toString(),key))
    }
    fun missed(p: ReminderPlan,last: Occurrence,now: Long,reason: String="reconcile_skipped") = transaction {
        val current=get(p.id) ?: return@transaction
        if(current.revision!=p.revision || !current.requested || last.key<=current.lastKey) return@transaction
        db.execute("INSERT OR IGNORE INTO reminder_events VALUES (?,?,?,?,?,'MISSED',0,?)",listOf(p.id,p.revision.toString(),last.key,last.at.toString(),now.toString(),reason))
        db.execute("UPDATE reminders SET last_key=?,next_key='',next_at=0 WHERE id=? AND revision=?",listOf(last.key,p.id,p.revision.toString()))
    }
    fun recoverUncertain() { db.execute("UPDATE reminder_events SET outcome='UNKNOWN',reason='process_interrupted' WHERE outcome='RESERVED'") }
    fun events(limit: Int=100): List<ReminderEvent> {
        require(limit>0)
        return db.query("SELECT reminder_id,revision,instance,at,checked_at,outcome,reported,reason FROM reminder_events ORDER BY checked_at DESC,reminder_id,instance LIMIT ?",listOf(limit.toString()))
            .map { ReminderEvent(it[0],it[1].toLong(),it[2],it[3].toLong(),it[4].toLong(),it[5],it[6].toInt(),it[7]) }
    }
    fun eventCount()=db.query("SELECT count(*) FROM reminder_events").single().single().toInt()
    fun terminalStatus(p: ReminderPlan): String = when(db.query(
        "SELECT outcome FROM reminder_events WHERE reminder_id=? AND revision=? AND instance=?",
        listOf(p.id,p.revision.toString(),p.lastKey)).firstOrNull()?.first()) {
        "POSTED" -> "COMPLETED"
        "POST_FAILED" -> "POST_FAILED"
        "MISSED" -> "MISSED"
        else -> "UNKNOWN"
    }
    fun claimSummary(): Boolean=transaction {
        val pending=db.query("SELECT 1 FROM reminder_events WHERE reported=0 AND outcome IN ('MISSED','UNKNOWN','POST_FAILED') LIMIT 1").isNotEmpty()
        if(pending) db.execute("UPDATE reminder_events SET reported=1 WHERE reported=0 AND outcome IN ('MISSED','UNKNOWN','POST_FAILED')")
        pending
    }
    fun finishSummary(posted: Boolean) { db.execute("UPDATE reminder_events SET reported=? WHERE reported=1",listOf(if(posted) "2" else "3")) }
    fun recordReconcile(reason: String,now: Long) {
        db.execute("INSERT OR REPLACE INTO reminder_runtime VALUES ('last_reason',?)",listOf(reason))
        db.execute("INSERT OR REPLACE INTO reminder_runtime VALUES ('last_reconcile_at',?)",listOf(now.toString()))
    }
    private fun <T> transaction(action: ()->T): T {
        db.begin()
        try { val value=action();db.commit();return value } catch(e: Exception) { db.rollback();throw e }
    }
}
