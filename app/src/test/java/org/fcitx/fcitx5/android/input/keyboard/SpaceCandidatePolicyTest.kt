/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCandidatePolicyTest {
    @Test
    fun acceptsFirstCandidateOnlyForEnabledSingleEnglishSpaceWithPreedit() {
        assertTrue(SpaceCandidatePolicy.shouldAcceptFirstCandidate(true, true, false, true, true))
        assertFalse(SpaceCandidatePolicy.shouldAcceptFirstCandidate(false, true, false, true, true))
        assertFalse(SpaceCandidatePolicy.shouldAcceptFirstCandidate(true, false, false, true, true))
        assertFalse(SpaceCandidatePolicy.shouldAcceptFirstCandidate(true, true, true, true, true))
        assertFalse(SpaceCandidatePolicy.shouldAcceptFirstCandidate(true, true, false, false, true))
        assertFalse(SpaceCandidatePolicy.shouldAcceptFirstCandidate(true, true, false, true, false))
    }
}
