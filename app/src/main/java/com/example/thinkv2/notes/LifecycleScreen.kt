package com.example.thinkv2.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CategorySelector(model: NotesModel) {
    val s=model.state
    val note=s.editor?.note ?: return
    var picker by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick={ picker=true },enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
        Text("分类：${note.category.ifBlank { "未分类" }}",fontSize=18.sp)
    }
    Text(if(note.categorySource=="auto") "自动建议，可手动更正" else "手动分类",fontSize=16.sp)
    if(picker) AlertDialog(onDismissRequest={ picker=false },title={ Text("选择分类") },
        text={ LazyColumn(Modifier.heightIn(max=380.dp)) {
            item { TextButton(onClick={ model.selectCategory(null);picker=false },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("未分类",fontSize=18.sp) } }
            items(s.categories,key={ it.id }) { category ->
                TextButton(onClick={ model.selectCategory(category);picker=false },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(category.name,fontSize=18.sp) }
            }
        } },
        confirmButton={ TextButton(onClick={ picker=false;creating=true },modifier=Modifier.heightIn(min=56.dp)) { Text("新建分类") } },
        dismissButton={ TextButton(onClick={ picker=false },modifier=Modifier.heightIn(min=56.dp)) { Text("取消") } })
    if(creating) CategoryForm("新建分类","",{ model.createCategory(it);creating=false },{ creating=false })
}

@Composable
internal fun LifecycleScreen(model: NotesModel,modifier: Modifier) {
    val s=model.state
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Category?>(null) }
    var removing by remember { mutableStateOf<Category?>(null) }
    Column(modifier.padding(horizontal=16.dp).imePadding()) {
        TextButton(onClick={ model.navigate(NotesPage.HOME) },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("返回笔记",fontSize=18.sp) }
        Text(if(s.page==NotesPage.CATEGORIES) "管理分类" else "回收站",fontSize=24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics { heading() })
        s.notice?.let { Text(it,fontSize=18.sp,modifier=Modifier.padding(vertical=8.dp)) }
        s.error?.let { Text(it,fontSize=18.sp,color=MaterialTheme.colorScheme.error) }
        if(s.loading || s.busy) Text("正在处理…",fontSize=18.sp)
        if(s.page==NotesPage.CATEGORIES) {
            Button(onClick={ creating=true },enabled=!s.busy && !s.loading,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("新建分类",fontSize=18.sp) }
            LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                if(s.categories.isEmpty() && !s.loading) item { Text("还没有分类。未分类也能保存笔记。",fontSize=18.sp) }
                items(s.categories,key={ it.id }) { category ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(category.name,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick={ renaming=category },enabled=!s.busy,modifier=Modifier.weight(1f).heightIn(min=56.dp).semantics { contentDescription="改名分类${category.name}" }) { Text("改名",fontSize=18.sp) }
                                TextButton(onClick={ removing=category },enabled=!s.busy,modifier=Modifier.weight(1f).heightIn(min=56.dp).semantics { contentDescription="删除分类${category.name}" }) { Text("删除分类",fontSize=18.sp) }
                            }
                        }
                    }
                }
            }
        } else {
            Text("这里的笔记不会自动清空。恢复后保留原记录和草稿。",fontSize=18.sp,modifier=Modifier.padding(vertical=8.dp))
            LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                if(s.trash.isEmpty() && !s.loading) item { Text("回收站为空",fontSize=18.sp) }
                items(s.trash,key={ it.note.id }) { item ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(item.note.title,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                            Text(matchingExcerpt(item.note.body,""),fontSize=18.sp)
                            Text("原分类：${item.note.category.ifBlank { "未分类" }}",fontSize=18.sp)
                            if(item.categoryMissing) Text("原分类已不存在，恢复时将移至未分类。",fontSize=18.sp)
                            if(item.hasDraft) Text("另保留一份尚未正式保存的草稿。",fontSize=18.sp)
                            Button(onClick={ model.restore(item.note) },enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("恢复笔记",fontSize=18.sp) }
                        }
                    }
                }
            }
        }
    }
    if(creating) CategoryForm("新建分类","",{ model.createCategory(it);creating=false },{ creating=false })
    renaming?.let { category -> CategoryForm("分类改名",category.name,{ model.renameCategory(category,it);renaming=null },{ renaming=null }) }
    removing?.let { category -> AlertDialog(onDismissRequest={ removing=null },
        title={ Text("删除分类“${category.name}”？") },text={ Text("相关笔记和草稿将移到未分类，笔记不会删除。回收站里的原分类信息会保留到恢复时处理。") },
        confirmButton={ TextButton(onClick={ model.deleteCategory(category);removing=null },modifier=Modifier.heightIn(min=56.dp)) { Text("确认删除分类") } },
        dismissButton={ TextButton(onClick={ removing=null },modifier=Modifier.heightIn(min=56.dp)) { Text("取消") } }) }
}

@Composable
private fun CategoryForm(title: String,initial: String,confirm: (String)->Unit,dismiss: ()->Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=dismiss,title={ Text(title) },
        text={ OutlinedTextField(value=name,onValueChange={ name=it },label={ Text("分类名称") },textStyle=LocalTextStyle.current.copy(fontSize=18.sp)) },
        confirmButton={ TextButton(onClick={ confirm(name) },enabled=name.isNotBlank(),modifier=Modifier.heightIn(min=56.dp)) { Text("保存分类") } },
        dismissButton={ TextButton(onClick=dismiss,modifier=Modifier.heightIn(min=56.dp)) { Text("取消") } })
}
