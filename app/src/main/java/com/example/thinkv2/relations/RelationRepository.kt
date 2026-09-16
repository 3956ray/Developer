package com.example.thinkv2.relations

import com.example.thinkv2.notes.Sql
import java.util.UUID

data class RelatedNote(val id: String,val title: String,val category: String,val excerpt: String)
data class RelationRow(val edgeId: String,val note: RelatedNote,val created: Long)
data class RelationPage(val source: RelatedNote,val rows: List<RelationRow>,val candidates: List<RelatedNote>,val total: Int,val offset: Int,val token: String)

/** Edges reference the existing notes; current draft titles/text are read, never copied into another store. */
class RelationRepository(private val db: Sql) {
    companion object {
        const val PAGE=50
        fun migrate(db: Sql) {
            db.execute("CREATE TABLE note_relations (id TEXT PRIMARY KEY,a TEXT NOT NULL,b TEXT NOT NULL,source TEXT NOT NULL CHECK(source='manual'),created INTEGER NOT NULL CHECK(created>0),CHECK(a<b),UNIQUE(a,b))")
            db.execute("CREATE INDEX relations_from ON note_relations(a,created,id)")
            db.execute("CREATE INDEX relations_to ON note_relations(b,created,id)")
            db.execute("CREATE TRIGGER relation_active_endpoints BEFORE INSERT ON note_relations BEGIN SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM notes WHERE id=NEW.a AND deleted_at=0) OR NOT EXISTS(SELECT 1 FROM notes WHERE id=NEW.b AND deleted_at=0) THEN RAISE(ABORT,'relation_endpoint_unavailable') END; END")
        }
    }
    private val display="n.id,coalesce(d.title,n.title),coalesce(d.category,n.category),substr(coalesce(d.body,n.body),1,100)"
    private val join="notes n LEFT JOIN drafts d ON d.id=n.id AND d.active=1"
    private fun note(r: List<String>,at: Int=0)=RelatedNote(r[at],r[at+1],r[at+2],r[at+3])
    private fun current(id: String)=db.query("SELECT $display FROM $join WHERE n.id=? AND n.deleted_at=0",listOf(id)).singleOrNull()?.let { note(it) } ?: error("relation_endpoint_unavailable")
    private fun filter(query: String): Pair<String,List<String>> {
        require(query.codePointCount(0,query.length)<=128) { "relation_query_limit" }
        val terms=query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() };require(terms.size<=8) { "relation_query_limit" }
        val where=terms.joinToString("") { " AND (coalesce(d.title,n.title) LIKE ? ESCAPE '\\' OR coalesce(d.body,n.body) LIKE ? ESCAPE '\\')" }
        return where to terms.flatMap { val v="%"+it.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";listOf(v,v) }
    }
    fun page(sourceId: String,choosing: Boolean=false,query: String="",offset: Int=0,expectedToken: String?=null): RelationPage=transaction {
        // Connection-local SQLite counters: other-connection commits + this connection's writes.
        val token=db.query("PRAGMA data_version").single().single()+":"+db.query("SELECT total_changes()").single().single()
        check(offset==0 || expectedToken==null || expectedToken==token) { "relation_page_changed" }
        require(offset>=0);val source=current(sourceId);val (filter,args)=filter(query)
        if(choosing) {
            val where="n.deleted_at=0 AND n.id!=? AND NOT EXISTS(SELECT 1 FROM note_relations r WHERE (r.a=? AND r.b=n.id) OR (r.b=? AND r.a=n.id))$filter"
            val parameters=listOf(sourceId,sourceId,sourceId)+args
            val total=db.query("SELECT count(*) FROM $join WHERE $where",parameters).single().single().toInt()
            val rows=db.query("SELECT $display FROM $join WHERE $where ORDER BY n.updated DESC,n.id LIMIT ? OFFSET ?",parameters+listOf(PAGE.toString(),offset.toString())).map { note(it) }
            RelationPage(source,emptyList(),rows,total,offset,token)
        } else {
            val from="note_relations r JOIN notes n ON n.id=CASE WHEN r.a=? THEN r.b ELSE r.a END LEFT JOIN drafts d ON d.id=n.id AND d.active=1"
            val where="(r.a=? OR r.b=?) AND n.deleted_at=0$filter";val parameters=listOf(sourceId,sourceId,sourceId)+args
            val total=db.query("SELECT count(*) FROM $from WHERE $where",parameters).single().single().toInt()
            val rows=db.query("SELECT r.id,$display,r.created FROM $from WHERE $where ORDER BY r.created DESC,r.id LIMIT ? OFFSET ?",parameters+listOf(PAGE.toString(),offset.toString()))
                .map { RelationRow(it[0],note(it,1),it[5].toLong()) }
            RelationPage(source,rows,emptyList(),total,offset,token)
        }
    }
    fun create(sourceId: String,targetId: String,now: Long=System.currentTimeMillis()): String=transaction {
        require(sourceId!=targetId) { "relation_self" };require(now>0)
        current(sourceId);current(targetId)
        val (a,b)=listOf(sourceId,targetId).sorted()
        check(db.query("SELECT id FROM note_relations WHERE a=? AND b=?",listOf(a,b)).isEmpty()) { "relation_duplicate" }
        val id=UUID.randomUUID().toString();db.execute("INSERT INTO note_relations VALUES (?,?,?,'manual',?)",listOf(id,a,b,now.toString()));id
    }
    fun remove(sourceId: String,row: RelationRow)=transaction {
        current(sourceId)
        val (a,b)=listOf(sourceId,row.note.id).sorted()
        check(db.query("SELECT id FROM note_relations WHERE id=? AND a=? AND b=?",listOf(row.edgeId,a,b)).isNotEmpty()) { "relation_changed" }
        db.execute("DELETE FROM note_relations WHERE id=?",listOf(row.edgeId))
    }
    private fun <T> transaction(block: ()->T): T {
        db.begin();try { val result=block();db.commit();return result } catch(e: Exception) { db.rollback();throw e }
    }
}
