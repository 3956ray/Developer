package com.example.thinkv2.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException

class BackupStorage(private val context: Context) {
    private val resolver get()=context.contentResolver
    fun read(uri: Uri): BackupCandidate=resolver.openInputStream(uri)?.use(BackupCodec::read) ?: throw IOException("cannot_read")
    fun write(uri: Uri,export: BackupExport): String=BackupWriter(object: BackupDocuments<Uri> {
        override fun name(handle: Uri): String?=resolver.query(handle,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null }
        override fun rename(handle: Uri,name: String)=DocumentsContract.renameDocument(resolver,handle,name)
        override fun openRead(handle: Uri)=resolver.openInputStream(handle)
        override fun openWrite(handle: Uri)=resolver.openOutputStream(handle,"wt")
        override fun delete(handle: Uri)=DocumentsContract.deleteDocument(resolver,handle)
    }).write(uri,export)
}
