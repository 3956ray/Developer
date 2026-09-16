package com.example.thinkv2.notes

import java.util.Locale
import java.util.UUID
import com.example.thinkv2.reminders.ReminderSchema
import com.example.thinkv2.backup.BackupRepository

/** One serial caller owns this repository. SQL values are always bound parameters. */
class NoteRepository(private val db: Sql) : AutoCloseable {
    private val columns = "id,body,title,manual,category,created,updated,revision,category_id,category_source"
    private val fields = "id TEXT PRIMARY KEY,body TEXT NOT NULL,title TEXT NOT NULL," +
        "manual INTEGER NOT NULL CHECK(manual IN (0,1)),category TEXT NOT NULL," +
        "created INTEGER NOT NULL,updated INTEGER NOT NULL,revision INTEGER NOT NULL"

    fun initialize() = transaction {
        val version = db.query("PRAGMA user_version").single().single().toInt()
        check(version in 0..7) { "unsupported_schema" }
        if (version == 0) {
            check(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'").isEmpty()) { "unknown_database" }
            db.execute("CREATE TABLE notes ($fields, title_fold TEXT NOT NULL, body_fold TEXT NOT NULL)")
            db.execute("CREATE INDEX notes_recent ON notes(updated DESC,id)")
            db.execute("CREATE TABLE drafts ($fields,base_revision INTEGER NOT NULL,active INTEGER NOT NULL CHECK(active IN (0,1)))")
            db.execute("PRAGMA user_version=1")
        }
        if(version < 2) {
            db.execute("CREATE TABLE categories (id TEXT PRIMARY KEY,name TEXT NOT NULL,revision INTEGER NOT NULL,active INTEGER NOT NULL CHECK(active IN (0,1)))")
            db.execute("CREATE UNIQUE INDEX category_names ON categories(name) WHERE active=1")
            for(table in listOf("notes","drafts")) {
                db.execute("ALTER TABLE $table ADD COLUMN category_id TEXT NOT NULL DEFAULT ''")
                db.execute("ALTER TABLE $table ADD COLUMN category_source TEXT NOT NULL DEFAULT 'manual' CHECK(category_source IN ('manual','auto'))")
            }
            db.execute("ALTER TABLE notes ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0")
            // Exact legacy strings, including draft-only categories, survive unchanged.
            val names=db.query("SELECT category FROM notes WHERE category!='' UNION SELECT category FROM drafts WHERE category!='' ORDER BY category")
            for(row in names) {
                val id=UUID.randomUUID().toString()
                db.execute("INSERT INTO categories VALUES (?,?,0,1)",listOf(id,row[0]))
                for(table in listOf("notes","drafts")) db.execute("UPDATE $table SET category_id=? WHERE category=?",listOf(id,row[0]))
            }
            db.execute("CREATE INDEX notes_lifecycle ON notes(deleted_at,updated DESC,id)")
            db.execute("PRAGMA user_version=2")
        }
        if(version<3) { ReminderSchema.migrate(db);db.execute("PRAGMA user_version=3") }
        if(version<4) { BackupRepository.migrate(db);db.execute("PRAGMA user_version=4") }
        if(version<5) { com.example.thinkv2.calendar.CalendarRepository.migrate(db);db.execute("PRAGMA user_version=5") }
        if(version<6) {
            db.execute("CREATE TABLE ai_acceptances (id TEXT PRIMARY KEY,request_id TEXT NOT NULL,record_id TEXT NOT NULL,field TEXT NOT NULL,endpoint TEXT NOT NULL,model TEXT NOT NULL,input_hash TEXT NOT NULL,proposed TEXT NOT NULL,chosen TEXT NOT NULL,accepted_at INTEGER NOT NULL,note_revision INTEGER NOT NULL,UNIQUE(request_id,field))")
            db.execute("PRAGMA user_version=6")
        }
        if(version<7) { com.example.thinkv2.relations.RelationRepository.migrate(db);db.execute("PRAGMA user_version=7") }
    }

    private fun note(r: List<String>) = Note(r[0],r[1],r[2],r[3]=="1",r[4],r[5].toLong(),r[6].toLong(),r[7].toLong(),r[8],r[9])
    private fun values(n: Note) = listOf(n.id,n.body,n.title,if(n.manualTitle) "1" else "0",n.category,
        n.created.toString(),n.updated.toString(),n.revision.toString(),n.categoryId,n.categorySource)
    fun find(id: String): Note? = db.query("SELECT $columns FROM notes WHERE id=? AND deleted_at=0",listOf(id)).firstOrNull()?.let(::note)
    fun drafts(): List<Note> = db.query("SELECT $columns FROM drafts WHERE active=1 AND NOT EXISTS (SELECT 1 FROM notes WHERE notes.id=drafts.id AND notes.deleted_at>0) ORDER BY updated DESC,id").map(::note)
    fun open(id: String): Editing? {
        if(isTrashed(id)) return null
        val draft = db.query("SELECT $columns,base_revision FROM drafts WHERE id=? AND active=1",listOf(id)).firstOrNull()
        return if (draft != null) Editing(note(draft),draft[10].toLong()) else find(id)?.let { formal ->
            val watermark=db.query("SELECT revision FROM drafts WHERE id=?",listOf(id)).firstOrNull()?.first()?.toLong() ?: -1
            Editing(formal.copy(revision=maxOf(formal.revision,watermark)),formal.revision)
        }
    }

    fun search(query: String, category: String? = null, offset: Int = 0): SearchPage = transaction {
        require(query.codePointCount(0,query.length) <= 128 && offset >= 0) { "query_limit" }
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()
        require(terms.size <= 8) { "query_limit" }
        val clauses = terms.map { "(title_fold LIKE ? ESCAPE '\\' OR body_fold LIKE ? ESCAPE '\\')" }.toMutableList().apply { add("deleted_at=0") }
        val args = terms.flatMap { term ->
            val literal = "%" + term.replace("\\","\\\\").replace("%","\\%").replace("_","\\_") + "%"
            listOf(literal,literal)
        }.toMutableList()
        if (category != null) { clauses += "category_id=?"; args += category }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val total = db.query("SELECT count(*) FROM notes$where",args).single().single().toInt()
        val rankArgs = if(terms.isEmpty()) emptyList() else listOf(query.trim().lowercase(Locale.ROOT)) + terms.map {
            "%"+it.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
        }
        val ranking = if(terms.isEmpty()) "" else "CASE WHEN title_fold=? THEN 0 WHEN " +
            terms.joinToString(" AND ") { "title_fold LIKE ? ESCAPE '\\'" } + " THEN 1 ELSE 2 END,"
        SearchPage(db.query("SELECT $columns FROM notes$where ORDER BY ${ranking}updated DESC,id LIMIT 100 OFFSET ?",args+rankArgs+offset.toString()).map(::note),total)
    }
    fun categories(): List<Category> = db.query("SELECT id,name,revision FROM categories WHERE active=1 ORDER BY name,id")
        .map { Category(it[0],it[1],it[2].toLong()) }
    private fun category(id: String): Category? = db.query("SELECT id,name,revision FROM categories WHERE id=? AND active=1",listOf(id))
        .firstOrNull()?.let { Category(it[0],it[1],it[2].toLong()) }
    private fun nameAvailable(name: String,exceptId: String="") {
        require(name.isNotBlank() && name.codePointCount(0,name.length)<=80) { "category_name_invalid" }
        check(db.query("SELECT id FROM categories WHERE name=? AND active=1 AND id!=?",listOf(name,exceptId)).isEmpty()) { "category_name_exists" }
    }
    private fun insertCategory(name: String): Category {
        nameAvailable(name)
        val c=Category(UUID.randomUUID().toString(),name)
        db.execute("INSERT INTO categories VALUES (?,?,0,1)",listOf(c.id,c.name))
        return c
    }
    fun createCategory(name: String): Category = transaction { insertCategory(name.trim()) }
    private fun normalized(note: Note): Note {
        require(note.categorySource in listOf("manual","auto"))
        if(note.categoryId.isNotEmpty()) {
            val category=category(note.categoryId) ?: error("category_missing")
            return note.copy(category=category.name)
        }
        if(note.category.isBlank()) return note.copy(category="")
        // Compatibility for still-in-memory v1 text edits; v2 UI selects IDs explicitly.
        val name=note.category.trim()
        val category=categories().firstOrNull { it.name==name } ?: insertCategory(name)
        return note.copy(category=category.name,categoryId=category.id)
    }
    fun calendarOriginal(id: String): String? = db.query("SELECT original_text FROM calendar_imports WHERE note_id=?",listOf(id)).firstOrNull()?.first()
    fun backupCopies(): Set<String> = db.query("SELECT record_id FROM backup_origins").map { it[0] }.toSet()
    fun isTrashed(id: String) = db.query("SELECT id FROM notes WHERE id=? AND deleted_at>0",listOf(id)).isNotEmpty()
    private fun anyNote(id: String) = db.query("SELECT $columns FROM notes WHERE id=?",listOf(id)).firstOrNull()?.let(::note)
    private fun anyDraft(id: String): Pair<Editing,Boolean>? = db.query("SELECT $columns,base_revision,active FROM drafts WHERE id=?",listOf(id))
        .firstOrNull()?.let { Pair(Editing(note(it),it[10].toLong()),it[11]=="1") }
    private fun updateNote(n: Note) {
        db.execute("UPDATE notes SET body=?,title=?,manual=?,category=?,created=?,updated=?,revision=?,category_id=?,category_source=?,title_fold=?,body_fold=? WHERE id=?",
            values(n).drop(1)+listOf(n.title.lowercase(Locale.ROOT),n.body.lowercase(Locale.ROOT),n.id))
    }

    private fun writeDraft(e: Editing, active: Boolean) {
        db.execute("INSERT OR REPLACE INTO drafts ($columns,base_revision,active) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            values(e.note)+listOf(e.baseRevision.toString(),if(active) "1" else "0"))
    }
    private fun ensureCurrent(e: Editing) {
        check(!isTrashed(e.note.id)) { "note_in_trash" }
        val formal = find(e.note.id)
        check((formal?.revision ?: -1) == e.baseRevision) { "stale_edit" }
        val saved = db.query("SELECT revision,active FROM drafts WHERE id=?",listOf(e.note.id)).firstOrNull()
        if (saved != null) check(saved[0].toLong() <= e.note.revision &&
            (saved[1]=="1" || saved[0].toLong() < e.note.revision || sameContent(formal,e.note))) { "stale_draft" }
    }
    private fun sameContent(a: Note?, b: Note) = a != null && a == b.copy(revision=a.revision,updated=a.updated)
    fun persistDraft(editing: Editing): Editing = transaction {
        ensureCurrent(editing)
        val e=editing.copy(note=normalized(editing.note))
        val active=db.query("SELECT id FROM drafts WHERE id=? AND active=1",listOf(e.note.id)).isNotEmpty()
        if(active || !sameContent(find(e.note.id),e.note)) writeDraft(e,true)
        e
    }
    /** Explicit acceptance is one transaction: optional confirmed category + draft + immutable provenance. */
    fun acceptAi(editing: Editing,a: com.example.thinkv2.ai.AiAcceptance): Editing = transaction {
        ensureCurrent(editing)
        require(a.field in setOf("title","category"))
        require(a.chosen.isNotBlank() && a.chosen.codePointCount(0,a.chosen.length)<=if(a.field=="title") 120 else 80)
        check(db.query("SELECT id FROM ai_acceptances WHERE request_id=? AND field=?",listOf(a.requestId,a.field)).isEmpty()) { "already_accepted" }
        val changed=if(a.field=="title") editing.note.titleChanged(a.chosen) else {
            // Do not silently map a deleted/renamed candidate or turn a model string into a new category.
            check(a.candidates.all { category(it.id)==it }) { "category_changed" }
            val selected=if(a.createCategory) insertCategory(a.chosen.trim()) else {
                val expected=a.candidates.singleOrNull { it.id==a.categoryId } ?: error("category_changed")
                check(expected.name==a.chosen);expected
            }
            editing.note.categorized(selected)
        }
        val next=editing.copy(note=changed);writeDraft(next,true)
        db.execute("INSERT INTO ai_acceptances VALUES (?,?,?,?,?,?,?,?,?,?,?)",listOf(UUID.randomUUID().toString(),a.requestId,changed.id,a.field,a.endpoint,a.model,a.inputHash,a.proposed,a.chosen,System.currentTimeMillis().toString(),changed.revision.toString()))
        next
    }
    fun save(e: Editing, now: Long = System.currentTimeMillis()): Note = transaction {
        require(e.note.body.isNotBlank()) { "body_required" }
        val n = normalized(e.note).copy(title=if(e.note.manualTitle) e.note.title else automaticTitle(e.note.body),updated=now)
        val current = find(n.id)
        // A retried identical submission must not create a second record or change its timestamp.
        if (current != null && sameContent(current,n)) {
            if(e.baseRevision == current.revision) {
                ensureCurrent(e)
                writeDraft(Editing(n.copy(body="",title="",category="",categoryId=""),current.revision),false)
            } else {
                val pending=db.query("SELECT revision FROM drafts WHERE id=? AND active=1",listOf(n.id)).firstOrNull()
                check(n.revision==current.revision && pending==null) { "stale_edit" }
            }
            return@transaction current
        }
        ensureCurrent(e)
        check(n.revision > e.baseRevision) { "revision_required" }
        if(current==null) db.execute("INSERT INTO notes ($columns,title_fold,body_fold) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            values(n)+listOf(n.title.lowercase(Locale.ROOT),n.body.lowercase(Locale.ROOT)))
        else updateNote(n)
        writeDraft(Editing(n.copy(body="",title="",category="",categoryId=""),n.revision),false)
        n
    }
    fun discard(e: Editing) = transaction {
        ensureCurrent(e)
        writeDraft(e.copy(note=e.note.copy(body="",title="",category="",categoryId="")),false)
    }
    fun renameCategory(expected: Category,name: String) = transaction {
        val current=category(expected.id)
        check(current==expected) { "category_changed" }
        val next=name.trim();nameAvailable(next,expected.id)
        db.execute("UPDATE categories SET name=?,revision=revision+1 WHERE id=?",listOf(next,expected.id))
        changeReferences(expected.id,expected.id,next,includeTrash=true)
    }
    fun deleteCategory(expected: Category) = transaction {
        check(category(expected.id)==expected) { "category_changed" }
        db.execute("UPDATE categories SET active=0,revision=revision+1 WHERE id=?",listOf(expected.id))
        // Trashed snapshots retain their old category until restore can report a missing category.
        changeReferences(expected.id,"","",includeTrash=false)
    }
    private fun changeReferences(oldId: String,newId: String,name: String,includeTrash: Boolean) {
        val ids=db.query("SELECT id FROM notes WHERE category_id=? UNION SELECT id FROM drafts WHERE category_id=?",listOf(oldId,oldId)).map { it[0] }
        for(id in ids) {
            if(!includeTrash && isTrashed(id)) continue
            val formal=anyNote(id);val draft=anyDraft(id)
            val high=maxOf(formal?.revision ?: -1,draft?.first?.note?.revision ?: -1)
            val formalRevision=if(formal==null) -1 else high+1
            fun changed(n: Note)=if(n.categoryId!=oldId) n else n.copy(categoryId=newId,category=name,
                categorySource=if(newId.isEmpty()) "manual" else n.categorySource)
            if(formal!=null) updateNote(changed(formal).copy(revision=formalRevision))
            if(draft!=null) writeDraft(Editing(changed(draft.first.note).copy(revision=high+2),formalRevision),draft.second)
        }
    }
    fun trash(): List<TrashItem> = db.query("SELECT $columns,deleted_at," +
        "CASE WHEN category_id!='' AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.id=notes.category_id AND c.active=1) THEN 1 ELSE 0 END," +
        "EXISTS (SELECT 1 FROM drafts d WHERE d.id=notes.id AND d.active=1) FROM notes WHERE deleted_at>0 ORDER BY deleted_at DESC,id")
        .map { row -> TrashItem(note(row),row[10].toLong(),row[11]=="1",row[12]=="1") }
    fun softDelete(editing: Editing,now: Long=System.currentTimeMillis()) = transaction {
        require(now>0);ensureCurrent(editing)
        val formal=find(editing.note.id) ?: error("formal_note_required")
        val e=editing.copy(note=normalized(editing.note))
        val draft=anyDraft(e.note.id)
        if(draft?.second==true || !sameContent(formal,e.note)) writeDraft(e,true)
        val next=maxOf(formal.revision,e.note.revision,draft?.first?.note?.revision ?: -1)+1
        db.execute("UPDATE notes SET deleted_at=?,revision=? WHERE id=?",listOf(now.toString(),next.toString(),formal.id))
        db.execute("UPDATE reminders SET requested=0,revision=revision+1,status='CANCEL_PENDING',next_key='',next_at=0,problem='' WHERE note_id=?",listOf(formal.id))
        db.execute("UPDATE reminder_events SET reported=4 WHERE reminder_id IN (SELECT id FROM reminders WHERE note_id=?) AND reported=0",listOf(formal.id))
        anyDraft(formal.id)?.let { (saved,active) ->
            writeDraft(saved.copy(note=saved.note.copy(revision=next+1),baseRevision=next),active)
        }
    }
    fun restore(expected: Note): RestoreResult = transaction {
        check(isTrashed(expected.id)) { "not_in_trash" }
        val formal=anyNote(expected.id) ?: error("missing_note")
        check(formal.revision==expected.revision) { "stale_restore" }
        val draft=anyDraft(formal.id)
        var missing=false
        fun recover(n: Note): Note {
            if(n.categoryId.isEmpty()) return n
            val c=category(n.categoryId)
            if(c!=null) return n.copy(category=c.name)
            missing=true
            return n.copy(categoryId="",category="",categorySource="manual")
        }
        val next=maxOf(formal.revision,draft?.first?.note?.revision ?: -1)+1
        val restored=recover(formal).copy(revision=next)
        updateNote(restored)
        db.execute("UPDATE notes SET deleted_at=0 WHERE id=?",listOf(formal.id))
        if(draft!=null) writeDraft(Editing(recover(draft.first.note).copy(revision=next+1),next),draft.second)
        RestoreResult(restored,missing,db.query("SELECT id FROM reminders WHERE note_id=?",listOf(formal.id)).isNotEmpty())
    }
    private fun <T> transaction(action: () -> T): T {
        db.begin()
        try { val result=action(); db.commit(); return result }
        catch (error: Exception) { db.rollback(); throw error }
    }
    override fun close() = db.close()
}
