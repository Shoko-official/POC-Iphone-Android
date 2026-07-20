package com.poc.camera.compare

import android.net.Uri

/**
 * MediaStore URIs for the most recent processed/reference capture pair saved this
 * session. Held only in memory (see [com.poc.camera.MainActivity]) - not persisted
 * beyond a `rememberSaveable` config-change survivor - so the Compare screen can
 * open instantly right after a burst instead of re-querying MediaStore.
 *
 * [referenceUri] is null right after a guided-comparison capture, before the user has
 * picked the matching reference photo (see [GuidedCompareStep.AwaitingReference]); the
 * Compare screen's empty reference state covers that case the same way it already
 * covers "no reference loaded yet".
 */
data class ComparePair(
    val processedUri: Uri,
    val referenceUri: Uri?,
)
