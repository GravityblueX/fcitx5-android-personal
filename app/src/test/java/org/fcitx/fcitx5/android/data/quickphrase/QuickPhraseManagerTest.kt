/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickPhraseManagerTest {

    @Test
    fun normalizesImportedQuickPhraseNames() {
        val target = quickPhraseImportTarget("..\\..\\custom.name.mb")!!

        assertEquals("custom.name.mb", target.fileName)
        assertEquals("custom.name", target.entryName)
    }

    @Test
    fun rejectsInvalidImportedQuickPhraseNames() {
        listOf("quickphrase", ".mb", "   .mb", "..mb", "custom.mb.disable")
            .forEach { assertNull(quickPhraseImportTarget(it)) }
    }
}
