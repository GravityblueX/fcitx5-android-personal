/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

internal fun <T> applyFileBackedRemovals(
    indexedItems: List<Pair<Int, T>>,
    remove: (T) -> Result<Unit>,
    onRemoved: (T) -> Unit,
    restore: (Int, T) -> Unit,
): Throwable? {
    var successfulRemovals = 0
    var firstFailure: Throwable? = null
    indexedItems.sortedBy { it.first }.forEach { (originalIndex, item) ->
        runCatching { remove(item).getOrThrow() }.fold(
            onSuccess = {
                onRemoved(item)
                successfulRemovals++
            },
            onFailure = { failure ->
                if (firstFailure == null) firstFailure = failure
                restore(originalIndex - successfulRemovals, item)
            },
        )
    }
    return firstFailure
}
