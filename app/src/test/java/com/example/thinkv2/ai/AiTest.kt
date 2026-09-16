package com.example.thinkv2.ai

import com.example.thinkv2.notes.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class AiTest {
    @get:Rule val temp=TemporaryFolder()
    private fun config(endpoint: String="https://example.invalid/v1/chat/completions")=AiConfig(endpoint,"synthetic-model",UUID.randomUUID().toString(),true)
    private val category=Category("synthetic-category","生活",0)
    private fun envelope(content: String,extra: Map<String,Any?> = emptyMap())=aiJson(mapOf("choices" to listOf(mapOf("finish_reason" to "stop","message" to (mapOf("role" to "assistant","content" to content)+extra)))))
    private fun reject(code: String?=null,block: ()->Unit) { try { block();fail("must fail") } catch(e: AiFailure) { if(code!=null) assertEquals(code,e.code) } }
    @Test fun endpointAndHeaderBoundariesRejectUnconsentedDestinations() {
        for(s in listOf("http://example.invalid/v1/chat/completions","https://secret@example.invalid/v1/chat/completions","https://example.invalid/v1/chat/completions?key=x","https://example.invalid/v1/chat/completions#x","https://example.invalid/../chat/completions","https://example.invalid/v1/%63hat/completions","https://example.invalid:0/chat/completions")) reject("configuration") { config(s) }
        reject("configuration") { AiConfig("https://example.invalid/chat/completions","model","bad\r\nheader") }
        assertEquals("example.invalid",config().destination);assertFalse(config().toString().contains("synthetic-model"))
    }
    @Test fun outgoingPayloadOnlyContainsExplicitTextAndChosenCandidates() {
        val c=config();val raw=AiProtocol.payload(c,"仅这段合成文字",listOf(category)).toString(Charsets.UTF_8)
        assertFalse(raw.contains(c.credential));assertFalse(raw.contains("recordId"));assertFalse(raw.contains("account_name"));assertFalse(raw.contains("calendar_imports"))
        val json=AiJson(raw.toByteArray()).parse() as Map<*,*>
        assertEquals(setOf("model","stream","store","max_completion_tokens","response_format","messages"),json.keys)
        val messages=json["messages"] as List<*>;val user=AiJson(((messages[1] as Map<*,*>)["content"] as String).toByteArray()).parse() as Map<*,*>
        assertEquals(setOf("text","categories"),user.keys);assertEquals("仅这段合成文字",user["text"])
        reject("input_limit") { AiProtocol.payload(c,"大".repeat(3000),emptyList()) }
    }
    @Test fun strictResponseAcceptsOnlyIndependentBoundedFields() {
        val p=AiProtocol.parse(envelope("""{"title":"合成标题","category_id":"synthetic-category","new_category":null}"""),listOf(category))
        assertEquals("合成标题",p.title);assertEquals(category.id,p.categoryId)
        for(s in listOf("""{"title":"x","category_id":null,"new_category":null,"body":"overwrite"}""","""{"title":"x","title":"y","category_id":null,"new_category":null}""","""{"title":"<script>bad</script>","category_id":null,"new_category":null}""","""{"title":null,"category_id":"unknown","new_category":null}""","""{"title":"x","category_id":"synthetic-category","new_category":"both"}""","""{"title":null,"category_id":null,"new_category":null}""")) reject { AiProtocol.parse(envelope(s),listOf(category)) }
        reject { AiProtocol.parse(envelope("{}",mapOf("tool_calls" to listOf(mapOf("function" to "delete")))),emptyList()) }
        reject { AiProtocol.parse("not-json".toByteArray(),emptyList()) }
        reject("response_limit") { AiProtocol.parse(ByteArray(65537),emptyList()) }
    }
    @Test fun parserRejectsInvalidUtf8SurrogatesDuplicateKeysDepthAndTrailingActions() {
        for(bytes in listOf(byteArrayOf(0xC3.toByte(),0x28),"{\"x\":1,\"x\":2}".toByteArray(),"\"\\uD800\"".toByteArray(),("[".repeat(10)+"0"+"]".repeat(10)).toByteArray(),"{} {}".toByteArray())) {
            try { AiJson(bytes).parse();fail() } catch(_: Exception) {}
        }
        assertNotNull(AiJson("{\"usage\":1.25}".toByteArray()).parse())
    }
    private fun acceptance(request: String,field: String,chosen: String,candidates: List<Category>,id: String?=null,create: Boolean=false)=AiAcceptance(request,"https://example.invalid/v1/chat/completions","synthetic-model","a".repeat(64),field,"original-model-proposal",chosen,candidates,id,create)
    private fun store(block: (NoteRepository,PythonSql)->Unit) { PythonSql(temp.newFile()).use { db ->val n=NoteRepository(db);n.initialize();block(n,db) } }
    @Test fun bothAcceptanceOrdersKeepBodyAndSeparateOriginFromManualFinalChoice()=store { n,db ->
        val c=n.createCategory("已有分类")
        for(titleFirst in listOf(true,false)) {
            var e=Editing(Note().bodyChanged("合成正文保留").titleChanged("原手工标题").categorized(c));n.persistDraft(e)
            val request=UUID.randomUUID().toString()
            val title=acceptance(request,"title","人工改过的AI标题",listOf(c));val cat=acceptance(request,"category",c.name,listOf(c),c.id)
            for(a in if(titleFirst) listOf(title,cat) else listOf(cat,title)) e=n.acceptAi(e,a)
            assertEquals("合成正文保留",e.note.body);assertEquals("人工改过的AI标题",e.note.title);assertTrue(e.note.manualTitle);assertEquals("manual",e.note.categorySource)
            assertEquals(2,db.query("SELECT * FROM ai_acceptances WHERE record_id=?",listOf(e.note.id)).size)
            assertEquals(listOf("original-model-proposal","人工改过的AI标题"),db.query("SELECT proposed,chosen FROM ai_acceptances WHERE record_id=? AND field='title'",listOf(e.note.id)).single())
            n.save(e);assertEquals("合成正文保留",n.find(e.note.id)!!.body)
        }
    }
    @Test fun manualEditBetweenAcceptancesAndObsoleteCategoriesRejectWithoutChanges()=store { n,db ->
        val c=n.createCategory("原分类");val e=Editing(Note().bodyChanged("正文"));n.persistDraft(e)
        val after=n.acceptAi(e,acceptance("request","title","已接受标题",listOf(c)))
        n.persistDraft(after.copy(note=after.note.titleChanged("人工再次改标题")))
        try { n.acceptAi(after,acceptance("request","category",c.name,listOf(c),c.id));fail() } catch(_: IllegalStateException) {}
        assertEquals("人工再次改标题",n.open(e.note.id)!!.note.title);assertEquals(1,db.query("SELECT * FROM ai_acceptances").size)
        n.renameCategory(c,"已改名")
        val fresh=n.open(e.note.id)!!
        try { n.acceptAi(fresh,acceptance("request2","category",c.name,listOf(c),c.id));fail() } catch(_: IllegalStateException) {}
        assertEquals(1,db.query("SELECT * FROM ai_acceptances").size)
    }
    @Test fun explicitNewCategoryAndProvenanceInsertFailureRollbackTogether()=store { n,db ->
        val e=Editing(Note().bodyChanged("原文"));n.persistDraft(e)
        val a=acceptance("request","category","确认新分类",emptyList(),create=true)
        val before=db.query("SELECT * FROM drafts")
        val failing=object: Sql by db { override fun execute(statement: String,args: List<String>) { if(statement.startsWith("INSERT INTO ai_acceptances")) error("synthetic_storage_failure");db.execute(statement,args) } }
        try { NoteRepository(failing).acceptAi(e,a);fail() } catch(_: IllegalStateException) {}
        assertTrue(n.categories().isEmpty());assertEquals(before,db.query("SELECT * FROM drafts"));assertTrue(db.query("SELECT * FROM ai_acceptances").isEmpty())
        val next=n.acceptAi(e,a);assertEquals("确认新分类",next.note.category);assertEquals(1,n.categories().size)
        try { n.acceptAi(next,a);fail() } catch(_: IllegalStateException) {};assertEquals(1,db.query("SELECT * FROM ai_acceptances").size)
    }
    @Test fun schemaFiveUpgradePreservesAllExistingRowsAndBackupIncludesAcceptedAiMetadata()=store { n,db ->
        val e=Editing(Note().bodyChanged("迁移正文"));n.save(e);n.persistDraft(n.open(e.note.id)!!.let { it.copy(note=it.note.bodyChanged("迁移草稿")) })
        val tables=listOf("notes","drafts","categories","reminders","calendar_imports","backup_imports","backup_origins")
        val before=tables.map { db.query("SELECT * FROM $it") };db.execute("DROP TABLE ai_acceptances");db.execute("DROP TABLE note_relations");db.execute("DROP TABLE correction_vocabulary");db.execute("PRAGMA user_version=5");n.initialize()
        assertEquals(before,tables.map { db.query("SELECT * FROM $it") });assertEquals("8",db.query("PRAGMA user_version").single().single())
        val accepted=n.acceptAi(n.open(e.note.id)!!,acceptance("migration","title","接受标题",emptyList()));n.save(accepted)
        val export=com.example.thinkv2.backup.BackupRepository(db).export()
        val payload=export.payload.toString(Charsets.UTF_8)
        val decoded=AiJson(export.payload).parse() as Map<*,*>
        assertEquals(setOf("notes","drafts","categories","reminders","origins","relations","vocabulary","calendar","ai","receipts"),decoded.keys)
        for(excluded in listOf("credential","unaccepted-response","rawAudio")) assertFalse(payload.contains(excluded))
        val serialized=java.io.ByteArrayOutputStream().also { com.example.thinkv2.backup.BackupCodec.write(export,it) }.toByteArray()
        val restored=com.example.thinkv2.backup.BackupCodec.read(serialized.inputStream()).data
        assertEquals("接受标题",restored.notes.single().note.title);assertEquals("迁移草稿",restored.notes.single().note.body);assertTrue(restored.notes.single().note.manualTitle)
        assertEquals(1,db.query("SELECT * FROM ai_acceptances").size);assertEquals(1,restored.ai.size)
    }
}
