/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FcitxDataProviderPathTest {

    @Test
    fun identifiesSameOrDescendantPaths() {
        val root = File("root")
        val child = root.resolve("child")

        assertTrue(isSameOrDescendant(root, root))
        assertTrue(isSameOrDescendant(child, root))
        assertFalse(isSameOrDescendant(root, child))
        assertFalse(isSameOrDescendant(File("root-sibling"), root))
    }

    @Test
    fun insertsConflictSuffixBeforeFileExtension() {
        assertEquals(
            "sample.main (2).dict",
            documentNameWithConflictSuffix("sample.main.dict", 2, isDirectory = false)
        )
        assertEquals(
            "archive.tar (3).gz",
            documentNameWithConflictSuffix("archive.tar.gz", 3, isDirectory = false)
        )
    }

    @Test
    fun appendsConflictSuffixToNamesWithoutFileExtensions() {
        assertEquals(
            "README (2)",
            documentNameWithConflictSuffix("README", 2, isDirectory = false)
        )
        assertEquals(
            ".profile (2)",
            documentNameWithConflictSuffix(".profile", 2, isDirectory = false)
        )
        assertEquals(
            "folder.with.dots (2)",
            documentNameWithConflictSuffix("folder.with.dots", 2, isDirectory = true)
        )
    }
}
