/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.utils.ensureDirectory
import org.fcitx.fcitx5.android.utils.errorArg
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.runWithCleanup
import timber.log.Timber
import java.io.File

class SougouDictionary(file: File) : PinyinDictionary() {
    override var file: File = file
        private set

    override val type: Type = Type.Sougou

    init {
        ensureFileExists()
        if (file.extension != type.ext)
            errorArg(R.string.exception_sougou_dict_filename, file.name)
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        ensureTxt(dest)
        PinyinDictManager.sougouDictConv(file.absolutePath, dest.absolutePath)
        return TextDictionary(dest)
    }

    override fun toLibIMEDictionary(dest: File): LibIMEDictionary {
        val parent = dest.parentFile ?: error("Cannot resolve dictionary directory: ${dest.path}")
        parent.ensureDirectory()
        val intermediate = File.createTempFile("sougou-", ".txt", parent)
        return runWithCleanup(
            cleanup = { intermediate.removeIfExists() },
            onCleanupFailure = { failure ->
                Timber.w(failure, "Failed to remove intermediate dictionary: ${intermediate.path}")
            },
        ) {
            toTextDictionary(intermediate).toLibIMEDictionary(dest)
        }
    }

}
