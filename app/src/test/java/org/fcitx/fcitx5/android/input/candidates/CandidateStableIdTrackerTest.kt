/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CandidateStableIdTrackerTest {

    @Test
    fun assignsDistinctIdsToDuplicateCandidates() {
        val candidate = candidate("same")
        val tracker = CandidateStableIdTracker()

        tracker.update(arrayOf(candidate, candidate))

        assertNotEquals(tracker[0], tracker[1])
    }

    @Test
    fun assignsDistinctIdsWhenCandidateHashesCollide() {
        val first = candidate("Aa")
        val second = candidate("BB")
        assertEquals(first.hashCode(), second.hashCode())
        val tracker = CandidateStableIdTracker()

        tracker.update(arrayOf(first, second))

        assertNotEquals(tracker[0], tracker[1])
    }

    @Test
    fun preservesIdsWhenCandidatesReorder() {
        val first = candidate("first")
        val second = candidate("second")
        val tracker = CandidateStableIdTracker()
        tracker.update(arrayOf(first, second))
        val firstId = tracker[0]
        val secondId = tracker[1]

        tracker.update(arrayOf(second, first))

        assertEquals(secondId, tracker[0])
        assertEquals(firstId, tracker[1])
    }

    @Test
    fun preservesIdsForEqualReplacementInstances() {
        val tracker = CandidateStableIdTracker()
        tracker.update(arrayOf(candidate("same")))
        val id = tracker[0]

        tracker.update(arrayOf(candidate("same")))

        assertEquals(id, tracker[0])
    }

    @Test
    fun preservesExistingDuplicateIdsAndAllocatesNewOne() {
        val candidate = candidate("same")
        val tracker = CandidateStableIdTracker()
        tracker.update(arrayOf(candidate, candidate))
        val firstId = tracker[0]
        val secondId = tracker[1]

        tracker.update(arrayOf(candidate, candidate, candidate))

        assertEquals(firstId, tracker[0])
        assertEquals(secondId, tracker[1])
        assertNotEquals(firstId, tracker[2])
        assertNotEquals(secondId, tracker[2])
    }

    private fun candidate(text: String) = CandidateWord("", text, "")
}
