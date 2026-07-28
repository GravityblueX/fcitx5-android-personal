/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder

open class PagingCandidateViewAdapter(val theme: Theme) :
    PagingDataAdapter<CandidateWord, CandidateViewHolder>(diffCallback) {

    var contentScale = 1f
        private set

    companion object {
        /**
         * Always re-bind all [CandidateViewHolder]s every time to make sure `idx` is up-to-date.
         * [CandidateViewHolder.update] would skip unnecessary UI updates.
         */
        private val diffCallback = object : DiffUtil.ItemCallback<CandidateWord>() {
            override fun areItemsTheSame(oldItem: CandidateWord, newItem: CandidateWord) = false
            override fun areContentsTheSame(oldItem: CandidateWord, newItem: CandidateWord) = false
        }
    }

    var offset = 0
        private set

    fun refreshWithOffset(offset: Int) {
        this.offset = offset
        refresh()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        return CandidateViewHolder(CandidateItemUi(parent.context, theme)).also {
            // Subclasses assign their layout params after this method returns. Calling the
            // overridable applyContentScale() here would access those params too early.
            it.ui.setContentScale(contentScale)
        }
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        applyContentScale(holder)
        val candidate = getItem(position) ?: CandidateWord.Empty
        holder.update(position + offset, candidate)
    }

    fun setContentScale(scale: Float) {
        contentScale = scale.coerceIn(0f, 1f)
    }

    open fun applyContentScale(holder: CandidateViewHolder) {
        holder.ui.setContentScale(contentScale)
    }
}
