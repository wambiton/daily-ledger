package com.personal.ledger.data

import android.content.Context
import androidx.room.Room

/** Share one Room instance so restores invalidate the active ledger immediately. */
object LedgerDatabases {
    private data class Open(val db: LedgerDatabase, var references: Int)
    private val open = mutableMapOf<String, Open>()
    @Synchronized fun acquire(context: Context, name: String): LedgerDatabase {
        val existing = open[name]
        if(existing != null) { existing.references++; return existing.db }
        return Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name).build().also { open[name] = Open(it, 1) }
    }
    @Synchronized fun release(name: String) {
        val existing = open[name] ?: return
        existing.references--
        if(existing.references == 0) { open.remove(name); existing.db.close() }
    }
}
