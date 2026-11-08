package com.phoneai.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class OrionPdfExtractorTest {
    private fun pdf(content: ByteArray, flate: Boolean): ByteArray {
        val head = "%PDF-1.4\n1 0 obj\n<< /Length ${content.size}${if (flate) " /Filter /FlateDecode" else ""} >>\nstream\n"
        val out = ByteArrayOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1)); out.write(content)
        out.write("\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    @Test fun readsPlainContentStream() {
        val text = OrionPdfExtractor.extract(pdf("BT /F1 12 Tf (Hello Kingi) Tj ET".toByteArray(), false))
        assertTrue(text, text.contains("Hello Kingi"))
    }

    @Test fun readsCompressedStreamAndTjArrays() {
        val raw = "BT [(Invoice) -300 (Total) 20 ( 450)] TJ ET".toByteArray()
        val d = Deflater(); d.setInput(raw); d.finish()
        val buf = ByteArray(256); val n = d.deflate(buf)
        val text = OrionPdfExtractor.extract(pdf(buf.copyOf(n), true))
        assertTrue(text, text.contains("Invoice") && text.contains("Total"))
    }
}
