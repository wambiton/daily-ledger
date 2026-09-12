package com.personal.ledger.data

import com.personal.ledger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.io.ByteArrayOutputStream

class BackupApiException(val status: Int, message: String): Exception(message)

class BackupClient(server: String, private val pairKey: String? = null) {
    val base = normalize(server)
    suspend fun call(path: String, body: JSONObject? = null, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI("$base/api/v1/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if(body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            pairKey?.let { connection.setRequestProperty("X-Ledger-Pair", it) }
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if(body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 20 * 1024 * 1024) { "备份超过20MB限制" }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if(status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while(true) {
                    val count = input.read(buffer)
                    if(count < 0) break
                    require(output.size() + count <= 20 * 1024 * 1024) { "服务器响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            require(bytes.size <= 20 * 1024 * 1024) { "服务器响应过大" }
            val result = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if(status !in 200..299) throw BackupApiException(status, result?.optJSONObject("error")?.optString("message") ?: "电脑服务返回错误（$status）")
            if(status == 204) JSONObject() else requireNotNull(result) { "电脑服务响应格式错误" }
        } finally { connection.disconnect() }
    }
    companion object {
        fun normalize(value: String): String {
            val uri = URI(value.trim().trimEnd('/'))
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) { "请输入完整的服务器地址，不含路径" }
            require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http" && uri.host == "127.0.0.1")) { "外网服务器必须使用HTTPS" }
            return "${uri.scheme}://${uri.rawAuthority.lowercase()}"
        }
    }
}
