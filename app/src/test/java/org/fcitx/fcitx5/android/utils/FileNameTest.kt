/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class FileNameTest {

    @Test
    fun removesUnixAndWindowsPathPrefixes() {
        assertEquals("dictionary.txt", "../../dictionary.txt".safeFileName())
        assertEquals("dictionary.txt", "..\\..\\dictionary.txt".safeFileName())
    }

    @Test
    fun substitutesUnsafePathComponents() {
        assertEquals("import", "".safeFileName())
        assertEquals("import", ".".safeFileName())
        assertEquals("import", "..".safeFileName())
    }

    @Test
    fun resolvesOnlyDirectChildren() {
        val directory = Files.createTempDirectory("file-name-").toFile()
        try {
            assertEquals(directory.resolve("theme.json").canonicalFile, directory.resolveDirectChild("theme.json"))
            listOf("", ".", "..", "../theme.json", "nested/theme.json", "..\\theme.json")
                .forEach { name ->
                    assertThrows(IllegalArgumentException::class.java) {
                        directory.resolveDirectChild(name)
                    }
                }
        } finally {
            directory.deleteRecursively()
        }
    }
}
