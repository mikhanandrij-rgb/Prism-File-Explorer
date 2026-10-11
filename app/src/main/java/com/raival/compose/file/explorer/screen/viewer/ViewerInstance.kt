package com.raival.compose.file.explorer.screen.viewer

import android.net.Uri
import android.os.Bundle

interface ViewerInstance {
    val uri: Uri
    val id: String

    /**
     * When true, the instance (and any player it owns) is kept alive while its activity is
     * recreated for a configuration change, and the recreated activity reuses it. The UI must
     * then not release the instance from composition; [onClose] is called only when the viewer
     * is really finished.
     */
    val retainAcrossRecreation: Boolean get() = false

    /** Save what is needed to rebuild the viewer after process death. */
    fun onSaveState(outState: Bundle) {}

    /** Called on a freshly created instance when the activity is restored after process death. */
    fun onRestoreState(savedState: Bundle) {}

    fun onClose()
}
