/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class PunctuationComponent :
    UniqueComponent<PunctuationComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val broadcaster: InputBroadcaster by manager.must()

    private var mapping: Map<String, String> = emptyMap()
    private var mappingLanguageCode: String? = null
    private var mappingJob: Job? = null
    private var mappingGeneration = 0L

    var enabled: Boolean = false
        private set

    fun transform(p: String) = mapping.getOrDefault(p, p)

    fun restorePunctuationMapping() {
        val generation = invalidatePendingUpdate()
        mappingJob = service.lifecycleScope.launch {
            val (mappingEnabled, languageCode, updatedMapping) = fcitx.runOnReady {
                val mappingEnabled = isMappingEnabled(statusAreaActionsCached)
                val languageCode = inputMethodEntryCached.languageCode
                val updatedMapping = if (mappingEnabled) {
                    loadMapping(languageCode)
                } else {
                    emptyMap()
                }
                Triple(mappingEnabled, languageCode, updatedMapping)
            }
            publishMapping(generation, mappingEnabled, languageCode, updatedMapping)
        }
    }

    fun updatePunctuationMapping(actions: Array<Action>, languageCode: String) {
        val mappingEnabled = isMappingEnabled(actions)
        enabled = mappingEnabled
        val generation = invalidatePendingUpdate()
        if (!mappingEnabled || mappingLanguageCode != languageCode) {
            clearMapping()
        }
        if (!mappingEnabled) {
            return
        }
        mappingJob = service.lifecycleScope.launch {
            val updatedMapping = fcitx.runOnReady { loadMapping(languageCode) }
            publishMapping(generation, mappingEnabled, languageCode, updatedMapping)
        }
    }

    fun cancelPendingUpdate() {
        invalidatePendingUpdate()
    }

    private fun invalidatePendingUpdate(): Long {
        mappingGeneration++
        mappingJob?.cancel()
        mappingJob = null
        return mappingGeneration
    }

    private fun isMappingEnabled(actions: Array<Action>) = actions.any {
        // TODO: A better way to check if punctuation mapping is enabled
        it.name == "punctuation" && it.icon == "fcitx-punc-active"
    }

    private suspend fun FcitxAPI.loadMapping(languageCode: String): Map<String, String> {
        val items = PunctuationManager.load(this, languageCode)
        val map = HashMap<String, String>()
        items.forEach {
            // use first entry as mapping value
            if (!map.containsKey(it.key)) {
                map[it.key] = it.mapping
            }
        }
        return map
    }

    private fun clearMapping() {
        val hadMapping = mapping.isNotEmpty()
        mapping = emptyMap()
        mappingLanguageCode = null
        if (hadMapping) {
            broadcaster.onPunctuationUpdate(mapping)
        }
    }

    private fun publishMapping(
        generation: Long,
        mappingEnabled: Boolean,
        languageCode: String,
        updatedMapping: Map<String, String>,
    ) {
        if (generation != mappingGeneration) return
        enabled = mappingEnabled
        mapping = updatedMapping
        mappingLanguageCode = languageCode.takeIf { mappingEnabled }
        broadcaster.onPunctuationUpdate(updatedMapping)
        mappingJob = null
    }
}
