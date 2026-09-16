package com.example.thinkv2.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.thinkv2.notes.NotesModel

@Composable
fun AiDialog(ai: AiModel,notes: NotesModel) {
    val s=ai.state
    if(s.screen.isEmpty()) return
    LaunchedEffect(notes.aiAnchor(),notes.state.busy) { ai.checkCurrent(notes) }
    Dialog(onDismissRequest=ai::close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).imePadding()) {
                Text(if(s.screen=="settings") "AI建议设置" else "当前笔记的AI建议",style=MaterialTheme.typography.headlineSmall)
                TextButton(onClick=ai::close,enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("关闭AI面板") }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("ai-scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("真实服务尚未验证。AI可永久关闭，不影响本地文字与手工分类。")
                    s.message?.let { Text(it) }
                    if(s.screen=="settings") {
                        var endpoint by remember(s.endpoint) { mutableStateOf(s.endpoint) }
                        var model by remember(s.model) { mutableStateOf(s.model) }
                        var credential by remember { mutableStateOf("") }
                        var enableConsent by remember(s.endpoint,s.model,s.enabled) { mutableStateOf(false) }
                        Text("状态：${if(s.enabled) "已启用手动请求" else "关闭"}。每次重新启动均需重新启用。")
                        OutlinedTextField(endpoint,{ endpoint=it;enableConsent=false },label={ Text("完整HTTPS端点") },singleLine=true,enabled=!s.saving,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(model,{ model=it;enableConsent=false },label={ Text("模型名称") },singleLine=true,enabled=!s.saving,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(credential,{ credential=it;enableConsent=false },label={ Text("服务凭据（不显示已保存值）") },singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!s.saving,modifier=Modifier.fillMaxWidth())
                        Text("支持Chat Completions兼容JSON接口：端点须以/chat/completions结束。无重定向或备用供应商。更换端点/模型须重输凭据；相同配置可留空保留。")
                        Button(onClick={ ai.saveConfiguration(endpoint,model,credential);credential="";enableConsent=false },enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("保存配置并关闭AI") }
                        Text("已保存供应商地址：${s.endpoint.ifBlank { "未配置" }}\n模型：${s.model.ifBlank { "未配置" }}")
                        Text("用途：给当前笔记提供标题和分类候选。发送：你逐次确认的文字、所选候选分类名/ID、模型和固定提示词/参数，以及发给此地址的Bearer凭据；服务也可看到网络地址。不自动发送正文、原音频、日历快照或整库。")
                        Row { Checkbox(enableConsent,{ enableConsent=it },enabled=s.configured && !s.saving && endpoint==s.endpoint && model==s.model && credential.isEmpty(),modifier=Modifier.semantics { contentDescription="同意向已保存供应商请求建议" });Text("我已核对已保存供应商和字段，同意启用手动请求") }
                        Button(onClick={ ai.enable(enableConsent) },enabled=enableConsent && !s.saving && endpoint==s.endpoint && model==s.model && credential.isEmpty(),modifier=Modifier.heightIn(min=56.dp)) { Text("确认启用AI") }
                        OutlinedButton(onClick={ ai.disable() },enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("关闭AI并取消请求") }
                        OutlinedButton(onClick={ ai.disable(true);credential="";enableConsent=false },enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("清除配置及凭据") }
                        Text("凭据由Android Keystore保护并排除备份。当前备份不含设置或AI接受来源记录；最终笔记文字仍按原格式备份。")
                    } else {
                        Text("供应商：${s.endpoint.ifBlank { "未配置" }}\n模型：${s.model}\n当前笔记：${notes.state.editor?.note?.title.orEmpty()}")
                        if(!s.enabled) Text("AI关闭或未配置。请关闭面板，在AI建议设置中配置并确认启用。")
                        OutlinedTextField(s.text,ai::text,label={ Text("本次发送的必要文字") },enabled=!s.sending && s.proposal==null,modifier=Modifier.fillMaxWidth().heightIn(min=120.dp))
                        Text("分类候选默认不发送；仅勾选本次需要的分类（最多显示50个）。")
                        for(c in s.candidates) Row { Checkbox(c.id in s.selected,{ ai.select(c.id,it) },enabled=!s.sending && s.proposal==null,modifier=Modifier.semantics { contentDescription="发送候选分类 ${c.name}" });Text(c.name) }
                        if(s.proposal==null) {
                            Text("将发送的完整JSON（凭据不回显，仅发往上述地址）：")
                            Text(ai.preview(),Modifier.testTag("ai-outgoing"))
                            Row { Checkbox(s.consent,ai::consent,enabled=!s.sending && s.enabled,modifier=Modifier.semantics { contentDescription="确认本次发送字段" });Text("我已核对本次文字和字段，同意发送；发送后无法撤回。") }
                            Button(onClick={ ai.send(notes) },enabled=s.enabled && s.consent && !s.sending && !s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("发送一次建议请求") }
                        }
                        s.proposal?.let { p ->
                            if(!s.titleDone) {
                                var title by remember(p) { mutableStateOf(p.title.orEmpty()) }
                                Text("标题建议（可改后确认）：${p.title}")
                                OutlinedTextField(title,{ title=it },label={ Text("最终标题选择") },modifier=Modifier.fillMaxWidth())
                                Button(onClick={ ai.accept(notes,"title",title) },enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("接受此标题到草稿") }
                                TextButton(onClick={ ai.reject("title") },enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("拒绝标题建议") }
                            }
                            if(!s.categoryDone) {
                                var newName by remember(p) { mutableStateOf(p.newCategory.orEmpty()) }
                                var selected by remember(p) { mutableStateOf(p.categoryId) }
                                Text("分类建议：${p.newCategory ?: s.candidates.firstOrNull { it.id==p.categoryId }?.name}")
                                for(c in s.candidates.filter { it.id in s.selected }) OutlinedButton(onClick={ selected=c.id;newName="" },enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("改选已有分类：${c.name}") }
                                if(selected!=null) Button(onClick={ val c=s.candidates.single { it.id==selected };ai.accept(notes,"category",c.name,c.id) },enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("接受已有分类到草稿") }
                                OutlinedTextField(newName,{ newName=it;selected=null },label={ Text("待确认新分类名称") },modifier=Modifier.fillMaxWidth())
                                Button(onClick={ ai.accept(notes,"category",newName,create=true) },enabled=newName.isNotBlank() && !s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("确认创建新分类并应用到草稿") }
                                TextButton(onClick={ ai.reject("category") },enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("拒绝分类建议") }
                            }
                        }
                        OutlinedButton(onClick={ ai.prepare(notes) },enabled=!s.sending && !s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("重新选取当前笔记") }
                        OutlinedButton(onClick=ai::cancel,enabled=!s.saving,modifier=Modifier.heightIn(min=56.dp)) { Text("取消请求并丢弃建议") }
                        Text("连接超时5秒，读超时10秒，总时限20秒；单次文字8KiB，请求32KiB，响应64KiB。不自动重试。建议不执行动作、脚本或正文替换。")
                    }
                }
            }
        }
    }
}
