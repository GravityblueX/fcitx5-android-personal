/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.handwriting.mlkit.recognition

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionRequest
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chinese Digital Ink Recognition backend. ML Kit types intentionally stay inside
 * the plugin process and never cross the Fcitx plugin IPC boundary.
 */
class MlKitRecognitionBackend : Closeable {

    class ModelNotDownloadedException : IllegalStateException()

    private val model: DigitalInkRecognitionModel by lazy {
        val identifier = requireNotNull(
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGUAGE_TAG)
        ) {
            "ML Kit does not provide the $LANGUAGE_TAG digital ink model"
        }
        DigitalInkRecognitionModel.builder(identifier).build()
    }

    private val modelManager by lazy { RemoteModelManager.getInstance() }
    private val recognizerLock = Any()
    private var recognizer: DigitalInkRecognizer? = null

    @Volatile
    private var modelState = HandwritingProtocol.MODEL_STATE_UNKNOWN

    private val downloadObservers =
        CopyOnWriteArrayList<(state: Int, errorMessage: String) -> Unit>()

    fun queryModelState(callback: (state: Int, errorMessage: String) -> Unit) {
        if (modelState == HandwritingProtocol.MODEL_STATE_DOWNLOADING) {
            downloadObservers += callback
            callback(modelState, "")
            return
        }
        modelManager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (modelState == HandwritingProtocol.MODEL_STATE_DOWNLOADING) {
                    downloadObservers += callback
                    callback(HandwritingProtocol.MODEL_STATE_DOWNLOADING, "")
                    return@addOnSuccessListener
                }
                val state = if (downloaded) {
                    HandwritingProtocol.MODEL_STATE_READY
                } else {
                    HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED
                }
                modelState = state
                callback(state, "")
            }
            .addOnFailureListener { error ->
                modelState = HandwritingProtocol.MODEL_STATE_FAILED
                callback(
                    HandwritingProtocol.MODEL_STATE_FAILED,
                    error.javaClass.simpleName,
                )
            }
    }

    fun downloadModel(
        wifiOnly: Boolean,
        callback: (state: Int, errorMessage: String) -> Unit,
    ) {
        downloadObservers += callback
        if (modelState == HandwritingProtocol.MODEL_STATE_DOWNLOADING) {
            callback(modelState, "")
            return
        }
        updateDownloadObservers(HandwritingProtocol.MODEL_STATE_DOWNLOADING)
        val conditions = DownloadConditions.Builder().apply {
            if (wifiOnly) requireWifi()
        }.build()
        modelManager.download(model, conditions)
            .addOnSuccessListener {
                updateDownloadObservers(HandwritingProtocol.MODEL_STATE_READY)
                downloadObservers.clear()
            }
            .addOnFailureListener { error ->
                updateDownloadObservers(
                    HandwritingProtocol.MODEL_STATE_FAILED,
                    error.javaClass.simpleName,
                )
                downloadObservers.clear()
            }
    }

    fun recognize(
        request: HandwritingRecognitionRequest,
        onSuccess: (List<String>) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        if (request.strokes.none { it.points.isNotEmpty() }) {
            onFailure(IllegalArgumentException("Ink has no points"))
            return
        }
        modelManager.isModelDownloaded(model)
            .addOnFailureListener(onFailure)
            .addOnSuccessListener { downloaded ->
                if (!downloaded) {
                    modelState = HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED
                    onFailure(ModelNotDownloadedException())
                    return@addOnSuccessListener
                }
                modelState = HandwritingProtocol.MODEL_STATE_READY
                runCatching {
                    val ink = request.toMlKitInk()
                    val context = RecognitionContext.builder()
                        .setWritingArea(
                            WritingArea(
                                request.canvasWidth.coerceAtLeast(1f),
                                request.canvasHeight.coerceAtLeast(1f),
                            )
                        )
                        .setPreContext(request.preContext.takeLast(MAX_PRE_CONTEXT_LENGTH))
                        .build()
                    getRecognizer().recognize(ink, context)
                        .addOnSuccessListener { result ->
                            onSuccess(
                                result.candidates
                                    .asSequence()
                                    .map { it.text }
                                    .filter { it.isNotBlank() }
                                    .distinct()
                                    .take(request.maxCandidates.coerceAtLeast(0))
                                    .toList()
                            )
                        }
                        .addOnFailureListener(onFailure)
                }.onFailure(onFailure)
            }
    }

    override fun close() {
        downloadObservers.clear()
        synchronized(recognizerLock) {
            recognizer?.close()
            recognizer = null
        }
    }

    private fun getRecognizer(): DigitalInkRecognizer = synchronized(recognizerLock) {
        recognizer ?: DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        ).also { recognizer = it }
    }

    private fun updateDownloadObservers(state: Int, errorMessage: String = "") {
        modelState = state
        downloadObservers.forEach { it(state, errorMessage) }
    }

    private fun HandwritingRecognitionRequest.toMlKitInk(): Ink {
        val inkBuilder = Ink.builder()
        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val strokeBuilder = Ink.Stroke.builder()
            stroke.points.forEach { point ->
                strokeBuilder.addPoint(
                    Ink.Point.create(point.x, point.y, point.timestampMillis)
                )
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        return inkBuilder.build()
    }

    private companion object {
        const val LANGUAGE_TAG = "zh-Hani-CN"
        const val MAX_PRE_CONTEXT_LENGTH = 20
    }
}
