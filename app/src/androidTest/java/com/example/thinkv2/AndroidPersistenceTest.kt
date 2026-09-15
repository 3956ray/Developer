package com.example.thinkv2

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.thinkv2.notes.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPersistenceTest {
    @Test fun actualAndroidSqliteRebuildAndRollback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-persistence-${UUID.randomUUID()}.db"
        val draft=Editing(Note().bodyChanged("合成Android原文").titleChanged("人工标题").categoryChanged("分类甲"))
        try {
            NoteRepository(AndroidSql(context,name)).use { r ->
                r.initialize();assertEquals(0,r.search("").total);r.persistDraft(draft)
            }
            NoteRepository(AndroidSql(context,name)).use { r ->
                r.initialize();assertEquals(draft,r.open(draft.note.id));r.save(draft)
            }
            val sql=AndroidSql(context,name)
            NoteRepository(sql).use { r ->
                r.initialize();val saved=r.find(draft.note.id)!!
                assertEquals("人工标题",saved.title);assertEquals("分类甲",saved.category)
                val editing=r.open(saved.id)!!
                r.persistDraft(editing);r.save(editing);assertTrue(r.drafts().isEmpty())
                val changed=editing.copy(note=editing.note.bodyChanged("合成更新").categoryChanged("分类乙"))
                r.persistDraft(changed)
                sql.execute("CREATE TRIGGER synthetic_failure BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT,'synthetic'); END")
                var failed=false
                try { r.save(changed) } catch(_: Exception) { failed=true }
                assertTrue(failed);assertEquals(saved,r.find(saved.id));assertEquals(changed,r.open(saved.id))
                sql.execute("DROP TRIGGER synthetic_failure");r.save(changed)
                assertEquals(0,r.search("原文").total);assertEquals(1,r.search("合成 更新").total)
                assertEquals("分类乙",r.find(saved.id)!!.category)
            }
        } finally { context.deleteDatabase(name) }
    }
}
