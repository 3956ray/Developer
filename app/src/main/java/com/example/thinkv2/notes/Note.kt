package com.example.thinkv2.notes

import java.util.UUID

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val body: String = "",
    val title: String = "",
    val manualTitle: Boolean = false,
    val category: String = "",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = created,
    val revision: Long = 0,
    val categoryId: String = "",
    val categorySource: String = "manual",
) {
    fun bodyChanged(text: String) = copy(body = text,
        title = if (manualTitle) title else automaticTitle(text), revision = revision + 1)
    fun titleChanged(text: String) = copy(title = if (text.isBlank()) automaticTitle(body) else text,
        manualTitle = text.isNotBlank(), revision = revision + 1)
    fun categoryChanged(text: String) = copy(category = text, categoryId = "", categorySource = "manual", revision = revision + 1)
    fun categorized(category: Category?) = copy(category = category?.name.orEmpty(),
        categoryId = category?.id.orEmpty(), categorySource = "manual", revision = revision + 1)
}

data class Category(val id: String, val name: String, val revision: Long = 0)
data class TrashItem(val note: Note, val deletedAt: Long, val categoryMissing: Boolean, val hasDraft: Boolean = false)
data class RestoreResult(val note: Note, val categoryMissing: Boolean)

fun automaticTitle(body: String): String {
    val first = body.trim().split('\n', '\r', '。', '！', '？', '.', '!', '?')
        .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (first.codePointCount(0, first.length) <= 24) first
    else first.substring(0, first.offsetByCodePoints(0, 24)) + "…"
}

data class Editing(val note: Note, val baseRevision: Long = -1)
data class SearchPage(val notes: List<Note>, val total: Int)

/** Return original text near a literal match, without splitting UTF-16 surrogate pairs. */
fun matchingExcerpt(body: String,query: String): String {
    val matches=query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .map { body.indexOf(it,ignoreCase=true) }.filter { it>=0 }
    val hit=matches.minOrNull() ?: 0
    val before=body.codePointCount(0,hit)
    val startPoint=maxOf(0,before-24)
    val total=body.codePointCount(0,body.length)
    val endPoint=minOf(total,startPoint+120)
    val start=body.offsetByCodePoints(0,startPoint);val end=body.offsetByCodePoints(0,endPoint)
    return (if(start>0) "…" else "")+body.substring(start,end)+(if(end<body.length) "…" else "")
}

interface Sql : AutoCloseable {
    fun execute(statement: String, args: List<String> = emptyList())
    fun query(statement: String, args: List<String> = emptyList()): List<List<String>>
    fun begin()
    fun commit()
    fun rollback()
}
