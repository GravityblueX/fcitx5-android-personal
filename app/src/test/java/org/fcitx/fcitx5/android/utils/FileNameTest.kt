/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameTest {

    @Test
    fun removesUnixAndWindowsPathPrefixes() {
        assertEquals("dictionary.txt", "../../dictionary.txt".safeFileName())
        assertEquals("dictionary.txt", "..\\..\\dictionary.txt".safeFileName())
    }

    @Test
    fun substitutesUnsafePathComponents() {
        assertEquals("import", "".safeFileName())
        assertEquals("import", ".".safeFileName())
        assertEquals("import", "..".safeFileName())
    }
}
