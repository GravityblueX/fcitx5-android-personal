/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.handwriting;

import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionResponse;

oneway interface IHandwritingRecognitionCallback {
    void onResult(in HandwritingRecognitionResponse response);
}
