package com.example.thinkv2.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BackupScreen(model: BackupModel,modifier: Modifier=Modifier,back: ()->Unit,export: ()->Unit,chooseImport: ()->Unit,
    changed: ()->Unit,openNote: (String)->Unit) {
    val s=model.state;val preview=s.preview
    BackHandler { if(!s.busy) { if(preview!=null) model.cancelPreview() else back() } }
    Column(modifier.padding(horizontal=16.dp)) {
        TextButton(onClick=back,enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("返回笔记",fontSize=18.sp) }
        Text("备份与恢复",fontSize=24.sp,modifier=Modifier.semantics { heading() })
        if(s.busy) Text("正在处理，请稍候…",fontSize=18.sp)
        s.error?.let { Text(it,fontSize=18.sp,color=MaterialTheme.colorScheme.error) }
        s.notice?.let { Text(it,fontSize=18.sp) }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            item {
                Text("备份含私人文字，属于明文。base64不是加密，校验仅检测损坏、不认证来源。",fontSize=18.sp)
                Text("当前备份包含笔记、草稿、分类、回收站和提醒配置，暂不包含语音纠错词表、日历原始来源快照和导入去重映射、AI设置及接受来源记录。凭据和未接受建议不会导出；已确认的最终标题/分类按笔记字段备份。恢复后的日历笔记文字仍在，但再次导入日历可能产生副本，须重新核对来源。",fontSize=16.sp)
                Text("请选本机安全目录。系统文件选择器也可能提供云盘；选择云盘可能上传文件。本应用不会自动上传或同步。",fontSize=18.sp)
            }
            if(preview==null) {
                item { Button(onClick=export,enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("导出备份",fontSize=18.sp) } }
                item { OutlinedButton(onClick=chooseImport,enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("恢复备份",fontSize=18.sp) } }
                if(s.error!=null && s.canRepreview) item { TextButton(onClick=model::repreview,enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text("重新预览已选文件",fontSize=18.sp) } }
                items(s.restored,key={ it.first }) { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text(row.second.ifBlank { "未命名草稿" },fontSize=20.sp)
                        TextButton(onClick={ openNote(row.first) },modifier=Modifier.heightIn(min=56.dp)) { Text("查看恢复记录",fontSize=18.sp) }
                        Text("如需提醒，请打开记录后逐项确认启用。回收站记录需先恢复。",fontSize=16.sp)
                    } }
                }
            } else {
                item {
                    Text("恢复预览 · 尚未写入",fontSize=22.sp,modifier=Modifier.semantics { heading() })
                    val d=preview.candidate.data
                    Text("文件内容：笔记 ${d.notes.size}（回收站 ${d.notes.count { it.deletedAt>0 }}）、草稿 ${d.drafts.count { it.active }}、分类 ${d.categories.size}、提醒 ${d.reminders.size}。",fontSize=18.sp)
                    Text("新增 ${preview.records.count { it.visible && it.action==ImportAction.NEW }}，相同 ${preview.records.count { it.visible && it.action==ImportAction.SAME }}，冲突副本 ${preview.records.count { it.visible && it.action==ImportAction.COPY }}，跳过 ${preview.records.count { it.visible && it.action==ImportAction.SKIP }}。",fontSize=18.sp)
                    Text("仅合并，保留本机版本；导入的提醒一律关闭。本机已有未冲突提醒不改变。",fontSize=18.sp)
                    Text("确认后此文件视为已导入，重复导入不会补入本次跳过的冲突项。",fontSize=16.sp)
                    if(!preview.alreadyImported) {
                        FilterChip(selected=s.policy==ConflictPolicy.COPY,onClick={ model.policy(ConflictPolicy.COPY) },enabled=!s.busy,label={ Text("冲突版本另存副本",fontSize=18.sp) },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp))
                        FilterChip(selected=s.policy==ConflictPolicy.SKIP,onClick={ model.policy(ConflictPolicy.SKIP) },enabled=!s.busy,label={ Text("跳过全部冲突项及其依赖",fontSize=18.sp) },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp))
                    }
                    Button(onClick={ model.apply(changed) },enabled=!s.busy && !preview.alreadyImported,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("确认合并恢复",fontSize=18.sp) }
                    OutlinedButton(onClick=model::cancelPreview,enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("取消预览",fontSize=18.sp) }
                }
                items(preview.categories.filter { it.action!=ImportAction.SAME },key={ "category-${it.source.category.id}" }) { row ->
                    Text("分类：${row.source.category.name} → ${row.target?.category?.name ?: "跳过"}${if(row.action==ImportAction.COPY) "（独立分类，不合并同名）" else ""}",fontSize=18.sp)
                }
                items(preview.records.filter { it.visible },key={ it.sourceId }) { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text(row.label,fontSize=20.sp)
                        Text(when(row.action) { ImportAction.NEW -> "新增";ImportAction.SAME -> "相同，保留本机";ImportAction.COPY -> "备份冲突副本";ImportAction.SKIP -> "跳过" },fontSize=18.sp)
                        Text(row.reason,fontSize=16.sp)
                    } }
                }
            }
        }
    }
}
