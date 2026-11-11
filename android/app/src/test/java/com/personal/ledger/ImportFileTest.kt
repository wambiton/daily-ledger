package com.personal.ledger

import com.personal.ledger.data.ImportFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ImportFileTest {
    @Test fun rejectsTrailingDocumentsAndBrokenEncoding() {
        for (text in listOf("{}{}", "{} trailing", "[]", "{}\u0000{}")) {
            val failure = runCatching { ImportFile.read(text.byteInputStream()) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("无法解析"))
        }
        val broken = byteArrayOf(0x7b,0x22,0x61,0x22,0x3a,0x22,0xc3.toByte(),0x28,0x22,0x7d)
        assertTrue(runCatching {ImportFile.read(broken.inputStream())}.exceptionOrNull()!!.message!!.contains("UTF-8"))
    }
    @Test fun acceptsExactLimitAndRejectsOneExtraByte() {
        val bytes = ByteArray(20*1024*1024) {32}
        bytes[0]=123;bytes[1]=125
        assertEquals(0,ImportFile.read(bytes.inputStream()).length())
        val larger = java.io.SequenceInputStream(bytes.inputStream(),byteArrayOf(32).inputStream())
        val failure = runCatching {larger.use {ImportFile.read(it)}}.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("20MB"))
    }
    @Test fun acceptsBomAndRejectsWrongFileWithoutEchoingContents() {
        assertEquals(2,ImportFile.read("\uFEFF {\"format_version\":2}".byteInputStream()).getInt("format_version"))
        for ((text, hint) in listOf("  " to "文件为空", "PK\u0003\u0004synthetic" to "Excel或压缩包", "private-fixture," to "无法解析")) {
            val failure = runCatching {ImportFile.read(text.byteInputStream())}.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains(hint))
            assertFalse(failure.message!!.contains("private-fixture"))
        }
    }
}
