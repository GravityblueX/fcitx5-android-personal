/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editorinfo

import android.content.Context
import android.util.TypedValue
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapInHorizontalScrollView
import splitties.views.dsl.core.wrapInScrollView
import kotlin.math.roundToInt

class EditorInfoUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val cells = mutableListOf<Pair<TextView, Float>>()
    private var contentScale = 1f

    private fun createTextView(str: String) = textView {
        text = str
        val padding = (ctx.dp(3) * contentScale).roundToInt()
        setPadding(padding, padding, padding, padding)
        setTextColor(theme.keyTextColor)
        val baseTextSize = textSize
        setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSize * contentScale)
    }.also { cells.add(it to (it.textSize / contentScale)) }

    private fun TableLayout.addRow(label: String, value: String) {
        addView(view(::TableRow) {
            addView(createTextView(label))
            addView(createTextView(value))
        })
    }

    val table = view(::TableLayout) {
        isStretchAllColumns = true
    }

    override val root = table
        .wrapInHorizontalScrollView()
        .wrapInScrollView { isFillViewport = true }

    fun setValues(values: Map<String, String>) {
        table.apply {
            removeAllViews()
            cells.clear()
            values.forEach { (k, v) ->
                addRow(k, v)
            }
        }
    }

    fun setContentScale(scale: Float) {
        contentScale = scale.coerceIn(0f, 1f)
        val padding = (ctx.dp(3) * contentScale).roundToInt()
        cells.forEach { (cell, baseTextSize) ->
            cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSize * contentScale)
            cell.setPadding(padding, padding, padding, padding)
        }
    }
}
