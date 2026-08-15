/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.databinding.ActivityClipboardEditBinding
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.str

class ClipboardEditActivity : Activity() {

    private val scope: CoroutineScope = MainScope()

    private lateinit var binding: ActivityClipboardEditBinding

    private lateinit var editText: EditText

    private var entryId: Int = -1

    private var loadGeneration = 0L

    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        binding = ActivityClipboardEditBinding.inflate(layoutInflater).apply {
            editText = clipboardEditText
            clipboardEditCancel.setOnClickListener { finish() }
            clipboardEditOk.setOnClickListener { finishEditing() }
            clipboardEditCopy.setOnClickListener { finishEditing(copy = true) }
        }
        setContentView(binding.root)
        processIntent(intent)
    }

    private fun finishEditing(copy: Boolean = false) {
        if (entryId < 0) return
        val str = editText.str
        ClipboardManager.updateTextAsync(entryId, str, copy)
        finish()
    }

    private fun setEditingEnabled(enabled: Boolean) {
        editText.isEnabled = enabled
        binding.clipboardEditOk.isEnabled = enabled
        binding.clipboardEditCopy.isEnabled = enabled
    }

    private fun setEntry(entry: ClipboardEntry) {
        entryId = entry.id
        editText.setText(entry.text)
        setEditingEnabled(true)
        editText.requestFocus()
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        entryId = -1
        editText.text.clear()
        setEditingEnabled(false)
        loadJob = scope.launch {
            val entry = intent.run {
                if (getBooleanExtra(LAST_ENTRY, false)) {
                    ClipboardManager.lastEntry
                } else {
                    ClipboardManager.get(getIntExtra(ENTRY_ID, -1))
                }
            }
            if (generation != loadGeneration) return@launch
            if (entry == null) {
                finish()
                return@launch
            }
            setEntry(entry)
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ENTRY_ID = "id"
        const val LAST_ENTRY = "last_entry"
    }
}
