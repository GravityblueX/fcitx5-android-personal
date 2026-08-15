/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

internal fun enumEntryLabels(
    entries: List<String>,
    entriesI18n: List<String>?,
): List<String> = entries.mapIndexed { index, entry -> entriesI18n?.getOrNull(index) ?: entry }

internal fun enumListEntryLabel(
    value: String,
    entries: List<String>,
    entriesI18n: List<String>?,
): String = entriesI18n?.getOrNull(entries.indexOf(value)) ?: value
