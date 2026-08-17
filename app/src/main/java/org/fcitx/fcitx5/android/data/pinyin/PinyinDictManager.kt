/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.utils.cleanupStagedFileInstalls
import org.fcitx.fcitx5.android.utils.safeFileName
import org.fcitx.fcitx5.android.utils.withTempDir
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.externalFilesDirOrFilesDir
import org.fcitx.fcitx5.android.utils.errorArg
import org.fcitx.fcitx5.android.utils.ensureDirectory
import org.fcitx.fcitx5.android.utils.moveToWithoutReplacing
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.resolveDirectChild
import org.fcitx.fcitx5.android.utils.runWithCleanup
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val pinyinDictionaryOperationLock = ReentrantLock()

internal fun <T> runPinyinDictionaryOperation(block: () -> T): T =
    pinyinDictionaryOperationLock.withLock(block)

object PinyinDictManager {

    private const val IMPORT_STAGING_PREFIX = ".pinyin-import-"
    private const val IMPORT_STAGING_SUFFIX = ".staged"

    private val pinyinDicDir = File(
        appContext.externalFilesDirOrFilesDir, "data/pinyin/dictionaries"
    ).also { directory ->
        directory.ensureDirectory()
        cleanupStagedImports(directory)
        cleanupStagedFileInstalls(directory)
    }

    private val builtinPinyinDictDir = File(
        DataManager.dataDir, "usr/share/fcitx5/pinyin/dictionaries"
    )

    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)

    private val scel2org5 by lazy { File(nativeDir, scel2org5Name) }

    fun listDictionaries(): List<PinyinDictionary> = runPinyinDictionaryOperation {
        val builtin = mutableListOf<PinyinDictionary>()
        builtinPinyinDictDir.listFiles()?.forEach {
            if (it.extension == PinyinDictionary.Type.LibIME.ext) {
                builtin.add(BuiltinDictionary(it))
            }
        }
        builtin.sortBy { it.name }
        val user = mutableListOf<PinyinDictionary>()
        pinyinDicDir.listFiles()?.forEach {
            PinyinDictionary.new(it)?.let { dict ->
                if (dict is LibIMEDictionary) {
                    user.add(dict)
                }
            }
        }
        user.sortBy { it.name }
        builtin + user
    }

    fun importFromFile(
        file: File,
        destinationName: String = file.name,
    ): Result<LibIMEDictionary> = runCatching {
        val raw = PinyinDictionary.new(file)
            ?: errorArg(R.string.exception_dict_filename, file.path)
        val target = pinyinDictionaryImportTarget(destinationName)
            ?: errorArg(R.string.exception_dict_filename, destinationName)
        val destination = pinyinDicDir.resolveDirectChild(target.destinationFileName)
        withTempDir { tempDir ->
            val converted = raw.toLibIMEDictionary(File(tempDir, destination.name))
            val staged = File.createTempFile(
                IMPORT_STAGING_PREFIX,
                IMPORT_STAGING_SUFFIX,
                pinyinDicDir,
            )
            runWithCleanup(
                cleanup = { staged.removeIfExists() },
                onCleanupFailure = { failure ->
                    Timber.w(failure, "Failed to remove staged pinyin dictionary: ${staged.path}")
                },
            ) {
                converted.file.copyTo(staged, overwrite = true)
                val published = try {
                    publishPinyinDictionary(
                        staged,
                        destination,
                        validate = {
                            if (pinyinDictionaryEntryExists(
                                    pinyinDicDir,
                                    builtinPinyinDictDir,
                                    target.entryName,
                                )
                            ) {
                                throw FileAlreadyExistsException(destination)
                            }
                        },
                    )
                } catch (_: FileAlreadyExistsException) {
                    errorArg(R.string.dict_already_exists)
                }
                LibIMEDictionary(published).also { Timber.d("Converted $raw to $it") }
            }
        }
    }

    fun importFromInputStream(stream: InputStream, name: String): Result<LibIMEDictionary> =
        runCatching {
            val target = pinyinDictionaryImportTarget(name)
                ?: errorArg(R.string.exception_dict_filename, name)
            withTempDir { tempDir ->
                val tempFile = tempDir.resolve(target.sourceFileName)
                stream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                importFromFile(tempFile, target.sourceFileName).getOrThrow()
            }
        }

    fun setEnabled(dictionary: LibIMEDictionary, enabled: Boolean): Boolean =
        runPinyinDictionaryOperation {
            runCatching {
                val file = managedPinyinDictionaryFile(pinyinDicDir, dictionary.file)
                check(file.isFile) { "Cannot find pinyin dictionary: ${file.path}" }
                if (enabled) dictionary.enable() else dictionary.disable()
            }.onFailure { failure ->
                Timber.w(failure, "Failed to change pinyin dictionary state: ${dictionary.file.path}")
            }.getOrDefault(false)
        }

    fun delete(dictionary: LibIMEDictionary): Result<Unit> = runPinyinDictionaryOperation {
        runCatching {
            managedPinyinDictionaryFile(pinyinDicDir, dictionary.file)
                .removeIfExists()
                .getOrThrow()
        }
    }

    fun sougouDictConv(src: String, dest: String) {
        val process = ProcessBuilder(scel2org5.absolutePath, "-o", dest, src)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
            }
            .start()
        val (exitCode, output) = collectProcessOutput(process)
        if (exitCode != 0) {
            throw IOException(output)
        }
    }

    @JvmStatic
    external fun pinyinDictConv(src: String, dest: String, mode: Boolean)

    const val MODE_BIN_TO_TXT = true
    const val MODE_TXT_TO_BIN = false
    private const val scel2org5Name = "libscel2org5.so"

    private fun cleanupStagedImports(directory: File) {
        directory.listFiles()
            ?.filter { isPinyinImportStagingFile(it.name) }
            ?.forEach { staged ->
                staged.removeIfExists().onFailure { failure ->
                    Timber.w(failure, "Failed to remove stale pinyin import: ${staged.path}")
                }
            }
    }
}

internal fun isPinyinImportStagingFile(fileName: String): Boolean =
    fileName.startsWith(".pinyin-import-") && fileName.endsWith(".staged")

internal fun publishPinyinDictionary(
    staged: File,
    destination: File,
    validate: () -> Unit = {},
    move: (File, File) -> Boolean = { source, target ->
        source.moveToWithoutReplacing(target)
    },
): File = runPinyinDictionaryOperation {
    validate()
    if (!move(staged, destination)) {
        if (destination.exists()) throw FileAlreadyExistsException(destination)
        throw IOException("Cannot publish pinyin dictionary: ${destination.path}")
    }
    check(destination.isFile) {
        "Failed to publish pinyin dictionary: ${destination.path}"
    }
    destination
}

internal data class PinyinDictionaryImportTarget(
    val sourceFileName: String,
    val entryName: String,
) {
    val destinationFileName = "$entryName.${PinyinDictionary.Type.LibIME.ext}"
}

internal fun pinyinDictionaryImportTarget(fileName: String): PinyinDictionaryImportTarget? {
    val safeFileName = fileName.safeFileName()
    val type = PinyinDictionary.Type.fromFileName(safeFileName) ?: return null
    val disabledSuffix =
        ".${PinyinDictionary.Type.LibIME.ext}.${LibIMEDictionary.DISABLE}"
    val suffix = if (safeFileName.endsWith(disabledSuffix)) disabledSuffix else ".${type.ext}"
    val entryName = safeFileName.removeSuffix(suffix)
    if (entryName.isBlank() || entryName == "." || entryName == "..") return null
    return PinyinDictionaryImportTarget(safeFileName, entryName)
}

internal fun pinyinDictionaryEntryExists(
    userDirectory: File,
    builtinDirectory: File,
    entryName: String,
): Boolean {
    val enabledFileName = "$entryName.${PinyinDictionary.Type.LibIME.ext}"
    val disabledFileName = "$enabledFileName.${LibIMEDictionary.DISABLE}"
    return userDirectory.resolveDirectChild(enabledFileName).exists() ||
            userDirectory.resolveDirectChild(disabledFileName).exists() ||
            builtinDirectory.resolveDirectChild(enabledFileName).exists()
}

internal fun managedPinyinDictionaryFile(directory: File, file: File): File {
    val managed = directory.resolveDirectChild(file.name)
    check(managed == file.canonicalFile) { "Unmanaged pinyin dictionary: ${file.path}" }
    return managed
}

private const val MAX_PROCESS_OUTPUT_CHARS = 64 * 1024

internal fun collectProcessOutput(process: Process): Pair<Int, String> {
    process.outputStream.close()
    val output = buildString {
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val readCount = reader.read(buffer)
                if (readCount < 0) break
                if (length < MAX_PROCESS_OUTPUT_CHARS) {
                    append(buffer, 0, minOf(readCount, MAX_PROCESS_OUTPUT_CHARS - length))
                }
            }
        }
    }
    return process.waitFor() to output
}
