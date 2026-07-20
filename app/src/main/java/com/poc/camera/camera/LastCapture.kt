package com.poc.camera.camera

import android.net.Uri

/**
 * Most recent successful capture this session (issue #74) - the source for the small
 * tappable thumbnail shown in a corner of the viewfinder. Held only in memory in
 * CameraScreen (survives rotation via `rememberSaveable`, like [com.poc.camera.compare.ComparePair]
 * in MainActivity) and never persisted to prefs/disk, so a fresh process start always
 * begins with the thumbnail absent until the next capture - matching the "no placeholder
 * before first capture" UI contract.
 */
data class LastCapture(val uri: Uri, val mediaType: CaptureMediaType)

enum class CaptureMediaType {
    Photo,
    Video,
}
