/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

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

internal const val MAX_DATA_DESCRIPTOR_BYTES = 1024 * 1024
internal const val MAX_STORED_DATA_DESCRIPTOR_BYTES = 8 * 1024 * 1024
internal const val MAX_DATA_DESCRIPTOR_ENTRIES = 4096
internal const val MAX_DATA_HIERARCHY_CONTENT_BYTES = 1024 * 1024
internal const val MAX_DATA_DESCRIPTOR_HASH_BYTES = 128
internal const val MAX_DATA_DESCRIPTOR_PATH_LENGTH = 1024
internal const val MAX_DATA_DESCRIPTOR_PATH_BYTES = 1024
internal const val MAX_DATA_DESCRIPTOR_PATH_SEGMENTS = 64
internal const val MAX_DATA_DESCRIPTOR_PATH_SEGMENT_BYTES = 255

internal sealed class InvalidDataDescriptor(message: String) : IllegalArgumentException(message)

internal class DataDescriptorLimitExceeded(val resource: String, val limit: Int) :
    InvalidDataDescriptor("Data descriptor $resource exceeds limit of $limit")

internal class UnsafeDataDescriptorPath(val path: String) :
    InvalidDataDescriptor("Unsafe data descriptor path: $path")

internal fun InputStream.readBoundedDataDescriptorText(
    maxBytes: Int = MAX_DATA_DESCRIPTOR_BYTES,
): String {
    require(maxBytes in 0 until Int.MAX_VALUE)
    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes + 1))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (output.size() <= maxBytes) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        when {
            count < 0 -> break
            count > 0 -> output.write(buffer, 0, count)
            else -> {
                val next = read()
                if (next < 0) break
                output.write(next)
            }
        }
    }
    if (output.size() > maxBytes) {
        throw DataDescriptorLimitExceeded("encoded size", maxBytes)
    }
    return output.toByteArray().decodeToString(throwOnInvalidSequence = true)
}

private fun normalizeManagedDataPath(path: String): String {
    if (path.length > MAX_DATA_DESCRIPTOR_PATH_LENGTH ||
        path.encodeToByteArray().size > MAX_DATA_DESCRIPTOR_PATH_BYTES
    ) {
        throw DataDescriptorLimitExceeded("path length", MAX_DATA_DESCRIPTOR_PATH_LENGTH)
    }
    val normalized = path.replace('\\', '/')
    val segments = normalized.split('/')
    if (segments.size > MAX_DATA_DESCRIPTOR_PATH_SEGMENTS) {
        throw DataDescriptorLimitExceeded(
            "path segment count",
            MAX_DATA_DESCRIPTOR_PATH_SEGMENTS,
        )
    }
    if (segments.any {
            it.length > MAX_DATA_DESCRIPTOR_PATH_SEGMENT_BYTES ||
                it.encodeToByteArray().size > MAX_DATA_DESCRIPTOR_PATH_SEGMENT_BYTES
        }
    ) {
        throw DataDescriptorLimitExceeded(
            "path segment length",
            MAX_DATA_DESCRIPTOR_PATH_SEGMENT_BYTES,
        )
    }
    if (normalized.startsWith('/') ||
        segments.any {
            it.isEmpty() || it == "." || it == ".." || it.any { char -> char.code < 0x20 }
        }
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
    if (files.size.toLong() + symlinks.size.toLong() > MAX_DATA_DESCRIPTOR_ENTRIES) {
        throw DataDescriptorLimitExceeded("entry count", MAX_DATA_DESCRIPTOR_ENTRIES)
    }
    sequenceOf(sha256).plus(files.values.asSequence()).forEach { hash ->
        if (hash.length > MAX_DATA_DESCRIPTOR_HASH_BYTES ||
            hash.encodeToByteArray().size > MAX_DATA_DESCRIPTOR_HASH_BYTES
        ) {
            throw DataDescriptorLimitExceeded("hash length", MAX_DATA_DESCRIPTOR_HASH_BYTES)
        }
    }
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
        if (target in normalizedFiles ||
            parentDataPaths(target).any { normalizedFiles[it]?.isNotEmpty() == true } ||
            parentDataPaths(target).any { it in normalizedSymlinks } ||
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
