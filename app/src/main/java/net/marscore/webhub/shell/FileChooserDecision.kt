package net.marscore.webhub.shell

import android.content.Intent

/**
 * Pure decision layer for `WebAppActivity.ChromeClient.onShowFileChooser`.
 *
 * OpenSpec improve-webapp-shell-ux task 3.2 / design D5.
 *
 * `onShowFileChooser` does three things: normalize accept types, decide
 * whether to go down the camera-capture path or the plain content-picker
 * path, and build the matching Intent. The decision ("capture? camera
 * granted? capture Intent buildable?") is pure and extracted here so it is
 * unit-testable without standing up the full Activity (which needs a ChildApp
 * in the DB and a real WebView — fragile under Robolectric).
 *
 * Returns a [Decision] telling the caller which Intent to launch and which
 * permission flow to enter. The caller is still responsible for
 * `startActivityForResult`, `ValueCallback` lifecycle, and runtime
 * permission requests.
 */
internal object FileChooserDecision {

    /**
     * @param isCapture          `fileChooserParams.isCaptureEnabled()`.
     * @param isCameraGranted    whether `CAMERA` permission is already granted.
     * @param acceptTypes        raw `fileChooserParams.acceptTypes` (will be normalized).
     * @param isMultiple         `fileChooserParams.isModeOpenMultiple`.
     * @return a [Decision] describing the Intent to launch.
     */
    fun chooseIntent(
        isCapture: Boolean,
        isCameraGranted: Boolean,
        acceptTypes: Array<String>,
        isMultiple: Boolean,
    ): Decision {
        val normalized = FileChooserHandler.normalizeAcceptTypes(acceptTypes)
        if (isCapture && isCameraGranted) {
            val capture = FileChooserHandler.buildCaptureIntent(normalized)
            if (capture != null) {
                return Decision(capture, needsCameraPermission = false, normalized)
            }
        }
        return Decision(
            FileChooserHandler.buildContentIntent(normalized, isMultiple),
            needsCameraPermission = false,
            normalized,
        )
    }

    /**
     * When `capture` is requested but `CAMERA` isn't granted yet, the caller
     * must first request permission; this computes whether the capture path is
     * even reachable for the given accept types (so the caller can decide
     * whether to bother asking for the permission).
     */
    fun isCaptureReachable(acceptTypes: Array<String>): Boolean {
        val normalized = FileChooserHandler.normalizeAcceptTypes(acceptTypes)
        return FileChooserHandler.buildCaptureIntent(normalized) != null
    }

    /** Result of [chooseIntent]. */
    data class Decision(
        val intent: Intent,
        /** True when the caller should request CAMERA before launching (capture intent w/o permission). */
        val needsCameraPermission: Boolean,
        /** The normalized accept types, kept for the permission-denied fallback path. */
        val normalizedAcceptTypes: Array<String>,
    )
}