package com.poc.camera.compare

import android.net.Uri

/**
 * MediaStore URIs for the most recent processed/reference capture pair saved this
 * session. Held only in memory (see [com.poc.camera.MainActivity]) - not persisted
 * beyond a `rememberSaveable` config-change survivor - so the Compare screen can
 * open instantly right after a burst instead of re-querying MediaStore.
 */
data class ComparePair(
    val processedUri: Uri,
    val referenceUri: Uri,
)
