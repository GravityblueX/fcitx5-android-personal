/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DataManagerStagingTest {

    @Test
    fun identifiesOnlyLegacyDataWriteStagingFiles() {
        assertTrue(isLegacyDataWriteStagingFile("data-descriptor-123.staged"))
        assertTrue(isLegacyDataWriteStagingFile("data-file-123.staged"))
        assertFalse(isLegacyDataWriteStagingFile("file-install-123.staged"))
        assertFalse(isLegacyDataWriteStagingFile("data-file-123.conf"))
    }

    @Test
    fun cleansCurrentAndLegacyDataWriteStagingFiles() {
        val directory = Files.createTempDirectory("data-write-cleanup-").toFile()
        try {
            val descriptor = directory.resolve("data-descriptor-123.staged").apply {
                writeText("partial")
            }
            val dataFile = directory.resolve("data-file-123.staged").apply {
                writeText("partial")
            }
            val current = directory.resolve("file-install-123.staged").apply {
                writeText("partial")
            }
            val unrelated = directory.resolve("data-file-123.conf").apply { writeText("keep") }
            val matchingDirectory = directory.resolve("data-file-dir.staged").apply { mkdir() }

            cleanupStagedDataWrites(directory)

            assertFalse(descriptor.exists())
            assertFalse(dataFile.exists())
            assertFalse(current.exists())
            assertTrue(unrelated.isFile)
            assertTrue(matchingDirectory.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
