/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.isEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

abstract class FcitxPreferenceFragment : PaddingPreferenceFragment() {
    abstract fun getPageTitle(): String
    abstract suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig
    abstract suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig)

    private lateinit var raw: RawConfig
    private var configLoaded = false

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.Main.immediate)

    private val viewModel: MainViewModel by activityViewModels()

    private val fcitx: FcitxConnection
        get() = viewModel.fcitx

    private val configSaver by lazy {
        val connection = fcitx
        SequentialSaveRunner<RawConfig>(
            scope = scope,
            save = { newConfig ->
                saveMutex.withLock {
                    connection.runOnReady {
                        saveConfig(this, newConfig)
                    }
                }
            },
            onFailure = { Timber.e(it, "Failed to save Fcitx configuration") }
        )
    }

    private fun save() {
        if (!configLoaded) return
        configSaver.submit(raw["cfg"].deepCopy())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher
            .addCallback(this, object : OnBackPressedCallback(true) {
                // prevent "back" from navigating away from this Fragment when it's still saving
                override fun handleOnBackPressed() {
                    lifecycleScope.withLoadingDialog(requireContext(), R.string.saving) {
                        val result = if (configLoaded) {
                            save()
                            configSaver.awaitIdle()
                        } else {
                            Result.success(Unit)
                        }
                        result.exceptionOrNull()?.let {
                            requireContext().toast(it)
                            return@withLoadingDialog
                        }
                        scope.cancel()
                        findNavController().popBackStack()
                    }
                }
            })
    }

    /**
     * **TLDR:**
     * Intentionally empty, since we need to create PreferenceScreen during onStart,
     * or it will crash when MainActivity relaunches.
     *
     * **Long version:**
     * When `MainActivity` relaunches, its `onCreate` get called, and somewhere in `super.onCreate`
     * decided to `restoreChildFragmentState` of `NavHostFragment`, thus recreate the child fragment.
     * If that fragment was derived from `FcitxPreferenceFragment`, it needs to call `obtainConfig`
     * which would need the route params, and in turn needs `NavGraph`.
     * But at this time it's still in `MainActivity`'s `super.onCreate`, the Activity did not have
     * chance to set up `NavGraph` on `navController`, so accessing `lazyRoute` would crash.
     *
     * That is to say, if we declare `app:navGraph` on `<FragmentContainerView />` in `activity_main.xml`,
     * the graph would have been initialized when `NavHostFragment` got inflated, and does not suffer
     * from this problem? But maintain navigation destinations in XML is too tedious ...
     */
    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // make sure to create preference only once since `onViewCreated` is also called on Fragment resume
        if (preferenceScreen?.isEmpty() == false) return
        val context = requireContext()
        lifecycleScope.withLoadingDialog(context) {
            raw = fcitx.runOnReady { obtainConfig(this) }
            configLoaded = raw.findByName("cfg") != null && raw.findByName("desc") != null
            preferenceScreen = if (configLoaded) {
                PreferenceScreenFactory.create(
                    preferenceManager, parentFragmentManager, raw, ::save
                ).apply {
                    if (isEmpty()) {
                        addPreference(R.string.no_config_options)
                    }
                }
            } else {
                preferenceManager.createPreferenceScreen(context).apply {
                    addPreference(R.string.config_addon_not_loaded)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getPageTitle())
    }

    companion object {
        private val saveMutex = Mutex()
    }
}
