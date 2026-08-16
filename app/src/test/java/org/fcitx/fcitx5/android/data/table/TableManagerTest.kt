/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

class TableManagerTest {

    @Test
    fun serializesTableOperations() {
        val workerCount = 8
        val barrier = CyclicBarrier(workerCount)
        val activeOperations = AtomicInteger()
        val maximumActiveOperations = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val operations = List(workerCount) {
                Callable {
                    barrier.await(5, TimeUnit.SECONDS)
                    runTableOperation {
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
    fun rejectsMutationAfterTableInputMethodRemoval() {
        val directory = Files.createTempDirectory("table-mutation-").toFile()
        try {
            val configuration = directory.resolve("sample.conf")
            configuration.writeText("config")
            requireExistingTableInputMethod(configuration)

            configuration.delete()

            val failure = assertThrows(IllegalStateException::class.java) {
                requireExistingTableInputMethod(configuration)
            }
            assertTrue(failure.message.orEmpty().contains(configuration.path))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun findsDistinctTableDictionaryNamesWithoutPublishingPlaceholder() {
        val directory = Files.createTempDirectory("table-dict-").toFile()
        try {
            directory.resolve("sample.main.dict").writeText("existing")

            val available = findAvailableTableFile(directory, "sample.main.dict")

            assertEquals("sample.main (2).dict", available.name)
            assertFalse(available.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleansEveryPublishedFileAfterFailedImport() {
        val directory = Files.createTempDirectory("table-import-").toFile()
        try {
            val configuration = directory.resolve("sample.conf").also { it.writeText("config") }
            val dictionary = directory.resolve("sample.dict").also { it.writeText("dictionary") }

            val results = cleanupTableImportFiles(configuration, dictionary)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertFalse(configuration.exists())
            assertFalse(dictionary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun continuesCleanupAfterOnePublishedFileFails() {
        val directory = Files.createTempDirectory("table-import-").toFile()
        try {
            val configuration = UndeletableFile(directory.resolve("sample.conf").path)
                .also { it.writeText("config") }
            val dictionary = directory.resolve("sample.dict").also { it.writeText("dictionary") }

            val results = cleanupTableImportFiles(configuration, dictionary)

            assertTrue(results.first().isFailure)
            assertTrue(results.last().isSuccess)
            assertTrue(configuration.exists())
            assertFalse(dictionary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun deletesJournalAfterRecoveringEveryPublishedFile() {
        val directory = Files.createTempDirectory("table-recovery-").toFile()
        try {
            val journal = directory.resolve(".table-import").also { it.writeText("journal") }
            val configuration = directory.resolve("sample.conf").also { it.writeText("config") }
            val dictionary = directory.resolve("sample.dict").also { it.writeText("dictionary") }

            val results = cleanupTableImportTransaction(journal, configuration, dictionary)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertFalse(journal.exists())
            assertFalse(configuration.exists())
            assertFalse(dictionary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesJournalWhenPublishedFileCleanupFails() {
        val directory = Files.createTempDirectory("table-recovery-").toFile()
        try {
            val journal = directory.resolve(".table-import").also { it.writeText("journal") }
            val configuration = UndeletableFile(directory.resolve("sample.conf").path)
                .also { it.writeText("config") }
            val dictionary = directory.resolve("sample.dict").also { it.writeText("dictionary") }

            val results = cleanupTableImportTransaction(journal, configuration, dictionary)

            assertTrue(results.first().isFailure)
            assertTrue(results.last().isSuccess)
            assertTrue(journal.isFile)
            assertTrue(configuration.isFile)
            assertFalse(dictionary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleansLegacyStagingAndUnreferencedEmptyReservations() {
        val directory = Files.createTempDirectory("table-recovery-").toFile()
        try {
            val stagedDictionary = directory.resolve("table-dict-123.dict")
                .also { it.writeText("partial") }
            val stagedJournal = directory.resolve("table-import-123.journal")
                .also { it.writeText("partial") }
            val emptyOrphan = directory.resolve("orphan.main.dict").also(File::createNewFile)
            val referencedEmpty = directory.resolve("referenced.main.dict").also(File::createNewFile)
            val referencedStaging = directory.resolve("table-dict-custom.main.dict")
                .also { it.writeText("referenced") }
            val emptyUnrelated = directory.resolve("empty-notes.txt").also(File::createNewFile)
            val unrelated = directory.resolve("notes.txt").also { it.writeText("keep") }
            val referenced = setOf(referencedEmpty.name, referencedStaging.name)

            val results = buildList {
                addAll(cleanupTableDictionaryStaging(directory, referenced))
                addAll(cleanupTableImportJournalStaging(directory))
                addAll(
                    cleanupUnreferencedEmptyTableFiles(
                        directory,
                        referenced,
                    )
                )
            }

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertFalse(stagedDictionary.exists())
            assertFalse(stagedJournal.exists())
            assertFalse(emptyOrphan.exists())
            assertTrue(referencedEmpty.isFile)
            assertTrue(referencedStaging.isFile)
            assertTrue(emptyUnrelated.isFile)
            assertTrue(unrelated.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class UndeletableFile(path: String) : File(path) {
        override fun delete(): Boolean = false
    }
}
