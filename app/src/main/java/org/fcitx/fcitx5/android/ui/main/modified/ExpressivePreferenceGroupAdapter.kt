/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx17 Contributors
 */
package org.fcitx.fcitx5.android.ui.main.modified

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import splitties.dimensions.dp

/**
 * Gives the existing AndroidX Preference hierarchy Material 3 Expressive segmented-list
 * containers without changing preference storage or behavior.
 *
 * AndroidX exposes the adapter hook publicly from PreferenceFragmentCompat, but keeps the only
 * adapter capable of binding a PreferenceGroup restricted to the library group. Keep the
 * dependency isolated in this class so a future AndroidX change has one migration point.
 */
@SuppressLint("RestrictedApi")
internal class ExpressivePreferenceGroupAdapter(group: PreferenceGroup) :
    PreferenceGroupAdapter(group) {

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val itemView = holder.itemView
        if (getItem(position) is PreferenceCategory) {
            itemView.background = null
            itemView.layoutParams = itemView.layoutParams.withMargins(
                horizontal = 0,
                vertical = 0
            )
            return
        }

        val previousStartsSection =
            position == 0 || getItem(position - 1) is PreferenceCategory
        val nextEndsSection =
            position == itemCount - 1 || getItem(position + 1) is PreferenceCategory
        val shapeStyle = when {
            previousStartsSection && nextEndsSection ->
                com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Single
            previousStartsSection ->
                com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_First
            nextEndsSection ->
                com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Last
            else ->
                com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Middle
        }
        val shape = ShapeAppearanceModel.builder(itemView.context, shapeStyle, 0).build()
        val container = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(
                MaterialColors.getColor(
                    itemView,
                    com.google.android.material.R.attr.colorSurfaceContainer
                )
            )
        }
        val mask = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(Color.WHITE)
        }
        val onSurface = MaterialColors.getColor(
            itemView,
            com.google.android.material.R.attr.colorOnSurface
        )
        val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 0x1f))
        itemView.background = RippleDrawable(ripple, container, mask)
        itemView.layoutParams = itemView.layoutParams.withMargins(
            horizontal = itemView.context.dp(16),
            vertical = itemView.context.dp(1)
        )
    }

    private fun ViewGroup.LayoutParams?.withMargins(
        horizontal: Int,
        vertical: Int
    ): RecyclerView.LayoutParams {
        val params = (this as? RecyclerView.LayoutParams)
            ?: RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        params.setMargins(horizontal, vertical, horizontal, vertical)
        return params
    }
}
