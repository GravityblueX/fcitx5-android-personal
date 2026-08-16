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
    fun reservesDocumentDestinationsWithoutReplacingExistingEntries() {
        val root = Files.createTempDirectory("provider-copy-").toFile()
        try {
            val existingFile = root.resolve("existing.txt").also { it.writeText("existing") }
            val existingDirectory = root.resolve("existing-directory").also(File::mkdir)

            assertFalse(reserveDocumentDestination(existingFile, isDirectory = false))
            assertFalse(reserveDocumentDestination(existingDirectory, isDirectory = true))
            assertEquals("existing", existingFile.readText())
            assertTrue(existingDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reservesDocumentDestinationWithRequestedType() {
        val root = Files.createTempDirectory("provider-copy-").toFile()
        try {
            val file = root.resolve("new.txt")
            val directory = root.resolve("new-directory")

            assertTrue(reserveDocumentDestination(file, isDirectory = false))
            assertTrue(reserveDocumentDestination(directory, isDirectory = true))
            assertTrue(file.isFile)
            assertTrue(directory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retriesDocumentDestinationWhenCandidateIsClaimedConcurrently() {
        val root = Files.createTempDirectory("provider-destination-").toFile()
        try {
            val attempts = mutableListOf<String>()

            val claimed = claimDocumentDestination(
                root,
                "sample.txt",
                isDirectory = false,
            ) { candidate ->
                attempts += candidate.name
                if (attempts.size == 1) candidate.writeText("racer")
                reserveDocumentDestination(candidate, isDirectory = false)
            }

            assertEquals(listOf("sample.txt", "sample (2).txt"), attempts)
            assertEquals("sample (2).txt", claimed.name)
            assertEquals("racer", root.resolve("sample.txt").readText())
            assertTrue(claimed.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failsDocumentDestinationClaimAfterNonConflictError() {
        val root = Files.createTempDirectory("provider-destination-").toFile()
        try {
            val attempts = mutableListOf<String>()

            val thrown = assertThrows(IOException::class.java) {
                claimDocumentDestination(
                    root,
                    "sample.txt",
                    isDirectory = false,
                ) { candidate ->
                    attempts += candidate.name
                    false
                }
            }

            assertEquals(listOf("sample.txt"), attempts)
            assertTrue(thrown.message.orEmpty().contains("sample.txt"))
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun identifiesOnlyDocumentOperationStagingNames() {
        assertTrue(
            isDocumentStagingFileName(
                ".document-copy-123e4567-e89b-12d3-a456-426614174000.staged"
            )
        )
        assertTrue(
            isDocumentStagingFileName(
                ".document-delete-123e4567-e89b-12d3-a456-426614174000.staged"
            )
        )
        assertFalse(
            isDocumentStagingFileName(
                "document-copy-123e4567-e89b-12d3-a456-426614174000.staged"
            )
        )
        assertFalse(isDocumentStagingFileName(".document-copy-not-a-uuid.staged"))
        assertFalse(isDocumentStagingFileName(".document-delete-not-a-uuid.staged"))
        assertFalse(
            isDocumentStagingFileName(
                ".document-copy-123e4567-e89b-12d3-a456-426614174000.tmp"
            )
        )
    }

    @Test
    fun rejectsReservedDocumentOperationStagingNames() {
        assertThrows(IllegalArgumentException::class.java) {
            safeDocumentDisplayName(
                ".document-copy-123e4567-e89b-12d3-a456-426614174000.staged"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            safeDocumentDisplayName(
                ".document-delete-123e4567-e89b-12d3-a456-426614174000.staged"
            )
        }
        assertEquals("visible.txt", safeDocumentDisplayName("directory/visible.txt"))
    }

    @Test
    fun identifiesStagingPathsAndTheirDescendants() {
        val root = File("root")
        val staging = root.resolve(
            ".document-delete-123e4567-e89b-12d3-a456-426614174000.staged"
        )

        assertFalse(isDocumentStagingPath(root, root))
        assertTrue(isDocumentStagingPath(staging, root))
        assertTrue(isDocumentStagingPath(staging.resolve("child.txt"), root))
        assertFalse(isDocumentStagingPath(root.resolve("ordinary/child.txt"), root))
        assertFalse(isDocumentStagingPath(File("outside/child.txt"), root))
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
                assertTrue(isDocumentStagingFileName(staging.name))
                assertEquals(
                    listOf("sample.txt"),
                    targetDirectory.list()
                        ?.filterNot(::isDocumentStagingFileName)
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
    fun stagesDocumentDeletionBeforeRecursiveCleanup() {
        val root = Files.createTempDirectory("provider-delete-").toFile()
        try {
            val document = root.resolve("document").apply { mkdir() }
            document.resolve("child.txt").writeText("content")
            var stagedDeletion: File? = null

            val cleanup = deleteDocumentAtomically(
                document,
                remove = { staging ->
                    stagedDeletion = staging
                    assertFalse(document.exists())
                    assertTrue(isDocumentStagingFileName(staging.name))
                    assertEquals("content", staging.resolve("child.txt").readText())
                    removeRecursively(staging)
                },
            )

            assertTrue(cleanup.isSuccess)
            assertFalse(document.exists())
            assertFalse(requireNotNull(stagedDeletion).exists())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsFailedDocumentDeletionCleanupHiddenForRecovery() {
        val root = Files.createTempDirectory("provider-delete-").toFile()
        try {
            val document = root.resolve("document.txt").apply { writeText("content") }
            val failure = IOException("cleanup failed")
            var stagedDeletion: File? = null

            val cleanup = deleteDocumentAtomically(
                document,
                remove = { staging ->
                    stagedDeletion = staging
                    Result.failure(failure)
                },
            )

            assertSame(failure, cleanup.exceptionOrNull())
            assertFalse(document.exists())
            assertTrue(requireNotNull(stagedDeletion).isFile)
            assertTrue(isDocumentStagingFileName(requireNotNull(stagedDeletion).name))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleansOnlyStagedDocumentOperationsRecursively() {
        val root = Files.createTempDirectory("provider-copy-").toFile()
        try {
            val stagedFile = createDocumentCopyStaging(root, isDirectory = false)
            val regularDirectory = root.resolve("regular").apply { mkdir() }
            val stagedDirectory = createDocumentCopyStaging(
                regularDirectory,
                isDirectory = true,
            )
            stagedDirectory.resolve("partial.txt").writeText("partial")
            val deletedDocument = regularDirectory.resolve("deleted.txt").apply {
                writeText("delete")
            }
            val stagedDeletion = stageDocumentDeletion(deletedDocument)
            val unrelated = root.resolve(".document-copy-not-a-uuid.staged").apply {
                writeText("keep")
            }

            val results = cleanupStagedDocuments(root, removeRecursively)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertFalse(stagedFile.exists())
            assertFalse(stagedDirectory.exists())
            assertFalse(deletedDocument.exists())
            assertFalse(stagedDeletion.exists())
            assertTrue(regularDirectory.isDirectory)
            assertTrue(unrelated.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
