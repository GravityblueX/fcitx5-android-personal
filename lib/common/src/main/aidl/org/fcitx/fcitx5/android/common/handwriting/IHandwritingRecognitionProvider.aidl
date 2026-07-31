/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.handwriting;

import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionRequest;
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingModelCallback;
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionCallback;

interface IHandwritingRecognitionProvider {
    int getProtocolVersion();
    String getProviderId();
    int[] getSupportedModes();
    oneway void recognize(
        in HandwritingRecognitionRequest request,
        IHandwritingRecognitionCallback callback
    );
    // Model operations are deliberately synchronous Binder transactions. Their implementations
    // only enqueue asynchronous ML Kit work, while synchronous delivery prevents control requests
    // from being silently deferred by vendor background-process policies.
    void queryModelState(
        int mode,
        IHandwritingModelCallback callback
    );
    void refreshModelState(
        int mode,
        IHandwritingModelCallback callback
    );
    void downloadModel(
        int mode,
        boolean wifiOnly,
        IHandwritingModelCallback callback
    );
    oneway void notifyCandidateSelected(
        int mode,
        String languageTag
    );
}
