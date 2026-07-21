package com.poc.camera.camera

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn viewfinder glyphs (issue #82). material-icons-core - this project's only icon
 * dependency, kept deliberately narrow so the extended set's ~1 MB isn't pulled in for a
 * handful of camera glyphs - ships none of these (flash/torch/flip/compare/burst/LUT), and
 * its `Settings` gear is a *filled* silhouette that would clash with an otherwise stroked
 * set. So every action icon on the camera chrome is drawn here in ONE consistent stroke
 * language instead: a 24dp viewport, a single 2dp path weight, round caps and round joins,
 * and the same optical size, so they read as one family at the 22dp they render at over the
 * viewfinder scrim (see [ChromeTokens.IconSize]). Pure path data, no runtime state - the
 * tint is supplied by the caller ([ActionIconButton] passes white / the active-state dark),
 * so each vector is built once as a lazy `val` and reused.
 */
object CameraGlyphs {

    /** Flash off: the shared bolt plus a diagonal slash. */
    val FlashOff: ImageVector by lazy {
        strokedIcon("FlashOff") {
            bolt()
            moveTo(5f, 6f)
            lineTo(19f, 18f)
        }
    }

    /** Flash auto: the shared bolt plus a small "A" mark in the lower-right. */
    val FlashAuto: ImageVector by lazy {
        strokedIcon("FlashAuto") {
            bolt()
            // Small "A": apex, two legs, crossbar - sits in the free area right of the bolt.
            moveTo(14f, 20f)
            lineTo(16f, 14f)
            lineTo(18f, 20f)
            moveTo(14.8f, 18f)
            lineTo(17.2f, 18f)
        }
    }

    /** Flash on: the shared bolt, plain. */
    val FlashOn: ImageVector by lazy {
        strokedIcon("FlashOn") {
            bolt()
        }
    }

    /** Torch: a flashlight head/body with three short light rays above it. */
    val Torch: ImageVector by lazy {
        strokedIcon("Torch") {
            // Head (trapezoid) then tapered body.
            moveTo(9f, 8f)
            lineTo(15f, 8f)
            lineTo(14f, 11f)
            lineTo(10f, 11f)
            close()
            moveTo(10f, 11f)
            lineTo(14f, 11f)
            lineTo(13.3f, 20f)
            lineTo(10.7f, 20f)
            close()
            // Three rays.
            moveTo(12f, 3f)
            lineTo(12f, 5f)
            moveTo(8f, 4f)
            lineTo(9.3f, 5.3f)
            moveTo(16f, 4f)
            lineTo(14.7f, 5.3f)
        }
    }

    /** Flip camera: a centre lens ringed by two curved arrows pointing opposite ways. */
    val FlipCamera: ImageVector by lazy {
        strokedIcon("FlipCamera") {
            // Lens (full circle from two half-arcs).
            moveTo(9f, 12f)
            arcTo(3f, 3f, 0f, false, true, 15f, 12f)
            arcTo(3f, 3f, 0f, false, true, 9f, 12f)
            // Top arrow arc bowing up, arrowhead pointing down at the right end.
            moveTo(5.5f, 10f)
            curveTo(5.5f, 4f, 18.5f, 4f, 18.5f, 10f)
            moveTo(16.7f, 8.8f)
            lineTo(18.5f, 10f)
            lineTo(20f, 8.8f)
            // Bottom arrow arc bowing down, arrowhead pointing up at the left end.
            moveTo(18.5f, 14f)
            curveTo(18.5f, 20f, 5.5f, 20f, 5.5f, 14f)
            moveTo(3.9f, 15.2f)
            lineTo(5.5f, 14f)
            lineTo(7.3f, 15.2f)
        }
    }

    /** Compare: a square split down the middle (before / after). */
    val Compare: ImageVector by lazy {
        strokedIcon("Compare") {
            moveTo(6f, 5f)
            lineTo(18f, 5f)
            lineTo(18f, 19f)
            lineTo(6f, 19f)
            close()
            moveTo(12f, 5f)
            lineTo(12f, 19f)
        }
    }

    /** Burst: a front frame with a second frame peeking behind it (top and right edges). */
    val Burst: ImageVector by lazy {
        strokedIcon("Burst") {
            // Frame behind: top and right edges only.
            moveTo(8f, 5f)
            lineTo(19f, 5f)
            lineTo(19f, 16f)
            // Frame in front: full square.
            moveTo(5f, 8f)
            lineTo(16f, 8f)
            lineTo(16f, 19f)
            lineTo(5f, 19f)
            close()
        }
    }

    /** Look / LUT: a droplet of colour. */
    val Look: ImageVector by lazy {
        strokedIcon("Look") {
            moveTo(12f, 4f)
            curveTo(12f, 4f, 18f, 11f, 18f, 14.5f)
            curveTo(18f, 18f, 15.3f, 20.5f, 12f, 20.5f)
            curveTo(8.7f, 20.5f, 6f, 18f, 6f, 14.5f)
            curveTo(6f, 11f, 12f, 4f, 12f, 4f)
            close()
        }
    }

    /** Settings: a cog - a ring with eight teeth around a small hub. */
    val Settings: ImageVector by lazy {
        strokedIcon("Settings") {
            // Ring.
            moveTo(7f, 12f)
            arcTo(5f, 5f, 0f, false, true, 17f, 12f)
            arcTo(5f, 5f, 0f, false, true, 7f, 12f)
            // Hub.
            moveTo(10f, 12f)
            arcTo(2f, 2f, 0f, false, true, 14f, 12f)
            arcTo(2f, 2f, 0f, false, true, 10f, 12f)
            // Eight teeth, radial from the ring outward.
            moveTo(17f, 12f); lineTo(19.5f, 12f)
            moveTo(15.54f, 15.54f); lineTo(17.3f, 17.3f)
            moveTo(12f, 17f); lineTo(12f, 19.5f)
            moveTo(8.46f, 15.54f); lineTo(6.7f, 17.3f)
            moveTo(7f, 12f); lineTo(4.5f, 12f)
            moveTo(8.46f, 8.46f); lineTo(6.7f, 6.7f)
            moveTo(12f, 7f); lineTo(12f, 4.5f)
            moveTo(15.54f, 8.46f); lineTo(17.3f, 6.7f)
        }
    }

    /** The shared lightning bolt underpinning the three flash glyphs. */
    private fun PathBuilder.bolt() {
        moveTo(13f, 3f)
        lineTo(7f, 13f)
        lineTo(11f, 13f)
        lineTo(11f, 21f)
        lineTo(17f, 11f)
        lineTo(13f, 11f)
        close()
    }
}

/**
 * Builds one stroked [ImageVector] in the shared glyph language: 24dp square viewport, 2dp
 * stroke, round caps and joins, black source colour so [androidx.compose.material3.Icon]'s
 * tint recolours it at the call site. [pathBuilder] may declare several sub-paths (each a
 * fresh `moveTo`), so a glyph can combine open strokes and closed outlines at one weight.
 */
private fun strokedIcon(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathBuilder,
        )
        .build()
