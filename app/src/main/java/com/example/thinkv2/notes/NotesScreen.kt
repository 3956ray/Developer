package com.example.thinkv2.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

@Composable
fun NotesScreen(model: NotesModel,modifier: Modifier = Modifier) {
    val s=model.state
    val editor=s.editor
    var confirmation by rememberSaveable { mutableStateOf("") }
    BackHandler(editor!=null || s.page!=NotesPage.HOME) { if(!s.busy) { if(editor!=null) model.back() else model.navigate(NotesPage.HOME) } }
    if(editor==null && s.page!=NotesPage.HOME) { LifecycleScreen(model,modifier);return }
    if(editor==null) {
        Column(modifier.padding(horizontal=16.dp).imePadding()) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("思 · 笔记",Modifier.weight(1f).padding(vertical=16.dp).semantics { heading() },fontSize=24.sp,fontWeight=FontWeight.Bold)
                Button(onClick=model::newNote,enabled=!s.loading && !s.busy && s.error==null,
                    modifier=Modifier.heightIn(min=56.dp)) { Text("新增文字",fontSize=18.sp) }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={ model.navigate(NotesPage.CATEGORIES) },enabled=!s.busy,modifier=Modifier.weight(1f).heightIn(min=56.dp)) { Text("管理分类",fontSize=18.sp) }
                TextButton(onClick={ model.navigate(NotesPage.TRASH) },enabled=!s.busy,modifier=Modifier.weight(1f).heightIn(min=56.dp)) { Text("回收站（${s.trash.size}）",fontSize=18.sp) }
            }
            s.notice?.let { Text(it,fontSize=18.sp) }
            OutlinedTextField(value=s.query,onValueChange=model::search,label={ Text("搜索标题或正文") },
                singleLine=true,enabled=!s.busy,modifier=Modifier.fillMaxWidth(),textStyle=LocalTextStyle.current.copy(fontSize=18.sp),
                trailingIcon={ if(s.query.isNotEmpty()) TextButton(onClick={ model.search("") },modifier=Modifier.heightIn(min=56.dp)) { Text("清空") } })
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected=s.category==null,onClick={ model.filter(null) },label={ Text("全部") },modifier=Modifier.heightIn(min=56.dp)) }
                item { FilterChip(selected=s.category=="",onClick={ model.filter("") },label={ Text("未分类") },modifier=Modifier.heightIn(min=56.dp)) }
                items(s.categories,key={ it.id }) { category -> FilterChip(selected=s.category==category.id,onClick={ model.filter(category.id) },
                    label={ Text(category.name,maxLines=2,overflow=TextOverflow.Ellipsis) },modifier=Modifier.heightIn(min=56.dp)) }
            }
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                item { VoiceUnavailable() }
                if(s.loading || s.busy) item { Text("正在读取…",fontSize=18.sp) }
                else if(s.error!=null) item {
                    Text(s.error,color=MaterialTheme.colorScheme.error,fontSize=18.sp)
                    Button(onClick={ model.refresh() },modifier=Modifier.heightIn(min=56.dp)) { Text("重试") }
                } else {
                    if(s.query.isBlank() && s.category==null && s.drafts.isNotEmpty()) {
                        item { Text("未完成草稿",fontSize=20.sp,modifier=Modifier.semantics { heading() }) }
                        items(s.drafts,key={ "draft-${it.id}" }) { note -> NoteCard(note,"草稿","",{ model.open(note.id) }) }
                    }
                    item { Text("已保存 · ${s.total} 条",fontSize=20.sp,modifier=Modifier.semantics { heading() }) }
                    if(s.notes.isEmpty()) item { Text(if(s.query.isBlank() && s.category==null) "还没有笔记，写下第一个想法吧。" else "没有匹配的笔记。试试其他关键词或分类。",fontSize=18.sp) }
                    items(s.notes,key={ it.id }) { note -> NoteCard(note,note.category.ifBlank { "未分类" },s.query,{ model.open(note.id) }) }
                    if(s.notes.size<s.total) item { OutlinedButton(onClick={ model.refresh(true) },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("加载更多",fontSize=18.sp) } }
                }
            }
        }
    } else {
        Column(modifier.imePadding().padding(horizontal=16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick=model::back,enabled=!s.busy,modifier=Modifier.weight(1f).heightIn(min=56.dp)) { Text("返回 · 保留草稿",fontSize=18.sp) }
                Button(onClick=model::save,enabled=!s.busy && editor.note.body.isNotBlank(),modifier=Modifier.weight(0.5f).heightIn(min=56.dp)) { Text("保存",fontSize=18.sp) }
            }
            Text(if(s.busy) "正在处理…" else when(s.draftState) {
                DraftState.UNSAVED -> "尚未保存"; DraftState.SAVING -> "正在保存草稿…"
                DraftState.SAVED -> "草稿已保存在本机，尚未正式保存"
                DraftState.FORMAL -> "正在查看已正式保存的版本"
                DraftState.ERROR -> "保存未完成"
            },fontSize=18.sp,modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
            if(s.error!=null) {
                Text(s.error,color=MaterialTheme.colorScheme.error,fontSize=18.sp)
                OutlinedButton(onClick=model::persist,enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("重试保存草稿") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value=editor.note.title,onValueChange=model::title,label={ Text(if(editor.note.manualTitle) "手动标题" else "自动标题") },
                    enabled=!s.busy,modifier=Modifier.fillMaxWidth(),textStyle=LocalTextStyle.current.copy(fontSize=20.sp))
                if(editor.note.manualTitle) TextButton(onClick={ model.title("") },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("恢复自动标题",fontSize=18.sp) }
                OutlinedTextField(value=editor.note.body,onValueChange=model::body,label={ Text("正文") },placeholder={ Text("写下想法…") },
                    enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=240.dp),textStyle=LocalTextStyle.current.copy(fontSize=18.sp))
                CategorySelector(model)
                VoiceUnavailable()
                OutlinedButton(onClick={ confirmation="discard" },enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("放弃这次编辑",fontSize=18.sp) }
                if(editor.baseRevision>=0) OutlinedButton(onClick={ confirmation="trash" },enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("移入回收站",fontSize=18.sp) }

            }
        }
    }
    if(confirmation.isNotEmpty() && editor!=null) AlertDialog(onDismissRequest={ confirmation="" },
        title={ Text(if(confirmation=="trash") "移入回收站？" else "放弃这次编辑？") },
        text={ Text(if(confirmation=="trash") "已保存的笔记和当前草稿都会保留，可在回收站恢复。" else "清除本次草稿，保留上次正式保存的笔记。") },
        confirmButton={ TextButton(onClick={ val trash=confirmation=="trash";confirmation="";if(trash) model.moveToTrash() else model.discard() },modifier=Modifier.heightIn(min=56.dp)) { Text("确认") } },
        dismissButton={ TextButton(onClick={ confirmation="" },modifier=Modifier.heightIn(min=56.dp)) { Text("取消") } })
}

@Composable
private fun NoteCard(note: Note,label: String,query: String,open: ()->Unit) {
    OutlinedCard(onClick=open,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(note.title.ifBlank { "未命名草稿" },fontSize=20.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(matchingExcerpt(note.body,query),fontSize=18.sp,maxLines=if(query.isBlank()) 3 else Int.MAX_VALUE,overflow=TextOverflow.Ellipsis)
            Text("$label · ${DateFormat.getDateInstance().format(Date(note.updated))}",maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VoiceUnavailable() {
    OutlinedButton(onClick={},enabled=false,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),
        colors=ButtonDefaults.outlinedButtonColors(disabledContentColor=MaterialTheme.colorScheme.onSurfaceVariant)) {
        Text("语音输入暂不可用",fontSize=18.sp)
    }
}
