/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DataDescriptorPathTest {

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
