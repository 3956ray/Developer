package com.example.thinkv2.relations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RelationsScreen(model: RelationsModel,modifier: Modifier=Modifier,back: ()->Unit,open: (String)->Unit) {
    val s=model.state
    var search by remember(s.sourceId,s.choosing) { mutableStateOf("") }
    var add by remember { mutableStateOf<RelatedNote?>(null) }
    var remove by remember { mutableStateOf<RelationRow?>(null) }
    BackHandler { if(!s.busy) back() }
    Column(modifier.padding(16.dp).imePadding()) {
        Text("相关笔记",fontSize=24.sp,modifier=Modifier.semantics { heading() })
        TextButton(onClick=back,enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("返回当前笔记",fontSize=18.sp) }
        LazyColumn(Modifier.weight(1f).testTag("relations-list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text("${s.page?.source?.title ?: "当前笔记"}\n记录：${s.sourceId}",fontSize=18.sp)
                Text("手工建立双向“相关”，不复制正文。回收站中的笔记暂不显示，恢复原记录后关系可重新显示。当前备份不包含这些关系。",fontSize=18.sp)
            }
            s.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error,fontSize=18.sp) } }
            s.message?.let { item { Text(it,fontSize=18.sp) } }
            item {
                OutlinedButton(onClick={ model.mode(!s.choosing) },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text(if(s.choosing) "查看已有关系" else "选择另一笔记建立关系",fontSize=18.sp) }
                OutlinedTextField(search,{ search=it },label={ Text(if(s.choosing) "搜索可关联笔记" else "搜索相关笔记") },singleLine=true,enabled=!s.busy,modifier=Modifier.fillMaxWidth())
                Row {
                    Button(onClick={ model.search(search) },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("搜索",fontSize=18.sp) }
                    TextButton(onClick={ search="";model.search("") },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("清空搜索",fontSize=18.sp) }
                    TextButton(onClick={ model.load(0) },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("刷新",fontSize=18.sp) }
                }
            }
            if(s.busy) item { Text("正在读取或提交…",fontSize=18.sp) }
            s.page?.let { p ->
                val count=if(s.choosing) p.candidates.size else p.rows.size
                item { Text("${if(s.choosing) "可关联" else "已有关系"} ${p.total} 条 · 当前 ${if(count==0) 0 else p.offset+1}–${p.offset+count} · 每页最多50条",fontSize=18.sp) }
                if(count==0) item { Text(if(s.query.isNotBlank()) "没有匹配结果。" else if(s.choosing) "没有其他可关联的已保存笔记。" else "还没有相关笔记。",fontSize=18.sp) }
                if(s.choosing) items(p.candidates,key={ it.id }) { n ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        NoteSummary(n)
                        Button(onClick={ add=n },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp).relatedAction("关联笔记 ${n.title}，记录 ${n.id}",!s.busy) { add=n }) { Text("选择此笔记",fontSize=18.sp) }
                    } }
                } else items(p.rows,key={ it.edgeId }) { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        NoteSummary(row.note)
                        Button(onClick={ open(row.note.id) },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp).relatedAction("打开相关笔记 ${row.note.title}，记录 ${row.note.id}",!s.busy) { open(row.note.id) }) { Text("打开这条笔记",fontSize=18.sp) }
                        OutlinedButton(onClick={ remove=row },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp).relatedAction("移除与 ${row.note.title} 的关系，记录 ${row.note.id}",!s.busy) { remove=row }) { Text("移除关系",fontSize=18.sp) }
                    } }
                }
                item { Row {
                    OutlinedButton(onClick={ model.load((p.offset-RelationRepository.PAGE).coerceAtLeast(0)) },enabled=!s.busy && p.offset>0,modifier=Modifier.heightIn(min=56.dp)) { Text("上一页",fontSize=18.sp) }
                    OutlinedButton(onClick={ model.load(p.offset+RelationRepository.PAGE) },enabled=!s.busy && p.offset+count<p.total,modifier=Modifier.heightIn(min=56.dp)) { Text("下一页",fontSize=18.sp) }
                } }
            }
        }
    }
    add?.let { n -> AlertDialog(onDismissRequest={ add=null },title={ Text("建立相关关系？") },text={ Text("${n.title}\n记录：${n.id}\n双方都会显示这条手工关系。") },
        confirmButton={ TextButton(onClick={ add=null;model.create(n) }) { Text("确认建立关系") } },dismissButton={ TextButton(onClick={ add=null }) { Text("取消") } }) }
    remove?.let { row -> AlertDialog(onDismissRequest={ remove=null },title={ Text("移除这条关系？") },text={ Text("${row.note.title}\n记录：${row.note.id}\n只移除关系，不删除任何笔记。") },
        confirmButton={ TextButton(onClick={ remove=null;model.remove(row) }) { Text("确认移除关系") } },dismissButton={ TextButton(onClick={ remove=null }) { Text("取消") } }) }
}
@Composable private fun NoteSummary(n: RelatedNote) {
    Text(n.title.ifBlank { "无标题笔记" },fontSize=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
    Text("分类：${n.category.ifBlank { "未分类" }}",fontSize=18.sp)
    Text(n.excerpt,fontSize=18.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
    Text("记录：${n.id}",fontSize=16.sp)
}

/** Keep the spoken identity and action on the same platform accessibility node. */
private fun Modifier.relatedAction(label: String,enabled: Boolean,action: ()->Unit)=clearAndSetSemantics {
    contentDescription=label;role=Role.Button
    if(!enabled) disabled() else onClick(label) { action();true }
}
