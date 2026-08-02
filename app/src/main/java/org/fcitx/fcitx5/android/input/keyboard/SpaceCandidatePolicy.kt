/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

internal object SpaceCandidatePolicy {
    fun shouldAcceptFirstCandidate(
        enabled: Boolean,
        isVirtualSpace: Boolean,
        isRepeat: Boolean,
        isEnglish: Boolean,
        hasPreedit: Boolean,
    ): Boolean = enabled && isVirtualSpace && !isRepeat && isEnglish && hasPreedit
}
