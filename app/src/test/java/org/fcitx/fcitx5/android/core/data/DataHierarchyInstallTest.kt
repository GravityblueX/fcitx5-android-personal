/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertThrows
import org.junit.Test

class DataHierarchyInstallTest {

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
