/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.locks.ReentrantLock

class DataManagerCallbackTest {

    @Test
    fun dispatchesCallbacksAfterReleasingLock() {
        val lock = ReentrantLock()
        var callbackRan = false

        runLockedThenDispatchCallbacks(
            lock,
            lockedBlock = {
                assertTrue(lock.isHeldByCurrentThread)
                listOf {
                    assertFalse(lock.isHeldByCurrentThread)
                    callbackRan = true
                }
            },
            onCallbackFailure = { throw AssertionError(it) },
        )

        assertTrue(callbackRan)
    }

    @Test
    fun isolatesCallbackFailures() {
        val lock = ReentrantLock()
        val events = mutableListOf<String>()
        val failures = mutableListOf<Exception>()

        runLockedThenDispatchCallbacks(
            lock,
            lockedBlock = {
                listOf(
                    {
                        events.add("first")
                        error("broken callback")
                    },
                    { events.add("second") },
                )
            },
            onCallbackFailure = failures::add,
        )

        assertEquals(listOf("first", "second"), events)
        assertEquals(1, failures.size)
        assertEquals("broken callback", failures.single().message)
    }
}
