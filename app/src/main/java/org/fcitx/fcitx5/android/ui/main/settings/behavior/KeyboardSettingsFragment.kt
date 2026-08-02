/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.setup

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private lateinit var customSoundLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var customSoundPreference: Preference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customSoundLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@registerForActivityResult
                val context = requireContext()
                val granted = runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }.isSuccess
                if (!granted) return@registerForActivityResult
                keyboardPrefs.customKeySoundUri.setValue(uri.toString())
                updateCustomSoundPreference()
            }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        customSoundPreference = Preference(requireContext()).apply {
            setup(getString(R.string.custom_key_sound)) { showCustomSoundActions() }
        }
        updateCustomSoundPreference()
        screen.addPreference(customSoundPreference)
    }

    private fun showCustomSoundActions() {
        val hasCustomSound = keyboardPrefs.customKeySoundUri.getValue().isNotBlank()
        val actions = buildList {
            add(getString(R.string.import_from_file))
            if (hasCustomSound) add(getString(R.string.reset))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.custom_key_sound)
            .setItems(actions.toTypedArray()) { _, selected ->
                if (selected == 0) {
                    customSoundLauncher.launch(arrayOf("audio/*"))
                } else {
                    keyboardPrefs.customKeySoundUri.setValue("")
                    updateCustomSoundPreference()
                }
            }
            .show()
    }

    private fun updateCustomSoundPreference() {
        if (!::customSoundPreference.isInitialized) return
        val uri = keyboardPrefs.customKeySoundUri.getValue()
        customSoundPreference.summary = if (uri.isBlank()) {
            getString(R.string.system_default)
        } else {
            val parsed = Uri.parse(uri)
            requireContext().contentResolver.queryFileName(parsed)
                ?: parsed.lastPathSegment
                ?: getString(R.string.custom_key_sound)
        }
    }
}
