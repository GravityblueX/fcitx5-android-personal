/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class SequentialSaveRunner<T>(
    private val scope: CoroutineScope,
    private val save: suspend (T) -> Unit,
    private val onFailure: (Throwable) -> Unit = {}
) {

    private var tail: Job? = null
    private var latestResult = Result.success(Unit)

    fun submit(value: T) {
        val previous = tail
        tail = scope.launch {
            previous?.join()
            val result = try {
                save(value)
                Result.success(Unit)
            } catch (exception: CancellationException) {
                latestResult = Result.failure(exception)
                throw exception
            } catch (exception: Exception) {
                onFailure(exception)
                Result.failure(exception)
            }
            latestResult = result
        }
    }

    suspend fun awaitIdle(): Result<Unit> {
        while (true) {
            val job = tail ?: return latestResult
            job.join()
            if (job === tail) return latestResult
        }
    }
}
