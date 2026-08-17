/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import android.util.Base64
import java.security.MessageDigest

/**
 * Merge [DataDescriptor]s
 *
 * It records files' sources, i.e. what [DataDescriptor] they belong to
 */
class DataHierarchy {
    private val files = mutableMapOf<String, Pair<SHA256, FileSource>>()
    private val descriptorSHA256 = mutableSetOf<SHA256>()
    private val symlinks = mutableMapOf<String, Pair<String, FileSource>>()
    private var contentBytes = 0L

    data class PathConflict(val path: String, val src: FileSource) : Exception()
    data class SymlinkConflict(val path: String, val src: FileSource) : Exception()

    /**
     * Merge a [DataDescriptor]
     *
     * @throws PathConflict if a non-directory path already exists in the hierarchy
     * @throws SymlinkConflict if a file or directory already exists when creating symlink
     */
    fun install(rawDescriptor: DataDescriptor, src: FileSource) {
        val descriptor = rawDescriptor.withValidatedManagedPaths()
        val newFiles = descriptor.files.mapValues { (path, sha256) ->
            files[path]?.also { old ->
                // path conflict when at least one of them is not a directory (empty sha256)
                if (old.first.isNotEmpty() || sha256.isNotEmpty()) {
                    throw PathConflict(path, old.second)
                }
            }
            parentDataPaths(path).forEach { parent ->
                files[parent]?.takeIf { it.first.isNotEmpty() }?.let { old ->
                    throw PathConflict(path, old.second)
                }
                descriptor.files[parent]?.takeIf { it.isNotEmpty() }?.let {
                    throw PathConflict(path, src)
                }
                symlinks[parent]?.let { old ->
                    throw PathConflict(path, old.second)
                }
                descriptor.symlinks[parent]?.let {
                    throw PathConflict(path, src)
                }
            }
            symlinks[path]?.let { old ->
                throw PathConflict(path, old.second)
            }
            Pair(sha256, src)
        }
        val newSymlinks = descriptor.symlinks.mapValues { (path, source) ->
            // path we try to create is already a file or directory in our hierarchy
            (files[path] ?: newFiles[path])?.let { (_, existingSrc) ->
                throw SymlinkConflict(path, existingSrc)
            }
            val descendantPrefix = "$path/"
            files.entries.firstOrNull { it.key.startsWith(descendantPrefix) }
                ?.let { (_, value) -> throw SymlinkConflict(path, value.second) }
            newFiles.entries.firstOrNull { it.key.startsWith(descendantPrefix) }
                ?.let { (_, value) -> throw SymlinkConflict(path, value.second) }
            parentDataPaths(path).forEach { parent ->
                files[parent]?.takeIf { it.first.isNotEmpty() }?.let { old ->
                    throw SymlinkConflict(path, old.second)
                }
                newFiles[parent]?.takeIf { it.first.isNotEmpty() }?.let { old ->
                    throw SymlinkConflict(path, old.second)
                }
                symlinks[parent]?.let { old ->
                    throw SymlinkConflict(path, old.second)
                }
                descriptor.symlinks[parent]?.let {
                    throw SymlinkConflict(path, src)
                }
            }
            // path we try to create is already a symlink in our hierarchy
            // but it refers to a different path
            symlinks[path]?.let { (existedSource, existingSrc) ->
                if (source != existedSource)
                    throw PathConflict(path, existingSrc)
            }
            symlinks.entries.firstOrNull { it.key.startsWith(descendantPrefix) }
                ?.let { (_, value) -> throw SymlinkConflict(path, value.second) }
            descriptor.symlinks.keys.firstOrNull {
                it != path && it.startsWith(descendantPrefix)
            }?.let { throw SymlinkConflict(path, src) }
            sequenceOf(source).plus(parentDataPaths(source)).forEach { sourcePath ->
                if (sourcePath == path ||
                    sourcePath in symlinks ||
                    sourcePath in descriptor.symlinks
                ) {
                    throw SymlinkConflict(path, src)
                }
            }
            Pair(source, src)
        }
        val mergedEntryCount = files.size + newFiles.keys.count { it !in files } +
            symlinks.size + newSymlinks.keys.count { it !in symlinks }
        if (mergedEntryCount > MAX_DATA_DESCRIPTOR_ENTRIES) {
            throw DataDescriptorLimitExceeded("merged entry count", MAX_DATA_DESCRIPTOR_ENTRIES)
        }
        val replacedFileBytes = newFiles.keys.sumOf { path ->
            files[path]?.let { (hash, _) -> path.contentBytes() + hash.contentBytes() } ?: 0L
        }
        val replacedSymlinkBytes = newSymlinks.keys.sumOf { path ->
            symlinks[path]?.let { (source, _) -> path.contentBytes() + source.contentBytes() } ?: 0L
        }
        val addedFileBytes = newFiles.entries.sumOf { (path, value) ->
            path.contentBytes() + value.first.contentBytes()
        }
        val addedSymlinkBytes = newSymlinks.entries.sumOf { (path, value) ->
            path.contentBytes() + value.first.contentBytes()
        }
        val addedDescriptorBytes = if (descriptor.sha256 in descriptorSHA256) {
            0L
        } else {
            descriptor.sha256.contentBytes()
        }
        val mergedContentBytes = contentBytes - replacedFileBytes - replacedSymlinkBytes +
            addedFileBytes + addedSymlinkBytes + addedDescriptorBytes
        if (mergedContentBytes > MAX_DATA_HIERARCHY_CONTENT_BYTES) {
            throw DataDescriptorLimitExceeded(
                "merged content size",
                MAX_DATA_HIERARCHY_CONTENT_BYTES,
            )
        }
        files.putAll(newFiles)
        symlinks.putAll(newSymlinks)
        descriptorSHA256.add(descriptor.sha256)
        contentBytes = mergedContentBytes
    }

    /**
     * Create a [DataDescriptor] from the file list, discarding other information
     */
    fun downToDataDescriptor() =
        DataDescriptor(
            sha256(this),
            files.mapValues { it.value.first },
            symlinks.mapValues { it.value.first })

    companion object {
        private fun String.contentBytes() = encodeToByteArray().size.toLong()

        private val digest by lazy { MessageDigest.getInstance("SHA-256") }

        /**
         * Calculate checksum according to merged descriptors
         *
         * Note: This is different from sha256 calculated by gradle task,
         * in which the it is the hash string of file list itself
         */
        private fun sha256(h: DataHierarchy): String =
            digest.digest(h.descriptorSHA256.joinToString(separator = "").encodeToByteArray())
                .let {
                    Base64.encodeToString(it, 0).trim()
                }

        /**
         * Compute the difference between a [DataDescriptor] and [DataHierarchy],
         * generating [FileAction]s to migrate from the [old] to [new]
         */
        fun diff(old: DataDescriptor, new: DataHierarchy): List<FileAction> {
            val normalizedOld = old.withValidatedManagedPaths()
            if (normalizedOld.sha256 == sha256(new))
                return emptyList()
            val diffFiles = new.files.mapNotNull { (path, v) ->
                val (sha256, src) = v
                when {
                    path !in normalizedOld.files && sha256.isNotBlank() ->
                        FileAction.CreateFile(path, src)
                    normalizedOld.files[path] != sha256 ->
                        if (sha256.isNotBlank())
                            FileAction.UpdateFile(path, src)
                        else null
                    else -> null
                }
            }.toMutableList<FileAction>().apply {
                addAll(normalizedOld.files.filterKeys { it !in new.files }
                    .map { (path, sha256) ->
                        if (sha256.isNotBlank())
                            FileAction.DeleteFile(path)
                        else
                            FileAction.DeleteDir(path)
                    })
            }
            val diffLinks = new.symlinks.mapNotNull { (target, v) ->
                val (source, _) = v
                if (normalizedOld.symlinks[target] == source)
                // old link will be overwritten
                    null
                else
                    FileAction.CreateSymlink(target, source)
            }.toMutableList<FileAction>().apply {
                addAll(
                    normalizedOld.symlinks
                        .filterKeys { it !in new.symlinks }
                        .map { (target, _) -> FileAction.DeleteFile(target) }
                )
            }
            return diffFiles + diffLinks
        }
    }
}
