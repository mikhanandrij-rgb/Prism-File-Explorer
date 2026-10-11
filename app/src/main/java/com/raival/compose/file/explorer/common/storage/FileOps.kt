package com.raival.compose.file.explorer.common.storage

import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.raival.compose.file.explorer.App.Companion.globalClass
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** Thrown when a write needs SD-card access the user has not granted yet (Android 8–10). */
class StorageAccessRequiredException(val volume: LegacyStorageAccess.Volume) :
    IOException("Write access to ${volume.root} has not been granted")

/**
 * Write operations on local files that also work on removable volumes before Android 11.
 *
 * Every call tries java.io first (internal storage, Android 11+, rooted or permissive devices).
 * Only when that fails on a removable volume on Android 8–10 does it go through the Storage
 * Access Framework with the tree the user granted for that volume.
 */
object FileOps {
    private val resolver get() = globalClass.contentResolver

    fun mkdirs(dir: File): Boolean {
        if (dir.isDirectory || dir.mkdirs()) return true
        if (!usesSaf(dir)) return false
        val parent = dir.parentFile ?: return false
        if (!mkdirs(parent)) return false
        if (dir.isDirectory) return true
        val parentUri = documentUriOrThrow(parent)
        return runCatching {
            DocumentsContract.createDocument(
                resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, dir.name
            ) != null
        }.getOrDefault(false)
    }

    fun createFile(file: File): Boolean {
        val created = runCatching { file.createNewFile() }.getOrDefault(false)
        if (created) return true
        if (file.exists() || !usesSaf(file)) return false
        val parent = file.parentFile ?: return false
        if (!mkdirs(parent)) return false
        return createDocument(parent, file.name) != null
    }

    /** Deletes a file or a directory with its content. */
    fun delete(file: File): Boolean {
        if (!file.exists()) return true
        if (file.deleteRecursively()) return true
        if (!usesSaf(file)) return false
        // DocumentsContract deletes directories recursively.
        val uri = documentUriOrThrow(file)
        return runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
                || !file.exists()
    }

    /**
     * Renames [file] to [target]. Within one directory this also works through SAF; moving
     * between directories on a SAF-only volume returns false so callers can fall back to
     * copy + delete.
     */
    fun rename(file: File, target: File): Boolean {
        if (file.renameTo(target)) return true
        if (!usesSaf(file) || file.parentFile?.absolutePath != target.parentFile?.absolutePath) {
            return false
        }
        val uri = documentUriOrThrow(file)
        return runCatching { DocumentsContract.renameDocument(resolver, uri, target.name) != null }
            .getOrDefault(false) && target.exists()
    }

    /** Opens [file] for writing (truncating, or appending when [append]). Creates it if needed. */
    fun outputStream(file: File, append: Boolean = false): OutputStream {
        try {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            return java.io.FileOutputStream(file, append)
        } catch (e: IOException) {
            if (!usesSaf(file)) throw e
        } catch (e: SecurityException) {
            if (!usesSaf(file)) throw e
        }
        val parent = file.parentFile ?: throw FileNotFoundException(file.path)
        if (!mkdirs(parent)) throw FileNotFoundException("Cannot create ${parent.path}")
        val uri = if (file.exists()) documentUriOrThrow(file) else {
            createDocument(parent, file.name) ?: throw FileNotFoundException(file.path)
        }
        return resolver.openOutputStream(uri, if (append) "wa" else "wt")
            ?: throw FileNotFoundException(file.path)
    }

    fun writeText(file: File, text: String) {
        outputStream(file).bufferedWriter().use { it.write(text) }
    }

    /** Copies a single file. */
    fun copyFile(source: File, target: File, overwrite: Boolean) {
        if (target.exists()) {
            if (!overwrite) throw FileAlreadyExistsException(target)
            if (target.isDirectory && !delete(target)) throw IOException("Cannot replace $target")
        }
        source.inputStream().use { input ->
            outputStream(target).use { output -> input.copyTo(output) }
        }
        target.setLastModified(source.lastModified())
    }

    /** True if [file] is on a removable volume that must be written through SAF. */
    fun usesSaf(file: File): Boolean = LegacyStorageAccess.volumeOf(file) != null

    private fun documentUriOrThrow(file: File): Uri {
        LegacyStorageAccess.documentUri(file)?.let { return it }
        val volume = LegacyStorageAccess.volumeOf(file) ?: throw FileNotFoundException(file.path)
        throw StorageAccessRequiredException(volume)
    }

    private fun createDocument(parent: File, name: String): Uri? {
        val parentUri = documentUriOrThrow(parent)
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val uri = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mime, name)
        }.getOrNull() ?: return null
        // Some providers append an extension for known MIME types; keep the exact name.
        val created = File(parent, name)
        if (!created.exists()) {
            runCatching { DocumentsContract.renameDocument(resolver, uri, name) }
        }
        return LegacyStorageAccess.documentUri(created) ?: uri
    }
}
