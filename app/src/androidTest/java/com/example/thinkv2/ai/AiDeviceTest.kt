package com.example.thinkv2.ai

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.MainActivity
import com.example.thinkv2.notes.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*

/** Explicit synthetic localhost TLS fixture. Test trust only; production retains system trust + hostname verification. */
class AiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val endpoint="https://localhost:18443/v1/chat/completions"
    private fun notes()=MainActivity::class.java.getDeclaredField("notes").apply { isAccessible=true }.get(ui.activity) as NotesModel
    private fun ai()=MainActivity::class.java.getDeclaredField("ai").apply { isAccessible=true }.get(ui.activity) as AiModel
    private val tls by lazy {
        val certificate=instrumentation.context.assets.open("ai-synthetic-localhost.pem").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val keys=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null);setCertificateEntry("synthetic-localhost",certificate) }
        val managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        SSLContext.getInstance("TLS").apply { init(null,managers.trustManagers,null) }.socketFactory
    }
    private fun connection(url: URL)=(url.openConnection() as HttpsURLConnection).apply { sslSocketFactory=tls }
    private fun call()=AiCall(::connection)
    private fun fixture(path: String): JSONObject=connection(URL("https://localhost:18443/$path")).let { c ->try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() } }
    private fun initialize() {
        ui.waitUntil(10000) { ai().state.loaded && !notes().state.loading }
        ui.runOnIdle { ai().callFactory={ call() } };fixture("reset")
    }
    private fun waitAi()=ui.waitUntil(30000) { !ai().state.saving && !ai().state.sending }
    private fun waitNotes()=ui.waitUntil(10000) { !notes().state.busy }
    private fun configure(mode: String) {
        ui.runOnIdle { ai().saveConfiguration(endpoint,mode,UUID.randomUUID().toString()) };waitAi()
        assertTrue(ai().state.configured);assertFalse(ai().state.enabled)
        ui.runOnIdle { ai().enable(true) };waitAi();assertTrue(ai().state.enabled)
    }
    private fun seed() {
        ui.runOnIdle { notes().newNote();notes().body("合成必要文字\nPRIVATE_ACCOUNT_SENTINEL 不得发送的日历账户和来源信息");notes().title("本机人工标题");notes().createCategory("合成已有分类") };waitNotes()
    }
    private fun request() {
        ui.runOnIdle { ai().prepare(notes());ai().text("合成必要文字");ai().select(notes().state.categories.single().id,true);ai().consent(true);ai().send(notes()) }
    }
    private fun save(name: String,value: JSONObject) { context.getFileStreamPath("ai-$name.json").writeText(value.toString(2)) }
    private fun screenshot(name: String) { instrumentation.uiAutomation.takeScreenshot()?.let { b ->context.getFileStreamPath("ai-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() } }
    @Test fun actualUiConsentMinimalFieldsEditedTitleAndCategoryAcceptedSeparately() {
        initialize();assertFalse(ai().state.enabled);assertFalse(ai().state.configured)
        ui.onNodeWithTag("notes-list").performScrollToNode(hasText("AI建议设置"));ui.onNodeWithText("AI建议设置").performClick()
        ui.onNodeWithText("完整HTTPS端点").performScrollTo().performTextReplacement(endpoint)
        ui.onNodeWithText("模型名称").performScrollTo().performTextReplacement("synthetic-ok")
        ui.onNodeWithText("服务凭据（不显示已保存值）").performScrollTo().performTextReplacement(UUID.randomUUID().toString());Espresso.closeSoftKeyboard()
        ui.onNodeWithText("保存配置并关闭AI").performScrollTo().performClick();waitAi();assertFalse(ai().state.enabled)
        ui.onNodeWithText("确认启用AI").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithContentDescription("同意向已保存供应商请求建议").performScrollTo().performClick()
        ui.onNodeWithText("确认启用AI").performScrollTo().performClick();waitAi();assertTrue(ai().state.enabled)
        assertEquals(0,fixture("requests").getJSONArray("requests").length())
        ui.onNodeWithText("关闭AI面板").performClick();seed()
        ui.onNodeWithText("请求AI标题与分类建议").performScrollTo().performClick()
        assertEquals("",ai().state.text);assertTrue(ai().state.selected.isEmpty())
        ui.onNodeWithText("本次发送的必要文字").performScrollTo().performTextInput("合成必要文字")
        ui.onNodeWithContentDescription("发送候选分类 合成已有分类").performScrollTo().performClick()
        ui.onNodeWithText("发送一次建议请求").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithContentDescription("确认本次发送字段").performScrollTo().performClick();screenshot("consent")
        ui.onNodeWithText("发送一次建议请求").performScrollTo().performClick();waitAi();assertNotNull(ai().state.proposal)
        val request=fixture("requests").getJSONArray("requests").getJSONObject(0)
        assertTrue(request.getBoolean("authorization_present"));val raw=request.getJSONObject("body").toString();assertFalse(raw.contains("PRIVATE_ACCOUNT_SENTINEL"));assertFalse(raw.contains("recordId"))
        val data=JSONObject(request.getJSONObject("body").getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("合成必要文字",data.getString("text"));assertEquals(1,data.getJSONArray("categories").length())
        ui.onNodeWithText("最终标题选择").performScrollTo().performTextReplacement("人工修订AI标题");Espresso.closeSoftKeyboard()
        ui.onNodeWithText("接受此标题到草稿").performScrollTo().performClick();waitAi();assertTrue(ai().state.titleDone);assertFalse(ai().state.categoryDone)
        ui.onNodeWithText("接受已有分类到草稿").performScrollTo().performClick();waitAi();assertTrue(ai().state.categoryDone)
        assertEquals("人工修订AI标题",notes().state.editor!!.note.title);assertTrue(notes().state.editor!!.note.body.contains("PRIVATE_ACCOUNT_SENTINEL"));screenshot("accepted")
        AndroidSql(context).use { db ->assertEquals(2,db.query("SELECT * FROM ai_acceptances").size);assertEquals(listOf("合成AI标题","人工修订AI标题"),db.query("SELECT proposed,chosen FROM ai_acceptances WHERE field='title'").single()) }
        ui.onNodeWithText("关闭AI面板").performClick();ui.onNodeWithText("保存").performClick();waitNotes()
        save("ui",JSONObject().put("mockOnly",true).put("providerVerified",false).put("requests",1).put("explicitMinimalText",true).put("bothSuggestionsAcceptedSeparately",true).put("bodyPreserved",true).put("credentialCaptured",false))
    }
    @Test fun tlsRedirectOfflineErrorsTimeoutAndCancellationUseRealAndroidAdapter() {
        initialize();val results=JSONObject();val payloadText="合成网络边界文字"
        fun execute(mode: String,transport: AiCall=call(),address: String=endpoint): AiProposal {
            val c=AiConfig(address,mode,UUID.randomUUID().toString(),true)
            return AiProtocol.parse(transport.execute(c,AiProtocol.payload(c,payloadText,emptyList())),emptyList())
        }
        fun fails(mode: String,code: String,transport: AiCall=call(),address: String=endpoint) {
            try { execute(mode,transport,address);fail(mode) } catch(e: AiFailure) { assertEquals(code,e.code);results.put(mode,code) }
        }
        fails("system-trust-rejects-test-ca","network",AiCall())
        fails("wrong-hostname","network",call(),"https://127.0.0.1:18443/v1/chat/completions")
        fails("offline-port","network",call(),"https://localhost:18445/v1/chat/completions")
        for((mode,code) in mapOf("synthetic-401" to "credentials","synthetic-403" to "credentials","synthetic-429" to "rate_limit","synthetic-500" to "service","synthetic-malformed" to "invalid_response","synthetic-inject" to "invalid_response","synthetic-empty" to "empty_response","synthetic-large" to "response_limit","synthetic-redirect" to "redirect")) fails(mode,code)
        fails("synthetic-timeout","timeout")
        val active=call();val error=AtomicReference<String>();val thread=Thread { try { execute("synthetic-delay",active);error.set("unexpected_success") } catch(e: AiFailure) { error.set(e.code) } }
        thread.start();Thread.sleep(100);active.cancel();thread.join(5000);assertFalse(thread.isAlive);assertEquals("cancelled",error.get());results.put("cancel","cancelled")
        val requests=fixture("requests").getJSONArray("requests")
        for(i in 0 until requests.length()) assertEquals("/v1/chat/completions",requests.getJSONObject(i).getString("path"))
        assertTrue(AndroidSql(context).use { it.query("SELECT * FROM ai_acceptances").isEmpty() })
        save("network",results.put("mockOnly",true).put("providerVerified",false).put("redirectTrapRequests",0).put("notesMutated",false))
    }
    @Test fun staleManualEditsSwitchCancelDisableAndReverseAcceptancePreserveOwnership() {
        initialize();seed();configure("synthetic-delay");request()
        ui.runOnIdle { ai().send(notes());notes().title("请求期间人工标题") };waitAi();assertNull(ai().state.proposal);assertEquals("请求期间人工标题",notes().state.editor!!.note.title)
        assertEquals(1,fixture("requests").getJSONArray("requests").length())
        request();ui.runOnIdle { ai().cancel() };Thread.sleep(2300);assertNull(ai().state.proposal)
        request();ui.runOnIdle { ai().disable() };waitAi();Thread.sleep(2300);assertFalse(ai().state.enabled);assertNull(ai().state.proposal)
        configure("synthetic-ok");request();waitAi()
        val category=notes().state.categories.single();ui.runOnIdle { ai().accept(notes(),"category",category.name,category.id) };waitAi()
        assertTrue(ai().state.categoryDone);assertFalse(ai().state.titleDone)
        ui.runOnIdle { ai().accept(notes(),"title","第二顺序接受标题") };waitAi();assertTrue(ai().state.titleDone)
        request();waitAi();ui.runOnIdle { ai().accept(notes(),"title","首次接受") };waitAi()
        ui.runOnIdle { notes().title("两次之间人工编辑");ai().accept(notes(),"category",category.name,category.id) };waitAi()
        assertEquals("两次之间人工编辑",notes().state.editor!!.note.title);assertNull(ai().state.proposal)
        configure("synthetic-delay");request();ui.runOnIdle { notes().back() };waitNotes();ui.runOnIdle { notes().newNote();notes().body("另一个笔记") };waitAi()
        assertNull(ai().state.proposal);assertEquals("另一个笔记",notes().state.editor!!.note.body)
        save("stale",JSONObject().put("duplicatePrevented",true).put("manualEditInvalidated",true).put("cancelDisableDiscardedLate",true).put("categoryThenTitleAccepted",true).put("manualEditBetweenAcceptancesProtected",true).put("noteSwitchProtected",true))
    }
    @Test fun keystoreCiphertextClearAndNewSessionDefaultsOff() {
        initialize();val vault=AiVault(context);val key=UUID.randomUUID().toString();val c=AiConfig(endpoint,"synthetic-vault",key,true)
        vault.save(c);assertEquals(key,vault.load()!!.credential)
        val bytes=context.noBackupFilesDir.resolve("ai-settings.enc").readBytes();assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(key));assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(endpoint))
        lateinit var restarted: AiModel;ui.runOnIdle { restarted=AiModel(vault) };ui.waitUntil(10000) { restarted.state.loaded }
        assertTrue(restarted.state.configured);assertFalse(restarted.state.enabled);assertNull(restarted.state.proposal);assertEquals(0,fixture("requests").getJSONArray("requests").length())
        AndroidSql(context).use { db ->
            val export=com.example.thinkv2.backup.BackupRepository(db).export()
            val payload=export.payload.toString(Charsets.UTF_8)
            for(excluded in listOf(key,endpoint,"synthetic-vault","ai_acceptances","credential","ai-settings")) assertFalse(payload.contains(excluded))
            val bytes=java.io.ByteArrayOutputStream().also { com.example.thinkv2.backup.BackupCodec.write(export,it) }.toByteArray()
            assertEquals(export.exportId,com.example.thinkv2.backup.BackupCodec.read(bytes.inputStream()).exportId)
        }
        vault.clear();assertNull(vault.load());assertFalse(context.noBackupFilesDir.resolve("ai-settings.enc").exists())
        save("vault",JSONObject().put("encryptedOnly",true).put("androidKeystore",true).put("newSessionDisabled",true).put("clearRemovedCiphertext",true).put("secretInEvidence",false))
    }
    @Test fun systemBackAndCloseDuringDelayedAcceptanceDoNotStrandSavingState() {
        initialize();configure("synthetic-ok")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val db=AndroidSql(context,"ai-delayed-accept.db")
        val wrapped=object: Sql by db {
            override fun execute(statement: String,args: List<String>) {
                if(statement.startsWith("INSERT INTO ai_acceptances VALUES")) { entered.countDown();check(release.await(10,TimeUnit.SECONDS)) }
                db.execute(statement,args)
            }
        }
        lateinit var local: NotesModel;val a=ai()
        ui.runOnIdle { local=NotesModel({ NoteRepository(wrapped) },worker=Executors.newSingleThreadExecutor().asCoroutineDispatcher()) }
        try {
            ui.waitUntil(10000) { !local.state.loading }
            ui.runOnIdle { ui.activity.setContent { AiDialog(a,local) } }
            ui.runOnIdle { local.newNote();local.body("延迟存储合成正文");a.prepare(local);a.text("延迟存储合成正文");a.consent(true);a.send(local) }
            waitAi();assertNotNull(a.state.proposal)
            ui.onNodeWithText("接受此标题到草稿").performScrollTo().performClick()
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(a.state.saving)
            Espresso.pressBack()
            ui.runOnIdle { a.close();a.cancel();a.settings() }
            assertTrue(a.state.saving);assertEquals("request",a.state.screen)
            release.countDown();waitAi()
            assertFalse(a.state.saving);assertEquals("合成AI标题",local.state.editor!!.note.title)
            ui.runOnIdle { a.close();a.prepare(local) };assertEquals("request",a.state.screen)
            save("dismiss",JSONObject().put("systemBackProtected",true).put("closeCancelProtected",true).put("acceptanceCommitted",true).put("savingReleased",true).put("entryUsableAfter",true))
        } finally { release.countDown();ui.runOnIdle { a.close();local.shutdown() } }
    }

    @Test fun endpointModelChangesInvalidateConsentAndLateResponse() {
        initialize();seed();configure("synthetic-delay");request();Thread.sleep(150)
        ui.runOnIdle { ai().saveConfiguration(endpoint,"synthetic-ok","") };waitAi()
        assertFalse(ai().state.enabled);assertTrue(ai().state.message!!.contains("配置不可用"))
        val alternative="https://localhost:18443/alternate/chat/completions"
        ui.runOnIdle { ai().saveConfiguration(alternative,"synthetic-ok",UUID.randomUUID().toString()) };waitAi()
        assertFalse(ai().state.enabled);assertEquals(alternative,ai().state.endpoint)
        ui.runOnIdle { ai().enable(false);ai().prepare(notes());ai().text("新配置合成文字");ai().consent(true);ai().send(notes()) }
        Thread.sleep(2300);assertFalse(ai().state.enabled);assertNull(ai().state.proposal)
        val calls=fixture("requests").getJSONArray("requests");assertEquals(1,calls.length());assertEquals("synthetic-delay",calls.getJSONObject(0).getJSONObject("body").getString("model"))
        assertEquals("本机人工标题",notes().state.editor!!.note.title)
        save("configuration",JSONObject().put("newCredentialRequiredForChangedModel",true).put("endpointChangeDisabled",true).put("newConsentRequired",true).put("oldResponseDiscarded",true).put("newDestinationRequests",0))
    }

    @Test fun continuouslyDrippingResponseStopsAtOverallDeadline() {
        initialize();val error=AtomicReference<String>();val elapsed=java.util.concurrent.atomic.AtomicLong()
        val c=AiConfig(endpoint,"synthetic-drip",UUID.randomUUID().toString(),true)
        val started=System.nanoTime()
        val thread=Thread {
            try { val raw=call().execute(c,AiProtocol.payload(c,"合成总时限测试",emptyList()));AiProtocol.parse(raw,emptyList());error.set("unexpected_success") }
            catch(e: AiFailure) { error.set(e.code) }
            finally { elapsed.set((System.nanoTime()-started)/1000000) }
        }
        thread.start();thread.join(27000)
        assertFalse("request worker must terminate",thread.isAlive);assertEquals("timeout",error.get())
        assertTrue(elapsed.get()>=19000 && elapsed.get()<27000)
        AndroidSql(context).use { db ->assertTrue(db.query("SELECT * FROM notes").isEmpty());assertTrue(db.query("SELECT * FROM ai_acceptances").isEmpty()) }
        save("deadline",JSONObject().put("model","synthetic-drip").put("interByteDelayMs",500).put("configuredDeadlineMs",AiProtocol.TIMEOUT_MS)
            .put("elapsedMs",elapsed.get()).put("workerTerminated",true).put("suggestionApplied",false))
    }

    @Test fun rejectedSuggestionsAndExplicitEditedNewCategoryAreIndependent() {
        initialize();seed();configure("synthetic-new");request();waitAi()
        assertEquals("合成AI新分类",ai().state.proposal!!.newCategory)
        assertEquals(1,notes().state.categories.size);assertEquals("本机人工标题",notes().state.editor!!.note.title)
        ui.onNodeWithText("拒绝标题建议").performScrollTo().performClick()
        ui.onNodeWithText("拒绝分类建议").performScrollTo().performClick()
        AndroidSql(context).use { db ->assertTrue(db.query("SELECT * FROM ai_acceptances").isEmpty());assertEquals(1,db.query("SELECT * FROM categories").size) }
        request();waitAi()
        ui.onNodeWithText("待确认新分类名称").performScrollTo().performTextReplacement("用户确认的新分类");Espresso.closeSoftKeyboard()
        ui.onNodeWithText("确认创建新分类并应用到草稿").performScrollTo().performClick();waitAi()
        assertEquals("用户确认的新分类",notes().state.editor!!.note.category);assertEquals("本机人工标题",notes().state.editor!!.note.title)
        AndroidSql(context).use { db ->assertEquals(1,db.query("SELECT * FROM ai_acceptances").size);assertEquals(2,db.query("SELECT * FROM categories").size) }
        save("new-category",JSONObject().put("noAutomaticCategory",true).put("rejectBothNoWrites",true).put("editedCategoryExplicitlyConfirmed",true).put("manualTitlePreserved",true))
    }

}
