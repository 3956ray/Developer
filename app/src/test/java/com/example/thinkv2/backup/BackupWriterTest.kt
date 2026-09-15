package com.example.thinkv2.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class BackupWriterTest {
    private class Documents: BackupDocuments<String> {
        var unreadableRename=false;var ignoreFinal=false;var uniqueFinal=false;var enforceJson=false;var label="用户改名.thinkbackup.json";var bytes=byteArrayOf();var failWrite=false;var failRename=false;var ignoreRename=false;var failClose=false;var corrupt=false;var writes=0
        val operations=mutableListOf<String>();var deleted: String?=null
        override fun name(handle: String)=if(handle==deleted || (unreadableRename && handle.endsWith("-renamed"))) null else label
        override fun rename(handle: String,name: String): String? { operations+="rename:$name";if(failRename) return null;if(!ignoreRename && !(ignoreFinal && !name.startsWith("未完成-"))) label=when { uniqueFinal && !name.startsWith("未完成-") -> name.removeSuffix(".json")+" (1).json";enforceJson && !name.endsWith(".json") -> "$name.json";else -> name };return handle+"-renamed" }
        override fun openRead(handle: String)=if(corrupt) byteArrayOf(1,2).inputStream() else bytes.inputStream()
        override fun openWrite(handle: String): OutputStream {
            operations+="write:$label";writes++
            return object: ByteArrayOutputStream() {
                override fun write(b: ByteArray,off: Int,len: Int) { if(failWrite) { super.write(b,off,minOf(len,3));throw IOException("ENOSPC"); };super.write(b,off,len) }
                override fun close() { bytes=toByteArray();if(failClose) throw IOException("close failed") }
            }
        }
        override fun delete(handle: String): Boolean { deleted=handle;operations+="delete";return true }
    }
    private fun export()=BackupCodec.prepare(BackupData(emptyList(),emptyList(),emptyList(),emptyList()))
    @Test fun changedFilenameIsMarkedBeforeWriteAndFinalizedOnlyAfterVerification() {
        val d=Documents();val name=BackupWriter(d).write("created",export())
        assertEquals("用户改名.thinkbackup.json",name)
        assertEquals("rename:未完成-用户改名.thinkbackup.json",d.operations[0]);assertEquals("write:未完成-用户改名.thinkbackup.json",d.operations[1]);assertNull(d.deleted)
    }
    @Test fun refusedOrIgnoredInitialRenameWritesNoBytes() {
        for(ignore in listOf(false,true)) {
            val d=Documents().apply { failRename=!ignore;ignoreRename=ignore }
            try { BackupWriter(d).write("created",export());fail("must fail") } catch(e: BackupWriteFailure) { assertTrue(e.removedPartial) }
            assertEquals(0,d.writes);assertTrue(d.deleted!!.startsWith("created"))
        }
    }
    @Test fun partialSpaceFailureCloseFailureAndCorruptionNeverFinalize() {
        for(failure in 0..2) {
            val d=Documents().apply { failWrite=failure==0;failClose=failure==1;corrupt=failure==2 }
            try { BackupWriter(d).write("created",export());fail("must fail") } catch(e: BackupWriteFailure) { assertTrue(e.removedPartial) }
            assertTrue(d.label.startsWith("未完成-"));assertEquals("created-renamed",d.deleted)
            assertEquals(1,d.operations.count { it.startsWith("rename:") })
        }
    }
    @Test fun jsonProviderExtensionNormalizationDoesNotEraseIncompleteMarker() {
        val d=Documents().apply { enforceJson=true }
        assertEquals("用户改名.thinkbackup.json",BackupWriter(d).write("created",export()))
        assertEquals("write:未完成-用户改名.thinkbackup.json",d.operations[1])
    }

    @Test fun ignoredFinalRenameCannotReportSuccess() {
        val d=Documents().apply { ignoreFinal=true }
        try { BackupWriter(d).write("created",export());fail("must fail") } catch(e: BackupWriteFailure) { assertEquals("finalize",e.phase);assertTrue(e.removedPartial) }
        assertTrue(d.label.startsWith("未完成-"));assertNotNull(d.deleted)
    }
    @Test fun repeatedMarkersAreRemovedAndProviderUniqueNameIsAccepted() {
        val d=Documents().apply { label="未完成-未完成-用户改名.thinkbackup.json";uniqueFinal=true }
        assertEquals("用户改名.thinkbackup (1).json",BackupWriter(d).write("created",export()))
        assertFalse(d.label.startsWith("未完成-"));assertNull(d.deleted)
    }

    @Test fun unreadableRenameHandleCannotProveCleanup() {
        val d=Documents().apply { unreadableRename=true }
        try { BackupWriter(d).write("created",export());fail("must fail") } catch(e: BackupWriteFailure) { assertFalse(e.removedPartial);assertEquals("verify_partial_name",e.phase) }
        assertEquals(0,d.writes)
    }

}
