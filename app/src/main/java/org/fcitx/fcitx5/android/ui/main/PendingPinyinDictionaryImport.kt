/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

internal class PendingPinyinDictionaryImport(savedUri: String? = null) {

    var uri: String? = savedUri
        private set

    fun begin(uri: String) {
        this.uri = uri
    }

    fun consume(): String? = uri.also { uri = null }

    fun clear() {
        uri = null
    }
}
