/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import java.io.Closeable

internal object HandwritingBackendHandover {

    fun complete(
        replacement: Closeable,
        previous: Closeable?,
        onComplete: (Boolean) -> Unit,
    ) {
        val accepted = previous != null
        try {
            (previous ?: replacement).close()
        } finally {
            onComplete(accepted)
        }
    }
}
