/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
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

    private fun writeFileTree(
        srcDir: File,
        destPrefix: String,
        dest: ZipOutputStream,
        include: (File) -> Boolean = { true },
    ) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "" && include(f)) {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.getExternalFilesDir(null)!!
    private val recentlyUsedDir = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs
            writeFileTree(sharedPrefsDir, "shared_prefs", zipStream) { file ->
                file.isDirectory || !isTransientSharedPreferenceFile(file.name)
            }
            // databases
            writeFileTree(dataBasesDir, "databases", zipStream)
            // external
            writeFileTree(externalDir, "external", zipStream)
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

    private fun copyDir(source: File, target: File) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            check(source.copyRecursively(target, overwrite = true)) {
                "Failed to import user data: ${source.path}"
            }
        } else {
            Timber.w("Cannot import user data: path='${source.path}', exists=$exists, isDir=$isDir")
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
                copyDir(importedSharedPrefsDir, sharedPrefsDir)
                copyDir(File(tempDir, "databases"), dataBasesDir)
                copyDir(File(tempDir, "external"), externalDir)
                // keep importing recently_used for backwords compatibility
                copyDir(File(tempDir, "recently_used"), recentlyUsedDir)
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
