package com.example.thinkv2.backup

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.example.thinkv2.notes.AndroidSql
import com.example.thinkv2.notes.NoteRepository
import com.example.thinkv2.reminders.ReminderRuntime
import kotlinx.coroutines.*

data class BackupState(val busy: Boolean=false,val error: String?=null,val notice: String?=null,
    val preview: BackupPreview?=null,val policy: ConflictPolicy=ConflictPolicy.COPY,
    val restored: List<Pair<String,String>> = emptyList(),val canRepreview: Boolean=false)
class BackupModel(context: Context,private val runtime: ReminderRuntime): ViewModel() {
    private val app=context.applicationContext
    private val storage=BackupStorage(app)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var sql: AndroidSql?=null
    private fun repository(): BackupRepository {
        val db=sql ?: AndroidSql(app).also { NoteRepository(it).initialize();sql=it }
        return BackupRepository(db)
    }
    var state by mutableStateOf(BackupState());private set
    private var export: BackupExport?=null
    private var candidate: BackupCandidate?=null
    fun prepareExport(choose: (String)->Unit) {
        if(state.busy) return
        candidate=null
        state=state.copy(busy=true,error=null,notice=null,preview=null,restored=emptyList(),canRepreview=false)
        scope.launch {
            try {
                export=withContext(runtime.dispatcher) { repository().export() }
                choose("未完成-thinkV2-${export!!.createdAtUTC.take(10)}.thinkbackup.json")
            } catch(e: Exception) { state=state.copy(busy=false,error=message(e)) }
        }
    }
    fun exportPicked(uri: Uri?) {
        val ready=export;export=null
        if(uri==null) { state=state.copy(busy=false,notice="已取消导出，本机内容不变。");return }
        if(ready==null) { state=state.copy(busy=false,error="导出流程已中断，请重新开始。所选位置若有未完成文件，请删除。");return }
        scope.launch {
            try {
                val name=withContext(Dispatchers.IO) { storage.write(uri,ready) }
                state=state.copy(busy=false,notice="备份已写入并校验完成：$name")
            } catch(e: Exception) { state=state.copy(busy=false,error=message(e)) }
        }
    }
    fun beginImport(choose: ()->Unit) {
        if(state.busy) return
        state=BackupState(busy=true);candidate=null
        try { choose() } catch(e: Exception) { state=state.copy(busy=false,error=message(e)) }
    }
    fun importPicked(uri: Uri?) {
        if(uri==null) { state=state.copy(busy=false,notice="已取消恢复，本机内容不变。");return }
        scope.launch {
            try { candidate=withContext(Dispatchers.IO) { storage.read(uri) };loadPreview() }
            catch(e: Exception) { state=state.copy(busy=false,error=message(e)) }
        }
    }
    fun policy(policy: ConflictPolicy) {
        if(state.busy) return
        state=state.copy(policy=policy,busy=true,error=null)
        scope.launch { loadPreview() }
    }
    private suspend fun loadPreview() {
        try {
            val file=candidate ?: throw BackupFailure("missing_candidate")
            val p=withContext(runtime.dispatcher) { repository().preview(file,state.policy) }
            state=state.copy(busy=false,preview=p,error=null,notice=if(p.alreadyImported) "这个备份已处理过（可能包含主动跳过项）；不会补入跳过项、新增副本或改变提醒。" else null)
        } catch(e: Exception) { state=state.copy(busy=false,preview=null,error=message(e),canRepreview=candidate!=null) }
    }
    fun repreview() { if(!state.busy) { state=state.copy(busy=true,error=null);scope.launch { loadPreview() } } }
    fun cancelPreview() { if(!state.busy) { candidate=null;state=BackupState(notice="已取消预览，本机内容不变。") } }
    fun apply(onChanged: ()->Unit) {
        val preview=state.preview ?: return
        if(state.busy) return
        state=state.copy(busy=true,error=null)
        scope.launch {
            try {
                val result=withContext(runtime.dispatcher) { repository().apply(preview) }
                val labels=preview.records.filter { it.visible }.associate { it.targetId to it.label }
                val visible=result.importedIds.filter { it in labels }
                state=state.copy(busy=false,preview=null,restored=visible.map { it to labels[it].orEmpty() },notice=
                    if(result.alreadyImported) "这个备份已处理过（可能包含主动跳过项），本次没有修改内容。" else "恢复已提交：新增 ${visible.size} 条可查看记录（含冲突副本 ${preview.records.count { it.visible && it.action==ImportAction.COPY }} 条），跳过 ${preview.records.count { it.visible && it.action==ImportAction.SKIP }} 条。导入的提醒保持关闭；AI需本机重新确认。")
                candidate=null;onChanged()
            } catch(e: Exception) { state=state.copy(busy=false,preview=if(e is BackupFailure && e.code=="stale_preview") null else state.preview,error=message(e),canRepreview=candidate!=null) }
        }
    }
    override fun onCleared() { scope.cancel();runtime.scope.launch { sql?.close() } }
    private fun message(error: Exception): String=when {
        error is BackupWriteFailure -> {
            val stage=when(error.phase) { "name","mark_partial","verify_partial_name" -> "文件提供者未能保留未完成文件名";"write_close" -> "文件写入或关闭失败";"read_verify" -> "写入后读回校验失败";"finalize" -> "文件完成改名失败";else -> "文件操作失败" }
            "$stage，导出未完成。" + if(error.removedPartial) "未完成文件已清理，请检查空间和文件权限后重试。" else "无法确认未完成文件已清理，请删除所选位置的未完成文件后重试。"
        }
        error is BackupFailure -> when(error.code) {
            "size_limit","count_limit","structure_limit" -> "备份超出保护上限，已拒绝且未修改本机数据。文件最多64MiB、内容32MiB、10000条记录、深度32。"
            "unsupported_version","unknown_or_missing_field","unsupported_relations" -> "备份包含不支持的版本或字段，未降级导入，本机内容不变。"
            "vocabulary_merge_limit" -> "合并后的纠错词表超过200项，尚未修改任何数据。请整理本机词表后重新预览。"
            "export_id_reused" -> "这个备份编号曾用于不同内容，已拒绝恢复。"
            "stale_preview" -> "本机持久化资料已变化，请重新预览后确认。"
            else -> "备份格式、校验或引用无效，未修改本机内容。请检查原文件。"
        }
        else -> "操作未完成，请检查文件权限或可用空间后重试。本机原有内容保持不变。"
    }
}
