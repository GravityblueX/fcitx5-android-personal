/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import kotlinx.serialization.Serializable
import java.io.File

typealias SHA256 = String

/**
 * A list of files with sha256
 */
@Serializable
data class DataDescriptor(
    /**
     * Implementation dependent, will be used to quick check if two descriptors are the same
     */
    val sha256: SHA256,
    /**
     * path -> sha256
     * sha256 will be empty if the path is a directory
     */
    val files: Map<String, SHA256>,
    /**
     * Symbolic links from target -> source
     */
    val symlinks: Map<String, String> = mapOf()
)

internal class UnsafeDataDescriptorPath(val path: String) :
    IllegalArgumentException("Unsafe data descriptor path: $path")

private fun normalizeManagedDataPath(path: String): String {
    val normalized = path.replace('\\', '/')
    val segments = normalized.split('/')
    if (normalized.startsWith('/') ||
        segments.any { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }
    ) {
        throw UnsafeDataDescriptorPath(path)
    }
    val managed = segments.first() == "usr" || normalized == "README.md"
    if (!managed) throw UnsafeDataDescriptorPath(path)
    return normalized
}

private fun Map<String, String>.normalizeDataDescriptorPaths(): Map<String, String> {
    val result = linkedMapOf<String, String>()
    forEach { (path, value) ->
        val normalized = normalizeManagedDataPath(path)
        val existing = result[normalized]
        if (existing != null && existing != value) {
            throw UnsafeDataDescriptorPath(path)
        }
        result[normalized] = value
    }
    return result
}

internal fun parentDataPaths(path: String) = sequence {
    var separator = path.lastIndexOf('/')
    while (separator > 0) {
        val parent = path.substring(0, separator)
        yield(parent)
        separator = parent.lastIndexOf('/')
    }
}

internal fun DataDescriptor.withValidatedManagedPaths(): DataDescriptor {
    val normalizedFiles = files.normalizeDataDescriptorPaths()
    val normalizedSymlinks = linkedMapOf<String, String>()
    symlinks.forEach { (target, source) ->
        val normalizedTarget = normalizeManagedDataPath(target)
        val normalizedSource = normalizeManagedDataPath(source)
        val existing = normalizedSymlinks[normalizedTarget]
        if (existing != null && existing != normalizedSource) {
            throw UnsafeDataDescriptorPath(target)
        }
        normalizedSymlinks[normalizedTarget] = normalizedSource
    }
    normalizedFiles.forEach { (path, _) ->
        if (path in normalizedSymlinks ||
            parentDataPaths(path).any { it in normalizedSymlinks }
        ) {
            throw UnsafeDataDescriptorPath(path)
        }
        parentDataPaths(path).firstOrNull { normalizedFiles[it]?.isNotEmpty() == true }
            ?.let { throw UnsafeDataDescriptorPath(path) }
    }
    normalizedSymlinks.forEach { (target, source) ->
        val descendantPrefix = "$target/"
        if (target in normalizedFiles ||
            normalizedFiles.keys.any { it.startsWith(descendantPrefix) } ||
            parentDataPaths(target).any { normalizedFiles[it]?.isNotEmpty() == true } ||
            parentDataPaths(target).any { it in normalizedSymlinks } ||
            normalizedSymlinks.keys.any { it != target && it.startsWith(descendantPrefix) } ||
            target.startsWith("$source/") ||
            sequenceOf(source).plus(parentDataPaths(source)).any { it in normalizedSymlinks }
        ) {
            throw UnsafeDataDescriptorPath(target)
        }
    }
    return copy(
        files = normalizedFiles,
        symlinks = normalizedSymlinks,
    )
}

internal fun resolveManagedDataPath(
    root: File,
    path: String,
    canonicalize: (File) -> File = { it.canonicalFile },
): File {
    val normalizedPath = normalizeManagedDataPath(path)
    val canonicalRoot = canonicalize(root)
    val requested = canonicalRoot.resolve(normalizedPath).absoluteFile.normalize()
    val parent = requested.parentFile ?: throw UnsafeDataDescriptorPath(path)
    if (canonicalize(parent) != parent.absoluteFile.normalize()) {
        throw UnsafeDataDescriptorPath(path)
    }
    return requested
}

internal fun resolveManagedDataSource(
    root: File,
    path: String,
    canonicalize: (File) -> File = { it.canonicalFile },
): File {
    val source = resolveManagedDataPath(root, path, canonicalize)
    if (canonicalize(source) != source.absoluteFile.normalize()) {
        throw UnsafeDataDescriptorPath(path)
    }
    return source
}
