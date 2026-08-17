/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class DataDescriptorPathTest {

    private fun managedPathOfLength(length: Int): String {
        var remaining = length - "usr/".length
        val segments = mutableListOf<String>()
        while (remaining > 0) {
            val segmentLength = minOf(MAX_DATA_DESCRIPTOR_PATH_SEGMENT_BYTES, remaining)
            segments.add("a".repeat(segmentLength))
            remaining -= segmentLength
            if (remaining > 0) remaining--
        }
        return "usr/${segments.joinToString("/")}"
    }

    @Test
    fun boundsEncodedDescriptorSize() {
        val accepted = ByteArray(MAX_DATA_DESCRIPTOR_BYTES) { ' '.code.toByte() }
        assertEquals(
            MAX_DATA_DESCRIPTOR_BYTES,
            ByteArrayInputStream(accepted).readBoundedDataDescriptorText().length,
        )

        val oversized = ByteArray(MAX_DATA_DESCRIPTOR_BYTES + 64) { ' '.code.toByte() }
        val input = ByteArrayInputStream(oversized)
        assertThrows(DataDescriptorLimitExceeded::class.java) {
            input.readBoundedDataDescriptorText()
        }
        assertEquals(63, input.available())
    }

    @Test
    fun boundsDescriptorEntryCount() {
        val files = (0 until MAX_DATA_DESCRIPTOR_ENTRIES).associate { index ->
            "usr/file-$index" to "file"
        }
        assertEquals(
            MAX_DATA_DESCRIPTOR_ENTRIES,
            DataDescriptor("descriptor", files).withValidatedManagedPaths().files.size,
        )

        assertThrows(DataDescriptorLimitExceeded::class.java) {
            DataDescriptor(
                "descriptor",
                files,
                mapOf("usr/link" to "usr/source"),
            ).withValidatedManagedPaths()
        }
    }

    @Test
    fun boundsManagedPathComplexity() {
        val maximumLengthPath = managedPathOfLength(MAX_DATA_DESCRIPTOR_PATH_LENGTH)
        DataDescriptor("descriptor", mapOf(maximumLengthPath to "file"))
            .withValidatedManagedPaths()

        assertThrows(DataDescriptorLimitExceeded::class.java) {
            DataDescriptor(
                "descriptor",
                mapOf(managedPathOfLength(MAX_DATA_DESCRIPTOR_PATH_LENGTH + 1) to "file"),
            ).withValidatedManagedPaths()
        }

        val maximumSegmentsPath =
            "usr/" + List(MAX_DATA_DESCRIPTOR_PATH_SEGMENTS - 1) { "a" }.joinToString("/")
        DataDescriptor("descriptor", mapOf(maximumSegmentsPath to "file"))
            .withValidatedManagedPaths()

        assertThrows(DataDescriptorLimitExceeded::class.java) {
            val excessiveSegmentsPath = "$maximumSegmentsPath/a"
            DataDescriptor("descriptor", mapOf(excessiveSegmentsPath to "file"))
                .withValidatedManagedPaths()
        }

        assertThrows(DataDescriptorLimitExceeded::class.java) {
            val excessiveSegmentPath =
                "usr/${"a".repeat(MAX_DATA_DESCRIPTOR_PATH_SEGMENT_BYTES + 1)}"
            DataDescriptor("descriptor", mapOf(excessiveSegmentPath to "file"))
                .withValidatedManagedPaths()
        }

        assertThrows(DataDescriptorLimitExceeded::class.java) {
            val excessiveEncodedSegmentPath = "usr/${"界".repeat(86)}"
            DataDescriptor("descriptor", mapOf(excessiveEncodedSegmentPath to "file"))
                .withValidatedManagedPaths()
        }

        assertThrows(DataDescriptorLimitExceeded::class.java) {
            val excessiveEncodedPath =
                "usr/" + List(5) { "界".repeat(80) }.joinToString("/")
            DataDescriptor("descriptor", mapOf(excessiveEncodedPath to "file"))
                .withValidatedManagedPaths()
        }
    }

    @Test
    fun boundsDescriptorHashLength() {
        val maximumHash = "a".repeat(MAX_DATA_DESCRIPTOR_HASH_BYTES)
        DataDescriptor(maximumHash, mapOf("usr/file" to maximumHash))
            .withValidatedManagedPaths()

        assertThrows(DataDescriptorLimitExceeded::class.java) {
            DataDescriptor("a".repeat(MAX_DATA_DESCRIPTOR_HASH_BYTES + 1), emptyMap())
                .withValidatedManagedPaths()
        }
        assertThrows(DataDescriptorLimitExceeded::class.java) {
            DataDescriptor(
                "descriptor",
                mapOf("usr/file" to "a".repeat(MAX_DATA_DESCRIPTOR_HASH_BYTES + 1)),
            ).withValidatedManagedPaths()
        }
    }

    @Test
    fun acceptsEmptyServiceOnlyDescriptor() {
        val descriptor = DataDescriptor("service", emptyMap()).withValidatedManagedPaths()

        assertEquals(emptyMap<String, String>(), descriptor.files)
        assertEquals(emptyMap<String, String>(), descriptor.symlinks)
    }

    @Test
    fun normalizesManagedDescriptorPaths() {
        val normalized = DataDescriptor(
            sha256 = "descriptor",
            files = linkedMapOf(
                "README.md" to "readme",
                "usr" to "",
                "usr\\share\\fcitx5\\addon.conf" to "addon",
            ),
            symlinks = mapOf(
                "usr\\share\\rime-data\\opencc" to "usr\\share\\opencc",
            ),
        ).withValidatedManagedPaths()

        assertEquals(
            linkedMapOf(
                "README.md" to "readme",
                "usr" to "",
                "usr/share/fcitx5/addon.conf" to "addon",
            ),
            normalized.files,
        )
        assertEquals(
            mapOf("usr/share/rime-data/opencc" to "usr/share/opencc"),
            normalized.symlinks,
        )
    }

    @Test
    fun rejectsFilesOutsideManagedDataTree() {
        listOf(
            "",
            ".",
            "..",
            "../shared_prefs/preferences.xml",
            "usr/../../shared_prefs/preferences.xml",
            "/data/user/0/app/preferences.xml",
            "C:\\data\\preferences.xml",
            "shared_prefs/preferences.xml",
            "usr//share/file",
            "usr/./share/file",
            "usr/share/../file",
            "usr/share/\u0000file",
            "usr/share/\u0001file",
        ).forEach { path ->
            val failure = assertThrows(UnsafeDataDescriptorPath::class.java) {
                DataDescriptor("descriptor", mapOf(path to "file"))
                    .withValidatedManagedPaths()
            }

            assertEquals(path, failure.path)
        }
    }

    @Test
    fun rejectsSymlinksOutsideManagedDataTree() {
        val unsafeTargets = listOf("../outside", "shared_prefs/link")
        unsafeTargets.forEach { target ->
            val failure = assertThrows(UnsafeDataDescriptorPath::class.java) {
                DataDescriptor(
                    "descriptor",
                    emptyMap(),
                    mapOf(target to "usr/share/source"),
                ).withValidatedManagedPaths()
            }

            assertEquals(target, failure.path)
        }

        val failure = assertThrows(UnsafeDataDescriptorPath::class.java) {
            DataDescriptor(
                "descriptor",
                emptyMap(),
                mapOf("usr/share/link" to "../../shared_prefs/preferences.xml"),
            ).withValidatedManagedPaths()
        }
        assertEquals("../../shared_prefs/preferences.xml", failure.path)
    }

    @Test
    fun rejectsConflictingPathsAfterSeparatorNormalization() {
        val failure = assertThrows(UnsafeDataDescriptorPath::class.java) {
            DataDescriptor(
                "descriptor",
                linkedMapOf(
                    "usr/share/file" to "first",
                    "usr\\share\\file" to "second",
                ),
            ).withValidatedManagedPaths()
        }

        assertEquals("usr\\share\\file", failure.path)
    }

    @Test
    fun rejectsRecursiveSymlinkSources() {
        listOf(
            "usr/tree" to "usr/tree/child",
            "usr/tree/child" to "usr/tree",
        ).forEach { (target, source) ->
            val failure = assertThrows(UnsafeDataDescriptorPath::class.java) {
                DataDescriptor(
                    "descriptor",
                    emptyMap(),
                    mapOf(target to source),
                ).withValidatedManagedPaths()
            }

            assertEquals(target, failure.path)
        }
    }

    @Test
    fun resolvesOnlyUnredirectedManagedPaths() {
        val parent = Files.createTempDirectory("managed-data-").toFile()
        val root = parent.resolve("root").apply { mkdir() }
        val outside = parent.resolve("outside").apply { mkdir() }
        val usr = root.resolve("usr").apply { mkdir() }
        try {
            assertEquals(
                usr.resolve("share/file.conf").absoluteFile.normalize(),
                resolveManagedDataPath(root, "usr/share/file.conf"),
            )

            val redirectedParent = usr.resolve("redirect").absoluteFile.normalize()
            val redirectedSource = usr.resolve("source").absoluteFile.normalize()
            val canonicalize: (File) -> File = { file ->
                when (val normalized = file.absoluteFile.normalize()) {
                    redirectedParent, redirectedSource -> outside.absoluteFile.normalize()
                    else -> normalized
                }
            }
            assertThrows(UnsafeDataDescriptorPath::class.java) {
                resolveManagedDataPath(root, "usr/redirect/file.conf", canonicalize)
            }
            assertThrows(UnsafeDataDescriptorPath::class.java) {
                resolveManagedDataSource(root, "usr/source", canonicalize)
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
