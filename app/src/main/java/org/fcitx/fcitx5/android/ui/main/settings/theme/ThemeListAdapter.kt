/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.views.dsl.core.Ui

internal fun adjustPositionAfterRemoval(position: Int, removedPosition: Int): Int = when {
    position < 0 -> position
    position == removedPosition -> -1
    position > removedPosition -> position - 1
    else -> position
}

internal fun adjustPositionAfterMoveToFront(
    position: Int,
    movedPosition: Int,
    firstPosition: Int,
): Int = when {
    position < 0 -> position
    position == movedPosition -> firstPosition
    position in firstPosition until movedPosition -> position + 1
    else -> position
}

abstract class ThemeListAdapter : RecyclerView.Adapter<ThemeListAdapter.ViewHolder>() {
    class ViewHolder(val ui: Ui) : RecyclerView.ViewHolder(ui.root)

    val entries = mutableListOf<Theme>()

    private var activeIndex = -1
    private var lightIndex = -1
    private var darkIndex = -1

    private fun entryAt(position: Int) = entries.getOrNull(position - OFFSET)

    private fun positionOf(theme: Theme? = null): Int {
        if (theme == null) return -1
        val index = entries.indexOfFirst { it.name == theme.name }
        return if (index == -1) -1 else index + OFFSET
    }

    fun setThemes(themes: List<Theme>) {
        entries.clear()
        entries.addAll(themes)
        notifyItemRangeInserted(OFFSET, themes.size)
    }

    fun setSelectedThemes(active: Theme, light: Theme? = null, dark: Theme? = null) {
        val oldActive = entryAt(activeIndex)
        if (oldActive != active) {
            if (activeIndex >= OFFSET) {
                notifyItemChanged(activeIndex)
            }
            activeIndex = positionOf(active)
            if (activeIndex >= OFFSET) {
                notifyItemChanged(activeIndex)
            }
        }
        val oldLight = entryAt(lightIndex)
        if (oldLight != light) {
            notifyItemChanged(lightIndex)
            lightIndex = positionOf(light)
            if (lightIndex >= OFFSET) {
                notifyItemChanged(lightIndex)
            }
        }
        val oldDark = entryAt(darkIndex)
        if (oldDark != dark) {
            notifyItemChanged(darkIndex)
            darkIndex = positionOf(dark)
            if (darkIndex >= OFFSET) {
                notifyItemChanged(darkIndex)
            }
        }
    }

    private fun prependOffset(index: Int): Int {
        return if (index == -1) 0 else 1
    }

    fun prependTheme(it: Theme) {
        entries.add(0, it)
        activeIndex += prependOffset(activeIndex)
        lightIndex += prependOffset(lightIndex)
        darkIndex += prependOffset(darkIndex)
        notifyItemInserted(OFFSET)
    }

    fun removeTheme(name: String) {
        val index = entries.indexOfFirst { it.name == name }
        if (index == -1) return
        val removedPosition = index + OFFSET
        entries.removeAt(index)
        activeIndex = adjustPositionAfterRemoval(activeIndex, removedPosition)
        lightIndex = adjustPositionAfterRemoval(lightIndex, removedPosition)
        darkIndex = adjustPositionAfterRemoval(darkIndex, removedPosition)
        notifyItemRemoved(removedPosition)
    }

    fun replaceTheme(theme: Theme) {
        val index = entries.indexOfFirst { it.name == theme.name }
        if (index == -1) {
            prependTheme(theme)
            return
        }
        if (index == 0) {
            entries[index] = theme
            notifyItemChanged(OFFSET)
            return
        }
        val movedPosition = index + OFFSET
        entries.removeAt(index)
        entries.add(0, theme)
        activeIndex = adjustPositionAfterMoveToFront(activeIndex, movedPosition, OFFSET)
        lightIndex = adjustPositionAfterMoveToFront(lightIndex, movedPosition, OFFSET)
        darkIndex = adjustPositionAfterMoveToFront(darkIndex, movedPosition, OFFSET)
        notifyItemMoved(movedPosition, OFFSET)
        notifyItemChanged(OFFSET)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            when (viewType) {
                ADD_THEME -> NewThemeEntryUi(parent.context)
                THEME -> ThemeThumbnailUi(parent.context)
                else -> throw IllegalArgumentException(INVALID_TYPE + viewType)
            }
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val it = getItemViewType(position)) {
            ADD_THEME -> holder.ui.root.setOnClickListener { onAddNewTheme() }
            THEME -> (holder.ui as ThemeThumbnailUi).apply {
                val theme = entryAt(position)!!
                setTheme(theme)
                setChecked(
                    when (position) {
                        darkIndex -> ThemeThumbnailUi.State.DarkMode
                        lightIndex -> ThemeThumbnailUi.State.LightMode
                        activeIndex -> ThemeThumbnailUi.State.Selected
                        else -> ThemeThumbnailUi.State.Normal
                    }
                )
                root.setOnClickListener {
                    onSelectTheme(theme)
                }
                root.setOnLongClickListener {
                    if (theme is Theme.Custom) {
                        onExportTheme(theme)
                        true
                    } else if (theme is Theme.Monet) {
                        onExportTheme(theme.toCustom())
                        true
                    } else false
                }
                editButton.setOnClickListener {
                    if (theme is Theme.Custom) onEditTheme(theme)
                }
            }
            else -> throw IllegalArgumentException(INVALID_TYPE + it)
        }
    }

    override fun getItemCount() = entries.size + 1

    override fun getItemViewType(position: Int) = if (position == 0) ADD_THEME else THEME

    abstract fun onAddNewTheme()

    abstract fun onSelectTheme(theme: Theme)

    abstract fun onEditTheme(theme: Theme.Custom)

    abstract fun onExportTheme(theme: Theme.Custom)

    companion object {
        const val OFFSET = 1

        const val ADD_THEME = 0
        const val THEME = 1

        const val INVALID_TYPE = "Invalid ItemView Type: "
    }
}
