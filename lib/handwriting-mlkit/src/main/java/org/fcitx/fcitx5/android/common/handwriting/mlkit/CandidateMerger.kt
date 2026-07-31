/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.handwriting.mlkit

data class BackendCandidate(
    val text: String,
    val languageTag: String,
    val score: Float?,
    val sourceRank: Int,
)

/**
 * Combines candidates from independently ranked language models. ML Kit scores
 * are optional, so source rank remains the stable base signal.
 */
internal object CandidateMerger {

    fun merge(
        candidates: List<BackendCandidate>,
        maxCandidates: Int,
        preContext: String,
        recentLanguageTag: String?,
    ): List<BackendCandidate> {
        if (maxCandidates <= 0) return emptyList()
        val contextLanguage = contextLanguage(preContext)
        return candidates
            .withIndex()
            .groupBy { it.value.text }
            .values
            .map { duplicates ->
                duplicates.minWith(
                    compareBy<IndexedValue<BackendCandidate>> {
                        rankingScore(it.value, contextLanguage, recentLanguageTag)
                    }.thenBy { it.index }
                )
            }
            .sortedWith(
                compareBy<IndexedValue<BackendCandidate>> {
                    rankingScore(it.value, contextLanguage, recentLanguageTag)
                }.thenBy { it.index }
            )
            .asSequence()
            .map { it.value }
            .take(maxCandidates)
            .toList()
    }

    private fun rankingScore(
        candidate: BackendCandidate,
        contextLanguage: String?,
        recentLanguageTag: String?,
    ): Float {
        var value = candidate.sourceRank * RANK_WEIGHT
        candidate.score
            ?.takeIf(Float::isFinite)
            ?.coerceIn(MIN_SCORE, MAX_SCORE)
            ?.let { value += it * SCORE_WEIGHT }
        if (candidate.languageTag == contextLanguage) {
            value -= CONTEXT_LANGUAGE_BONUS
        }
        if (candidate.languageTag == recentLanguageTag) {
            value -= RECENT_LANGUAGE_BONUS
        }
        value += scriptPenalty(candidate)
        return value
    }

    private fun scriptPenalty(candidate: BackendCandidate): Float {
        val scripts = candidate.text.scriptCounts()
        return when {
            scripts.kana > 0 ->
                if (candidate.languageTag == LANGUAGE_JAPANESE) {
                    -SCRIPT_MATCH_BONUS
                } else {
                    SCRIPT_MISMATCH_PENALTY
                }
            scripts.latin > scripts.han && scripts.latin > 0 ->
                if (candidate.languageTag == LANGUAGE_ENGLISH) {
                    -SCRIPT_MATCH_BONUS
                } else {
                    SCRIPT_MISMATCH_PENALTY
                }
            scripts.han > 0 && candidate.languageTag == LANGUAGE_CHINESE ->
                -HAN_CHINESE_TIE_BREAK
            else -> 0f
        }
    }

    private fun contextLanguage(context: String): String? {
        val scripts = context.takeLast(MAX_CONTEXT_LENGTH).scriptCounts()
        return when {
            scripts.kana > 0 -> LANGUAGE_JAPANESE
            scripts.latin > scripts.han && scripts.latin > 0 -> LANGUAGE_ENGLISH
            scripts.han > 0 -> LANGUAGE_CHINESE
            else -> null
        }
    }

    private data class ScriptCounts(
        val latin: Int,
        val han: Int,
        val kana: Int,
    )

    private fun String.scriptCounts(): ScriptCounts {
        var latin = 0
        var han = 0
        var kana = 0
        forEach { character ->
            when {
                character.isLatinLetter() -> latin++
                character.isHanCharacter() -> han++
                character.isKanaCharacter() -> kana++
            }
        }
        return ScriptCounts(latin, han, kana)
    }

    private fun Char.isLatinLetter(): Boolean =
        this in 'A'..'Z' ||
            this in 'a'..'z' ||
            this in '\u00C0'..'\u024F' ||
            this in '\u1E00'..'\u1EFF'

    private fun Char.isHanCharacter(): Boolean =
        this in '\u3400'..'\u4DBF' ||
            this in '\u4E00'..'\u9FFF' ||
            this in '\uF900'..'\uFAFF'

    private fun Char.isKanaCharacter(): Boolean =
        this in '\u3040'..'\u30FF' ||
            this in '\u31F0'..'\u31FF' ||
            this in '\uFF66'..'\uFF9D'

    private const val LANGUAGE_CHINESE = "zh-Hani-CN"
    private const val LANGUAGE_ENGLISH = "en"
    private const val LANGUAGE_JAPANESE = "ja"
    private const val MAX_CONTEXT_LENGTH = 20
    private const val RANK_WEIGHT = 10f
    private const val SCORE_WEIGHT = 0.25f
    private const val MIN_SCORE = -8f
    private const val MAX_SCORE = 8f
    private const val CONTEXT_LANGUAGE_BONUS = 6f
    private const val RECENT_LANGUAGE_BONUS = 3f
    private const val SCRIPT_MATCH_BONUS = 2f
    private const val SCRIPT_MISMATCH_PENALTY = 2f
    private const val HAN_CHINESE_TIE_BREAK = 0.25f
}
