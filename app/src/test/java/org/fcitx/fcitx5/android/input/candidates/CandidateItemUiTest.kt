/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateItemUiTest {

    @Test
    fun contentDescriptionIncludesVisibleComment() {
        val candidate = CandidateWord("1", "example", "comment")

        assertEquals("example, comment", candidateContentDescription(candidate, true))
    }

    @Test
    fun contentDescriptionOmitsHiddenOrBlankComment() {
        val candidate = CandidateWord("1", "example", "comment")

        assertEquals("example", candidateContentDescription(candidate, false))
        assertEquals("example", candidateContentDescription(candidate.copy(comment = ""), true))
    }
}
