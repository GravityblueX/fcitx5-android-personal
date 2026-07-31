/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingModelCallback
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.handwriting.HandwritingProviderRegistry
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionMode
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

class HandwritingSettingsFragment :
    ManagedPreferenceFragment(AppPrefs.getInstance().handwriting) {

    private class ModelPreference(context: Context) : Preference(context) {
        var progressVisible = false
            set(value) {
                if (field == value) return
                field = value
                notifyChanged()
            }

        init {
            widgetLayoutResource = R.layout.preference_widget_handwriting_model_progress
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            (holder.findViewById(R.id.handwriting_model_progress)
                    as? LinearProgressIndicator)?.visibility =
                if (progressVisible) View.VISIBLE else View.GONE
        }
    }

    private data class ModelEntry(
        val mode: HandwritingRecognitionMode,
        val estimatedSizeMb: Int,
    )

    private val modelEntries = listOf(
        ModelEntry(HandwritingRecognitionMode.Chinese, 20),
        ModelEntry(HandwritingRecognitionMode.English, 20),
        ModelEntry(HandwritingRecognitionMode.Japanese, 20),
        ModelEntry(HandwritingRecognitionMode.Auto, 60),
    )
    private val languageEntries =
        modelEntries.filter { it.mode != HandwritingRecognitionMode.Auto }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelPreferences = mutableMapOf<HandwritingRecognitionMode, ModelPreference>()
    private val languageModelStates = mutableMapOf<HandwritingRecognitionMode, Int>()
    private val modelQueryGenerations = mutableMapOf<HandwritingRecognitionMode, Long>()
    private val modelCheckTimedOutModes = mutableSetOf<HandwritingRecognitionMode>()
    private lateinit var reloadEnginePreference: Preference
    private lateinit var refreshModelsPreference: Preference
    private var autoModelState = HandwritingProtocol.MODEL_STATE_UNKNOWN
    private var autoProviderUnavailable = false
    private val providerUnavailableModes = mutableSetOf<HandwritingRecognitionMode>()
    private var manualReloadInProgress = false
    private var manualModelRefreshInProgress = false
    private val providerChangeListener = {
        ContextCompat.getMainExecutor(appContext).execute {
            if (isAdded) {
                if (!manualReloadInProgress) {
                    refreshModelStates()
                }
            }
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        reloadEnginePreference = Preference(screen.context).apply {
            setTitle(R.string.handwriting_reload_engine)
            setSummary(R.string.handwriting_reload_engine_summary)
            setIcon(R.drawable.ic_baseline_sync_24)
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                reloadRecognitionEngine()
                true
            }
        }
        screen.addPreference(reloadEnginePreference)
        refreshModelsPreference = Preference(screen.context).apply {
            setTitle(R.string.handwriting_refresh_models)
            setSummary(R.string.handwriting_refresh_models_summary)
            setIcon(R.drawable.ic_baseline_search_24)
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                refreshModels()
                true
            }
        }
        screen.addPreference(refreshModelsPreference)
        val category = PreferenceCategory(screen.context).apply {
            setTitle(R.string.handwriting_models)
            isIconSpaceReserved = false
        }
        screen.addPreference(category)
        modelEntries.forEach { entry ->
            val preference = ModelPreference(screen.context).apply {
                key = "handwriting_model_${entry.mode.name.lowercase()}"
                setTitle(entry.mode.stringRes)
                setSummary(R.string.handwriting_model_checking_settings)
                setIcon(R.drawable.ic_baseline_download_24)
                isIconSpaceReserved = true
                setOnPreferenceClickListener {
                    downloadModel(entry)
                    true
                }
            }
            modelPreferences[entry.mode] = preference
            category.addPreference(preference)
        }
    }

    override fun onStart() {
        super.onStart()
        HandwritingProviderRegistry.addOnChangeListener(providerChangeListener)
        if (modelPreferences.isNotEmpty()) {
            refreshModelStates()
        }
    }

    override fun onStop() {
        HandwritingProviderRegistry.removeOnChangeListener(providerChangeListener)
        super.onStop()
    }

    private fun refreshModelStates(forceCheck: Boolean = false) {
        autoProviderUnavailable =
            HandwritingProviderRegistry.select(HandwritingRecognitionMode.Auto.protocolMode) == null
        refreshLanguageModelStates(forceCheck)
        updateAutoPreference()
    }

    private fun refreshLanguageModelStates(forceCheck: Boolean = false) {
        languageEntries.forEach { entry ->
            val provider = HandwritingProviderRegistry.select(entry.mode.protocolMode)
            if (provider == null) {
                updatePreference(
                    entry,
                    HandwritingProtocol.MODEL_STATE_FAILED,
                    providerUnavailable = true,
                )
                return@forEach
            }
            val wasUnavailable = providerUnavailableModes.remove(entry.mode)
            if (forceCheck || wasUnavailable || languageModelStates[entry.mode] == null) {
                updatePreference(entry, HandwritingProtocol.MODEL_STATE_UNKNOWN)
            }
            val queryGeneration = nextQueryGeneration(entry.mode)
            if (languageModelStates[entry.mode] == HandwritingProtocol.MODEL_STATE_UNKNOWN) {
                postModelCheckTimeout(entry, queryGeneration)
            }
            try {
                if (forceCheck) {
                    provider.remote.refreshModelState(
                        entry.mode.protocolMode,
                        modelCallback(entry, queryGeneration),
                    )
                } else {
                    provider.remote.queryModelState(
                        entry.mode.protocolMode,
                        modelCallback(entry, queryGeneration),
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Cannot query %s handwriting model", entry.mode)
                updatePreference(
                    entry,
                    HandwritingProtocol.MODEL_STATE_FAILED,
                    providerUnavailable = true,
                )
            }
        }
    }

    private fun downloadModel(entry: ModelEntry) {
        val provider = HandwritingProviderRegistry.select(entry.mode.protocolMode)
        if (provider == null) {
            updatePreference(
                entry,
                HandwritingProtocol.MODEL_STATE_FAILED,
                providerUnavailable = true,
            )
            return
        }
        val queryGeneration = nextQueryGeneration(entry.mode)
        updatePreference(entry, HandwritingProtocol.MODEL_STATE_DOWNLOADING)
        try {
            provider.remote.downloadModel(
                entry.mode.protocolMode,
                false,
                modelCallback(entry, queryGeneration),
            )
        } catch (e: Exception) {
            Timber.w(e, "Cannot download %s handwriting model", entry.mode)
            updatePreference(entry, HandwritingProtocol.MODEL_STATE_FAILED)
        }
    }

    private fun reloadRecognitionEngine() {
        manualReloadInProgress = true
        manualModelRefreshInProgress = false
        invalidateModelQueries()
        languageEntries.forEach {
            updatePreference(it, HandwritingProtocol.MODEL_STATE_UNKNOWN)
        }
        autoModelState = HandwritingProtocol.MODEL_STATE_UNKNOWN
        autoProviderUnavailable = false
        updateAutoPreference()
        setManualActionsEnabled(false)
        reloadEnginePreference.setSummary(R.string.handwriting_reload_engine_in_progress)
        if (!HandwritingProviderRegistry.reloadBuiltIn { engineReady ->
            ContextCompat.getMainExecutor(appContext).execute {
                manualReloadInProgress = false
                setManualActionsEnabled(true)
                reloadEnginePreference.setSummary(
                    if (engineReady) {
                        R.string.handwriting_reload_engine_summary
                    } else {
                        R.string.handwriting_reload_engine_failed
                    }
                )
                if (!isAdded) return@execute
                if (engineReady) {
                    refreshModelStates()
                } else {
                    languageEntries.forEach {
                        updatePreference(
                            it,
                            HandwritingProtocol.MODEL_STATE_FAILED,
                            providerUnavailable = true,
                        )
                    }
                    autoProviderUnavailable = true
                    updateAutoPreference()
                }
            }
        }) {
            manualReloadInProgress = false
            setManualActionsEnabled(true)
            reloadEnginePreference.setSummary(R.string.handwriting_reload_engine_failed)
        }
    }

    private fun refreshModels() {
        manualModelRefreshInProgress = false
        invalidateModelQueries()
        languageEntries.forEach {
            updatePreference(it, HandwritingProtocol.MODEL_STATE_UNKNOWN)
        }
        autoModelState = HandwritingProtocol.MODEL_STATE_UNKNOWN
        autoProviderUnavailable = false
        updateAutoPreference()
        manualModelRefreshInProgress = true
        setManualActionsEnabled(false)
        refreshModelsPreference.setSummary(R.string.handwriting_refresh_models_in_progress)
        refreshModelStates(forceCheck = true)
        maybeFinishManualModelRefresh()
    }

    private fun setManualActionsEnabled(enabled: Boolean) {
        reloadEnginePreference.isEnabled = enabled
        refreshModelsPreference.isEnabled = enabled
    }

    private fun maybeFinishManualModelRefresh() {
        if (!manualModelRefreshInProgress) return
        val states = languageEntries.map { languageModelStates[it.mode] }
        if (states.any {
                it == null ||
                        it == HandwritingProtocol.MODEL_STATE_UNKNOWN ||
                        it == HandwritingProtocol.MODEL_STATE_DOWNLOADING
            }
        ) {
            return
        }
        manualModelRefreshInProgress = false
        setManualActionsEnabled(true)
        refreshModelsPreference.setSummary(
            if (providerUnavailableModes.isEmpty() &&
                states.none { it == HandwritingProtocol.MODEL_STATE_FAILED }
            ) {
                R.string.handwriting_refresh_models_summary
            } else {
                R.string.handwriting_refresh_models_failed
            }
        )
    }

    private fun nextQueryGeneration(mode: HandwritingRecognitionMode): Long {
        val generation = (modelQueryGenerations[mode] ?: 0L) + 1L
        modelQueryGenerations[mode] = generation
        return generation
    }

    private fun invalidateModelQueries() {
        modelEntries.forEach { nextQueryGeneration(it.mode) }
    }

    private fun postModelCheckTimeout(
        entry: ModelEntry,
        queryGeneration: Long,
    ) {
        mainHandler.postDelayed(
            {
                if (isAdded &&
                    modelQueryGenerations[entry.mode] == queryGeneration &&
                    languageModelStates[entry.mode] ==
                    HandwritingProtocol.MODEL_STATE_UNKNOWN
                ) {
                    updatePreference(
                        entry,
                        HandwritingProtocol.MODEL_STATE_FAILED,
                        checkTimedOut = true,
                    )
                }
            },
            MODEL_QUERY_TIMEOUT_MS,
        )
    }

    private fun modelCallback(
        entry: ModelEntry,
        queryGeneration: Long,
    ): IHandwritingModelCallback {
        val appContext = requireContext().applicationContext
        return object : IHandwritingModelCallback.Stub() {
            override fun onState(mode: Int, state: Int, errorMessage: String) {
                ContextCompat.getMainExecutor(appContext).execute {
                    if (!isAdded ||
                        mode != entry.mode.protocolMode ||
                        modelQueryGenerations[entry.mode] != queryGeneration
                    ) {
                        return@execute
                    }
                    if (errorMessage.isNotBlank()) {
                        Timber.w("Handwriting model %s: %s", entry.mode, errorMessage)
                    }
                    updatePreference(
                        entry,
                        state,
                        checkTimedOut = errorMessage == MODEL_CHECK_TIMEOUT_ERROR,
                    )
                    if (entry.mode == HandwritingRecognitionMode.Auto &&
                        state != HandwritingProtocol.MODEL_STATE_DOWNLOADING
                    ) {
                        refreshLanguageModelStates()
                    }
                }
            }
        }
    }

    private fun updatePreference(
        entry: ModelEntry,
        state: Int,
        providerUnavailable: Boolean = false,
        checkTimedOut: Boolean = false,
    ) {
        if (checkTimedOut) {
            modelCheckTimedOutModes.add(entry.mode)
        } else {
            modelCheckTimedOutModes.remove(entry.mode)
        }
        if (entry.mode == HandwritingRecognitionMode.Auto) {
            autoModelState = state
            autoProviderUnavailable = providerUnavailable
            updateAutoPreference()
            return
        }
        if (providerUnavailable) {
            providerUnavailableModes.add(entry.mode)
        } else {
            providerUnavailableModes.remove(entry.mode)
        }
        languageModelStates[entry.mode] = state
        val preference = modelPreferences[entry.mode] ?: return
        preference.progressVisible =
            state == HandwritingProtocol.MODEL_STATE_DOWNLOADING
        preference.isEnabled = state != HandwritingProtocol.MODEL_STATE_DOWNLOADING
        preference.summary = when {
            providerUnavailable -> getString(R.string.handwriting_provider_unavailable)
            entry.mode in modelCheckTimedOutModes ->
                getString(R.string.handwriting_model_check_timed_out_settings)
            state == HandwritingProtocol.MODEL_STATE_READY ->
                getString(R.string.handwriting_model_ready)
            state == HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED ->
                getString(R.string.handwriting_model_not_downloaded, entry.estimatedSizeMb)
            state == HandwritingProtocol.MODEL_STATE_DOWNLOADING ->
                getString(R.string.handwriting_model_downloading_settings)
            state == HandwritingProtocol.MODEL_STATE_FAILED ->
                getString(R.string.handwriting_model_failed_settings)
            else -> getString(R.string.handwriting_model_checking_settings)
        }
        updateAutoPreference()
        maybeFinishManualModelRefresh()
    }

    private fun updateAutoPreference() {
        val preference = modelPreferences[HandwritingRecognitionMode.Auto] ?: return
        val states = languageEntries.map { languageModelStates[it.mode] }
        val downloading =
            autoModelState == HandwritingProtocol.MODEL_STATE_DOWNLOADING ||
                    states.any { it == HandwritingProtocol.MODEL_STATE_DOWNLOADING }
        preference.progressVisible = downloading
        preference.isEnabled = !downloading
        preference.summary = when {
            autoProviderUnavailable -> getString(R.string.handwriting_provider_unavailable)
            downloading -> getString(R.string.handwriting_model_downloading_settings)
            states.any { it == null || it == HandwritingProtocol.MODEL_STATE_UNKNOWN } ->
                getString(R.string.handwriting_model_checking_settings)
            languageEntries.any { it.mode in modelCheckTimedOutModes } ->
                getString(R.string.handwriting_model_check_timed_out_settings)
            else -> {
                val missing = languageEntries
                    .filter { languageModelStates[it.mode] != HandwritingProtocol.MODEL_STATE_READY }
                    .joinToString(getString(R.string.handwriting_model_list_separator)) {
                        getString(it.mode.stringRes)
                    }
                if (missing.isEmpty()) {
                    getString(R.string.handwriting_model_ready)
                } else {
                    getString(R.string.handwriting_model_missing_languages, missing)
                }
            }
        }
    }

    private companion object {
        const val MODEL_QUERY_TIMEOUT_MS = 6_000L
        const val MODEL_CHECK_TIMEOUT_ERROR = "TimeoutException"
    }
}
