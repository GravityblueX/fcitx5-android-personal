/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

@PublishedApi
internal fun Throwable.addSuppressedFailures(results: Iterable<Result<Unit>>) {
    results.mapNotNull(Result<Unit>::exceptionOrNull)
        .filterNot { it === this }
        .forEach(::addSuppressed)
}

@PublishedApi
internal inline fun <T> runWithCleanup(
    cleanup: () -> Result<Unit>,
    onCleanupFailure: (Throwable) -> Unit,
    block: () -> T,
): T {
    var primaryFailure: Throwable? = null
    try {
        return block()
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        val cleanupResult = try {
            cleanup()
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        val primary = primaryFailure
        if (primary == null) {
            cleanupResult.onFailure(onCleanupFailure)
        } else {
            primary.addSuppressedFailures(listOf(cleanupResult))
        }
    }
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
