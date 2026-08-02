/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags

internal object HandwritingPrivacyPolicy {
    fun canRecognize(capabilityFlags: CapabilityFlags): Boolean =
        !capabilityFlags.hasAny(CapabilityFlag.Password, CapabilityFlag.Sensitive)
}
