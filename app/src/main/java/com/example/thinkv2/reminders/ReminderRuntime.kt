package com.example.thinkv2.reminders

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import com.example.thinkv2.notes.AndroidSql
import com.example.thinkv2.notes.NoteRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

/** All note mutations and alarm callbacks share this serial dispatcher, including soft deletion. */
class ReminderRuntime private constructor(context: Context) {
    val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope=CoroutineScope(SupervisorJob()+dispatcher)
    val changes=MutableStateFlow(0L)
    private val app=context.applicationContext
    val port by lazy { AndroidReminderPort(app) }
    private val sql by lazy { AndroidSql(app).also { NoteRepository(it).initialize() } }
    val repository by lazy { ReminderRepository(sql) }
    val engine by lazy { ReminderEngine(repository,port) }
    private var activityStarted=false
    @Synchronized fun activityStart() {
        if(activityStarted) return
        activityStarted=true
        reconcile("COLD")
    }
    fun changed() { changes.value++ }
    fun cancelForNote(id: String) {
        engine.cancelForNote(id);changed()
        if(repository.forNote(id)?.status=="CANCEL_PENDING") enqueue(app,"DELETE_RETRY")
    }
    fun reconcile(reason: String) {
        scope.launch {
            try { if(!engine.reconcile(reason)) enqueue(app,reason) }
            catch(_: Exception) { enqueue(app,reason) }
            finally { changed() }
        }
    }
    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") // Stores applicationContext only, never an Activity.
        @Volatile private var instance: ReminderRuntime?=null
        fun get(context: Context)=instance ?: synchronized(this) { instance ?: ReminderRuntime(context).also { instance=it } }
        fun enqueue(context: Context,reason: String) {
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(7101,ComponentName(context,ReminderRecoveryService::class.java))
                .setExtras(PersistableBundle().apply { putString("reason",reason) })
                .setMinimumLatency(0).setOverrideDeadline(30_000).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }
}
class ReminderAlarmReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val id=intent.data?.takeIf { it.scheme=="thinkv2" && it.host=="alarm" }?.lastPathSegment ?: return
        val pending=goAsync();val runtime=ReminderRuntime.get(context)
        runtime.scope.launch {
            try { runtime.engine.fire(id,intent.getLongExtra("revision",-1),intent.getStringExtra("instance").orEmpty(),intent.getLongExtra("at",0)) }
            catch(_: Exception) { ReminderRuntime.enqueue(context,"ALARM_RECOVERY") }
            finally { runtime.changed();pending.finish() }
        }
    }
}
class ReminderRecoveryReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val reason=when(intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "BOOT"
            Intent.ACTION_TIME_CHANGED -> "TIME_CHANGED"
            Intent.ACTION_TIMEZONE_CHANGED -> "TIMEZONE_CHANGED"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "PACKAGE_REPLACED"
            else -> return
        }
        ReminderRuntime.enqueue(context,reason)
    }
}
class ReminderRecoveryService: JobService() {
    private var work: Job?=null
    override fun onStartJob(params: JobParameters): Boolean {
        val runtime=ReminderRuntime.get(this)
        work=runtime.scope.launch {
            val retry=try { !runtime.engine.reconcile(params.extras.getString("reason") ?: "JOB") } catch(_: Exception) { true }
            runtime.changed()
            if(isActive) jobFinished(params,retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel();return true }
}
