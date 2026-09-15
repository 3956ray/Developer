package com.example.thinkv2.reminders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@Composable
fun ReminderScreen(model: ReminderModel,modifier: Modifier=Modifier,back: ()->Unit,openNote: (String)->Unit,
    requestPermission: ()->Unit,settings: ()->Unit) {
    val s=model.state;val f=s.form
    BackHandler { if(!s.busy) { if(f!=null) model.backForm() else back() } }
    Column(modifier.padding(horizontal=16.dp).imePadding()) {
        TextButton(onClick={ if(f!=null) model.backForm() else back() },enabled=!s.busy,modifier=Modifier.heightIn(min=56.dp)) { Text(if(f!=null) "返回提醒列表" else "返回笔记",fontSize=18.sp) }
        Text(if(f==null) "提醒计划与记录" else "设置笔记提醒",fontSize=24.sp,modifier=Modifier.semantics { heading() })
        s.error?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=18.sp) }
        s.notice?.let { Text(it,fontSize=18.sp) }
        if(s.busy) Text("正在处理…",fontSize=18.sp)
        if(f!=null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("按设备当地日期和时刻提醒：${TimeZone.getDefault().id}",fontSize=18.sp)
                Row(Modifier.fillMaxWidth()) {
                    Text("启用此提醒",Modifier.weight(1f).padding(vertical=16.dp),fontSize=18.sp)
                    Switch(checked=f.enabled,onCheckedChange={ checked -> model.edit { it.copy(enabled=checked) } },enabled=!s.busy)
                }
                for(repeat in Repeat.entries) FilterChip(selected=f.repeat==repeat,onClick={ model.edit { it.copy(repeat=repeat) } },
                    label={ Text(repeatLabel(repeat),fontSize=18.sp) },enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp))
                OutlinedTextField(value=f.date,onValueChange={ text -> model.edit { it.copy(date=text) } },label={ Text(if(f.repeat==Repeat.ONCE) "提醒日期 YYYY-MM-DD" else "开始日期 YYYY-MM-DD") },enabled=!s.busy,singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=f.time,onValueChange={ text -> model.edit { it.copy(time=text) } },label={ Text("当地时间 HH:mm（24小时制）") },enabled=!s.busy,singleLine=true,modifier=Modifier.fillMaxWidth())
                if(f.repeat==Repeat.WEEKLY) for(day in 0..6) {
                    FilterChip(selected=f.weekdays and (1 shl day)!=0,onClick={ model.edit { it.copy(weekdays=it.weekdays xor (1 shl day)) } },
                        label={ Text("每周${dayLabel(day)}",fontSize=18.sp) },enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp))
                }
                Text("采用系统非精确提醒，可能延迟。跨时区仍保持当地时刻；夏令时缺失时刻顺延到第一个有效时刻，重复时刻仅提醒一次。",fontSize=18.sp)
                Text("修改或关闭会撤销旧计划和该笔记的当前通知；正文编辑不会更改计划。",fontSize=18.sp)
                Button(onClick=model::save,enabled=!s.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("保存提醒规则",fontSize=18.sp) }
                Spacer(Modifier.height(24.dp))
            }
        } else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            item {
                Text("非精确提醒可能延迟；电池优化、后台限制、休眠或强行停止应用可能影响触发。强行停止后需重新打开应用。",fontSize=18.sp)
                Text(s.background,fontSize=16.sp)
                OutlinedButton(onClick=requestPermission,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("允许通知",fontSize=18.sp) }
                OutlinedButton(onClick=settings,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("打开系统通知设置",fontSize=18.sp) }
                OutlinedButton(onClick=model::retry,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("重新检查提醒状态",fontSize=18.sp) }
                if(s.plans.isEmpty()) Text("还没有提醒。打开一条已保存笔记，选择“设置笔记提醒”。",fontSize=18.sp)
            }
            items(s.plans,key={ it.id }) { p ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(s.labels[p.noteId].orEmpty(),fontSize=20.sp)
                        Text(ruleLabel(p.rule),fontSize=18.sp)
                        Text(reminderStatus(p),fontSize=18.sp)
                        if(p.nextAt>0) Text("下次计划：${DateFormat.getDateTimeInstance().format(Date(p.nextAt))}（${TimeZone.getDefault().id}）",fontSize=16.sp)
                        TextButton(onClick={ openNote(p.noteId) },modifier=Modifier.heightIn(min=56.dp)) { Text("查看笔记",fontSize=18.sp) }
                        OutlinedButton(onClick={ model.open(p.noteId) },modifier=Modifier.heightIn(min=56.dp)) { Text("修改或重新设置",fontSize=18.sp) }
                    }
                }
            }
            item { Text("触发记录 · ${s.eventCount}",fontSize=22.sp,modifier=Modifier.semantics { heading() });Text("“已提交”不代表已经阅读。未确认的实例不会自动重发，重复计划会继续后续周期。",fontSize=18.sp) }
            items(s.events,key={ "${it.reminderId}/${it.revision}/${it.key}" }) { e ->
                val p=s.plans.firstOrNull { it.id==e.reminderId }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(p?.let { s.labels[it.noteId] } ?: "原提醒记录",fontSize=20.sp)
                        Text("${e.key.replace('T',' ')} · 规则版本 ${e.revision}",fontSize=18.sp)
                        Text(eventStatus(e),fontSize=18.sp);Text(eventReason(e),fontSize=16.sp)
                        if(p!=null) {
                            TextButton(onClick={ openNote(p.noteId) },modifier=Modifier.heightIn(min=56.dp)) { Text("查看对应笔记",fontSize=18.sp) }
                            TextButton(onClick={ model.open(p.noteId) },modifier=Modifier.heightIn(min=56.dp)) { Text("重新设置此提醒",fontSize=18.sp) }
                        }
                    }
                }
            }
            if(s.events.size<s.eventCount) item { Button(onClick=model::more,modifier=Modifier.heightIn(min=56.dp)) { Text("加载更多记录",fontSize=18.sp) } }
        }
    }
}
private fun repeatLabel(repeat: Repeat)=when(repeat) { Repeat.ONCE -> "仅一次";Repeat.DAILY -> "每天";Repeat.WEEKLY -> "每周（可多选）" }
private fun dayLabel(day: Int)=listOf("一","二","三","四","五","六","日")[day]
private fun ruleLabel(r: ReminderRule)=buildString {
    append(repeatLabel(r.repeat));append(" · ");append(r.start);append(" 起 · ");append("%02d:%02d".format(java.util.Locale.ROOT,r.hour,r.minute))
    if(r.repeat==Repeat.WEEKLY) append((0..6).filter { r.weekdays and (1 shl it)!=0 }.joinToString(prefix=" · 周",separator="、") { dayLabel(it) })
}
