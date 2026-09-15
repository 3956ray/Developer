package com.example.thinkv2.notes

import java.util.Locale

/** One serial caller owns this repository. SQL values are always bound parameters. */
class NoteRepository(private val db: Sql) : AutoCloseable {
    private val columns = "id,body,title,manual,category,created,updated,revision"
    private val fields = "id TEXT PRIMARY KEY,body TEXT NOT NULL,title TEXT NOT NULL," +
        "manual INTEGER NOT NULL CHECK(manual IN (0,1)),category TEXT NOT NULL," +
        "created INTEGER NOT NULL,updated INTEGER NOT NULL,revision INTEGER NOT NULL"

    fun initialize() = transaction {
        val version = db.query("PRAGMA user_version").single().single().toInt()
        check(version in 0..1) { "unsupported_schema" }
        if (version == 0) {
            check(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'").isEmpty()) { "unknown_database" }
            db.execute("CREATE TABLE notes ($fields, title_fold TEXT NOT NULL, body_fold TEXT NOT NULL)")
            db.execute("CREATE INDEX notes_recent ON notes(updated DESC,id)")
            db.execute("CREATE TABLE drafts ($fields,base_revision INTEGER NOT NULL,active INTEGER NOT NULL CHECK(active IN (0,1)))")
            db.execute("PRAGMA user_version=1")
        }
    }

    private fun note(r: List<String>) = Note(r[0],r[1],r[2],r[3]=="1",r[4],r[5].toLong(),r[6].toLong(),r[7].toLong())
    private fun values(n: Note) = listOf(n.id,n.body,n.title,if(n.manualTitle) "1" else "0",n.category,
        n.created.toString(),n.updated.toString(),n.revision.toString())
    fun find(id: String): Note? = db.query("SELECT $columns FROM notes WHERE id=?",listOf(id)).firstOrNull()?.let(::note)
    fun drafts(): List<Note> = db.query("SELECT $columns FROM drafts WHERE active=1 ORDER BY updated DESC,id").map(::note)
    fun open(id: String): Editing? {
        val draft = db.query("SELECT $columns,base_revision FROM drafts WHERE id=? AND active=1",listOf(id)).firstOrNull()
        return if (draft != null) Editing(note(draft),draft[8].toLong()) else find(id)?.let { formal ->
            val watermark=db.query("SELECT revision FROM drafts WHERE id=?",listOf(id)).firstOrNull()?.first()?.toLong() ?: -1
            Editing(formal.copy(revision=maxOf(formal.revision,watermark)),formal.revision)
        }
    }

    fun search(query: String, category: String? = null, offset: Int = 0): SearchPage = transaction {
        require(query.codePointCount(0,query.length) <= 128 && offset >= 0) { "query_limit" }
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()
        require(terms.size <= 8) { "query_limit" }
        val clauses = terms.map { "(title_fold LIKE ? ESCAPE '\\' OR body_fold LIKE ? ESCAPE '\\')" }.toMutableList()
        val args = terms.flatMap { term ->
            val literal = "%" + term.replace("\\","\\\\").replace("%","\\%").replace("_","\\_") + "%"
            listOf(literal,literal)
        }.toMutableList()
        if (category != null) { clauses += "category=?"; args += category }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val total = db.query("SELECT count(*) FROM notes$where",args).single().single().toInt()
        val rankArgs = if(terms.isEmpty()) emptyList() else listOf(query.trim().lowercase(Locale.ROOT)) + terms.map {
            "%"+it.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
        }
        val ranking = if(terms.isEmpty()) "" else "CASE WHEN title_fold=? THEN 0 WHEN " +
            terms.joinToString(" AND ") { "title_fold LIKE ? ESCAPE '\\'" } + " THEN 1 ELSE 2 END,"
        SearchPage(db.query("SELECT $columns FROM notes$where ORDER BY ${ranking}updated DESC,id LIMIT 100 OFFSET ?",args+rankArgs+offset.toString()).map(::note),total)
    }
    fun categories() = db.query("SELECT DISTINCT category FROM notes WHERE category!='' ORDER BY category").map { it[0] }

    private fun writeDraft(e: Editing, active: Boolean) {
        db.execute("INSERT OR REPLACE INTO drafts ($columns,base_revision,active) VALUES (?,?,?,?,?,?,?,?,?,?)",
            values(e.note)+listOf(e.baseRevision.toString(),if(active) "1" else "0"))
    }
    private fun ensureCurrent(e: Editing) {
        val formal = find(e.note.id)
        check((formal?.revision ?: -1) == e.baseRevision) { "stale_edit" }
        val saved = db.query("SELECT revision,active FROM drafts WHERE id=?",listOf(e.note.id)).firstOrNull()
        if (saved != null) check(saved[0].toLong() <= e.note.revision &&
            (saved[1]=="1" || saved[0].toLong() < e.note.revision || sameContent(formal,e.note))) { "stale_draft" }
    }
    private fun sameContent(a: Note?, b: Note) = a != null && a == b.copy(revision=a.revision,updated=a.updated)
    fun persistDraft(e: Editing) = transaction {
        ensureCurrent(e)
        val active=db.query("SELECT id FROM drafts WHERE id=? AND active=1",listOf(e.note.id)).isNotEmpty()
        if(active || !sameContent(find(e.note.id),e.note)) writeDraft(e,true)
    }
    fun save(e: Editing, now: Long = System.currentTimeMillis()): Note = transaction {
        require(e.note.body.isNotBlank()) { "body_required" }
        val n = e.note.copy(title=if(e.note.manualTitle) e.note.title else automaticTitle(e.note.body),category=e.note.category.trim(),updated=now)
        val current = find(n.id)
        // A retried identical submission must not create a second record or change its timestamp.
        if (current != null && sameContent(current,n)) {
            if(e.baseRevision == current.revision) {
                ensureCurrent(e)
                writeDraft(Editing(n.copy(body="",title="",category=""),current.revision),false)
            } else {
                val pending=db.query("SELECT revision FROM drafts WHERE id=? AND active=1",listOf(n.id)).firstOrNull()
                check(n.revision==current.revision && pending==null) { "stale_edit" }
            }
            return@transaction current
        }
        ensureCurrent(e)
        check(n.revision > e.baseRevision) { "revision_required" }
        db.execute("INSERT OR REPLACE INTO notes ($columns,title_fold,body_fold) VALUES (?,?,?,?,?,?,?,?,?,?)",
            values(n)+listOf(n.title.lowercase(Locale.ROOT),n.body.lowercase(Locale.ROOT)))
        writeDraft(Editing(n.copy(body="",title="",category=""),n.revision),false)
        n
    }
    fun discard(e: Editing) = transaction {
        ensureCurrent(e)
        writeDraft(e.copy(note=e.note.copy(body="",title="",category="")),false)
    }
    private fun <T> transaction(action: () -> T): T {
        db.begin()
        try { val result=action(); db.commit(); return result }
        catch (error: Exception) { db.rollback(); throw error }
    }
    override fun close() = db.close()
}
