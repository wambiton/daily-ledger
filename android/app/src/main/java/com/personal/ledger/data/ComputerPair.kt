package com.personal.ledger.data

import org.json.JSONObject
import java.util.UUID

object ComputerPair {
    fun parse(text: String): JSONObject {
        require(text.length <= 2048) { "二维码内容过长" }
        val value = try { JSONObject(text) } catch(_: Exception) { throw IllegalArgumentException("请扫描电脑备份程序中的二维码") }
        require(value.optString("type") == "personal-ledger-pair" && value.optInt("version") == 1) { "不是电脑备份配对二维码" }
        val server = BackupClient.normalize(value.getString("server"))
        require(server.startsWith("https://")) { "配对地址必须使用HTTPS" }
        val id = value.getString("computer_id")
        require(UUID.fromString(id).toString() == id) { "电脑身份无效" }
        require(value.getString("pair_key").matches(Regex("[A-Za-z0-9_-]{43,128}"))) { "配对信息无效" }
        return JSONObject().put("server",server).put("computer_id",id).put("pair_key",value.getString("pair_key"))
    }
    fun client(account: JSONObject) = BackupClient(account.getString("server"),account.optString("pair_key").takeIf { it.isNotEmpty() })
    fun databaseName(account: JSONObject): String = account.optString("database_name").takeIf { it.matches(Regex("account-[a-f0-9]{64}\\.db")) }
        ?: "account-${AccountVault.databaseKey(account.optString("computer_id").ifEmpty { account.getString("server") },account.getJSONObject("user").getString("id"))}.db"
}
