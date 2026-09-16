package com.example.thinkv2.ai

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.example.thinkv2.notes.*
import kotlinx.coroutines.*
import java.util.UUID

data class AiState(val loaded: Boolean=false,val configured: Boolean=false,val enabled: Boolean=false,val endpoint: String="",val model: String="",
    val screen: String="",val text: String="",val candidates: List<Category> = emptyList(),val selected: Set<String> = emptySet(),val consent: Boolean=false,
    val sending: Boolean=false,val saving: Boolean=false,val proposal: AiProposal?=null,val titleDone: Boolean=false,val categoryDone: Boolean=false,val message: String?=null)
class AiModel(private val vault: AiVault,internal var callFactory: ()->AiCall = { AiCall() }): ViewModel() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var config: AiConfig?=null
    private var generation=0L
    private var call: AiCall?=null
    private var anchor: AiAnchor?=null
    private var requestId="";private var inputHash="";private var sentCandidates=emptyList<Category>()
    var state by mutableStateOf(AiState());private set
    init { scope.launch { try {
        // Restart deliberately requires fresh enable consent; no automatic resumed request.
        config=withContext(Dispatchers.IO) { vault.load()?.enabled(false) }
        state=state.copy(loaded=true,configured=config!=null,endpoint=config?.endpoint.orEmpty(),model=config?.model.orEmpty())
    } catch(_: Exception) { state=state.copy(loaded=true,message=message("credential_storage")) } } }
    private fun invalidate(text: String?=null) {
        generation++;call?.cancel();call=null;anchor=null
        state=state.copy(sending=false,proposal=null,consent=false,titleDone=false,categoryDone=false,message=text)
    }
    fun settings() { if(!state.loaded || state.saving) return;invalidate();state=state.copy(screen="settings",text="",selected=emptySet(),candidates=emptyList()) }
    fun saveConfiguration(endpoint: String,model: String,credential: String) {
        if(state.saving) return
        invalidate();val previous=config;config=previous?.enabled(false);state=state.copy(enabled=false)
        val next=try { AiConfig(endpoint.trim(),model.trim(),credential.ifEmpty {
            if(previous?.endpoint==endpoint.trim() && previous.model==model.trim()) previous.credential else ""
        }) } catch(_: Exception) { state=state.copy(message=message("configuration"));return }
        state=state.copy(saving=true)
        scope.launch { try { withContext(Dispatchers.IO) { vault.save(next) };config=next;state=state.copy(saving=false,configured=true,endpoint=next.endpoint,model=next.model,message="配置已加密保存，AI仍关闭；启用需要重新确认。") }
            catch(_: Exception) { state=state.copy(saving=false,message=message("credential_storage")) } }
    }
    fun enable(confirmed: Boolean) {
        if(!confirmed || state.saving) return
        val next=config?.enabled(true) ?: return
        invalidate();state=state.copy(saving=true)
        scope.launch { try { withContext(Dispatchers.IO) { vault.save(next) };config=next;state=state.copy(saving=false,enabled=true,message="已启用手动请求；每次仍需核对发送字段并确认。真实服务尚未验证。") }
            catch(_: Exception) { state=state.copy(saving=false,message=message("credential_storage")) } }
    }
    fun disable(clear: Boolean=false) {
        if(state.saving) return
        invalidate("已停止并丢弃建议；已经发送的文字无法撤回。");config=config?.enabled(false)
        state=state.copy(enabled=false,saving=true)
        scope.launch { try {
            withContext(Dispatchers.IO) { if(clear) vault.clear() else config?.let(vault::save) }
            if(clear) { config=null;state=state.copy(configured=false,endpoint="",model="") }
            state=state.copy(saving=false)
        } catch(_: Exception) { state=state.copy(saving=false,message=message("credential_storage")) } }
    }
    fun prepare(notes: NotesModel) {
        if(!state.loaded || state.saving) return
        invalidate();anchor=notes.aiAnchor() ?: return
        state=state.copy(screen="request",text="",candidates=notes.state.categories.take(50),selected=emptySet(),message="发送框默认空白。仅填写当前笔记中需要建议的文字；不要加入日历账户、来源ID等无关信息。")
    }
    fun text(value: String) { if(!state.sending && state.proposal==null) state=state.copy(text=value,consent=false) }
    fun select(id: String,value: Boolean) { if(!state.sending && state.proposal==null) state=state.copy(selected=if(value) state.selected+id else state.selected-id,consent=false) }
    fun consent(value: Boolean) { if(!state.sending) state=state.copy(consent=value) }
    fun preview(): String = try { val c=config ?: throw AiFailure("configuration");AiProtocol.payload(c,state.text,state.candidates.filter { it.id in state.selected }).toString(Charsets.UTF_8) }
        catch(_: Exception) { "请填写非空必要文字（最多8KiB），最多50个候选分类；完整请求最多32KiB。" }
    fun send(notes: NotesModel) {
        if(state.sending || state.saving || !state.consent || !state.enabled) return
        val c=config ?: return;val expected=anchor ?: return
        if(notes.aiAnchor()!=expected) { invalidate(message("stale"));return }
        val categories=state.candidates.filter { it.id in state.selected }
        val bytes=try { AiProtocol.payload(c,state.text,categories) } catch(_: Exception) { state=state.copy(message=message("input_limit"));return }
        val token=++generation;requestId=UUID.randomUUID().toString();inputHash=aiHash(bytes.toString(Charsets.UTF_8));sentCandidates=categories
        val active=callFactory();call=active;state=state.copy(sending=true,proposal=null,message="正在请求；取消只能丢弃结果，已发送文字无法撤回。")
        scope.launch {
            try {
                val proposal=withContext(Dispatchers.IO) { AiProtocol.parse(active.execute(c,bytes),categories) }
                if(token==generation) {
                    if(notes.aiAnchor()!=expected) invalidate(message("stale"))
                    else state=state.copy(sending=false,proposal=proposal,consent=false,titleDone=proposal.title==null,categoryDone=proposal.categoryId==null && proposal.newCategory==null,message="收到待确认建议。请核对内容；正文不会改变。")
                }
            } catch(e: Exception) { if(token==generation) state=state.copy(sending=false,consent=false,message=message((e as? AiFailure)?.code ?: "network")) }
            finally { if(token==generation) call=null;bytes.fill(0) }
        }
    }
    fun checkCurrent(notes: NotesModel) {
        if(anchor!=null && !notes.state.busy && notes.aiAnchor()!=anchor) invalidate(message("stale"))
    }
    fun accept(notes: NotesModel,field: String,chosen: String,categoryId: String?=null,create: Boolean=false) {
        val c=config ?: return;val expected=anchor ?: return;val p=state.proposal ?: return
        if(!state.enabled || state.saving || (field=="title" && state.titleDone) || (field=="category" && state.categoryDone)) return
        val proposed=if(field=="title") p.title ?: return else p.categoryId ?: p.newCategory ?: return
        val acceptance=AiAcceptance(requestId,c.endpoint,c.model,inputHash,field,proposed,chosen,sentCandidates,categoryId,create)
        val token=generation;state=state.copy(saving=true)
        notes.acceptAi(expected,acceptance) { ok ->
            if(token==generation) {
                state=state.copy(saving=false)
                if(ok) { anchor=notes.aiAnchor();state=state.copy(titleDone=state.titleDone || field=="title",categoryDone=state.categoryDone || field=="category",message="已按你的选择保存到本机草稿；可继续修改或正式保存。") }
                else invalidate(message("stale"))
            }
        }
    }
    fun reject(field: String) { state=state.copy(titleDone=state.titleDone || field=="title",categoryDone=state.categoryDone || field=="category",message="已拒绝该建议，笔记未修改。") }
    fun cancel() { if(state.saving) return;invalidate("已取消并丢弃建议；已经发送的文字无法撤回。") }
    fun close() { if(state.saving) return;invalidate();state=state.copy(screen="",text="",candidates=emptyList(),selected=emptySet()) }
    override fun onCleared() { call?.cancel();scope.cancel();config=null }
    private fun message(code: String)=when(code) {
        "configuration" -> "配置不可用：需完整HTTPS /chat/completions端点、模型和凭据；不接受查询参数、用户信息或重定向。更换端点/模型须重新输入凭据。"
        "credential_storage" -> "受保护配置无法读取或保存；AI不可用，请清除后重新配置。本地文字仍可用。"
        "stale" -> "笔记、版本或分类已变化，建议已失效；原有编辑保留，请重新请求。"
        "input_limit" -> "发送文字需1至8KiB，候选最多50个，完整请求最多32KiB；没有发送。"
        "timeout" -> "请求超时，未应用建议；已发送文字无法撤回，可稍后手动重试。"
        "cancelled" -> "请求已取消，未应用建议；已发送文字无法撤回。"
        "redirect" -> "服务要求重定向，已拒绝；没有向新地址发送凭据或文字。"
        "credentials" -> "服务拒绝凭据，AI当前不可用；请核对配置。"
        "rate_limit" -> "服务额度或频率受限（429），未应用建议。"
        "service" -> "服务暂不可用（5xx），未应用建议。"
        "response_limit" -> "响应超过64KiB，已拒绝；笔记未修改。"
        "empty_response","invalid_response" -> "响应为空或不符合受限建议结构，已拒绝；笔记未修改。"
        else -> "网络或服务不可用，未应用建议；本地保存和手工分类仍可用。"
    }
}
