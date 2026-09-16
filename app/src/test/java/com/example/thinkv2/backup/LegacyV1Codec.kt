package com.example.thinkv2.backup

import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.*
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

// Frozen reader from accepted baseline 53fb5da; proves deployed v1 fails closed on v2.
object LegacyV1Codec {
    fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT,it.toInt() and 255) }
    private fun utc()=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.ROOT).apply { timeZone=TimeZone.getTimeZone("UTC");isLenient=false }
    fun prepare(data: BackupData,now: Long=System.currentTimeMillis(),id: String=UUID.randomUUID().toString()): BackupExport {
        validate(data);validId(id);validTime(now)
        val bytes=payload(data);return BackupExport(id,utc().format(Date(now)),bytes,sha(bytes))
    }
    fun write(export: BackupExport,target: OutputStream) {
        val writer=OutputStreamWriter(BoundedOutput(target,BackupLimits.FILE),Charsets.UTF_8)
        val fields=linkedMapOf<String,Any?>("format" to "thinkV2-backup","schemaVersion" to 1,"exportId" to export.exportId,
            "createdAtUTC" to export.createdAtUTC,"payloadBytes" to export.payload.size,"payloadSha256" to export.hash)
        writer.write('{'.code)
        fields.entries.forEachIndexed { i,e -> if(i>0) writer.write(','.code);StrictJson.write(e.key,writer);writer.write(':'.code);StrictJson.write(e.value,writer) }
        writer.write(",\"payload\":\"");StrictJson.base64(export.payload,writer);writer.write("\"}");writer.flush()
    }
    fun read(input: InputStream): BackupCandidate {
        try {
            val e=StrictJson(input,BackupLimits.FILE,true).parse().obj(setOf("format","schemaVersion","exportId","createdAtUTC","payloadBytes","payloadSha256","payload"))
            requireBackup(e.string("format")=="thinkV2-backup");requireBackup(e.long("schemaVersion")==1L,"unsupported_version")
            val id=e.string("exportId");validId(id);val created=e.string("createdAtUTC")
            requireBackup(created.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")))
            val parsed=try { utc().parse(created)!! } catch(_: Exception) { throw BackupFailure("invalid_time") }
            validTime(parsed.time);requireBackup(utc().format(parsed)==created,"invalid_time")
            val bytes=e["payload"] as? ByteArray ?: throw BackupFailure("invalid_payload")
            requireBackup(e.long("payloadBytes")==bytes.size.toLong(),"payload_length")
            val hash=e.string("payloadSha256");requireBackup(hash.matches(Regex("[0-9a-f]{64}")) && hash==sha(bytes),"payload_hash")
            val data=fromJson(StrictJson(ByteArrayInputStream(bytes),BackupLimits.PAYLOAD).parse());validate(data)
            return BackupCandidate(id,created,hash,data)
        } catch(_: java.nio.charset.CharacterCodingException) { throw BackupFailure("invalid_utf8") }
    }
    internal fun payload(data: BackupData)=StrictJson.encode(linkedMapOf(
        "notes" to data.notes.map { noteJson(it.note)+mapOf("deletedAt" to it.deletedAt) },
        "drafts" to data.drafts.map { mapOf("record" to noteJson(it.editing.note),"baseRevision" to it.editing.baseRevision,"active" to it.active) },
        "categories" to data.categories.map { mapOf("id" to it.category.id,"name" to it.category.name,"revision" to it.category.revision,"active" to it.active) },
        "reminders" to data.reminders.map { mapOf("id" to it.id,"noteId" to it.noteId,"revision" to it.revision,"repeat" to it.rule.repeat.name,"start" to it.rule.start.toString(),"hour" to it.rule.hour,"minute" to it.rule.minute,"weekdays" to it.rule.weekdays,"enabled" to it.enabled) },
        "origins" to data.origins.map { mapOf("recordId" to it.recordId,"exportId" to it.exportId,"sourceId" to it.sourceId) },"relations" to emptyList<Any>()))
    private val noteKeys=setOf("id","body","title","titleMode","categoryId","categoryName","categorySource","created","updated","revision")
    private fun noteJson(n: Note)=linkedMapOf<String,Any?>("id" to n.id,"body" to n.body,"title" to n.title,"titleMode" to if(n.manualTitle) "MANUAL" else "AUTO", "categoryId" to n.categoryId,"categoryName" to n.category,"categorySource" to n.categorySource,"created" to n.created,"updated" to n.updated,"revision" to n.revision)
    private fun readNote(e: Map<String,Any?>): Note {
        val mode=e.string("titleMode");requireBackup(mode in setOf("AUTO","MANUAL"))
        return Note(e.string("id"),e.string("body"),e.string("title"),mode=="MANUAL",e.string("categoryName"),e.long("created"),e.long("updated"),e.long("revision"),e.string("categoryId"),e.string("categorySource"))
    }
    private fun fromJson(value: Any?): BackupData {
        val p=value.obj(setOf("notes","drafts","categories","reminders","origins","relations"))
        val relations=p.array("relations",BackupLimits.RELATIONS);requireBackup(relations.isEmpty(),"unsupported_relations")
        return BackupData(
            p.array("notes").map { val e=it.obj(noteKeys+"deletedAt");BackupNote(readNote(e),e.long("deletedAt")) },
            p.array("drafts").map { val e=it.obj(setOf("record","baseRevision","active"));BackupDraft(Editing(readNote(e["record"].obj(noteKeys)),e.long("baseRevision")),e.bool("active")) },
            p.array("categories").map { val e=it.obj(setOf("id","name","revision","active"));BackupCategory(Category(e.string("id"),e.string("name"),e.long("revision")),e.bool("active")) },
            p.array("reminders").map {
                val e=it.obj(setOf("id","noteId","revision","repeat","start","hour","minute","weekdays","enabled"))
                val rule=try { ReminderRule(Repeat.valueOf(e.string("repeat")),LocalDay.parse(e.string("start")),e.integer("hour"),e.integer("minute"),e.integer("weekdays")).also { r ->r.validate() } } catch(_: Exception) { throw BackupFailure("invalid_reminder") }
                BackupReminder(e.string("id"),e.string("noteId"),e.long("revision"),rule,e.bool("enabled"))
            },p.array("origins").map { val e=it.obj(setOf("recordId","exportId","sourceId"));BackupOrigin(e.string("recordId"),e.string("exportId"),e.string("sourceId")) })
    }
    internal fun validate(d: BackupData) {
        fun <T> unique(rows: List<T>,id: (T)->String) { requireBackup(rows.size<=BackupLimits.RECORDS,"count_limit");val ids=rows.map(id);ids.forEach(::validId);requireBackup(ids.distinct().size==ids.size,"duplicate_id") }
        unique(d.notes) { it.note.id };unique(d.drafts) { it.editing.note.id };unique(d.categories) { it.category.id };unique(d.reminders) { it.id };unique(d.origins) { it.recordId }
        val records=(d.notes.map { it.note.id }+d.drafts.map { it.editing.note.id }).toSet();requireBackup(records.size<=BackupLimits.RECORDS,"count_limit")
        val categories=d.categories.associateBy { it.category.id };val notes=d.notes.associateBy { it.note.id }
        requireBackup(d.categories.filter { it.active }.map { it.category.name }.distinct().size==d.categories.count { it.active },"duplicate_category_name")
        for(c in d.categories) { requireBackup(c.category.name.isNotBlank() && c.category.name.codePointCount(0,c.category.name.length)<=80,"invalid_category");validRevision(c.category.revision) }
        fun note(n: Note) {
            validId(n.id);validTime(n.created);validTime(n.updated);validRevision(n.revision)
            requireBackup(n.categorySource in setOf("auto","manual"))
            if(n.categoryId.isEmpty()) requireBackup(n.category.isEmpty(),"dangling_category")
            else { validId(n.categoryId);val c=categories[n.categoryId] ?: throw BackupFailure("dangling_category");requireBackup(n.category==c.category.name,"category_name_mismatch") }
        }
        for(n in d.notes) { note(n.note);requireBackup(n.note.body.isNotBlank(),"empty_note");validTime(n.deletedAt) }
        for(draft in d.drafts) {
            val e=draft.editing;note(e.note)
            requireBackup(e.baseRevision==(notes[e.note.id]?.note?.revision ?: -1L),"draft_base_revision")
            requireBackup(e.note.revision>=e.baseRevision,"draft_revision")
        }
        requireBackup(d.reminders.map { it.noteId }.distinct().size==d.reminders.size,"duplicate_reminder_note")
        for(r in d.reminders) { validId(r.noteId);validRevision(r.revision);requireBackup(notes.containsKey(r.noteId),"dangling_reminder");try { r.rule.validate() } catch(_: Exception) { throw BackupFailure("invalid_reminder") };requireBackup(notes[r.noteId]!!.deletedAt==0L || !r.enabled,"trash_reminder_enabled") }
        for(o in d.origins) { validId(o.exportId);validId(o.sourceId);requireBackup(o.recordId in records,"dangling_origin") }
    }
    private fun validId(id: String) { requireBackup(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")),"invalid_id") }
    private fun validTime(n: Long) { requireBackup(n in 0..253402300799999L,"invalid_time") }
    private fun validRevision(n: Long) { requireBackup(n in 0..Long.MAX_VALUE-1024,"invalid_revision") }
    @Suppress("UNCHECKED_CAST") private fun Any?.obj(keys: Set<String>): Map<String,Any?> {
        val m=this as? Map<*,*> ?: throw BackupFailure("invalid_type");requireBackup(m.keys==keys,"unknown_or_missing_field");return m as Map<String,Any?>
    }
    private fun Map<String,Any?>.string(key: String)=this[key] as? String ?: throw BackupFailure("invalid_type")
    private fun Map<String,Any?>.long(key: String)=this[key] as? Long ?: throw BackupFailure("invalid_type")
    private fun Map<String,Any?>.integer(key: String): Int { val n=long(key);requireBackup(n in Int.MIN_VALUE..Int.MAX_VALUE);return n.toInt() }
    private fun Map<String,Any?>.bool(key: String)=this[key] as? Boolean ?: throw BackupFailure("invalid_type")
    private fun Map<String,Any?>.array(key: String,max: Int=BackupLimits.RECORDS): List<*> { val a=this[key] as? List<*> ?: throw BackupFailure("invalid_type");requireBackup(a.size<=max,"count_limit");return a }
}
