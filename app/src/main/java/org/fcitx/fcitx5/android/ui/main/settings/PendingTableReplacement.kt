/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

internal class PendingTableReplacement(savedConfigFileName: String? = null) {

    var configFileName: String? = savedConfigFileName
        private set

    fun begin(configFileName: String) {
        this.configFileName = configFileName
    }

    fun consume(): String? = configFileName.also { configFileName = null }
}
