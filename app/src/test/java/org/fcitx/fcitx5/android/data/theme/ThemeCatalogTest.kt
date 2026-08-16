/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ThemeCatalogTest {

    private data class CatalogTheme(val name: String, val revision: Int = 0)

    @Test
    fun serializesThemeManagerMutations() {
        val workerCount = 8
        val barrier = CyclicBarrier(workerCount)
        val activeOperations = AtomicInteger()
        val maximumActiveOperations = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val operations = List(workerCount) {
                Callable {
                    barrier.await(5, TimeUnit.SECONDS)
                    runThemeManagerMutation {
                        val active = activeOperations.incrementAndGet()
                        try {
                            maximumActiveOperations.updateAndGet { maximum ->
                                maxOf(maximum, active)
                            }
                            Thread.sleep(25)
                        } finally {
                            activeOperations.decrementAndGet()
                        }
                    }
                }
            }

            executor.invokeAll(operations).forEach { future ->
                future.get(5, TimeUnit.SECONDS)
            }

            assertEquals(1, maximumActiveOperations.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun exposesOnlyCompleteSnapshotsDuringReplacement() {
        val first = List(64) { CatalogTheme("first-$it") }
        val second = List(96) { CatalogTheme("second-$it") }
        val expectedSnapshots = setOf(first, second)
        val catalog = ThemeCatalog(first, CatalogTheme::name)
        val workerCount = 8
        val barrier = CyclicBarrier(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val operations = List(workerCount) { worker ->
                Callable {
                    barrier.await(5, TimeUnit.SECONDS)
                    repeat(2_000) { iteration ->
                        if (worker == 0) {
                            catalog.replaceAll(if (iteration % 2 == 0) second else first)
                        } else {
                            assertTrue(catalog.snapshot() in expectedSnapshots)
                        }
                    }
                }
            }

            executor.invokeAll(operations).forEach { future ->
                future.get(10, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentUpsertsKeepOneThemePerName() {
        val workerCount = 8
        val barrier = CyclicBarrier(workerCount)
        val catalog = ThemeCatalog<CatalogTheme>(emptyList(), CatalogTheme::name)
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val operations = List(workerCount) { worker ->
                Callable {
                    barrier.await(5, TimeUnit.SECONDS)
                    repeat(500) { revision ->
                        catalog.upsert(CatalogTheme("shared", worker * 500 + revision))
                    }
                }
            }

            executor.invokeAll(operations).forEach { future ->
                future.get(10, TimeUnit.SECONDS)
            }

            assertEquals(1, catalog.snapshot().count { it.name == "shared" })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun snapshotsAndReplacementInputsCannotMutateCatalogStorage() {
        val original = mutableListOf(CatalogTheme("first"), CatalogTheme("second"))
        val supplemental = mutableListOf(CatalogTheme("supplemental"))
        val catalog = ThemeCatalog(original, CatalogTheme::name, supplemental)

        original.clear()
        supplemental.clear()
        val snapshot = catalog.snapshot()
        @Suppress("UNCHECKED_CAST")
        (snapshot as MutableList<CatalogTheme>).clear()

        assertEquals(
            listOf("first", "second", "supplemental"),
            catalog.snapshot().map(CatalogTheme::name),
        )
    }

    @Test
    fun replacesSupplementalThemesWithoutChangingManagedThemes() {
        val catalog = ThemeCatalog(
            listOf(CatalogTheme("custom")),
            CatalogTheme::name,
            listOf(CatalogTheme("old-supplemental")),
        )

        catalog.replaceSupplemental(listOf(CatalogTheme("new-supplemental")))

        assertEquals(
            listOf("custom", "new-supplemental"),
            catalog.snapshot().map(CatalogTheme::name),
        )
    }

    @Test
    fun removesThemeByName() {
        val removed = CatalogTheme("removed")
        val retained = CatalogTheme("retained")
        val catalog = ThemeCatalog(listOf(removed, retained), CatalogTheme::name)

        assertEquals(removed, catalog.remove("removed"))
        assertNull(catalog.find("removed"))
        assertEquals(listOf(retained), catalog.snapshot())
    }
}
