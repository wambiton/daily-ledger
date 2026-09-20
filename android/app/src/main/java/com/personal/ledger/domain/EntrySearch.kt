package com.personal.ledger.domain

import com.personal.ledger.data.Entry

data class EntrySearch(
    val keyword: String = "",
    val from: String = "",
    val to: String = "",
    val type: String = "",
    val categoryId: String = "",
) {
    val valid: Boolean get() = from.isEmpty() || to.isEmpty() || from <= to

    fun apply(entries: List<Entry>, owner: String): List<Entry> {
        if (!valid) return emptyList()
        return entries.filter {
            it.owner == owner && it.deletedAt == null &&
                it.note.contains(keyword.trim(), ignoreCase = true) &&
                (from.isEmpty() || it.date >= from) && (to.isEmpty() || it.date <= to) &&
                (type.isEmpty() || it.type == type) && (categoryId.isEmpty() || it.categoryId == categoryId)
        }.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.createdAt }.thenBy { it.id })
    }
}
