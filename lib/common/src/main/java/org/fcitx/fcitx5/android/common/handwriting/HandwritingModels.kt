/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.handwriting

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

object HandwritingProtocol {
    const val VERSION = 2

    const val MODE_AUTO = 0
    const val MODE_CHINESE_SIMPLIFIED = 1
    const val MODE_ENGLISH = 2
    const val MODE_JAPANESE = 3

    const val ERROR_NONE = 0
    const val ERROR_UNAVAILABLE = 1
    const val ERROR_RECOGNITION_FAILED = 2
    const val ERROR_MODEL_NOT_DOWNLOADED = 3
    const val ERROR_INVALID_REQUEST = 4

    const val MODEL_STATE_UNKNOWN = 0
    const val MODEL_STATE_NOT_DOWNLOADED = 1
    const val MODEL_STATE_DOWNLOADING = 2
    const val MODEL_STATE_READY = 3
    const val MODEL_STATE_FAILED = 4
}

@Parcelize
data class HandwritingInkPoint(
    val x: Float,
    val y: Float,
    val timestampMillis: Long,
    val pressure: Float = 1f,
    val toolType: Int = 0,
) : Parcelable

@Parcelize
data class HandwritingInkStroke(
    val points: List<HandwritingInkPoint>,
) : Parcelable

@Parcelize
data class HandwritingRecognitionCandidate(
    val text: String,
    val languageTag: String = "",
    val score: Float = Float.NaN,
) : Parcelable

@Parcelize
data class HandwritingRecognitionRequest(
    val requestId: Long,
    val mode: Int,
    val strokes: List<HandwritingInkStroke>,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val preContext: String,
    val maxCandidates: Int,
) : Parcelable

@Parcelize
data class HandwritingRecognitionResponse(
    val requestId: Long,
    val candidates: List<HandwritingRecognitionCandidate>,
    val errorCode: Int = HandwritingProtocol.ERROR_NONE,
    val errorMessage: String = "",
) : Parcelable
