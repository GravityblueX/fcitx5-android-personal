/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinDictManagerTest {

    @Test
    fun identifiesOnlyPinyinImportStagingFiles() {
        assertTrue(isPinyinImportStagingFile(".pinyin-import-123.staged"))
        assertFalse(isPinyinImportStagingFile("pinyin-import-123.staged"))
        assertFalse(isPinyinImportStagingFile(".pinyin-import-123.dict"))
    }
}
