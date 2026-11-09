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
