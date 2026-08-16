package org.fcitx.fcitx5.android.data.theme

import android.system.Os
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.externalFilesDirOrFilesDir
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.installNewFileAtomically
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.replaceFileAtomically
import org.fcitx.fcitx5.android.utils.resolveDirectChild
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal fun Throwable.addSuppressedFailures(results: Iterable<Result<Unit>>) {
    results.mapNotNull(Result<Unit>::exceptionOrNull).forEach(::addSuppressed)
}

object ThemeFilesManager {

    private val dir = File(appContext.externalFilesDirOrFilesDir, "theme").also {
        check(it.mkdirs() || it.isDirectory) { "Cannot create theme directory: $it" }
    }

    private fun themeFile(name: String) = dir.resolveDirectChild("$name.json")

    private fun themeFile(theme: Theme.Custom) = themeFile(theme.name)

    private fun imageFile(path: String) = dir.resolveDirectChild(path)

    private fun isThemeFile(file: File) = runCatching {
        file.canonicalFile.parentFile == dir.canonicalFile
    }.getOrDefault(false)

    private fun isValidThemeName(name: String) = runCatching { themeFile(name) }.isSuccess

    private fun backup(file: File, tempDir: File): File? =
        file.takeIf(File::exists)?.let { source ->
            File.createTempFile("theme-import-", ".backup", tempDir).also { source.copyTo(it) }
        }

    private fun restore(file: File, backup: File?): Result<Unit> = runCatching {
        if (backup == null) {
            file.removeIfExists().getOrThrow()
        } else {
            replaceFileAtomically(file) { staged ->
                backup.copyTo(staged, overwrite = true)
            }
        }
    }

    private fun installImportedFile(source: File, destination: File, replace: Boolean) {
        if (replace) {
            replaceFileAtomically(destination) { staged ->
                source.copyTo(staged, overwrite = true)
            }
        } else {
            source.inputStream().use { input ->
                installNewFileAtomically(input, dir, destination.name)
            }
        }
    }

    fun newCustomBackgroundImages(): Triple<String, File, File> {
        val themeName = UUID.randomUUID().toString()
        val croppedImageFile = File(dir, "$themeName-cropped.png")
        val srcImageFile = File(dir, "$themeName-src")
        return Triple(themeName, croppedImageFile, srcImageFile)
    }

    fun saveThemeFiles(theme: Theme.Custom) {
        val file = themeFile(theme)
        val staged = File.createTempFile("theme-", ".staged", dir)
        try {
            staged.writeText(Json.encodeToString(CustomThemeSerializer, theme))
            Os.rename(staged.path, file.path)
        } finally {
            staged.removeIfExists().onFailure {
                Timber.w(it, "Failed to remove staged theme file: ${staged.path}")
            }
        }
    }

    fun deleteThemeFiles(theme: Theme.Custom): Result<Unit> =
        themeFile(theme).removeIfExists().onSuccess {
            theme.backgroundImage?.let { background ->
                listOf(File(background.croppedFilePath), File(background.srcFilePath))
                    .filter(::isThemeFile)
                    .forEach { file ->
                        file.removeIfExists().onFailure {
                            Timber.w(it, "Failed to remove orphaned theme image: ${file.path}")
                        }
                    }
            }
        }

    fun listThemes(): MutableList<Theme.Custom> {
        val files = dir.listFiles(FileFilter { it.extension == "json" }) ?: return mutableListOf()
        return files
            .sortedByDescending { it.lastModified() } // newest first
            .mapNotNull decode@{
                val (theme, migrated) = runCatching {
                    Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, it.readText())
                }.getOrElse { e ->
                    Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                    return@decode null
                }
                if (!isValidThemeName(theme.name)) {
                    Timber.w("Invalid theme name: ${theme.name}")
                    return@decode null
                }
                if (theme.backgroundImage != null) {
                    val croppedFile = File(theme.backgroundImage.croppedFilePath)
                    val srcFile = File(theme.backgroundImage.srcFilePath)
                    if (!isThemeFile(croppedFile) || !isThemeFile(srcFile) ||
                        !croppedFile.exists() || !srcFile.exists()
                    ) {
                        Timber.w("Cannot find background image file for theme ${theme.name}")
                        return@decode null
                    }
                }
                // Update the saved file if migration happens
                if (migrated) {
                    saveThemeFiles(theme)
                }
                return@decode theme
            }.toMutableList()
    }

    /**
     * [dest] will be closed on finished
     */
    fun exportTheme(theme: Theme.Custom, dest: OutputStream) =
        runCatching {
            ZipOutputStream(dest.buffered()).use { zipStream ->
                // we don't export the internal path of images
                val tweakedTheme = theme.backgroundImage?.let {
                    theme.copy(
                        backgroundImage = theme.backgroundImage.copy(
                            croppedFilePath = theme.backgroundImage.croppedFilePath
                                .substringAfterLast('/'),
                            srcFilePath = theme.backgroundImage.srcFilePath
                                .substringAfterLast('/'),
                        )
                    )
                } ?: theme
                if (tweakedTheme.backgroundImage != null) {
                    requireNotNull(theme.backgroundImage)
                    // write cropped image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.croppedFilePath))
                    File(theme.backgroundImage.croppedFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                    // write src image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.srcFilePath))
                    File(theme.backgroundImage.srcFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                }
                // write json
                zipStream.putNextEntry(ZipEntry("${tweakedTheme.name}.json"))
                zipStream.write(
                    Json.encodeToString(CustomThemeSerializer, tweakedTheme)
                        .encodeToByteArray()
                )
                // done
                zipStream.closeEntry()
            }
        }

    /**
     * @return (newCreated, theme, migrated)
     */
    fun importTheme(src: InputStream): Result<Triple<Boolean, Theme.Custom, Boolean>> =
        runCatching {
            ZipInputStream(src).use { zipStream ->
                withTempDir { tempDir ->
                    val extracted = zipStream.extract(tempDir)
                    val jsonFile = extracted.find { it.extension == "json" && it.isFile }
                        ?: errorRuntime(R.string.exception_theme_json)
                    val (decoded, migrated) = Json.decodeFromString(
                        CustomThemeSerializer.WithMigrationStatus,
                        jsonFile.readText()
                    )
                    if (!isValidThemeName(decoded.name)) errorRuntime(R.string.exception_theme_json)
                    if (ThemeManager.BuiltinThemes.find { it.name == decoded.name } != null)
                        errorRuntime(R.string.exception_theme_name_clash)
                    val oldTheme = ThemeManager.getTheme(decoded.name) as? Theme.Custom
                    val newCreated = oldTheme == null
                    val oldSrcFile = oldTheme?.backgroundImage?.srcFilePath?.let(::File)
                    val oldCroppedFile = oldTheme?.backgroundImage?.croppedFilePath?.let(::File)
                    val themeFile = themeFile(decoded.name)
                    val themeBackup = backup(themeFile, tempDir)
                    val newTheme = decoded.backgroundImage?.let { background ->
                        val srcFile = imageFile(background.srcFilePath)
                        val croppedFile = imageFile(background.croppedFilePath)
                        if (srcFile == croppedFile) errorRuntime(R.string.exception_theme_json)
                        val importedSrcFile = extracted.find { it.name == srcFile.name && it.isFile }
                            ?: errorRuntime(R.string.exception_theme_src_image)
                        val importedCroppedFile = extracted.find { it.name == croppedFile.name && it.isFile }
                            ?: errorRuntime(R.string.exception_theme_cropped_image)
                        val srcFileMatchesOldTheme = oldSrcFile?.canonicalFile == srcFile
                        val croppedFileMatchesOldTheme = oldCroppedFile?.canonicalFile == croppedFile
                        val srcBackup = backup(srcFile, tempDir)
                        val croppedBackup = backup(croppedFile, tempDir)
                        try {
                            installImportedFile(importedSrcFile, srcFile, srcFileMatchesOldTheme)
                            installImportedFile(
                                importedCroppedFile,
                                croppedFile,
                                croppedFileMatchesOldTheme,
                            )
                            decoded.copy(
                                backgroundImage = background.copy(
                                    croppedFilePath = croppedFile.path,
                                    srcFilePath = srcFile.path
                                )
                            ).also(::saveThemeFiles)
                        } catch (e: Exception) {
                            e.addSuppressedFailures(
                                listOf(
                                    restore(srcFile, srcBackup),
                                    restore(croppedFile, croppedBackup),
                                    restore(themeFile, themeBackup),
                                )
                            )
                            throw e
                        }
                    } ?: try {
                        decoded.also(::saveThemeFiles)
                    } catch (e: Exception) {
                        e.addSuppressedFailures(listOf(restore(themeFile, themeBackup)))
                        throw e
                    }
                    val newBackgroundFiles = newTheme.backgroundImage?.let {
                        listOf(File(it.srcFilePath).canonicalFile, File(it.croppedFilePath).canonicalFile)
                    }.orEmpty()
                    listOf(oldSrcFile, oldCroppedFile)
                        .filterNotNull()
                        .filter(::isThemeFile)
                        .filterNot { it.canonicalFile in newBackgroundFiles }
                        .forEach { file ->
                            file.removeIfExists().onFailure {
                                Timber.w(it, "Failed to remove orphaned theme image: ${file.path}")
                            }
                        }
                    Triple(newCreated, newTheme, migrated)
                }
            }
        }

}
