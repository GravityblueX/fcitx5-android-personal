/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import java.io.File

internal class PendingHeapDump(savedPath: String? = null) {

    var file: File? = savedPath?.let(::File)
        private set

    val path: String?
        get() = file?.path

    fun begin(file: File): File? = this.file.also { this.file = file }

    fun consume(): File? = file.also { file = null }
}
