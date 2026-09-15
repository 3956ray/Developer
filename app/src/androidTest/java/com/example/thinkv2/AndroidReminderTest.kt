package com.example.thinkv2

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.TimeZone

/** Fresh isolated synthetic emulator only. Real AlarmManager minute test + separately labelled fixed-clock cycles. */
@RunWith(AndroidJUnit4::class)
class AndroidReminderTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime get()=ReminderRuntime.get(context)
    private fun <T> serial(block: ()->T): T=runBlocking(runtime.dispatcher) { block() }
    private fun waitFor(timeout: Long=15000,condition: ()->Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<end) { if(condition()) return;Thread.sleep(200) }
        assertTrue("condition timed out",condition())
    }
    private fun recreateTrackedActivity(original: Intent) {
        // ActivityScenario filters lifecycle events by its launch Intent. onNewIntent legitimately
        // changes Activity.intent, so restore only the harness identity before requesting recreation.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single().apply { intent=Intent(original);recreate() }
        }
        ui.waitUntil(15000) { ui.activityRule.scenario.state==androidx.lifecycle.Lifecycle.State.RESUMED }
    }
    @Test fun realAlarmPermissionsPrivacyDeepLinksAndCompressedCycles() {
        val scenarioIntent=Intent(ui.activity.intent)
        val manager=context.getSystemService(NotificationManager::class.java)
        val note=serial { NoteRepository(AndroidSql(context)).use { r ->r.initialize();r.save(Editing(Note().bodyChanged("提醒测试私密正文，不可出现在通知中。"))) } }
        val tomorrow=LocalDay.from(System.currentTimeMillis(),TimeZone.getDefault()).plus(1)
        val blocked=serial { runtime.engine.save(note.id,-1,ReminderRule(Repeat.ONCE,tomorrow,9,0),true) }
        assertEquals("BLOCKED",blocked.status)
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        serial { runtime.engine.reconcile("TEST_PERMISSION") }
        assertEquals("SCHEDULED",serial { runtime.repository.get(blocked.id)!!.status })
        manager.createNotificationChannel(NotificationChannel("test-blocked","Synthetic blocked",NotificationManager.IMPORTANCE_NONE))
        assertEquals("channel_disabled",AndroidReminderPort(context,"test-blocked").blockedReason())
        ui.waitUntil(15000) { ui.onAllNodesWithText("新增文字").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("新增文字").performClick()
        ui.onNodeWithText("正文").performTextInput("另一个草稿。通知跳转必须保留这段未正式保存的内容。")
        val calendar=Calendar.getInstance().apply { add(Calendar.MINUTE,1);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0) }
        val once=ReminderRule(Repeat.ONCE,LocalDay.from(calendar.timeInMillis,TimeZone.getDefault()),calendar.get(Calendar.HOUR_OF_DAY),calendar.get(Calendar.MINUTE))
        val live=serial { runtime.engine.save(note.id,blocked.revision,once,true) }
        assertEquals("SCHEDULED",live.status)
        // This wait observes the actual unexported OS AlarmReceiver, not a manually invoked callback.
        waitFor(180000) { serial { runtime.repository.get(live.id)!!.status=="COMPLETED" } }
        val delivered=manager.activeNotifications.single { it.tag==live.id }
        assertEquals("有一条笔记提醒",delivered.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("点击查看笔记",delivered.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.VISIBILITY_PRIVATE,delivered.notification.visibility)
        assertFalse(delivered.notification.extras.toString().contains("私密正文"))
        assertEquals(0,delivered.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        delivered.notification.contentIntent.send()
        ui.waitUntil(15000) { ui.onAllNodesWithText("正文").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("正文").assertTextContains(note.body)
        // Keep the current note intact across activity recreation after the deep link.
        recreateTrackedActivity(scenarioIntent)
        ui.onNodeWithText("正文").assertTextContains(note.body)
        ui.onNodeWithText("返回 · 保留草稿").performClick()
        ui.waitUntil(15000) { ui.onAllNodesWithText("另一个草稿").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("另一个草稿").performClick()
        ui.waitUntil(15000) { ui.onAllNodesWithText("正文").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("正文").assertTextContains("另一个草稿。通知跳转必须保留这段未正式保存的内容。")
        delivered.notification.contentIntent.send()
        ui.waitUntil(15000) { ui.onAllNodesWithText(note.body).fetchSemanticsNodes().isNotEmpty() }
        val daily=serial { runtime.engine.save(note.id,live.revision,ReminderRule(Repeat.DAILY,tomorrow,10,0),true) }
        var fixed=daily.nextAt
        val engine=serial { ReminderEngine(runtime.repository,runtime.port,{fixed}) }
        serial { engine.fire(daily.id,daily.revision,daily.nextKey,daily.nextAt) }
        waitFor { manager.activeNotifications.any { it.tag==daily.id } }
        val first=manager.activeNotifications.single { it.tag==daily.id }
        Thread.sleep(6000) // Separate OS alert-rate-limit window. The first notification remains present.
        val second=serial { runtime.repository.get(daily.id)!! }
        fixed=second.nextAt
        serial { engine.fire(second.id,second.revision,second.nextKey,second.nextAt) }
        waitFor { manager.activeNotifications.single { it.tag==daily.id }.postTime>first.postTime }
        val updated=manager.activeNotifications.single { it.tag==daily.id }
        assertEquals(0,updated.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(3,serial { runtime.repository.events().count { it.reminderId==daily.id && it.outcome=="POSTED" } })
        context.sendBroadcast(Intent(context,ReminderAlarmReceiver::class.java).setData(Uri.parse("thinkv2://alarm/${daily.id}"))
            .putExtra("revision",daily.revision).putExtra("instance",daily.nextKey).putExtra("at",daily.nextAt))
        Thread.sleep(500)
        assertEquals(3,serial { runtime.repository.events().count { it.reminderId==daily.id && it.outcome=="POSTED" } })
        // Close the current editor before deleting via the actual repository, then follow its old notification.
        ui.onNodeWithText("返回 · 保留草稿").performClick()
        ui.waitUntil(15000) { ui.onAllNodesWithText("新增文字").fetchSemanticsNodes().isNotEmpty() }
        serial { NoteRepository(AndroidSql(context)).use { r ->r.initialize();r.softDelete(r.open(note.id)!!);runtime.cancelForNote(note.id) } }
        updated.notification.contentIntent.send()
        ui.waitUntil(15000) { ui.onAllNodesWithText("这条笔记已在回收站，可返回首页后到回收站恢复。").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("正文").assertDoesNotExist()
        runtime.port.contentIntent("missing-synthetic").send()
        ui.waitUntil(15000) { ui.onAllNodesWithText("这条笔记已不存在。").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("正文").assertDoesNotExist()
        assertFalse(serial { runtime.repository.get(daily.id)!!.requested })
        assertTrue(manager.activeNotifications.none { it.tag==daily.id })
        serial { NoteRepository(AndroidSql(context)).use { r ->r.initialize();assertTrue(r.restore(r.trash().single().note).reminderDisabled) };runtime.engine.reconcile("TEST_RESTORE") }
        assertFalse(serial { runtime.repository.get(daily.id)!!.requested })
        // Activity recreation alone must not consume a valid delayed callback. No OS alarm is injected here.
        val past=Calendar.getInstance().apply { add(Calendar.MINUTE,-1);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0) }
        val delayedRule=ReminderRule(Repeat.ONCE,LocalDay.from(past.timeInMillis,TimeZone.getDefault()),past.get(Calendar.HOUR_OF_DAY),past.get(Calendar.MINUTE))
        val delayed=serial {
            val p=runtime.repository.save(note.id,runtime.repository.get(daily.id)!!.revision,delayedRule,true,"")
            runtime.repository.state(p,"SCHEDULED",delayedRule.occurrence(delayedRule.start,TimeZone.getDefault()))
            runtime.repository.get(p.id)!!
        }
        recreateTrackedActivity(scenarioIntent)
        assertEquals("SCHEDULED",serial { runtime.repository.get(delayed.id)!!.status })
        serial { runtime.engine.fire(delayed.id,delayed.revision,delayed.nextKey,delayed.nextAt) }
        assertEquals("COMPLETED",serial { runtime.repository.get(delayed.id)!!.status })
        // Future active plan retained for actual emulator boot/timezone/package recovery checks.
        val bootRule=ReminderRule(Repeat.DAILY,LocalDay.from(System.currentTimeMillis(),TimeZone.getDefault()).plus(7),9,0)
        serial { runtime.engine.save(note.id,runtime.repository.get(daily.id)!!.revision,bootRule,true) }
    }
}
