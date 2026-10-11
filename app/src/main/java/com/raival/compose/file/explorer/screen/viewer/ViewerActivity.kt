package com.raival.compose.file.explorer.screen.viewer

import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import com.raival.compose.file.explorer.App.Companion.globalClass
import com.raival.compose.file.explorer.base.BaseActivity
import com.raival.compose.file.explorer.common.emptyString
import com.raival.compose.file.explorer.common.randomString

abstract class ViewerActivity : BaseActivity() {
    private var uri: Uri? = null
    private var uid: String = emptyString
    private var currentInstance: ViewerInstance? = null

    abstract fun onCreateNewInstance(uri: Uri, uid: String): ViewerInstance
    abstract fun onReady(instance: ViewerInstance)

    protected val viewerInstance: ViewerInstance? get() = currentInstance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        if (intent.data == null) {
            finish()
            return
        }

        uri = intent.data

        if (savedInstanceState != null) {
            uid = savedInstanceState.getString(KEY_UID, String.randomString(12))
        }

        if (uid.isEmpty()) {
            uid = String.randomString(12)
        }

        // Reuse the instance kept alive across a configuration change (same uid),
        // otherwise create a new one and restore it from the saved state if the
        // process was killed in the meantime.
        val existing = globalClass.viewersManager.instances.find { it.id == uid }
        currentInstance = existing ?: onCreateNewInstance(uri!!, uid).also { instance ->
            savedInstanceState?.getBundle(KEY_STATE)?.let { instance.onRestoreState(it) }
            globalClass.viewersManager.instances.add(instance)
        }

        checkPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_UID, uid)
        currentInstance?.let { instance ->
            outState.putBundle(KEY_STATE, Bundle().also { instance.onSaveState(it) })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val instance = currentInstance ?: return
        if (isChangingConfigurations && instance.retainAcrossRecreation) {
            // The recreated activity picks the instance up again by its uid.
            return
        }
        instance.onClose()
        globalClass.viewersManager.instances.remove(instance)
    }

    override fun onPermissionGranted() {
        currentInstance?.let { onReady(it) } ?: finish()
    }

    private companion object {
        const val KEY_UID = "uid"
        const val KEY_STATE = "viewer_state"
    }
}
