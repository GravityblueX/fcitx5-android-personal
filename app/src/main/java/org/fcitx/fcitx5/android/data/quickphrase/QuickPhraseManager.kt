/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import android.system.Os
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.utils.cleanupStagedFileInstalls
import org.fcitx.fcitx5.android.utils.safeFileName
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.externalFilesDirOrFilesDir
import org.fcitx.fcitx5.android.utils.errorArg
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.ensureDirectory
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.resolveDirectChild
import org.fcitx.fcitx5.android.utils.runWithCleanup
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val IMPORT_STAGING_PREFIX = ".quickphrase-import-"
private const val IMPORT_STAGING_SUFFIX = ".staged"

private val quickPhraseOperationLock = ReentrantLock()

internal fun <T> runQuickPhraseOperation(block: () -> T): T =
    quickPhraseOperationLock.withLock(block)

object QuickPhraseManager {

    private val builtinQuickPhraseDir = File(
        DataManager.dataDir, "usr/share/fcitx5/data/quickphrase.d"
    )

    private val customQuickPhraseDir = File(
        appContext.externalFilesDirOrFilesDir, "data/data/quickphrase.d"
    ).also { directory ->
        directory.ensureDirectory()
        cleanupStagedImports(directory)
        cleanupStagedFileInstalls(directory)
    }

    fun listQuickPhrase(): List<QuickPhrase> = runQuickPhraseOperation {
        val builtin = listDir(builtinQuickPhraseDir) { file ->
            BuiltinQuickPhrase(file, customQuickPhraseDir.resolveDirectChild(file.name))
        }
        val custom = listDir(customQuickPhraseDir) { file ->
            CustomQuickPhrase(file).takeUnless { cq -> builtin.any { cq.name == it.name } }
        }
        builtin + custom
    }

    fun newEmpty(name: String): CustomQuickPhrase {
        val target = quickPhraseImportTarget("$name.${QuickPhrase.EXT}")
            ?.takeIf { it.entryName == name }
            ?: errorArg(R.string.exception_quickphrase_filename, name)
        return runQuickPhraseOperation {
            requireAvailableQuickPhraseEntry(target)
            CustomQuickPhrase(reserveQuickPhraseFile(customQuickPhraseDir, target.fileName))
        }
    }

    private fun importFromFile(file: File): Result<CustomQuickPhrase> {
        return runCatching {
            val target = quickPhraseImportTarget(file.name)
                ?: errorArg(R.string.exception_quickphrase_filename, file.name)
            // check quickphrase format of each line
            file.readLines().forEachIndexed { idx, line ->
                if (line.isNotBlank() && QuickPhraseEntry.fromLine(line) == null) {
                    errorRuntime(R.string.exception_quickphrase_parse, "\n(${idx + 1}) $line")
                }
            }
            val destination = publishNewQuickPhraseFile(
                file,
                customQuickPhraseDir,
                target.fileName,
                validate = { requireAvailableQuickPhraseEntry(target) },
            )
            CustomQuickPhrase(destination)
        }
    }

    fun importFromInputStream(stream: InputStream, fileName: String): Result<CustomQuickPhrase> =
        runCatching {
            val target = quickPhraseImportTarget(fileName)
                ?: errorArg(R.string.exception_quickphrase_filename, fileName)
            withTempDir { dir ->
                val tempFile = dir.resolve(target.fileName)
                stream.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                importFromFile(tempFile).getOrThrow()
            }
        }

    fun loadData(quickPhrase: QuickPhrase): QuickPhraseData = runQuickPhraseOperation {
        requireExistingQuickPhrase(quickPhrase)
        quickPhrase.loadData()
    }

    fun saveData(quickPhrase: QuickPhrase, data: QuickPhraseData) = runQuickPhraseOperation {
        requireExistingQuickPhrase(quickPhrase)
        quickPhrase.saveData(data)
    }

    fun setEnabled(quickPhrase: QuickPhrase, enabled: Boolean): Boolean =
        runQuickPhraseOperation {
            runCatching {
                requireExistingQuickPhrase(quickPhrase)
                if (enabled) quickPhrase.enable() else quickPhrase.disable()
            }.onFailure { failure ->
                Timber.w(failure, "Failed to change quick phrase state: ${quickPhrase.file.path}")
            }.getOrDefault(false)
        }

    fun reset(quickPhrase: BuiltinQuickPhrase): Result<Unit> = runQuickPhraseOperation {
        runCatching {
            requireExistingQuickPhrase(quickPhrase)
            quickPhrase.deleteOverride().getOrThrow()
        }
    }

    fun delete(quickPhrase: CustomQuickPhrase): Result<Unit> = runQuickPhraseOperation {
        runCatching {
            val file = managedQuickPhraseFile(customQuickPhraseDir, quickPhrase.file)
            check(file.isFile) { "Cannot find quick phrase: ${file.path}" }
            requireCustomQuickPhraseName(quickPhrase)
            file.removeIfExists().getOrThrow()
        }
    }

    private fun <T : QuickPhrase> listDir(
        dir: File,
        block: (File) -> T?
    ): List<T> =
        dir.listFiles()
            ?.mapNotNull { file ->
                file.name.takeIf { name ->
                    name.endsWith(".${QuickPhrase.EXT}") || name.endsWith(".${QuickPhrase.EXT}.${QuickPhrase.DISABLE}")
                }
                    ?.let { block(file) }
            } ?: listOf()

    private fun requireAvailableQuickPhraseEntry(target: QuickPhraseImportTarget) {
        if (quickPhraseEntryExists(
                customQuickPhraseDir,
                builtinQuickPhraseDir,
                target.entryName,
            )
        ) {
            throw FileAlreadyExistsException(
                customQuickPhraseDir.resolveDirectChild(target.fileName)
            )
        }
    }

    private fun requireExistingQuickPhrase(quickPhrase: QuickPhrase) {
        when (quickPhrase) {
            is CustomQuickPhrase -> {
                val file = managedQuickPhraseFile(customQuickPhraseDir, quickPhrase.file)
                check(file.isFile) { "Cannot find quick phrase: ${file.path}" }
                requireCustomQuickPhraseName(quickPhrase)
            }
            is BuiltinQuickPhrase -> {
                val file = managedQuickPhraseFile(builtinQuickPhraseDir, quickPhrase.file)
                check(file.isFile) { "Cannot find builtin quick phrase: ${file.path}" }
                val expectedOverride = customQuickPhraseDir.resolveDirectChild(file.name)
                check(expectedOverride == File(quickPhrase.overrideFilePath).canonicalFile) {
                    "Unmanaged builtin quick phrase override: ${quickPhrase.overrideFilePath}"
                }
            }
            else -> error("Unsupported quick phrase type: ${quickPhrase.javaClass.name}")
        }
    }

    private fun requireCustomQuickPhraseName(quickPhrase: CustomQuickPhrase) {
        val builtin = builtinQuickPhraseDir.resolveDirectChild(
            "${quickPhrase.name}.${QuickPhrase.EXT}"
        )
        check(!builtin.isFile) {
            "Custom quick phrase conflicts with builtin quick phrase: ${quickPhrase.file.path}"
        }
    }

    private fun cleanupStagedImports(directory: File) {
        directory.listFiles()
            ?.filter { file -> file.isFile && isQuickPhraseImportStagingFile(file.name) }
            ?.forEach { staged ->
                staged.removeIfExists().onFailure { failure ->
                    Timber.w(failure, "Failed to remove stale quick phrase import: ${staged.path}")
                }
            }
    }

}

internal fun isQuickPhraseImportStagingFile(fileName: String): Boolean =
    fileName.startsWith(".quickphrase-import-") && fileName.endsWith(".staged")

internal fun publishNewQuickPhraseFile(
    source: File,
    directory: File,
    fileName: String,
    validate: () -> Unit = {},
    publish: (File, File) -> Unit = { staged, destination ->
        Os.rename(staged.path, destination.path)
    },
): File {
    directory.ensureDirectory()
    val destination = directory.resolveDirectChild(fileName)
    val staged = File.createTempFile(
        IMPORT_STAGING_PREFIX,
        IMPORT_STAGING_SUFFIX,
        directory,
    )
    return runWithCleanup(
        cleanup = { staged.removeIfExists() },
        onCleanupFailure = { failure ->
            Timber.w(failure, "Failed to remove staged quick phrase: ${staged.path}")
        },
    ) {
        source.copyTo(staged, overwrite = true)
        runQuickPhraseOperation {
            validate()
            if (destination.exists()) throw FileAlreadyExistsException(destination)
            publish(staged, destination)
            check(destination.isFile) { "Failed to publish quick phrase: ${destination.path}" }
            destination
        }
    }
}

internal fun reserveQuickPhraseFile(directory: File, fileName: String): File {
    val file = directory.resolveDirectChild(fileName)
    if (!file.createNewFile()) throw FileAlreadyExistsException(file)
    return file
}

internal fun quickPhraseEntryExists(
    customDirectory: File,
    builtinDirectory: File,
    entryName: String,
): Boolean {
    val enabledFileName = "$entryName.${QuickPhrase.EXT}"
    val disabledFileName = "$enabledFileName.${QuickPhrase.DISABLE}"
    return customDirectory.resolveDirectChild(enabledFileName).exists() ||
            customDirectory.resolveDirectChild(disabledFileName).exists() ||
            builtinDirectory.resolveDirectChild(enabledFileName).exists()
}

internal fun managedQuickPhraseFile(directory: File, file: File): File {
    val managed = directory.resolveDirectChild(file.name)
    check(managed == file.canonicalFile) { "Unmanaged quick phrase: ${file.path}" }
    return managed
}

internal data class QuickPhraseImportTarget(
    val fileName: String,
    val entryName: String,
)

internal fun quickPhraseImportTarget(fileName: String): QuickPhraseImportTarget? {
    val safeFileName = fileName.safeFileName()
    val suffix = ".${QuickPhrase.EXT}"
    if (!safeFileName.endsWith(suffix)) return null
    val entryName = safeFileName.removeSuffix(suffix)
    if (entryName.isBlank() || entryName == "." || entryName == "..") return null
    return QuickPhraseImportTarget(safeFileName, entryName)
}
