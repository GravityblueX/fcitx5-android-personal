/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import androidx.preference.PreferenceDataStore
import org.fcitx.fcitx5.android.core.RawConfig

class FcitxRawConfigStore(private var cfg: RawConfig) : PreferenceDataStore() {
    private fun valueOf(key: String?) = key?.let(cfg::findByName)?.value

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        valueOf(key)?.let { it == "True" } ?: defValue

    override fun putBoolean(key: String?, value: Boolean) {
        key?.let(cfg::findByName)?.value = if (value) "True" else "False"
    }

    override fun getInt(key: String?, defValue: Int): Int =
        valueOf(key)?.toIntOrNull() ?: defValue

    override fun putInt(key: String?, value: Int) {
        key?.let(cfg::findByName)?.value = value.toString()
    }

    override fun getString(key: String?, defValue: String?): String? =
        valueOf(key) ?: defValue

    override fun putString(key: String?, value: String?) {
        key?.let(cfg::findByName)?.value = value ?: ""
    }

}
