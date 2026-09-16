package com.example.thinkv2.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thinkv2.notes.NotesModel

@Composable
fun VoiceControls(voice: VoiceModel,notes: NotesModel,cursor: Int?) {
    val context=LocalContext.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if(it) voice.permissionGranted() else voice.permissionDenied()
    }
    var vocabulary by remember { mutableStateOf(false) }
    LaunchedEffect(voice) { voice.prepare() }
    DisposableEffect(voice) { onDispose { voice.cancel("已离开语音输入，本次结果丢弃。") } }
    LaunchedEffect(notes.state.editor?.note?.id,notes.state.editor?.note?.revision,notes.state.busy,notes.state.page) { voice.checkEditor(notes) }
    val start by rememberUpdatedState(newValue={
        if(voice.state.phase==VoicePhase.IDLE && !notes.state.busy) {
            if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice.start(notes,cursor)
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    })
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("本机离线语音 · 最长90秒",fontSize=20.sp)
        Text("音频只在内存处理，不保存或上传。识别效果仍待改进，尤其专名，请核对正文后保存；词表仅提供待确认的纠错建议。",fontSize=16.sp)
        Text(voice.state.message,fontSize=18.sp)
        if(voice.state.phase==VoicePhase.UNREADY) OutlinedButton(onClick=voice::prepare,modifier=Modifier.heightIn(min=56.dp)) { Text("准备离线语音") }
        Surface(color=if(voice.state.phase==VoicePhase.RECORDING) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            shape=MaterialTheme.shapes.medium,
            modifier=Modifier.fillMaxWidth().heightIn(min=64.dp).semantics(mergeDescendants=true) {
                role=Role.Button
                if(voice.state.phase!=VoicePhase.IDLE && voice.state.phase!=VoicePhase.RECORDING) disabled()
                onClick(label="开始录音；再次激活结束并处理") { if(voice.state.phase==VoicePhase.RECORDING) voice.release() else start();true }
            }.pointerInput(voice) {
                detectTapGestures(onPress={ start();val released=tryAwaitRelease();if(released) voice.release() else voice.cancel() })
            }) {
            Text(when(voice.state.phase) {
                VoicePhase.RECORDING -> "录音中 · 松手处理"
                VoicePhase.PROCESSING -> "正在处理语音…"
                VoicePhase.CANCELLING -> "正在取消语音…"
                VoicePhase.IDLE -> "按住说话"
                else -> "语音尚未就绪"
            },Modifier.padding(18.dp),fontSize=20.sp)
        }
        if(voice.active) OutlinedButton(onClick={ voice.cancel() },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("取消本次语音",fontSize=18.sp) }
        voice.state.suggestions.forEach { row ->
            OutlinedButton(onClick={ voice.correct(notes,row) },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("确认将本次转写中所有“${row.from}”改为“${row.to}”",fontSize=18.sp) }
        }
        TextButton(onClick={
            if(runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}"))) }.isFailure) voice.settingsUnavailable()
        },enabled=!voice.active,modifier=Modifier.heightIn(min=56.dp)) { Text("麦克风权限设置",fontSize=18.sp) }
        TextButton(onClick={ vocabulary=true },enabled=!voice.active,modifier=Modifier.heightIn(min=56.dp)) { Text("编辑本机纠错词表",fontSize=18.sp) }
    }
    if(vocabulary) VocabularyDialog(voice.state.mappings,{ voice.saveMappings(it);vocabulary=false },{ vocabulary=false })
}

@Composable
private fun VocabularyDialog(initial: List<Correction>,save: (List<Correction>)->Unit,dismiss: ()->Unit) {
    var rows by remember { mutableStateOf(initial) }
    var from by remember { mutableStateOf("") };var to by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) };var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=dismiss,title={ Text("本机纠错词表") },text={
        Column {
            Text("只保存文字，逐次确认；不会训练模型。同音词可保留多个候选。当前备份尚不包含此词表。")
            OutlinedTextField(value=from,onValueChange={ from=it },label={ Text("识别出的词") },singleLine=true)
            OutlinedTextField(value=to,onValueChange={ to=it },label={ Text("期望的词") },singleLine=true)
            TextButton(onClick={
                val next=rows.toMutableList();val row=Correction(from,to)
                if(editing==null) next.add(row) else next[editing!!]=row
                try { VoiceText.validate(next);rows=next;from="";to="";editing=null;error="" } catch(_: Exception) { error="请检查字数、空白或重复项（最多200项）。" }
            },modifier=Modifier.heightIn(min=56.dp)) { Text(if(editing==null) "添加到列表" else "更新此项") }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.heightIn(max=240.dp)) {
                itemsIndexed(rows) { i,row -> Column {
                    Text("${row.from} → ${row.to}")
                    Row {
                        TextButton(onClick={ editing=i;from=row.from;to=row.to },modifier=Modifier.heightIn(min=56.dp)) { Text("编辑第${i+1}项") }
                        TextButton(onClick={ rows=rows.filterIndexed { index,_ -> index!=i };editing=null;from="";to="" },modifier=Modifier.heightIn(min=56.dp)) { Text("删除第${i+1}项") }
                    }
                } }
            }
        }
    },confirmButton={ TextButton(onClick={ save(rows) },enabled=from.isEmpty() && to.isEmpty(),modifier=Modifier.heightIn(min=56.dp)) { Text("保存词表") } },
        dismissButton={ TextButton(onClick=dismiss,modifier=Modifier.heightIn(min=56.dp)) { Text("取消") } })
}
