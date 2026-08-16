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
import java.nio.file.Files

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
    fun rejectsCanonicalPathRedirections() {
        val root = File("provider-root").absoluteFile
        val direct = root.resolve("direct")
        val redirected = object : File(root, "linked") {
            override fun getCanonicalFile(): File = root.resolve("target")
        }

        assertTrue(isUnredirectedPath(direct))
        assertFalse(isUnredirectedPath(redirected))
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

    @Test
    fun reservesCopyDestinationsWithoutReplacingExistingEntries() {
        val root = Files.createTempDirectory("provider-copy-").toFile()
        try {
            val existingFile = root.resolve("existing.txt").also { it.writeText("existing") }
            val existingDirectory = root.resolve("existing-directory").also(File::mkdir)

            assertFalse(reserveDocumentCopyDestination(existingFile, isDirectory = false))
            assertFalse(reserveDocumentCopyDestination(existingDirectory, isDirectory = true))
            assertEquals("existing", existingFile.readText())
            assertTrue(existingDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reservesCopyDestinationWithRequestedType() {
        val root = Files.createTempDirectory("provider-copy-").toFile()
        try {
            val file = root.resolve("new.txt")
            val directory = root.resolve("new-directory")

            assertTrue(reserveDocumentCopyDestination(file, isDirectory = false))
            assertTrue(reserveDocumentCopyDestination(directory, isDirectory = true))
            assertTrue(file.isFile)
            assertTrue(directory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
