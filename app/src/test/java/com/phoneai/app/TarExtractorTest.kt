package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class TarExtractorTest {
    private fun header(name: String, type: Char, size: Long, mode: Int = 420, link: String = ""): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) { s.toByteArray().copyInto(h, off) }
        put(0, name); put(100, "%07o".format(mode)); put(108, "0000000"); put(116, "0000000")
        put(124, "%011o".format(size)); put(136, "00000000000"); put(148, "        ")
        h[156] = type.code.toByte(); put(157, link); put(257, "ustar"); put(263, "00")
        var sum = 0; for (b in h) sum += b.toInt() and 0xff
        put(148, "%06o".format(sum) + "\u0000 ")
        return h
    }

    private fun entry(out: ByteArrayOutputStream, name: String, type: Char, data: ByteArray = ByteArray(0), mode: Int = 420, link: String = "") {
        out.write(header(name, type, data.size.toLong(), mode, link))
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
    }

    private fun tar(build: (ByteArrayOutputStream) -> Unit): ByteArray {
        val o = ByteArrayOutputStream(); build(o); o.write(ByteArray(1024)); return o.toByteArray()
    }

    private fun tempDir() = Files.createTempDirectory("tar-test").toFile()

    @Test fun extractsFilesDirectoriesAndRelativeLinks() {
        val dir = tempDir()
        val stats = TarExtractor.extract(ByteArrayInputStream(tar { o ->
            entry(o, "bin/", '5')
            entry(o, "bin/sh", '0', "#!/bin/sh\n".toByteArray(), mode = 493)
            entry(o, "usr/bin/env", '0', "env".toByteArray(), mode = 493)
            entry(o, "bin/busy", '2', link = "../usr/bin/env")
            entry(o, "etc/pass", '0', "root:x:0".toByteArray())
        }), dir)
        assertEquals(3, stats.files)
        assertTrue(File(dir, "bin/sh").canExecute())
        assertTrue(File(dir, "bin/busy").exists())
        assertEquals("root:x:0", File(dir, "etc/pass").readText())
    }

    @Test fun rejectsPathTraversalAndAbsolutePaths() {
        listOf("../evil", "a/../../evil", "/etc/passwd").forEach { name ->
            try {
                TarExtractor.extract(ByteArrayInputStream(tar { entry(it, name, '0', "x".toByteArray()) }), tempDir())
                fail("accepted $name")
            } catch (_: TarExtractor.Rejected) {}
        }
    }

    @Test fun rejectsSymlinksThatLeaveTheRoot() {
        listOf("../../../../etc" to "x", "/system/bin/sh" to "y").forEach { (target, name) ->
            try {
                TarExtractor.extract(ByteArrayInputStream(tar { entry(it, name, '2', link = target) }), tempDir())
                fail("accepted link to $target")
            } catch (_: TarExtractor.Rejected) {}
        }
    }

    @Test fun rejectsHardLinksOutsideTheRoot() {
        try {
            TarExtractor.extract(ByteArrayInputStream(tar { entry(it, "h", '1', link = "../../etc/shadow") }), tempDir())
            fail("accepted hard link")
        } catch (_: TarExtractor.Rejected) {}
    }

    @Test fun honorsPaxLongPaths() {
        val body = " path=deep/long/name.txt\n"
        val record = "${body.length + body.length.toString().length}$body"
        val dir = tempDir()
        TarExtractor.extract(ByteArrayInputStream(tar { o ->
            entry(o, "ignored", 'x', record.toByteArray())
            entry(o, "ignored", '0', "pax".toByteArray())
        }), dir)
        assertTrue(File(dir, "deep/long/name.txt").isFile)
    }

    @Test fun symlinkTargetRulesAreExplicit() {
        assertTrue(TarExtractor.symlinkTargetAllowed("bin/busy", "../usr/bin/env"))
        assertFalse(TarExtractor.symlinkTargetAllowed("bin/x", "../../etc"))
        assertFalse(TarExtractor.symlinkTargetAllowed("x", "/abs"))
    }
}
