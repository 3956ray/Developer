package com.example.thinkv2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.thinkv2.notes.*
import com.example.thinkv2.ui.theme.ThinkV2Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app=applicationContext
        val model=ViewModelProvider(this,object: ViewModelProvider.Factory {
            override fun <T: ViewModel> create(modelClass: Class<T>): T {
                require(modelClass==NotesModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return NotesModel({ NoteRepository(AndroidSql(app)) }) as T
            }
        })[NotesModel::class.java]
        setContent { ThinkV2Theme {
            Scaffold { padding -> NotesScreen(model,Modifier.fillMaxSize().padding(padding)) }
        } }
    }
}
