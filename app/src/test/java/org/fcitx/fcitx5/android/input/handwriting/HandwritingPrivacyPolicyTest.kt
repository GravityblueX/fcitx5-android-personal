/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingPrivacyPolicyTest {

    @Test
    fun allowsRegularTextFields() {
        assertTrue(HandwritingPrivacyPolicy.canRecognize(CapabilityFlags()))
    }

    @Test
    fun blocksPasswordFields() {
        assertFalse(HandwritingPrivacyPolicy.canRecognize(CapabilityFlags(CapabilityFlag.Password)))
    }

    @Test
    fun blocksSensitiveFields() {
        assertFalse(HandwritingPrivacyPolicy.canRecognize(CapabilityFlags(CapabilityFlag.Sensitive)))
    }
}
