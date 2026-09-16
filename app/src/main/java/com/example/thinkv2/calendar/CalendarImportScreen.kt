package com.example.thinkv2.calendar

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CalendarImportScreen(model: CalendarImportModel,modifier: Modifier=Modifier,back: ()->Unit,changed: ()->Unit) {
    val s=model.state
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) model.calendars() else model.denied() }
    var detail by remember { mutableStateOf<String?>(null) }
    DisposableEffect(model) { onDispose { model.leave() } }
    BackHandler { if(!s.committing) { model.cancel();back() } }
    Column(modifier.padding(16.dp).imePadding()) {
        Text("只读日历导入",fontSize=24.sp,modifier=Modifier.semantics { heading() })
        TextButton(onClick={ model.cancel();back() },enabled=!s.committing,modifier=Modifier.heightIn(min=56.dp)) { Text("返回笔记") }
        LazyColumn(Modifier.weight(1f).testTag("calendar-list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("先选择日历和有限日期，再预览确认。源日历不会改变，导入不会开启本机提醒或发送AI。",fontSize=18.sp) }
            item { Text("当前备份保留导入笔记文字，但不含原始来源快照和去重映射；恢复后再次导入须核对副本。",fontSize=16.sp) }
            s.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error,fontSize=18.sp) } }
            s.message?.let { item { Text(it,fontSize=18.sp) } }
            item { OutlinedButton(onClick={ if(model.permitted()) model.calendars() else permission.launch(Manifest.permission.READ_CALENDAR) },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("读取可选日历") } }
            items(s.calendars,key={ "calendar-${it.id}" }) { calendar ->
                OutlinedButton(onClick={ model.choose(calendar) },enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                    Text("${if(s.chosen==calendar) "已选 · " else ""}${calendar.label}\n${calendar.fields["account_name"].orEmpty()} · ${calendar.fields["account_type"].orEmpty()}")
                }
            }
            item {
                OutlinedTextField(s.first,{ model.dates(first=it) },label={ Text("开始日期 YYYY-MM-DD") },singleLine=true,enabled=!s.busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(s.last,{ model.dates(last=it) },label={ Text("结束日期 YYYY-MM-DD（含当天）") },singleLine=true,enabled=!s.busy,modifier=Modifier.fillMaxWidth())
                Text("范围按 ${s.zone}，最多366天；最多300个源事件，单事件128KiB。重复系列按一条主事件导入。")
                Button(onClick=model::preview,enabled=!s.busy && s.chosen!=null,modifier=Modifier.heightIn(min=56.dp)) { Text("读取事件预览") }
            }
            val p=s.preview
            if(p!=null) {
                item {
                    val chosen=p.rows.filter { it.event.key in s.selected }
                    Text("将新增 ${chosen.size} 条 · 已导入 ${p.rows.count { it.action==CalendarAction.SAME }} 条 · 源变更 ${p.rows.count { it.action==CalendarAction.CHANGED }} 条",fontSize=18.sp)
                    Text("含未转换元数据 ${p.rows.count { it.event.unconverted }} 条（已选 ${chosen.count { it.event.unconverted }}）；跳过 ${p.rows.count { it.action!=CalendarAction.SAME && it.event.key !in s.selected }} 条。")
                    Text("源变更只可跳过或导入新副本，现有笔记/草稿/分类/回收站均保留。")
                    Text("导入分类：${s.categories.firstOrNull { it.id==s.categoryId }?.name ?: "未分类"}")
                    OutlinedButton(onClick={ model.category("") },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("选择未分类") }
                }
                items(s.categories,key={ "category-${it.id}" }) { c -> TextButton(onClick={ model.category(c.id) },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("选择分类：${c.name}") } }
                items(p.rows,key={ it.event.key }) { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(row.event.title.ifBlank { "无标题事件" },fontSize=20.sp)
                        Text(row.event.description.take(300));Text(row.event.kind)
                        Text(when(row.action) { CalendarAction.NEW -> "新来源";CalendarAction.SAME -> "已导入此来源版本，不重复";CalendarAction.CHANGED -> "源内容已变更";CalendarAction.SKIPPED -> "标题及描述均空，跳过" })
                        if(row.localChanged) Text("本机内容/草稿/回收站状态与原导入时不同，将完整保留。")
                        if(row.event.unconverted) Text("重复、例外或提醒信息保留原文，未转换。")
                        TextButton(onClick={ detail=row.event.originalText() },modifier=Modifier.heightIn(min=56.dp)) { Text("查看完整源内容 · ${row.event.id}") }
                        if(row.action in setOf(CalendarAction.NEW,CalendarAction.CHANGED)) Row {
                            Checkbox(row.event.key in s.selected,{ model.select(row,it) },enabled=!s.busy,modifier=Modifier.semantics { contentDescription="选择事件 ${row.event.id}" })
                            Text(if(row.action==CalendarAction.CHANGED) "导入新副本（保留本机）" else "导入此事件",Modifier.padding(top=12.dp))
                        }
                    } }
                }
                item { Row {
                    Checkbox(s.identityConfirmed,model::acknowledge,enabled=!s.busy,modifier=Modifier.semantics { contentDescription="确认已核对来源身份" })
                    Text("我已核对来源；账户/日历重建后ID可能变化或复用，无法保证跨重建自动去重，相似标题日期也不代表同一事件。",Modifier.padding(top=12.dp))
                } }
                item { Button(onClick={ model.apply(changed) },enabled=!s.busy && s.selected.isNotEmpty() && s.identityConfirmed,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("确认导入所选事件") } }
            }
            if(s.busy) item { Text(if(s.committing) "正在重检并提交，暂不能取消…" else "正在只读查询…") }
            item { OutlinedButton(onClick=model::cancel,enabled=!s.committing,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("取消预览") } }
        }
    }
    detail?.let { original -> AlertDialog(onDismissRequest={ detail=null },title={ Text("日历原始快照") },
        text={ Text(original,Modifier.verticalScroll(rememberScrollState())) },confirmButton={ TextButton(onClick={ detail=null }) { Text("关闭") } }) }
}
