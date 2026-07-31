/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.handwriting.mlkit

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Multilingual Digital Ink Recognition backend shared by the built-in engine and the
 * compatibility plugin. ML Kit types stay behind this module's small Kotlin API.
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
    private val modelInventoryLock = Any()
    private var modelInventoryTask: Task<Set<DigitalInkRecognitionModel>>? = null
    private val taskExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "HandwritingMlKit").apply {
                isDaemon = true
            }
        }

    /**
     * Starts one model inventory query for this process.
     *
     * A single inventory query replaces three concurrent isModelDownloaded() calls. ML Kit
     * delegates these operations to its MDD model manager, so sharing the inventory also avoids
     * making keyboard attachment compete with startup warm-up.
     */
    fun warmUpModelStates(onComplete: () -> Unit = {}) {
        queryDownloadedModels().addOnCompleteListener(taskExecutor) {
            onComplete()
        }
    }

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
            Tasks.whenAllComplete(activeDownloads).addOnCompleteListener(taskExecutor) {
                resolveModelState(mode, specs, callback = callback)
            }
            return
        }
        // ML Kit's isModelDownloaded() may take several seconds on some devices even though it
        // only checks a local model. Return the last verified state immediately, then validate it
        // asynchronously so opening settings or reattaching the keyboard does not repeatedly show
        // "checking model".
        val cachedState = cachedModelState(mode, specs)
        cachedState?.let { callback(it, "") }
        resolveModelState(mode, specs) { state, errorMessage ->
            // Successful inventory results may legitimately invalidate the cache (for example,
            // after a model was deleted). A transient manager error must not downgrade a model
            // that was already verified and may still be used by the recognizer.
            if (cachedState == null ||
                (errorMessage.isEmpty() && cachedState != state)
            ) {
                callback(state, errorMessage)
            }
        }
    }

    fun refreshModelState(
        mode: Int,
        callback: (state: Int, errorMessage: String) -> Unit,
    ) {
        val specs = modelSpecs(mode) ?: run {
            callback(HandwritingProtocol.MODEL_STATE_FAILED, ERROR_UNSUPPORTED_MODE)
            return
        }
        resolveModelState(mode, specs, forceCheck = true, callback = callback)
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
        Tasks.whenAllComplete(tasks).addOnCompleteListener(taskExecutor) {
            val firstFailure = tasks.firstOrNull { !it.isSuccessful }?.exception
            resolveModelState(mode, specs, forceCheck = true) { state, errorMessage ->
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
        synchronized(modelInventoryLock) {
            modelInventoryTask = null
        }
        taskExecutor.shutdownNow()
    }

    private fun resolveModelState(
        mode: Int,
        specs: List<ModelSpec>,
        forceCheck: Boolean = false,
        callback: (state: Int, errorMessage: String) -> Unit,
    ) {
        val inventory = queryDownloadedModels(forceCheck)
        inventory.addOnCompleteListener(taskExecutor) {
            val availability = specs.associateWith { spec ->
                if (inventory.isSuccessful) {
                    inventory.result.contains(modelFor(spec))
                } else if (forceCheck) {
                    null
                } else {
                    cachedModelAvailability(spec)
                }
            }
            val readyCount = availability.values.count { it == true }
            val state = when {
                mode == HandwritingProtocol.MODE_AUTO && readyCount > 0 ->
                    HandwritingProtocol.MODEL_STATE_READY
                mode != HandwritingProtocol.MODE_AUTO && readyCount == 1 ->
                    HandwritingProtocol.MODEL_STATE_READY
                availability.values.all { it != null } ->
                    HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED
                !inventory.isSuccessful ->
                    HandwritingProtocol.MODEL_STATE_FAILED
                else ->
                    HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED
            }
            callback(
                state,
                if (state == HandwritingProtocol.MODEL_STATE_FAILED) {
                    inventory.exception?.javaClass?.simpleName.orEmpty()
                } else {
                    ""
                },
            )
        }
    }

    private fun resolveReadyModels(
        specs: List<ModelSpec>,
        onSuccess: (List<ModelSpec>) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val inventory = queryDownloadedModels()
        inventory.addOnCompleteListener(taskExecutor) {
            val ready = if (inventory.isSuccessful) {
                specs.filter { inventory.result.contains(modelFor(it)) }
            } else {
                emptyList()
            }
            if (ready.isNotEmpty()) {
                onSuccess(ready)
                return@addOnCompleteListener
            }
            val failure = inventory.exception
            if (failure != null) {
                queryDownloadedModels(forceCheck = true)
            }
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
        Tasks.whenAllComplete(tasks.values).addOnCompleteListener(taskExecutor) {
            val successful = tasks.filterValues(Task<*>::isSuccessful)
            val failedModels = tasks
                .filterValues { !it.isSuccessful }
                .keys
            if (failedModels.isNotEmpty()) {
                // A previously verified model may have been removed or corrupted. Refresh only
                // after an actual recognition failure instead of before every ink request.
                queryDownloadedModels(forceCheck = true)
            }
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

    private fun queryDownloadedModels(
        forceCheck: Boolean = false,
    ): Task<Set<DigitalInkRecognitionModel>> =
        synchronized(modelInventoryLock) {
            modelInventoryTask?.let { existing ->
                if (!forceCheck || !existing.isComplete) {
                    return@synchronized existing
                }
            }
            val startedAt = SystemClock.elapsedRealtime()
            modelManager
                .getDownloadedModels(DigitalInkRecognitionModel::class.java)
                .also { task ->
                    modelInventoryTask = task
                    task.addOnCompleteListener(taskExecutor) {
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        if (task.isSuccessful) {
                            val downloaded = task.result
                            preferences.edit().apply {
                                ModelSpec.entries.forEach { spec ->
                                    putBoolean(
                                        modelStatePreference(spec),
                                        downloaded.contains(modelFor(spec)),
                                    )
                                }
                            }.apply()
                            Log.d(
                                LOG_TAG,
                                "Model inventory (${downloaded.size}) completed in ${elapsed} ms",
                            )
                        } else {
                            Log.w(
                                LOG_TAG,
                                "Model inventory failed in ${elapsed} ms",
                                task.exception,
                            )
                            synchronized(modelInventoryLock) {
                                if (modelInventoryTask === task) {
                                    modelInventoryTask = null
                                }
                            }
                        }
                    }
                }
        }

    private fun cachedModelState(mode: Int, specs: List<ModelSpec>): Int? {
        val availability = specs.map(::cachedModelAvailability)
        val readyCount = availability.count { it == true }
        return when {
            mode == HandwritingProtocol.MODE_AUTO && readyCount > 0 ->
                HandwritingProtocol.MODEL_STATE_READY
            mode != HandwritingProtocol.MODE_AUTO && readyCount == 1 ->
                HandwritingProtocol.MODEL_STATE_READY
            availability.all { it != null } ->
                HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED
            else -> null
        }
    }

    private fun cachedModelAvailability(spec: ModelSpec): Boolean? {
        val key = modelStatePreference(spec)
        return if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    }

    private fun modelStatePreference(spec: ModelSpec) =
        "$PREFERENCE_MODEL_DOWNLOADED_PREFIX${spec.languageTag}"

    private fun ensureModelDownload(
        spec: ModelSpec,
        conditions: DownloadConditions,
    ): Task<Void> = synchronized(downloadLock) {
        downloadTasks[spec]?.takeUnless(Task<*>::isComplete)
            ?: modelManager.download(modelFor(spec), conditions).also { task ->
                downloadTasks[spec] = task
                task.addOnCompleteListener(taskExecutor) {
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
        const val PREFERENCE_MODEL_DOWNLOADED_PREFIX = "model_downloaded_"
        const val MAX_PRE_CONTEXT_LENGTH = 20
        const val ERROR_UNSUPPORTED_MODE = "UnsupportedMode"
        const val ERROR_RECOGNITION_FAILED = "RecognitionFailed"
        const val LOG_TAG = "HandwritingBackend"
    }
}
