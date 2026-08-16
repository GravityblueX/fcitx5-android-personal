/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

private val themeManagerMutationLock = ReentrantLock()

internal fun <T> runThemeManagerMutation(block: () -> T): T =
    themeManagerMutationLock.withLock(block)

internal class ThemeCatalog<T>(
    initialThemes: List<T>,
    private val nameOf: (T) -> String,
    initialSupplementalThemes: List<T> = emptyList(),
) {
    private val lock = ReentrantReadWriteLock()
    private var themes = initialThemes.toList()
    private var supplementalThemes = initialSupplementalThemes.toList()

    fun find(name: String): T? = lock.read {
        themes.find { nameOf(it) == name }
    }

    fun snapshot(): List<T> = lock.read {
        ArrayList<T>(themes.size + supplementalThemes.size).apply {
            addAll(themes)
            addAll(supplementalThemes)
        }
    }

    fun replaceAll(replacement: List<T>) {
        val replacementSnapshot = replacement.toList()
        lock.write {
            themes = replacementSnapshot
        }
    }

    fun replaceSupplemental(replacement: List<T>) {
        val replacementSnapshot = replacement.toList()
        lock.write {
            supplementalThemes = replacementSnapshot
        }
    }

    fun upsert(theme: T) = lock.write {
        val updatedThemes = themes.toMutableList()
        val existingIndex = updatedThemes.indexOfFirst { nameOf(it) == nameOf(theme) }
        if (existingIndex >= 0) {
            updatedThemes[existingIndex] = theme
        } else {
            updatedThemes.add(0, theme)
        }
        themes = updatedThemes
    }

    fun remove(name: String): T? = lock.write {
        val existingIndex = themes.indexOfFirst { nameOf(it) == name }
        if (existingIndex < 0) return@write null
        val updatedThemes = themes.toMutableList()
        val removed = updatedThemes.removeAt(existingIndex)
        themes = updatedThemes
        removed
    }
}
