/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionCandidate
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionRequest
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionResponse
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingModelCallback
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionCallback
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionProvider
import org.fcitx.fcitx5.android.common.handwriting.mlkit.BackendCandidate
import org.fcitx.fcitx5.android.common.handwriting.mlkit.MlKitRecognitionBackend

/**
 * In-process handwriting provider used by the main application.
 *
 * The AIDL stub is retained as a common provider interface, but calls to this instance are local
 * method calls. No service binding, package activation, Binder transport, or companion APK is
 * involved.
 */
class BuiltInHandwritingRecognitionProvider(context: Context) {

    private val appContext = context.applicationContext
    private val backendLock = Any()
    private var backend = MlKitRecognitionBackend(appContext)
    private var reloadGeneration = 0L

    val remote: IHandwritingRecognitionProvider =
        object : IHandwritingRecognitionProvider.Stub() {
            override fun getProtocolVersion(): Int = HandwritingProtocol.VERSION

            override fun getProviderId(): String = PROVIDER_ID

            override fun getSupportedModes(): IntArray = SUPPORTED_MODES

            override fun recognize(
                request: HandwritingRecognitionRequest,
                callback: IHandwritingRecognitionCallback,
            ) {
                if (request.mode !in SUPPORTED_MODES) {
                    callback.respond(
                        HandwritingRecognitionResponse(
                            requestId = request.requestId,
                            candidates = emptyList(),
                            errorCode = HandwritingProtocol.ERROR_UNAVAILABLE,
                        )
                    )
                    return
                }
                currentBackend().recognize(
                    request = request,
                    onSuccess = { recognized ->
                        callback.respond(
                            HandwritingRecognitionResponse(
                                requestId = request.requestId,
                                candidates = recognized.map { it.toParcelable() },
                            )
                        )
                    },
                    onFailure = { error ->
                        val errorCode = when (error) {
                            is MlKitRecognitionBackend.ModelNotDownloadedException ->
                                HandwritingProtocol.ERROR_MODEL_NOT_DOWNLOADED
                            is IllegalArgumentException ->
                                HandwritingProtocol.ERROR_INVALID_REQUEST
                            else -> HandwritingProtocol.ERROR_RECOGNITION_FAILED
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

            override fun queryModelState(
                mode: Int,
                callback: IHandwritingModelCallback,
            ) {
                if (!validateMode(mode, callback)) return
                currentBackend().queryModelState(mode) { state, errorMessage ->
                    callback.respond(mode, state, errorMessage)
                }
            }

            override fun refreshModelState(
                mode: Int,
                callback: IHandwritingModelCallback,
            ) {
                if (!validateMode(mode, callback)) return
                currentBackend().refreshModelState(mode) { state, errorMessage ->
                    callback.respond(mode, state, errorMessage)
                }
            }

            override fun downloadModel(
                mode: Int,
                wifiOnly: Boolean,
                callback: IHandwritingModelCallback,
            ) {
                if (!validateMode(mode, callback)) return
                currentBackend().downloadModel(mode, wifiOnly) { state, errorMessage ->
                    callback.respond(mode, state, errorMessage)
                }
            }

            override fun notifyCandidateSelected(mode: Int, languageTag: String) {
                currentBackend().notifyCandidateSelected(mode, languageTag)
            }
        }

    fun warmUp() {
        warmUp(currentBackend())
    }

    /**
     * Replaces the recognition backend only after its local model inventory has initialized.
     * Existing recognition requests may finish on the previous backend during the handover.
     */
    fun reload(onComplete: (Boolean) -> Unit = {}) {
        val generation = synchronized(backendLock) {
            ++reloadGeneration
        }
        val replacement = runCatching {
            MlKitRecognitionBackend(appContext)
        }.getOrElse {
            log("Cannot create replacement recognition backend: ${it.javaClass.simpleName}")
            onComplete(false)
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        replacement.warmUpModelStates {
            val previous = synchronized(backendLock) {
                if (generation != reloadGeneration) {
                    null
                } else {
                    backend.also { backend = replacement }
                }
            }
            if (previous == null) {
                replacement.close()
                return@warmUpModelStates
            }
            previous.close()
            log(
                "Recognition engine reloaded in " +
                        "${SystemClock.elapsedRealtime() - startedAt} ms"
            )
            onComplete(true)
        }
    }

    private fun warmUp(target: MlKitRecognitionBackend) {
        val startedAt = SystemClock.elapsedRealtime()
        target.warmUpModelStates {
            log(
                "Built-in model state warm-up completed in " +
                        "${SystemClock.elapsedRealtime() - startedAt} ms"
            )
        }
    }

    private fun currentBackend(): MlKitRecognitionBackend =
        synchronized(backendLock) { backend }

    private fun validateMode(
        mode: Int,
        callback: IHandwritingModelCallback,
    ): Boolean {
        if (mode in SUPPORTED_MODES) return true
        callback.respond(
            mode,
            HandwritingProtocol.MODEL_STATE_FAILED,
            "UnsupportedMode",
        )
        return false
    }

    private fun BackendCandidate.toParcelable() = HandwritingRecognitionCandidate(
        text = text,
        languageTag = languageTag,
        score = score ?: Float.NaN,
    )

    private fun IHandwritingRecognitionCallback.respond(
        response: HandwritingRecognitionResponse,
    ) {
        runCatching { onResult(response) }
            .onFailure {
                log("Recognition callback unavailable: ${it.javaClass.simpleName}")
            }
    }

    private fun IHandwritingModelCallback.respond(
        mode: Int,
        state: Int,
        errorMessage: String,
    ) {
        runCatching { onState(mode, state, errorMessage) }
            .onFailure {
                log("Model callback unavailable: ${it.javaClass.simpleName}")
            }
    }

    private fun log(message: String) {
        Log.d(LOG_TAG, message)
    }

    private companion object {
        const val LOG_TAG = "HandwritingBuiltIn"
        const val PROVIDER_ID = "fcitx17.handwriting.builtin"
        val SUPPORTED_MODES = intArrayOf(
            HandwritingProtocol.MODE_AUTO,
            HandwritingProtocol.MODE_CHINESE_SIMPLIFIED,
            HandwritingProtocol.MODE_ENGLISH,
            HandwritingProtocol.MODE_JAPANESE,
        )
    }
}
