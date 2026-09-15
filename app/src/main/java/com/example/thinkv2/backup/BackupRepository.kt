package com.example.thinkv2.backup

import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.*
import java.util.Locale
import java.util.UUID

enum class ConflictPolicy { COPY, SKIP }
enum class ImportAction { NEW, SAME, COPY, SKIP }
data class CategoryMerge(val source: BackupCategory,val target: BackupCategory?,val action: ImportAction)
data class RecordBundle(val note: BackupNote?,val draft: BackupDraft?,val reminder: BackupReminder?,val origin: BackupOrigin?)
data class RecordMerge(val sourceId: String,val targetId: String?,val source: RecordBundle,val action: ImportAction,val reason: String) {
    val visible get()=source.note!=null || source.draft?.active==true
    val label get()=source.note?.note?.title ?: source.draft?.editing?.note?.title.orEmpty().ifBlank { "未命名草稿" }
}
data class BackupPreview(val candidate: BackupCandidate,val token: String,val policy: ConflictPolicy,
    val categories: List<CategoryMerge>,val records: List<RecordMerge>,val alreadyImported: Boolean=false)
data class BackupApplied(val importedIds: List<String>,val copied: Int,val skipped: Int,val alreadyImported: Boolean=false)

class BackupRepository(private val db: Sql) {
    companion object {
        fun migrate(db: Sql) {
            db.execute("CREATE TABLE backup_imports (export_id TEXT PRIMARY KEY,payload_hash TEXT NOT NULL,imported_at INTEGER NOT NULL)")
            db.execute("CREATE TABLE backup_origins (record_id TEXT PRIMARY KEY,export_id TEXT NOT NULL,source_id TEXT NOT NULL)")
        }
    }
    private val fields="id,body,title,manual,category,created,updated,revision,category_id,category_source"
    private fun note(row: List<String>)=Note(row[0],row[1],row[2],row[3]=="1",row[4],row[5].toLong(),row[6].toLong(),row[7].toLong(),row[8],row[9])
    private fun values(n: Note)=listOf(n.id,n.body,n.title,if(n.manualTitle) "1" else "0",n.category,n.created.toString(),n.updated.toString(),n.revision.toString(),n.categoryId,n.categorySource)
    private fun snapshot(): BackupData {
        // Reject oversized local snapshots before loading large text columns into the app heap.
        for(table in listOf("notes","drafts","categories","reminders","backup_origins"))
            requireBackup(db.query("SELECT count(*) FROM $table").single().single().toLong()<=BackupLimits.RECORDS,"count_limit")
        val textBytes=listOf("notes","drafts").sumOf { table -> db.query("SELECT coalesce(sum(length(CAST(body AS BLOB))+length(CAST(title AS BLOB))+length(CAST(category AS BLOB))),0) FROM $table").single().single().toLong() }
        requireBackup(textBytes<=BackupLimits.PAYLOAD,"size_limit")
        return BackupData(db.query("SELECT $fields,deleted_at FROM notes ORDER BY id").map { BackupNote(note(it),it[10].toLong()) },
            db.query("SELECT $fields,base_revision,active FROM drafts ORDER BY id").map { BackupDraft(Editing(note(it),it[10].toLong()),it[11]=="1") },
            db.query("SELECT id,name,revision,active FROM categories ORDER BY id").map { BackupCategory(Category(it[0],it[1],it[2].toLong()),it[3]=="1") },
            db.query("SELECT id,note_id,revision,kind,start_day,hour,minute,weekdays,requested FROM reminders ORDER BY id").map {
                BackupReminder(it[0],it[1],it[2].toLong(),ReminderRule(Repeat.valueOf(it[3]),LocalDay.parse(it[4]),it[5].toInt(),it[6].toInt(),it[7].toInt()),it[8]=="1") },
            db.query("SELECT record_id,export_id,source_id FROM backup_origins ORDER BY record_id").map { BackupOrigin(it[0],it[1],it[2]) })
    }
    fun export(): BackupExport=transaction { BackupCodec.prepare(snapshot()) }
    internal fun data(): BackupData=transaction { snapshot() }
    private fun fingerprint(local: BackupData): String {
        val content=BackupCodec.sha(BackupCodec.payload(local))
        val reminders=BackupCodec.sha(StrictJson.encode(db.query("SELECT * FROM reminders ORDER BY id")))
        return "$content:$reminders"
    }
    private fun receipt(candidate: BackupCandidate): Boolean {
        val hash=db.query("SELECT payload_hash FROM backup_imports WHERE export_id=?",listOf(candidate.exportId)).firstOrNull()?.first() ?: return false
        requireBackup(hash==candidate.hash,"export_id_reused");return true
    }
    fun preview(candidate: BackupCandidate,policy: ConflictPolicy=ConflictPolicy.COPY): BackupPreview=transaction {
        BackupCodec.validate(candidate.data)
        if(receipt(candidate)) return@transaction BackupPreview(candidate,"",policy,emptyList(),emptyList(),true)
        val local=snapshot();val token=fingerprint(local)
        val oldCategories=local.categories.associateBy { it.category.id };val usedCategoryIds=(oldCategories.keys+candidate.data.categories.map { it.category.id }).toMutableSet()
        val usedNames=local.categories.filter { it.active }.map { it.category.name }.toMutableSet()
        val categoryMerge=candidate.data.categories.map { source ->
            val old=oldCategories[source.category.id]
            when {
                old==source -> CategoryMerge(source,old,ImportAction.SAME)
                old!=null || (source.active && source.category.name in usedNames) -> {
                    if(policy==ConflictPolicy.SKIP) CategoryMerge(source,null,ImportAction.SKIP)
                    else {
                        val id=if(old!=null) fresh(usedCategoryIds) else source.category.id.also { usedCategoryIds+=it }
                        var name=source.category.name
                        if(source.active && name in usedNames) name=copyName(name,usedNames)
                        if(source.active) usedNames+=name
                        CategoryMerge(source,source.copy(category=source.category.copy(id=id,name=name)),ImportAction.COPY)
                    }
                }
                else -> { usedCategoryIds+=source.category.id;if(source.active) usedNames+=source.category.name;CategoryMerge(source,source,ImportAction.NEW) }
            }
        }
        val categoryMap=categoryMerge.associateBy { it.source.category.id }
        val original=bundles(candidate.data);val existing=bundles(local);val usedRecordIds=(existing.keys+original.keys).toMutableSet()
        val records=original.map { (id,source) ->
            val refs=listOfNotNull(source.note?.note?.categoryId,source.draft?.editing?.note?.categoryId).filter { it.isNotEmpty() }
            val blocked=refs.any { categoryMap[it]?.target==null }
            val remapped=refs.any { categoryMap[it]?.target!=categoryMap[it]?.source }
            when {
                blocked -> RecordMerge(id,null,source,ImportAction.SKIP,"分类冲突已跳过，依赖该分类的记录也跳过")
                existing[id]==source && !remapped -> RecordMerge(id,id,source,ImportAction.SAME,"全部内容相同")
                existing.containsKey(id) -> if(policy==ConflictPolicy.SKIP) RecordMerge(id,null,source,ImportAction.SKIP,"本机记录或草稿、分类、提醒不同")
                    else RecordMerge(id,fresh(usedRecordIds),source,ImportAction.COPY,"保留本机，备份另存副本")
                else -> { usedRecordIds+=id;RecordMerge(id,id,source,ImportAction.NEW,"新增记录") }
            }
        }
        BackupPreview(candidate,token,policy,categoryMerge,records)
    }
    fun apply(preview: BackupPreview,now: Long=System.currentTimeMillis()): BackupApplied=transaction {
        val candidate=preview.candidate
        if(receipt(candidate)) return@transaction BackupApplied(emptyList(),0,0,true)
        requireBackup(!preview.alreadyImported && fingerprint(snapshot())==preview.token,"stale_preview")
        val categories=preview.categories.associate { it.source.category.id to it.target }
        for(c in preview.categories.filter { it.action in setOf(ImportAction.NEW,ImportAction.COPY) }) {
            val n=c.target!!;db.execute("INSERT INTO categories VALUES (?,?,?,?)",listOf(n.category.id,n.category.name,n.category.revision.toString(),if(n.active) "1" else "0"))
        }
        val occupiedReminders=db.query("SELECT id FROM reminders").map { it[0] }.toMutableSet()
        val usedReminders=(occupiedReminders+candidate.data.reminders.map { it.id }).toMutableSet()
        val imported=mutableListOf<String>()
        for(record in preview.records.filter { it.action in setOf(ImportAction.NEW,ImportAction.COPY) }) {
            val id=record.targetId!!;val source=record.source
            fun mapped(n: Note): Note {
                val c=if(n.categoryId.isEmpty()) null else categories[n.categoryId] ?: throw BackupFailure("invalid_mapping")
                return n.copy(id=id,categoryId=c?.category?.id.orEmpty(),category=c?.category?.name.orEmpty())
            }
            source.note?.let { val n=mapped(it.note)
                db.execute("INSERT INTO notes ($fields,title_fold,body_fold,deleted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    values(n)+listOf(n.title.lowercase(Locale.ROOT),n.body.lowercase(Locale.ROOT),it.deletedAt.toString())) }
            source.draft?.let { val n=mapped(it.editing.note)
                db.execute("INSERT INTO drafts ($fields,base_revision,active) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    values(n)+listOf(it.editing.baseRevision.toString(),if(it.active) "1" else "0")) }
            source.reminder?.let { r ->
                val reminderId=if(r.id in occupiedReminders) fresh(usedReminders) else r.id.also { occupiedReminders+=it }
                db.execute("INSERT INTO reminders (id,note_id,revision,kind,start_day,hour,minute,weekdays,requested,status) VALUES (?,?,?,?,?,?,?,?,0,'DISABLED')",
                    listOf(reminderId,id,r.revision.toString(),r.rule.repeat.name,r.rule.start.toString(),r.rule.hour.toString(),r.rule.minute.toString(),r.rule.weekdays.toString()))
            }
            val origin=if(record.action==ImportAction.COPY) BackupOrigin(id,candidate.exportId,record.sourceId) else source.origin?.copy(recordId=id)
            if(origin!=null) db.execute("INSERT INTO backup_origins VALUES (?,?,?)",listOf(origin.recordId,origin.exportId,origin.sourceId))
            imported+=id
        }
        db.execute("INSERT INTO backup_imports VALUES (?,?,?)",listOf(candidate.exportId,candidate.hash,now.toString()))
        BackupApplied(imported,preview.records.count { it.action==ImportAction.COPY },preview.records.count { it.action==ImportAction.SKIP })
    }
    private fun bundles(d: BackupData): Map<String,RecordBundle> {
        val notes=d.notes.associateBy { it.note.id };val drafts=d.drafts.associateBy { it.editing.note.id }
        val reminders=d.reminders.associateBy { it.noteId };val origins=d.origins.associateBy { it.recordId }
        return (notes.keys+drafts.keys).sorted().associateWith { RecordBundle(notes[it],drafts[it],reminders[it],origins[it]) }
    }
    private fun fresh(used: MutableSet<String>): String { var id: String;do { id=UUID.randomUUID().toString() } while(!used.add(id));return id }
    private fun copyName(base: String,used: Set<String>): String {
        val prefix=base.substring(0,base.offsetByCodePoints(0,minOf(50,base.codePointCount(0,base.length))));var n=1;var name: String
        do { name="$prefix · 恢复副本 $n";n++ } while(name in used)
        return name
    }
    private fun <T> transaction(block: ()->T): T {
        db.begin();try { val result=block();db.commit();return result } catch(e: Exception) { db.rollback();throw e }
    }
}
