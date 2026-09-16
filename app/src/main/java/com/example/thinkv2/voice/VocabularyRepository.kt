package com.example.thinkv2.voice

import com.example.thinkv2.notes.Sql

/** Sole owner after schema 8. Legacy preferences are read once by the schema transaction. */
class VocabularyRepository(private val db: Sql) {
    fun rows()=db.query("SELECT from_text,to_text FROM correction_vocabulary ORDER BY position").map { Correction(it[0],it[1]) }
    fun replace(expected: List<Correction>,rows: List<Correction>) {
        VoiceText.validate(rows);db.begin()
        try {
            check(rows()==expected) { "vocabulary_changed" }
            db.execute("DELETE FROM correction_vocabulary")
            rows.forEachIndexed { i,row -> db.execute("INSERT INTO correction_vocabulary VALUES (?,?,?)",listOf(i.toString(),row.from,row.to)) }
            db.commit()
        } catch(e: Exception) { db.rollback();throw e }
    }
}
