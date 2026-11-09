package com.personal.ledger.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONObject

/** Reads a portable JSON file without retaining arbitrary file contents in error messages. */
object ImportFile {
    fun read(input: InputStream): JSONObject {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            require(out.size() + count <= 20 * 1024 * 1024) { "文件超过20MB，请缩小导出日期范围后重试" }
            out.write(chunk,0,count)
        }
        val bytes = out.toByteArray()
        require(!(bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte())) {
            "所选文件是Excel或压缩包，请选择可恢复备份JSON；微信或支付宝账单请先在电脑端核对"
        }
        val content = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        require(content.isNotEmpty()) { "所选文件为空，请重新导出可恢复备份JSON" }
        return try { JSONObject(content) } catch (_: org.json.JSONException) {
            throw IllegalArgumentException("无法解析所选文件，请选择可恢复备份JSON或电脑导出的补记JSON；文件可能损坏")
        }
    }
}
