/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.system.Os
import timber.log.Timber
import java.io.File
import java.io.InputStream

private const val FILE_INSTALL_STAGING_PREFIX = "file-install-"
private const val FILE_INSTALL_STAGING_SUFFIX = ".staged"

internal fun installNewFileAtomically(
    stream: InputStream,
    directory: File,
    fileName: String,
): File = installNewFileAtomically(stream, directory, fileName) { staged, destination ->
    Os.rename(staged.path, destination.path)
}

internal fun installNewFileAtomically(
    stream: InputStream,
    directory: File,
    fileName: String,
    publish: (File, File) -> Unit,
): File {
    val destination = directory.resolveDirectChild(fileName)
    if (destination.exists()) throw FileAlreadyExistsException(destination)
    val staged = File.createTempFile(
        FILE_INSTALL_STAGING_PREFIX,
        FILE_INSTALL_STAGING_SUFFIX,
        directory,
    )
    var reserved = false
    var published = false
    try {
        staged.outputStream().use { output -> stream.copyTo(output) }
        if (!destination.createNewFile()) throw FileAlreadyExistsException(destination)
        reserved = true
        publish(staged, destination)
        published = true
        return destination
    } finally {
        if (reserved && !published) destination.delete()
        staged.delete()
    }
}

internal fun isFileInstallStagingFile(fileName: String): Boolean =
    fileName.startsWith(FILE_INSTALL_STAGING_PREFIX) &&
            fileName.endsWith(FILE_INSTALL_STAGING_SUFFIX)

internal fun cleanupStagedFileInstalls(directory: File) {
    directory.listFiles()
        ?.filter { file -> file.isFile && isFileInstallStagingFile(file.name) }
        ?.forEach { staged ->
            if (!staged.delete()) Timber.w("Failed to remove stale file install: ${staged.path}")
        }
}
