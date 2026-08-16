/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SequentialSaveRunnerTest {

    @Test
    fun savesSnapshotsInSubmissionOrder() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val runner = SequentialSaveRunner<Int>(scope, save = { value ->
            started += value
            if (value == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        })

        runner.submit(1)
        firstStarted.await()
        runner.submit(2)
        yield()

        assertEquals(listOf(1), started)
        releaseFirst.complete(Unit)
        assertTrue(runner.awaitIdle().isSuccess)
        assertEquals(listOf(1, 2), started)
        scope.cancel()
    }

    @Test
    fun reportsLatestSaveFailure() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failure = IllegalStateException("save failed")
        val observedFailures = mutableListOf<Throwable>()
        val runner = SequentialSaveRunner<Int>(
            scope,
            save = { throw failure },
            onFailure = observedFailures::add
        )

        runner.submit(1)
        val result = runner.awaitIdle()

        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf(failure), observedFailures)
        scope.cancel()
    }

    @Test
    fun laterSuccessClearsEarlierFailure() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failure = IllegalStateException("save failed")
        val runner = SequentialSaveRunner<Int>(scope, save = { value ->
            if (value == 1) throw failure
        })

        runner.submit(1)
        assertSame(failure, runner.awaitIdle().exceptionOrNull())
        runner.submit(2)

        assertTrue(runner.awaitIdle().isSuccess)
        scope.cancel()
    }

    @Test
    fun cancellationIsNotReportedAsSaveFailure() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val cancellation = CancellationException("cancelled")
        val observedFailures = mutableListOf<Throwable>()
        val runner = SequentialSaveRunner<Int>(
            scope,
            save = { throw cancellation },
            onFailure = observedFailures::add
        )

        runner.submit(1)
        val result = runner.awaitIdle()

        assertSame(cancellation, result.exceptionOrNull())
        assertFalse(observedFailures.isNotEmpty())
        scope.cancel()
    }
}
