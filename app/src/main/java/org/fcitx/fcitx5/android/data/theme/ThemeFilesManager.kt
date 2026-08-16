package org.fcitx.fcitx5.android.data.theme

import android.system.Os
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.cleanupStagedFileInstalls
import org.fcitx.fcitx5.android.utils.ensureDirectory
import org.fcitx.fcitx5.android.utils.externalFilesDirOrFilesDir
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.replaceFileAtomically
import org.fcitx.fcitx5.android.utils.resolveDirectChild
import org.fcitx.fcitx5.android.utils.runWithCleanup
import org.fcitx.fcitx5.android.utils.runWithRollback
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock

private const val THEME_INSTALL_STAGING_PREFIX = ".theme-install-"
private const val THEME_INSTALL_STAGING_SUFFIX = ".staged"

private val themeFileOperationLock = ReentrantLock()

internal fun <T> runThemeFileOperation(block: () -> T): T =
    themeFileOperationLock.withLock(block)

private fun <T> runThemeFileResultOperation(block: () -> T): Result<T> =
    runThemeFileOperation { runCatching(block) }

object ThemeFilesManager {

    private val dir = File(appContext.externalFilesDirOrFilesDir, "theme").also { directory ->
        directory.ensureDirectory()
        cleanupLegacyThemeMetadataStaging(directory)
        cleanupStagedFileInstalls(directory)
        cleanupStagedThemeInstalls(directory)
        recoverThemeImportTransactions(directory).forEach { result ->
            result.onFailure { failure ->
                Timber.e(failure, "Failed to recover interrupted theme import")
            }
        }
    }

    private fun recoverPendingThemeImports(): Boolean {
        recoverThemeImportTransactions(dir).forEach { result ->
            result.onFailure { failure ->
                Timber.e(failure, "Failed to recover interrupted theme import")
            }
        }
        return !hasUnresolvedThemeImportTransaction(dir)
    }

    private fun requireRecoveredThemeImports() {
        check(recoverPendingThemeImports()) {
            "Cannot access theme data while an interrupted import requires recovery"
        }
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
                publishNewThemeFile(input, dir, destination.name)
            }
        }
    }

    fun newCustomBackgroundImages(): Triple<String, File, File> {
        val themeName = UUID.randomUUID().toString()
        val croppedImageFile = File(dir, "$themeName-cropped.png")
        val srcImageFile = File(dir, "$themeName-src")
        return Triple(themeName, croppedImageFile, srcImageFile)
    }

    fun installNewThemeImage(stream: InputStream, destination: File): File =
        runThemeFileOperation {
            requireRecoveredThemeImports()
            require(isThemeFile(destination)) { "Invalid theme image path: $destination" }
            stream.use { input ->
                publishNewThemeFile(input, dir, destination.name)
            }
        }

    private fun saveThemeMetadata(theme: Theme.Custom) {
        val file = themeFile(theme)
        replaceFileAtomically(file) { staged ->
            staged.writeText(Json.encodeToString(CustomThemeSerializer, theme))
        }
    }

    fun saveThemeFiles(theme: Theme.Custom) = runThemeFileOperation {
        requireRecoveredThemeImports()
        saveThemeMetadata(theme)
    }

    fun saveThemeFiles(
        theme: Theme.Custom,
        pendingCroppedImage: File,
        replaceExistingImage: Boolean,
    ) = runThemeFileOperation {
        requireRecoveredThemeImports()
        require(pendingCroppedImage.isFile) {
            "Cannot find pending cropped theme image: $pendingCroppedImage"
        }
        val background = requireNotNull(theme.backgroundImage)
        val croppedImage = File(background.croppedFilePath)
        require(isThemeFile(croppedImage)) {
            "Invalid cropped theme image path: $croppedImage"
        }
        require(pendingCroppedImage.canonicalFile != croppedImage.canonicalFile) {
            "Pending cropped theme image must not be the destination"
        }
        withTempDir { tempDir ->
            val croppedBackup = backup(croppedImage, tempDir)
            runWithRollback(
                rollback = { listOf(restore(croppedImage, croppedBackup)) },
            ) {
                installImportedFile(
                    pendingCroppedImage,
                    croppedImage,
                    replaceExistingImage,
                )
                saveThemeMetadata(theme)
            }
        }
    }

    fun deleteThemeFiles(theme: Theme.Custom): Result<Unit> = runThemeFileResultOperation {
        requireRecoveredThemeImports()
        themeFile(theme).removeIfExists().getOrThrow()
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

    fun listThemes(): MutableList<Theme.Custom> = runThemeFileOperation {
        if (!recoverPendingThemeImports()) return@runThemeFileOperation mutableListOf()
        val files = dir.listFiles(FileFilter { it.extension == "json" })
            ?: return@runThemeFileOperation mutableListOf()
        files
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
        runThemeFileResultOperation {
            requireRecoveredThemeImports()
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
        runThemeFileResultOperation {
            requireRecoveredThemeImports()
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
                    val mutations = mutableListOf<ThemeImportMutation>()
                    val newTheme = decoded.backgroundImage?.let { background ->
                        val srcFile = imageFile(background.srcFilePath)
                        val croppedFile = imageFile(background.croppedFilePath)
                        if (setOf(srcFile, croppedFile, themeFile).size != 3) {
                            errorRuntime(R.string.exception_theme_json)
                        }
                        val importedSrcFile = extracted.find { it.name == srcFile.name && it.isFile }
                            ?: errorRuntime(R.string.exception_theme_src_image)
                        val importedCroppedFile = extracted.find { it.name == croppedFile.name && it.isFile }
                            ?: errorRuntime(R.string.exception_theme_cropped_image)
                        val srcFileMatchesOldTheme = oldSrcFile?.canonicalFile == srcFile
                        val croppedFileMatchesOldTheme = oldCroppedFile?.canonicalFile == croppedFile
                        mutations += ThemeImportMutation(
                            srcFile,
                            importedSrcFile,
                            replaceExisting = srcFileMatchesOldTheme,
                        )
                        mutations += ThemeImportMutation(
                            croppedFile,
                            importedCroppedFile,
                            replaceExisting = croppedFileMatchesOldTheme,
                        )
                        decoded.copy(
                            backgroundImage = background.copy(
                                croppedFilePath = croppedFile.path,
                                srcFilePath = srcFile.path
                            )
                        )
                    } ?: decoded
                    val importedMetadata = File.createTempFile(
                        "theme-import-",
                        ".json",
                        tempDir,
                    ).also { metadata ->
                        metadata.writeText(Json.encodeToString(CustomThemeSerializer, newTheme))
                    }
                    mutations += ThemeImportMutation(
                        themeFile,
                        importedMetadata,
                        replaceExisting = true,
                    )
                    val newBackgroundFiles = newTheme.backgroundImage?.let {
                        listOf(File(it.srcFilePath).canonicalFile, File(it.croppedFilePath).canonicalFile)
                    }.orEmpty()
                    val retainedFiles = (newBackgroundFiles + themeFile.canonicalFile).toSet()
                    listOf(oldSrcFile, oldCroppedFile)
                        .filterNotNull()
                        .filter(::isThemeFile)
                        .map(File::getCanonicalFile)
                        .distinct()
                        .filterNot { it in retainedFiles }
                        .filter(File::isFile)
                        .forEach { file ->
                            mutations += ThemeImportMutation(file, source = null)
                        }
                    executeThemeImportTransaction(dir, mutations)
                    Triple(newCreated, newTheme, migrated)
                }
            }
        }

}

internal fun isThemeInstallStagingFile(fileName: String): Boolean =
    fileName.startsWith(THEME_INSTALL_STAGING_PREFIX) &&
            fileName.endsWith(THEME_INSTALL_STAGING_SUFFIX)

internal fun cleanupStagedThemeInstalls(directory: File) {
    directory.listFiles()
        ?.filter { file -> file.isFile && isThemeInstallStagingFile(file.name) }
        ?.forEach { staged ->
            staged.removeIfExists().onFailure { failure ->
                Timber.w(failure, "Failed to remove stale theme install: ${staged.path}")
            }
        }
}

internal fun publishNewThemeFile(
    stream: InputStream,
    directory: File,
    fileName: String,
    publish: (File, File) -> Unit = { staged, destination ->
        Os.rename(staged.path, destination.path)
    },
): File {
    directory.ensureDirectory()
    val destination = directory.resolveDirectChild(fileName)
    val staged = File.createTempFile(
        THEME_INSTALL_STAGING_PREFIX,
        THEME_INSTALL_STAGING_SUFFIX,
        directory,
    )
    return runWithCleanup(
        cleanup = { staged.removeIfExists() },
        onCleanupFailure = { failure ->
            Timber.w(failure, "Failed to remove staged theme file: ${staged.path}")
        },
    ) {
        staged.outputStream().use { output -> stream.copyTo(output) }
        runThemeFileOperation {
            if (destination.exists()) throw FileAlreadyExistsException(destination)
            publish(staged, destination)
            check(destination.isFile) { "Failed to publish theme file: ${destination.path}" }
            destination
        }
    }
}

internal fun isLegacyThemeMetadataStagingFile(fileName: String): Boolean =
    fileName.startsWith("theme-") && fileName.endsWith(".staged")

internal fun cleanupLegacyThemeMetadataStaging(directory: File) {
    directory.listFiles()
        ?.filter { file -> file.isFile && isLegacyThemeMetadataStagingFile(file.name) }
        ?.forEach { staged ->
            staged.removeIfExists().onFailure {
                Timber.w(it, "Failed to remove stale theme metadata: ${staged.path}")
            }
        }
}
