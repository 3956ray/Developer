package com.example.thinkv2.backup

import com.example.thinkv2.notes.*
import com.example.thinkv2.reminders.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.Base64

class BackupCodecTest {
    private val n=Note("note-1","中文正文。\n含引号\"与emoji🙂","中文正文",false,"已删除分类",100,200,3,"category-1","manual")
    private fun data()=BackupData(listOf(BackupNote(n,300)),listOf(BackupDraft(Editing(n.copy(body="不同草稿🙂",revision=4),3),true),BackupDraft(Editing(Note("draft-only",body="只有草稿",title="只有草稿",created=100,updated=100,revision=1)) ,true)),
        listOf(BackupCategory(Category("category-1","已删除分类",7),false)),listOf(BackupReminder("reminder-1",n.id,3,ReminderRule(Repeat.WEEKLY,LocalDay.parse("2026-09-16"),9,0,17),false)))
    private fun bytes(d: BackupData=data()): ByteArray=ByteArrayOutputStream().also { BackupCodec.write(BackupCodec.prepare(d,1000,"export-1"),it) }.toByteArray()
    private fun rejected(expected: String?=null,action: ()->Unit) {
        try { action();fail("input must be rejected") } catch(e: BackupFailure) { if(expected!=null) assertEquals(expected,e.code) }
    }
    private fun envelope(payload: ByteArray,version: Int=2,id: String="export-1",hash: String=BackupCodec.sha(payload)): ByteArray {
        return """{"format":"thinkV2-backup","schemaVersion":$version,"exportId":"$id","createdAtUTC":"2026-09-16T00:00:00.000Z","payloadBytes":${payload.size},"payloadSha256":"$hash","payload":"${Base64.getEncoder().encodeToString(payload)}"}""".toByteArray()
    }
    @Test fun roundTripPreservesDraftOnlyTrashCategoryTombstoneAndUnicode() {
        val original=data();val encoded=bytes(original);val loaded=BackupCodec.read(encoded.inputStream())
        assertEquals(original,loaded.data);assertEquals("export-1",loaded.exportId)
        assertFalse(String(encoded).contains("中文正文"));assertTrue(String(encoded).contains("payload")) // Encoding is not encryption.
    }
    @Test fun exactPayloadBytesIncludingWhitespaceDetermineChecksum() {
        val raw=BackupCodec.payload(data());val padded=byteArrayOf(32)+raw+byteArrayOf(10)
        assertEquals(data(),BackupCodec.read(envelope(padded).inputStream()).data)
        rejected("payload_hash") { BackupCodec.read(envelope(padded,hash=BackupCodec.sha(raw)).inputStream()) }
    }
    @Test fun unknownVersionUnknownFieldsAndDuplicateKeysReject() {
        val payload=BackupCodec.payload(data());rejected("unsupported_version") { BackupCodec.read(envelope(payload,3).inputStream()) }
        val unknown=String(bytes()).replace("{\"format\"","{\"future\":true,\"format\"")
        rejected("unknown_or_missing_field") { BackupCodec.read(unknown.byteInputStream()) }
        rejected("duplicate_key") { StrictJson("{\"a\":1,\"a\":2}".byteInputStream(),100).parse() }
    }
    @Test fun truncatedAndTrailingAndWrongTypesReject() {
        val input=bytes();rejected { BackupCodec.read(input.copyOf(input.size-4).inputStream()) }
        rejected { BackupCodec.read((String(input)+"{}").byteInputStream()) }
        rejected("invalid_type") { BackupCodec.read(String(input).replace("\"schemaVersion\":2","\"schemaVersion\":true").byteInputStream()) }
        for(number in listOf("01","1.0","1e0","9223372036854775808")) rejected { StrictJson(number.byteInputStream(),100).parse() }
    }
    @Test fun malformedUtf8AndLoneSurrogatesReject() {
        rejected("invalid_utf8") { BackupCodec.read(byteArrayOf(123,34,0xc0.toByte(),0xaf.toByte(),34,58,48,125).inputStream()) }
        for(s in listOf("\"\\uD800\"","\"\\uDC00\"","\"\\uD800x\"")) rejected { StrictJson(s.byteInputStream(),100).parse() }
        rejected { StrictJson("\"\\u１２３４\"".byteInputStream(),100).parse() }
        rejected { StrictJson("\"\\uＦＦＦＦ\"".byteInputStream(),100).parse() }
        assertEquals("🙂",StrictJson("\"\\uD83D\\uDE42\"".byteInputStream(),100).parse())
    }
    @Test fun strictBase64RejectsNoncanonicalAndBadPadding() {
        for(s in listOf("A===","AA=A","AB==","AAA","AA==AA==","A A=")) {
            rejected { StrictJson(("{\"payload\":\""+s+"\"}").byteInputStream(),100,true).parse() }
        }
    }
    @Test fun depthAndStreamingByteLimitsStopParsing() {
        rejected("structure_limit") { StrictJson(("[".repeat(34)+"0"+"]".repeat(34)).byteInputStream(),1000).parse() }
        rejected("size_limit") { StrictJson("[0,0,0,0,0]".byteInputStream(),5).parse() }
        rejected("size_limit") { StrictJson.encode(listOf("long string"),3) }
    }
    @Test fun duplicateIdsDanglingReferencesAndInvalidWeeklyRulesReject() {
        val d=data();rejected("duplicate_id") { BackupCodec.prepare(d.copy(notes=d.notes+d.notes)) }
        rejected("dangling_category") { BackupCodec.prepare(d.copy(categories=emptyList())) }
        rejected("dangling_reminder") { BackupCodec.prepare(d.copy(reminders=d.reminders.map { it.copy(noteId="absent") })) }
        rejected("invalid_reminder") { BackupCodec.prepare(d.copy(reminders=d.reminders.map { it.copy(rule=it.rule.copy(weekdays=0)) })) }
        rejected("draft_base_revision") { BackupCodec.prepare(d.copy(drafts=d.drafts.map { it.copy(editing=it.editing.copy(baseRevision=99)) })) }
    }
    @Test fun emptyFormalBodyCountLimitsAndUnsupportedRelationsReject() {
        rejected("empty_note") { BackupCodec.prepare(data().copy(notes=listOf(BackupNote(n.copy(body="  "),300)))) }
        val notes=(0..10000).map { BackupNote(n.copy(id="n-$it",category="",categoryId=""),0) }
        rejected("count_limit") { BackupCodec.prepare(BackupData(notes,emptyList(),emptyList(),emptyList())) }
        val raw=String(BackupCodec.payload(data())).replace("\"relations\":[]","\"relations\":[{\"from\":\"missing\"}]")
        rejected("unknown_or_missing_field") { BackupCodec.read(envelope(raw.toByteArray()).inputStream()) }
    }
    @Test fun partialOutputFailurePropagatesWithoutSuccessfulExport() {
        val output=object: OutputStream() { override fun write(b: Int) { throw IOException("synthetic write failure") } }
        try { BackupCodec.write(BackupCodec.prepare(data()),output);fail("must fail") } catch(_: IOException) {}
    }
}
