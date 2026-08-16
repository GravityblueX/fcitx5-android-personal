/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import timber.log.Timber
import java.io.File
import java.nio.file.Files

@PublishedApi
internal fun createTempDir(parent: File, prefix: String = "fcitx-"): File {
    return Files.createTempDirectory(parent.toPath(), prefix).toFile()
}

inline fun <T> withTempDir(block: (File) -> T): T {
    val dir = createTempDir(appContext.cacheDir)
    return runWithCleanup(
        cleanup = { FileUtil.removeFile(dir) },
        onCleanupFailure = { failure ->
            Timber.w(failure, "Failed to remove temporary directory: ${dir.path}")
        },
        block = { block(dir) },
    )
}
