/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

internal fun Throwable.addSuppressedFailures(results: Iterable<Result<Unit>>) {
    results.mapNotNull(Result<Unit>::exceptionOrNull)
        .filterNot { it === this }
        .forEach(::addSuppressed)
}

internal inline fun <T> runWithRollback(
    rollback: () -> Iterable<Result<Unit>>,
    block: () -> T,
): T = try {
    block()
} catch (primary: Throwable) {
    val rollbackResults = runCatching(rollback)
        .getOrElse { listOf(Result.failure(it)) }
    primary.addSuppressedFailures(rollbackResults)
    throw primary
}
