package com.example.thinkv2.calendar

import com.example.thinkv2.notes.Sql
import java.util.Locale
import java.util.UUID

/** Called on the existing single database dispatcher. Source provider is never mutated. */
class CalendarRepository(private val db: Sql) {
    companion object {
        fun migrate(db: Sql) {
            db.execute("CREATE TABLE calendar_imports (source_key TEXT NOT NULL,fingerprint TEXT NOT NULL,note_id TEXT NOT NULL UNIQUE,original_payload TEXT NOT NULL,original_text TEXT NOT NULL,imported_note_hash TEXT NOT NULL,PRIMARY KEY(source_key,fingerprint))")
        }
    }
    private fun mapping(event: CalendarEvent)=db.query("SELECT note_id,imported_note_hash FROM calendar_imports WHERE source_key=? AND fingerprint=?",listOf(event.key,event.fingerprint)).firstOrNull()
    private fun noteHash(id: String)=calendarHash(listOf(
        db.query("SELECT * FROM notes WHERE id=?",listOf(id)),db.query("SELECT * FROM drafts WHERE id=?",listOf(id))))
    private fun localToken(): String {
        val tables=listOf("notes","drafts","categories","calendar_imports")
        for(t in tables) calendarCheck(db.query("SELECT count(*) FROM $t").single().single().toLong()<=10000,"local_limit")
        val noteBytes=listOf("notes","drafts").sumOf { table ->db.query("SELECT coalesce(sum(length(CAST(body AS BLOB))+length(CAST(title AS BLOB))+length(CAST(category AS BLOB))),0) FROM $table").single().single().toLong() }
        val sourceBytes=db.query("SELECT coalesce(sum(length(CAST(original_payload AS BLOB))+length(CAST(original_text AS BLOB))),0) FROM calendar_imports").single().single().toLong()
        calendarCheck(noteBytes+sourceBytes<=32L*1024*1024,"local_limit")
        return calendarHash(tables.map { db.query("SELECT * FROM $it ORDER BY 1,2") })
    }
    fun preview(snapshot: CalendarSnapshot): CalendarPreview=transaction {
        snapshot.events.forEach { calendarCheck(it.payload.toByteArray(Charsets.UTF_8).size<=CalendarEvent.MAX_PAYLOAD_BYTES,"event_limit") }
        CalendarPreview(snapshot,localToken(),snapshot.events.map { event ->
            val prior=mapping(event)
            val other=db.query("SELECT note_id,imported_note_hash FROM calendar_imports WHERE source_key=?",listOf(event.key))
            CalendarRow(event,when {
                !event.importable -> CalendarAction.SKIPPED
                prior!=null -> CalendarAction.SAME
                other.isNotEmpty() -> CalendarAction.CHANGED
                else -> CalendarAction.NEW
            },other.any { noteHash(it[0])!=it[1] })
        })
    }
    fun apply(preview: CalendarPreview,selected: Set<String>,copyChanged: Set<String>,categoryId: String,
              identityConfirmed: Boolean,source: CalendarSource,stillCurrent: ()->Boolean = { true }): CalendarApplied {
        calendarCheck(identityConfirmed,"identity_confirmation")
        calendarCheck(selected.isNotEmpty() && selected.size<=300,"selection_required")
        calendarCheck(selected.all { key -> preview.rows.any { it.event.key==key && it.event.importable } },"selection_invalid")
        fun sourceCurrent() {
            if(!source.permitted()) throw SecurityException("calendar_permission")
            calendarCheck(stillCurrent(),"cancelled")
            calendarCheck(source.read(preview.source.calendar,preview.source.range).token==preview.source.token,"source_changed")
            if(!source.permitted()) throw SecurityException("calendar_permission")
            calendarCheck(stillCurrent(),"cancelled")
        }
        sourceCurrent()
        return transaction {
            val rows=preview.rows.filter { it.event.key in selected }
            // Retried identical confirmation is a read-only success, even after process restart.
            if(rows.all { mapping(it.event)!=null }) return@transaction CalendarApplied(emptyList(),rows.size)
            calendarCheck(localToken()==preview.localToken,"local_changed")
            val category=if(categoryId.isEmpty()) "" else db.query("SELECT name FROM categories WHERE id=? AND active=1",listOf(categoryId)).singleOrNull()?.single()
                ?: throw CalendarFailure("local_changed")
            val imported=mutableListOf<String>();var already=0
            for(row in rows) {
                val event=row.event
                if(mapping(event)!=null) { already++;continue }
                calendarCheck(row.action!=CalendarAction.CHANGED || event.key in copyChanged,"copy_confirmation")
                val id=UUID.randomUUID().toString();val body=event.noteBody();val title=event.title.ifBlank { com.example.thinkv2.notes.automaticTitle(event.description) }
                val now=System.currentTimeMillis().toString()
                db.execute("INSERT INTO notes (id,body,title,manual,category,created,updated,revision,category_id,category_source,title_fold,body_fold,deleted_at) VALUES (?,?,?,1,?,?,?,0,?,'manual',?,?,0)",
                    listOf(id,body,title,category,now,now,categoryId,title.lowercase(Locale.ROOT),body.lowercase(Locale.ROOT)))
                db.execute("INSERT INTO calendar_imports VALUES (?,?,?,?,?,?)",listOf(event.key,event.fingerprint,id,event.payload,event.originalText(),noteHash(id)))
                imported+=id
            }
            // Last source/permission check while local writes are still rollbackable.
            sourceCurrent()
            CalendarApplied(imported,already)
        }
    }
    private fun <T> transaction(block: ()->T): T {
        db.begin();try { val result=block();db.commit();return result } catch(e: Exception) { db.rollback();throw e }
    }
}
