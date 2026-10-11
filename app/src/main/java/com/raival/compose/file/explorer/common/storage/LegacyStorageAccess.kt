package com.raival.compose.file.explorer.common.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import com.raival.compose.file.explorer.App.Companion.globalClass
import java.io.File

/**
 * Write access to removable volumes (SD card, USB OTG) on Android 8–10.
 *
 * Before Android 11 there is no "all files access": apps can read a removable volume with
 * READ_EXTERNAL_STORAGE but java.io writes to it fail. The only supported way to write is the
 * Storage Access Framework with a tree URI the user granted for the volume root. This object
 * finds the volume of a path, keeps track of persisted grants and maps paths to document URIs.
 *
 * On Android 11+ (MANAGE_EXTERNAL_STORAGE) none of this is used.
 */
object LegacyStorageAccess {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    val isApplicable: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    data class Volume(val uuid: String, val root: File, val storageVolume: StorageVolume)

    /** The removable (non-primary) volume containing [file], or null. */
    fun volumeOf(file: File, context: Context = globalClass): Volume? {
        if (!isApplicable) return null
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val path = file.absolutePath
        if (path == primary || path.startsWith("$primary/")) return null

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val storageVolume = runCatching { storageManager.getStorageVolume(file) }.getOrNull()
            ?: return null
        if (storageVolume.isPrimary) return null
        val uuid = storageVolume.uuid ?: return null
        val root = File("/storage/$uuid")
        if (path != root.absolutePath && !path.startsWith(root.absolutePath + "/")) return null
        return Volume(uuid, root, storageVolume)
    }

    /** Persisted tree URI granted for the root of [volume], if any. */
    fun grantedTreeUri(volume: Volume, context: Context = globalClass): Uri? {
        return context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isWritePermission && it.uri.authority == EXTERNAL_STORAGE_AUTHORITY }
            .map { it.uri }
            .firstOrNull { uri ->
                runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() == "${volume.uuid}:"
            }
    }

    /** True when writing to [file] needs a SAF grant the user has not given yet. */
    fun needsGrant(file: File): Boolean {
        val volume = volumeOf(file) ?: return false
        return grantedTreeUri(volume) == null && !canWriteDirectly(volume.root)
    }

    /** Document URI for [file] inside the granted tree of its volume, or null. */
    fun documentUri(file: File): Uri? {
        val volume = volumeOf(file) ?: return null
        val tree = grantedTreeUri(volume) ?: return null
        val relative = file.absolutePath.removePrefix(volume.root.absolutePath).trimStart('/')
        return DocumentsContract.buildDocumentUriUsingTree(tree, "${volume.uuid}:$relative")
    }

    /** Intent that asks the user to grant access to the root of [volume]. */
    fun createGrantIntent(volume: Volume): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            volume.storageVolume.createOpenDocumentTreeIntent()
        } else {
            @Suppress("DEPRECATION")
            volume.storageVolume.createAccessIntent(null)
                ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }

    /**
     * Persists a tree URI returned by the picker. Returns false if the user picked something
     * other than the root of [volume].
     */
    fun persistGrant(volume: Volume, treeUri: Uri, context: Context = globalClass): Boolean {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY || docId != "${volume.uuid}:") {
            return false
        }
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        return true
    }

    private fun canWriteDirectly(dir: File): Boolean {
        val probe = File(dir, ".facet_write_probe_${System.nanoTime()}")
        return runCatching { probe.createNewFile().also { if (it) probe.delete() } }
            .getOrDefault(false)
    }
}
