/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class DocumentStreamUnavailableException : IOException()

fun ContentResolver.requireInputStream(uri: Uri): InputStream =
    openInputStream(uri) ?: throw DocumentStreamUnavailableException()

fun ContentResolver.requireOutputStream(uri: Uri): OutputStream =
    openOutputStream(uri) ?: throw DocumentStreamUnavailableException()
