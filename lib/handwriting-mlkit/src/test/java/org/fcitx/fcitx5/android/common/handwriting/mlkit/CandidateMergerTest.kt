/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.handwriting.mlkit

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateMergerTest {

    @Test
    fun `deduplicates text and keeps context-matching source`() {
        val merged = CandidateMerger.merge(
            candidates = listOf(
                candidate("日本", "zh-Hani-CN"),
                candidate("日本", "ja"),
            ),
            maxCandidates = 8,
            preContext = "これは",
            recentLanguageTag = null,
        )

        assertEquals(1, merged.size)
        assertEquals("ja", merged.single().languageTag)
    }

    @Test
    fun `latin context promotes English candidate`() {
        val merged = CandidateMerger.merge(
            candidates = listOf(
                candidate("你好", "zh-Hani-CN"),
                candidate("hello", "en"),
            ),
            maxCandidates = 8,
            preContext = "say ",
            recentLanguageTag = "zh-Hani-CN",
        )

        assertEquals("hello", merged.first().text)
    }

    @Test
    fun `recent language breaks an empty-context tie`() {
        val merged = CandidateMerger.merge(
            candidates = listOf(
                candidate("一", "zh-Hani-CN"),
                candidate("ー", "ja"),
            ),
            maxCandidates = 8,
            preContext = "",
            recentLanguageTag = "ja",
        )

        assertEquals("ja", merged.first().languageTag)
    }

    @Test
    fun `lower optional score wins without requiring scores`() {
        val merged = CandidateMerger.merge(
            candidates = listOf(
                candidate("one", "en", score = null),
                candidate("two", "en", score = -4f),
            ),
            maxCandidates = 1,
            preContext = "",
            recentLanguageTag = null,
        )

        assertEquals("two", merged.single().text)
    }

    private fun candidate(
        text: String,
        languageTag: String,
        score: Float? = null,
        rank: Int = 0,
    ) = BackendCandidate(text, languageTag, score, rank)
}
