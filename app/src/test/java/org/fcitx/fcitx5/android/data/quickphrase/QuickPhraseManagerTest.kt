/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QuickPhraseManagerTest {

    @Test
    fun serializesQuickPhraseOperations() {
        val workerCount = 8
        val barrier = CyclicBarrier(workerCount)
        val activeOperations = AtomicInteger()
        val maximumActiveOperations = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val operations = List(workerCount) {
                Callable {
                    barrier.await(5, TimeUnit.SECONDS)
                    runQuickPhraseOperation {
                        val active = activeOperations.incrementAndGet()
                        try {
                            maximumActiveOperations.updateAndGet { maximum ->
                                maxOf(maximum, active)
                            }
                            Thread.sleep(25)
                        } finally {
                            activeOperations.decrementAndGet()
                        }
                    }
                }
            }

            executor.invokeAll(operations).forEach { future ->
                future.get(5, TimeUnit.SECONDS)
            }

            assertEquals(1, maximumActiveOperations.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun identifiesOnlyQuickPhraseImportStagingFiles() {
        assertTrue(isQuickPhraseImportStagingFile(".quickphrase-import-123.staged"))
        assertFalse(isQuickPhraseImportStagingFile("quickphrase-import-123.staged"))
        assertFalse(isQuickPhraseImportStagingFile(".quickphrase-import-123.mb"))
        assertFalse(isQuickPhraseImportStagingFile("file-install-123.staged"))
    }

    @Test
    fun publishesCompleteQuickPhraseWithoutPlaceholder() {
        val root = Files.createTempDirectory("quickphrase-publish-").toFile()
        try {
            val source = root.resolve("source.mb").also { it.writeText("key phrase") }
            val directory = root.resolve("destination")

            val published = publishNewQuickPhraseFile(
                source,
                directory,
                "custom.mb",
                publish = { staged, destination ->
                    assertFalse(destination.exists())
                    assertEquals("key phrase", staged.readText())
                    assertTrue(staged.renameTo(destination))
                },
            )

            assertEquals(directory.resolve("custom.mb").canonicalFile, published)
            assertEquals("key phrase", published.readText())
            assertEquals(listOf("custom.mb"), directory.list()?.sorted())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleansStagingWhenQuickPhrasePublishFails() {
        val root = Files.createTempDirectory("quickphrase-publish-").toFile()
        try {
            val source = root.resolve("source.mb").also { it.writeText("key phrase") }
            val directory = root.resolve("destination")
            val failure = IllegalStateException("publish failed")

            val thrown = assertThrows(IllegalStateException::class.java) {
                publishNewQuickPhraseFile(
                    source,
                    directory,
                    "custom.mb",
                    publish = { staged, destination ->
                        assertEquals("key phrase", staged.readText())
                        assertFalse(destination.exists())
                        throw failure
                    },
                )
            }

            assertTrue(thrown === failure)
            assertFalse(directory.resolve("custom.mb").exists())
            assertTrue(directory.list()?.isEmpty() == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun doesNotReplaceExistingQuickPhraseDuringPublish() {
        val root = Files.createTempDirectory("quickphrase-publish-").toFile()
        try {
            val source = root.resolve("source.mb").also { it.writeText("new content") }
            val directory = root.resolve("destination").also(File::mkdir)
            val existing = directory.resolve("custom.mb").also { it.writeText("existing") }

            assertThrows(FileAlreadyExistsException::class.java) {
                publishNewQuickPhraseFile(
                    source,
                    directory,
                    "custom.mb",
                )
            }

            assertEquals("existing", existing.readText())
            assertEquals(listOf("custom.mb"), directory.list()?.sorted())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun doesNotReplaceQuickPhraseCreatedAfterValidation() {
        val root = Files.createTempDirectory("quickphrase-publish-").toFile()
        try {
            val source = root.resolve("source.mb").also { it.writeText("new content") }
            val directory = root.resolve("destination").also(File::mkdir)
            val destination = directory.resolve("custom.mb")

            assertThrows(FileAlreadyExistsException::class.java) {
                publishNewQuickPhraseFile(
                    source,
                    directory,
                    "custom.mb",
                    validate = { destination.writeText("concurrent content") },
                )
            }

            assertEquals("concurrent content", destination.readText())
            assertEquals(listOf("custom.mb"), directory.list()?.sorted())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun normalizesImportedQuickPhraseNames() {
        val target = quickPhraseImportTarget("..\\..\\custom.name.mb")!!

        assertEquals("custom.name.mb", target.fileName)
        assertEquals("custom.name", target.entryName)
    }

    @Test
    fun rejectsInvalidImportedQuickPhraseNames() {
        listOf("quickphrase", ".mb", "   .mb", "..mb", "custom.mb.disable")
            .forEach { assertNull(quickPhraseImportTarget(it)) }
    }

    @Test
    fun rejectsDuplicateCreatedQuickPhrasesWithoutChangingExistingFile() {
        val directory = Files.createTempDirectory("quickphrase-").toFile()
        try {
            val file = reserveQuickPhraseFile(directory, "custom.mb")
            file.writeText("existing content")

            assertThrows(FileAlreadyExistsException::class.java) {
                reserveQuickPhraseFile(directory, "custom.mb")
            }

            assertTrue(file.isFile)
            assertEquals("existing content", file.readText())
            assertEquals(listOf("custom.mb"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun detectsEnabledDisabledAndBuiltinNameConflicts() {
        val root = Files.createTempDirectory("quickphrase-conflict-").toFile()
        try {
            val customDirectory = root.resolve("custom").also(File::mkdir)
            val builtinDirectory = root.resolve("builtin").also(File::mkdir)

            assertFalse(quickPhraseEntryExists(customDirectory, builtinDirectory, "custom"))

            val enabled = customDirectory.resolve("custom.mb").also { it.writeText("enabled") }
            assertTrue(quickPhraseEntryExists(customDirectory, builtinDirectory, "custom"))
            enabled.delete()

            val disabled = customDirectory.resolve("custom.mb.disable")
                .also { it.writeText("disabled") }
            assertTrue(quickPhraseEntryExists(customDirectory, builtinDirectory, "custom"))
            disabled.delete()

            builtinDirectory.resolve("custom.mb").writeText("builtin")
            assertTrue(quickPhraseEntryExists(customDirectory, builtinDirectory, "custom"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsOnlyManagedQuickPhrasePaths() {
        val root = Files.createTempDirectory("quickphrase-managed-").toFile()
        try {
            val directory = root.resolve("managed").also(File::mkdir)
            val managed = directory.resolve("custom.mb").also { it.writeText("quick phrase") }
            val unmanaged = root.resolve("custom.mb").also { it.writeText("quick phrase") }

            assertEquals(managed.canonicalFile, managedQuickPhraseFile(directory, managed))
            assertThrows(IllegalStateException::class.java) {
                managedQuickPhraseFile(directory, unmanaged)
            }
        } finally {
            root.deleteRecursively()
        }
    }

}
