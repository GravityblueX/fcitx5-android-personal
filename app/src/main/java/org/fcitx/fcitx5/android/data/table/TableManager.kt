/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table

import android.system.Os
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.table.dict.Dictionary
import org.fcitx.fcitx5.android.data.table.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.utils.cleanupStagedFileInstalls
import org.fcitx.fcitx5.android.utils.safeFileName
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.externalFilesDirOrFilesDir
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.ensureDirectory
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.installNewFileAtomically
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.replaceFileAtomically
import org.fcitx.fcitx5.android.utils.runWithCleanup
import org.fcitx.fcitx5.android.utils.runWithRollback
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

object TableManager {

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

    fun inputMethods(): List<TableBasedInputMethod> =
        inputMethodDir.listFiles()?.mapNotNull { confFile ->
            runCatching {
                TableBasedInputMethod.new(confFile).apply {
                    runCatching {
                        table = LibIMEDictionary(File(tableDicDir, tableFileName))
                    }
                }
            }.getOrNull()
        } ?: emptyList()

    fun importFromZip(src: InputStream): Result<TableBasedInputMethod> =
        runCatching {
            ZipInputStream(src).use { zipStream ->
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

    fun importFromConfAndDict(
        confName: String,
        confStream: InputStream,
        dictName: String,
        dictStream: InputStream
    ): Result<TableBasedInputMethod> = runCatching {
        withTempDir { tempDir ->
            val confFile = File(tempDir, confName.safeFileName()).also {
                it.outputStream().use { o -> confStream.use { i -> i.copyTo(o) } }
            }
            val dictFile = File(tempDir, dictName.safeFileName()).also {
                it.outputStream().use { o -> dictStream.use { i -> i.copyTo(o) } }
            }
            importFiles(confFile, dictFile)
        }
    }

    private fun importFiles(confFile: File, dictFile: File): TableBasedInputMethod {
        val importedConfName = confFile.name.removeSuffix(".in")
        val importedConfFile = try {
            confFile.inputStream().use { input ->
                installNewFileAtomically(input, inputMethodDir, importedConfName)
            }
        } catch (_: FileAlreadyExistsException) {
            errorRuntime(R.string.table_already_exists, importedConfName)
        }
        var tableFile: File? = null
        return runWithRollback(
            rollback = { cleanupTableImportFiles(importedConfFile, tableFile) },
        ) {
            val im = TableBasedInputMethod.new(importedConfFile)
            val table = Dictionary.new(dictFile)
                ?: errorRuntime(
                    R.string.invalid_table_dict,
                    "Unsupported dictionary file: ${dictFile.name}"
                )
            val reservedTableFile = reserveTableFile(
                tableDicDir,
                TableBasedInputMethod.fixedTableFileName(table.name)
            )
            tableFile = reservedTableFile
            im.tableFileName = reservedTableFile.name
            val staged = File.createTempFile("table-dict-", ".dict", tableDicDir)
            try {
                runWithCleanup(
                    cleanup = { staged.removeIfExists() },
                    onCleanupFailure = { failure ->
                        Timber.w(failure, "Failed to remove staged table dictionary: ${staged.path}")
                    },
                ) {
                    table.toLibIMEDictionary(staged)
                    Os.rename(staged.path, reservedTableFile.path)
                    im.table = LibIMEDictionary(reservedTableFile)
                }
            } catch (failure: Throwable) {
                errorRuntime(R.string.invalid_table_dict, failure.message, failure)
            }
            im.save()
            im
        }
    }

    fun replaceTableDict(
        im: TableBasedInputMethod,
        dictName: String,
        dictStream: InputStream
    ): Result<LibIMEDictionary> = runCatching {
        withTempDir { tempDir ->
            val dictFile = File(tempDir, dictName.safeFileName()).also {
                it.outputStream().use { o -> dictStream.use { i -> i.copyTo(o) } }
            }
            val dict = Dictionary.new(dictFile)
                ?: errorRuntime(R.string.invalid_table_dict, "Unsupported dictionary file: ${dictFile.name}")
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

    @JvmStatic
    external fun tableDictConv(src: String, dest: String, mode: Boolean)

    @JvmStatic
    external fun checkTableDictFormat(src: String, user: Boolean = false): Boolean

    const val MODE_BIN_TO_TXT = true
    const val MODE_TXT_TO_BIN = false
}

internal fun cleanupTableImportFiles(
    configurationFile: File,
    dictionaryFile: File?,
): List<Result<Unit>> = listOfNotNull(configurationFile, dictionaryFile)
    .map(File::removeIfExists)

internal fun reserveTableFile(directory: File, preferredName: String): File {
    val extension = preferredName.substringAfterLast('.', missingDelimiterValue = "")
    check(extension.isNotEmpty()) { "Dictionary file name must have an extension: ${preferredName}" }
    val baseName = preferredName.removeSuffix(".${extension}")
    var suffix = 1
    while (true) {
        val fileName = if (suffix == 1) preferredName else "${baseName} (${suffix}).${extension}"
        val candidate = directory.resolve(fileName)
        if (candidate.createNewFile()) return candidate
        check(candidate.exists()) { "Cannot reserve dictionary file: ${candidate.path}" }
        suffix += 1
    }
}
