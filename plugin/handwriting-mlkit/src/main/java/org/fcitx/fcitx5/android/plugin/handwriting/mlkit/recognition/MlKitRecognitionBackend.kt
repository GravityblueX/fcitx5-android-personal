/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.handwriting.mlkit.recognition

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
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

/**
 * Multilingual Digital Ink Recognition backend. ML Kit types intentionally stay
 * inside the plugin process and never cross the Fcitx plugin IPC boundary.
 */
class MlKitRecognitionBackend(context: Context) : Closeable {

    class ModelNotDownloadedException : IllegalStateException()

    private enum class ModelSpec(
        val mode: Int,
        val languageTag: String,
    ) {
        Chinese(HandwritingProtocol.MODE_CHINESE_SIMPLIFIED, "zh-Hani-CN"),
        English(HandwritingProtocol.MODE_ENGLISH, "en"),
        Japanese(HandwritingProtocol.MODE_JAPANESE, "ja"),
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val modelManager by lazy { RemoteModelManager.getInstance() }
    private val models by lazy {
        ModelSpec.entries.associateWith { spec ->
            val identifier = requireNotNull(
                DigitalInkRecognitionModelIdentifier.fromLanguageTag(spec.languageTag)
            ) {
                "ML Kit does not provide the ${spec.languageTag} digital ink model"
            }
            DigitalInkRecognitionModel.builder(identifier).build()
        }
    }

    private val recognizerLock = Any()
    private val recognizers = mutableMapOf<ModelSpec, DigitalInkRecognizer>()
    private val downloadLock = Any()
    private val downloadTasks = mutableMapOf<ModelSpec, Task<Void>>()

    fun queryModelState(
        mode: Int,
        callback: (state: Int, errorMessage: String) -> Unit,
    ) {
        val specs = modelSpecs(mode) ?: run {
            callback(HandwritingProtocol.MODEL_STATE_FAILED, ERROR_UNSUPPORTED_MODE)
            return
        }
        val activeDownloads = synchronized(downloadLock) {
            specs.mapNotNull(downloadTasks::get).filterNot(Task<*>::isComplete)
        }
        if (activeDownloads.isNotEmpty()) {
            callback(HandwritingProtocol.MODEL_STATE_DOWNLOADING, "")
            Tasks.whenAllComplete(activeDownloads).addOnCompleteListener {
                resolveModelState(mode, specs, callback)
            }
            return
        }
        resolveModelState(mode, specs, callback)
    }

    fun downloadModel(
        mode: Int,
        wifiOnly: Boolean,
        callback: (state: Int, errorMessage: String) -> Unit,
    ) {
        val specs = modelSpecs(mode) ?: run {
            callback(HandwritingProtocol.MODEL_STATE_FAILED, ERROR_UNSUPPORTED_MODE)
            return
        }
        val conditions = DownloadConditions.Builder().apply {
            if (wifiOnly) requireWifi()
        }.build()
        callback(HandwritingProtocol.MODEL_STATE_DOWNLOADING, "")
        val tasks = specs.map { ensureModelDownload(it, conditions) }
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            val firstFailure = tasks.firstOrNull { !it.isSuccessful }?.exception
            resolveModelState(mode, specs) { state, errorMessage ->
                if (firstFailure != null && state != HandwritingProtocol.MODEL_STATE_READY) {
                    callback(
                        HandwritingProtocol.MODEL_STATE_FAILED,
                        firstFailure.javaClass.simpleName,
                    )
                } else {
                    callback(state, errorMessage)
                }
            }
        }
    }

    fun recognize(
        request: HandwritingRecognitionRequest,
        onSuccess: (List<BackendCandidate>) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val specs = modelSpecs(request.mode)
        if (specs == null) {
            onFailure(IllegalArgumentException(ERROR_UNSUPPORTED_MODE))
            return
        }
        val ink = runCatching { request.toMlKitInk() }.getOrElse {
            onFailure(it)
            return
        }
        val recognitionContext = RecognitionContext.builder()
            .setWritingArea(
                WritingArea(
                    request.canvasWidth.coerceAtLeast(1f),
                    request.canvasHeight.coerceAtLeast(1f),
                )
            )
            .setPreContext(request.preContext.takeLast(MAX_PRE_CONTEXT_LENGTH))
            .build()
        resolveReadyModels(
            specs = specs,
            onSuccess = { readyModels ->
                recognizeWithModels(
                    request = request,
                    readyModels = readyModels,
                    ink = ink,
                    recognitionContext = recognitionContext,
                    onSuccess = onSuccess,
                    onFailure = onFailure,
                )
            },
            onFailure = onFailure,
        )
    }

    fun notifyCandidateSelected(mode: Int, languageTag: String) {
        if (mode != HandwritingProtocol.MODE_AUTO) return
        if (ModelSpec.entries.none { it.languageTag == languageTag }) return
        preferences.edit().putString(PREFERENCE_RECENT_LANGUAGE, languageTag).apply()
    }

    override fun close() {
        synchronized(recognizerLock) {
            recognizers.values.forEach(DigitalInkRecognizer::close)
            recognizers.clear()
        }
        synchronized(downloadLock) {
            downloadTasks.clear()
        }
    }

    private fun resolveModelState(
        mode: Int,
        specs: List<ModelSpec>,
        callback: (state: Int, errorMessage: String) -> Unit,
    ) {
        val checks = specs.associateWith { modelManager.isModelDownloaded(modelFor(it)) }
        Tasks.whenAllComplete(checks.values).addOnCompleteListener {
            val readyCount = checks.values.count { it.isSuccessful && it.result == true }
            val firstFailure = checks.values.firstOrNull { !it.isSuccessful }?.exception
            val state = when {
                mode == HandwritingProtocol.MODE_AUTO && readyCount > 0 ->
                    HandwritingProtocol.MODEL_STATE_READY
                mode != HandwritingProtocol.MODE_AUTO && readyCount == 1 ->
                    HandwritingProtocol.MODEL_STATE_READY
                firstFailure != null ->
                    HandwritingProtocol.MODEL_STATE_FAILED
                else ->
                    HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED
            }
            callback(state, firstFailure?.javaClass?.simpleName.orEmpty())
        }
    }

    private fun resolveReadyModels(
        specs: List<ModelSpec>,
        onSuccess: (List<ModelSpec>) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val checks = specs.associateWith { modelManager.isModelDownloaded(modelFor(it)) }
        Tasks.whenAllComplete(checks.values).addOnCompleteListener {
            val ready = checks
                .filterValues { it.isSuccessful && it.result == true }
                .keys
                .toList()
            if (ready.isNotEmpty()) {
                onSuccess(ready)
                return@addOnCompleteListener
            }
            val failure = checks.values.firstOrNull { !it.isSuccessful }?.exception
            onFailure(failure ?: ModelNotDownloadedException())
        }
    }

    private fun recognizeWithModels(
        request: HandwritingRecognitionRequest,
        readyModels: List<ModelSpec>,
        ink: Ink,
        recognitionContext: RecognitionContext,
        onSuccess: (List<BackendCandidate>) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val tasks = readyModels.associateWith { spec ->
            getRecognizer(spec).recognize(ink, recognitionContext)
        }
        Tasks.whenAllComplete(tasks.values).addOnCompleteListener {
            val successful = tasks.filterValues(Task<*>::isSuccessful)
            if (successful.isEmpty()) {
                onFailure(
                    tasks.values.firstNotNullOfOrNull(Task<*>::getException)
                        ?: IllegalStateException(ERROR_RECOGNITION_FAILED)
                )
                return@addOnCompleteListener
            }
            val candidates = successful.flatMap { (spec, task) ->
                task.result.candidates.mapIndexedNotNull { rank, candidate ->
                    candidate.text
                        .takeIf(String::isNotBlank)
                        ?.let {
                            BackendCandidate(
                                text = it,
                                languageTag = spec.languageTag,
                                score = candidate.score,
                                sourceRank = rank,
                            )
                        }
                }
            }
            val maxCandidates = request.maxCandidates.coerceAtLeast(0)
            val result = if (request.mode == HandwritingProtocol.MODE_AUTO) {
                CandidateMerger.merge(
                    candidates = candidates,
                    maxCandidates = maxCandidates,
                    preContext = request.preContext,
                    recentLanguageTag = preferences.getString(
                        PREFERENCE_RECENT_LANGUAGE,
                        null,
                    ),
                )
            } else {
                candidates
                    .distinctBy(BackendCandidate::text)
                    .take(maxCandidates)
            }
            onSuccess(result)
        }
    }

    private fun ensureModelDownload(
        spec: ModelSpec,
        conditions: DownloadConditions,
    ): Task<Void> = synchronized(downloadLock) {
        downloadTasks[spec]?.takeUnless(Task<*>::isComplete)
            ?: modelManager.download(modelFor(spec), conditions).also { task ->
                downloadTasks[spec] = task
                task.addOnCompleteListener {
                    synchronized(downloadLock) {
                        if (downloadTasks[spec] === task) {
                            downloadTasks.remove(spec)
                        }
                    }
                }
            }
    }

    private fun getRecognizer(spec: ModelSpec): DigitalInkRecognizer =
        synchronized(recognizerLock) {
            recognizers.getOrPut(spec) {
                DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(modelFor(spec)).build()
                )
            }
        }

    private fun modelFor(spec: ModelSpec): DigitalInkRecognitionModel =
        requireNotNull(models[spec])

    private fun modelSpecs(mode: Int): List<ModelSpec>? = when (mode) {
        HandwritingProtocol.MODE_AUTO -> ModelSpec.entries
        else -> ModelSpec.entries.firstOrNull { it.mode == mode }?.let(::listOf)
    }

    private fun HandwritingRecognitionRequest.toMlKitInk(): Ink {
        if (strokes.none { it.points.isNotEmpty() }) {
            throw IllegalArgumentException("Ink has no points")
        }
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
        const val PREFERENCES_NAME = "handwriting_recognition"
        const val PREFERENCE_RECENT_LANGUAGE = "recent_auto_language"
        const val MAX_PRE_CONTEXT_LENGTH = 20
        const val ERROR_UNSUPPORTED_MODE = "UnsupportedMode"
        const val ERROR_RECOGNITION_FAILED = "RecognitionFailed"
    }
}
