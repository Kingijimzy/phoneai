package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OrionOfficeExtractorTest {
    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> parts.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun docxParagraphsAreExtracted() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space=\"preserve\"> world</w:t></w:r></w:p><w:p><w:r><w:t>Tom &amp; Jerry</w:t></w:r></w:p></w:body></w:document>"
        assertEquals("Hello world\nTom & Jerry", OrionOfficeExtractor.extract("docx", zip("word/document.xml" to xml)))
    }

    @Test fun pptxSlidesAreOrderedNumerically() {
        val s = { t: String -> "<p:sld><a:p><a:r><a:t>$t</a:t></a:r></a:p></p:sld>" }
        val text = OrionOfficeExtractor.extract("pptx", zip("ppt/slides/slide10.xml" to s("Ten"), "ppt/slides/slide2.xml" to s("Two")))
        assertTrue(text.indexOf("Two") < text.indexOf("Ten"))
        assertTrue(text.contains("Slide 2:"))
    }

    @Test fun xlsxResolvesSharedStringsAndNumbers() {
        val shared = "<sst><si><t>Name</t></si><si><t>Bob</t></si></sst>"
        val sheet = "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>42</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\"><v>7</v></c></row></sheetData></worksheet>"
        val text = OrionOfficeExtractor.extract("xlsx", zip("xl/sharedStrings.xml" to shared, "xl/worksheets/sheet1.xml" to sheet))
        assertTrue(text.contains("Name\t42"))
        assertTrue(text.contains("Bob\t7"))
    }

    @Test fun invalidAndUnsupportedInputsAreRejected() {
        try { OrionOfficeExtractor.extract("docx", "not a zip".toByteArray()); fail() } catch (_: IllegalArgumentException) {}
        try { OrionOfficeExtractor.extract("exe", ByteArray(0)); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun oversizedPartIsRefused() {
        val big = "x".repeat(3 * 1024 * 1024)
        try { OrionOfficeExtractor.extract("docx", zip("word/document.xml" to big)); fail() } catch (_: IllegalArgumentException) {}
    }
}
