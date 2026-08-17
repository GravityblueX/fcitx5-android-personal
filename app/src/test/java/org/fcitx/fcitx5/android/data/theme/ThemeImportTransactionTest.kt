/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThemeImportTransactionTest {

    private val renameFile: (File, File) -> Unit = { source, destination ->
        check(!destination.exists() || destination.delete()) {
            "Cannot replace ${destination.path}"
        }
        check(source.renameTo(destination)) {
            "Cannot rename ${source.path} to ${destination.path}"
        }
    }

    private val removeTarget: (File) -> Result<Unit> = { target ->
        runCatching {
            check(!target.exists() || target.delete()) { "Cannot delete ${target.path}" }
        }
    }

    private val removeTransaction: (File) -> Result<Unit> = { transactionDirectory ->
        runCatching {
            check(!transactionDirectory.exists() || transactionDirectory.deleteRecursively()) {
                "Cannot delete ${transactionDirectory.path}"
            }
        }
    }

    private fun createTransactionDirectory(directory: File, suffix: String): File =
        directory.resolve(
            "$THEME_IMPORT_TRANSACTION_PREFIX$suffix$THEME_IMPORT_TRANSACTION_SUFFIX"
        ).apply {
            check(mkdir()) { "Cannot create transaction directory: $path" }
        }

    private fun writeJournal(
        transactionDirectory: File,
        mutations: List<ThemeImportJournalMutation>,
    ) {
        transactionDirectory.resolve(THEME_IMPORT_JOURNAL_FILE_NAME).writeText(
            Json.encodeToString(ThemeImportJournal(mutations))
        )
    }

    private fun recover(directory: File): List<Result<Unit>> =
        recoverThemeImportTransactions(
            directory,
            rename = renameFile,
            removeTarget = removeTarget,
            removeTransaction = removeTransaction,
        )

    @Test
    fun identifiesOnlyThemeImportTransactionDirectories() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val transaction = createTransactionDirectory(root, "valid")
            val missingSuffix = root.resolve("${THEME_IMPORT_TRANSACTION_PREFIX}invalid").apply {
                mkdir()
            }
            val matchingFile = root.resolve(
                "$THEME_IMPORT_TRANSACTION_PREFIX-file$THEME_IMPORT_TRANSACTION_SUFFIX"
            ).apply { writeText("keep") }

            assertTrue(isThemeImportTransactionName(transaction.name))
            assertFalse(isThemeImportTransactionName(missingSuffix.name))
            assertTrue(isThemeImportTransactionName(matchingFile.name))
            assertTrue(isThemeImportTransactionDirectory(transaction))
            assertFalse(isThemeImportTransactionDirectory(missingSuffix))
            assertFalse(isThemeImportTransactionDirectory(matchingFile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun commitsThemeImportAndDeletesObsoleteFiles() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val sources = root.resolve("sources").apply { mkdir() }
            val metadata = directory.resolve("custom.json").apply { writeText("old metadata") }
            val newMetadata = sources.resolve("custom.json").apply { writeText("new metadata") }
            val newImage = sources.resolve("background-src").apply { writeText("new image") }
            val obsolete = directory.resolve("old-background").apply { writeText("old image") }

            executeThemeImportTransaction(
                directory,
                listOf(
                    ThemeImportMutation(metadata, newMetadata, replaceExisting = true),
                    ThemeImportMutation(directory.resolve("background-src"), newImage),
                    ThemeImportMutation(obsolete, source = null),
                ),
                rename = renameFile,
                removeTarget = removeTarget,
                removeTransaction = removeTransaction,
                onCleanupFailure = { throw it },
            )

            assertEquals("new metadata", metadata.readText())
            assertEquals("new image", directory.resolve("background-src").readText())
            assertFalse(obsolete.exists())
            assertEquals(listOf("background-src", "custom.json"), directory.list()?.sorted())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deletesEveryThemeFileInOneTransaction() {
        val root = Files.createTempDirectory("theme-delete-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val metadata = directory.resolve("custom.json").apply { writeText("metadata") }
            val sourceImage = directory.resolve("background-src").apply {
                writeText("source")
            }
            val croppedImage = directory.resolve("background-cropped.png").apply {
                writeText("cropped")
            }

            deleteThemeFilesTransactionally(
                directory,
                listOf(metadata, sourceImage, croppedImage, sourceImage),
            ) { transactionDirectory, mutations ->
                executeThemeImportTransaction(
                    transactionDirectory,
                    mutations,
                    rename = renameFile,
                    removeTarget = removeTarget,
                    removeTransaction = removeTransaction,
                    onCleanupFailure = { throw it },
                )
            }

            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rollsBackThemeDeletionWhenAFileCannotBeRemoved() {
        val root = Files.createTempDirectory("theme-delete-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val metadata = directory.resolve("custom.json").apply { writeText("metadata") }
            val sourceImage = directory.resolve("background-src").apply {
                writeText("source")
            }
            val croppedImage = directory.resolve("background-cropped.png").apply {
                writeText("cropped")
            }
            val failure = IllegalStateException("cannot remove cropped image")
            var deletionFailed = false

            val thrown = assertThrows(IllegalStateException::class.java) {
                deleteThemeFilesTransactionally(
                    directory,
                    listOf(metadata, sourceImage, croppedImage),
                ) { transactionDirectory, mutations ->
                    executeThemeImportTransaction(
                        transactionDirectory,
                        mutations,
                        rename = renameFile,
                        removeTarget = { target ->
                            if (target == croppedImage && !deletionFailed) {
                                deletionFailed = true
                                Result.failure(failure)
                            } else {
                                removeTarget(target)
                            }
                        },
                        removeTransaction = removeTransaction,
                        onCleanupFailure = { throw it },
                    )
                }
            }

            assertSame(failure, thrown)
            assertEquals("metadata", metadata.readText())
            assertEquals("source", sourceImage.readText())
            assertEquals("cropped", croppedImage.readText())
            assertEquals(
                listOf("background-cropped.png", "background-src", "custom.json"),
                directory.list()?.sorted(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rollsBackPublishedFilesWhenThemeImportFails() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val sources = root.resolve("sources").apply { mkdir() }
            val metadata = directory.resolve("custom.json").apply { writeText("old metadata") }
            val newMetadata = sources.resolve("custom.json").apply { writeText("new metadata") }
            val newImage = sources.resolve("background-src").apply { writeText("new image") }
            val failure = IllegalStateException("interrupted publication")

            val thrown = assertThrows(IllegalStateException::class.java) {
                executeThemeImportTransaction(
                    directory,
                    listOf(
                        ThemeImportMutation(metadata, newMetadata, replaceExisting = true),
                        ThemeImportMutation(directory.resolve("background-src"), newImage),
                    ),
                    rename = renameFile,
                    removeTarget = removeTarget,
                    removeTransaction = removeTransaction,
                    beforeMutation = { index -> if (index == 1) throw failure },
                    onCleanupFailure = { throw it },
                )
            }

            assertSame(failure, thrown)
            assertEquals("old metadata", metadata.readText())
            assertFalse(directory.resolve("background-src").exists())
            assertEquals(listOf("custom.json"), directory.list()?.sorted())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsExistingUnownedThemeImportTarget() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val source = root.resolve("background-src").apply { writeText("new image") }
            val existing = directory.resolve("background-src").apply { writeText("existing") }

            assertThrows(FileAlreadyExistsException::class.java) {
                executeThemeImportTransaction(
                    directory,
                    listOf(ThemeImportMutation(existing, source)),
                    rename = renameFile,
                    removeTarget = removeTarget,
                    removeTransaction = removeTransaction,
                    onCleanupFailure = { throw it },
                )
            }

            assertEquals("existing", existing.readText())
            assertEquals(listOf("background-src"), directory.list()?.sorted())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rollsBackInterruptedThemeImportFromJournal() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val metadata = directory.resolve("custom.json").apply { writeText("new metadata") }
            val newImage = directory.resolve("background-src").apply { writeText("new image") }
            val deletedImage = directory.resolve("old-background")
            val transaction = createTransactionDirectory(directory, "interrupted")
            transaction.resolve("mutation-0.backup").writeText("old metadata")
            transaction.resolve("mutation-2.backup").writeText("old image")
            writeJournal(
                transaction,
                listOf(
                    ThemeImportJournalMutation(
                        metadata.name,
                        "mutation-0.staged",
                        "mutation-0.backup",
                    ),
                    ThemeImportJournalMutation(newImage.name, "mutation-1.staged", null),
                    ThemeImportJournalMutation(deletedImage.name, null, "mutation-2.backup"),
                ),
            )

            val results = recover(directory)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertEquals("old metadata", metadata.readText())
            assertFalse(newImage.exists())
            assertEquals("old image", deletedImage.readText())
            assertFalse(transaction.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rollsBackInterruptedThemeDeletionFromJournal() {
        val root = Files.createTempDirectory("theme-delete-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val transaction = createTransactionDirectory(directory, "interrupted-delete")
            transaction.resolve("mutation-0.backup").writeText("metadata")
            transaction.resolve("mutation-1.backup").writeText("source")
            transaction.resolve("mutation-2.backup").writeText("cropped")
            writeJournal(
                transaction,
                listOf(
                    ThemeImportJournalMutation("custom.json", null, "mutation-0.backup"),
                    ThemeImportJournalMutation("background-src", null, "mutation-1.backup"),
                    ThemeImportJournalMutation(
                        "background-cropped.png",
                        null,
                        "mutation-2.backup",
                    ),
                ),
            )

            val results = recover(directory)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertEquals("metadata", directory.resolve("custom.json").readText())
            assertEquals("source", directory.resolve("background-src").readText())
            assertEquals("cropped", directory.resolve("background-cropped.png").readText())
            assertFalse(transaction.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retriesThemeImportCleanupAfterCompletedRollback() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val metadata = directory.resolve("custom.json").apply { writeText("new metadata") }
            val transaction = createTransactionDirectory(directory, "cleanup-retry")
            transaction.resolve("mutation-0.backup").writeText("old metadata")
            writeJournal(
                transaction,
                listOf(
                    ThemeImportJournalMutation(
                        metadata.name,
                        "mutation-0.staged",
                        "mutation-0.backup",
                    )
                ),
            )
            val cleanupFailure = IllegalStateException("cleanup failed")

            val firstRecovery = recoverThemeImportTransactions(
                directory,
                rename = renameFile,
                removeTarget = removeTarget,
                removeTransaction = { Result.failure(cleanupFailure) },
            )

            assertSame(cleanupFailure, firstRecovery.single().exceptionOrNull())
            assertEquals("old metadata", metadata.readText())
            assertTrue(transaction.isDirectory)
            assertTrue(hasUnresolvedThemeImportTransaction(directory))

            val secondRecovery = recover(directory)

            assertTrue(secondRecovery.all(Result<Unit>::isSuccess))
            assertEquals("old metadata", metadata.readText())
            assertFalse(transaction.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preservesCommittedThemeImportDuringRecovery() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val metadata = directory.resolve("custom.json").apply { writeText("new metadata") }
            val deletedImage = directory.resolve("old-background")
            val transaction = createTransactionDirectory(directory, "committed")
            transaction.resolve("mutation-0.backup").writeText("old metadata")
            transaction.resolve("mutation-1.backup").writeText("old image")
            transaction.resolve(THEME_IMPORT_JOURNAL_FILE_NAME).writeText("corrupt after commit")
            transaction.resolve(THEME_IMPORT_COMMIT_FILE_NAME).writeText("committed")

            val results = recover(directory)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertEquals("new metadata", metadata.readText())
            assertFalse(deletedImage.exists())
            assertFalse(transaction.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preservesCommittedThemeDeletionDuringRecovery() {
        val root = Files.createTempDirectory("theme-delete-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val transaction = createTransactionDirectory(directory, "committed-delete")
            transaction.resolve("mutation-0.backup").writeText("metadata")
            transaction.resolve("mutation-1.backup").writeText("source")
            writeJournal(
                transaction,
                listOf(
                    ThemeImportJournalMutation("custom.json", null, "mutation-0.backup"),
                    ThemeImportJournalMutation("background-src", null, "mutation-1.backup"),
                ),
            )
            transaction.resolve(THEME_IMPORT_COMMIT_FILE_NAME).writeText("committed")

            val results = recover(directory)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertFalse(directory.resolve("custom.json").exists())
            assertFalse(directory.resolve("background-src").exists())
            assertFalse(transaction.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleansAbandonedThemeImportBeforeJournalPublication() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val metadata = directory.resolve("custom.json").apply { writeText("metadata") }
            val transaction = createTransactionDirectory(directory, "abandoned")
            transaction.resolve("mutation-0.staged").writeText("partial")

            val results = recover(directory)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertEquals("metadata", metadata.readText())
            assertFalse(transaction.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preservesMalformedThemeImportJournalForRecovery() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val transaction = createTransactionDirectory(directory, "malformed")
            val journal = transaction.resolve(THEME_IMPORT_JOURNAL_FILE_NAME).apply {
                writeText("not json")
            }

            val results = recover(directory)

            assertEquals(1, results.size)
            assertTrue(results.single().isFailure)
            assertTrue(journal.isFile)
            assertTrue(transaction.isDirectory)
            assertTrue(hasUnresolvedThemeImportTransaction(directory))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsThemeImportJournalPathsOutsideThemeDirectory() {
        val root = Files.createTempDirectory("theme-import-").toFile()
        try {
            val directory = root.resolve("theme").apply { mkdir() }
            val outside = root.resolve("outside.json").apply { writeText("keep") }
            val transaction = createTransactionDirectory(directory, "invalid-path")
            transaction.resolve("mutation-0.backup").writeText("old")
            writeJournal(
                transaction,
                listOf(
                    ThemeImportJournalMutation(
                        "../outside.json",
                        null,
                        "mutation-0.backup",
                    )
                ),
            )

            val results = recover(directory)

            assertEquals(1, results.size)
            assertTrue(results.single().isFailure)
            assertEquals("keep", outside.readText())
            assertTrue(transaction.isDirectory)
            assertTrue(hasUnresolvedThemeImportTransaction(directory))
        } finally {
            root.deleteRecursively()
        }
    }
}
