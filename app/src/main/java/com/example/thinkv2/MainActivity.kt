package com.example.thinkv2

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.*
import com.example.thinkv2.backup.*
import com.example.thinkv2.voice.*
import com.example.thinkv2.calendar.*
import com.example.thinkv2.ai.*
import com.example.thinkv2.relations.*
import com.example.thinkv2.ui.theme.ThinkV2Theme

class MainActivity : ComponentActivity() {
    private lateinit var notes: NotesModel
    private lateinit var reminders: ReminderModel
    private lateinit var backups: BackupModel
    private lateinit var voice: VoiceModel
    private lateinit var calendarImport: CalendarImportModel
    private lateinit var ai: AiModel
    private lateinit var relations: RelationsModel
    private val createBackup=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { backups.exportPicked(it) }
    private val openBackup=registerForActivityResult(ActivityResultContracts.OpenDocument()) { backups.importPicked(it) }
    private lateinit var runtime: ReminderRuntime
    private var permissionState: String?=null
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        runtime.reconcile("PERMISSION");reminders.refresh()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        val app=applicationContext;runtime=ReminderRuntime.get(app)
        val provider=ViewModelProvider(this,object: ViewModelProvider.Factory {
            override fun <T: ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return when(modelClass) {
                    NotesModel::class.java -> NotesModel({ NoteRepository(AndroidSql(app)) },worker=runtime.dispatcher,closeWorkerOnShutdown=false,onTrashed=runtime::cancelForNote)
                    ReminderModel::class.java -> ReminderModel(runtime)
                    BackupModel::class.java -> BackupModel(app,runtime)
                    VoiceModel::class.java -> VoiceModel(app)
                    CalendarImportModel::class.java -> CalendarImportModel(app,runtime)
                    AiModel::class.java -> AiModel(AiVault(app))
                    RelationsModel::class.java -> RelationsModel(app,runtime)
                    else -> error("unknown_model")
                } as T
            }
        })
        notes=provider[NotesModel::class.java];reminders=provider[ReminderModel::class.java];backups=provider[BackupModel::class.java];voice=provider[VoiceModel::class.java];calendarImport=provider[CalendarImportModel::class.java];ai=provider[AiModel::class.java];relations=provider[RelationsModel::class.java]
        runtime.activityStart()
        handleLink(intent,false)
        setContent { ThinkV2Theme {
            Scaffold { padding ->
                val modifier=Modifier.fillMaxSize().padding(padding)
                if(notes.state.page==NotesPage.REMINDERS) ReminderScreen(reminders,modifier,notes::closeReminders,
                    { notes.openIncoming(it,true) },{ if(Build.VERSION.SDK_INT>=33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings() },::openNotificationSettings)
                else if(notes.state.page==NotesPage.BACKUP) BackupScreen(backups,modifier,{ notes.navigate(NotesPage.HOME) },
                    { backups.prepareExport { createBackup.launch(it) } },{ backups.beginImport { openBackup.launch(arrayOf("*/*")) } },
                    { notes.refresh() },{ notes.openIncoming(it,true) })
                else if(notes.state.page==NotesPage.CALENDAR) CalendarImportScreen(calendarImport,modifier,{ notes.navigate(NotesPage.HOME) },notes::refresh)
                else if(notes.state.page==NotesPage.RELATIONS) RelationsScreen(relations,modifier,notes::closeRelations,{ notes.openIncoming(it,true) })
                else NotesScreen(notes,modifier,voice=voice,ai=ai,onReminders={ id -> reminders.open(id);notes.showReminders() },onBackup={ notes.navigate(NotesPage.BACKUP) },onCalendar={ notes.navigate(NotesPage.CALENDAR) },onRelations={ notes.showRelations(relations::open) })
                AiDialog(ai,notes)
            }
        } }
    }
    override fun onStop() { if(::ai.isInitialized && ai.state.sending) ai.cancel();if(::voice.isInitialized) voice.cancel("应用进入后台，本次语音已丢弃。 ");super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);handleLink(intent,true) }
    private fun handleLink(intent: Intent,newIntent: Boolean) {
        val uri=intent.data ?: return
        if(uri.scheme!="thinkv2") return
        when(uri.host) {
            "note" -> uri.lastPathSegment?.let { notes.openIncoming(it,newIntent) }
            "reminders" -> { reminders.open(null);notes.showReminders() }
        }
    }
    override fun onResume() {
        super.onResume()
        if(!::runtime.isInitialized) return
        val current=runtime.port.permissionFingerprint()
        if(permissionState!=null && permissionState!=current) runtime.reconcile("PERMISSION")
        permissionState=current;reminders.refresh()
    }
    private fun openNotificationSettings() {
        val intent=if(Build.VERSION.SDK_INT>=26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))
        startActivity(intent)
    }
}
