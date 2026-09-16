package com.example.thinkv2.reminders

import com.example.thinkv2.notes.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class ReminderTest {
    @get:Rule val temp=TemporaryFolder()
    private val utc=TimeZone.getTimeZone("UTC")
    private val day=LocalDay.parse("2026-09-16")
    private fun rule(repeat: Repeat=Repeat.ONCE)=ReminderRule(repeat,day,10,0)
    private class Port: ReminderPort {
        var blocked: String?=null;var posts=0;var summaries=0;var scheduled: Occurrence?=null
        var crash=false;var failSchedule=false;var failPost=false;var failCancel=false
        override fun blockedReason()=blocked
        override fun schedule(plan: ReminderPlan,occurrence: Occurrence) { if(failSchedule) error("OS failure");scheduled=occurrence }
        override fun cancelAlarm(id: String) { if(failCancel) error("cancel failed");scheduled=null }
        override fun cancelNotification(id: String) {}
        override fun post(plan: ReminderPlan) { if(crash) throw AssertionError("process exit");if(failPost) error("notify failure");posts++ }
        override fun postSummary() { summaries++ }
    }
    private inner class Fixture: AutoCloseable {
        val sql=PythonSql(temp.newFile());val notes=NoteRepository(sql).apply { initialize() }
        val note=notes.save(Editing(Note().bodyChanged("私密合成正文")))
        val repo=ReminderRepository(sql);val port=Port();var time=day.epoch()+9*3600000L;var zone=utc
        val engine=ReminderEngine(repo,port,{time},{zone})
        fun save(repeat: Repeat=Repeat.ONCE)=engine.save(note.id,repo.forNote(note.id)?.revision ?: -1,rule(repeat),true)
        fun fire(p: ReminderPlan) { time=p.nextAt;engine.fire(p.id,p.revision,p.nextKey,p.nextAt) }
        override fun close() { notes.close() }
    }
    @Test fun weeklyMultipleDaysAndFutureBoundary() {
        val r=ReminderRule(Repeat.WEEKLY,day,10,0,(1 shl 0) or (1 shl 4))
        assertEquals("2026-09-18T10:00",r.nextAfter(day.epoch(),utc)!!.key)
        assertEquals("2026-09-21T10:00",r.nextAfter(LocalDay.parse("2026-09-18").epoch()+10*3600000L,utc)!!.key)
        assertNull(rule().nextAfter(day.epoch()+10*3600000L,utc))
    }
    @Test fun validatesDateTimeAndEmptyWeeklySelection() {
        for(action in listOf<()->Unit>({LocalDay.parse("2026-02-30")},{ReminderRule(Repeat.WEEKLY,day,10,0).validate()},{rule().copy(hour=24).validate()})) {
            try { action();fail("must reject") } catch(_: IllegalArgumentException) {}
        }
    }
    @Test fun dstGapUsesFirstValidMinuteAndOverlapFirstOffset() {
        val zone=TimeZone.getTimeZone("America/New_York")
        val gap=ReminderRule(Repeat.DAILY,LocalDay.parse("2026-03-08"),2,30)
        assertEquals(LocalDay.parse("2026-03-08").epoch()+7*3600000L,gap.occurrence(gap.start,zone).at)
        val overlap=ReminderRule(Repeat.DAILY,LocalDay.parse("2026-11-01"),1,30)
        val first=overlap.occurrence(overlap.start,zone)
        assertEquals(LocalDay.parse("2026-11-01").epoch()+330*60000L,first.at)
        assertEquals("2026-11-02T01:30",overlap.nextAfter(first.at,zone,first.key)!!.key)
    }
    @Test fun timezoneAndRollbackRespectLocalCursor() {
        val r=rule(Repeat.DAILY);val occurrence=r.occurrence(day,utc)
        assertEquals(occurrence.at-8*3600000L,r.occurrence(day,TimeZone.getTimeZone("Asia/Taipei")).at)
        assertEquals("2026-09-17T10:00",r.nextAfter(day.epoch(),utc,occurrence.key)!!.key)
    }
    @Test fun permissionBlockedConfigurationRecoversWithoutNewRevision()=Fixture().use { f ->
        f.port.blocked="permission";val p=f.save();assertEquals("BLOCKED",p.status);assertNull(f.port.scheduled)
        f.port.blocked=null;assertTrue(f.engine.reconcile("PERMISSION"));assertEquals(p.revision,f.repo.get(p.id)!!.revision)
        assertEquals("SCHEDULED",f.repo.get(p.id)!!.status)
    }
    @Test fun failedSchedulingIsVisibleAndRetryable()=Fixture().use { f ->
        f.port.failSchedule=true;val p=f.save();assertEquals("ERROR",p.status)
        f.port.failSchedule=false;assertTrue(f.engine.reconcile("COLD"));assertEquals("SCHEDULED",f.repo.get(p.id)!!.status)
    }
    @Test fun onceDeliveryDuplicateAndReadingDoesNotInventReadStatus()=Fixture().use { f ->
        val p=f.save();f.fire(p);f.fire(p);f.engine.reconcile("COLD")
        assertEquals(1,f.port.posts);assertEquals("COMPLETED",f.repo.get(p.id)!!.status)
        assertTrue(reminderStatus(f.repo.get(p.id)!!).contains("阅读未知"))
    }
    @Test fun crashBeforeNotifyOnceRemainsUnknownOnColdStart()=Fixture().use { f ->
        val p=f.save();f.port.crash=true
        try { f.fire(p);fail("crash") } catch(_: AssertionError) {}
        f.port.crash=false;f.engine.reconcile("COLD");f.fire(p)
        assertEquals(0,f.port.posts);assertEquals("UNKNOWN",f.repo.get(p.id)!!.status)
        assertEquals("UNKNOWN",f.repo.events().single().outcome);assertEquals(1,f.port.summaries)
    }
    @Test fun crashAfterNotifyBeforeAckDoesNotRepostAndDailyContinues()=Fixture().use { f ->
        val p=f.save(Repeat.DAILY)
        f.sql.execute("CREATE TRIGGER fail_ack BEFORE UPDATE OF outcome ON reminder_events WHEN NEW.outcome='POSTED' BEGIN SELECT RAISE(ABORT,'crash'); END")
        try { f.fire(p);fail("crash") } catch(_: IllegalStateException) {}
        f.sql.execute("DROP TRIGGER fail_ack");f.engine.reconcile("COLD");f.fire(p)
        assertEquals(1,f.port.posts);assertEquals("UNKNOWN",f.repo.events().single().outcome)
        val next=f.repo.get(p.id)!!;assertEquals("2026-09-17T10:00",next.nextKey)
        f.fire(next);assertEquals(2,f.port.posts)
    }
    @Test fun crashAfterPostedAckBeforeOnceFinishRetainsCompleted()=Fixture().use { f ->
        val p=f.save()
        f.sql.execute("CREATE TRIGGER fail_finish BEFORE UPDATE OF requested ON reminders WHEN NEW.requested=0 BEGIN SELECT RAISE(ABORT,'crash'); END")
        try { f.fire(p);fail("crash") } catch(_: IllegalStateException) {}
        assertEquals("POSTED",f.repo.events().single().outcome)
        f.sql.execute("DROP TRIGGER fail_finish");f.engine.reconcile("COLD")
        assertEquals("COMPLETED",f.repo.get(p.id)!!.status);assertEquals(1,f.port.posts)
    }
    @Test fun definiteFailureIsNotCompletedOrMissed()=Fixture().use { f ->
        val p=f.save();f.port.failPost=true;f.fire(p);f.engine.reconcile("COLD")
        assertEquals("POST_FAILED",f.repo.get(p.id)!!.status);assertEquals(0,f.port.posts)
    }
    @Test fun overdueCyclesAggregateOnceAndLateCallbackCannotRaceColdStart()=Fixture().use { f ->
        val p=f.save(Repeat.DAILY);f.time=p.nextAt+5*86400000L;f.engine.reconcile("COLD")
        f.engine.fire(p.id,p.revision,p.nextKey,p.nextAt);f.engine.reconcile("COLD")
        assertEquals(0,f.port.posts);assertEquals(1,f.port.summaries);assertEquals(1,f.repo.eventCount())
        assertEquals("2026-09-22T10:00",f.repo.get(p.id)!!.nextKey)
    }
    @Test fun overdueOnceIsMissedAndCanBeResetAtNewRevision()=Fixture().use { f ->
        val p=f.save();f.time=p.nextAt+1;f.engine.reconcile("BOOT")
        assertEquals("MISSED",f.repo.get(p.id)!!.status)
        val reset=f.engine.save(f.note.id,p.revision,rule().copy(start=day.plus(1)),true)
        assertEquals(p.id,reset.id);assertEquals(p.revision+1,reset.revision);assertEquals("SCHEDULED",reset.status)
    }
    @Test fun editDisableAndTrashRejectStaleCallbacksAndRestoreStaysOff()=Fixture().use { f ->
        val p=f.save(Repeat.DAILY);val edited=f.engine.save(f.note.id,p.revision,rule(Repeat.DAILY).copy(hour=11),true)
        f.fire(p);assertEquals(0,f.port.posts)
        val disabled=f.engine.save(f.note.id,edited.revision,edited.rule,false)
        f.fire(edited);assertEquals("DISABLED",f.repo.get(p.id)!!.status)
        f.time=day.epoch()+9*3600000L
        val active=f.engine.save(f.note.id,disabled.revision,rule(Repeat.DAILY),true)
        f.notes.softDelete(f.notes.open(f.note.id)!!);f.fire(active);assertEquals(0,f.port.posts)
        f.engine.cancelForNote(f.note.id);assertNull(f.port.scheduled)
        val result=f.notes.restore(f.notes.trash().single().note);assertTrue(result.reminderDisabled)
        f.engine.reconcile("COLD");assertFalse(f.repo.get(p.id)!!.requested)
        assertEquals("DISABLED",f.repo.get(p.id)!!.status);assertEquals(1,f.repo.all().size)
    }
    @Test fun noteBodyEditDoesNotChangeReminderIdentityOrRevision()=Fixture().use { f ->
        val p=f.save();val e=f.notes.open(f.note.id)!!;f.notes.save(e.copy(note=e.note.bodyChanged("新的私密内容")))
        assertEquals(p,f.repo.forNote(f.note.id))
    }
    @Test fun timezoneReconciliationRecomputesPendingWallClock()=Fixture().use { f ->
        val p=f.save();f.zone=TimeZone.getTimeZone("America/New_York");f.engine.reconcile("TIMEZONE")
        val changed=f.repo.get(p.id)!!;assertEquals(p.nextKey,changed.nextKey);assertEquals(p.nextAt+4*3600000L,changed.nextAt)
        f.fire(p);assertEquals(0,f.port.posts)
    }
    @Test fun schemaTwoUpgradePreservesNotesDraftsCategoriesAndRollsBackOnFailure() {
        val sql=PythonSql(temp.newFile());val notes=NoteRepository(sql);notes.initialize()
        val cat=notes.createCategory("原分类");val n=notes.save(Editing(Note().bodyChanged("原正文").categorized(cat)))
        val e=notes.open(n.id)!!;notes.persistDraft(e.copy(note=e.note.bodyChanged("原草稿")))
        for(table in listOf("reminders","reminder_events","reminder_runtime","backup_imports","backup_origins","calendar_imports")) sql.execute("DROP TABLE $table")
        sql.execute("PRAGMA user_version=2")
        val oldNotes=sql.query("SELECT * FROM notes");val oldDrafts=sql.query("SELECT * FROM drafts");val oldCategories=sql.query("SELECT * FROM categories")
        sql.execute("CREATE TABLE reminder_events (collision TEXT)")
        try { notes.initialize();fail("must roll back") } catch(_: IllegalStateException) {}
        assertEquals(listOf(listOf("2")),sql.query("PRAGMA user_version"))
        assertTrue(sql.query("SELECT name FROM sqlite_master WHERE name='reminders'").isEmpty())
        sql.execute("DROP TABLE reminder_events");notes.initialize()
        assertEquals(oldNotes,sql.query("SELECT * FROM notes"));assertEquals(oldDrafts,sql.query("SELECT * FROM drafts"));assertEquals(oldCategories,sql.query("SELECT * FROM categories"))
        assertEquals(listOf(listOf("5")),sql.query("PRAGMA user_version"));notes.close()
    }

    @Test fun cancellationFailureStillRejectsOldCallbackAndRetriesAfterRestore()=Fixture().use { f ->
        val p=f.save(Repeat.DAILY);f.port.failCancel=true
        f.notes.softDelete(f.notes.open(f.note.id)!!);f.engine.cancelForNote(f.note.id)
        assertEquals("CANCEL_PENDING",f.repo.get(p.id)!!.status)
        f.fire(p);assertEquals(0,f.port.posts)
        f.notes.restore(f.notes.trash().single().note)
        f.port.failCancel=false;assertTrue(f.engine.reconcile("COLD"))
        assertEquals("DISABLED",f.repo.get(p.id)!!.status);assertNull(f.port.scheduled)
    }
    @Test fun earlyCallbackAfterClockRollbackRearmsWithoutPosting()=Fixture().use { f ->
        val p=f.save(Repeat.DAILY);f.time=p.nextAt-3600000L
        f.engine.fire(p.id,p.revision,p.nextKey,p.nextAt)
        assertEquals(0,f.port.posts);assertEquals(p.nextKey,f.repo.get(p.id)!!.nextKey)
        f.fire(f.repo.get(p.id)!!);assertEquals(1,f.port.posts)
    }

}
