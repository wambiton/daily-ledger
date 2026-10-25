package com.personal.ledger.data

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** A historical snapshot is a new offline book, never an account's replacement. */
object HistoryRecovery {
    data class Summary(val normal: Int, val trash: Int, val expired: Int, val budgets: Int,
        val dateFrom: String?, val dateTo: String?)

    fun preview(payload: JSONObject, now: Long = System.currentTimeMillis()): Summary {
        val parsed = PortableSnapshot.parse(payload, "history-preview")
        val retained = parsed.retained(now)
        val normal = retained.filter { it.deletedAt == null }
        return Summary(normal.size, retained.size - normal.size, parsed.entries.size - retained.size,
            parsed.budgets.size, normal.minOfOrNull { it.date }, normal.maxOfOrNull { it.date })
    }

    suspend fun create(context: Context, payload: JSONObject, revision: Long,
                       label: String): JSONObject {
        require(revision > 0)
        // Validate before creating a database; callers cannot supply a destination filename/owner.
        preview(payload)
        val id = UUID.randomUUID().toString()
        val name = "history-$id.db"
        val owner = "history-$id"
        check(!context.getDatabasePath(name).exists()) { "账本名称冲突，请重试" }
        val book = JSONObject().put("database", name).put("owner", owner)
            .put("label", "$label · 历史第${revision}版")
        val db = LedgerDatabases.acquire(context, name)
        try {
            // Cursor zero: this independent copy has never been uploaded as a book.
            BackupStore(db, owner).restore(payload, 0, requireEmpty = true)
        } finally { LedgerDatabases.release(name) }
        return book
    }
}
