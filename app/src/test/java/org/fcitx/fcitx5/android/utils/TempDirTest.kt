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

class TempDirTest {

    @Test
    fun createsDistinctDirectories() {
        val parent = Files.createTempDirectory("fcitx-temp-").toFile()
        try {
            val directories = List(4) { createTempDir(parent) }

            assertEquals(directories.size, directories.distinct().size)
            assertTrue(directories.all(File::isDirectory))
        } finally {
            parent.deleteRecursively()
        }
    }
}
