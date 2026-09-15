package com.example.thinkv2

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.thinkv2.notes.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidLifecycleTest {
    @Test fun actualPlatformMigrationAndLifecyclePreserveDraft() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-lifecycle-${UUID.randomUUID()}.db"
        try {
            AndroidSql(context,name).use { sql ->
                val fields="id TEXT PRIMARY KEY,body TEXT NOT NULL,title TEXT NOT NULL,manual INTEGER NOT NULL,category TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,revision INTEGER NOT NULL"
                sql.execute("CREATE TABLE notes ($fields,title_fold TEXT NOT NULL,body_fold TEXT NOT NULL)")
                sql.execute("CREATE TABLE drafts ($fields,base_revision INTEGER NOT NULL,active INTEGER NOT NULL)")
                sql.execute("INSERT INTO notes VALUES ('android-legacy','原文','手动标题',1,'旧分类',10,20,2,'手动标题','原文')")
                sql.execute("INSERT INTO drafts VALUES ('android-legacy','草稿原文','草稿标题',1,'仅草稿分类',10,30,5,2,1)")
                sql.execute("PRAGMA user_version=1")
                sql.execute("CREATE TRIGGER fail_migration BEFORE UPDATE ON drafts BEGIN SELECT RAISE(ABORT,'synthetic'); END")
                val repository=NoteRepository(sql)
                var rejected=false;try { repository.initialize() } catch(_: Exception) { rejected=true }
                assertTrue(rejected);assertEquals("1",sql.query("PRAGMA user_version").single().single())
                assertEquals("旧分类",sql.query("SELECT category FROM notes").single().single())
                assertFalse(sql.query("PRAGMA table_info(notes)").any { it[1]=="category_id" })
                sql.execute("DROP TRIGGER fail_migration")
            }
            NoteRepository(AndroidSql(context,name)).use { r ->
                r.initialize();val formal=r.find("android-legacy")!!;val draft=r.open(formal.id)!!
                assertEquals("原文",formal.body);assertEquals("草稿原文",draft.note.body)
                assertEquals("旧分类",formal.category);assertEquals("仅草稿分类",draft.note.category)
                assertNotEquals(formal.categoryId,draft.note.categoryId)
                r.softDelete(draft)
                val both=r.categories();both.forEach { r.deleteCategory(it) }
                assertEquals(0,r.search("").total);assertTrue(r.trash().single().hasDraft)
            }
            NoteRepository(AndroidSql(context,name)).use { r ->
                r.initialize();val result=r.restore(r.trash().single().note)
                assertTrue(result.categoryMissing);assertEquals("android-legacy",result.note.id)
                assertEquals("原文",result.note.body);assertEquals("手动标题",result.note.title)
                assertEquals("草稿原文",r.open(result.note.id)!!.note.body)
                assertEquals("",r.open(result.note.id)!!.note.categoryId)
                r.save(r.open(result.note.id)!!);assertEquals(1,r.search("草稿").total)
            }
        } finally { context.deleteDatabase(name) }
    }
}
