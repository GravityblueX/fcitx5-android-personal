/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import android.system.Os
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.errorArg
import java.io.File

@Parcelize
class CustomQuickPhrase(
    private var _file: File,
    private var _enabled: Boolean = false
) : QuickPhrase() {

    override var isEnabled: Boolean
        get() = _enabled
        private set(value) {
            _enabled = value
        }

    override var file: File
        get() = _file
        private set(value) {
            _file = value
        }

    override val name: String
        get() = if (isEnabled) super.name
        else file.name.substringBefore(".$EXT.$DISABLE")

    override fun loadData() = QuickPhraseData.fromLines(file.readLines())

    init {
        ensureFileExists()
        isEnabled = when {
            file.extension == EXT -> {
                true
            }
            file.name.endsWith(".$EXT.$DISABLE") -> {
                false
            }
            else -> errorArg(R.string.exception_quickphrase_filename, file.name)
        }
    }

    override fun enable(): Boolean {
        if (isEnabled) return true
        val newFile = file.resolveSibling("$name.$EXT")
        if (!file.renameTo(newFile)) return false
        file = newFile
        isEnabled = true
        return true
    }

    override fun disable(): Boolean {
        if (!isEnabled) return true
        val newFile = file.resolveSibling("$name.$EXT.$DISABLE")
        if (!file.renameTo(newFile)) return false
        file = newFile
        isEnabled = false
        return true
    }

    override fun saveData(data: QuickPhraseData) {
        val parent = file.parentFile ?: error("Cannot resolve quick phrase directory: ${file.path}")
        check(parent.mkdirs() || parent.isDirectory) {
            "Cannot create quick phrase directory: ${parent}"
        }
        val staged = File.createTempFile("quickphrase-", ".staged", parent)
        try {
            staged.writeText(data.serialize())
            Os.rename(staged.path, file.path)
        } finally {
            staged.delete()
        }
    }

    override fun toString(): String {
        return "CustomQuickPhrase(isEnabled=$isEnabled, file=$file, name='$name')"
    }

}
