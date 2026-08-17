/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertThrows
import org.junit.Test

class DataHierarchyInstallTest {

    private fun paddedManagedPath(index: Int): String {
        val prefix = "usr/$index/"
        val segment = "a".repeat(MAX_DATA_DESCRIPTOR_PATH_SEGMENT_BYTES)
        return prefix + List(3) { segment }.joinToString("/")
    }

    @Test
    fun leavesHierarchyUnchangedAfterMergedContentLimit() {
        val hierarchy = DataHierarchy()
        val firstFiles = (0 until 700).associate { index ->
            paddedManagedPath(index) to "a".repeat(MAX_DATA_DESCRIPTOR_HASH_BYTES)
        }
        val secondFiles = (700 until 1400).associate { index ->
            paddedManagedPath(index) to "a".repeat(MAX_DATA_DESCRIPTOR_HASH_BYTES)
        }
        hierarchy.install(DataDescriptor("main", firstFiles), FileSource.Main)

        assertThrows(DataDescriptorLimitExceeded::class.java) {
            hierarchy.install(DataDescriptor("failed-plugin", secondFiles), FileSource.Main)
        }

        hierarchy.install(
            DataDescriptor("replacement-plugin", mapOf("usr/replacement" to "replacement")),
            FileSource.Main,
        )
    }

    @Test
    fun leavesHierarchyUnchangedAfterMergedEntryLimit() {
        val hierarchy = DataHierarchy()
        val baselineFiles = (0 until MAX_DATA_DESCRIPTOR_ENTRIES - 1).associate { index ->
            "usr/baseline-$index" to "file"
        }
        hierarchy.install(DataDescriptor("main", baselineFiles), FileSource.Main)

        assertThrows(DataDescriptorLimitExceeded::class.java) {
            hierarchy.install(
                DataDescriptor(
                    "failed-plugin",
                    mapOf(
                        "usr/overflow-first" to "first",
                        "usr/overflow-second" to "second",
                    ),
                ),
                FileSource.Main,
            )
        }

        hierarchy.install(
            DataDescriptor("replacement-plugin", mapOf("usr/overflow-first" to "replacement")),
            FileSource.Main,
        )
    }

    @Test
    fun rejectsFilesBelowSymlinkTargets() {
        val hierarchy = DataHierarchy()

        assertThrows(UnsafeDataDescriptorPath::class.java) {
            hierarchy.install(
                DataDescriptor(
                    "plugin",
                    mapOf("usr/link/child.conf" to "child"),
                    mapOf("usr/link" to "usr/source"),
                ),
                FileSource.Main,
            )
        }
    }

    @Test
    fun leavesHierarchyUnchangedAfterSymlinkConflict() {
        val hierarchy = DataHierarchy()
        hierarchy.install(
            DataDescriptor("main", mapOf("usr/existing.conf" to "existing")),
            FileSource.Main,
        )

        assertThrows(DataHierarchy.SymlinkConflict::class.java) {
            hierarchy.install(
                DataDescriptor(
                    "failed-plugin",
                    mapOf("usr/partial.conf" to "partial"),
                    mapOf("usr/existing.conf" to "usr/source"),
                ),
                FileSource.Main,
            )
        }

        hierarchy.install(
            DataDescriptor("replacement-plugin", mapOf("usr/partial.conf" to "replacement")),
            FileSource.Main,
        )
    }

    @Test
    fun leavesHierarchyUnchangedAfterUnsafePath() {
        val hierarchy = DataHierarchy()

        assertThrows(UnsafeDataDescriptorPath::class.java) {
            hierarchy.install(
                DataDescriptor(
                    "failed-plugin",
                    linkedMapOf(
                        "usr/partial.conf" to "partial",
                        "../shared_prefs/preferences.xml" to "unsafe",
                    ),
                ),
                FileSource.Main,
            )
        }

        hierarchy.install(
            DataDescriptor("replacement-plugin", mapOf("usr/partial.conf" to "replacement")),
            FileSource.Main,
        )
    }

    @Test
    fun rejectsUnsafeStoredHierarchyBeforeDiffing() {
        val hierarchy = DataHierarchy()
        hierarchy.install(
            DataDescriptor("main", mapOf("usr/main.conf" to "main")),
            FileSource.Main,
        )
        val unsafeStored = DataDescriptor(
            "stored",
            mapOf("usr/link/child.conf" to "child"),
            mapOf("usr/link" to "usr/source"),
        )

        assertThrows(UnsafeDataDescriptorPath::class.java) {
            DataHierarchy.diff(unsafeStored, hierarchy)
        }
    }
}
