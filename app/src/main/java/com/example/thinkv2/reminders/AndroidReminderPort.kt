package com.example.thinkv2.reminders

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.thinkv2.MainActivity

class AndroidReminderPort(private val context: Context,private val channel: String=CHANNEL): ReminderPort {
    private val manager=context.getSystemService(NotificationManager::class.java)
    private val alarms=context.getSystemService(AlarmManager::class.java)
    init {
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel(channel,"笔记提醒",NotificationManager.IMPORTANCE_DEFAULT).apply {
            description="只显示通用提示，点击后查看本机笔记"
            lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
    }
    override fun blockedReason(): String? {
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return "notification_permission"
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "notifications_disabled"
        if(Build.VERSION.SDK_INT>=26 && manager.getNotificationChannel(channel)?.importance==NotificationManager.IMPORTANCE_NONE) return "channel_disabled"
        return null
    }
    private fun alarm(id: String,plan: ReminderPlan?=null,occurrence: Occurrence?=null): PendingIntent {
        val intent=Intent(context,ReminderAlarmReceiver::class.java).setAction("com.example.thinkv2.REMINDER")
            .setData(Uri.Builder().scheme("thinkv2").authority("alarm").appendPath(id).build())
        if(plan!=null && occurrence!=null) intent.putExtra("revision",plan.revision).putExtra("instance",occurrence.key).putExtra("at",occurrence.at)
        return PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    override fun schedule(plan: ReminderPlan,occurrence: Occurrence) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,occurrence.at,alarm(plan.id,plan,occurrence))
    }
    override fun cancelAlarm(id: String) { alarms.cancel(alarm(id)) }
    override fun cancelNotification(id: String) { manager.cancel(id,1) }
    fun contentIntent(noteId: String?): PendingIntent {
        val uri=Uri.Builder().scheme("thinkv2").authority(if(noteId==null) "reminders" else "note").apply { if(noteId!=null) appendPath(noteId) }.build()
        return PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun notification(summary: Boolean,noteId: String?): Notification {
        val title=if(summary) "有提醒可能未送达" else "有一条笔记提醒"
        val body=if(summary) "点击查看记录并重新设置" else "点击查看笔记"
        val public=NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(body).build()
        return NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(contentIntent(noteId)).setAutoCancel(true).setOnlyAlertOnce(summary).build()
    }
    override fun post(plan: ReminderPlan) {
        check(blockedReason()==null) { "notifications_blocked" }
        manager.notify(plan.id,1,notification(false,plan.noteId))
    }
    override fun postSummary() {
        check(blockedReason()==null) { "notifications_blocked" }
        manager.notify("reminder-summary",2,notification(true,null))
    }
    fun backgroundState(): String {
        val power=context.getSystemService(PowerManager::class.java)
        val battery=if(power.isIgnoringBatteryOptimizations(context.packageName)) "未受电池优化限制" else "受系统电池优化管理"
        val restricted=Build.VERSION.SDK_INT>=28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        return "$battery；${if(restricted) "后台运行受限" else "未检测到后台运行限制"}。"
    }
    fun permissionFingerprint()=blockedReason().orEmpty()
    companion object { const val CHANNEL="note-reminders-v1" }
}
