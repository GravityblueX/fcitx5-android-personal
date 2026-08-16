/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.system.Os
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

private const val FILE_INSTALL_STAGING_PREFIX = "file-install-"
private const val FILE_INSTALL_STAGING_SUFFIX = ".staged"

internal fun installNewFileAtomically(
    stream: InputStream,
    directory: File,
    fileName: String,
): File = installNewFileAtomically(stream, directory, fileName) { staged, destination ->
    if (!staged.moveToWithoutReplacing(destination)) {
        if (destination.exists()) throw FileAlreadyExistsException(destination)
        throw IOException("Cannot publish installed file: ${destination.path}")
    }
}

internal fun installNewFileAtomically(
    stream: InputStream,
    directory: File,
    fileName: String,
    publish: (File, File) -> Unit,
): File {
    directory.ensureDirectory()
    val destination = directory.resolveDirectChild(fileName)
    if (destination.exists()) throw FileAlreadyExistsException(destination)
    val staged = File.createTempFile(
        FILE_INSTALL_STAGING_PREFIX,
        FILE_INSTALL_STAGING_SUFFIX,
        directory,
    )
    var published = false
    try {
        return runWithRollback(
            rollback = { listOf(staged.removeIfExists()) },
        ) {
            staged.outputStream().use { output -> stream.copyTo(output) }
            publish(staged, destination)
            published = true
            destination
        }
    } finally {
        if (published) {
            staged.removeIfExists().onFailure {
                Timber.w(it, "Failed to remove committed file staging: ${staged.path}")
            }
        }
    }
}

internal fun replaceFileAtomically(
    destination: File,
    write: (File) -> Unit,
): File = replaceFileAtomically(destination, write) { staged, target ->
    Os.rename(staged.path, target.path)
}

internal fun replaceFileAtomically(
    destination: File,
    write: (File) -> Unit,
    publish: (File, File) -> Unit,
): File {
    val directory = destination.parentFile
        ?: error("Cannot resolve destination directory: ${destination.path}")
    directory.ensureDirectory()
    val staged = File.createTempFile(
        FILE_INSTALL_STAGING_PREFIX,
        FILE_INSTALL_STAGING_SUFFIX,
        directory,
    )
    var published = false
    try {
        return runWithRollback(
            rollback = { listOf(staged.removeIfExists()) },
        ) {
            write(staged)
            publish(staged, destination)
            published = true
            destination
        }
    } finally {
        if (published) {
            staged.removeIfExists().onFailure {
                Timber.w(it, "Failed to remove committed file staging: ${staged.path}")
            }
        }
    }
}

internal fun isFileInstallStagingFile(fileName: String): Boolean =
    fileName.startsWith(FILE_INSTALL_STAGING_PREFIX) &&
            fileName.endsWith(FILE_INSTALL_STAGING_SUFFIX)

internal fun cleanupStagedFileInstalls(directory: File) {
    directory.listFiles()
        ?.filter { file -> file.isFile && isFileInstallStagingFile(file.name) }
        ?.forEach { staged ->
            staged.removeIfExists().onFailure {
                Timber.w(it, "Failed to remove stale file install: ${staged.path}")
            }
        }
}
