/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileInstallTest {

    @Test
    fun identifiesOnlyFileInstallStagingNames() {
        assertTrue(isFileInstallStagingFile("file-install-123.staged"))
        assertFalse(isFileInstallStagingFile(".file-install-123.staged"))
        assertFalse(isFileInstallStagingFile("file-install-123.conf"))
    }

    @Test
    fun cleansOnlyStagedInstallFiles() {
        val directory = Files.createTempDirectory("file-install-cleanup-").toFile()
        try {
            val staged = directory.resolve("file-install-123.staged").apply { writeText("partial") }
            val wrongPrefix = directory.resolve("other-123.staged").apply { writeText("keep") }
            val wrongSuffix = directory.resolve("file-install-123.conf").apply { writeText("keep") }
            val matchingDirectory = directory.resolve("file-install-dir.staged").apply { mkdir() }

            cleanupStagedFileInstalls(directory)

            assertFalse(staged.exists())
            assertTrue(wrongPrefix.exists())
            assertTrue(wrongSuffix.exists())
            assertTrue(matchingDirectory.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun publishesOnlyAfterStagingCompletes() {
        val directory = Files.createTempDirectory("file-install-").toFile()
        try {
            val content = "complete content"

            val installed = installNewFileAtomically(
                ByteArrayInputStream(content.toByteArray()),
                directory,
                "target.conf",
            ) { staged, destination ->
                assertEquals(content, staged.readText())
                assertEquals("", destination.readText())
                publishForTest(staged, destination)
            }

            assertEquals("target.conf", installed.name)
            assertEquals(content, installed.readText())
            assertEquals(listOf("target.conf"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleansStagingFileWhenCopyFails() {
        val directory = Files.createTempDirectory("file-install-").toFile()
        try {
            val stream = object : InputStream() {
                private var reads = 0

                override fun read(): Int {
                    if (reads == 4) throw IOException("copy interrupted")
                    reads += 1
                    return 'x'.code
                }
            }

            assertThrows(IOException::class.java) {
                installNewFileAtomically(stream, directory, "target.conf", ::publishForTest)
            }

            assertTrue(directory.listFiles()?.isEmpty() == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleansReservedFileWhenPublishingFails() {
        val directory = Files.createTempDirectory("file-install-").toFile()
        try {
            assertThrows(IOException::class.java) {
                installNewFileAtomically(
                    ByteArrayInputStream("complete content".toByteArray()),
                    directory,
                    "target.conf",
                ) { staged, destination ->
                    assertEquals("complete content", staged.readText())
                    assertTrue(destination.isFile)
                    throw IOException("publish interrupted")
                }
            }

            assertTrue(directory.listFiles()?.isEmpty() == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesExistingFileWhenNameAlreadyExists() {
        val directory = Files.createTempDirectory("file-install-").toFile()
        try {
            val existing = directory.resolve("target.conf").apply { writeText("existing content") }
            var publishCalled = false
            val unreadStream = object : InputStream() {
                override fun read(): Int = error("existing destinations must be rejected before reading")
            }

            assertThrows(FileAlreadyExistsException::class.java) {
                installNewFileAtomically(
                    unreadStream,
                    directory,
                    "target.conf",
                ) { _, _ -> publishCalled = true }
            }

            assertFalse(publishCalled)
            assertEquals("existing content", existing.readText())
            assertEquals(listOf("target.conf"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun publishForTest(staged: File, destination: File) {
        Files.move(
            staged.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
