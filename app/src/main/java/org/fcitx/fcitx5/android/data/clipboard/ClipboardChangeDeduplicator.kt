/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

internal data class ClipboardContentSignature(
    val text: String,
    val type: String,
    val sensitive: Boolean,
)

internal data class ClipboardChangeSnapshot(
    val sourceTimestamp: Long?,
    val receivedAtElapsedMillis: Long,
    val content: List<ClipboardContentSignature>,
)

internal class ClipboardChangeDeduplicator(
    private val legacyDuplicateWindowMillis: Long = 100L,
) {
    init {
        require(legacyDuplicateWindowMillis > 0L)
    }

    private var previous: ClipboardChangeSnapshot? = null

    @Synchronized
    fun shouldIgnore(change: ClipboardChangeSnapshot): Boolean {
        val duplicate = previous?.let { change.isDuplicateOf(it) } ?: false
        previous = change
        return duplicate
    }

    private fun ClipboardChangeSnapshot.isDuplicateOf(
        previous: ClipboardChangeSnapshot,
    ): Boolean {
        if (content != previous.content) return false
        if (sourceTimestamp != null || previous.sourceTimestamp != null) {
            return sourceTimestamp != null && sourceTimestamp == previous.sourceTimestamp
        }
        val elapsedMillis = receivedAtElapsedMillis - previous.receivedAtElapsedMillis
        return elapsedMillis >= 0L && elapsedMillis < legacyDuplicateWindowMillis
    }
}
