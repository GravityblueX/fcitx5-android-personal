/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.core.CandidateWord
import java.util.ArrayDeque

internal class CandidateStableIdTracker {
    private var candidates = emptyList<CandidateWord>()
    private var ids = LongArray(0)
    private var nextId = 0L

    fun update(updated: Array<CandidateWord>) {
        val reusableIds = HashMap<CandidateWord, ArrayDeque<Long>>()
        candidates.forEachIndexed { index, candidate ->
            reusableIds.getOrPut(candidate, ::ArrayDeque).addLast(ids[index])
        }
        ids = LongArray(updated.size) { index ->
            reusableIds[updated[index]]?.pollFirst() ?: nextId++
        }
        candidates = updated.toList()
    }

    operator fun get(position: Int): Long = ids[position]
}
