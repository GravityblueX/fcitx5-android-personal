/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.system.Os
import java.io.File
import java.io.InputStream

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
    val staged = File.createTempFile("file-install-", ".staged", directory)
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
