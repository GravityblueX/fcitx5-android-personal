/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.utils.errorArg
import org.fcitx.fcitx5.android.utils.moveToWithoutReplacing
import java.io.File

class LibIMEDictionary(file: File) : PinyinDictionary() {

    override var file: File = file
        private set

    var isEnabled: Boolean = true
        private set

    override val type: Type = Type.LibIME

    override val name: String
        get() = if (isEnabled) super.name
        else file.name.removeSuffix(".${type.ext}.$DISABLE")

    init {
        ensureFileExists()
        isEnabled = when {
            file.extension == type.ext -> {
                true
            }
            file.name.endsWith(".${type.ext}.$DISABLE") -> {
                false
            }
            else -> errorArg(R.string.exception_libime_dict_filename, file.name)
        }
    }

    fun enable(): Boolean {
        if (isEnabled) return true
        val newFile = file.resolveSibling(name + ".${type.ext}")
        if (!file.moveToWithoutReplacing(newFile)) return false
        file = newFile
        isEnabled = true
        return true
    }

    fun disable(): Boolean {
        if (!isEnabled) return true
        val newFile = file.resolveSibling(name + ".${type.ext}.$DISABLE")
        if (!file.moveToWithoutReplacing(newFile)) return false
        file = newFile
        isEnabled = false
        return true
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        writeTxtAtomically(dest) { staged ->
            PinyinDictManager.pinyinDictConv(
                file.absolutePath,
                staged.absolutePath,
                PinyinDictManager.MODE_BIN_TO_TXT
            )
        }
        return TextDictionary(dest)
    }

    override fun toLibIMEDictionary(dest: File): LibIMEDictionary {
        if (isSameFile(dest)) {
            requireBin(dest)
            return this
        }
        writeBinAtomically(dest) { staged ->
            file.copyTo(staged, overwrite = true)
        }
        return LibIMEDictionary(dest)
    }

    companion object {
        const val DISABLE = "disable"
    }
}
