/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import timber.log.Timber
import java.io.File

@PublishedApi
internal fun createTempDir(parent: File): File {
    val dir = File.createTempFile("fcitx-", ".tmp", parent)
    check(dir.delete() && dir.mkdir()) { "Cannot create temporary directory: $dir" }
    return dir
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
