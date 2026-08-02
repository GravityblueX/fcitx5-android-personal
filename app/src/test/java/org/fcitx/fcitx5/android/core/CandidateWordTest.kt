/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateWordTest {
    @Test
    fun omitsCommentWhenRequested() {
        val candidate = CandidateWord("", "好", "(hao)")
        assertEquals("好 (hao)", candidate.textWithComment())
        assertEquals("好", candidate.textWithComment(includeComment = false))
    }
}
