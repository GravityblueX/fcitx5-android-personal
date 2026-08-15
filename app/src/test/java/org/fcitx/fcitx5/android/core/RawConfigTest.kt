/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class RawConfigTest {

    @Test
    fun deepCopyDetachesAllMutableState() {
        val original = RawConfig(
            name = "root",
            comment = "root comment",
            value = "root value",
            subItems = arrayOf(
                RawConfig(
                    name = "child",
                    comment = "child comment",
                    value = "child value",
                    subItems = arrayOf(RawConfig("leaf", "leaf value"))
                )
            )
        )

        val snapshot = original.deepCopy()

        assertEquals(original, snapshot)
        assertNotSame(original, snapshot)
        assertNotSame(original.subItems, snapshot.subItems)
        assertNotSame(original["child"], snapshot["child"])
        assertNull(snapshot["child"]["leaf"].subItems)

        original.value = "changed root"
        original["child"].value = "changed child"
        original["child"].subItems = null
        original.subItems = emptyArray()

        assertEquals("root value", snapshot.value)
        assertEquals("child value", snapshot["child"].value)
        assertEquals("leaf value", snapshot["child"]["leaf"].value)
    }
}
