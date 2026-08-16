/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table

import android.system.Os
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.table.dict.Dictionary
import org.fcitx.fcitx5.android.data.table.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.utils.cleanupStagedFileInstalls
import org.fcitx.fcitx5.android.utils.addSuppressedFailures
import org.fcitx.fcitx5.android.utils.safeFileName
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.externalFilesDirOrFilesDir
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.ensureDirectory
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.installNewFileAtomically
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.replaceFileAtomically
import org.fcitx.fcitx5.android.utils.resolveDirectChild
import org.fcitx.fcitx5.android.utils.runWithCleanup
import org.fcitx.fcitx5.android.utils.runWithRollback
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipInputStream
import kotlin.concurrent.withLock

private const val TABLE_DICTIONARY_STAGING_PREFIX = "table-dict-"
private const val TABLE_DICTIONARY_STAGING_SUFFIX = ".dict"
private const val TABLE_IMPORT_JOURNAL_STAGING_PREFIX = "table-import-"
private const val TABLE_IMPORT_JOURNAL_STAGING_SUFFIX = ".journal"

@Serializable
private data class TableImportJournal(
    val configurationFileName: String,
    val dictionaryFileName: String,
)

private val tableOperationLock = ReentrantLock()

internal fun <T> runTableOperation(block: () -> T): T =
    tableOperationLock.withLock(block)

internal fun requireExistingTableInputMethod(configurationFile: File) {
    check(configurationFile.isFile) {
        "Cannot modify a removed table input method: ${configurationFile.path}"
    }
}

object TableManager {

    private val json = Json { prettyPrint = true }

    private val inputMethodDir = File(
        appContext.externalFilesDirOrFilesDir, "data/inputmethod"
    ).also { directory ->
        directory.ensureDirectory()
        cleanupStagedFileInstalls(directory)
    }

    private val tableDicDir = File(
        appContext.externalFilesDirOrFilesDir, "data/table"
    ).also { directory ->
        directory.ensureDirectory()
        cleanupStagedFileInstalls(directory)
    }

    private val importJournalFile = appContext.filesDir.resolve(".table-import")

    init {
        cleanupTableImportJournalStaging(importJournalFile.parentFile)
            .forEach { it.onFailure(::reportImportCleanupFailure) }
        recoverPendingImport()
        if (!importJournalFile.exists()) {
            cleanupLegacyTableArtifacts()
        }
    }

    fun inputMethods(): List<TableBasedInputMethod> = runTableOperation {
        recoverPendingImport()
        if (importJournalFile.exists()) return@runTableOperation emptyList()
        inputMethodDir.listFiles()?.mapNotNull { confFile ->
            runCatching {
                TableBasedInputMethod.new(confFile).apply {
                    runCatching {
                        table = LibIMEDictionary(File(tableDicDir, tableFileName))
                    }
                }
            }.getOrNull()
        } ?: emptyList()
    }

    fun importFromZip(src: InputStream): Result<TableBasedInputMethod> =
        runTableOperation {
            runCatching {
                ZipInputStream(src).use { zipStream ->
                    prepareForMutation()
                    withTempDir { tempDir ->
                        val extracted = zipStream.extract(tempDir)
                        val confFile = extracted.find { it.name.endsWith(".conf") }
                            ?: extracted.find { it.name.endsWith(".conf.in") }
                            ?: errorRuntime(R.string.exception_table_im)
                        val dictFile = extracted.find { it.name.endsWith(".dict") }
                            ?: extracted.find { it.name.endsWith(".txt") }
                            ?: errorRuntime(R.string.exception_table)
                        importFiles(confFile, dictFile)
                    }
                }
            }
        }

    fun importFromConfAndDict(
        confName: String,
        confStream: InputStream,
        dictName: String,
        dictStream: InputStream
    ): Result<TableBasedInputMethod> = runTableOperation {
        runCatching {
            confStream.use { confInput ->
                dictStream.use { dictInput ->
                    prepareForMutation()
                    withTempDir { tempDir ->
                        val confFile = File(tempDir, confName.safeFileName()).also {
                            it.outputStream().use(confInput::copyTo)
                        }
                        val dictFile = File(tempDir, dictName.safeFileName()).also {
                            it.outputStream().use(dictInput::copyTo)
                        }
                        importFiles(confFile, dictFile)
                    }
                }
            }
        }
    }

    private fun importFiles(confFile: File, dictFile: File): TableBasedInputMethod {
        val importedConfName = confFile.name.removeSuffix(".in")
        val importedConfFile = inputMethodDir.resolveDirectChild(importedConfName)
        if (importedConfFile.exists()) {
            errorRuntime(R.string.table_already_exists, importedConfName)
        }
        TableBasedInputMethod.new(confFile)
        val table = Dictionary.new(dictFile)
            ?: errorRuntime(
                R.string.invalid_table_dict,
                "Unsupported dictionary file: ${dictFile.name}"
            )
        val converted = try {
            val convertedFile = File.createTempFile(
                "table-converted-",
                ".dict",
                dictFile.parentFile,
            )
            table.toLibIMEDictionary(convertedFile)
        } catch (failure: Throwable) {
            errorRuntime(R.string.invalid_table_dict, failure.message, failure)
        }
        val tableFile = findAvailableTableFile(
            tableDicDir,
            TableBasedInputMethod.fixedTableFileName(table.name),
        )
        writeImportJournal(importedConfFile, tableFile)
        return runWithRollback(
            rollback = {
                cleanupTableImportTransaction(importJournalFile, importedConfFile, tableFile)
            },
        ) {
            try {
                confFile.inputStream().use { input ->
                    installNewFileAtomically(input, inputMethodDir, importedConfName)
                }
            } catch (_: FileAlreadyExistsException) {
                errorRuntime(R.string.table_already_exists, importedConfName)
            }
            converted.file.inputStream().use { input ->
                installNewFileAtomically(input, tableDicDir, tableFile.name)
            }
            val im = TableBasedInputMethod.new(importedConfFile)
            im.tableFileName = tableFile.name
            im.table = LibIMEDictionary(tableFile)
            im.save()
            importJournalFile.removeIfExists().getOrThrow()
            im
        }
    }

    fun delete(im: TableBasedInputMethod): Result<Unit> = runTableOperation {
        runCatching {
            prepareForMutation()
            val configurationFile = inputMethodDir.resolveDirectChild(im.file.name)
            check(configurationFile == im.file.canonicalFile) {
                "Cannot delete an unmanaged table input method: ${im.file.path}"
            }
            val dictionaryFile = tableDicDir.resolveDirectChild(im.tableFileName)
            deleteTableInputMethodFiles(
                importJournalFile,
                configurationFile,
                dictionaryFile,
                publishJournal = { writeImportJournal(configurationFile, dictionaryFile) },
                onCleanupFailure = ::reportImportCleanupFailure,
            ).getOrThrow()
        }.onSuccess {
            im.table = null
        }
    }

    fun replaceTableDict(
        im: TableBasedInputMethod,
        dictName: String,
        dictStream: InputStream
    ): Result<LibIMEDictionary> = runTableOperation {
        runCatching {
            dictStream.use { dictInput ->
                prepareForMutation()
                requireExistingTableInputMethod(im.file)
                withTempDir { tempDir ->
                    val dictFile = File(tempDir, dictName.safeFileName()).also {
                        it.outputStream().use(dictInput::copyTo)
                    }
                    val dict = Dictionary.new(dictFile)
                        ?: errorRuntime(
                            R.string.invalid_table_dict,
                            "Unsupported dictionary file: ${dictFile.name}"
                        )
                    val destination = File(tableDicDir, im.tableFileName)
                    try {
                        val converted = dict.toLibIMEDictionary(File(tempDir, im.tableFileName))
                        replaceFileAtomically(destination) { staged ->
                            converted.file.copyTo(staged, overwrite = true)
                        }
                    } catch (failure: Throwable) {
                        errorRuntime(R.string.invalid_table_dict, failure.message, failure)
                    }
                    LibIMEDictionary(destination)
                }
            }
        }
    }

    private fun prepareForMutation() {
        cleanupTableImportJournalStaging(importJournalFile.parentFile)
            .forEach { it.onFailure(::reportImportCleanupFailure) }
        recoverPendingImport()
        check(!importJournalFile.exists()) {
            "Cannot modify table data while a previous table operation requires recovery"
        }
    }

    private fun writeImportJournal(configurationFile: File, dictionaryFile: File) {
        val parent = importJournalFile.parentFile
            ?: error("Cannot resolve table import journal parent")
        parent.ensureDirectory()
        val staged = File.createTempFile(
            TABLE_IMPORT_JOURNAL_STAGING_PREFIX,
            TABLE_IMPORT_JOURNAL_STAGING_SUFFIX,
            parent,
        )
        runWithCleanup(
            cleanup = { staged.removeIfExists() },
            onCleanupFailure = ::reportImportCleanupFailure,
        ) {
            staged.writeText(
                json.encodeToString(
                    TableImportJournal(configurationFile.name, dictionaryFile.name)
                )
            )
            Os.rename(staged.path, importJournalFile.path)
        }
    }

    private fun recoverPendingImport() {
        if (!importJournalFile.exists() || !importJournalFile.isFile) return
        val pending = runCatching {
            json.decodeFromString<TableImportJournal>(importJournalFile.readText())
        }.getOrElse { failure ->
            Timber.e(failure, "Failed to read table transaction journal")
            return
        }
        val files = runCatching {
            inputMethodDir.resolveDirectChild(pending.configurationFileName) to
                tableDicDir.resolveDirectChild(pending.dictionaryFileName)
        }.getOrElse { failure ->
            Timber.e(failure, "Invalid table transaction journal")
            return
        }
        cleanupTableImportTransaction(importJournalFile, files.first, files.second)
            .forEach { it.onFailure(::reportImportCleanupFailure) }
    }

    private fun cleanupLegacyTableArtifacts() {
        val referenced = inputMethodDir.listFiles()
            ?.mapNotNull { configurationFile ->
                runCatching {
                    TableBasedInputMethod.new(configurationFile).tableFileName
                }.getOrNull()
            }
            ?.toSet()
            .orEmpty()
        cleanupTableDictionaryStaging(tableDicDir, referenced)
            .forEach { it.onFailure(::reportImportCleanupFailure) }
        cleanupUnreferencedEmptyTableFiles(tableDicDir, referenced)
            .forEach { it.onFailure(::reportImportCleanupFailure) }
    }

    private fun reportImportCleanupFailure(failure: Throwable) {
        Timber.w(failure, "Failed to clean table transaction artifact")
    }

    @JvmStatic
    external fun tableDictConv(src: String, dest: String, mode: Boolean)

    @JvmStatic
    external fun checkTableDictFormat(src: String, user: Boolean = false): Boolean

    const val MODE_BIN_TO_TXT = true
    const val MODE_TXT_TO_BIN = false
}

internal fun cleanupTableImportFiles(
    configurationFile: File?,
    dictionaryFile: File?,
): List<Result<Unit>> = listOfNotNull(configurationFile, dictionaryFile)
    .map(File::removeIfExists)

internal fun cleanupTableImportTransaction(
    journalFile: File,
    configurationFile: File?,
    dictionaryFile: File?,
): List<Result<Unit>> {
    val publishedResults = cleanupTableImportFiles(configurationFile, dictionaryFile)
    return buildList {
        addAll(publishedResults)
        if (publishedResults.all(Result<Unit>::isSuccess)) {
            add(journalFile.removeIfExists())
        }
    }
}

internal fun deleteTableInputMethodFiles(
    journalFile: File,
    configurationFile: File,
    dictionaryFile: File,
    publishJournal: () -> Unit,
    onCleanupFailure: (Throwable) -> Unit,
): Result<Unit> = runCatching {
    publishJournal()
    val cleanupResults = cleanupTableImportTransaction(
        journalFile,
        configurationFile,
        dictionaryFile,
    )
    cleanupResults.first().exceptionOrNull()?.let { failure ->
        failure.addSuppressedFailures(cleanupResults)
        throw failure
    }
    cleanupResults.forEach { it.onFailure(onCleanupFailure) }
}

internal fun isTableDictionaryStagingFile(fileName: String): Boolean =
    fileName.startsWith(TABLE_DICTIONARY_STAGING_PREFIX) &&
            fileName.endsWith(TABLE_DICTIONARY_STAGING_SUFFIX)

internal fun isTableImportJournalStagingFile(fileName: String): Boolean =
    fileName.startsWith(TABLE_IMPORT_JOURNAL_STAGING_PREFIX) &&
            fileName.endsWith(TABLE_IMPORT_JOURNAL_STAGING_SUFFIX)

internal fun cleanupTableDictionaryStaging(
    directory: File,
    referencedFileNames: Set<String> = emptySet(),
): List<Result<Unit>> =
    directory.listFiles()
        ?.filter { file ->
            file.isFile &&
                    file.name !in referencedFileNames &&
                    isTableDictionaryStagingFile(file.name)
        }
        ?.map(File::removeIfExists)
        .orEmpty()

internal fun cleanupTableImportJournalStaging(directory: File?): List<Result<Unit>> =
    directory?.listFiles()
        ?.filter { file -> file.isFile && isTableImportJournalStagingFile(file.name) }
        ?.map(File::removeIfExists)
        .orEmpty()

internal fun cleanupUnreferencedEmptyTableFiles(
    directory: File,
    referencedFileNames: Set<String>,
): List<Result<Unit>> = directory.listFiles()
    ?.filter { file ->
        file.isFile &&
                file.name.endsWith(TABLE_DICTIONARY_STAGING_SUFFIX) &&
                file.length() == 0L &&
                file.name !in referencedFileNames
    }
    ?.map(File::removeIfExists)
    .orEmpty()

internal fun findAvailableTableFile(directory: File, preferredName: String): File {
    val extension = preferredName.substringAfterLast('.', missingDelimiterValue = "")
    check(extension.isNotEmpty()) { "Dictionary file name must have an extension: ${preferredName}" }
    val baseName = preferredName.removeSuffix(".${extension}")
    var suffix = 1
    while (true) {
        val fileName = if (suffix == 1) preferredName else "${baseName} (${suffix}).${extension}"
        val candidate = directory.resolveDirectChild(fileName)
        if (!candidate.exists()) return candidate
        suffix += 1
    }
}
