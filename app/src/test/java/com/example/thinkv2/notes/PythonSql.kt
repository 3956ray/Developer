package com.example.thinkv2.notes

import java.io.File
import java.util.Base64

/** Test-only bridge: production SQL runs against a real temporary on-disk SQLite database. */
class PythonSql(path: File) : Sql {
    private val process=ProcessBuilder("/usr/bin/python3","-u","-c",SCRIPT,path.absolutePath).start()
    private val input=process.outputStream.bufferedWriter()
    private val output=process.inputStream.bufferedReader()
    private fun encode(s: String)=Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun decode(s: String)=String(Base64.getDecoder().decode(s),Charsets.UTF_8)
    private fun request(sql: String,args: List<String>): List<List<String>> {
        input.write((listOf(sql)+args).joinToString("\t",transform=::encode)); input.newLine(); input.flush()
        val count=output.readLine() ?: error("sqlite_bridge_terminated")
        check(!count.startsWith("ERR")) { count }
        return List(count.toInt()) { output.readLine().split('\t').map(::decode) }
    }
    override fun execute(statement: String,args: List<String>) { request(statement,args) }
    override fun query(statement: String,args: List<String>)=request(statement,args)
    override fun begin() { execute("BEGIN IMMEDIATE") }
    override fun commit() { execute("COMMIT") }
    override fun rollback() { execute("ROLLBACK") }
    override fun close() { input.close(); process.waitFor(); output.close() }
    companion object {
        private val SCRIPT="""
import sys,sqlite3,base64
c=sqlite3.connect(sys.argv[1],isolation_level=None)
for line in sys.stdin:
    try:
        parts=[base64.b64decode(x).decode('utf-8') for x in line.rstrip('\n').split('\t')]
        cursor=c.execute(parts[0],parts[1:])
        rows=cursor.fetchall()
        print(len(rows))
        for row in rows:
            print('\t'.join(base64.b64encode(str(v).encode('utf-8')).decode('ascii') for v in row))
    except Exception as e:
        print('ERR:'+type(e).__name__)
c.close()
""".trimIndent()
    }
}
