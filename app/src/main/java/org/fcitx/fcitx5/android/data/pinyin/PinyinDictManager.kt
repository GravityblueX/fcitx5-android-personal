/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import android.system.Os
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.utils.safeFileName
import org.fcitx.fcitx5.android.utils.withTempDir
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.externalFilesDirOrFilesDir
import org.fcitx.fcitx5.android.utils.errorArg
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

object PinyinDictManager {

    private const val IMPORT_STAGING_PREFIX = ".pinyin-import-"
    private const val IMPORT_STAGING_SUFFIX = ".staged"

    private val pinyinDicDir = File(
        appContext.externalFilesDirOrFilesDir, "data/pinyin/dictionaries"
    ).also { directory ->
        directory.mkdirs()
        cleanupStagedImports(directory)
    }

    private val builtinPinyinDictDir = File(
        DataManager.dataDir, "usr/share/fcitx5/pinyin/dictionaries"
    )

    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)

    private val scel2org5 by lazy { File(nativeDir, scel2org5Name) }

    fun listDictionaries(): List<PinyinDictionary> {
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
        return builtin + user
    }

    fun importFromFile(file: File, destinationName: String = file.name): Result<LibIMEDictionary> = runCatching {
        val raw =
            PinyinDictionary.new(file) ?: errorArg(R.string.exception_dict_filename, file.path)
        val target = pinyinDictionaryImportTarget(destinationName)
            ?: errorArg(R.string.exception_dict_filename, destinationName)
        val destination = File(pinyinDicDir, target.destinationFileName)
        withTempDir { tempDir ->
            val converted = raw.toLibIMEDictionary(File(tempDir, destination.name))
            val staged = File.createTempFile(
                IMPORT_STAGING_PREFIX,
                IMPORT_STAGING_SUFFIX,
                pinyinDicDir
            )
            try {
                converted.file.copyTo(staged, overwrite = true)
                Os.rename(staged.path, destination.path)
            } finally {
                staged.delete()
            }
        }
        LibIMEDictionary(destination).also { Timber.d("Converted $raw to $it") }
    }

    fun importFromInputStream(stream: InputStream, name: String): Result<LibIMEDictionary> =
        runCatching {
            val target = pinyinDictionaryImportTarget(name)
                ?: errorArg(R.string.exception_dict_filename, name)
            val suffix = target.sourceFileName.substringAfter('.', missingDelimiterValue = "")
            val tempFile = File.createTempFile(
                "pinyin-import-",
                suffix.takeIf { it.isNotEmpty() }?.let { ".$it" },
                appContext.cacheDir
            )
            try {
                stream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                importFromFile(tempFile, target.sourceFileName).getOrThrow()
            } finally {
                tempFile.delete()
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
                if (!staged.delete()) Timber.w("Failed to remove stale pinyin import: ${staged.path}")
            }
    }
}

internal fun isPinyinImportStagingFile(fileName: String): Boolean =
    fileName.startsWith(".pinyin-import-") && fileName.endsWith(".staged")

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
