package com.phoneai.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSessionVaultTest {
    @Test fun legacyCacheHomeMovesWithNativeThreadFilesIntoPersistentStorage() {
        val root = createTempDir(prefix = "codex-home-migration-")
        try {
            val legacy = File(root, "cache/codex-session").apply { mkdirs() }
            val threadDb = File(legacy, "state/threads/thread.jsonl").apply {
                parentFile?.mkdirs()
                writeText("native-thread-history")
            }
            val auth = File(legacy, "auth.json").apply { writeText("legacy-auth") }
            val persistent = File(root, "files/codex-session")

            CodexSessionVault.migrateLegacyHome(legacy, persistent)

            assertEquals("native-thread-history", File(persistent, "state/threads/thread.jsonl").readText())
            assertEquals("legacy-auth", File(persistent, "auth.json").readText())
            assertFalse(legacy.exists())
            assertTrue(threadDb.exists().not() || !threadDb.exists())
            assertTrue(auth.exists().not() || !auth.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun existingPersistentThreadDataWinsAndMissingLegacyFilesAreMerged() {
        val root = createTempDir(prefix = "codex-home-merge-")
        try {
            val legacy = File(root, "cache/codex-session").apply { mkdirs() }
            File(legacy, "thread.jsonl").writeText("old-thread")
            File(legacy, "auth.json").writeText("legacy-auth")
            val persistent = File(root, "files/codex-session").apply { mkdirs() }
            File(persistent, "thread.jsonl").writeText("newer-persistent-thread")

            CodexSessionVault.migrateLegacyHome(legacy, persistent)

            assertEquals("newer-persistent-thread", File(persistent, "thread.jsonl").readText())
            assertEquals("legacy-auth", File(persistent, "auth.json").readText())
            assertFalse(legacy.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun migrationSucceedsWhenTheFilesParentDirectoryAlreadyExists() {
        // Real devices: filesDir always exists, so mkdirs() on the parent returns false even though nothing is wrong.
        val root = createTempDir(prefix = "codex-home-parent-")
        try {
            val legacy = File(root, "cache/codex-session").apply { mkdirs() }
            File(legacy, "auth.json").writeText("legacy-auth")
            val filesDir = File(root, "files").apply { mkdirs() }
            val persistent = File(filesDir, "codex-session")

            CodexSessionVault.migrateLegacyHome(legacy, persistent)

            assertEquals("legacy-auth", File(persistent, "auth.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
