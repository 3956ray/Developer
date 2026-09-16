package com.example.thinkv2.backup

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thinkv2.notes.*
import com.example.thinkv2.calendar.*
import com.example.thinkv2.voice.*
import com.example.thinkv2.relations.*
import com.example.thinkv2.reminders.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Fresh synthetic emulator only; fixture provider owns the local test calendar. */
class BackupCompatibilityDeviceTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val tables=listOf("notes","drafts","categories","reminders","reminder_events","backup_imports","backup_origins","calendar_imports","ai_acceptances","note_relations","correction_vocabulary")
    private fun snapshot(db: Sql)=tables.associateWith { db.query("SELECT * FROM $it ORDER BY 1,2") }
    private fun save(name: String,value: Any?) { context.getFileStreamPath("backup-v2-$name.json").writeBytes(StrictJson.encode(value)) }
    private fun encoded(export: BackupExport)=ByteArrayOutputStream().also { BackupCodec.write(export,it) }.toByteArray()
    private fun source(): Pair<AndroidCalendarSource,CalendarSnapshot> {
        ins.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.READ_CALENDAR)
        val source=AndroidCalendarSource(context);val cal=source.calendars().single { it.label=="合成测试日历" }
        return source to source.read(cal,CalendarRange("2026-09-01","2026-09-07","Asia/Shanghai"))
    }
    private fun seed(db: Sql): String {
        val n=NoteRepository(db);n.initialize();val (source,snapshot)=source();val calendar=CalendarRepository(db)
        val ids=calendar.apply(calendar.preview(snapshot),snapshot.events.filter { it.importable }.map { it.key }.toSet(),emptySet(),"",true,source).noteIds
        val category=n.createCategory("兼容合成分类");val other=n.save(Editing(Note(id="v2-other").bodyChanged("关系另一端🙂").titleChanged("人工标题").categorized(category)))
        n.persistDraft(n.open(ids.first())!!.let { it.copy(note=it.note.bodyChanged("保留本机日历编辑草稿🙂")) })
        n.persistDraft(Editing(Note(id="v2-draft").bodyChanged("草稿独有记录")))
        db.execute("INSERT INTO ai_acceptances VALUES ('accepted-title','request-one',?,'title','https://example.invalid/v1/chat/completions','synthetic',?,'模型原建议','用户确认标题',1,1)",listOf(ids.first(),"a".repeat(64)))
        db.execute("INSERT INTO ai_acceptances VALUES ('accepted-category','request-two',?,'category','https://example.invalid/v1/chat/completions','synthetic',?,?,'兼容合成分类',2,1)",listOf(ids.first(),"b".repeat(64),category.id))
        RelationRepository(db).create(ids.first(),other.id,1)
        ReminderRepository(db).save(ids.first(),-1,ReminderRule(Repeat.WEEKLY,LocalDay.parse("2030-01-01"),9,0,17),true,"")
        n.softDelete(n.open(other.id)!!)
        VocabularyRepository(db).replace(VocabularyRepository(db).rows(),listOf(Correction("纳瓦","纳瓦尔"),Correction("纳瓦","另一个纠正")))
        return ids.first()
    }
    @Test fun androidAllFieldsEmptyConflictRepeatRollbackAndCalendarAfterRestore() {
        AndroidSql(context).use { db ->
            val id=seed(db);val repository=BackupRepository(db);val original=snapshot(db);val export=repository.export();val bytes=encoded(export)
            context.getFileStreamPath("backup-v2-full.thinkbackup.json").writeBytes(bytes)
            val candidate=BackupCodec.read(bytes.inputStream());assertEquals(9,candidate.data.calendar.size);assertEquals(2,candidate.data.ai.size)
            save("source",original)
            val name="backup-v2-target.db";context.deleteDatabase(name)
            AndroidSql(context,name).use { target ->
                val notes=NoteRepository(target);notes.initialize();val backup=BackupRepository(target);backup.apply(backup.preview(candidate))
                assertEquals(candidate.data.copy(reminders=candidate.data.reminders.map { it.copy(enabled=false) }),backup.data().copy(receipts=emptyList()))
                save("empty-restored",snapshot(target));assertTrue(target.query("SELECT * FROM reminder_events").isEmpty())
                val (source,sourceSnapshot)=source();val calendar=CalendarRepository(target);val p=calendar.preview(sourceSnapshot)
                assertEquals(9,p.rows.count { it.action==CalendarAction.SAME });val before=snapshot(target)
                assertEquals(9,calendar.apply(p,p.rows.filter { it.event.importable }.map { it.event.key }.toSet(),emptySet(),"",true,source).alreadyImported)
                assertEquals(before,snapshot(target));assertTrue(backup.apply(backup.preview(candidate)).alreadyImported)
                notes.persistDraft(notes.open(id)!!.let { it.copy(note=it.note.bodyChanged("本机冲突不能被覆盖")) })
                val local=target.query("SELECT * FROM drafts WHERE id=?",listOf(id));val next=candidate.copy(exportId="another-export")
                val preview=backup.preview(next);val mapped=preview.records.single { it.sourceId==id }.targetId!!
                backup.apply(preview);assertNotEquals(id,mapped);assertEquals(local,target.query("SELECT * FROM drafts WHERE id=?",listOf(id)))
                assertEquals(10,target.query("SELECT * FROM calendar_imports").size);assertEquals(4,target.query("SELECT * FROM ai_acceptances").size)
                assertEquals(2,target.query("SELECT * FROM note_relations").size)
                val repeated=calendar.preview(sourceSnapshot);assertEquals(9,repeated.rows.count { it.action==CalendarAction.SAME })
                val conflictAfter=snapshot(target);calendar.apply(repeated,repeated.rows.filter { it.event.importable }.map { it.event.key }.toSet(),emptySet(),"",true,source)
                assertEquals(conflictAfter,snapshot(target));save("conflict-restored",conflictAfter)
                save("identity-map",preview.records.associate { it.sourceId to it.targetId })
                val callId=java.util.UUID.randomUUID().toString()
                val descriptor=ins.uiAutomation.executeShellCommand("content call --uri content://com.example.thinkv2.test.calendar.fixture --method edit --arg chinese --extra call_id:s:$callId")
                val response=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                check(response.contains("synthetic result saved $callId"))
                val changedSource=source.read(sourceSnapshot.calendar,sourceSnapshot.range);val changed=calendar.preview(changedSource)
                val changedEvent=changed.rows.single { it.action==CalendarAction.CHANGED }.event
                val oldNotes=target.query("SELECT * FROM notes ORDER BY id");val oldDrafts=target.query("SELECT * FROM drafts ORDER BY id")
                try { calendar.apply(changed,setOf(changedEvent.key),emptySet(),"",true,source);fail("must require copy confirmation") } catch(e: CalendarFailure) { assertEquals("copy_confirmation",e.code) }
                val changedResult=calendar.apply(changed,setOf(changedEvent.key),setOf(changedEvent.key),"",true,source)
                assertEquals(1,changedResult.noteIds.size)
                for(row in oldNotes) assertEquals(row,target.query("SELECT * FROM notes WHERE id=?",listOf(row[0])).single())
                assertEquals(oldDrafts,target.query("SELECT * FROM drafts ORDER BY id"))
                assertEquals(1,calendar.apply(calendar.preview(changedSource),setOf(changedEvent.key),emptySet(),"",true,source).alreadyImported)
                save("calendar-changed-after-restore",mapOf("explicitCopyRequired" to true,"createdOnlyOneNewNote" to true,"allExistingNotesAndDraftsUnchanged" to true,"repeatedChangedVersionSkipped" to true,"newNoteId" to changedResult.noteIds.single()))
            }
            val failures=mutableListOf<Map<String,Any>>()
            for(prefix in listOf("INSERT INTO calendar_imports","INSERT INTO ai_acceptances","INSERT INTO note_relations","INSERT INTO correction_vocabulary","INSERT INTO backup_imports")) {
                val failureName="backup-v2-failure.db";context.deleteDatabase(failureName)
                AndroidSql(context,failureName).use { raw ->
                    NoteRepository(raw).initialize();val before=snapshot(raw)
                    val fail=object: Sql by raw { override fun execute(statement: String,args: List<String>) { raw.execute(statement,args);if(statement.startsWith(prefix)) error("synthetic_write_failure") } }
                    val backup=BackupRepository(fail);val p=backup.preview(candidate)
                    try { backup.apply(p);fail("must fail") } catch(_: IllegalStateException) {}
                    assertEquals(before,snapshot(raw));assertEquals(1,raw.query("SELECT sql FROM sqlite_master WHERE name='relation_active_endpoints'").size)
                    failures+=mapOf("stage" to prefix,"beforeSha256" to BackupCodec.sha(StrictJson.encode(before)),"afterSha256" to BackupCodec.sha(StrictJson.encode(snapshot(raw))),"triggerRestored" to true)
                    BackupRepository(raw).apply(p)
                }
            }
            save("rollback-comparisons",failures)
            val malformed=listOf(bytes.copyOf(bytes.size-10),String(bytes).replace("\"schemaVersion\":2","\"schemaVersion\":99").toByteArray(),String(bytes).replace(export.hash,"0".repeat(64)).toByteArray())
            for(input in malformed) { try { BackupCodec.read(input.inputStream());fail("reject malformed") } catch(_: BackupFailure) {};assertEquals(original,snapshot(db)) }
            assertEquals(original,snapshot(db))
            save("result",mapOf("fullRoundTrip" to true,"calendarRows" to 9,"acceptedAiRows" to 2,"relations" to 1,"vocabulary" to 2,"disabledRestoredReminders" to true,"conflictOriginalPreserved" to true,"calendarAfterEmptyAndConflictRestoreNoWrites" to true,"idempotent" to true,"rollbackStages" to 5,"invalidFiles" to 3,"productSourceUnchanged" to true))
        }
    }
    @Test fun legacyPreferencesMigrateOnceAndRollbackOnRealAndroid() {
        val prefs=context.getSharedPreferences("voice-corrections",0)
        assertTrue(prefs.edit().putString("rows","[{\"from\":\"旧词\",\"to\":\"保留原词表\"}]").commit())
        AndroidSql(context).use { db ->
            NoteRepository(db).initialize();assertEquals(listOf(Correction("旧词","保留原词表")),VocabularyRepository(db).rows())
            VocabularyRepository(db).replace(VocabularyRepository(db).rows(),emptyList());NoteRepository(db).initialize();assertTrue(VocabularyRepository(db).rows().isEmpty())
            assertTrue(prefs.getString("rows","")!!.contains("保留原词表"))
            db.execute("DROP TABLE correction_vocabulary");db.execute("PRAGMA user_version=7")
            val before=db.query("SELECT * FROM notes")
            val fail=object: Sql by db { override fun execute(statement: String,args: List<String>) { db.execute(statement,args);if(statement=="DROP TABLE ai_acceptances_v7") error("synthetic_failure") } }
            try { NoteRepository(fail).initialize();fail("must fail") } catch(_: IllegalStateException) {}
            assertEquals("7",db.query("PRAGMA user_version").single().single());assertEquals(before,db.query("SELECT * FROM notes"))
            NoteRepository(db).initialize();assertEquals(listOf(Correction("旧词","保留原词表")),VocabularyRepository(db).rows())
            VocabularyRepository(db).replace(VocabularyRepository(db).rows(),emptyList())
            save("legacy-migration",mapOf("exactLegacyPreserved" to true,"preferencesInertAfterMigration" to true,"schemaTransactionRollback" to true,"retrySucceeded" to true))
        }
    }

    @Test fun oldFileOnAndroidPreservesNewFieldsAndUnknownV2FieldsReject() {
        AndroidSql(context).use { db ->
            val n=NoteRepository(db);n.initialize();val pair=Correction("本机词","保留词")
            VocabularyRepository(db).replace(emptyList(),listOf(pair))
            val old=ins.context.assets.open("backup/legacy-v1.thinkbackup.json").use(BackupCodec::read)
            assertEquals(1,old.schemaVersion);val backup=BackupRepository(db);backup.apply(backup.preview(old))
            assertEquals(listOf(pair),VocabularyRepository(db).rows());assertTrue(n.search("").total>0)
            val exported=backup.export();val raw=exported.payload.toString(Charsets.UTF_8).replace("\"vocabulary\":", "\"futureData\":[],\"vocabulary\":")
            val bytes=raw.toByteArray(Charsets.UTF_8);val bad=encoded(exported.copy(payload=bytes,hash=BackupCodec.sha(bytes)))
            val before=snapshot(db)
            try { BackupCodec.read(bad.inputStream());fail("unknown field must reject") } catch(e: BackupFailure) { assertEquals("unknown_or_missing_field",e.code) }
            assertEquals(before,snapshot(db));save("old-version",mapOf("schemaVersion" to old.schemaVersion,"notesRestored" to n.search("").total,"existingVocabularyUnchanged" to true,"unknownV2FieldRejected" to true,"failureAllRowsUnchanged" to true))
        }
    }

    @Test fun verifyMigrationAfterProcessRestart() {
        AndroidSql(context).use { db ->
            NoteRepository(db).initialize();assertEquals("8",db.query("PRAGMA user_version").single().single())
            assertTrue(VocabularyRepository(db).rows().isEmpty())
            assertTrue(context.getSharedPreferences("voice-corrections",0).getString("rows","")!!.contains("保留原词表"))
            save("migration-new-process",mapOf("newInstrumentationProcess" to true,"schema8" to true,"deletedVocabularyNotRevived" to true,"oldPreferencesStillPresentButInert" to true))
        }
    }
    @Test fun receiptSkipPropagationAndHashConflictsLeaveAllRowsUnchanged() {
        val data=BackupData(listOf(BackupNote(Note(id="receipt-note",body="备份原文",title="备份原文",created=1,updated=1),0)),emptyList(),emptyList(),emptyList())
        val original=BackupCodec.read(encoded(BackupCodec.prepare(data,1000,"receipt-original")).inputStream())
        AndroidSql(context).use { db ->
            val notes=NoteRepository(db);notes.initialize();notes.save(Editing(Note(id="receipt-note").bodyChanged("本机原文保留")))
            val backup=BackupRepository(db);val p=backup.preview(original,ConflictPolicy.SKIP);assertEquals(ImportAction.SKIP,p.records.single().action)
            backup.apply(p);val before=snapshot(db)
            val archive=BackupCodec.read(encoded(backup.export()).inputStream())
            val otherName="receipt-target.db";context.deleteDatabase(otherName)
            AndroidSql(context,otherName).use { other ->
                NoteRepository(other).initialize();val target=BackupRepository(other);target.apply(target.preview(archive));val unchanged=snapshot(other)
                assertTrue(target.preview(original).alreadyImported);assertTrue(target.apply(target.preview(original)).alreadyImported)
                assertEquals(unchanged,snapshot(other));assertEquals("本机原文保留",NoteRepository(other).find("receipt-note")!!.body)
            }
            val altered=BackupCodec.read(encoded(BackupCodec.prepare(data.copy(notes=data.notes.map { it.copy(note=it.note.copy(body="不同内容")) }),1000,"receipt-original")).inputStream())
            val receiptConflict=BackupCodec.read(encoded(BackupCodec.prepare(data.copy(receipts=listOf(BackupReceipt(original.exportId,"0".repeat(64),1))),1000,"another-receipt-file")).inputStream())
            for(file in listOf(altered,receiptConflict)) {
                try { backup.preview(file);fail("must reject") } catch(e: BackupFailure) { assertEquals("export_id_reused",e.code) }
                assertEquals(before,snapshot(db))
            }
            save("receipt-decisions",mapOf("skipDecisionPreservedAcrossExportRestore" to true,"reimportDoesNotClaimEveryRecordRestored" to true,"localOriginalPreserved" to true,"sameExportDifferentHashRejected" to true,"embeddedReceiptHashConflictRejected" to true,"beforeSha256" to BackupCodec.sha(StrictJson.encode(before)),"afterSha256" to BackupCodec.sha(StrictJson.encode(snapshot(db)))))
        }
    }

    @Test fun actualCategoryAndEdgeIdConflictsMapCopiesWithoutChangingOriginals() {
        AndroidSql(context).use { db ->
            val notes=NoteRepository(db);notes.initialize();val category=notes.createCategory("本机分类")
            notes.save(Editing(Note(id="category-note").bodyChanged("本机原文").categorized(category)))
            notes.save(Editing(Note(id="local-other").bodyChanged("本机关系另一端")))
            db.execute("INSERT INTO note_relations VALUES ('category-edge','category-note','local-other','manual',1)")
            val oldNotes=db.query("SELECT * FROM notes ORDER BY id");val oldCategory=db.query("SELECT * FROM categories").single();val oldEdge=db.query("SELECT * FROM note_relations").single()
            val importedCategory=category.copy(name="备份分类")
            val incoming=listOf("category-note","other").map { BackupNote(Note(id=it,body="备份原文 $it",title="备份标题",category=importedCategory.name,created=1,updated=1,categoryId=category.id),0) }
            val data=BackupData(incoming,emptyList(),listOf(BackupCategory(importedCategory,true)),emptyList(),relations=listOf(BackupRelation("category-edge","category-note","other","manual",2)))
            val candidate=BackupCodec.read(encoded(BackupCodec.prepare(data)).inputStream());val backup=BackupRepository(db);val preview=backup.preview(candidate)
            assertEquals(ImportAction.COPY,preview.categories.single().action);assertEquals(ImportAction.COPY,preview.relations.single().action)
            val copyId=preview.records.single { it.sourceId=="category-note" }.targetId!!;assertNotEquals("category-note",copyId)
            val categoryId=preview.categories.single().target!!.category.id;assertNotEquals(category.id,categoryId)
            backup.apply(preview)
            for(row in oldNotes) assertEquals(row,db.query("SELECT * FROM notes WHERE id=?",listOf(row[0])).single())
            assertEquals(oldCategory,db.query("SELECT * FROM categories WHERE id=?",listOf(category.id)).single())
            assertEquals(oldEdge,db.query("SELECT * FROM note_relations WHERE id='category-edge'").single())
            assertEquals(categoryId,notes.find(copyId)!!.categoryId);assertEquals(categoryId,notes.find("other")!!.categoryId)
            val edge=preview.relations.single().target!!;assertNotEquals("category-edge",edge.id);assertEquals(setOf(copyId,"other"),setOf(edge.a,edge.b))
            assertEquals(1,db.query("SELECT * FROM note_relations WHERE id=? AND a=? AND b=?",listOf(edge.id,edge.a,edge.b)).size)
            val beforeSkip=snapshot(db);val skip=backup.preview(candidate.copy(exportId="category-skip"),ConflictPolicy.SKIP)
            assertTrue(skip.records.all { it.action==ImportAction.SKIP });assertEquals(ImportAction.SKIP,skip.relations.single().action)
            backup.apply(skip);val after=snapshot(db);for(table in tables.filter { it!="backup_imports" }) assertEquals(beforeSkip[table],after[table])
            save("category-and-edge-conflicts",mapOf("localNotesCategoryAndEdgeUnchanged" to true,"independentCategoryId" to categoryId,"noteIdMapping" to preview.records.associate { it.sourceId to it.targetId },"edgeIdMapping" to mapOf("category-edge" to edge.id),"edgeEndpoints" to listOf(edge.a,edge.b),"skipDependenciesPreserved" to true,"rowsAfter" to after))
        }
    }
}
