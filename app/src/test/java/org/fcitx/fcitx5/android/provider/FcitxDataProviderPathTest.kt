/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FcitxDataProviderPathTest {

    private val removeRecursively: (File) -> Result<Unit> = { file ->
        runCatching {
            check(file.deleteRecursively() || !file.exists()) {
                "Cannot delete '${file.path}'"
            }
        }
    }

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

    @Test
    fun identifiesOnlyDocumentCopyStagingNames() {
        assertTrue(
            isDocumentCopyStagingFileName(
                ".document-copy-123e4567-e89b-12d3-a456-426614174000.staged"
            )
        )
        assertFalse(
            isDocumentCopyStagingFileName(
                "document-copy-123e4567-e89b-12d3-a456-426614174000.staged"
            )
        )
        assertFalse(isDocumentCopyStagingFileName(".document-copy-not-a-uuid.staged"))
        assertFalse(
            isDocumentCopyStagingFileName(
                ".document-copy-123e4567-e89b-12d3-a456-426614174000.tmp"
            )
        )
    }

    @Test
    fun publishesCompletedCopyWithoutReplacingConflict() {
        val root = Files.createTempDirectory("provider-copy-").toFile()
        try {
            val sourceDirectory = root.resolve("source").apply { mkdir() }
            val targetDirectory = root.resolve("target").apply { mkdir() }
            val source = sourceDirectory.resolve("sample.txt").apply { writeText("complete") }
            val existing = targetDirectory.resolve("sample.txt").apply { writeText("existing") }

            val published = copyDocumentAtomically(
                source,
                targetDirectory,
                remove = removeRecursively,
            ) { input, staging ->
                assertTrue(isDocumentCopyStagingFileName(staging.name))
                assertEquals(
                    listOf("sample.txt"),
                    targetDirectory.list()
                        ?.filterNot(::isDocumentCopyStagingFileName)
                        ?.sorted(),
                )
                input.copyTo(staging, overwrite = true)
            }

            assertEquals("sample (2).txt", published.name)
            assertEquals("complete", published.readText())
            assertEquals("existing", existing.readText())
            assertEquals(listOf("sample (2).txt", "sample.txt"), targetDirectory.list()?.sorted())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removesStagingAfterFailedDocumentCopy() {
        val root = Files.createTempDirectory("provider-copy-").toFile()
        try {
            val sourceDirectory = root.resolve("source").apply { mkdir() }
            val targetDirectory = root.resolve("target").apply { mkdir() }
            val source = sourceDirectory.resolve("sample.txt").apply { writeText("complete") }
            val failure = IOException("copy failed")

            val thrown = assertThrows(IOException::class.java) {
                copyDocumentAtomically(
                    source,
                    targetDirectory,
                    remove = removeRecursively,
                ) { _, staging ->
                    staging.writeText("partial")
                    throw failure
                }
            }

            assertSame(failure, thrown)
            assertTrue(targetDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleansOnlyStagedDocumentCopiesRecursively() {
        val root = Files.createTempDirectory("provider-copy-").toFile()
        try {
            val stagedFile = createDocumentCopyStaging(root, isDirectory = false)
            val regularDirectory = root.resolve("regular").apply { mkdir() }
            val stagedDirectory = createDocumentCopyStaging(
                regularDirectory,
                isDirectory = true,
            )
            stagedDirectory.resolve("partial.txt").writeText("partial")
            val unrelated = root.resolve(".document-copy-not-a-uuid.staged").apply {
                writeText("keep")
            }

            val results = cleanupStagedDocumentCopies(root, removeRecursively)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertFalse(stagedFile.exists())
            assertFalse(stagedDirectory.exists())
            assertTrue(regularDirectory.isDirectory)
            assertTrue(unrelated.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
