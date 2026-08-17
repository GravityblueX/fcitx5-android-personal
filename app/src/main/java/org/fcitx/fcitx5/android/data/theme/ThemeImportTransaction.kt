/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.system.Os
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.ImportRecoveryAction
import org.fcitx.fcitx5.android.data.determineImportRecoveryAction
import org.fcitx.fcitx5.android.utils.FileUtil
import org.fcitx.fcitx5.android.utils.addSuppressedFailures
import org.fcitx.fcitx5.android.utils.ensureDirectory
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.resolveDirectChild
import org.fcitx.fcitx5.android.utils.runWithCleanup
import timber.log.Timber
import java.io.File
import java.util.UUID

internal const val THEME_IMPORT_TRANSACTION_PREFIX = ".theme-import-"
internal const val THEME_IMPORT_TRANSACTION_SUFFIX = ".transaction"
internal const val THEME_IMPORT_JOURNAL_FILE_NAME = "journal.json"
internal const val THEME_IMPORT_COMMIT_FILE_NAME = "committed"

private const val THEME_IMPORT_JOURNAL_STAGING_PREFIX = ".journal-"
private const val THEME_IMPORT_JOURNAL_STAGING_SUFFIX = ".staged"
private const val THEME_IMPORT_COMMIT_STAGING_PREFIX = ".commit-"
private const val THEME_IMPORT_COMMIT_STAGING_SUFFIX = ".staged"

private val themeImportTransactionJson = Json { prettyPrint = true }

internal data class ThemeImportMutation(
    val target: File,
    val source: File?,
    val replaceExisting: Boolean = false,
)

@Serializable
internal data class ThemeImportJournal(
    val mutations: List<ThemeImportJournalMutation>,
    val version: Int = 1,
)

@Serializable
internal data class ThemeImportJournalMutation(
    val targetFileName: String,
    val stagedFileName: String?,
    val backupFileName: String?,
)

private data class StagedThemeImportMutation(
    val target: File,
    val staged: File?,
    val backup: File?,
)

private fun renameThemeImportFile(source: File, destination: File) {
    Os.rename(source.path, destination.path)
}

private fun themeImportJournalFile(transactionDirectory: File): File =
    transactionDirectory.resolveDirectChild(THEME_IMPORT_JOURNAL_FILE_NAME)

private fun themeImportCommitFile(transactionDirectory: File): File =
    transactionDirectory.resolveDirectChild(THEME_IMPORT_COMMIT_FILE_NAME)

internal fun isThemeImportTransactionName(fileName: String): Boolean =
    fileName.startsWith(THEME_IMPORT_TRANSACTION_PREFIX) &&
            fileName.endsWith(THEME_IMPORT_TRANSACTION_SUFFIX)

internal fun isThemeImportTransactionDirectory(file: File): Boolean =
    file.isDirectory && isThemeImportTransactionName(file.name)

internal fun hasUnresolvedThemeImportTransaction(directory: File): Boolean =
    directory.listFiles()
        ?.filter(::isThemeImportTransactionDirectory)
        ?.any { transactionDirectory ->
            themeImportJournalFile(transactionDirectory).exists() &&
                    !themeImportCommitFile(transactionDirectory).isFile
        } == true

private fun publishThemeImportMarker(
    transactionDirectory: File,
    destinationFileName: String,
    stagingPrefix: String,
    stagingSuffix: String,
    content: String,
    rename: (File, File) -> Unit,
    onCleanupFailure: (Throwable) -> Unit,
) {
    val destination = transactionDirectory.resolveDirectChild(destinationFileName)
    check(!destination.exists()) { "Theme import marker already exists: ${destination.path}" }
    val staged = File.createTempFile(stagingPrefix, stagingSuffix, transactionDirectory)
    runWithCleanup(
        cleanup = { staged.removeIfExists() },
        onCleanupFailure = onCleanupFailure,
    ) {
        staged.writeText(content)
        rename(staged, destination)
        check(destination.isFile) { "Failed to publish theme import marker: ${destination.path}" }
    }
}

private fun stageThemeImportMutations(
    directory: File,
    transactionDirectory: File,
    mutations: List<ThemeImportMutation>,
): List<StagedThemeImportMutation> {
    val managedMutations = mutations.map { mutation ->
        val target = directory.resolveDirectChild(mutation.target.name)
        require(target == mutation.target.canonicalFile) {
            "Unmanaged theme import target: ${mutation.target.path}"
        }
        mutation.copy(target = target)
    }
    require(managedMutations.map { it.target }.distinct().size == managedMutations.size) {
        "Theme import contains duplicate targets"
    }
    return managedMutations
        .filter { mutation -> mutation.source != null || mutation.target.exists() }
        .mapIndexed { index, mutation ->
            val target = mutation.target
            val source = mutation.source
            val targetExists = target.exists()
            if (targetExists) {
                require(target.isFile) { "Theme import target is not a file: ${target.path}" }
                if (source != null && !mutation.replaceExisting) {
                    throw FileAlreadyExistsException(target)
                }
            }
            source?.let {
                require(it.isFile) { "Theme import source is not a file: ${it.path}" }
            }
            val staged = source?.let {
                transactionDirectory.resolveDirectChild("mutation-$index.staged").also { staged ->
                    it.copyTo(staged)
                }
            }
            val backup = if (targetExists) {
                transactionDirectory.resolveDirectChild("mutation-$index.backup").also { backup ->
                    target.copyTo(backup)
                }
            } else {
                null
            }
            StagedThemeImportMutation(target, staged, backup)
        }
}

private fun writeThemeImportJournal(
    transactionDirectory: File,
    mutations: List<StagedThemeImportMutation>,
    rename: (File, File) -> Unit,
    onCleanupFailure: (Throwable) -> Unit,
) {
    val journal = ThemeImportJournal(
        mutations.map { mutation ->
            ThemeImportJournalMutation(
                mutation.target.name,
                mutation.staged?.name,
                mutation.backup?.name,
            )
        }
    )
    publishThemeImportMarker(
        transactionDirectory,
        THEME_IMPORT_JOURNAL_FILE_NAME,
        THEME_IMPORT_JOURNAL_STAGING_PREFIX,
        THEME_IMPORT_JOURNAL_STAGING_SUFFIX,
        themeImportTransactionJson.encodeToString(journal),
        rename,
        onCleanupFailure,
    )
}

private fun applyThemeImportMutation(
    mutation: StagedThemeImportMutation,
    rename: (File, File) -> Unit,
    removeTarget: (File) -> Result<Unit>,
) {
    val target = mutation.target
    val staged = mutation.staged
    if (staged == null) {
        removeTarget(target).getOrThrow()
        check(!target.exists()) { "Failed to delete obsolete theme file: ${target.path}" }
        return
    }
    if (mutation.backup == null && target.exists()) throw FileAlreadyExistsException(target)
    rename(staged, target)
    check(target.isFile) { "Failed to publish imported theme file: ${target.path}" }
}

private fun resolveThemeImportJournal(
    transactionDirectory: File,
): List<StagedThemeImportMutation> {
    val journal = themeImportTransactionJson.decodeFromString<ThemeImportJournal>(
        themeImportJournalFile(transactionDirectory).readText()
    )
    require(journal.version == 1) { "Unsupported theme import journal version" }
    require(journal.mutations.isNotEmpty()) { "Theme import journal is empty" }
    val directory = transactionDirectory.parentFile
        ?: error("Cannot resolve theme import directory")
    val mutations = journal.mutations.mapIndexed { index, mutation ->
        require(mutation.stagedFileName != null || mutation.backupFileName != null) {
            "Theme import journal contains an empty mutation"
        }
        mutation.stagedFileName?.let { stagedFileName ->
            require(stagedFileName == "mutation-$index.staged") {
                "Invalid theme import staging file"
            }
        }
        mutation.backupFileName?.let { backupFileName ->
            require(backupFileName == "mutation-$index.backup") {
                "Invalid theme import backup file"
            }
        }
        StagedThemeImportMutation(
            directory.resolveDirectChild(mutation.targetFileName),
            mutation.stagedFileName?.let(transactionDirectory::resolveDirectChild),
            mutation.backupFileName?.let(transactionDirectory::resolveDirectChild),
        )
    }
    require(mutations.map { it.target }.distinct().size == mutations.size) {
        "Theme import journal contains duplicate targets"
    }
    val artifacts = mutations.flatMap { mutation ->
        listOfNotNull(mutation.staged, mutation.backup)
    }
    require(artifacts.distinct().size == artifacts.size) {
        "Theme import journal contains duplicate artifacts"
    }
    return mutations
}

private fun rollbackThemeImportTransaction(
    transactionDirectory: File,
    rename: (File, File) -> Unit,
    removeTarget: (File) -> Result<Unit>,
): Result<Unit> {
    val mutations = runCatching { resolveThemeImportJournal(transactionDirectory) }
        .getOrElse { return Result.failure(it) }
    val rollbackResults = mutations.asReversed().map { mutation ->
        runCatching {
            val target = mutation.target
            val staged = mutation.staged
            val backup = mutation.backup
            check(!target.exists() || target.isFile) {
                "Theme import target is not a file: ${target.path}"
            }
            check(staged == null || !staged.exists() || staged.isFile) {
                "Theme import staging is not a file: ${staged?.path}"
            }
            check(backup == null || !backup.exists() || backup.isFile) {
                "Theme import backup is not a file: ${backup?.path}"
            }
            when (
                determineImportRecoveryAction(
                    targetExists = target.exists(),
                    stagedExists = staged?.exists() == true,
                    backupExpected = backup != null,
                    backupExists = backup?.exists() == true,
                )
            ) {
                ImportRecoveryAction.NONE -> Unit
                ImportRecoveryAction.REMOVE_TARGET -> removeTarget(target).getOrThrow()
                ImportRecoveryAction.RESTORE_BACKUP -> {
                    rename(requireNotNull(backup), target)
                }

                ImportRecoveryAction.REPLACE_TARGET_WITH_BACKUP -> {
                    removeTarget(target).getOrThrow()
                    rename(requireNotNull(backup), target)
                }

                ImportRecoveryAction.UNRECOVERABLE -> {
                    error("Cannot recover imported theme file: ${target.path}")
                }
            }
            check(!target.exists() || target.isFile) {
                "Recovered theme target is not a file: ${target.path}"
            }
        }
    }
    val failure = rollbackResults.firstNotNullOfOrNull(Result<Unit>::exceptionOrNull)
        ?: return Result.success(Unit)
    failure.addSuppressedFailures(rollbackResults)
    return Result.failure(failure)
}

internal fun executeThemeImportTransaction(
    directory: File,
    mutations: List<ThemeImportMutation>,
    rename: (File, File) -> Unit = ::renameThemeImportFile,
    removeTarget: (File) -> Result<Unit> = { target -> target.removeIfExists() },
    removeTransaction: (File) -> Result<Unit> = FileUtil::removeFile,
    beforeMutation: (Int) -> Unit = {},
    onCleanupFailure: (Throwable) -> Unit = { failure ->
        Timber.w(failure, "Failed to clean theme import transaction")
    },
) {
    directory.ensureDirectory()
    require(mutations.isNotEmpty()) { "Theme import transaction is empty" }
    val transactionDirectory = directory.resolveDirectChild(
        "$THEME_IMPORT_TRANSACTION_PREFIX${UUID.randomUUID()}$THEME_IMPORT_TRANSACTION_SUFFIX"
    ).also { transaction ->
        check(transaction.mkdir()) {
            "Cannot create theme import transaction directory: ${transaction.path}"
        }
    }
    var journalWritten = false
    var committed = false
    var preserveTransaction = false
    runWithCleanup(
        cleanup = {
            if (preserveTransaction) Result.success(Unit)
            else removeTransaction(transactionDirectory)
        },
        onCleanupFailure = onCleanupFailure,
    ) {
        val stagedMutations = stageThemeImportMutations(
            directory,
            transactionDirectory,
            mutations,
        )
        if (stagedMutations.isEmpty()) return@runWithCleanup
        writeThemeImportJournal(
            transactionDirectory,
            stagedMutations,
            rename,
            onCleanupFailure,
        )
        journalWritten = true
        try {
            stagedMutations.forEachIndexed { index, mutation ->
                beforeMutation(index)
                applyThemeImportMutation(mutation, rename, removeTarget)
            }
            publishThemeImportMarker(
                transactionDirectory,
                THEME_IMPORT_COMMIT_FILE_NAME,
                THEME_IMPORT_COMMIT_STAGING_PREFIX,
                THEME_IMPORT_COMMIT_STAGING_SUFFIX,
                "committed",
                rename,
                onCleanupFailure,
            )
            committed = true
        } catch (failure: Throwable) {
            if (journalWritten && !committed) {
                rollbackThemeImportTransaction(transactionDirectory, rename, removeTarget)
                    .onFailure { rollbackFailure ->
                        preserveTransaction = true
                        if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
                    }
            }
            throw failure
        }
    }
}

internal fun recoverThemeImportTransactions(
    directory: File,
    rename: (File, File) -> Unit = ::renameThemeImportFile,
    removeTarget: (File) -> Result<Unit> = { target -> target.removeIfExists() },
    removeTransaction: (File) -> Result<Unit> = FileUtil::removeFile,
): List<Result<Unit>> = directory.listFiles()
    ?.filter(::isThemeImportTransactionDirectory)
    ?.sortedBy(File::getName)
    ?.map { transactionDirectory ->
        val journal = themeImportJournalFile(transactionDirectory)
        val committed = themeImportCommitFile(transactionDirectory).isFile
        if (committed || !journal.exists()) {
            removeTransaction(transactionDirectory)
        } else {
            val rollback = rollbackThemeImportTransaction(
                transactionDirectory,
                rename,
                removeTarget,
            )
            if (rollback.isSuccess) removeTransaction(transactionDirectory) else rollback
        }
    }
    .orEmpty()
