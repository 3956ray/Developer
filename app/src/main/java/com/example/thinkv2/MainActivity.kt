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
import com.example.thinkv2.ui.theme.ThinkV2Theme

class MainActivity : ComponentActivity() {
    private lateinit var notes: NotesModel
    private lateinit var reminders: ReminderModel
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
                    else -> error("unknown_model")
                } as T
            }
        })
        notes=provider[NotesModel::class.java];reminders=provider[ReminderModel::class.java]
        runtime.activityStart()
        handleLink(intent,false)
        setContent { ThinkV2Theme {
            Scaffold { padding ->
                val modifier=Modifier.fillMaxSize().padding(padding)
                if(notes.state.page==NotesPage.REMINDERS) ReminderScreen(reminders,modifier,notes::closeReminders,
                    { notes.openIncoming(it,true) },{ if(Build.VERSION.SDK_INT>=33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings() },::openNotificationSettings)
                else NotesScreen(notes,modifier) { id -> reminders.open(id);notes.showReminders() }
            }
        } }
    }
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
