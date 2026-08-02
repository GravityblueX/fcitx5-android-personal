/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityFlagsTest {

    @Test
    fun visiblePasswordFieldsAreSensitive() {
        val flags = CapabilityFlags.fromEditorInfo(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
        )

        assertTrue(flags.has(CapabilityFlag.Sensitive))
        assertTrue(flags.hasAny(CapabilityFlag.Password, CapabilityFlag.Sensitive))
        assertFalse(flags.has(CapabilityFlag.Password))
    }

    @Test
    fun noPersonalizedLearningEditorsAreSensitive() {
        val flags = CapabilityFlags.fromEditorInfo(
            EditorInfo().apply {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
        )

        assertTrue(flags.has(CapabilityFlag.Sensitive))
        assertTrue(flags.hasAny(CapabilityFlag.Password, CapabilityFlag.Sensitive))
    }

    @Test
    fun regularTextFieldsAreNotSensitive() {
        val flags = CapabilityFlags.fromEditorInfo(
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        )

        assertFalse(flags.hasAny(CapabilityFlag.Password, CapabilityFlag.Sensitive))
    }
}
