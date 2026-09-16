package com.example.thinkv2.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.thinkv2.notes.NotesModel
import com.example.thinkv2.notes.AndroidSql
import com.example.thinkv2.notes.NoteRepository
import java.util.concurrent.Executors

enum class VoicePhase { UNREADY, PREPARING, IDLE, RECORDING, PROCESSING, CANCELLING }
data class VoiceState(val phase: VoicePhase=VoicePhase.UNREADY,val message: String="离线语音尚未准备",
    val mappings: List<Correction> = emptyList(),val suggestions: List<Correction> = emptyList(),
    val commandMode: Boolean=false,val command: VoiceCommandPreview?=null)

class VoiceModel(context: Context,private val testDecoder: (() -> VoiceDecoder)?=null,private val createCapture: (Context)->VoiceCapture={ VoiceCapture({ AndroidMicrophone(it) }) }) : ViewModel() {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val engine=OfflineEngine(app)
    private fun <T> vocabulary(block: (VocabularyRepository)->T): T = AndroidSql(app).use { db ->
        NoteRepository(db).initialize();block(VocabularyRepository(db))
    }
    fun reloadMappings() {
        cancel()
        worker.execute {
            val result=runCatching { vocabulary { it.rows() } }
            main.post { if(!closed) state=if(result.isSuccess) state.copy(mappings=result.getOrThrow(),suggestions=emptyList()) else state.copy(message="词表读取失败，请重试。") }
        }
    }
    var state by mutableStateOf(VoiceState())
        private set
    private var capture: VoiceCapture?=null
    private var generation=0L
    private var anchor: VoiceAnchor?=null
    private var commandAnchor: VoiceAnchor?=null
    private var inserted=""
    private var closed=false
    val active get()=state.phase in setOf(VoicePhase.RECORDING,VoicePhase.PROCESSING,VoicePhase.CANCELLING)
    fun prepare() {
        if(state.phase!=VoicePhase.UNREADY) return
        state=state.copy(phase=VoicePhase.PREPARING,message="正在准备本机模型…")
        worker.execute {
            val result=runCatching {
                val rows=vocabulary { it.rows() }
                VoiceText.validate(rows);engine.prepare();rows
            }
            main.post { if(!closed) state=if(result.isSuccess) state.copy(phase=VoicePhase.IDLE,message="按住说话，松手处理；最长90秒",mappings=result.getOrThrow())
                else state.copy(phase=VoicePhase.UNREADY,message="本机模型无法加载，文字输入仍可用。可重试准备。") }
        }
    }
    fun settingsUnavailable() { state=state.copy(message="无法打开系统设置，请从设备设置检查麦克风权限。") }
    fun permissionDenied() { cancel("未获麦克风权限，仍可输入文字；授权后请重新按住说话。") }
    fun permissionGranted() { state=state.copy(message="麦克风已授权，请重新按住说话。") }
    fun enterCommands(notes: NotesModel) {
        if(state.phase!=VoicePhase.IDLE) return
        val ticket=notes.voiceAnchor() ?: return
        generation++;commandAnchor=ticket
        state=state.copy(commandMode=true,command=null,message="命令模式：仅识别新建笔记、保存当前草稿、取消本次输入；识别后仍需确认。")
    }
    fun confirmCommand(notes: NotesModel,expected: VoiceCommandPreview) {
        val permitted=app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(!state.commandMode || state.command!==expected || expected.session!=generation || !notes.matchesVoice(expected.anchor) || !permitted) {
            if(state.command===expected) cancel(if(permitted) "编辑或会话已变化，命令已失效；已有文字保留。" else "麦克风权限不可用，命令已丢弃；已有文字保留。")
            return
        }
        val token=++generation;commandAnchor=null
        state=state.copy(command=null,commandMode=false,message="正在执行已确认命令…")
        notes.confirmVoiceCommand(expected.anchor,expected.intent) { ok ->
            if(!closed && token==generation) state=state.copy(message=if(!ok) "命令未完成，当前编辑仍保留，请重试。" else when(expected.intent) {
                VoiceCommand.NEW -> "原草稿已保留，已新建空白笔记。"
                VoiceCommand.SAVE -> "已按确认保存笔记。"
                VoiceCommand.CANCEL -> "本次输入已取消，既有文字和草稿保留。"
            })
        }
    }
    fun start(notes: NotesModel,cursor: Int?) {
        if(state.phase!=VoicePhase.IDLE) return
        val ticket=notes.voiceAnchor(cursor) ?: return
        val token=++generation;val recording=createCapture(app);capture=recording;val commands=state.commandMode
        if(commands) commandAnchor=ticket else { anchor=ticket;inserted="" }

        state=state.copy(phase=VoicePhase.RECORDING,message=if(commands) "命令录音中；松手识别，不插入正文。" else "正在录音；松手处理，或点取消",command=null,suggestions=if(commands) state.suggestions else emptyList())
        worker.execute {
            val result=runCatching { (testDecoder?.invoke() ?: engine.decoder()).use { decoder -> recording.run(decoder) {
                main.post { if(!closed && token==generation && capture===recording && state.phase==VoicePhase.RECORDING && !recording.isCancelled) state=state.copy(phase=VoicePhase.PROCESSING,message="正在处理本次语音…") }
            } } }
            main.post {
                if(closed) return@post
                if(capture===recording) capture=null
                if(token!=generation || recording.isCancelled) { if(capture==null) state=state.copy(phase=VoicePhase.IDLE);return@post }
                val text=result.getOrNull().orEmpty()
                if(commands) {
                    val intent=VoiceCommand.parse(text)
                    val permitted=app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED
                    val current=state.commandMode && commandAnchor==ticket && notes.matchesVoice(ticket) && permitted
                    if(!permitted) commandAnchor=null
                    val available=current && result.isSuccess && intent!=null && !(intent==VoiceCommand.SAVE && notes.state.editor!!.note.body.isBlank())
                    state=state.copy(phase=VoicePhase.IDLE,commandMode=state.commandMode && permitted,command=if(available) VoiceCommandPreview(intent!!,text,ticket,token) else null,message=when {
                        !permitted -> "麦克风权限不可用，命令已丢弃；没有执行操作。"
                        !current -> "编辑或会话已变化，命令已丢弃；已有文字保留。"
                        result.isFailure -> "命令识别失败，请检查麦克风权限后重试；没有执行操作。"
                        text.isBlank() -> "未识别到有效命令，没有执行操作。"
                        intent==null -> "未匹配完整口令：${text.take(128)}。没有执行操作。"
                        !available -> "识别为保存当前草稿，但正文为空，没有保存。"
                        else -> "识别为：${intent.phrase}。请核对并确认；尚未执行。"
                    })
                    return@post
                }
                val saved=if(result.isSuccess && text.isNotBlank()) notes.insertVoice(ticket,text) else null
                anchor=saved;inserted=if(saved!=null) text else ""
                state=state.copy(phase=VoicePhase.IDLE,suggestions=if(saved!=null) VoiceText.suggestions(text,state.mappings) else emptyList(),message=when {
                    result.isFailure -> when((result.exceptionOrNull() as? VoiceProblem)?.code) {
                        "permission" -> "麦克风权限不可用，本次结果已丢弃。"
                        "overload" -> "设备处理跟不上录音，本次未插入。请重试较短语音。"
                        else -> "本次语音处理失败，已有文字保留。请重试。"
                    }
                    text.isBlank() -> "未识别到有效语音，已有文字保留。请重试。"
                    saved==null -> "笔记或编辑版本已改变，本次迟到结果已丢弃。"
                    else -> "转写已插入草稿，可继续修改；尚未正式保存。"
                })
            }
        }
    }
    fun release() {
        if(state.phase!=VoicePhase.RECORDING) return
        capture?.release();state=state.copy(phase=VoicePhase.PROCESSING,message="正在处理本次语音…")
    }
    fun cancel(message: String="本次语音已取消，已有文字保留。") {
        val commands=state.commandMode
        generation++;commandAnchor=null
        if(!commands) { anchor=null;inserted="" }
        if(active) capture?.cancel()
        state=state.copy(phase=if(active) VoicePhase.CANCELLING else state.phase,commandMode=false,command=null,message=message,
            suggestions=if(commands) state.suggestions else emptyList())
    }
    fun checkEditor(notes: NotesModel) {
        commandAnchor?.let { if(!notes.matchesVoice(it)) cancel("编辑位置或版本已改变，命令已失效；已有文字保留。") }
        val ticket=anchor ?: return
        if(!notes.matchesVoice(ticket)) { anchor=null;inserted="";state=state.copy(suggestions=emptyList())
            if(active && !state.commandMode) cancel("编辑位置或版本已改变，本次结果已丢弃。") }
    }
    fun correct(notes: NotesModel,row: Correction) {
        val ticket=anchor ?: return
        if(row !in state.suggestions) return
        val fixed=inserted.replace(row.from,row.to)
        val next=notes.correctVoice(ticket,inserted,fixed)
        if(next==null) { anchor=null;state=state.copy(suggestions=emptyList(),message="文字已改变，纠错建议已过期。");return }
        anchor=next;inserted=fixed
        state=state.copy(suggestions=VoiceText.suggestions(fixed,state.mappings),message="已按确认修改本次转写，可继续手工编辑。")
    }
    fun saveMappings(rows: List<Correction>) {
        if(active) return
        try { VoiceText.validate(rows) } catch(_: Exception) { state=state.copy(message="词表未保存：最多200项，每项1–80字，不能相同、重复或含换行。");return }
        val expected=state.mappings
        worker.execute {
            val saved=runCatching { vocabulary { it.replace(expected,rows) };true }.getOrDefault(false)
            main.post { if(!closed) state=if(saved) state.copy(mappings=rows,suggestions=VoiceText.suggestions(inserted,rows),message="本机纠错词表已保存。仅提供确认建议，不是模型热词。") else state.copy(message="词表未保存，请重试。") }
        }
    }
    override fun onCleared() {
        closed=true;generation++;capture?.cancel();anchor=null;commandAnchor=null;inserted=""
        worker.execute { engine.close() };worker.shutdown()
    }
}
