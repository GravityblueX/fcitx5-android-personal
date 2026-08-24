/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class DataHierarchyDiffTest {

    private fun hierarchy(descriptor: DataDescriptor) = DataHierarchy().apply {
        install(descriptor, FileSource.Main)
    }

    private fun sortedDiff(old: DataDescriptor, new: DataHierarchy) =
        DataHierarchy.diff(old, new).sortedByDescending { it.ordinal }

    private fun aggregateIdentity(vararg identities: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(identities.joinToString(separator = "").encodeToByteArray())
            .let(Base64.getEncoder()::encodeToString)

    @Test
    fun diffsContentsWhenAggregateIdentityMatches() {
        val path = "usr/share/changed.conf"
        val new = hierarchy(DataDescriptor("reused-identity", mapOf(path to "new-hash")))
        val old = DataDescriptor(
            aggregateIdentity("reused-identity"),
            mapOf(path to "old-hash"),
        )

        assertEquals(
            listOf(FileAction.UpdateFile(path, FileSource.Main)),
            sortedDiff(old, new),
        )
    }

    @Test
    fun deletesObsoleteSymlinkBeforeCreatingReplacementFile() {
        val path = "usr/share/replacement"
        val new = hierarchy(DataDescriptor("new", mapOf(path to "new-hash")))
        val old = DataDescriptor(
            "old",
            emptyMap(),
            mapOf(path to "usr/share/old-source"),
        )

        assertEquals(
            listOf(
                FileAction.DeleteBeforeCreate(path),
                FileAction.CreateFile(path, FileSource.Main),
            ),
            sortedDiff(old, new),
        )
    }

    @Test
    fun deletesDirectoryBeforeCreatingReplacementFile() {
        val path = "usr/share/replacement"
        val new = hierarchy(DataDescriptor("new", mapOf(path to "new-hash")))
        val old = DataDescriptor("old", mapOf(path to ""))

        assertEquals(
            listOf(
                FileAction.DeleteBeforeCreate(path),
                FileAction.CreateFile(path, FileSource.Main),
            ),
            sortedDiff(old, new),
        )
    }

    @Test
    fun deletesBlockingFileBeforeCreatingDescendant() {
        val parent = "usr/share/replacement"
        val child = "$parent/child.conf"
        val new = hierarchy(DataDescriptor("new", mapOf(child to "new-hash")))
        val old = DataDescriptor("old", mapOf(parent to "old-hash"))

        assertEquals(
            listOf(
                FileAction.DeleteBeforeCreate(parent),
                FileAction.CreateFile(child, FileSource.Main),
            ),
            sortedDiff(old, new),
        )
    }

    @Test
    fun deletesBlockingSymlinkBeforeCreatingDescendant() {
        val parent = "usr/share/replacement"
        val child = "$parent/child.conf"
        val new = hierarchy(DataDescriptor("new", mapOf(child to "new-hash")))
        val old = DataDescriptor(
            "old",
            emptyMap(),
            mapOf(parent to "usr/share/old-source"),
        )

        assertEquals(
            listOf(
                FileAction.DeleteBeforeCreate(parent),
                FileAction.CreateFile(child, FileSource.Main),
            ),
            sortedDiff(old, new),
        )
    }
}
