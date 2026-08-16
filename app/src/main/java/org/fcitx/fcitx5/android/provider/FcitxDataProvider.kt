/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.provider

import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import org.fcitx.fcitx5.android.utils.FileUtil
import org.fcitx.fcitx5.android.utils.moveToWithoutReplacing
import org.fcitx.fcitx5.android.utils.safeFileName
import org.fcitx.fcitx5.android.R
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

private const val DOCUMENT_COPY_STAGING_PREFIX = ".document-copy-"
private const val DOCUMENT_DELETE_STAGING_PREFIX = ".document-delete-"
private const val DOCUMENT_STAGING_SUFFIX = ".staged"

internal fun isSameOrDescendant(file: File, directory: File): Boolean =
    file == directory || file.path.startsWith("${directory.path}${File.separator}")

internal fun isUnredirectedPath(file: File): Boolean = runCatching {
    file.canonicalFile == file.absoluteFile.normalize()
}.getOrDefault(false)

internal fun reserveDocumentDestination(destination: File, isDirectory: Boolean): Boolean =
    if (isDirectory) destination.mkdir() else destination.createNewFile()

internal fun claimDocumentDestination(
    parent: File,
    displayName: String,
    isDirectory: Boolean,
    claim: (File) -> Boolean,
): File {
    require(parent.isDirectory) { "Document parent is not a directory: ${parent.path}" }
    val safeName = safeDocumentDisplayName(displayName)
    var conflictId = 1
    while (true) {
        val destinationName = if (conflictId == 1) {
            safeName
        } else {
            documentNameWithConflictSuffix(safeName, conflictId, isDirectory)
        }
        val destination = parent.resolve(destinationName)
        if (claim(destination)) return destination
        if (!destination.exists()) {
            throw IOException("Cannot claim document destination: ${destination.path}")
        }
        if (conflictId == Int.MAX_VALUE) {
            throw IOException("Cannot find available document destination")
        }
        conflictId += 1
    }
}

internal fun isDocumentStagingFileName(fileName: String): Boolean {
    val prefix = when {
        fileName.startsWith(DOCUMENT_COPY_STAGING_PREFIX) -> DOCUMENT_COPY_STAGING_PREFIX
        fileName.startsWith(DOCUMENT_DELETE_STAGING_PREFIX) -> DOCUMENT_DELETE_STAGING_PREFIX
        else -> return false
    }
    if (!fileName.endsWith(DOCUMENT_STAGING_SUFFIX)) return false
    val id = fileName
        .removePrefix(prefix)
        .removeSuffix(DOCUMENT_STAGING_SUFFIX)
    val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return false
    return uuid.toString().equals(id, ignoreCase = true)
}

internal fun safeDocumentDisplayName(displayName: String): String =
    displayName.safeFileName().also { safeName ->
        require(!isDocumentStagingFileName(safeName)) {
            "Document name is reserved for internal staging: $safeName"
        }
    }

internal fun isDocumentStagingPath(file: File, root: File): Boolean {
    var current = file
    while (current != root) {
        if (!isSameOrDescendant(current, root)) return false
        if (isDocumentStagingFileName(current.name)) return true
        current = current.parentFile ?: return false
    }
    return false
}

internal fun createDocumentCopyStaging(parent: File, isDirectory: Boolean): File {
    require(parent.isDirectory) { "Document copy parent is not a directory: ${parent.path}" }
    while (true) {
        val staging = parent.resolve(
            "$DOCUMENT_COPY_STAGING_PREFIX${UUID.randomUUID()}$DOCUMENT_STAGING_SUFFIX"
        )
        if (reserveDocumentDestination(staging, isDirectory)) return staging
        if (!staging.exists()) {
            throw IOException("Cannot create document copy staging: ${staging.path}")
        }
    }
}

internal fun publishDocumentCopy(
    staging: File,
    targetParent: File,
    displayName: String,
    isDirectory: Boolean,
    move: (File, File) -> Boolean = { source, destination ->
        source.moveToWithoutReplacing(destination)
    },
): File = claimDocumentDestination(targetParent, displayName, isDirectory) { destination ->
    move(staging, destination)
}

internal fun copyDocumentAtomically(
    source: File,
    targetParent: File,
    remove: (File) -> Result<Unit> = FileUtil::removeFile,
    copy: (File, File) -> Unit,
): File {
    val isDirectory = source.isDirectory
    val staging = createDocumentCopyStaging(targetParent, isDirectory)
    try {
        copy(source, staging)
        return publishDocumentCopy(staging, targetParent, source.name, isDirectory)
    } catch (failure: Throwable) {
        remove(staging).exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

internal fun stageDocumentDeletion(
    document: File,
    move: (File, File) -> Boolean = { source, destination ->
        source.moveToWithoutReplacing(destination)
    },
): File {
    val parent = document.parentFile
        ?: throw IOException("Document has no parent: ${document.path}")
    require(parent.isDirectory) { "Document parent is not a directory: ${parent.path}" }
    while (true) {
        val staging = parent.resolve(
            "$DOCUMENT_DELETE_STAGING_PREFIX${UUID.randomUUID()}$DOCUMENT_STAGING_SUFFIX"
        )
        if (move(document, staging)) return staging
        if (!staging.exists()) {
            throw IOException("Cannot stage document deletion: ${document.path}")
        }
    }
}

internal fun deleteDocumentAtomically(
    document: File,
    move: (File, File) -> Boolean = { source, destination ->
        source.moveToWithoutReplacing(destination)
    },
    remove: (File) -> Result<Unit> = FileUtil::removeFile,
): Result<Unit> {
    val staging = stageDocumentDeletion(document, move)
    return runCatching { remove(staging).getOrThrow() }
}

internal fun cleanupStagedDocuments(
    directory: File,
    remove: (File) -> Result<Unit> = FileUtil::removeFile,
): List<Result<Unit>> {
    val root = directory.canonicalFile
    val results = mutableListOf<Result<Unit>>()
    fun cleanup(current: File) {
        current.listFiles()?.forEach { child ->
            if (isDocumentStagingFileName(child.name)) {
                results += remove(child)
            } else if (child.isDirectory &&
                isUnredirectedPath(child) &&
                isSameOrDescendant(child.canonicalFile, root)
            ) {
                cleanup(child)
            }
        }
    }
    cleanup(root)
    return results
}

internal fun documentNameWithConflictSuffix(
    displayName: String,
    conflictId: Int,
    isDirectory: Boolean,
): String {
    require(conflictId >= 2)
    val extensionIndex = displayName.lastIndexOf('.')
    val hasExtension = !isDirectory &&
            extensionIndex > 0 &&
            extensionIndex < displayName.lastIndex &&
            displayName.take(extensionIndex).any { it != '.' }
    return if (hasExtension) {
        "${displayName.substring(0, extensionIndex)} ($conflictId)${displayName.substring(extensionIndex)}"
    } else {
        "$displayName ($conflictId)"
    }
}

class FcitxDataProvider : DocumentsProvider() {

    companion object {
        private const val MIME_TYPE_WILDCARD = "*/*"
        private const val MIME_TYPE_TEXT = "text/plain"
        private const val MIME_TYPE_BIN = "application/octet-stream"

        private val TEXT_EXTENSIONS = arrayOf(
            "conf",
            "mb",
            "lua",
            "yml",
            "yaml"
        )

        // path relative to baseDir that should be recognize as text files
        private val TEXT_FILES = arrayOf(
            "config/config",
            "config/profile",
            "data/punctuation/punc.mb.zh_CN",
            "data/punctuation/punc.mb.zh_HK",
            "data/punctuation/punc.mb.zh_TW"
        )

        // The default columns to return information about a root if no specific
        // columns are requested in a query.
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
        )

        // The default columns to return information about a document if no specific
        // columns are requested in a query.
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )

        private const val SEARCH_RESULTS_LIMIT = 50
    }

    private lateinit var baseDir: File
    private lateinit var docIdPrefix: String
    private lateinit var textFilePaths: Array<String>

    private val File.docId
        get() = absolutePath.removePrefix(docIdPrefix)

    private fun isSafeDocumentPath(file: File): Boolean = runCatching {
        val canonical = file.canonicalFile
        canonical == file.absoluteFile.normalize() &&
                isSameOrDescendant(canonical, baseDir) &&
                !isDocumentStagingPath(canonical, baseDir)
    }.getOrDefault(false)

    @Throws(FileNotFoundException::class)
    private fun requireNonRootDocument(file: File, documentId: String) {
        if (file == baseDir) {
            throw FileNotFoundException("documentId=$documentId is the data directory root")
        }
    }

    @Throws(FileNotFoundException::class)
    private fun fileFromDocId(docId: String): File {
        val requested = File(docIdPrefix, docId)
        val file = runCatching { requested.canonicalFile }.getOrElse { failure ->
            throw FileNotFoundException("documentId=$docId cannot be resolved: ${failure.message}")
                .apply { initCause(failure) }
        }
        if (file != requested.absoluteFile.normalize()) {
            throw FileNotFoundException("documentId=$docId redirects through a symbolic link")
        }
        if (!isSameOrDescendant(file, baseDir)) {
            throw FileNotFoundException("documentId=$docId is outside the data directory")
        }
        if (isDocumentStagingPath(file, baseDir)) {
            throw FileNotFoundException("documentId=$docId is an internal staging path")
        }
        return file
    }

    override fun onCreate(): Boolean {
        baseDir = (context!!.getExternalFilesDir(null) ?: return false).canonicalFile
        docIdPrefix = "${baseDir.parent}${File.separator}"
        textFilePaths = Array(TEXT_FILES.size) { baseDir.resolve(TEXT_FILES[it]).absolutePath }
        cleanupStagedDocuments(baseDir).forEach { result ->
            result.onFailure { failure ->
                Timber.w(failure, "Failed to clean staged document operation")
            }
        }
        return true
    }

    override fun queryRoots(projection: Array<String>?) =
        MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, baseDir.docId)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD
                )
                add(Root.COLUMN_ICON, R.mipmap.app_icon)
                add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                add(Root.COLUMN_DOCUMENT_ID, baseDir.docId)
                add(Root.COLUMN_MIME_TYPES, MIME_TYPE_WILDCARD)
            }
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?) =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            newRowFromFile(fileFromDocId(documentId))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ) = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
        fileFromDocId(parentDocumentId).listFiles()
            ?.filter { file ->
                isSafeDocumentPath(file) && !isDocumentStagingFileName(file.name)
            }
            ?.forEach { newRowFromFile(it) }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(
            fileFromDocId(documentId),
            ParcelFileDescriptor.parseMode(mode)
        )
    }

    @Throws(FileNotFoundException::class)
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val file = fileFromDocId(documentId)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    @Throws(FileNotFoundException::class)
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = fileFromDocId(parentDocumentId)
        val isDirectory = mimeType == Document.MIME_TYPE_DIR
        val newFile = try {
            claimDocumentDestination(parent, displayName, isDirectory) { destination ->
                reserveDocumentDestination(destination, isDirectory)
            }
        } catch (e: Exception) {
            throw FileNotFoundException(
                "createDocument parent=$parentDocumentId name=$displayName failed: ${e.message}"
            ).apply { initCause(e) }
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        val file = fileFromDocId(documentId)
        requireNonRootDocument(file, documentId)
        if (!file.exists()) {
            throw FileNotFoundException("deleteDocument id=$documentId failed: file not found")
        }
        val cleanup = try {
            deleteDocumentAtomically(file)
        } catch (e: Exception) {
            throw FileNotFoundException("deleteDocument id=$documentId failed: ${e.message}")
                .apply { initCause(e) }
        }
        cleanup.onFailure { failure ->
            Timber.w(failure, "Failed to clean staged document deletion: $documentId")
        }
    }

    override fun getDocumentType(documentId: String): String {
        return fileFromDocId(documentId).mimeType
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileFromDocId(parentDocumentId)
        val child = fileFromDocId(documentId)
        return child.path.startsWith("${parent.path}${File.separator}")
    }

    private fun copyIntoReservedDestination(source: File, destination: File) {
        if (!isSafeDocumentPath(source)) {
            throw IOException("Source path is unsafe: ${source.path}")
        }
        if (!source.isDirectory) {
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        val children = source.listFiles()
            ?: throw IOException("Cannot list source directory: ${source.path}")
        children
            .filter { child ->
                isSafeDocumentPath(child) && !isDocumentStagingFileName(child.name)
            }
            .forEach { child ->
                val childDestination = destination.resolve(child.name)
                if (!reserveDocumentDestination(childDestination, child.isDirectory)) {
                    throw IOException("Cannot reserve copy destination: ${childDestination.path}")
                }
                copyIntoReservedDestination(child, childDestination)
            }
    }

    @Throws(FileNotFoundException::class)
    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val oldFile = fileFromDocId(sourceDocumentId)
        requireNonRootDocument(oldFile, sourceDocumentId)
        val targetParent = fileFromDocId(targetParentDocumentId)
        if (oldFile.isDirectory && isSameOrDescendant(targetParent, oldFile)) {
            throw FileNotFoundException("copyDocument id=$sourceDocumentId into itself is not allowed")
        }
        val newFile = try {
            copyDocumentAtomically(
                oldFile,
                targetParent,
                copy = ::copyIntoReservedDestination,
            )
        } catch (e: Exception) {
            throw FileNotFoundException(
                "copyDocument id=$sourceDocumentId failed: ${e.message}"
            ).apply { initCause(e) }
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun renameDocument(documentId: String, displayName: String): String {
        val oldFile = fileFromDocId(documentId)
        requireNonRootDocument(oldFile, documentId)
        val newFile = oldFile.resolveSibling(safeDocumentDisplayName(displayName))
        if (newFile.exists()) {
            throw FileNotFoundException("renameDocument id=$documentId to $displayName failed: target exists")
        }
        if (!oldFile.moveToWithoutReplacing(newFile)) {
            throw FileNotFoundException("renameDocument id=$documentId to $displayName failed")
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        val oldFile = fileFromDocId(sourceDocumentId)
        requireNonRootDocument(oldFile, sourceDocumentId)
        val sourceParent = fileFromDocId(sourceParentDocumentId)
        if (oldFile.parentFile != sourceParent) {
            throw FileNotFoundException(
                "moveDocument id=$sourceDocumentId is not a child of $sourceParentDocumentId"
            )
        }
        val targetParent = fileFromDocId(targetParentDocumentId)
        if (oldFile.isDirectory && isSameOrDescendant(targetParent, oldFile)) {
            throw FileNotFoundException("moveDocument id=$sourceDocumentId into itself is not allowed")
        }
        if (sourceParent == targetParent) return oldFile.docId
        val newFile = try {
            claimDocumentDestination(targetParent, oldFile.name, oldFile.isDirectory) { destination ->
                oldFile.moveToWithoutReplacing(destination)
            }
        } catch (e: Exception) {
            throw FileNotFoundException(
                "moveDocument id=$sourceDocumentId to $targetParentDocumentId failed: ${e.message}"
            ).apply { initCause(e) }
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<String>?
    ) = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
        val q = query.lowercase()
        fileFromDocId(rootId).walkTopDown()
            .onEnter { file ->
                isSafeDocumentPath(file) && !isDocumentStagingFileName(file.name)
            }
            .filter { file ->
                isSafeDocumentPath(file) &&
                        !isDocumentStagingFileName(file.name) &&
                        file.name.lowercase().contains(q)
            }
            .take(SEARCH_RESULTS_LIMIT)
            .forEach { newRowFromFile(it) }
    }

    private val File.mimeType: String
        get() = when {
            isDirectory -> Document.MIME_TYPE_DIR
            TEXT_EXTENSIONS.contains(extension) -> MIME_TYPE_TEXT
            textFilePaths.contains(absolutePath) -> MIME_TYPE_TEXT
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: MIME_TYPE_BIN
        }

    @Throws(FileNotFoundException::class)
    private fun MatrixCursor.newRowFromFile(file: File) {
        if (!file.exists()) {
            throw FileNotFoundException("File(path=${file.absolutePath}) not found")
        }

        val mimeType = file.mimeType
        var flags =
            if (file != baseDir && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Document.FLAG_SUPPORTS_COPY
            } else {
                0
            }
        if (file.canWrite()) {
            flags = flags or if (file.isDirectory) {
                Document.FLAG_DIR_SUPPORTS_CREATE
            } else {
                Document.FLAG_SUPPORTS_WRITE
            }
        }
        if (file != baseDir && file.parentFile?.canWrite() == true) {
            flags = flags or
                    Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                flags = flags or Document.FLAG_SUPPORTS_MOVE
            }
        }
        if (mimeType.startsWith("image/")) {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }

        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.docId)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, file.length())
        }
    }
}
