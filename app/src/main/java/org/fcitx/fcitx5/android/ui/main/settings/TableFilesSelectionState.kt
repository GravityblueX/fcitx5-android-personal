/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

internal class TableFilesSelectionState(
    confUri: String? = null,
    confFileName: String? = null,
    dictUri: String? = null,
    dictFileName: String? = null,
) {

    var confUri: String? = confUri
        private set

    var confFileName: String? = confFileName
        private set

    var dictUri: String? = dictUri
        private set

    var dictFileName: String? = dictFileName
        private set

    val hasSelection: Boolean
        get() = confUri != null || dictUri != null

    val isComplete: Boolean
        get() = confUri != null && dictUri != null

    fun selectConf(uri: String, fileName: String) {
        confUri = uri
        confFileName = fileName
    }

    fun selectDict(uri: String, fileName: String) {
        dictUri = uri
        dictFileName = fileName
    }

    fun clear() {
        confUri = null
        confFileName = null
        dictUri = null
        dictFileName = null
    }
}
