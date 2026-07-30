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
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingModelCallback
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionCallback
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionProvider
import org.fcitx.fcitx5.android.common.ipc.FcitxRemoteConnection
import org.fcitx.fcitx5.android.common.ipc.bindFcitxRemoteService
import org.fcitx.fcitx5.android.plugin.handwriting.mlkit.recognition.MlKitRecognitionBackend

class MainService : FcitxPluginService() {

    private lateinit var connection: FcitxRemoteConnection
    private val backend by lazy { MlKitRecognitionBackend() }

    private val provider = object : IHandwritingRecognitionProvider.Stub() {
        override fun getProtocolVersion(): Int = HandwritingProtocol.VERSION

        override fun getProviderId(): String = PROVIDER_ID

        override fun getSupportedModes(): IntArray =
            intArrayOf(HandwritingProtocol.MODE_CHINESE_SIMPLIFIED)

        override fun recognize(
            request: HandwritingRecognitionRequest,
            callback: IHandwritingRecognitionCallback,
        ) {
            if (request.mode != HandwritingProtocol.MODE_CHINESE_SIMPLIFIED) {
                callback.respond(
                    HandwritingRecognitionResponse(
                        requestId = request.requestId,
                        candidates = emptyList(),
                        errorCode = HandwritingProtocol.ERROR_UNAVAILABLE,
                    )
                )
                return
            }
            backend.recognize(
                request = request,
                onSuccess = { recognized ->
                    val candidates = recognized.map {
                        HandwritingRecognitionCandidate(
                            text = it,
                            languageTag = LANGUAGE_TAG,
                        )
                    }
                    callback.respond(
                        HandwritingRecognitionResponse(
                            requestId = request.requestId,
                            candidates = candidates,
                        )
                    )
                },
                onFailure = { error ->
                    val errorCode = when (error) {
                        is MlKitRecognitionBackend.ModelNotDownloadedException ->
                            HandwritingProtocol.ERROR_MODEL_NOT_DOWNLOADED
                        is IllegalArgumentException ->
                            HandwritingProtocol.ERROR_INVALID_REQUEST
                        else ->
                            HandwritingProtocol.ERROR_RECOGNITION_FAILED
                    }
                    log("Recognition failed: ${error.javaClass.simpleName}")
                    callback.respond(
                        HandwritingRecognitionResponse(
                            requestId = request.requestId,
                            candidates = emptyList(),
                            errorCode = errorCode,
                            errorMessage = error.javaClass.simpleName,
                        )
                    )
                },
            )
        }

        override fun queryModelState(mode: Int, callback: IHandwritingModelCallback) {
            if (mode != HandwritingProtocol.MODE_CHINESE_SIMPLIFIED) {
                callback.respond(
                    mode,
                    HandwritingProtocol.MODEL_STATE_FAILED,
                    "UnsupportedMode",
                )
                return
            }
            backend.queryModelState { state, errorMessage ->
                callback.respond(mode, state, errorMessage)
            }
        }

        override fun downloadModel(
            mode: Int,
            wifiOnly: Boolean,
            callback: IHandwritingModelCallback,
        ) {
            if (mode != HandwritingProtocol.MODE_CHINESE_SIMPLIFIED) {
                callback.respond(
                    mode,
                    HandwritingProtocol.MODEL_STATE_FAILED,
                    "UnsupportedMode",
                )
                return
            }
            backend.downloadModel(wifiOnly) { state, errorMessage ->
                callback.respond(mode, state, errorMessage)
            }
        }
    }

    private fun IHandwritingRecognitionCallback.respond(
        response: HandwritingRecognitionResponse
    ) {
        runCatching { onResult(response) }
            .onFailure { log("Recognition callback unavailable: ${it.javaClass.simpleName}") }
    }

    private fun IHandwritingModelCallback.respond(
        mode: Int,
        state: Int,
        errorMessage: String,
    ) {
        runCatching { onState(mode, state, errorMessage) }
            .onFailure { log("Model callback unavailable: ${it.javaClass.simpleName}") }
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
        if (::connection.isInitialized) {
            unbindService(connection)
        }
        backend.close()
        log("Unbound from Fcitx17 remote service")
    }

    private fun log(message: String) {
        Log.d(LOG_TAG, message)
    }

    private companion object {
        const val LOG_TAG = "HandwritingPlugin"
        const val PROVIDER_ID = "fcitx17.handwriting.mlkit"
        const val LANGUAGE_TAG = "zh-Hani-CN"
    }
}
