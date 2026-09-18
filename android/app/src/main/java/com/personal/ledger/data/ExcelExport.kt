package com.personal.ledger.data

import com.personal.ledger.domain.Rules
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small OOXML workbook: user text is always an inline string, never a formula. */
object ExcelExport {
    fun write(output: OutputStream, snapshot: PortableSnapshot) {
        fun escape(text: String) = buildString {
            text.codePoints().forEach { cp ->
                when(cp) {
                    38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;"); 34 -> append("&quot;")
                    9,10,13 -> appendCodePoint(cp)
                    else -> if(cp in 32..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF) appendCodePoint(cp)
                }
            }
        }
        ZipOutputStream(output).use { zip ->
            fun part(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            part("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            part("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            part("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="账目" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            part("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            part("xl/styles.xml", """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf xfId="0"/><xf numFmtId="2" applyNumberFormat="1" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            val categories = snapshot.categories.associateBy { it.id }
            val sheet = buildString {
                append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><cols><col min=\"1\" max=\"4\" width=\"18\" customWidth=\"1\"/><col min=\"5\" max=\"5\" width=\"50\" customWidth=\"1\"/></cols><sheetData>")
                fun row(index: Int, values: List<String>, numeric: Boolean) {
                    append("<row r=\"$index\">")
                    values.forEachIndexed { col, value ->
                        val ref = "${('A'.code + col).toChar()}$index"
                        if(numeric && col == 3) append("<c r=\"$ref\" s=\"1\"><v>$value</v></c>")
                        else append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>")
                    }
                    append("</row>")
                }
                row(1,listOf("日期","收支","分类","金额（人民币元）","备注"),false)
                snapshot.entries.filter { it.deletedAt == null }.sortedWith(compareBy<Entry> { it.date }.thenBy { it.createdAt }).forEachIndexed { i, e ->
                    row(i+2,listOf(e.date,if(e.type=="expense") "支出" else "收入",categories.getValue(e.categoryId).name,Rules.money(e.amountFen),e.note),true)
                }
                append("</sheetData></worksheet>")
            }
            part("xl/worksheets/sheet1.xml",sheet)
        }
    }
}
