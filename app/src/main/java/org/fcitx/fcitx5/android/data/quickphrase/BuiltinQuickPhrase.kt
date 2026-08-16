/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.utils.installNewFileAtomically
import org.fcitx.fcitx5.android.utils.removeIfExists
import java.io.File

@Parcelize
class BuiltinQuickPhrase(
    override val file: File,
    // always be .mb (not disabled)
    private val overrideFile: File,
    private var _override: CustomQuickPhrase? = null
) : QuickPhrase() {

    init {
        ensureFileExists()
        evaluateOverride()
    }

    val overrideFilePath: String
        get() = overrideFile.absolutePath

    var override: CustomQuickPhrase?
        get() = _override
        private set(value) {
            _override = value
        }

    override val isEnabled: Boolean
        get() {
            evaluateOverride()
            return override?.isEnabled ?: true
        }

    private fun createOverrideIfNotExist() {
        evaluateOverride()
        if (override != null) return
        val directory = overrideFile.parentFile
            ?: error("Cannot resolve quick phrase directory: ${overrideFile.path}")
        val created = try {
            file.inputStream().use { input ->
                installNewFileAtomically(input, directory, overrideFile.name)
            }
        } catch (e: FileAlreadyExistsException) {
            evaluateOverride()
            if (override != null) return
            throw e
        }
        override = CustomQuickPhrase(created)
    }

    private fun loadBuiltinData() = QuickPhraseData.fromLines(file.readLines())

    override fun loadData(): QuickPhraseData {
        evaluateOverride()
        return override?.loadData() ?: loadBuiltinData()
    }

    override fun saveData(data: QuickPhraseData) {
        createOverrideIfNotExist()
        override!!.saveData(data)
    }

    override fun enable(): Boolean {
        if (isEnabled) return true
        // override must exist in this case
        return override!!.enable()
    }

    override fun disable(): Boolean {
        if (!isEnabled) return true
        createOverrideIfNotExist()
        return override!!.disable()
    }

    internal fun deleteOverride(): Result<Unit> {
        var firstFailure: Throwable? = null
        listOf(overrideFile, File(overrideFile.path + ".$DISABLE")).forEach { file ->
            file.removeIfExists().onFailure { failure ->
                if (firstFailure == null) firstFailure = failure
            }
        }
        evaluateOverride()
        return firstFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /**
     * Make sure [override] is set correctly.
     */
    fun evaluateOverride() {
        override = if (overrideFile.exists())
            CustomQuickPhrase(overrideFile)
        else {
            val disabledOverride = File(overrideFile.path + ".$DISABLE")
            if (disabledOverride.exists())
                CustomQuickPhrase(disabledOverride)
            else
                null
        }
    }

    override fun toString(): String {
        return "BuiltinQuickPhrase(file=$file, overrideFile=$overrideFile, override=$override, isEnabled=$isEnabled)"
    }

}
