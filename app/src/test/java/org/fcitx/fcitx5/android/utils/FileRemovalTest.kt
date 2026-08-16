/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileRemovalTest {

    @Test
    fun reportsDeletionFailureWithoutRemovingFile() {
        val directory = Files.createTempDirectory("file-removal-").toFile()
        try {
            val file = UndeletableFile(directory.resolve("data").path)
                .also { it.writeText("content") }

            assertTrue(file.removeIfExists().isFailure)
            assertTrue(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun acceptsAlreadyMissingFile() {
        val directory = Files.createTempDirectory("file-removal-").toFile()
        try {
            val file = directory.resolve("missing")

            assertTrue(file.removeIfExists().isSuccess)
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class UndeletableFile(path: String) : File(path) {
        override fun delete() = false
    }
}
