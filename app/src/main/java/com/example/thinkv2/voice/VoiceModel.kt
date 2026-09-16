package com.example.thinkv2.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.thinkv2.notes.NotesModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

enum class VoicePhase { UNREADY, PREPARING, IDLE, RECORDING, PROCESSING, CANCELLING }
data class VoiceState(val phase: VoicePhase=VoicePhase.UNREADY,val message: String="离线语音尚未准备",
    val mappings: List<Correction> = emptyList(),val suggestions: List<Correction> = emptyList())

class VoiceModel(context: Context,private val createCapture: (Context)->VoiceCapture={ VoiceCapture({ AndroidMicrophone(it) }) }) : ViewModel() {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val engine=OfflineEngine(app)
    private val preferences=app.getSharedPreferences("voice-corrections",Context.MODE_PRIVATE)
    var state by mutableStateOf(VoiceState())
        private set
    private var capture: VoiceCapture?=null
    private var generation=0L
    private var anchor: VoiceAnchor?=null
    private var inserted=""
    private var closed=false
    val active get()=state.phase in setOf(VoicePhase.RECORDING,VoicePhase.PROCESSING,VoicePhase.CANCELLING)
    fun prepare() {
        if(state.phase!=VoicePhase.UNREADY) return
        state=state.copy(phase=VoicePhase.PREPARING,message="正在准备本机模型…")
        worker.execute {
            val result=runCatching {
                val array=JSONArray(preferences.getString("rows","[]"));val rows=(0 until array.length()).map { val r=array.getJSONObject(it);Correction(r.getString("from"),r.getString("to")) }
                VoiceText.validate(rows);engine.prepare();rows
            }
            main.post { if(!closed) state=if(result.isSuccess) state.copy(phase=VoicePhase.IDLE,message="按住说话，松手处理；最长90秒",mappings=result.getOrThrow())
                else state.copy(phase=VoicePhase.UNREADY,message="本机模型无法加载，文字输入仍可用。可重试准备。") }
        }
    }
    fun settingsUnavailable() { state=state.copy(message="无法打开系统设置，请从设备设置检查麦克风权限。") }
    fun permissionDenied() { state=state.copy(message="未获麦克风权限，仍可输入文字；授权后请重新按住说话。") }
    fun permissionGranted() { state=state.copy(message="麦克风已授权，请重新按住说话。") }
    fun start(notes: NotesModel,cursor: Int?) {
        if(state.phase!=VoicePhase.IDLE) return
        val ticket=notes.voiceAnchor(cursor) ?: return
        val token=++generation;val recording=createCapture(app);capture=recording;anchor=ticket;inserted=""
        state=state.copy(phase=VoicePhase.RECORDING,message="正在录音；松手处理，或点取消",suggestions=emptyList())
        worker.execute {
            val result=runCatching { engine.decoder().use { decoder -> recording.run(decoder) {
                main.post { if(!closed && token==generation && capture===recording && state.phase==VoicePhase.RECORDING && !recording.isCancelled) state=state.copy(phase=VoicePhase.PROCESSING,message="正在处理本次语音…") }
            } } }
            main.post {
                if(closed) return@post
                if(capture===recording) capture=null
                if(token!=generation || recording.isCancelled) { if(capture==null) state=state.copy(phase=VoicePhase.IDLE);return@post }
                val text=result.getOrNull().orEmpty()
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
        anchor=null;inserted=""
        if(!active) { state=state.copy(suggestions=emptyList());return }
        generation++;capture?.cancel()
        state=state.copy(phase=VoicePhase.CANCELLING,message=message,suggestions=emptyList())
    }
    fun checkEditor(notes: NotesModel) {
        val ticket=anchor ?: return
        if(!notes.matchesVoice(ticket)) cancel("编辑位置或版本已改变，本次结果已丢弃。")
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
        val json=JSONArray();rows.forEach { json.put(JSONObject().put("from",it.from).put("to",it.to)) }
        worker.execute {
            val saved=runCatching { preferences.edit().putString("rows",json.toString()).commit() }.getOrDefault(false)
            main.post { if(!closed) state=if(saved) state.copy(mappings=rows,suggestions=VoiceText.suggestions(inserted,rows),message="本机纠错词表已保存。仅提供确认建议，不是模型热词。") else state.copy(message="词表未保存，请重试。") }
        }
    }
    override fun onCleared() {
        closed=true;generation++;capture?.cancel();anchor=null;inserted=""
        worker.execute { engine.close() };worker.shutdown()
    }
}
