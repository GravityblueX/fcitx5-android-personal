/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

internal class PendingThemeExport(savedThemeName: String? = null) {

    var themeName: String? = savedThemeName
        private set

    fun begin(themeName: String) {
        this.themeName = themeName
    }

    fun consume(): String? = themeName.also { themeName = null }
}
