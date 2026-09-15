package com.example.thinkv2.backup

import java.io.*

interface BackupDocuments<H> {
    fun name(handle: H): String?
    fun rename(handle: H,name: String): H?
    fun openRead(handle: H): InputStream?
    fun openWrite(handle: H): OutputStream?
    fun delete(handle: H): Boolean
}
class BackupWriteFailure(val removedPartial: Boolean,val phase: String="unknown",val exceptionType: String=""): IOException("backup_write_failed")
/** The handle must be the new document returned by this export's ACTION_CREATE_DOCUMENT. */
class BackupWriter<H>(private val documents: BackupDocuments<H>) {
    private val marker="未完成-"
    fun write(created: H,export: BackupExport): String {
        var current=created
        var phase="name"
        try {
            val selected=documents.name(current) ?: throw IOException("cannot_name")
            // User-edited names and provider-normalized extensions must be checked before any bytes.
            phase="mark_partial"
            if(!selected.startsWith(marker)) current=documents.rename(current,"$marker$selected") ?: throw IOException("cannot_mark_partial")
            phase="verify_partial_name"
            val partial=documents.name(current) ?: throw IOException("cannot_name")
            if(!partial.startsWith(marker)) throw IOException("provider_did_not_mark_partial")
            phase="write_close"
            documents.openWrite(current)?.use { BackupCodec.write(export,it) } ?: throw IOException("cannot_write")
            phase="read_verify"
            val verified=documents.openRead(current)?.use(BackupCodec::read) ?: throw IOException("cannot_verify")
            requireBackup(verified.exportId==export.exportId && verified.hash==export.hash,"write_verification")
            var clean=partial
            while(clean.startsWith(marker)) clean=clean.removePrefix(marker)
            clean=clean.removeSuffix(".partial")
            val desired=if(clean.endsWith(".thinkbackup.json")) clean else "$clean.thinkbackup.json"
            phase="finalize"
            current=documents.rename(current,desired) ?: throw IOException("cannot_finalize")
            val finalName=documents.name(current) ?: throw IOException("cannot_name")
            val uniqueName=Regex(Regex.escape(desired.removeSuffix(".json"))+" \\([1-9][0-9]*\\)\\.json")
            if(finalName.startsWith(marker) || !(finalName.endsWith(".thinkbackup.json") || uniqueName.matches(finalName))) throw IOException("provider_did_not_finalize")
            return finalName
        } catch(error: Exception) {
            val known=runCatching { documents.name(current)!=null }.getOrDefault(false)
            val deleted=runCatching { documents.delete(current) }.getOrDefault(false)
            val absent=runCatching { documents.name(current)==null }.getOrDefault(false)
            throw BackupWriteFailure(known && deleted && absent,phase,error.javaClass.simpleName)
        }
    }
}
