/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TempDirTest {

    @Test
    fun usesRequestedPrefix() {
        val parent = Files.createTempDirectory("fcitx-temp-prefix-").toFile()
        try {
            val directory = createTempDir(parent, ".user-data-import-")

            assertTrue(directory.name.startsWith(".user-data-import-"))
            assertTrue(directory.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun createsOnlyDistinctDirectoriesConcurrently() {
        val parent = Files.createTempDirectory("fcitx-temp-").toFile()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val directories = executor.invokeAll(
                List(16) { Callable { createTempDir(parent) } }
            ).map { it.get() }

            assertEquals(directories.size, directories.distinct().size)
            assertTrue(directories.all(File::isDirectory))
            assertEquals(directories.toSet(), parent.listFiles()?.toSet())
        } finally {
            executor.shutdownNow()
            parent.deleteRecursively()
        }
    }
}
