/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import android.system.Os
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.utils.safeFileName
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.externalFilesDirOrFilesDir
import org.fcitx.fcitx5.android.utils.errorArg
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.resolveDirectChild
import org.fcitx.fcitx5.android.utils.withTempDir
import java.io.File
import java.io.InputStream

object QuickPhraseManager {

    private val builtinQuickPhraseDir = File(
        DataManager.dataDir, "usr/share/fcitx5/data/quickphrase.d"
    )

    private val customQuickPhraseDir = File(
        appContext.externalFilesDirOrFilesDir, "data/data/quickphrase.d"
    ).also { it.mkdirs() }

    fun listQuickPhrase(): List<QuickPhrase> {
        val builtin = listDir(builtinQuickPhraseDir) { file ->
            BuiltinQuickPhrase(file, File(customQuickPhraseDir, file.name))
        }
        val custom = listDir(customQuickPhraseDir) { file ->
            CustomQuickPhrase(file).takeUnless { cq -> builtin.any { cq.name == it.name } }
        }
        return builtin + custom
    }

    fun newEmpty(name: String): CustomQuickPhrase {
        val file = reserveQuickPhraseFile(customQuickPhraseDir, "$name.${QuickPhrase.EXT}")
        return CustomQuickPhrase(file)
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
            val dest = file.inputStream().use { input ->
                installQuickPhraseFile(
                    input,
                    customQuickPhraseDir,
                    target.fileName,
                ) { staged, destination ->
                    Os.rename(staged.path, destination.path)
                }
            }
            CustomQuickPhrase(dest)
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


}

internal fun reserveQuickPhraseFile(directory: File, fileName: String): File {
    val file = directory.resolveDirectChild(fileName)
    if (!file.createNewFile()) throw FileAlreadyExistsException(file)
    return file
}

internal fun installQuickPhraseFile(
    stream: InputStream,
    directory: File,
    fileName: String,
    publish: (File, File) -> Unit,
): File {
    val staged = File.createTempFile("quickphrase-import-", ".staged", directory)
    var destination: File? = null
    var published = false
    try {
        staged.outputStream().use { output -> stream.copyTo(output) }
        destination = reserveQuickPhraseFile(directory, fileName)
        publish(staged, destination)
        published = true
        return destination
    } finally {
        if (!published) destination?.delete()
        staged.delete()
    }
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
