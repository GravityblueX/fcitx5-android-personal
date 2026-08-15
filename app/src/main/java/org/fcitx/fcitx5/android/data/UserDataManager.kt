/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.system.Os
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.encodeToString
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.FileUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.externalFilesDirOrFilesDir
import org.fcitx.fcitx5.android.utils.createTempDir
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal fun isSafeUserDataExportPath(file: File, sourceDir: File): Boolean {
    val relative = runCatching { file.relativeTo(sourceDir) }.getOrNull() ?: return false
    val canonicalSource = runCatching { sourceDir.canonicalFile }.getOrNull() ?: return false
    val expectedPath = canonicalSource.resolve(relative.path).normalize()
    val canonicalSourcePrefix = canonicalSource.path + File.separator
    if (expectedPath != canonicalSource &&
        !expectedPath.path.startsWith(canonicalSourcePrefix)
    ) {
        return false
    }
    return runCatching { file.canonicalFile == expectedPath }.getOrDefault(false)
}

internal fun writeUserDataFileTree(
    sourceDir: File,
    destinationPrefix: String,
    destination: ZipOutputStream,
    include: (File) -> Boolean = { true },
) {
    destination.putNextEntry(ZipEntry("$destinationPrefix/"))
    destination.closeEntry()
    sourceDir.walkTopDown()
        .onEnter { directory -> isSafeUserDataExportPath(directory, sourceDir) }
        .forEach { file ->
            val relative = file.relativeTo(sourceDir)
            if (relative.path.isEmpty() ||
                !isSafeUserDataExportPath(file, sourceDir) ||
                !include(file)
            ) {
                return@forEach
            }
            val destinationPath = "$destinationPrefix/${relative.invariantSeparatorsPath}"
            if (file.isDirectory) {
                destination.putNextEntry(ZipEntry("$destinationPath/"))
                destination.closeEntry()
            } else if (file.isFile) {
                destination.putNextEntry(ZipEntry(destinationPath))
                file.inputStream().use { it.copyTo(destination) }
                destination.closeEntry()
            }
        }
}

object UserDataManager {

    private const val PRODUCT_PACKAGE_NAME = "org.fcitx.fcitx17.android"
    private const val OFFICIAL_PACKAGE_NAME = "org.fcitx.fcitx5.android"
    private const val OFFICIAL_DEBUG_PACKAGE_NAME = "$OFFICIAL_PACKAGE_NAME.debug"
    private const val LEGACY_PERSONAL_PACKAGE_NAME =
        "$OFFICIAL_PACKAGE_NAME.debug17yizuka"

    private val json = Json { prettyPrint = true }

    private val compatiblePackageNames = setOf(
        BuildConfig.APPLICATION_ID,
        PRODUCT_PACKAGE_NAME,
        OFFICIAL_PACKAGE_NAME,
        OFFICIAL_DEBUG_PACKAGE_NAME,
        LEGACY_PERSONAL_PACKAGE_NAME
    )

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )


    @Serializable
    private data class ImportJournal(
        val directories: List<ImportJournalDirectory>,
    )

    @Serializable
    private data class ImportJournalDirectory(
        val targetPath: String,
        val stagedPath: String,
        val backupPath: String?,
    )

    private val importJournalFile get() = appContext.filesDir.resolve(".user-data-import")
    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.externalFilesDirOrFilesDir
    private val recentlyUsedDir = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs
            writeUserDataFileTree(sharedPrefsDir, "shared_prefs", zipStream) { file ->
                file.isDirectory || !isTransientSharedPreferenceFile(file.name)
            }
            // databases
            writeUserDataFileTree(dataBasesDir, "databases", zipStream)
            // external
            writeUserDataFileTree(externalDir, "external", zipStream)
            // recently_used moved to SharedPreference and shoud not be exported
            // metadata
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val metadata = Metadata(
                pkgInfo.packageName,
                pkgInfo.versionCodeCompat,
                Const.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    private data class ImportDirectory(
        val source: File,
        val target: File,
        val preserveExistingFiles: ((File, File) -> Unit)? = null,
    )

    private class StagedImportDirectory(
        val target: File,
        val staged: File,
        var backup: File?,
    ) {
        var originalMoved = false
        var stagedMoved = false
    }

    private fun createImportBackupPath(parent: File): File {
        val backup = createTempDir(parent)
        check(backup.delete()) { "Cannot prepare import backup: ${backup.path}" }
        return backup
    }
    private fun stageImportDirectory(directory: ImportDirectory): StagedImportDirectory? {
        val source = directory.source
        val target = directory.target
        val exists = source.exists()
        val isDir = source.isDirectory
        if (!exists || !isDir) {
            Timber.w("Cannot import user data: path='${source.path}', exists=$exists, isDir=$isDir")
            return null
        }
        val parent = target.parentFile ?: error("Cannot resolve import directory parent: ${target.path}")
        check(parent.mkdirs() || parent.isDirectory) {
            "Cannot create import directory parent: $parent"
        }
        val staged = createTempDir(parent)
        try {
            check(source.copyRecursively(staged, overwrite = true)) {
                "Failed to stage imported user data: ${source.path}"
            }
            directory.preserveExistingFiles?.invoke(target, staged)
            val backup = target.takeIf(File::exists)?.let { createImportBackupPath(parent) }
            return StagedImportDirectory(target, staged, backup)
        } catch (e: Exception) {
            staged.deleteRecursively()
            throw e
        }
    }

    private fun replaceImportDirectory(directory: StagedImportDirectory) {
        val target = directory.target
        val parent = target.parentFile ?: error("Cannot resolve import directory parent: ${target.path}")
        if (target.exists()) {
            val backup = requireNotNull(directory.backup) {
                "Missing import backup for ${target.path}"
            }
            Os.rename(target.path, backup.path)
            directory.originalMoved = true
        }
        Os.rename(directory.staged.path, target.path)
        directory.stagedMoved = true
    }

    private fun restoreImportDirectories(
        directories: List<StagedImportDirectory>,
        originalFailure: Exception,
    ): Boolean {
        var restored = true
        directories.asReversed().forEach { directory ->
            try {
                if (directory.stagedMoved && directory.target.exists()) {
                    FileUtil.removeFile(directory.target).getOrThrow()
                }
                if (directory.originalMoved) {
                    Os.rename(requireNotNull(directory.backup).path, directory.target.path)
                }
            } catch (rollbackFailure: Exception) {
                restored = false
                originalFailure.addSuppressed(rollbackFailure)
            }
        }
        return restored
    }

    private fun cleanupStagedImportDirectories(directories: List<StagedImportDirectory>) {
        directories.forEach { directory ->
            listOf(directory.staged, directory.backup).filterNotNull().forEach { file ->
                if (file.exists() && !file.deleteRecursively()) {
                    Timber.w("Failed to clean temporary user data import directory: ${file.path}")
                }
            }
        }
    }

    private fun writeImportJournal(directories: List<StagedImportDirectory>) {
        val journal = importJournalFile
        val parent = journal.parentFile ?: error("Cannot resolve import journal parent")
        check(parent.mkdirs() || parent.isDirectory) {
            "Cannot create import journal parent: $parent"
        }
        val staged = File.createTempFile("user-data-import-", ".journal", parent)
        try {
            staged.writeText(
                json.encodeToString(
                    ImportJournal(
                        directories.map { directory ->
                            ImportJournalDirectory(
                                directory.target.path,
                                directory.staged.path,
                                directory.backup?.path,
                            )
                        }
                    )
                )
            )
            Os.rename(staged.path, journal.path)
        } finally {
            staged.delete()
        }
    }

    private fun deleteImportJournal() {
        val journal = importJournalFile
        if (journal.exists() && !journal.delete()) {
            Timber.w("Failed to remove user data import journal: ${journal.path}")
        }
    }

    private fun recoverInterruptedImport(
        directories: List<ImportJournalDirectory>,
    ): Boolean {
        var recovered = true
        directories.asReversed().forEach { directory ->
            try {
                val target = File(directory.targetPath)
                val staged = File(directory.stagedPath)
                val backup = directory.backupPath?.let(::File)
                val stagedMoved = !staged.exists() && target.exists()
                if (backup?.exists() == true) {
                    if (stagedMoved) {
                        FileUtil.removeFile(target).getOrThrow()
                    }
                    if (!target.exists()) {
                        Os.rename(backup.path, target.path)
                    }
                } else if (stagedMoved) {
                    FileUtil.removeFile(target).getOrThrow()
                }
            } catch (e: Exception) {
                recovered = false
                Timber.e(e, "Failed to recover interrupted user data import")
            }
        }
        return recovered
    }

    fun recoverPendingImport() {
        val journal = importJournalFile
        if (!journal.isFile) return
        val importJournal = runCatching {
            json.decodeFromString<ImportJournal>(journal.readText())
        }.getOrElse {
            Timber.e(it, "Failed to read user data import journal")
            return
        }
        val completed = importJournal.directories.all { directory ->
            File(directory.targetPath).exists() && !File(directory.stagedPath).exists()
        }
        val recovered = completed || recoverInterruptedImport(importJournal.directories)
        if (!recovered) return
        importJournal.directories.forEach { directory ->
            listOfNotNull(directory.backupPath, directory.stagedPath).forEach { path ->
                val file = File(path)
                if (file.exists() && !file.deleteRecursively()) {
                    Timber.w("Failed to clean recovered user data import directory: ${file.path}")
                }
            }
        }
        deleteImportJournal()
    }

    private fun importDirectories(directories: List<ImportDirectory>) {
        val stagedDirectories = mutableListOf<StagedImportDirectory>()
        var journalWritten = false
        var completed = false
        var rolledBack = false
        try {
            directories.mapNotNullTo(stagedDirectories, ::stageImportDirectory)
            if (stagedDirectories.isEmpty()) return
            writeImportJournal(stagedDirectories)
            journalWritten = true
            try {
                stagedDirectories.forEach(::replaceImportDirectory)
                completed = true
            } catch (e: Exception) {
                rolledBack = restoreImportDirectories(stagedDirectories, e)
                throw e
            }
        } finally {
            if (!journalWritten || completed || rolledBack) {
                cleanupStagedImportDirectories(stagedDirectories)
                if (journalWritten) deleteImportJournal()
            }
        }
    }

    /**
     * Android's default SharedPreferences filename contains the application ID. Backups made by
     * the official package therefore need to be renamed before they can be read by Fcitx17.
     */
    private fun migrateDefaultSharedPreferences(sourceDir: File, sourcePackageName: String) {
        if (sourcePackageName == BuildConfig.APPLICATION_ID) return
        listOf(".xml", ".xml.bak").forEach { suffix ->
            val source = sourceDir.resolve("${sourcePackageName}_preferences$suffix")
            if (!source.isFile) return@forEach
            val target = sourceDir.resolve("${BuildConfig.APPLICATION_ID}_preferences$suffix")
            source.copyTo(target, overwrite = true)
            if (!source.delete()) {
                Timber.w("Failed to remove migrated preference file: ${source.path}")
            }
        }
    }

    fun import(src: InputStream) = runCatching {
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val extracted = zipStream.extract(tempDir)
                val metadataFile = extracted.find { it.name == "metadata.json" }
                    ?: errorRuntime(R.string.exception_user_data_metadata)
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
                if (metadata.packageName !in compatiblePackageNames)
                    errorRuntime(R.string.exception_user_data_package_name_mismatch)
                if (!hasRequiredUserDataDirectories(tempDir))
                    errorRuntime(R.string.exception_user_data_metadata)
                val importedSharedPrefsDir = File(tempDir, "shared_prefs")
                importedSharedPrefsDir.listFiles()
                    ?.filter { isTransientSharedPreferenceFile(it.name) }
                    ?.forEach { transientFile ->
                        if (!transientFile.delete()) {
                            Timber.w(
                                "Failed to discard imported runtime cache: ${transientFile.path}"
                            )
                        }
                    }
                migrateDefaultSharedPreferences(importedSharedPrefsDir, metadata.packageName)
                importDirectories(
                    listOf(
                        ImportDirectory(
                            importedSharedPrefsDir,
                            sharedPrefsDir,
                            ::preserveTransientSharedPreferenceFiles,
                        ),
                        ImportDirectory(File(tempDir, "databases"), dataBasesDir),
                        ImportDirectory(File(tempDir, "external"), externalDir),
                        // keep importing recently_used for backwords compatibility
                        ImportDirectory(File(tempDir, "recently_used"), recentlyUsedDir),
                    )
                )
                metadata
            }
        }
    }
}

/**
 * ML Kit's model inventory and the handwriting backend's verified-state cache describe files that
 * live outside the user-data archive. Restoring them without the model files can report a model as
 * available when it is not, and Google's MDD preferences may also contain the source package name.
 */
internal fun isTransientSharedPreferenceFile(fileName: String): Boolean {
    val baseName = fileName.removeSuffix(".bak")
    return baseName == "handwriting_recognition.xml" ||
        baseName == "com.google.mlkit.internal.xml" ||
        baseName.startsWith("gms_icing_mdd_")
}

internal fun hasRequiredUserDataDirectories(root: File): Boolean =
    listOf("shared_prefs", "databases", "external").all { directoryName ->
        root.resolve(directoryName).isDirectory
    }

internal fun preserveTransientSharedPreferenceFiles(sourceDir: File, targetDir: File) {
    sourceDir.listFiles()
        ?.filter { it.isFile && isTransientSharedPreferenceFile(it.name) }
        ?.forEach { source ->
            source.copyTo(targetDir.resolve(source.name), overwrite = true)
        }
}
