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
import org.fcitx.fcitx5.android.utils.ensureDirectory
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.runWithCleanup
import org.fcitx.fcitx5.android.utils.runWithCleanups
import org.fcitx.fcitx5.android.utils.runWithRollback
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val USER_DATA_IMPORT_DIRECTORY_PREFIX = ".user-data-import-"
private const val LEGACY_USER_DATA_IMPORT_DIRECTORY_PREFIX = "fcitx-"
private const val USER_DATA_IMPORT_JOURNAL_STAGING_PREFIX = "user-data-import-"
private const val USER_DATA_IMPORT_JOURNAL_STAGING_SUFFIX = ".journal"

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

internal enum class ImportRecoveryAction {
    NONE,
    REMOVE_TARGET,
    RESTORE_BACKUP,
    REPLACE_TARGET_WITH_BACKUP,
    UNRECOVERABLE,
}

internal fun determineImportRecoveryAction(
    targetExists: Boolean,
    stagedExists: Boolean,
    backupExpected: Boolean,
    backupExists: Boolean,
): ImportRecoveryAction = when {
    backupExists && !backupExpected -> ImportRecoveryAction.UNRECOVERABLE
    backupExists && targetExists -> ImportRecoveryAction.REPLACE_TARGET_WITH_BACKUP
    backupExists -> ImportRecoveryAction.RESTORE_BACKUP
    backupExpected && !targetExists -> ImportRecoveryAction.UNRECOVERABLE
    backupExpected -> ImportRecoveryAction.NONE
    stagedExists && targetExists -> ImportRecoveryAction.UNRECOVERABLE
    !stagedExists && targetExists -> ImportRecoveryAction.REMOVE_TARGET
    else -> ImportRecoveryAction.NONE
}

internal fun isUserDataImportStagingDirectory(fileName: String): Boolean =
    fileName.startsWith(USER_DATA_IMPORT_DIRECTORY_PREFIX) ||
            fileName.startsWith(LEGACY_USER_DATA_IMPORT_DIRECTORY_PREFIX)

internal fun isUserDataImportJournalStagingFile(fileName: String): Boolean =
    fileName.startsWith(USER_DATA_IMPORT_JOURNAL_STAGING_PREFIX) &&
            fileName.endsWith(USER_DATA_IMPORT_JOURNAL_STAGING_SUFFIX)

internal fun cleanupAbandonedUserDataImport(
    journal: File,
    importDirectoryParents: Iterable<File>,
    removeFile: (File) -> Result<Unit> = FileUtil::removeFile,
): List<Result<Unit>> {
    if (journal.exists()) return emptyList()
    val abandoned = buildList {
        journal.parentFile?.listFiles()
            ?.filterTo(this) { file ->
                file.isFile && isUserDataImportJournalStagingFile(file.name)
            }
        importDirectoryParents
            .distinctBy { parent -> parent.absoluteFile.normalize().path }
            .forEach { parent ->
                parent.listFiles()
                    ?.filterTo(this) { file ->
                        file.isDirectory && isUserDataImportStagingDirectory(file.name)
                    }
            }
    }
    return abandoned.distinctBy { file -> file.absoluteFile.normalize().path }
        .map(removeFile)
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
    private val importDirectoryParents get() =
        listOf(sharedPrefsDir, dataBasesDir, externalDir, recentlyUsedDir)
            .mapNotNull(File::getParentFile)

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
        val backup = createTempDir(parent, USER_DATA_IMPORT_DIRECTORY_PREFIX)
        backup.removeIfExists().getOrThrow()
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
        parent.ensureDirectory()
        val staged = createTempDir(parent, USER_DATA_IMPORT_DIRECTORY_PREFIX)
        return runWithRollback(
            rollback = { listOf(FileUtil.removeFile(staged)) },
        ) {
            check(source.copyRecursively(staged, overwrite = true)) {
                "Failed to stage imported user data: ${source.path}"
            }
            directory.preserveExistingFiles?.invoke(target, staged)
            val backup = target.takeIf(File::exists)?.let { createImportBackupPath(parent) }
            StagedImportDirectory(target, staged, backup)
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

    private fun cleanupStagedImportDirectories(
        directories: List<StagedImportDirectory>,
    ): List<Result<Unit>> = directories.flatMap { directory ->
        listOfNotNull(directory.staged, directory.backup).map(FileUtil::removeFile)
    }

    private fun reportImportCleanupFailure(failure: Throwable) {
        Timber.w(failure, "Failed to clean user data import transaction")
    }

    private fun writeImportJournal(directories: List<StagedImportDirectory>) {
        val journal = importJournalFile
        val parent = journal.parentFile ?: error("Cannot resolve import journal parent")
        parent.ensureDirectory()
        val staged = File.createTempFile(
            USER_DATA_IMPORT_JOURNAL_STAGING_PREFIX,
            USER_DATA_IMPORT_JOURNAL_STAGING_SUFFIX,
            parent,
        )
        runWithCleanup(
            cleanup = { staged.removeIfExists() },
            onCleanupFailure = ::reportImportCleanupFailure,
        ) {
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
        }
    }

    private fun deleteImportJournal(): Result<Unit> = importJournalFile.removeIfExists()

    private fun cleanupImportTransaction(
        directories: List<StagedImportDirectory>,
        journalWritten: Boolean,
    ): List<Result<Unit>> {
        val cleanupResults = cleanupStagedImportDirectories(directories)
        return buildList {
            addAll(cleanupResults)
            if (journalWritten && cleanupResults.all(Result<Unit>::isSuccess)) {
                add(deleteImportJournal())
            }
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
                when (
                    determineImportRecoveryAction(
                        targetExists = target.exists(),
                        stagedExists = staged.exists(),
                        backupExpected = backup != null,
                        backupExists = backup?.exists() == true,
                    )
                ) {
                    ImportRecoveryAction.NONE -> Unit
                    ImportRecoveryAction.REMOVE_TARGET -> {
                        FileUtil.removeFile(target).getOrThrow()
                    }

                    ImportRecoveryAction.RESTORE_BACKUP -> {
                        Os.rename(requireNotNull(backup).path, target.path)
                    }

                    ImportRecoveryAction.REPLACE_TARGET_WITH_BACKUP -> {
                        FileUtil.removeFile(target).getOrThrow()
                        Os.rename(requireNotNull(backup).path, target.path)
                    }

                    ImportRecoveryAction.UNRECOVERABLE -> {
                        error("Cannot determine safe recovery for imported path: ${target.path}")
                    }
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
        if (!journal.exists()) {
            cleanupAbandonedUserDataImport(journal, importDirectoryParents)
                .forEach { it.onFailure(::reportImportCleanupFailure) }
            return
        }
        if (!journal.isFile) return
        val importJournal = runCatching {
            json.decodeFromString<ImportJournal>(journal.readText())
        }.getOrElse {
            Timber.e(it, "Failed to read user data import journal")
            return
        }
        val completed = importJournal.directories.all { directory ->
            File(directory.targetPath).isDirectory && !File(directory.stagedPath).exists()
        }
        val recovered = completed || recoverInterruptedImport(importJournal.directories)
        if (!recovered) return
        val cleanupResults = importJournal.directories.flatMap { directory ->
            listOfNotNull(directory.backupPath, directory.stagedPath)
                .map(::File)
                .map(FileUtil::removeFile)
        }
        cleanupResults.forEach { it.onFailure(::reportImportCleanupFailure) }
        if (cleanupResults.any(Result<Unit>::isFailure)) return
        deleteImportJournal().onFailure(::reportImportCleanupFailure)
    }

    private fun importDirectories(directories: List<ImportDirectory>) {
        val stagedDirectories = mutableListOf<StagedImportDirectory>()
        var journalWritten = false
        var completed = false
        var rolledBack = false
        runWithCleanups(
            cleanup = {
                if (!journalWritten || completed || rolledBack) {
                    cleanupImportTransaction(stagedDirectories, journalWritten)
                } else {
                    emptyList()
                }
            },
            onCleanupFailure = ::reportImportCleanupFailure,
        ) {
            directories.mapNotNullTo(stagedDirectories, ::stageImportDirectory)
            if (stagedDirectories.isEmpty()) return@runWithCleanups
            writeImportJournal(stagedDirectories)
            journalWritten = true
            try {
                stagedDirectories.forEach(::replaceImportDirectory)
                completed = true
            } catch (e: Exception) {
                rolledBack = restoreImportDirectories(stagedDirectories, e)
                throw e
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
            source.removeIfExists().onFailure(::reportImportCleanupFailure)
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
                        transientFile.removeIfExists().onFailure(::reportImportCleanupFailure)
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
