package com.personal.ledger.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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
        val content = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                .removePrefix("\uFEFF").trim()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("文件编码不是有效UTF-8，请重新导出可恢复备份JSON")
        }
        require(content.isNotEmpty()) { "所选文件为空，请重新导出可恢复备份JSON" }
        return try {
            if ('\u0000' in content) throw org.json.JSONException("Invalid document")
            val reader = JSONTokener(content)
            val value = reader.nextValue()
            if (value !is JSONObject || reader.nextClean() != '\u0000') throw org.json.JSONException("Invalid document")
            value
        } catch (_: org.json.JSONException) {
            throw IllegalArgumentException("无法解析所选文件，请选择可恢复备份JSON或电脑导出的补记JSON；文件可能损坏")
        }
    }
}
