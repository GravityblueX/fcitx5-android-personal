/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

internal class PendingPinyinDictionaryRouteImport(initialUri: String?) {

    var uri: String? = initialUri?.takeIf { it.isNotBlank() }
        private set

    private var isRunning = false

    fun start(): String? {
        if (isRunning) return null
        return uri?.also { isRunning = true }
    }

    fun finish(shouldRetry: Boolean) {
        isRunning = false
        if (!shouldRetry) uri = null
    }
}
