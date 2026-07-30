/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.handwriting.mlkit

import android.util.Log
import org.fcitx.fcitx5.android.common.FcitxPluginService
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionCandidate
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionRequest
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionResponse
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionCallback
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionProvider
import org.fcitx.fcitx5.android.common.ipc.FcitxRemoteConnection
import org.fcitx.fcitx5.android.common.ipc.bindFcitxRemoteService

class MainService : FcitxPluginService() {

    private lateinit var connection: FcitxRemoteConnection

    private val provider = object : IHandwritingRecognitionProvider.Stub() {
        override fun getProtocolVersion(): Int = HandwritingProtocol.VERSION

        override fun getProviderId(): String = PROVIDER_ID

        override fun getSupportedModes(): IntArray =
            intArrayOf(HandwritingProtocol.MODE_CHINESE_SIMPLIFIED)

        override fun recognize(
            request: HandwritingRecognitionRequest,
            callback: IHandwritingRecognitionCallback,
        ) {
            val candidates = FIXED_CANDIDATES
                .take(request.maxCandidates.coerceIn(0, FIXED_CANDIDATES.size))
                .map {
                    HandwritingRecognitionCandidate(
                        text = it,
                        languageTag = "zh-Hans",
                    )
                }
            callback.onResult(
                HandwritingRecognitionResponse(
                    requestId = request.requestId,
                    candidates = candidates,
                )
            )
        }
    }

    override fun start() {
        connection = bindFcitxRemoteService(BuildConfig.MAIN_APPLICATION_ID) {
            log("Bound to Fcitx17 remote service")
            it.registerHandwritingRecognitionProvider(provider)
        }
    }

    override fun stop() {
        runCatching {
            connection.remoteService?.unregisterHandwritingRecognitionProvider(provider)
        }
        unbindService(connection)
        log("Unbound from Fcitx17 remote service")
    }

    private fun log(message: String) {
        Log.d(LOG_TAG, message)
    }

    private companion object {
        const val LOG_TAG = "HandwritingPlugin"
        const val PROVIDER_ID = "fcitx17.handwriting.stage1.fixed"
        val FIXED_CANDIDATES = listOf("你好", "手写", "Fcitx17", "测试")
    }
}
