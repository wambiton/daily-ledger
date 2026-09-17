package com.personal.ledger.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local book references are independent of an online login. No entries are moved. */
class PhoneLedgers(private val context: Context, namespace: String) {
    private val vault = AccountVault(context,"$namespace-books")
    fun books(): List<JSONObject> {
        val rows = vault.read()?.optJSONArray("books") ?: JSONArray()
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { valid(it) && context.getDatabasePath(it.getString("database")).exists() }
    }
    private fun valid(book: JSONObject): Boolean {
        val name=book.optString("database")
        return name.isNotEmpty() && name == java.io.File(name).name && !name.contains('/') && !name.contains('\\') && book.optString("owner").isNotEmpty()
    }
    fun remember(book: JSONObject) {
        require(valid(book))
        val old=vault.read() ?: JSONObject()
        val retained=books().filterNot { it.getString("database")==book.getString("database") && it.getString("owner")==book.getString("owner") }
        old.put("books",JSONArray(retained+book))
        vault.write(old)
    }
    fun selected(): JSONObject? = vault.read()?.optJSONObject("selected")?.takeIf { valid(it) && context.getDatabasePath(it.getString("database")).exists() }
    fun select(book: JSONObject?) {
        if(book != null) remember(book)
        val state=vault.read() ?: JSONObject()
        if(book == null) state.remove("selected") else state.put("selected",book)
        vault.write(state)
    }
    fun descriptor(account: JSONObject): JSONObject = JSONObject()
        .put("database",ComputerPair.databaseName(account)).put("owner",account.getJSONObject("user").getString("id"))
        .put("label",account.getJSONObject("user").getString("email"))
}
