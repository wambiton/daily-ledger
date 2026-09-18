package com.personal.ledger.data

import com.personal.ledger.domain.Rules
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.util.UUID

/** Parse the entire file before any write. No credentials or database filenames are accepted. */
data class PortableSnapshot(val categories: List<Category>, val entries: List<Entry>, val sources: List<String>) {
    companion object {
        fun parse(payload: JSONObject, owner: String): PortableSnapshot {
            fun id(value: String): String = value.also { require(UUID.fromString(it).toString() == it) }
            fun number(o: JSONObject, key: String): Long {
                val v = o.get(key)
                require(v is Long || v is Int) { "备份中的数字格式不正确" }
                return (v as Number).toLong()
            }
            fun keys(o: JSONObject, allowed: Set<String>, required: Set<String> = allowed) {
                val actual = o.keys().asSequence().toSet()
                require(actual.containsAll(required) && allowed.containsAll(actual)) { "备份字段不正确" }
            }
            keys(payload,setOf("format_version","categories","entries","import_sources"),setOf("format_version","categories","entries"))
            val version = number(payload,"format_version")
            require(version == 1L || version == 2L) { "备份版本不支持，请更新软件" }
            val cats = payload.getJSONArray("categories"); val items = payload.getJSONArray("entries")
            require(cats.length() <= 2000 && items.length() <= 50000) { "备份记录过多" }
            val categories = (0 until cats.length()).map { i ->
                val c = cats.getJSONObject(i)
                keys(c,setOf("id","type","name","default_key"))
                val type = c.getString("type"); require(type in listOf("expense","income"))
                val default = if(c.isNull("default_key")) null else c.getString("default_key")
                require(default == null || default in (0 until if(type == "expense") 7 else 5).map { "$type-$it" })
                Category(id(c.getString("id")),owner,type,Rules.category(c.getString("name")),default)
            }
            require(categories.map { it.id }.distinct().size == categories.size)
            require(categories.map { it.type to it.name }.distinct().size == categories.size)
            val byId = categories.associateBy { it.id }
            val entries = (0 until items.length()).map { i ->
                val e = items.getJSONObject(i)
                val fields = setOf("id","type","category_id","amount_fen","date","note","created_at","updated_at")
                keys(e,if(version == 2L) fields + "deleted_at" else fields)
                val c = requireNotNull(byId[e.getString("category_id")]) { "备份缺少分类" }
                require(e.getString("type") == c.type)
                val amount = number(e,"amount_fen"); require(amount in 1..99999999999L)
                val day = LocalDate.parse(e.getString("date")); Rules.date(day)
                require(day.year >= 1900 && day.toString() == e.getString("date"))
                val note = e.getString("note"); Rules.note(note)
                val created = number(e,"created_at"); val updated = number(e,"updated_at")
                require(created in 0..9999999999999L && updated in created..9999999999999L)
                val deleted = if(version == 1L || e.isNull("deleted_at")) null else number(e,"deleted_at")
                require(deleted == null || deleted in created..updated)
                Entry(id(e.getString("id")),owner,c.type,c.id,amount,day.toString(),note,created,updated,deletedAt=deleted)
            }
            require(entries.map { it.id }.distinct().size == entries.size)
            val sourceArray = if(payload.has("import_sources")) payload.getJSONArray("import_sources") else JSONArray()
            require(sourceArray.length() <= 50000)
            val sources = (0 until sourceArray.length()).map { id(sourceArray.getString(it)) }
            return PortableSnapshot(categories,entries,sources)
        }
    }
    fun retained(now: Long) = entries.filter { it.deletedAt == null || it.deletedAt > now - LedgerRepository.RETENTION_MS }
}
