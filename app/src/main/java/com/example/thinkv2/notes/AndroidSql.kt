package com.example.thinkv2.notes

import android.content.Context

class AndroidSql(context: Context,databaseName: String = "thinkv2-notes.db") : Sql {
    private val db = context.openOrCreateDatabase(databaseName,Context.MODE_PRIVATE,null)
    override fun execute(statement: String,args: List<String>) { db.execSQL(statement,args.toTypedArray()) }
    override fun query(statement: String,args: List<String>): List<List<String>> =
        db.rawQuery(statement,args.toTypedArray()).use { cursor -> buildList {
            while(cursor.moveToNext()) add(List(cursor.columnCount) { cursor.getString(it) })
        } }
    override fun begin() = db.beginTransaction()
    override fun commit() { db.setTransactionSuccessful(); db.endTransaction() }
    override fun rollback() { if(db.inTransaction()) db.endTransaction() }
    override fun close() = db.close()
}
