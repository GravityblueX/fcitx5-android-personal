/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.provider

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
}
