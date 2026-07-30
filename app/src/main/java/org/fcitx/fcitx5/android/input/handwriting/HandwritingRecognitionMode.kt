/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class HandwritingRecognitionMode(
    override val stringRes: Int,
    val protocolMode: Int,
) : ManagedPreferenceEnum {
    Chinese(
        R.string.handwriting_mode_chinese,
        HandwritingProtocol.MODE_CHINESE_SIMPLIFIED,
    ),
    English(
        R.string.handwriting_mode_english,
        HandwritingProtocol.MODE_ENGLISH,
    ),
    Japanese(
        R.string.handwriting_mode_japanese,
        HandwritingProtocol.MODE_JAPANESE,
    ),
    Auto(
        R.string.handwriting_mode_auto,
        HandwritingProtocol.MODE_AUTO,
    );

    fun next(): HandwritingRecognitionMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromProtocolMode(mode: Int): HandwritingRecognitionMode =
            entries.firstOrNull { it.protocolMode == mode } ?: Chinese
    }
}
