/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingModelCallback
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.handwriting.HandwritingProviderRegistry
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionMode
import timber.log.Timber

class HandwritingSettingsFragment :
    ManagedPreferenceFragment(AppPrefs.getInstance().handwriting) {

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
    private val modelPreferences = mutableMapOf<HandwritingRecognitionMode, Preference>()

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val category = PreferenceCategory(screen.context).apply {
            setTitle(R.string.handwriting_models)
            isIconSpaceReserved = false
        }
        screen.addPreference(category)
        modelEntries.forEach { entry ->
            val preference = Preference(screen.context).apply {
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
        refreshModelStates()
    }

    override fun onResume() {
        super.onResume()
        if (modelPreferences.isNotEmpty()) {
            refreshModelStates()
        }
    }

    private fun refreshModelStates() {
        modelEntries.forEach { entry ->
            val provider = HandwritingProviderRegistry.select(entry.mode.protocolMode)
            if (provider == null) {
                updatePreference(
                    entry,
                    HandwritingProtocol.MODEL_STATE_FAILED,
                    providerUnavailable = true,
                )
                return@forEach
            }
            updatePreference(entry, HandwritingProtocol.MODEL_STATE_UNKNOWN)
            try {
                provider.remote.queryModelState(
                    entry.mode.protocolMode,
                    modelCallback(entry),
                )
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
        updatePreference(entry, HandwritingProtocol.MODEL_STATE_DOWNLOADING)
        try {
            provider.remote.downloadModel(
                entry.mode.protocolMode,
                false,
                modelCallback(entry),
            )
        } catch (e: Exception) {
            Timber.w(e, "Cannot download %s handwriting model", entry.mode)
            updatePreference(entry, HandwritingProtocol.MODEL_STATE_FAILED)
        }
    }

    private fun modelCallback(entry: ModelEntry): IHandwritingModelCallback {
        val appContext = requireContext().applicationContext
        return object : IHandwritingModelCallback.Stub() {
            override fun onState(mode: Int, state: Int, errorMessage: String) {
                ContextCompat.getMainExecutor(appContext).execute {
                    if (!isAdded || mode != entry.mode.protocolMode) return@execute
                    if (errorMessage.isNotBlank()) {
                        Timber.w("Handwriting model %s: %s", entry.mode, errorMessage)
                    }
                    updatePreference(entry, state)
                }
            }
        }
    }

    private fun updatePreference(
        entry: ModelEntry,
        state: Int,
        providerUnavailable: Boolean = false,
    ) {
        val preference = modelPreferences[entry.mode] ?: return
        preference.isEnabled = state != HandwritingProtocol.MODEL_STATE_DOWNLOADING
        preference.summary = when {
            providerUnavailable -> getString(R.string.handwriting_provider_unavailable)
            state == HandwritingProtocol.MODEL_STATE_READY &&
                    entry.mode == HandwritingRecognitionMode.Auto ->
                getString(R.string.handwriting_model_auto_ready)
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
    }
}
